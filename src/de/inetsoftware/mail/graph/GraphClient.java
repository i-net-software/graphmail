/*
 * MIT License
 *
 * Copyright (c) 2026 i-net software GmbH
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package de.inetsoftware.mail.graph;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import jakarta.mail.MessagingException;

/** HTTP adapter for the small subset of Microsoft Graph used by this provider. */
final class GraphClient {

    private final URI baseUri;
    private final GraphCredentials credentials;
    private final String mailboxPath;
    private final Duration connectionTimeout;
    private final Duration timeout;
    private final long connectionPoolTimeoutMillis;
    private volatile HttpClient http;
    private volatile long lastRequestMillis;

    /**
     * Creates a Microsoft Graph HTTP client.
     *
     * @param baseUrl Graph API root
     * @param credentials bearer-token source
     * @param user mailbox id, user principal name, or {@code me}
     * @param connectionTimeoutMillis HTTP connect timeout in milliseconds
     * @param timeoutMillis complete-request timeout in milliseconds
     * @param connectionPoolTimeoutMillis idle lifetime of the internal HTTP client in milliseconds
     * @throws MessagingException if the base URL is invalid
     */
    GraphClient( String baseUrl, GraphCredentials credentials, String user, int connectionTimeoutMillis, int timeoutMillis, int connectionPoolTimeoutMillis ) throws MessagingException {
        try {
            this.baseUri = URI.create( baseUrl.endsWith( "/" ) ? baseUrl : baseUrl + '/' );
        } catch( IllegalArgumentException ex ) {
            throw new MessagingException( "Invalid Microsoft Graph base URL: " + baseUrl, ex );
        }
        this.credentials = credentials;
        this.mailboxPath = "me".equalsIgnoreCase( user ) ? "me" : "users/" + pathSegment( user );
        this.connectionTimeout = Duration.ofMillis( connectionTimeoutMillis );
        this.timeout = Duration.ofMillis( timeoutMillis );
        this.connectionPoolTimeoutMillis = connectionPoolTimeoutMillis;
        this.http = newHttpClient();
    }

    /** Returns a path relative to the selected mailbox. */
    String mailbox( String relative ) {
        return mailboxPath + (relative.isEmpty() ? "" : '/' + relative);
    }

    /** Performs a GET and returns the response body unchanged. */
    byte[] getBytes( String path ) throws MessagingException {
        return request( "GET", path, null, null ).body();
    }

    /** Performs a GET and parses the response as a JSON object. */
    Map<String, Object> getObject( String path ) throws MessagingException {
        return jsonObject( request( "GET", path, null, null ).body() );
    }

    /** Performs a JSON POST and parses a non-empty response object. */
    Map<String, Object> postJson( String path, Object body ) throws MessagingException {
        byte[] bytes = Json.stringify( body ).getBytes( StandardCharsets.UTF_8 );
        byte[] response = request( "POST", path, "application/json", bytes ).body();
        return response.length == 0 ? Collections.emptyMap() : jsonObject( response );
    }

    /** Performs a MIME POST using Graph's base64-in-text format. */
    Map<String, Object> postMime( String path, byte[] mime ) throws MessagingException {
        byte[] encoded = java.util.Base64.getEncoder().encode( mime );
        byte[] response = request( "POST", path, "text/plain", encoded ).body();
        return response.length == 0 ? Collections.emptyMap() : jsonObject( response );
    }

    /** Performs a JSON PATCH and parses a non-empty response object. */
    Map<String, Object> patch( String path, Object body ) throws MessagingException {
        byte[] response = request( "PATCH", path, "application/json", Json.stringify( body ).getBytes( StandardCharsets.UTF_8 ) ).body();
        return response.length == 0 ? Collections.emptyMap() : jsonObject( response );
    }

    /** Performs a DELETE request. */
    void delete( String path ) throws MessagingException {
        request( "DELETE", path, null, null );
    }

