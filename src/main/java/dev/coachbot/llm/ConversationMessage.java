package dev.coachbot.llm;

/**
 * A single turn in a conversation, transport- and LLM-agnostic.
 * Each {@link dev.coachbot.core.GroupSession} keeps per-user history as a list of these.
 */
public record ConversationMessage(Role role, String content) {

    public enum Role { USER, ASSISTANT }

    public static ConversationMessage user(String content) {
        return new ConversationMessage(Role.USER, content);
    }

    public static ConversationMessage assistant(String content) {
        return new ConversationMessage(Role.ASSISTANT, content);
    }
}
