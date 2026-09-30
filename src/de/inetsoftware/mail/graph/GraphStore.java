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

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.StoreClosedException;
import jakarta.mail.URLName;

/**
 * Jakarta Mail store backed by a Microsoft Graph mailbox.
 */
public class GraphStore extends Store {

    private volatile GraphClient client;

    /**
     * Creates a Graph store. Usually instantiated by {@link Session#getStore(String)}.
     *
     * @param session owning mail session
     * @param urlName provider URL
     */
    public GraphStore( Session session, URLName urlName ) {
        super( session, urlName );
    }

    /** {@inheritDoc} */
    @Override
    protected boolean protocolConnect( String host, int port, String user, String password ) throws MessagingException {
        if( user == null || user.isBlank() || password == null || password.isBlank() ) {
            return false;
        }
        client = GraphConnection.connect( session, GraphProperties.STORE_PROTOCOL, host, port, user, password );
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public Folder getDefaultFolder() throws MessagingException {
        requireClient();
        return GraphFolder.root( this );
    }

    /** {@inheritDoc} */
    @Override
    public Folder getFolder( String name ) throws MessagingException {
        requireClient();
        if( name == null || name.isBlank() || "/".equals( name ) ) {
            return getDefaultFolder();
        }
        return GraphFolder.unresolved( this, name );
    }

    /** {@inheritDoc} */
    @Override
    public Folder getFolder( URLName name ) throws MessagingException {
        return getFolder( name == null ? null : name.getFile() );
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void close() throws MessagingException {
        try {
            super.close();
        } finally {
            client = null;
        }
    }

    /** Returns the connected Graph client. */
    GraphClient client() throws StoreClosedException {
        return requireClient();
    }

    /** Returns whether hidden folders should be included in listings. */
    boolean includeHiddenFolders() {
        return Boolean.parseBoolean( session.getProperty( GraphProperties.property( GraphProperties.STORE_PROTOCOL, GraphProperties.INCLUDE_HIDDEN_FOLDERS ) ) );
    }

    private GraphClient requireClient() throws StoreClosedException {
        GraphClient value = client;
        if( !isConnected() || value == null ) {
            throw new StoreClosedException( this, "The graph store is not connected" );
        }
        return value;
    }
}
