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
package io.gravitee.gateway.http.connector;

import io.gravitee.common.component.AbstractLifecycleComponent;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.definition.model.HttpClientSslOptions;
import io.gravitee.definition.model.HttpProxy;
import io.gravitee.definition.model.endpoint.HttpEndpoint;
import io.gravitee.definition.model.ssl.jks.JKSKeyStore;
import io.gravitee.definition.model.ssl.jks.JKSTrustStore;
import io.gravitee.definition.model.ssl.pem.PEMKeyStore;
import io.gravitee.definition.model.ssl.pem.PEMTrustStore;
import io.gravitee.definition.model.ssl.pkcs12.PKCS12KeyStore;
import io.gravitee.definition.model.ssl.pkcs12.PKCS12TrustStore;
import io.gravitee.gateway.api.Connector;
import io.gravitee.gateway.api.proxy.ProxyConnection;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.gateway.core.endpoint.EndpointException;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.net.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class AbstractConnector<T extends HttpEndpoint> extends AbstractLifecycleComponent<Connector> implements Connector {

    private final Logger LOGGER = LoggerFactory.getLogger(AbstractConnector.class);

    protected static final String HTTPS_SCHEME = "https";
    protected static final String WSS_SCHEME = "wss";
    protected static final String GRPCS_SCHEME = "grpcs";

    protected static final int DEFAULT_HTTP_PORT = 80;
    protected static final int DEFAULT_HTTPS_PORT = 443;

    @Autowired
    private Vertx vertx;

    protected final T endpoint;

    private HttpClientOptions options;

    @Autowired
    public AbstractConnector(T endpoint) {
        this.endpoint = endpoint;
    }

    private final Map<Context, HttpClient> httpClients = new HashMap<>();

    @Override
    public ProxyConnection request(ProxyRequest proxyRequest) {
        final URI uri = proxyRequest.uri();

        // Add the endpoint reference in metrics to know which endpoint has been invoked while serving the request
        proxyRequest.metrics().setEndpoint(uri.toString());

        final int port = uri.getPort() != -1 ? uri.getPort() :
                (HTTPS_SCHEME.equals(uri.getScheme()) || WSS_SCHEME.equals(uri.getScheme()) ? 443 : 80);

        final String host = (port == DEFAULT_HTTP_PORT || port == DEFAULT_HTTPS_PORT) ?
                uri.getHost() : uri.getHost() + ':' + port;

        proxyRequest.headers().set(HttpHeaders.HOST, host);

        // Enhance proxy request with endpoint configuration
        if (endpoint.getHeaders() != null && !endpoint.getHeaders().isEmpty()) {
            endpoint.getHeaders().forEach(proxyRequest.headers()::set);
        }

        // Create the connector to the upstream
        final AbstractHttpProxyConnection connection = create(proxyRequest);

        // Grab an instance of the HTTP client
        final HttpClient client = httpClients.computeIfAbsent(Vertx.currentContext(), createHttpClient());

        // Connect to the upstream
        return connection.connect(client, port, uri.getHost(),
                (uri.getRawQuery() == null) ? uri.getRawPath() : uri.getRawPath() + '?' + uri.getRawQuery());
    }

    protected abstract AbstractHttpProxyConnection create(ProxyRequest proxyRequest);

    @Override
    protected void doStart() throws Exception {
        this.options = this.getOptions();
        printHttpClientConfiguration();
    }

    protected HttpClientOptions getOptions() throws EndpointException {
        HttpClientOptions options = new HttpClientOptions();

        options.setPipelining(endpoint.getHttpClientOptions().isPipelining());
        options.setKeepAlive(endpoint.getHttpClientOptions().isKeepAlive());
        options.setIdleTimeout((int) (endpoint.getHttpClientOptions().getIdleTimeout() / 1000));
        options.setConnectTimeout((int) endpoint.getHttpClientOptions().getConnectTimeout());
        options.setUsePooledBuffers(true);
        options.setMaxPoolSize(endpoint.getHttpClientOptions().getMaxConcurrentConnections());
        options.setTryUseCompression(endpoint.getHttpClientOptions().isUseCompression());
        options.setLogActivity(true);

        // Configure proxy
        HttpProxy proxy = endpoint.getHttpProxy();
        if (proxy != null && proxy.isEnabled()) {
            ProxyOptions proxyOptions = new ProxyOptions();
            proxyOptions.setHost(proxy.getHost());
            proxyOptions.setPort(proxy.getPort());
            proxyOptions.setUsername(proxy.getUsername());
            proxyOptions.setPassword(proxy.getPassword());
            proxyOptions.setType(ProxyType.valueOf(proxy.getType().name()));

            options.setProxyOptions(proxyOptions);
        }

        URI target = URI.create(endpoint.getTarget());
        HttpClientSslOptions sslOptions = endpoint.getHttpClientSslOptions();

        if (HTTPS_SCHEME.equalsIgnoreCase(target.getScheme())
                || WSS_SCHEME.equalsIgnoreCase(target.getScheme())
                || GRPCS_SCHEME.equalsIgnoreCase(target.getScheme())) {
            // Configure SSL
            options.setSsl(true);

            if (sslOptions != null) {
                options
                        .setVerifyHost(sslOptions.isHostnameVerifier())
                        .setTrustAll(sslOptions.isTrustAll());

                // Client trust configuration
                if (!sslOptions.isTrustAll() && sslOptions.getTrustStore() != null) {
                    switch (sslOptions.getTrustStore().getType()) {
                        case PEM:
                            PEMTrustStore pemTrustStore = (PEMTrustStore) sslOptions.getTrustStore();
                            PemTrustOptions pemTrustOptions = new PemTrustOptions();
                            if (pemTrustStore.getPath() != null && !pemTrustStore.getPath().isEmpty()) {
                                pemTrustOptions.addCertPath(pemTrustStore.getPath());
                            } else if (pemTrustStore.getContent() != null && !pemTrustStore.getContent().isEmpty()) {
                                pemTrustOptions.addCertValue(io.vertx.core.buffer.Buffer.buffer(pemTrustStore.getContent()));
                            } else {
                                throw new EndpointException("Missing PEM certificate value for endpoint " + endpoint.getName());
                            }
                            options.setPemTrustOptions(pemTrustOptions);
                            break;
                        case PKCS12:
                            PKCS12TrustStore pkcs12TrustStore = (PKCS12TrustStore) sslOptions.getTrustStore();
                            PfxOptions pfxOptions = new PfxOptions();
                            pfxOptions.setPassword(pkcs12TrustStore.getPassword());
                            if (pkcs12TrustStore.getPath() != null && !pkcs12TrustStore.getPath().isEmpty()) {
                                pfxOptions.setPath(pkcs12TrustStore.getPath());
                            } else if (pkcs12TrustStore.getContent() != null && !pkcs12TrustStore.getContent().isEmpty()) {
                                pfxOptions.setValue(io.vertx.core.buffer.Buffer.buffer(pkcs12TrustStore.getContent()));
                            } else {
                                throw new EndpointException("Missing PKCS12 value for endpoint " + endpoint.getName());
                            }
                            options.setPfxTrustOptions(pfxOptions);
                            break;
                        case JKS:
                            JKSTrustStore jksTrustStore = (JKSTrustStore) sslOptions.getTrustStore();
                            JksOptions jksOptions = new JksOptions();
                            jksOptions.setPassword(jksTrustStore.getPassword());
                            if (jksTrustStore.getPath() != null && !jksTrustStore.getPath().isEmpty()) {
                                jksOptions.setPath(jksTrustStore.getPath());
                            } else if (jksTrustStore.getContent() != null && !jksTrustStore.getContent().isEmpty()) {
                                jksOptions.setValue(io.vertx.core.buffer.Buffer.buffer(jksTrustStore.getContent()));
                            } else {
                                throw new EndpointException("Missing JKS value for endpoint " + endpoint.getName());
                            }
                            options.setTrustStoreOptions(jksOptions);
                            break;
                    }
                }

                // Client authentication configuration
                if (sslOptions.getKeyStore() != null) {
                    switch (sslOptions.getKeyStore().getType()) {
                        case PEM:
                            PEMKeyStore pemKeyStore = (PEMKeyStore) sslOptions.getKeyStore();
                            PemKeyCertOptions pemKeyCertOptions = new PemKeyCertOptions();
                            if (pemKeyStore.getCertPath() != null && !pemKeyStore.getCertPath().isEmpty()) {
                                pemKeyCertOptions.setCertPath(pemKeyStore.getCertPath());
                            } else if (pemKeyStore.getCertContent() != null && !pemKeyStore.getCertContent().isEmpty()) {
                                pemKeyCertOptions.setCertValue(io.vertx.core.buffer.Buffer.buffer(pemKeyStore.getCertContent()));
                            }
                            if (pemKeyStore.getKeyPath() != null && !pemKeyStore.getKeyPath().isEmpty()) {
                                pemKeyCertOptions.setKeyPath(pemKeyStore.getKeyPath());
                            } else if (pemKeyStore.getKeyContent() != null && !pemKeyStore.getKeyContent().isEmpty()) {
                                pemKeyCertOptions.setKeyValue(io.vertx.core.buffer.Buffer.buffer(pemKeyStore.getKeyContent()));
                            }
                            options.setPemKeyCertOptions(pemKeyCertOptions);
                            break;
                        case PKCS12:
                            PKCS12KeyStore pkcs12KeyStore = (PKCS12KeyStore) sslOptions.getKeyStore();
                            PfxOptions pfxOptions = new PfxOptions();
                            pfxOptions.setPassword(pkcs12KeyStore.getPassword());
                            if (pkcs12KeyStore.getPath() != null && !pkcs12KeyStore.getPath().isEmpty()) {
                                pfxOptions.setPath(pkcs12KeyStore.getPath());
                            } else if (pkcs12KeyStore.getContent() != null && !pkcs12KeyStore.getContent().isEmpty()) {
                                pfxOptions.setValue(io.vertx.core.buffer.Buffer.buffer(pkcs12KeyStore.getContent()));
                            }
                            options.setPfxKeyCertOptions(pfxOptions);
                            break;
                        case JKS:
                            JKSKeyStore jksKeyStore = (JKSKeyStore) sslOptions.getKeyStore();
                            JksOptions jksOptions = new JksOptions();
                            jksOptions.setPassword(jksKeyStore.getPassword());
                            if (jksKeyStore.getPath() != null && !jksKeyStore.getPath().isEmpty()) {
                                jksOptions.setPath(jksKeyStore.getPath());
                            } else if (jksKeyStore.getContent() != null && !jksKeyStore.getContent().isEmpty()) {
                                jksOptions.setValue(io.vertx.core.buffer.Buffer.buffer(jksKeyStore.getContent()));
                            }
                            options.setKeyStoreOptions(jksOptions);
                            break;
                    }
                }
            }
        }

        return options;
    }

    @Override
    protected void doStop() throws Exception {
        LOGGER.info("Closing HTTP Client for '{}' endpoint [{}]", endpoint.getName(), endpoint.getTarget());

        httpClients.values().forEach(httpClient -> {
            try {
                httpClient.close();
            } catch (IllegalStateException ise) {
                LOGGER.warn(ise.getMessage());
            }
        });
    }

    private Function<Context, HttpClient> createHttpClient() {
        return context -> vertx.createHttpClient(options);
    }

    private void printHttpClientConfiguration() {
        LOGGER.info("Create HTTP connector with configuration: ");
        LOGGER.info("\t" + options.getProtocolVersion() + " {" +
                "ConnectTimeout='" + options.getConnectTimeout() + '\'' +
                ", KeepAlive='" + options.isKeepAlive() + '\'' +
                ", IdleTimeout='" + options.getIdleTimeout() + '\'' +
                ", MaxChunkSize='" + options.getMaxChunkSize() + '\'' +
                ", MaxPoolSize='" + options.getMaxPoolSize() + '\'' +
                ", MaxWaitQueueSize='" + options.getMaxWaitQueueSize() + '\'' +
                ", Pipelining='" + options.isPipelining() + '\'' +
                ", PipeliningLimit='" + options.getPipeliningLimit() + '\'' +
                ", TryUseCompression='" + options.isTryUseCompression() + '\'' +
                '}');

        if (options.isSsl()) {
            LOGGER.info("\tSSL {" +
                    "TrustAll='" + options.isTrustAll() + '\'' +
                    ", VerifyHost='" + options.isVerifyHost() + '\'' +
                    '}');
        }

        if (options.getProxyOptions() != null) {
            LOGGER.info("\tProxy {" +
                    "Type='" + options.getProxyOptions().getType() +
                    ", Host='" + options.getProxyOptions().getHost() + '\'' +
                    ", Port='" + options.getProxyOptions().getPort() + '\'' +
                    '}');
        }
    }
}
