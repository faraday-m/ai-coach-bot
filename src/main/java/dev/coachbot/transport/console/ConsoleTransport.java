package dev.coachbot.transport.console;

import dev.coachbot.transport.InboundMessage;
import dev.coachbot.transport.InboundMessageHandler;
import dev.coachbot.transport.TransportPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Scanner;

/**
 * Stdin/stdout transport for local development and testing.
 * Reads lines from stdin, writes responses to stdout.
 * Enabled when {@code bot.transports.console.enabled=true} (default: true).
 */
@Component
@ConditionalOnProperty(prefix = "bot.transports.console", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ConsoleTransport implements TransportPlugin {

    private static final Logger log = LoggerFactory.getLogger(ConsoleTransport.class);

    static final String CHAT_ID   = "console";
    static final String SENDER_ID = "console-user";

    private volatile boolean connected = false;

    @Override
    public String id() {
        return "console";
    }

    @Override
    public void start(InboundMessageHandler handler) {
        connected = true;
        // Platform thread (non-daemon) keeps JVM alive even when all virtual threads finish
        Thread.ofPlatform().daemon(false).name("console-input").start(() -> {
            System.out.println("┌─────────────────────────────────────────┐");
            System.out.println("│  coach-bot console  ·  type to chat     │");
            System.out.println("│  Ctrl+C to quit                         │");
            System.out.println("└─────────────────────────────────────────┘");
            System.out.println();

            var scanner = new Scanner(System.in);
            while (connected && scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) {
                    handler.onMessage(new InboundMessage(
                            "console", CHAT_ID, SENDER_ID, "You", line, Instant.now()));
                }
            }
        });
    }

    @Override
    public void send(String chatId, String text) {
        // Print each response on its own line, visually separated
        System.out.println();
        for (String line : text.split("\n")) {
            System.out.println("  " + line);
        }
        System.out.println();
        System.out.print("> ");
        System.out.flush();
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void stop() {
        connected = false;
    }
}
