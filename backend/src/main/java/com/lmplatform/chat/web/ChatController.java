package com.lmplatform.chat.web;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.chat.model.ChatStreamRequest;
import com.lmplatform.chat.model.ConversationMessage;
import com.lmplatform.chat.model.ConversationSummary;
import com.lmplatform.chat.model.RenameConversationRequest;
import com.lmplatform.chat.repository.ConversationRepository;
import com.lmplatform.chat.service.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    private final ConversationRepository repository;
    private final AiServiceClient aiServiceClient;

    public ChatController(
            ChatService chatService,
            ConversationRepository repository,
            AiServiceClient aiServiceClient
    ) {
        this.chatService = chatService;
        this.repository = repository;
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public String models() {
        return aiServiceClient.listModels();
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@Valid @RequestBody ChatStreamRequest request) {
        StreamingResponseBody body = output -> chatService.stream(request, output);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    @GetMapping("/conversations")
    public List<ConversationSummary> conversations() {
        long userId = repository.ensureDevelopmentUser();
        return repository.listConversations(userId);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<ConversationMessage> messages(@PathVariable UUID conversationId) {
        long userId = repository.ensureDevelopmentUser();
        return repository.listMessages(conversationId, userId);
    }

    @PatchMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> renameConversation(
            @PathVariable UUID conversationId,
            @Valid @RequestBody RenameConversationRequest request
    ) {
        long userId = repository.ensureDevelopmentUser();
        return repository.renameConversation(conversationId, userId, request.title())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
