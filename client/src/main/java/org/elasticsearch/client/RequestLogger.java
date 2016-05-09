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
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.util.EntityUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Helper class that exposes static methods to unify the way requests are logged.
 * Includes trace logging to log complete requests and responses in curl format.
 */
public final class RequestLogger {

    private static final Log tracer = LogFactory.getLog("tracer");

    private RequestLogger() {
    }

    /**
     * Logs a request that yielded a response
     */
    public static void log(Log logger, String message, HttpUriRequest request, HttpHost host, HttpResponse httpResponse) {
        logger.debug(message + " [" + request.getMethod() + " " + host + request.getRequestLine().getUri() +
                "] [" + httpResponse.getStatusLine() + "]");

        if (tracer.isTraceEnabled()) {
            String requestLine;
            try {
                requestLine = buildTraceRequest(request, host);
            } catch(IOException e) {
                requestLine = "";
                tracer.trace("error while reading request for trace purposes", e);
            }
            String responseLine;
            try {
                responseLine = buildTraceResponse(httpResponse);
            } catch(IOException e) {
                responseLine = "";
                tracer.trace("error while reading response for trace purposes", e);
            }
            tracer.trace(requestLine + '\n' + responseLine);
        }
    }

    /**
     * Logs a request that failed
     */
    public static void log(Log logger, String message, HttpUriRequest request, HttpHost host, IOException e) {
        logger.debug(message + " [" + request.getMethod() + " " + host + request.getRequestLine().getUri() + "]", e);
        if (logger.isTraceEnabled()) {
            String traceRequest;
            try {
                traceRequest = buildTraceRequest(request, host);
            } catch (IOException e1) {
                tracer.trace("error while reading request for trace purposes", e);
                traceRequest = "";
            }
            tracer.trace(traceRequest);
        }
    }

    /**
     * Creates curl output for given request
     */
    static String buildTraceRequest(HttpUriRequest request, HttpHost host) throws IOException {
        String requestLine = "curl -iX " + request.getMethod() + " '" + host + request.getRequestLine().getUri() + "'";
        if (request instanceof  HttpEntityEnclosingRequest) {
            HttpEntityEnclosingRequest enclosingRequest = (HttpEntityEnclosingRequest) request;
            if (enclosingRequest.getEntity() != null) {
                requestLine += " -d '";
                HttpEntity entity = new BufferedHttpEntity(enclosingRequest.getEntity());
                enclosingRequest.setEntity(entity);
                requestLine += EntityUtils.toString(entity) + "'";
            }
        }
        return requestLine;
    }

    /**
     * Creates curl output for given response
     */
    static String buildTraceResponse(HttpResponse httpResponse) throws IOException {
        String responseLine = "# " + httpResponse.getStatusLine().toString();
        for (Header header : httpResponse.getAllHeaders()) {
            responseLine += "\n# " + header.getName() + ": " + header.getValue();
        }
        responseLine += "\n#";
        HttpEntity entity = httpResponse.getEntity();
        if (entity != null) {
            entity = new BufferedHttpEntity(entity);
            httpResponse.setEntity(entity);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(entity.getContent()))) {
                String line;
                while( (line = reader.readLine()) != null) {
                    responseLine += "\n# " + line;
                }
            }
        }
        return responseLine;
    }
}
