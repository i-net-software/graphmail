# Jakarta Mail provider for Microsoft Graph

[![CI](https://github.com/i-net-software/graphmail/actions/workflows/ci.yml/badge.svg)](https://github.com/i-net-software/graphmail/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/de.inetsoftware/graphmail?label=Maven%20Central)](https://central.sonatype.com/artifact/de.inetsoftware/graphmail)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

GraphMail provides Jakarta Mail access to Microsoft 365 mailboxes through the
Microsoft Graph API. It implements the symmetric protocols `msgraph-store` for
mailbox access and `msgraph-send` for sending RFC 822 messages. Jakarta Mail
keeps a single provider per protocol name, so store and transport need distinct
protocol names.

## Installation

GraphMail requires Java 21 or newer. Once version `0.1` is available from Maven
Central, add it to a Gradle build with:

```groovy
implementation 'jakarta.mail:jakarta.mail-api:2.1.5'
implementation 'org.eclipse.angus:angus-mail:2.0.5'
implementation 'de.inetsoftware:graphmail:0.1'
```

`jakarta.mail-api` is the API used by GraphMail. Angus Mail supplies the Jakarta
Mail runtime implementation and also provides the standard SMTP and IMAP
providers when an application needs them in addition to Microsoft Graph.

## Microsoft Entra ID permissions

To use both GraphMail protocols, register these Microsoft Graph API permissions
for the application:

| Mode | Permission type | Required permissions | Consent |
| --- | --- | --- | --- |
| Delegated (user mode) | Delegated | `Mail.ReadWrite`, `Mail.Send` | User or administrator, according to the tenant policy |
| App-only (client credentials) | Application | `Mail.ReadWrite`, `Mail.Send` | Administrator consent is required |

`Mail.ReadWrite` permits mailbox and message operations but does not include
sending mail. `Mail.Send` is therefore required separately. Applications that
use only one of the two protocols can grant only its corresponding permission.
See the [Microsoft Graph permissions reference](https://learn.microsoft.com/en-us/graph/permissions-reference#mailreadwrite).

Application permissions normally apply tenant-wide. For production deployments,
consider restricting the accessible mailboxes with
[Exchange Online Application RBAC](https://learn.microsoft.com/en-us/exchange/permissions-exo/application-rbac).

## Access token scopes

GraphMail does not acquire tokens itself. The application obtains a Microsoft
Graph access token and supplies it as the Jakarta Mail password.

For delegated access, request the following space-separated scopes during user
authorization:

```text
https://graph.microsoft.com/Mail.ReadWrite https://graph.microsoft.com/Mail.Send
```

Add `offline_access` if the application also needs a refresh token. Scopes such
as `openid` or `profile` are needed only when the application additionally uses
OpenID Connect for user sign-in; GraphMail itself does not require them.

For app-only access with the OAuth 2.0 client credentials flow, request exactly:

```text
https://graph.microsoft.com/.default
```

Individual permissions such as `Mail.Send` must not be appended to `.default`.
They are configured as application permissions on the app registration and are
returned as roles after administrator consent. See Microsoft's documentation on
the [`.default` scope](https://learn.microsoft.com/en-us/entra/identity-platform/scopes-oidc#the-default-scope)
and the [client credentials flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-client-creds-grant-flow).

A successfully issued token does not by itself prove that the required Graph
permissions were granted. For delegated tokens, check the `scp` claim; for
app-only tokens, check the `roles` claim for `Mail.ReadWrite` and `Mail.Send`.
Missing permissions typically result in an HTTP 403 response from Microsoft
Graph.

## Configuration

The OAuth access token is the Jakarta Mail password, as it is for XOAUTH2
providers. It can be passed to `connect`, supplied through a
`jakarta.mail.Authenticator`, or stored with `Session.setPasswordAuthentication`.
The Jakarta Mail connection user is the mailbox to access. Use `me` for the
delegated-token endpoint, or pass a mailbox address or Microsoft Graph user id
for application-permission access. As usual in Jakarta Mail, the user can be
passed to `connect` or configured with `mail.<protocol>.user` or `mail.user`.

```java
Properties properties = new Properties();

Session session = Session.getInstance(properties, new Authenticator() {
    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication("me", accessToken());
    }
});

try (Store store = session.getStore("msgraph-store")) {
    store.connect();
    Folder inbox = store.getFolder("Inbox");
    inbox.open(Folder.READ_ONLY);
    Message first = inbox.getMessage(1);
}

try (Transport transport = session.getTransport("msgraph-send")) {
    transport.connect();
    transport.sendMessage(message, message.getAllRecipients());
}
```

Optional properties:

| Property | Default | Meaning |
| --- | --- | --- |
| `mail.msgraph-store.baseurl` / `mail.msgraph-send.baseurl` | `https://graph.microsoft.com/v1.0/` | Graph API root; useful for sovereign clouds and tests |
| `mail.msgraph-store.connectiontimeout` / `mail.msgraph-send.connectiontimeout` | `10000` | Establishing an HTTP connection, in milliseconds |
| `mail.msgraph-store.timeout` / `mail.msgraph-send.timeout` | `60000` | Completing an HTTP request, in milliseconds |
| `mail.msgraph-store.connectionpooltimeout` / `mail.msgraph-send.connectionpooltimeout` | `45000` | Idle time after which the internal HTTP client and its connection pool are replaced |
| `mail.msgraph-store.forcepasswordrefresh` / `mail.msgraph-send.forcepasswordrefresh` | `false` | Ask the Jakarta Mail `Authenticator` for a fresh password/token before every Graph request |
| `mail.msgraph-store.includehiddenfolders` | `false` | Include hidden mail folders when listing |

All settings are protocol-specific: use `mail.msgraph-store.*` for mailbox access
and `mail.msgraph-send.*` for sending. For example,
`mail.msgraph-send.timeout=120000` only changes sending.

With `forcepasswordrefresh`, the provider asks the configured `Authenticator`
for a new token before each Graph request. The `Authenticator` remains
responsible for actually acquiring or refreshing that token.

## Building from source

The build requires JDK 21 and Gradle 9:

```shell
gradle clean build
```

## License

GraphMail is available under the [MIT License](LICENSE).
