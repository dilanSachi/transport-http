/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.transport.http.netty.connectionpool;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.carbon.messaging.exceptions.ServerConnectorException;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.config.TransportsConfiguration;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contractimpl.HttpWsConnectorFactoryImpl;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.transport.http.netty.message.HTTPConnectorUtil;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.util.HTTPConnectorListener;
import org.wso2.transport.http.netty.util.TestUtil;
import org.wso2.transport.http.netty.util.server.HttpServer;
import org.wso2.transport.http.netty.util.server.initializers.SendChannelIDServerInitializer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.testng.Assert.assertFalse;

/**
 * Tests for connection pool implementation.
 */
public class ConnectionPoolEvictionTestCase {

    private HttpServer httpServer;
    private HttpClientConnector httpClientConnector;

    @BeforeClass
    public void setup() {
        TransportsConfiguration transportsConfiguration = TestUtil.getConfiguration(
                "/simple-test-config" + File.separator + "netty-transports.yml");

        Map<String, Object> transportProperties = HTTPConnectorUtil.getTransportProperties(transportsConfiguration);
        transportProperties.put(Constants.MIN_EVICTION_IDLE_TIME, 2000);
        transportProperties.put(Constants.TIME_BETWEEN_EVICTION_RUNS, 1000);

        httpServer = TestUtil.startHTTPServer(TestUtil.HTTP_SERVER_PORT, new SendChannelIDServerInitializer(0));

        HttpWsConnectorFactory connectorFactory = new HttpWsConnectorFactoryImpl();

        httpClientConnector = connectorFactory.createHttpClientConnector(transportProperties,
                HTTPConnectorUtil.getSenderConfiguration(transportsConfiguration, Constants.HTTP_SCHEME));
    }

    @Test
    public void testConnectionEviction() {
        try {
            CountDownLatch requestLatchOne = new CountDownLatch(1);
            CountDownLatch requestLatchTwo = new CountDownLatch(1);

            HTTPConnectorListener responseListener;

            responseListener = sendRequestAsync(requestLatchOne);
            String responseOne = waitAndGetStringEntity(requestLatchOne, responseListener);

            // wait till the eviction occurs
            Thread.sleep(5000);
            responseListener = sendRequestAsync(requestLatchTwo);
            String responseTwo = waitAndGetStringEntity(requestLatchTwo, responseListener);

            assertFalse(responseOne.equals(responseTwo));
        } catch (Exception e) {
            TestUtil.handleException("IOException occurred while running testConnectionReuseForMain", e);
        }
    }

    private String waitAndGetStringEntity(CountDownLatch latch, HTTPConnectorListener responseListener)
            throws InterruptedException {
        String response;
        latch.await(10, TimeUnit.SECONDS);
        HTTPCarbonMessage httpResponse = responseListener.getHttpResponseMessage();
        response = new BufferedReader(new InputStreamReader(new HttpMessageDataStreamer(httpResponse)
                .getInputStream()))
                .lines().collect(Collectors.joining("\n"));
        return response;
    }

    private HTTPConnectorListener sendRequestAsync(CountDownLatch latch) {
        HTTPCarbonMessage httpsPostReq = TestUtil.
                createHttpsPostReq(TestUtil.HTTP_SERVER_PORT, "hello", "/");
        HTTPConnectorListener requestListener = new HTTPConnectorListener(latch);
        HttpResponseFuture responseFuture = httpClientConnector.send(httpsPostReq);
        responseFuture.setHttpConnectorListener(requestListener);
        return requestListener;
    }

    @AfterClass
    public void cleanUp() throws ServerConnectorException {
        TestUtil.cleanUp(new ArrayList<>(), httpServer);
    }
}
