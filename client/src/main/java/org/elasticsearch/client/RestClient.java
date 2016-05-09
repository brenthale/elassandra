/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.client;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class RestClient implements Closeable {

    private static final Log logger = LogFactory.getLog(RestClient.class);

    private final CloseableHttpClient client;
    private final ConnectionPool connectionPool;
    private final long maxRetryTimeout;

    public RestClient(CloseableHttpClient client, ConnectionPool connectionPool, long maxRetryTimeout) {
        Objects.requireNonNull(client, "client cannot be null");
        Objects.requireNonNull(connectionPool, "connectionPool cannot be null");
        if (maxRetryTimeout <= 0) {
            throw new IllegalArgumentException("maxRetryTimeout must be greater than 0");
        }
        this.client = client;
        this.connectionPool = connectionPool;
        this.maxRetryTimeout = maxRetryTimeout;
    }

    public ElasticsearchResponse performRequest(String method, String endpoint, Map<String, Object> params, HttpEntity entity)
            throws IOException {
        URI uri = buildUri(endpoint, params);
        HttpRequestBase request = createHttpRequest(method, uri, entity);
        Iterator<Connection> connectionIterator = connectionPool.nextConnection().iterator();
        if (connectionIterator.hasNext() == false) {
            Connection connection = connectionPool.lastResortConnection();
            logger.info("no healthy nodes available, trying " + connection.getHost());
            return performRequest(request, Stream.of(connection).iterator());
        }
        return performRequest(request, connectionIterator);
    }

    private ElasticsearchResponse performRequest(HttpRequestBase request, Iterator<Connection> connectionIterator) throws IOException {
        //we apply a soft margin so that e.g. if a request took 59 seconds and timeout is set to 60 we don't do another attempt
        long retryTimeout = Math.round(this.maxRetryTimeout / (float)100 * 98);
        IOException lastSeenException = null;
        long startTime = System.nanoTime();

        while (connectionIterator.hasNext()) {
            Connection connection = connectionIterator.next();

            if (lastSeenException != null) {
                long timeElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
                long timeout = retryTimeout - timeElapsed;
                if (timeout <= 0) {
                    IOException retryTimeoutException = new IOException(
                            "request retries exceeded max retry timeout [" + retryTimeout + "]");
                    retryTimeoutException.addSuppressed(lastSeenException);
                    throw retryTimeoutException;
                }
            }

            CloseableHttpResponse response;
            try {
                response = client.execute(connection.getHost(), request);
            } catch(IOException e) {
                RequestLogger.log(logger, "request failed", request, connection.getHost(), e);
                connectionPool.onFailure(connection);
                lastSeenException = addSuppressedException(lastSeenException, e);
                continue;
            } finally {
                request.reset();
            }
            int statusCode = response.getStatusLine().getStatusCode();
            //TODO make ignore status code configurable. rest-spec and tests support that parameter (ignore_missing)
            if (statusCode < 300 || (request.getMethod().equals(HttpHead.METHOD_NAME) && statusCode == 404) ) {
                RequestLogger.log(logger, "request succeeded", request, connection.getHost(), response);
                connectionPool.onSuccess(connection);
                return new ElasticsearchResponse(request.getRequestLine(), connection.getHost(), response);
            } else {
                RequestLogger.log(logger, "request failed", request, connection.getHost(), response);
                String responseBody = null;
                if (response.getEntity() != null) {
                    responseBody = EntityUtils.toString(response.getEntity());
                }
                ElasticsearchResponseException elasticsearchResponseException = new ElasticsearchResponseException(
                        request.getRequestLine(), connection.getHost(), response.getStatusLine(), responseBody);
                lastSeenException = addSuppressedException(lastSeenException, elasticsearchResponseException);
                //clients don't retry on 500 because elasticsearch still misuses it instead of 400 in some places
                if (statusCode == 502 || statusCode == 503 || statusCode == 504) {
                    connectionPool.onFailure(connection);
                } else {
                    //don't retry and call onSuccess as the error should be a request problem, the node is alive
                    connectionPool.onSuccess(connection);
                    break;
                }
            }
        }
        assert lastSeenException != null;
        throw lastSeenException;
    }

    private static IOException addSuppressedException(IOException suppressedException, IOException currentException) {
        if (suppressedException != null) {
            currentException.addSuppressed(suppressedException);
        }
        return currentException;
    }

    private static HttpRequestBase createHttpRequest(String method, URI uri, HttpEntity entity) {
        switch(method.toUpperCase(Locale.ROOT)) {
            case HttpDeleteWithEntity.METHOD_NAME:
                HttpDeleteWithEntity httpDeleteWithEntity = new HttpDeleteWithEntity(uri);
                addRequestBody(httpDeleteWithEntity, entity);
                return httpDeleteWithEntity;
            case HttpGetWithEntity.METHOD_NAME:
                HttpGetWithEntity httpGetWithEntity = new HttpGetWithEntity(uri);
                addRequestBody(httpGetWithEntity, entity);
                return httpGetWithEntity;
            case HttpHead.METHOD_NAME:
                if (entity != null) {
                    throw new UnsupportedOperationException("HEAD with body is not supported");
                }
                return new HttpHead(uri);
            case HttpPost.METHOD_NAME:
                HttpPost httpPost = new HttpPost(uri);
                addRequestBody(httpPost, entity);
                return httpPost;
            case HttpPut.METHOD_NAME:
                HttpPut httpPut = new HttpPut(uri);
                addRequestBody(httpPut, entity);
                return httpPut;
            default:
                throw new UnsupportedOperationException("http method not supported: " + method);
        }
    }

    private static void addRequestBody(HttpEntityEnclosingRequestBase httpRequest, HttpEntity entity) {
        if (entity != null) {
            httpRequest.setEntity(entity);
        }
    }

    private static URI buildUri(String path, Map<String, Object> params) {
        try {
            URIBuilder uriBuilder = new URIBuilder(path);
            for (Map.Entry<String, Object> param : params.entrySet()) {
                uriBuilder.addParameter(param.getKey(), param.getValue().toString());
            }
            return uriBuilder.build();
        } catch(URISyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        connectionPool.close();
        client.close();
    }
}
