/*
 * Copyright (c) 2018 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.stdlib.tcp;

import io.ballerina.runtime.api.PredefinedTypes;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.TupleType;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BError;
import org.ballerinalang.stdlib.tcp.exceptions.SelectorInitializeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import static java.nio.channels.SelectionKey.OP_READ;
import static org.ballerinalang.stdlib.tcp.SocketConstants.DEFAULT_EXPECTED_READ_LENGTH;

/**
 * This will manage the Selector instance and handle the accept, read and write operations.
 *
 * @since 0.985.0
 */
public class SelectorManager {

    private static final Logger log = LoggerFactory.getLogger(SelectorManager.class);

    private Selector selector;
    private ThreadFactory threadFactory = new SocketThreadFactory("tcp-selector");
    private ExecutorService executor = null;
    private boolean running = false;
    private boolean executing = true;
    private ConcurrentLinkedQueue<ChannelRegisterCallback> registerPendingSockets = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Integer> readReadySockets = new ConcurrentLinkedQueue<>();
    private final Object startStopLock = new Object();
    private static final TupleType tcpReadResultTuple = TypeCreator.createTupleType(
            Arrays.asList(TypeCreator.createArrayType(PredefinedTypes.TYPE_BYTE), PredefinedTypes.TYPE_INT));

    private SelectorManager() throws IOException {
        selector = Selector.open();
    }

    /**
     * This will use to hold the SelectorManager singleton object.
     */
    private static class SelectorManagerHolder {
        private static SelectorManager manager;
        static {
            try {
                manager = new SelectorManager();
            } catch (IOException e) {
                throw new SelectorInitializeException("Unable to initialize the selector", e);
            }
        }
    }

    /**
     * This method will return SelectorManager singleton instance.
     *
     * @return {@link SelectorManager} instance
     * @throws SelectorInitializeException when unable to open a selector
     */
    public static SelectorManager getInstance() throws SelectorInitializeException {
        return SelectorManagerHolder.manager;
    }

    /**
     * Add channel to register pending tcp queue. Socket registration has to be happen in the same thread
     * that selector loop execute.
     *
     * @param callback A {@link ChannelRegisterCallback} instance which contains the resources,
     *                      packageInfo and A {@link SelectableChannel}.
     */
    public void registerChannel(ChannelRegisterCallback callback) {
        registerPendingSockets.add(callback);
        selector.wakeup();
    }

    /**
     * Unregister the given client channel from the selector instance.
     *
     * @param channel {@link SelectableChannel} that about to unregister.
     */
    public void unRegisterChannel(SelectableChannel channel) {
        final SelectionKey selectionKey = channel.keyFor(selector);
        if (selectionKey != null) {
            selectionKey.cancel();
        }
    }

    /**
     * Adding onReadReady finish notification to the queue and wakeup the selector.
     *
     * @param socketHashCode hashCode of the read ready tcp.
     */
    void invokePendingReadReadyResources(int socketHashCode) {
        readReadySockets.add(socketHashCode);
        selector.wakeup();
    }

    /**
     * Start the selector loop.
     */
    public void start() {
        synchronized (startStopLock) {
            if (running) {
                return;
            }
            if (executor == null || executor.isTerminated()) {
                executor = Executors.newSingleThreadExecutor(threadFactory);
            }
            running = true;
            executing = true;
            executor.execute(this::execute);
        }
    }

