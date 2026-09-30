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
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.MethodNotSupportedException;
import jakarta.mail.StoreClosedException;
import jakarta.mail.UIDFolder;
import jakarta.mail.event.ConnectionEvent;
import jakarta.mail.event.FolderEvent;

/** Microsoft Graph mail folder. */
public final class GraphFolder extends Folder implements UIDFolder {

    private static final String FOLDER_SELECT = "$select=id,displayName,parentFolderId,totalItemCount,unreadItemCount,childFolderCount,isHidden";
    private static final String MESSAGE_SELECT = "$select=id,isRead,isDraft,receivedDateTime,sentDateTime,subject,internetMessageId,hasAttachments,flag";
    private static final Set<String> WELL_KNOWN_FOLDERS = Set.of( "archive", "clutter", "conflicts", "conversationhistory", "deleteditems", "drafts", "inbox", "junkemail",
                    "localfailures", "msgfolderroot", "outbox", "recoverableitemsdeletions", "scheduled", "searchfolders", "sentitems", "serverfailures", "syncissues" );

    private final GraphStore graphStore;
    private String fullName;
    private FolderInfo info;
    private boolean root;
    private boolean open;
    private List<MessageInfo> messageInfos = Collections.emptyList();
    private List<GraphMessage> messages = Collections.emptyList();

    static GraphFolder root( GraphStore store ) {
        GraphFolder result = new GraphFolder( store, "", null );
        result.root = true;
        return result;
    }

    static GraphFolder unresolved( GraphStore store, String name ) {
        String normalized = name.replace( '\\', '/' );
        while( normalized.startsWith( "/" ) ) {
            normalized = normalized.substring( 1 );
        }
        while( normalized.endsWith( "/" ) ) {
            normalized = normalized.substring( 0, normalized.length() - 1 );
        }
        return new GraphFolder( store, normalized, null );
    }

    private GraphFolder( GraphStore store, String fullName, FolderInfo info ) {
        super( store );
        this.graphStore = store;
        this.fullName = fullName;
        this.info = info;
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        int separator = fullName.lastIndexOf( '/' );
        return separator < 0 ? fullName : fullName.substring( separator + 1 );
    }

    /** {@inheritDoc} */
    @Override
    public String getFullName() {
        return fullName;
    }

    /** {@inheritDoc} */
    @Override
    public Folder getParent() throws MessagingException {
        if( root ) {
            return null;
        }
        int separator = fullName.lastIndexOf( '/' );
        return separator < 0 ? graphStore.getDefaultFolder() : graphStore.getFolder( fullName.substring( 0, separator ) );
    }

