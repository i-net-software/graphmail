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

/**
 * Configuration properties understood by the Graph Jakarta Mail provider.
 */
public final class GraphProperties {

    /** Protocol name of the mailbox store. */
    public static final String STORE_PROTOCOL = "msgraph-store";

    /** Protocol name of the message transport. */
    public static final String TRANSPORT_PROTOCOL = "msgraph-send";

    /** Microsoft Graph API root. Defaults to {@code https://graph.microsoft.com/v1.0/}. */
    public static final String BASE_URL = "baseurl";

    /** HTTP connection timeout in milliseconds. Defaults to 10,000. */
    public static final String CONNECTION_TIMEOUT = "connectiontimeout";

    /** HTTP request timeout in milliseconds. Defaults to 60,000. */
    public static final String TIMEOUT = "timeout";

    /** HTTP connection-pool idle timeout in milliseconds. Defaults to 45,000. */
    public static final String CONNECTION_POOL_TIMEOUT = "connectionpooltimeout";

    /** Whether to obtain a fresh password/token from the Authenticator before each request. */
    public static final String FORCE_PASSWORD_REFRESH = "forcepasswordrefresh";

    /** Whether hidden Graph mail folders are included. Defaults to {@code false}. */
    public static final String INCLUDE_HIDDEN_FOLDERS = "includehiddenfolders";

    /**
     * Returns the Jakarta Mail property key for a provider protocol and a property name.
     *
     * @param protocol provider protocol
     * @param name unprefixed property name
     * @return fully-qualified Jakarta Mail property key
     */
    static String property( String protocol, String name ) {
        return "mail." + protocol + '.' + name;
    }

    private GraphProperties() {
    }
}