    private void execute() {
        while (executing) {
            try {
                registerChannels();
                invokeReadReadyResources();
                if (selector.select() == 0) {
                    continue;
                }
                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();
                    performAction(key);
                }
            } catch (Throwable e) {
                log.error("An error occurred in selector loop: " + e.getMessage(), e);
            }
        }
    }

    /*
    Channel registration has to be done in the same thread that selector loops runs.
     */
    private void registerChannels() {
        ChannelRegisterCallback channelRegisterCallback;
        while ((channelRegisterCallback = registerPendingSockets.poll()) != null) {
            SocketService socketService = channelRegisterCallback.getSocketService();
            try {
                socketService.getSocketChannel()
                        .register(selector, channelRegisterCallback.getInitialInterest(), socketService);
            } catch (ClosedChannelException e) {
                channelRegisterCallback.notifyFailure("tcp already closed");
                continue;
            }
            // Notification needs to happen to the client connection in the tcp server only if the client has
            // a callback service.
            boolean serviceAttached = (socketService.getService() != null
                    && channelRegisterCallback.getInitialInterest() == OP_READ);
            channelRegisterCallback.notifyRegister(serviceAttached);
        }
    }

    private void invokeReadReadyResources() {
        final Iterator<Integer> iterator = readReadySockets.iterator();
        while (iterator.hasNext()) {
            Integer socketHashCode = iterator.next();
            // Removing an entry from the readReadySockets queue is fine. This will cleanup the last entry that add due
            // execution of TCPSocketReadCallback.
            final SocketReader socketReader = ReadReadySocketMap.getInstance().get(socketHashCode);
            // SocketReader can be null if there is no new read ready notification.
            if (socketReader == null) {
                continue;
            }
            iterator.remove();
            final SocketService socketService = socketReader.getSocketService();
            invokeReadReadyResource(socketService);
        }
    }

    private void performAction(SelectionKey key) {
        if (!key.isValid()) {
            key.cancel();
        } else if (key.isAcceptable()) {
            onAccept(key);
        } else if (key.isReadable()) {
            onReadReady(key);
        }
    }

    private void onAccept(SelectionKey key) {
        SocketService socketService = (SocketService) key.attachment();
        ServerSocketChannel server = (ServerSocketChannel) socketService.getSocketChannel();
        try {
            SocketChannel client = server.accept();
            client.configureBlocking(false);
            // Creating a new SocketService instance with the newly accepted client.
            // We don't need the ServerSocketChannel in here since we have all the necessary resources.
            SocketService clientSocketService = new SocketService(client, socketService.getRuntime(),
                    socketService.getService(), socketService.getReadTimeout());
            // Registering the channel against the selector directly without going through the queue,
            // since we are in same thread.
            client.register(selector, OP_READ, clientSocketService);
            SelectorDispatcher.invokeOnConnect(clientSocketService);
        } catch (ClosedByInterruptException e) {
            SelectorDispatcher
                    .invokeOnError(new SocketService(socketService.getRuntime(), socketService.getService()),
                    "client accept interrupt by another process");
        } catch (AsynchronousCloseException e) {
            SelectorDispatcher
                    .invokeOnError(new SocketService(socketService.getRuntime(), socketService.getService()),
                            "client closed by another process");
        } catch (ClosedChannelException e) {
            SelectorDispatcher
                    .invokeOnError(new SocketService(socketService.getRuntime(), socketService.getService()),
                            "client is already closed");
        } catch (IOException e) {
            log.error("An error occurred while accepting new client", e);
            SelectorDispatcher
                    .invokeOnError(new SocketService(socketService.getRuntime(), socketService.getService()),
                            "unable to accept a new client. " +  e.getMessage());
        }
    }

    private void onReadReady(SelectionKey key) {
        SocketService socketService = (SocketService) key.attachment();
        // Remove further interest on future read ready requests until this one is served.
        // This will prevent the busy loop.
        key.interestOps(0);
        // Add to the read ready queue. The content will be read through the caller->read action.
        ReadReadySocketMap.getInstance().add(new SocketReader(socketService, key));
        invokeRead(key.channel().hashCode(), socketService.getService() != null);
    }

    /**
     * Perform the read operation for the given tcp. This will either read data from the tcp channel or dispatch
     * to the onReadReady resource if resource's lock available.
     *
     * @param socketHashId tcp hash id
     * @param clientServiceAttached whether client callback service attached or not
     */
    public void invokeRead(int socketHashId, boolean clientServiceAttached) {
        // Check whether there is any caller->read pending action and read ready tcp.
        ReadPendingSocketMap readPendingSocketMap = ReadPendingSocketMap.getInstance();
        if (readPendingSocketMap.isPending(socketHashId)) {
            // Lock the ReadPendingCallback instance. This will prevent duplicate invocation that happen from both
            // read action and selector manager sides.
            synchronized (readPendingSocketMap.get(socketHashId)) {
                ReadReadySocketMap readReadySocketMap = ReadReadySocketMap.getInstance();
                if (readReadySocketMap.isReadReady(socketHashId)) {
                    SocketReader socketReader = readReadySocketMap.remove(socketHashId);
                    ReadPendingCallback callback = readPendingSocketMap.remove(socketHashId);
                    // Read ready tcp available.
                    readTcpSocket(socketReader, callback);
                }
            }
            // If the read pending tcp not available then do nothing. Above will be invoked once read ready
            // tcp is connected.
        } else if (clientServiceAttached) {
            // No caller->read pending actions hence try to dispatch to onReadReady resource if read ready available.
            final SocketReader socketReader = ReadReadySocketMap.getInstance().get(socketHashId);
            invokeReadReadyResource(socketReader.getSocketService());
        }
    }

    private void readTcpSocket(SocketReader socketReader, ReadPendingCallback callback) {
        SocketChannel socketChannel = (SocketChannel) socketReader.getSocketService().getSocketChannel();
        try {
            ByteBuffer buffer = createBuffer(callback, socketChannel);
            int read = socketChannel.read(buffer);
            callback.resetTimeout();
            if (read < 0) {
                SelectorManager.getInstance().unRegisterChannel(socketChannel);
            } else {
                callback.updateCurrentLength(read);
                // Re-register for read ready events.
                socketReader.getSelectionKey().interestOps(OP_READ);
                selector.wakeup();
                if (callback.getBuffer() == null) {
                    callback.setBuffer(ByteBuffer.allocate(buffer.capacity()));
                }
                buffer.flip();
                callback.getBuffer().put(buffer);
                if (callback.getExpectedLength() != DEFAULT_EXPECTED_READ_LENGTH
                        && callback.getExpectedLength() != callback.getCurrentLength()) {
                    ReadPendingSocketMap.getInstance().add(socketChannel.hashCode(), callback);
                    invokeRead(socketChannel.hashCode(), socketReader.getSocketService().getService() != null);
                    return;
                }
            }
            byte[] bytes = SocketUtils
                    .getByteArrayFromByteBuffer(callback.getBuffer() == null ? buffer : callback.getBuffer());
            callback.getFuture().complete(createTcpSocketReturnValue(callback, bytes));
            callback.cancelTimeout();
        } catch (NotYetConnectedException e) {
            processError(callback, null, "connection not yet connected");
        } catch (CancelledKeyException | ClosedChannelException e) {
            processError(callback, null, "connection closed");
        } catch (IOException e) {
            log.error("Error while read.", e);
            processError(callback, null, e.getMessage());
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
            processError(callback, null, "error while on read operation");
        }
    }

    private void processError(ReadPendingCallback callback, SocketConstants.ErrorType type, String msg) {
        BError socketError =
                type == null ? SocketUtils.createSocketError(msg) : SocketUtils.createSocketError(type, msg);
        callback.getFuture().complete(socketError);
    }

    private BArray createTcpSocketReturnValue(ReadPendingCallback callback, byte[] bytes) {
        BArray contentTuple = ValueCreator.createTupleValue(tcpReadResultTuple);
        contentTuple.add(0, ValueCreator.createArrayValue(bytes));
        contentTuple.add(1, Long.valueOf(callback.getCurrentLength()));
        return contentTuple;
    }

    private ByteBuffer createBuffer(ReadPendingCallback callback, int osBufferSize) {
        ByteBuffer buffer;
        // If the length is not specified in the read action then create a byte buffer to match the size of
        // the receiver buffer.
        if (callback.getExpectedLength() == DEFAULT_EXPECTED_READ_LENGTH) {
            buffer = ByteBuffer.allocate(osBufferSize);
        } else {
            int newBufferSize = callback.getExpectedLength() - callback.getCurrentLength();
            buffer = ByteBuffer.allocate(newBufferSize);
        }
        return buffer;
    }

    private ByteBuffer createBuffer(ReadPendingCallback callback, SocketChannel socketChannel) throws SocketException {
        return createBuffer(callback, socketChannel.socket().getReceiveBufferSize());
    }

    private void invokeReadReadyResource(SocketService socketService) {
        // If lock is not available then already inside the resource.
        // If lock is available then invoke the resource dispatch.
        if (socketService.getResourceLock().tryAcquire()) {
            SelectorDispatcher.invokeReadReady(socketService);
        }
    }

    /**
     * Stop the selector loop.
     *
     * @param graceful whether to shutdown executor gracefully or not
     */
    public void stop(boolean graceful) {
        stop();
        try {
            if (graceful) {
                SocketUtils.shutdownExecutorGracefully(executor);
            } else {
                SocketUtils.shutdownExecutorImmediately(executor);
            }
        } catch (Exception e) {
            log.error("Error occurred while stopping the selector loop: " + e.getMessage(), e);
        }
    }

    private void stop() {
        synchronized (startStopLock) {
            executing = false;
            running = false;
            selector.wakeup();
        }
    }
}