    /** {@inheritDoc} */
    @Override
    public boolean exists() throws MessagingException {
        if( root ) {
            graphStore.client();
            return true;
        }
        try {
            resolve();
            return true;
        } catch( FolderNotFoundGraphException ex ) {
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Folder[] list( String pattern ) throws MessagingException {
        FolderInfo parentInfo = root ? null : resolve();
        String effectivePattern = pattern == null ? "%" : pattern;
        Pattern matcher = mailPattern( effectivePattern );
        List<Folder> result = new ArrayList<>();
        boolean recursive = effectivePattern.indexOf( '*' ) >= 0 || effectivePattern.indexOf( '/' ) >= 0;
        collectFolders( parentInfo, "", matcher, recursive, result );
        return result.toArray( Folder[]::new );
    }

    /** {@inheritDoc} */
    @Override
    public char getSeparator() {
        return '/';
    }

    /** {@inheritDoc} */
    @Override
    public int getType() throws MessagingException {
        if( root ) {
            return HOLDS_FOLDERS;
        }
        resolve();
        return HOLDS_MESSAGES | HOLDS_FOLDERS;
    }

    /** {@inheritDoc} */
    @Override
    public boolean create( int type ) throws MessagingException {
        if( root || exists() ) {
            return false;
        }
        GraphFolder parent = (GraphFolder)getParent();
        FolderInfo parentInfo = parent.root ? null : parent.resolve();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put( "displayName", getName() );
        Map<String, Object> created = graphStore.client().postJson( createFolderPath( parentInfo ), body );
        info = FolderInfo.from( created );
        notifyFolderListeners( FolderEvent.CREATED );
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasNewMessages() throws MessagingException {
        return !root && resolve().unreadCount > 0;
    }

    /** {@inheritDoc} */
    @Override
    public Folder getFolder( String name ) throws MessagingException {
        if( name == null || name.isBlank() ) {
            return this;
        }
        return graphStore.getFolder( fullName.isEmpty() ? name : fullName + '/' + name );
    }

    /** {@inheritDoc} */
    @Override
    public boolean delete( boolean recurse ) throws MessagingException {
        ensureClosed();
        if( root ) {
            return false;
        }
        FolderInfo current = resolve();
        if( !recurse && current.childCount > 0 ) {
            return false;
        }
        graphStore.client().delete( graphStore.client().mailbox( "mailFolders/" + GraphClient.pathSegment( current.id ) ) );
        notifyFolderListeners( FolderEvent.DELETED );
        info = null;
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean renameTo( Folder destination ) throws MessagingException {
        ensureClosed();
        if( root || !(destination instanceof GraphFolder) || destination.getStore() != store ) {
            return false;
        }
        GraphFolder target = (GraphFolder)destination;
        String sourceParent = parentName( fullName );
        if( !Objects.equals( sourceParent, parentName( target.fullName ) ) ) {
            throw new MethodNotSupportedException( "Microsoft Graph folder moves are not exposed as Jakarta Mail rename operations" );
        }
        FolderInfo current = resolve();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put( "displayName", target.getName() );
        Map<String, Object> updated = graphStore.client().patch( graphStore.client().mailbox( "mailFolders/" + GraphClient.pathSegment( current.id ) ), body );
        if( updated.isEmpty() ) {
            // Kept only for clients returning 204; normal Graph PATCH returns the updated folder.
            current.name = target.getName();
        }
        target.info = updated.isEmpty() ? current : FolderInfo.from( updated );
        String oldName = fullName;
        this.fullName = target.fullName;
        this.info = target.info;
        GraphFolder oldFolder = GraphFolder.unresolved( graphStore, oldName );
        oldFolder.info = this.info;
        oldFolder.notifyFolderRenamedListeners( this );
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void open( int mode ) throws MessagingException {
        if( open ) {
            throw new IllegalStateException( "Folder is already open" );
        }
        if( root ) {
            throw new MessagingException( "The default graph folder cannot be opened" );
        }
        if( mode != READ_ONLY && mode != READ_WRITE ) {
            throw new MessagingException( "Invalid folder mode: " + mode );
        }
        FolderInfo current = resolve();
        String query = graphStore.client().mailbox( "mailFolders/" + GraphClient.pathSegment( current.id ) + "/messages" )
                        + "?$top=100&" + MESSAGE_SELECT + "&$orderby=receivedDateTime%20asc";
        List<Map<String, Object>> values = graphStore.client().getAll( query );
        List<MessageInfo> metadata = new ArrayList<>( values.size() );
        for( Map<String, Object> value : values ) {
            metadata.add( MessageInfo.from( value ) );
        }
        this.messageInfos = metadata;
        this.messages = new ArrayList<>( Collections.nCopies( metadata.size(), null ) );
        this.mode = mode;
        this.open = true;
        notifyConnectionListeners( ConnectionEvent.OPENED );
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void close( boolean expunge ) throws MessagingException {
        if( !open ) {
            return;
        }
        MessagingException failure = null;
        if( expunge && mode == READ_WRITE ) {
            try {
                expunge();
            } catch( MessagingException ex ) {
                failure = ex;
            }
        }
        open = false;
        messageInfos = Collections.emptyList();
        messages = Collections.emptyList();
        notifyConnectionListeners( ConnectionEvent.CLOSED );
        if( failure != null ) {
            throw failure;
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isOpen() {
        return open && graphStore.isConnected();
    }

    /** {@inheritDoc} */
    @Override
    public Flags getPermanentFlags() {
        Flags flags = new Flags();
        flags.add( Flags.Flag.SEEN );
        flags.add( Flags.Flag.DELETED );
        flags.add( Flags.Flag.FLAGGED );
        return flags;
    }

    /** {@inheritDoc} */
    @Override
    public int getMessageCount() throws MessagingException {
        if( open ) {
            return messageInfos.size();
        }
        return root ? 0 : resolve().totalCount;
    }

    /** {@inheritDoc} */
    @Override
    public int getNewMessageCount() {
        return 0; // Microsoft Graph has no equivalent of the transient IMAP RECENT flag.
    }

    /** {@inheritDoc} */
    @Override
    public int getUnreadMessageCount() throws MessagingException {
        if( root ) {
            return 0;
        }
        return resolve().unreadCount;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Message getMessage( int msgnum ) throws MessagingException {
        ensureOpen();
        if( msgnum < 1 || msgnum > messageInfos.size() ) {
            throw new IndexOutOfBoundsException( "Message number: " + msgnum );
        }
        int index = msgnum - 1;
        GraphMessage message = messages.get( index );
        if( message == null ) {
            MessageInfo metadata = messageInfos.get( index );
            byte[] mime = graphStore.client().getBytes( graphStore.client().mailbox( "messages/" + GraphClient.pathSegment( metadata.id ) + "/$value" ) );
            message = new GraphMessage( this, mime, msgnum, metadata );
            messages.set( index, message );
        }
        return message;
    }

    /** {@inheritDoc} */
    @Override
    public void appendMessages( Message[] source ) throws MessagingException {
        if( open && mode != READ_WRITE ) {
            throw new IllegalStateException( "Folder is open in READ_ONLY mode" );
        }
        FolderInfo current = resolve();
        List<Message> added = new ArrayList<>();
        for( Message message : source ) {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                message.writeTo( output );
                Map<String, Object> created = graphStore.client().postMime(
                                graphStore.client().mailbox( "mailFolders/" + GraphClient.pathSegment( current.id ) + "/messages" ), output.toByteArray() );
                if( !created.isEmpty() ) {
                    MessageInfo metadata = MessageInfo.from( created );
                    GraphMessage graphMessage = new GraphMessage( this, output.toByteArray(), messageInfos.size() + 1, metadata );
                    messageInfos.add( metadata );
                    messages.add( graphMessage );
                    current.totalCount++;
                    if( !metadata.read ) {
                        current.unreadCount++;
                    }
                    added.add( graphMessage );
                }
            } catch( IOException ex ) {
                throw new MessagingException( "Unable to serialize message", ex );
            }
        }
        if( !added.isEmpty() ) {
            notifyMessageAddedListeners( added.toArray( Message[]::new ) );
        }
    }

    /** {@inheritDoc} */
    @Override
    public void fetch( Message[] requested, FetchProfile profile ) throws MessagingException {
        ensureOpen();
        // Calling getMessage materializes the complete MIME message; there is no partial MIME endpoint in Graph.
        for( Message message : requested ) {
            if( message instanceof GraphMessage && message.getFolder() == this ) {
                getMessage( message.getMessageNumber() );
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void copyMessages( Message[] source, Folder destination ) throws MessagingException {
        ensureOpen();
        if( !(destination instanceof GraphFolder) || destination.getStore() != store ) {
            super.copyMessages( source, destination );
            return;
        }
        GraphFolder target = (GraphFolder)destination;
        FolderInfo targetInfo = target.resolve();
        for( Message message : source ) {
            if( !(message instanceof GraphMessage) || message.getFolder() != this ) {
                throw new MessagingException( "Only messages from this graph folder can be copied" );
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put( "destinationId", targetInfo.id );
            graphStore.client().postJson( graphStore.client().mailbox( "messages/" + GraphClient.pathSegment( ((GraphMessage)message).graphId() ) + "/copy" ), body );
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Message[] expunge() throws MessagingException {
        ensureWritable();
        List<Message> removed = new ArrayList<>();
        for( int i = messages.size() - 1; i >= 0; i-- ) {
            GraphMessage message = messages.get( i );
            if( message != null && message.isSet( Flags.Flag.DELETED ) ) {
                message.remoteDelete();
                message.markExpunged();
                removed.add( message );
                messages.remove( i );
                MessageInfo metadata = messageInfos.remove( i );
                if( info != null ) {
                    info.totalCount = Math.max( 0, info.totalCount - 1 );
                    if( !metadata.read ) {
                        info.unreadCount = Math.max( 0, info.unreadCount - 1 );
                    }
                }
            }
        }
        for( int i = 0; i < messages.size(); i++ ) {
            GraphMessage message = messages.get( i );
            if( message != null ) {
                message.setGraphMessageNumber( i + 1 );
            }
        }
        Collections.reverse( removed );
        if( !removed.isEmpty() ) {
            notifyMessageRemovedListeners( true, removed.toArray( Message[]::new ) );
        }
        return removed.toArray( Message[]::new );
    }

    /** {@inheritDoc} */
    @Override
    public long getUIDValidity() throws MessagingException {
        return uid( root ? "root" : resolve().id );
    }

    /** {@inheritDoc} */
    @Override
    public Message getMessageByUID( long uid ) throws MessagingException {
        ensureOpen();
        for( int i = 0; i < messageInfos.size(); i++ ) {
            if( uid( messageInfos.get( i ).id ) == uid ) {
                return getMessage( i + 1 );
            }
        }
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public Message[] getMessagesByUID( long start, long end ) throws MessagingException {
        ensureOpen();
        List<Message> result = new ArrayList<>();
        for( int i = 0; i < messageInfos.size(); i++ ) {
            long uid = uid( messageInfos.get( i ).id );
            if( uid >= start && (end == LASTUID || uid <= end) ) {
                result.add( getMessage( i + 1 ) );
            }
        }
        return result.toArray( Message[]::new );
    }

    /** {@inheritDoc} */
    @Override
    public Message[] getMessagesByUID( long[] uids ) throws MessagingException {
        List<Message> result = new ArrayList<>();
        for( long uid : uids ) {
            Message message = getMessageByUID( uid );
            if( message != null ) {
                result.add( message );
            }
        }
        return result.toArray( Message[]::new );
    }

    /** {@inheritDoc} */
    @Override
    public long getUID( Message message ) throws MessagingException {
        if( !(message instanceof GraphMessage) || message.getFolder() != this ) {
            throw new MessagingException( "Message does not belong to this graph folder" );
        }
        return uid( ((GraphMessage)message).graphId() );
    }

    /** {@inheritDoc} */
    @Override
    public long getUIDNext() {
        return -1;
    }

    /** Returns the connected Graph client. */
    GraphClient client() throws StoreClosedException {
        return graphStore.client();
    }

    /** Ensures that the folder is open for mutation. */
    void ensureWritable() throws MessagingException {
        ensureOpen();
        if( mode != READ_WRITE ) {
            throw new IllegalStateException( "Folder is not open in READ_WRITE mode" );
        }
    }

    private FolderInfo resolve() throws MessagingException {
        if( root ) {
            return null;
        }
        if( info != null ) {
            return info;
        }
        FolderInfo parent = null;
        StringBuilder resolvedPath = new StringBuilder();
        for( String segment : fullName.split( "/" ) ) {
            FolderInfo found = null;
            String normalizedSegment = segment.toLowerCase( Locale.ROOT );
            if( parent == null && WELL_KNOWN_FOLDERS.contains( normalizedSegment ) ) {
                String path = graphStore.client().mailbox( "mailFolders/" + normalizedSegment ) + '?' + FOLDER_SELECT;
                found = FolderInfo.from( graphStore.client().getObject( path ) );
            } else {
                for( Map<String, Object> candidateValue : graphStore.client().getAll( childFoldersPath( parent ) ) ) {
                    FolderInfo candidate = FolderInfo.from( candidateValue );
                    if( candidate.name.equalsIgnoreCase( segment ) ) {
                        found = candidate;
                        break;
                    }
                }
            }
            if( found == null ) {
                throw new FolderNotFoundGraphException( "Graph folder does not exist: " + fullName );
            }
            parent = found;
            if( resolvedPath.length() > 0 ) {
                resolvedPath.append( '/' );
            }
            resolvedPath.append( found.name );
        }
        fullName = resolvedPath.toString();
        info = parent;
        return info;
    }

    private String childFoldersPath( FolderInfo parent ) throws StoreClosedException {
        String relative = parent == null ? "mailFolders" : "mailFolders/" + GraphClient.pathSegment( parent.id ) + "/childFolders";
        String query = "?$top=100&" + FOLDER_SELECT;
        if( graphStore.includeHiddenFolders() ) {
            query += "&includeHiddenFolders=true";
        }
        return graphStore.client().mailbox( relative ) + query;
    }

    private void collectFolders( FolderInfo parent, String relativeParent, Pattern matcher, boolean recursive, List<Folder> result ) throws MessagingException {
        for( Map<String, Object> value : graphStore.client().getAll( childFoldersPath( parent ) ) ) {
            FolderInfo child = FolderInfo.from( value );
            String relativeName = relativeParent.isEmpty() ? child.name : relativeParent + '/' + child.name;
            if( matcher.matcher( relativeName ).matches() ) {
                String childFullName = fullName.isEmpty() ? relativeName : fullName + '/' + relativeName;
                result.add( new GraphFolder( graphStore, childFullName, child ) );
            }
            if( recursive && child.childCount > 0 ) {
                collectFolders( child, relativeName, matcher, true, result );
            }
        }
    }

    private String createFolderPath( FolderInfo parent ) throws StoreClosedException {
        return graphStore.client().mailbox( parent == null ? "mailFolders" : "mailFolders/" + GraphClient.pathSegment( parent.id ) + "/childFolders" );
    }

    private void ensureOpen() throws FolderClosedException {
        if( !isOpen() ) {
            throw new FolderClosedException( this, "Graph folder is not open" );
        }
    }

    private void ensureClosed() {
        if( open ) {
            throw new IllegalStateException( "Folder must be closed" );
        }
    }

    private static Pattern mailPattern( String pattern ) {
        StringBuilder regex = new StringBuilder( "(?i)" );
        for( int i = 0; i < pattern.length(); i++ ) {
            char ch = pattern.charAt( i );
            if( ch == '*' ) {
                regex.append( ".*" );
            } else if( ch == '%' ) {
                regex.append( "[^/]*" );
            } else {
                regex.append( Pattern.quote( String.valueOf( ch ) ) );
            }
        }
        return Pattern.compile( regex.toString() );
    }

    private static String parentName( String name ) {
        int separator = name.lastIndexOf( '/' );
        return separator < 0 ? "" : name.substring( 0, separator );
    }

    private static long uid( String value ) {
        // FNV-1a provides a deterministic positive 63-bit Jakarta Mail UID for opaque Graph ids.
        long hash = 0xcbf29ce484222325L;
        for( byte item : value.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ) {
            hash ^= item & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }

    static final class MessageInfo {
        final String id;
        final boolean read;
        final boolean draft;
        final boolean flagged;
        final Date received;

        MessageInfo( String id, boolean read, boolean draft, boolean flagged, Date received ) {
            this.id = id;
            this.read = read;
            this.draft = draft;
            this.flagged = flagged;
            this.received = received;
        }

        static MessageInfo from( Map<String, Object> value ) throws MessagingException {
            String id = string( value, "id" );
            Object flag = value.get( "flag" );
            boolean flagged = flag instanceof Map && "flagged".equals( ((Map<?, ?>)flag).get( "flagStatus" ) );
            return new MessageInfo( id, bool( value, "isRead" ), bool( value, "isDraft" ), flagged, date( value.get( "receivedDateTime" ) ) );
        }
    }

    private static final class FolderInfo {
        final String id;
        String name;
        int totalCount;
        int unreadCount;
        final int childCount;

        FolderInfo( String id, String name, int totalCount, int unreadCount, int childCount ) {
            this.id = id;
            this.name = name;
            this.totalCount = totalCount;
            this.unreadCount = unreadCount;
            this.childCount = childCount;
        }

        static FolderInfo from( Map<String, Object> value ) throws MessagingException {
            return new FolderInfo( string( value, "id" ), string( value, "displayName" ), integer( value, "totalItemCount" ), integer( value, "unreadItemCount" ),
                            integer( value, "childFolderCount" ) );
        }
    }

    private static String string( Map<String, Object> object, String key ) throws MessagingException {
        Object value = object.get( key );
        if( value instanceof String ) {
            return (String)value;
        }
        throw new MessagingException( "Microsoft Graph response has no string property '" + key + "'" );
    }

    private static int integer( Map<String, Object> object, String key ) {
        Object value = object.get( key );
        return value instanceof Number ? ((Number)value).intValue() : 0;
    }

    private static boolean bool( Map<String, Object> object, String key ) {
        return Boolean.TRUE.equals( object.get( key ) );
    }

    private static Date date( Object value ) {
        if( value instanceof String ) {
            try {
                return Date.from( Instant.parse( (String)value ) );
            } catch( DateTimeParseException ignored ) {
                // Leave unavailable dates as null, as required by the Message API.
            }
        }
        return null;
    }

    private static final class FolderNotFoundGraphException extends MessagingException {
        private static final long serialVersionUID = 1L;

        FolderNotFoundGraphException( String message ) {
            super( message );
        }
    }
}
