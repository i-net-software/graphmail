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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import jakarta.mail.Authenticator;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

public class GraphProviderTest {

    private HttpServer server;
    private final List<Request> requests = new ArrayList<>();

    @BeforeEach
    public void startServer() throws IOException {
        server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
        server.createContext( "/", this::handle );
        server.start();
    }

    @AfterEach
    public void stopServer() {
        server.stop( 0 );
    }

    @Test
    public void readsMessageAndUpdatesSeenFlag() throws Exception {
        Session session = session();
        try( Store store = session.getStore( GraphProperties.STORE_PROTOCOL ) ) {
            assertEquals( GraphStore.class, store.getClass() );
            store.connect();
            Folder inbox = store.getFolder( "Inbox" );
            assertTrue( inbox.exists() );
            assertEquals( "Posteingang", inbox.getName() );
            assertEquals( 1, inbox.getMessageCount() );

            inbox.open( Folder.READ_WRITE );
            Message message = inbox.getMessage( 1 );
            assertEquals( "Graph subject", message.getSubject() );
            assertFalse( message.isSet( Flags.Flag.SEEN ) );
            message.setFlag( Flags.Flag.SEEN, true );
            assertTrue( message.isSet( Flags.Flag.SEEN ) );
            assertTrue( requests.stream().anyMatch( request -> request.method.equals( "PATCH" )
                            && request.path.equals( "/v1.0/me/messages/message-1" )
                            && request.body.contains( "\"isRead\":true" ) ) );
            inbox.close( false );
        }
    }

    @Test
    public void sendsMimeMessage() throws Exception {
        Session session = session();
        MimeMessage message = new MimeMessage( session );
        message.setFrom( new InternetAddress( "sender@example.test" ) );
        message.setRecipient( Message.RecipientType.TO, new InternetAddress( "receiver@example.test" ) );
        message.setSubject( "Sent through Graph" );
        message.setText( "Body" );

        try( Transport transport = session.getTransport( GraphProperties.TRANSPORT_PROTOCOL ) ) {
            assertEquals( GraphTransport.class, transport.getClass() );
            transport.connect();
            transport.sendMessage( message, message.getAllRecipients() );
        }

        Request send = requests.stream().filter( request -> request.path.equals( "/v1.0/me/sendMail" ) ).findFirst().orElse( null );
        assertNotNull( send );
        String mime = new String( Base64.getDecoder().decode( send.body ), StandardCharsets.UTF_8 );
        assertTrue( mime.contains( "Subject: Sent through Graph" ) );
        assertEquals( "Bearer test-token", send.authorization );
    }

    @Test
    public void usesConnectionUserAsMailbox() throws Exception {
        Session session = session();
        MimeMessage message = new MimeMessage( session );
        message.setFrom( new InternetAddress( "sender@example.test" ) );
        message.setRecipient( Message.RecipientType.TO, new InternetAddress( "receiver@example.test" ) );
        message.setSubject( "Sent through Graph" );
        message.setText( "Body" );

        try( Transport transport = session.getTransport( GraphProperties.TRANSPORT_PROTOCOL ) ) {
            transport.connect( "mailbox@example.test", "test-token" );
            transport.sendMessage( message, message.getAllRecipients() );
        }

        assertTrue( requests.stream().anyMatch( request -> request.path.equals( "/v1.0/users/mailbox@example.test/sendMail" ) ) );
    }

