package dev.coachbot.transport.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.request.SendChatAction;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.GetUpdatesResponse;
import dev.coachbot.transport.InboundMessage;
import dev.coachbot.transport.InboundMessageHandler;
import dev.coachbot.transport.TransportPlugin;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Telegram transport backed by long-polling (no webhook required).
 * Enabled when {@code bot.transports.telegram.enabled=true}.
 *
 * <h2>Setup</h2>
 * <ol>
 *   <li>Create a bot via @BotFather, copy the token.</li>
 *   <li>Set {@code TELEGRAM_BOT_TOKEN=123456:ABC-DEF…} in the environment.</li>
 *   <li>Add the bot to a chat and find the chat ID (e.g. @username_to_id_bot).</li>
 *   <li>Set {@code TELEGRAM_CHAT_ID} in the seed transport config.</li>
 * </ol>
 *
 * <h2>Chat ID in agent config</h2>
 * The {@code chat_id} stored in {@code agent_transports} must match the Telegram
 * chat ID exactly as reported by the API — negative for groups/channels
 * (e.g. {@code -1001234567890}), positive for private chats.
 *
 * <h2>Trigger in groups</h2>
 * In group chats the bot receives all messages. Set {@code require-trigger: true}
 * on the agent so it only responds when addressed (e.g. {@code @Andy hello}).
 */
@Component
@ConditionalOnProperty(prefix = "bot.transports.telegram", name = "enabled", havingValue = "true")
public class TelegramTransport implements TransportPlugin {

    private static final Logger log = LoggerFactory.getLogger(TelegramTransport.class);

    /** Long-poll timeout in seconds. Telegram will hold the connection up to this long. */
    private static final int LONG_POLL_TIMEOUT_SECONDS = 30;
    /** Pause between retries after a network/API error. */
    private static final long RETRY_PAUSE_MS = 5_000;

    private final TelegramBot bot;
    private final AtomicBoolean running   = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private Thread pollingThread;

    public TelegramTransport(
            @Value("${bot.transports.telegram.token}") String token) {
        this.bot = new TelegramBot(token);
    }

    @PostConstruct
    public void validate() {
        // Verify the token works at startup — fail fast with a clear message
        var response = bot.execute(new com.pengrad.telegrambot.request.GetMe());
        if (!response.isOk()) {
            throw new IllegalStateException(
                    "Telegram token is invalid or unreachable: " + response.description());
        }
        log.info("Telegram transport validated (bot=@{})", response.user().username());
    }

    // ── TransportPlugin ────────────────────────────────────────────────────────

    @Override
    public String id() { return "telegram"; }

    @Override
    public void start(InboundMessageHandler handler) {
        running.set(true);
        pollingThread = Thread.ofVirtual()
                .name("telegram-polling")
                .start(() -> pollLoop(handler));
        log.info("Telegram long-polling started");
    }

    @Override
    public void send(String chatId, String text) {
        if (!StringUtils.hasText(text)) return;
        // Telegram has a 4096-char limit per message — split if needed
        for (String chunk : splitIfNeeded(text, 4096)) {
            var response = bot.execute(new SendMessage(chatId, chunk));
            if (!response.isOk()) {
                log.warn("Telegram sendMessage failed for chat {}: {}", chatId, response.description());
            }
        }
    }

    @Override
    public void sendTyping(String chatId) {
        bot.execute(new SendChatAction(chatId, "typing"));
    }

    @Override
    public boolean isConnected() { return connected.get(); }

    @Override
    public void stop() {
        running.set(false);
        if (pollingThread != null) pollingThread.interrupt();
        log.info("Telegram transport stopped");
    }

    // ── Polling loop ───────────────────────────────────────────────────────────

    private void pollLoop(InboundMessageHandler handler) {
        int offset = 0;

        while (running.get()) {
            try {
                GetUpdatesResponse response = bot.execute(
                        new GetUpdates()
                                .limit(100)
                                .timeout(LONG_POLL_TIMEOUT_SECONDS)
                                .offset(offset));

                if (!response.isOk()) {
                    log.warn("Telegram getUpdates error {}: {}",
                            response.errorCode(), response.description());
                    connected.set(false);
                    sleep(RETRY_PAUSE_MS);
                    continue;
                }

                connected.set(true);
                List<Update> updates = response.updates();

                for (Update update : updates) {
                    try {
                        dispatchUpdate(update, handler);
                    } catch (Exception e) {
                        log.error("Error dispatching Telegram update {}", update.updateId(), e);
                    }
                    offset = update.updateId() + 1;
                }

            } catch (Exception e) {
                if (!running.get()) break;
                log.warn("Telegram polling exception: {}", e.getMessage());
                connected.set(false);
                sleep(RETRY_PAUSE_MS);
                if (Thread.currentThread().isInterrupted()) break;
            }
        }
    }

    private void dispatchUpdate(Update update, InboundMessageHandler handler) {
        Message msg = update.message();
        if (msg == null) return;                          // edited message, callback, etc.

        String text = msg.text();
        if (!StringUtils.hasText(text)) {
            log.debug("Skipping non-text Telegram message in chat {}", msg.chat().id());
            return;
        }

        User from = msg.from();
        String senderId   = from != null ? String.valueOf(from.id()) : "unknown";
        String senderName = from != null ? displayName(from) : "unknown";
        String chatId     = String.valueOf(msg.chat().id());

        handler.onMessage(new InboundMessage(
                id(), chatId, senderId, senderName, text, Instant.now()));

        log.debug("Telegram message from {} in {}: '{}'",
                senderName, chatId, truncate(text, 60));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String displayName(User user) {
        String first = user.firstName() != null ? user.firstName() : "";
        String last  = user.lastName()  != null ? " " + user.lastName() : "";
        String full  = (first + last).trim();
        return full.isEmpty() && user.username() != null ? user.username() : full;
    }

    private static List<String> splitIfNeeded(String text, int limit) {
        if (text.length() <= limit) return List.of(text);
        // Split on newline boundaries where possible to avoid mid-sentence cuts
        java.util.List<String> chunks = new java.util.ArrayList<>();
        while (text.length() > limit) {
            int cut = text.lastIndexOf('\n', limit);
            if (cut <= 0) cut = limit;
            chunks.add(text.substring(0, cut));
            text = text.substring(cut).stripLeading();
        }
        if (!text.isEmpty()) chunks.add(text);
        return chunks;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
