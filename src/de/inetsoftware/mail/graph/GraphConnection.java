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

import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;

/** Shared connection configuration for store and transport. */
final class GraphConnection {

    /**
     * Creates a configured Graph client for a connected Jakarta Mail service.
     *
     * @param session mail session
     * @param protocol provider protocol
     * @param host configured host, or {@code null}
     * @param port configured port, or {@code -1}
     * @param user mailbox user resolved by Jakarta Mail
     * @param password OAuth token supplied as the Jakarta Mail password
     * @return configured client
     * @throws MessagingException if a property is invalid
     */
    static GraphClient connect( Session session, String protocol, String host, int port, String user, String password ) throws MessagingException {
        Properties properties = session.getProperties();
        String authority = host == null || host.isBlank() ? "graph.microsoft.com" : host + (port > 0 ? ':' + String.valueOf( port ) : "");
        String defaultBase = "https://" + authority + "/v1.0/";
        String baseUrl = first( property( properties, protocol, GraphProperties.BASE_URL ), defaultBase );
        int connectionTimeout = positiveInt( property( properties, protocol, GraphProperties.CONNECTION_TIMEOUT ), 10_000 );
        int timeout = positiveInt( property( properties, protocol, GraphProperties.TIMEOUT ), 60_000 );
        int connectionPoolTimeout = positiveInt( property( properties, protocol, GraphProperties.CONNECTION_POOL_TIMEOUT ), 45_000 );
        boolean forcePasswordRefresh = Boolean.parseBoolean( property( properties, protocol, GraphProperties.FORCE_PASSWORD_REFRESH ) );
        GraphCredentials credentials = new GraphCredentials( session, protocol, baseUrl, user, password, forcePasswordRefresh );
        return new GraphClient( baseUrl, credentials, user, connectionTimeout, timeout, connectionPoolTimeout );
    }

    private static int positiveInt( String value, int defaultValue ) throws MessagingException {
        if( value == null || value.isBlank() ) {
            return defaultValue;
        }
        try {
            int result = Integer.parseInt( value );
            if( result <= 0 ) {
                throw new NumberFormatException();
            }
            return result;
        } catch( NumberFormatException ex ) {
            throw new MessagingException( "Timeout properties must be positive integer millisecond values" );
        }
    }

    private static String property( Properties properties, String protocol, String suffix ) {
        return properties.getProperty( GraphProperties.property( protocol, suffix ) );
    }

    private static String first( String... values ) {
        for( String value : values ) {
            if( value != null && !value.isBlank() ) {
                return value;
            }
        }
        return null;
    }

    private GraphConnection() {
    }
}
