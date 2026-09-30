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

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;

/** Supplies OAuth bearer tokens through Jakarta Mail's password abstraction. */
final class GraphCredentials {

    private final Session session;
    private final String protocol;
    private final String host;
    private final int port;
    private final boolean forcePasswordRefresh;
    private String user;
    private String password;

    /**
     * Creates a credential holder.
     *
     * @param session mail session used to invoke the authenticator
     * @param protocol provider protocol
     * @param baseUrl Graph base URL
     * @param user current authentication user
     * @param password current OAuth token
     * @param forcePasswordRefresh whether the authenticator is queried for each request
     * @throws MessagingException if the base URL is invalid
     */
    GraphCredentials( Session session, String protocol, String baseUrl, String user, String password, boolean forcePasswordRefresh ) throws MessagingException {
        this.session = session;
        this.protocol = protocol;
        URI uri;
        try {
            uri = URI.create( baseUrl );
        } catch( IllegalArgumentException ex ) {
            throw new MessagingException( "Invalid Microsoft Graph base URL: " + baseUrl, ex );
        }
        this.host = uri.getHost();
        this.port = uri.getPort();
        this.user = user;
        this.password = password;
        this.forcePasswordRefresh = forcePasswordRefresh;
    }

    /**
     * Returns the current access token, optionally refreshing it through the session authenticator.
     *
     * @return OAuth bearer token
     * @throws AuthenticationFailedException if no token is available
     */
    synchronized String token() throws AuthenticationFailedException {
        if( forcePasswordRefresh ) {
            PasswordAuthentication authentication = session.requestPasswordAuthentication( address(), port, protocol, "OAuth 2.0 access token", user );
            if( authentication != null ) {
                user = authentication.getUserName();
                password = authentication.getPassword();
            }
        }
        if( password == null || password.isBlank() ) {
            throw new AuthenticationFailedException( "An OAuth access token is required as the Jakarta Mail password" );
        }
        return password;
    }

    private InetAddress address() {
        try {
            return host == null ? null : InetAddress.getByName( host );
        } catch( UnknownHostException ex ) {
            return null;
        }
    }
}
