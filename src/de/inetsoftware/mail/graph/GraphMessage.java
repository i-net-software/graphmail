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

import java.io.ByteArrayInputStream;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.mail.Flags;
import jakarta.mail.MessagingException;
import jakarta.mail.MethodNotSupportedException;
import jakarta.mail.internet.MimeMessage;

/** RFC 822 message loaded from Microsoft Graph. */
public final class GraphMessage extends MimeMessage {

    private final GraphFolder graphFolder;
    private final String graphId;
    private final Date graphReceivedDate;

    GraphMessage( GraphFolder folder, byte[] mime, int messageNumber, GraphFolder.MessageInfo metadata ) throws MessagingException {
        super( folder, new ByteArrayInputStream( mime ), messageNumber );
        this.graphFolder = folder;
        this.graphId = metadata.id;
        this.graphReceivedDate = metadata.received;
        this.flags = new Flags();
        if( metadata.read ) {
            this.flags.add( Flags.Flag.SEEN );
        }
        if( metadata.draft ) {
            this.flags.add( Flags.Flag.DRAFT );
        }
        if( metadata.flagged ) {
            this.flags.add( Flags.Flag.FLAGGED );
        }
    }

    /**
     * Returns the opaque Microsoft Graph message id.
     *
     * @return Graph message id
     */
    public String getGraphId() {
        return graphId;
    }

    /** {@inheritDoc} */
    @Override
    public Date getReceivedDate() throws MessagingException {
        return graphReceivedDate == null ? super.getReceivedDate() : new Date( graphReceivedDate.getTime() );
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void setFlags( Flags requested, boolean set ) throws MessagingException {
        if( isExpunged() ) {
            throw new MessagingException( "Message has been expunged" );
        }
        graphFolder.ensureWritable();
        Map<String, Object> patch = new LinkedHashMap<>();
        if( requested.contains( Flags.Flag.SEEN ) ) {
            patch.put( "isRead", set );
        }
        if( requested.contains( Flags.Flag.FLAGGED ) ) {
            Map<String, Object> flag = new LinkedHashMap<>();
            flag.put( "flagStatus", set ? "flagged" : "notFlagged" );
            patch.put( "flag", flag );
        }
        if( !patch.isEmpty() ) {
            graphFolder.client().patch( graphFolder.client().mailbox( "messages/" + GraphClient.pathSegment( graphId ) ), patch );
        }
        super.setFlags( requested, set );
    }

    /** {@inheritDoc} */
    @Override
    public void saveChanges() throws MessagingException {
        throw new MethodNotSupportedException( "Saving MIME changes to an existing Microsoft Graph message is not supported" );
    }

    /** Returns the opaque Graph message id for internal operations. */
    String graphId() {
        return graphId;
    }

    /** Deletes this message from Microsoft Graph. */
    void remoteDelete() throws MessagingException {
        graphFolder.client().delete( graphFolder.client().mailbox( "messages/" + GraphClient.pathSegment( graphId ) ) );
    }

    /** Marks this message as expunged in the Jakarta Mail model. */
    void markExpunged() {
        setExpunged( true );
    }

    /** Updates the Jakarta Mail sequence number after an expunge. */
    void setGraphMessageNumber( int number ) {
        setMessageNumber( number );
    }
}
