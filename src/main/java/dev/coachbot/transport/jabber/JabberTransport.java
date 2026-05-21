package dev.coachbot.transport.jabber;

import dev.coachbot.transport.InboundMessage;
import dev.coachbot.transport.InboundMessageHandler;
import dev.coachbot.transport.TransportPlugin;
import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ReconnectionManager;
import org.jivesoftware.smack.chat2.Chat;
import org.jivesoftware.smack.chat2.ChatManager;
import org.jivesoftware.smackx.chatstates.ChatState;
import org.jivesoftware.smackx.chatstates.ChatStateManager;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.time.Instant;

/**
 * Jabber/XMPP transport using Smack 4.4.
 * Enabled when {@code bot.transports.jabber.enabled=true}.
 *
 * <p>The bot connects as a regular XMPP user (not a component) and responds
 * to 1:1 (chat) messages. Each remote JID is treated as both {@code chatId}
 * and {@code senderId} — this maps cleanly to the Orchestrator routing model.
 *
 * <h2>Minimal .env</h2>
 * <pre>
 * BOT_TRANSPORTS_JABBER_ENABLED=true
 * BOT_JABBER_SERVER=xmpp.example.com
 * BOT_JABBER_USERNAME=coachbot
 * BOT_JABBER_PASSWORD=secret
 * </pre>
 *
 * <h2>Self-signed / local server</h2>
 * Set {@code BOT_JABBER_ACCEPT_ALL_CERTS=true} to bypass TLS certificate
 * validation. Never use this in production.
 */
@Component
@ConditionalOnProperty(prefix = "bot.transports.jabber", name = "enabled", havingValue = "true")
public class JabberTransport implements TransportPlugin {

    private static final Logger log = LoggerFactory.getLogger(JabberTransport.class);

    private final String server;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean acceptAllCerts;

    private volatile AbstractXMPPConnection connection;
    private volatile InboundMessageHandler handler;

    public JabberTransport(
            @Value("${bot.transports.jabber.server}") String server,
            @Value("${bot.transports.jabber.host:}") String host,
            @Value("${bot.transports.jabber.port:5222}") int port,
            @Value("${bot.transports.jabber.username}") String username,
            @Value("${bot.transports.jabber.password}") String password,
            @Value("${bot.transports.jabber.accept-all-certs:false}") boolean acceptAllCerts) {
        this.server         = server;
        this.host           = host.isBlank() ? server : host;
        this.port           = port;
        this.username       = username;
        this.password       = password;
        this.acceptAllCerts = acceptAllCerts;
    }

    @Override
    public String id() { return "jabber"; }

    @Override
    public void start(InboundMessageHandler handler) {
        this.handler = handler;
        Thread.ofVirtual().name("jabber-connect").start(() -> {
            try {
                connection = buildConnection();
                connection.connect();
                connection.login(username, password);
                connection.sendStanza(new Presence(Presence.Type.available));

                ReconnectionManager rm = ReconnectionManager.getInstanceFor(connection);
                rm.setReconnectionPolicy(ReconnectionManager.ReconnectionPolicy.RANDOM_INCREASING_DELAY);
                rm.enableAutomaticReconnection();

                ChatManager.getInstanceFor(connection).addIncomingListener(this::onIncoming);

                log.info("Jabber transport connected as {}@{} ({}:{})",
                        username, server, host, port);
            } catch (Exception e) {
                log.error("Jabber transport failed to connect", e);
            }
        });
    }

    private void onIncoming(EntityBareJid from, Message message, Chat chat) {
        String body = message.getBody();
        if (body == null || body.isBlank()) return;
        String jid  = from.toString();
        String name = from.getLocalpart().asUnescapedString();
        handler.onMessage(new InboundMessage("jabber", jid, jid, name, body, Instant.now()));
    }

    @Override
    public void send(String chatId, String text) {
        if (!isConnected()) {
            log.warn("Jabber not connected — dropping message to {}", chatId);
            return;
        }
        try {
            EntityBareJid jid = JidCreate.entityBareFrom(chatId);
            ChatManager.getInstanceFor(connection).chatWith(jid).send(text);
        } catch (Exception e) {
            log.error("Failed to send Jabber message to {}", chatId, e);
        }
    }

    @Override
    public void sendTyping(String chatId) {
        if (!isConnected()) return;
        try {
            EntityBareJid jid  = JidCreate.entityBareFrom(chatId);
            Chat          chat = ChatManager.getInstanceFor(connection).chatWith(jid);
            ChatStateManager.getInstance(connection).setCurrentState(ChatState.composing, chat);
        } catch (Exception e) {
            log.debug("Could not send typing state to {}: {}", chatId, e.getMessage());
        }
    }

    @Override
    public boolean isConnected() {
        return connection != null && connection.isConnected() && connection.isAuthenticated();
    }

    @Override
    public void stop() {
        if (connection != null) {
            connection.disconnect();
            log.info("Jabber transport disconnected");
        }
    }

    // ── Connection builder ─────────────────────────────────────────────────────

    private AbstractXMPPConnection buildConnection() throws Exception {
        var builder = XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain(server)
                .setHost(host)
                .setPort(port)
                .setSecurityMode(acceptAllCerts
                        ? ConnectionConfiguration.SecurityMode.ifpossible
                        : ConnectionConfiguration.SecurityMode.required);

        if (acceptAllCerts) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            builder.setCustomSSLContext(ctx);
        }

        return new XMPPTCPConnection(builder.build());
    }
}
