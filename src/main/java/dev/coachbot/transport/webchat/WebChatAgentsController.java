package dev.coachbot.transport.webchat;

import dev.coachbot.core.AgentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes the list of enabled agents to the React web-chat frontend.
 *
 * <p>Intentionally separate from {@link WebChatController} because that class
 * has a class-level {@code @RequestMapping("/api/chat/{agentId}")} which would
 * make any new endpoint relative to that base path.
 *
 * <p>Not conditional on webchat being enabled — the agent list is read-only
 * metadata needed as soon as the frontend loads, regardless of transport config.
 */
@RestController
@RequestMapping("/api/agents")
public class WebChatAgentsController {

    private final AgentRepository agentRepository;

    public WebChatAgentsController(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    /**
     * Returns all enabled agents visible to the web chat.
     * Used by the frontend to populate the agent selector on load.
     */
    @GetMapping
    public List<AgentDto> listAgents() {
        return agentRepository.findAllEnabled().stream()
                .map(a -> new AgentDto(a.id(), a.name()))
                .toList();
    }

    public record AgentDto(String id, String name) {}
}