    /** Reads all pages of a Graph collection. */
    List<Map<String, Object>> getAll( String path ) throws MessagingException {
        List<Map<String, Object>> result = new ArrayList<>();
        String next = path;
        while( next != null ) {
            Map<String, Object> page = getObject( next );
            Object values = page.get( "value" );
            if( values instanceof List ) {
                for( Object value : (List<?>)values ) {
                    if( value instanceof Map ) {
                        @SuppressWarnings( "unchecked" )
                        Map<String, Object> item = (Map<String, Object>)value;
                        result.add( item );
                    }
                }
            }
            Object nextLink = page.get( "@odata.nextLink" );
            next = nextLink instanceof String ? (String)nextLink : null;
        }
        return result;
    }

    private HttpResponse<byte[]> request( String method, String path, String contentType, byte[] body ) throws MessagingException {
        URI uri;
        try {
            uri = URI.create( path ).isAbsolute() ? URI.create( path ) : baseUri.resolve( path );
        } catch( IllegalArgumentException ex ) {
            throw new MessagingException( "Invalid Microsoft Graph URL", ex );
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder( uri )
                        .timeout( timeout )
                        .header( "Authorization", "Bearer " + credentials.token() )
                        .header( "Accept", "*/*" );
        if( contentType != null ) {
            builder.header( "Content-Type", contentType );
        }
        builder.method( method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray( body ) );
        try {
            HttpResponse<byte[]> response = httpClient().send( builder.build(), HttpResponse.BodyHandlers.ofByteArray() );
            if( response.statusCode() >= 200 && response.statusCode() < 300 ) {
                return response;
            }
            throw graphError( method, uri, response );
        } catch( InterruptedException ex ) {
            Thread.currentThread().interrupt();
            throw new MessagingException( "Interrupted while calling Microsoft Graph", ex );
        } catch( IOException ex ) {
            throw new MessagingException( "Unable to call Microsoft Graph at " + uri, ex );
        }
    }

    private synchronized HttpClient httpClient() {
        long now = System.currentTimeMillis();
        if( lastRequestMillis != 0 && now - lastRequestMillis > connectionPoolTimeoutMillis ) {
            http = newHttpClient();
        }
        lastRequestMillis = now;
        return http;
    }

    private HttpClient newHttpClient() {
        return HttpClient.newBuilder().connectTimeout( connectionTimeout ).build();
    }

    private MessagingException graphError( String method, URI uri, HttpResponse<byte[]> response ) {
        String detail = new String( response.body(), StandardCharsets.UTF_8 );
        try {
            Map<String, Object> root = Json.object( detail );
            Object error = root.get( "error" );
            if( error instanceof Map ) {
                Object code = ((Map<?, ?>)error).get( "code" );
                Object message = ((Map<?, ?>)error).get( "message" );
                detail = String.valueOf( code ) + ": " + String.valueOf( message );
            }
        } catch( RuntimeException ignored ) {
            if( detail.length() > 500 ) {
                detail = detail.substring( 0, 500 );
            }
        }
        return new MessagingException( method + " " + uri + " failed with HTTP " + response.statusCode() + ": " + detail );
    }

    private static Map<String, Object> jsonObject( byte[] bytes ) throws MessagingException {
        try {
            return Json.object( new String( bytes, StandardCharsets.UTF_8 ) );
        } catch( RuntimeException ex ) {
            throw new MessagingException( "Invalid JSON returned by Microsoft Graph", ex );
        }
    }

    /** Percent-encodes a single URL path segment using UTF-8. */
    static String pathSegment( String value ) {
        byte[] bytes = value.getBytes( StandardCharsets.UTF_8 );
        StringBuilder result = new StringBuilder();
        for( byte item : bytes ) {
            int ch = item & 0xff;
            if( ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-' || ch == '_' || ch == '.' || ch == '~' ) {
                result.append( (char)ch );
            } else {
                result.append( '%' );
                result.append( Character.toUpperCase( Character.forDigit( ch >>> 4, 16 ) ) );
                result.append( Character.toUpperCase( Character.forDigit( ch & 15, 16 ) ) );
            }
        }
        return result.toString();
    }
}
