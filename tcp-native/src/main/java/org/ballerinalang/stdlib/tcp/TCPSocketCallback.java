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

import io.ballerina.runtime.api.async.Callback;
import io.ballerina.runtime.api.values.BError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Socket callback for service resource execution.
 *
 * @since 0.985.0
 */
public class TCPSocketCallback implements Callback {

    private static final Logger log = LoggerFactory.getLogger(TCPSocketCallback.class);

    private SocketService socketService;
    private boolean executeFailureOnce = false;

    public TCPSocketCallback(SocketService socketService) {
        this.socketService = socketService;
    }

    public TCPSocketCallback(SocketService socketService, boolean executeFailureOnce) {
        this.socketService = socketService;
        this.executeFailureOnce = executeFailureOnce;
    }

    @Override
    public void notifySuccess(Object o) {
        log.debug("Socket resource dispatch succeed.");
    }

    @Override
    public void notifyFailure(BError error) {
        String errorMsg = error.stringValue(null);
        if (log.isDebugEnabled()) {
            log.debug(String.format("Socket resource dispatch failed: %s", errorMsg));
        }
        if (!executeFailureOnce) {
            // This is the first failure. Hence dispatching to onError.
            SelectorDispatcher.invokeOnError(socketService, new TCPSocketCallback(socketService, true),
                                             error);
        } else {
            log.error(
                    String.format("NotifyFailure hit twice, hence preventing error dispatching. Cause: %s", errorMsg));
        }

    }
}