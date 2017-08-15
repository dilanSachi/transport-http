/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.carbon.transport.http.netty.contractimpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.messaging.exceptions.ClientConnectorException;
import org.wso2.carbon.messaging.exceptions.MessagingException;
import org.wso2.carbon.transport.http.netty.common.Constants;
import org.wso2.carbon.transport.http.netty.common.HttpRoute;
import org.wso2.carbon.transport.http.netty.common.Util;
import org.wso2.carbon.transport.http.netty.common.ssl.SSLConfig;
import org.wso2.carbon.transport.http.netty.contract.HTTPClientConnector;
import org.wso2.carbon.transport.http.netty.contract.HTTPClientConnectorFuture;
import org.wso2.carbon.transport.http.netty.listener.SourceHandler;
import org.wso2.carbon.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.carbon.transport.http.netty.sender.channel.TargetChannel;
import org.wso2.carbon.transport.http.netty.sender.channel.pool.ConnectionManager;

import java.util.Locale;

/**
 * Implementation of the client connector.
 */
public class HTTPClientConnectorImpl implements HTTPClientConnector {

    private static final Logger log = LoggerFactory.getLogger(HTTPClientConnector.class);

    private ConnectionManager connectionManager;
    private SSLConfig sslConfig;
    private int socketIdleTimeout;

    public HTTPClientConnectorImpl(ConnectionManager connectionManager, SSLConfig sslConfig, int socketIdleTimeout) {
        this.connectionManager = connectionManager;
        this.sslConfig = sslConfig;
        this.socketIdleTimeout = socketIdleTimeout;
    }

    @Override
    public HTTPClientConnectorFuture connect() {
        return null;
    }

    @Override
    public HTTPClientConnectorFuture send(HTTPCarbonMessage httpCarbonRequest) throws Exception {
        HTTPClientConnectorFuture httpClientConnectorFuture = new HTTPClientConnectorFutureImpl();

        SourceHandler srcHandler = (SourceHandler) httpCarbonRequest.getProperty(Constants.SRC_HANDLER);
        if (srcHandler == null) {
            if (log.isDebugEnabled()) {
                log.debug(Constants.SRC_HANDLER + " property not found in the message."
                        + " Message is not originated from the HTTP Server connector");
            }
        }

        try {
            final HttpRoute route = getTargetRoute(httpCarbonRequest);
            TargetChannel targetChannel = connectionManager.borrowTargetChannel(route, srcHandler, sslConfig);
            targetChannel.getChannel().eventLoop().execute(() -> {

                Util.prepareBuiltMessageForTransfer(httpCarbonRequest);
                Util.setupTransferEncodingForRequest(httpCarbonRequest);

                targetChannel.configure(httpCarbonRequest, srcHandler);
                targetChannel.setEndPointTimeout(socketIdleTimeout);

                try {
                    targetChannel.writeContent(httpCarbonRequest);
                } catch (Exception e) {
                    String msg = "Failed to send the request : " + e.getMessage().toLowerCase(Locale.ENGLISH);
                    log.error(msg, e);
                    MessagingException messagingException = new MessagingException(msg, e, 101500);
                    httpCarbonRequest.setMessagingException(messagingException);
                    //                        httpClientConnectorFuture.notifyHTTPListener(httpCarbonMessage);
                    httpCarbonRequest.getResponseListener().onMessage(httpCarbonRequest);
                }
            });
        } catch (Exception failedCause) {
            throw new ClientConnectorException(failedCause.getMessage(), failedCause);
        }

        return httpClientConnectorFuture;
    }

    @Override
    public boolean close() {
        return false;
    }

    private HttpRoute getTargetRoute(HTTPCarbonMessage httpCarbonMessage) {
        // Fetch Host
        String host;
        Object hostProperty = httpCarbonMessage.getProperty(Constants.HOST);
        if (hostProperty != null && hostProperty instanceof String) {
            host = (String) hostProperty;
        } else {
            host = Constants.LOCALHOST;
            httpCarbonMessage.setProperty(Constants.HOST, Constants.LOCALHOST);
            log.debug("Cannot find property HOST of type string, hence using localhost as the host");
        }

        // Fetch Port
        int port;
        Object intProperty = httpCarbonMessage.getProperty(Constants.PORT);
        if (intProperty != null && intProperty instanceof Integer) {
            port = (int) intProperty;
        } else {
            port = sslConfig != null ?
                    Constants.DEFAULT_HTTPS_PORT : Constants.DEFAULT_HTTP_PORT;
            httpCarbonMessage.setProperty(Constants.PORT, port);
            log.debug("Cannot find property PORT of type integer, hence using " + port);
        }

        return new HttpRoute(host, port);
    }
}
