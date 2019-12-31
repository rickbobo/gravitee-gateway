/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.gateway.http.connector.http2;

import io.gravitee.definition.model.endpoint.Http2Endpoint;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.http2.HttpFrame;
import io.gravitee.gateway.api.proxy.ProxyConnection;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.gateway.http.connector.http.HttpProxyConnection;
import io.vertx.core.http.HttpClientResponse;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class Http2ProxyConnection extends HttpProxyConnection<Http2ProxyResponse> {

    public Http2ProxyConnection(Http2Endpoint endpoint, ProxyRequest proxyRequest) {
        super(endpoint, proxyRequest);
    }

    @Override
    protected Http2ProxyResponse handleUpstreamResponse(HttpClientResponse clientResponse) {
        Http2ProxyResponse proxyResponse = super.handleUpstreamResponse(clientResponse);

        clientResponse.customFrameHandler(frame -> proxyResponse.writeCustomFrame(
                HttpFrame.create(frame.type(), frame.flags(), Buffer.buffer(frame.payload().getBytes()))));

        return proxyResponse;
    }

    @Override
    protected Http2ProxyResponse createProxyResponse(HttpClientResponse clientResponse) {
        return new Http2ProxyResponse(clientResponse);
    }

    protected void writeUpstreamHeaders() {
        // Copy headers to upstream
        proxyRequest.headers().forEach(httpClientRequest::putHeader);
    }

    @Override
    public ProxyConnection writeCustomFrame(HttpFrame frame) {
        httpClientRequest.writeCustomFrame(frame.type(), frame.flags(),
                io.vertx.core.buffer.Buffer.buffer(frame.payload().getBytes()));

        return this;
    }
}