    @Test
    public void forcePasswordRefreshUsesAuthenticatorForEveryRequest() throws Exception {
        AtomicInteger authentications = new AtomicInteger();
        Properties properties = baseProperties();
        properties.setProperty( GraphProperties.property( GraphProperties.STORE_PROTOCOL, GraphProperties.FORCE_PASSWORD_REFRESH ), "true" );
        Session session = Session.getInstance( properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication( "me", "token-" + authentications.incrementAndGet() );
            }
        } );

        try( Store store = session.getStore( GraphProperties.STORE_PROTOCOL ) ) {
            store.connect();
            assertTrue( store.getFolder( "Inbox" ).exists() );
        }

        assertEquals( 2, authentications.get() );
        assertEquals( "Bearer token-2", requests.get( 0 ).authorization );
    }

    private Session session() {
        Properties properties = baseProperties();
        return Session.getInstance( properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication( "me", "test-token" );
            }
        } );
    }

    private Properties baseProperties() {
        Properties properties = new Properties();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1.0/";
        configureProtocol( properties, GraphProperties.STORE_PROTOCOL, baseUrl );
        configureProtocol( properties, GraphProperties.TRANSPORT_PROTOCOL, baseUrl );
        return properties;
    }

    private static void configureProtocol( Properties properties, String protocol, String baseUrl ) {
        properties.setProperty( GraphProperties.property( protocol, GraphProperties.BASE_URL ), baseUrl );
        properties.setProperty( GraphProperties.property( protocol, GraphProperties.CONNECTION_TIMEOUT ), "3000" );
        properties.setProperty( GraphProperties.property( protocol, GraphProperties.TIMEOUT ), "3000" );
        properties.setProperty( GraphProperties.property( protocol, GraphProperties.CONNECTION_POOL_TIMEOUT ), "3000" );
    }

    private void handle( HttpExchange exchange ) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String( exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8 );
        requests.add( new Request( exchange.getRequestMethod(), path, body, exchange.getRequestHeaders().getFirst( "Authorization" ) ) );
        String query = exchange.getRequestURI().getRawQuery();
        if( query != null && query.contains( "wellKnownName" ) ) {
            json( exchange, 400, "{\"error\":{\"code\":\"BadRequest\",\"message\":\"Unknown mailFolder property\"}}" );
        } else if( path.equals( "/v1.0/me/mailFolders/inbox" ) ) {
            json( exchange, 200, "{\"id\":\"folder-1\",\"displayName\":\"Posteingang\",\"totalItemCount\":1,\"unreadItemCount\":1,\"childFolderCount\":0}" );
        } else if( path.equals( "/v1.0/me/mailFolders" ) ) {
            json( exchange, 200, "{\"value\":[{\"id\":\"folder-1\",\"displayName\":\"Posteingang\",\"totalItemCount\":1,\"unreadItemCount\":1,\"childFolderCount\":0}]}" );
        } else if( path.equals( "/v1.0/me/mailFolders/folder-1/messages" ) ) {
            json( exchange, 200, "{\"value\":[{\"id\":\"message-1\",\"isRead\":false,\"isDraft\":false,\"receivedDateTime\":\"2026-09-10T10:15:30Z\",\"flag\":{\"flagStatus\":\"notFlagged\"}}]}" );
        } else if( path.equals( "/v1.0/me/messages/message-1/$value" ) ) {
            bytes( exchange, 200, "From: sender@example.test\r\nTo: receiver@example.test\r\nSubject: Graph subject\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nHello".getBytes( StandardCharsets.UTF_8 ) );
        } else if( path.equals( "/v1.0/me/messages/message-1" ) && exchange.getRequestMethod().equals( "PATCH" ) ) {
            bytes( exchange, 200, "{}".getBytes( StandardCharsets.UTF_8 ) );
        } else if( path.equals( "/v1.0/me/sendMail" ) ) {
            bytes( exchange, 202, new byte[0] );
        } else if( path.equals( "/v1.0/users/mailbox@example.test/sendMail" ) ) {
            bytes( exchange, 202, new byte[0] );
        } else {
            json( exchange, 404, "{\"error\":{\"code\":\"NotFound\",\"message\":\"Not found\"}}" );
        }
    }

    private static void json( HttpExchange exchange, int status, String json ) throws IOException {
        exchange.getResponseHeaders().set( "Content-Type", "application/json" );
        bytes( exchange, status, json.getBytes( StandardCharsets.UTF_8 ) );
    }

    private static void bytes( HttpExchange exchange, int status, byte[] body ) throws IOException {
        exchange.sendResponseHeaders( status, body.length == 0 ? -1 : body.length );
        if( body.length > 0 ) {
            exchange.getResponseBody().write( body );
        }
        exchange.close();
    }

    private static final class Request {
        final String method;
        final String path;
        final String body;
        final String authorization;

        Request( String method, String path, String body, String authorization ) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.authorization = authorization;
        }
    }
}
