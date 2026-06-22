package com.lmplatform.chat.web;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.chat.model.ChatStreamRequest;
import com.lmplatform.chat.model.BranchConversationRequest;
import com.lmplatform.chat.model.ConversationMessage;
import com.lmplatform.chat.model.ConversationSummary;
import com.lmplatform.chat.model.EditMessageRequest;
import com.lmplatform.chat.model.PromptTemplate;
import com.lmplatform.chat.model.PromptTemplateRequest;
import com.lmplatform.chat.model.RegenerateMessageRequest;
import com.lmplatform.chat.model.RenameConversationRequest;
import com.lmplatform.chat.repository.ConversationRepository;
import com.lmplatform.chat.service.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** HTTP API for model discovery, streaming chat, and conversation history management. */
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

    /** Proxies registered models so browser clients never call the AI service directly. */
    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public String models() {
        return aiServiceClient.listModels();
    }

    /** Opens a persisted streaming turn and disables intermediary buffering. */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@Valid @RequestBody ChatStreamRequest request) {
        long userId = repository.ensureDevelopmentUser();
        StreamingResponseBody body = output -> chatService.stream(userId, request, output);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    /** Lists conversations belonging to the current development user. */
    @GetMapping("/conversations")
    public List<ConversationSummary> conversations() {
        long userId = repository.ensureDevelopmentUser();
        return repository.listConversations(userId);
    }

    /** Restores ordered messages for one owned conversation. */
    @GetMapping("/conversations/{conversationId}/messages")
    public List<ConversationMessage> messages(@PathVariable UUID conversationId) {
        long userId = repository.ensureDevelopmentUser();
        return repository.listMessages(conversationId, userId);
    }

    /** Renames an owned conversation and protects the manual title from async replacement. */
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

    /** Soft-deletes an owned conversation while retaining its audit and usage history. */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable UUID conversationId) {
        long userId = repository.ensureDevelopmentUser();
        return repository.softDeleteConversation(conversationId, userId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Edits one user message and preserves later history as superseded audit records. */
    @PatchMapping("/conversations/{conversationId}/messages/{messageId}")
    public ResponseEntity<Void> editMessage(
            @PathVariable UUID conversationId,
            @PathVariable UUID messageId,
            @Valid @RequestBody EditMessageRequest request
    ) {
        long userId = repository.ensureDevelopmentUser();
        return repository.editUserMessage(conversationId, messageId, userId, request.content())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Creates a durable branch through the selected active message. */
    @PostMapping("/conversations/{conversationId}/branches")
    public ResponseEntity<ConversationBranchResponse> branchConversation(
            @PathVariable UUID conversationId,
            @RequestBody BranchConversationRequest request
    ) {
        long userId = repository.ensureDevelopmentUser();
        UUID branchId = repository.branchConversation(conversationId, request.messageId(), userId);
        return branchId == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(new ConversationBranchResponse(branchId));
    }

    /** Lists reusable system and user-owned prompt templates. */
    @GetMapping("/prompt-templates")
    public List<PromptTemplate> promptTemplates() {
        return repository.listPromptTemplates(repository.ensureDevelopmentUser());
    }

    /** Creates a personal template owned exclusively by the current user. */
    @PostMapping("/prompt-templates")
    public PromptTemplate createPromptTemplate(@Valid @RequestBody PromptTemplateRequest request) {
        long userId = repository.ensureDevelopmentUser();
        return repository.createPromptTemplate(
                userId, "personal", request.name(), request.description(),
                request.systemPrompt(), true
        );
    }

    /** Updates only a personal template owned by the current user. */
    @PatchMapping("/prompt-templates/{templateId}")
    public ResponseEntity<Void> updatePromptTemplate(
            @PathVariable UUID templateId,
            @Valid @RequestBody PromptTemplateRequest request
    ) {
        long userId = repository.ensureDevelopmentUser();
        return repository.updatePersonalPromptTemplate(
                templateId, userId, request.name(), request.description(),
                request.systemPrompt(), true
        ) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** Disables an owned personal template without breaking historical references. */
    @DeleteMapping("/prompt-templates/{templateId}")
    public ResponseEntity<Void> deletePromptTemplate(@PathVariable UUID templateId) {
        long userId = repository.ensureDevelopmentUser();
        return repository.disablePersonalPromptTemplate(templateId, userId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Replaces one assistant answer while preserving its previous version for audit. */
    @PostMapping(
            value = "/conversations/{conversationId}/messages/{messageId}/regenerate",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<StreamingResponseBody> regenerate(
            @PathVariable UUID conversationId,
            @PathVariable UUID messageId,
            @Valid @RequestBody RegenerateMessageRequest request
    ) {
        long userId = repository.ensureDevelopmentUser();
        StreamingResponseBody body = output ->
                chatService.regenerate(userId, conversationId, messageId, request.model(), output);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    private record ConversationBranchResponse(UUID conversationId) {
    }
}
