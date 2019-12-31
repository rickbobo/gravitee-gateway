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

import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.http2.HttpFrame;
import io.gravitee.gateway.api.proxy.ProxyResponse;
import io.gravitee.gateway.http.connector.http.HttpProxyResponse;
import io.vertx.core.http.HttpClientResponse;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class Http2ProxyResponse extends HttpProxyResponse {

    private Handler<HttpFrame> customFrameHandler;

    Http2ProxyResponse(final HttpClientResponse httpClientResponse) {
        super(httpClientResponse);
    }

    @Override
    public ProxyResponse customFrameHandler(Handler<HttpFrame> frameHandler) {
        this.customFrameHandler = frameHandler;
        return this;
    }

    @Override
    public void writeCustomFrame(HttpFrame frame) {
        this.customFrameHandler.handle(frame);
    }
}
