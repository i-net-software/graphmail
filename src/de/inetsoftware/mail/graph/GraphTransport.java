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

import java.io.ByteArrayOutputStream;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessageRemovedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.event.TransportEvent;

/** Sends RFC 822 messages through the Microsoft Graph {@code sendMail} endpoint. */
public class GraphTransport extends Transport {

    private volatile GraphClient client;

    /**
     * Creates a Graph transport. Usually instantiated by {@link Session#getTransport(String)}.
     *
     * @param session owning mail session
     * @param urlName provider URL
     */
    public GraphTransport( Session session, URLName urlName ) {
        super( session, urlName );
    }

    /** {@inheritDoc} */
    @Override
    protected boolean protocolConnect( String host, int port, String user, String password ) throws MessagingException {
        if( user == null || user.isBlank() || password == null || password.isBlank() ) {
            return false;
        }
        client = GraphConnection.connect( session, GraphProperties.TRANSPORT_PROTOCOL, host, port, user, password );
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void sendMessage( Message message, Address[] addresses ) throws MessagingException {
        if( !isConnected() || client == null ) {
            throw new IllegalStateException( "The graph transport is not connected" );
        }
        if( message == null ) {
            throw new MessagingException( "Message must not be null" );
        }
        Address[] recipients = addresses == null ? new Address[0] : addresses.clone();
        if( recipients.length == 0 ) {
            throw new SendFailedException( "No recipient addresses" );
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            message.writeTo( output );
            client.postMime( client.mailbox( "sendMail" ), output.toByteArray() );
            notifyTransportListeners( TransportEvent.MESSAGE_DELIVERED, recipients, new Address[0], new Address[0], message );
        } catch( MessageRemovedException ex ) {
            notifyTransportListeners( TransportEvent.MESSAGE_NOT_DELIVERED, new Address[0], new Address[0], recipients, message );
            throw ex;
        } catch( java.io.IOException ex ) {
            notifyTransportListeners( TransportEvent.MESSAGE_NOT_DELIVERED, new Address[0], new Address[0], recipients, message );
            throw new MessagingException( "Unable to serialize message", ex );
        } catch( MessagingException ex ) {
            notifyTransportListeners( TransportEvent.MESSAGE_NOT_DELIVERED, new Address[0], new Address[0], recipients, message );
            throw ex;
        }
    }
}
