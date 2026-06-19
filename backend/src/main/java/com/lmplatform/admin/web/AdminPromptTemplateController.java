package com.lmplatform.admin.web;

import com.lmplatform.chat.model.PromptTemplate;
import com.lmplatform.chat.model.PromptTemplateRequest;
import com.lmplatform.chat.repository.ConversationRepository;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only maintenance API for platform-owned system prompt templates. */
@RestController
@RequestMapping("/api/admin/prompt-templates")
public class AdminPromptTemplateController {

    private final ConversationRepository repository;

    public AdminPromptTemplateController(ConversationRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<PromptTemplate> templates() {
        return repository.listSystemPromptTemplates();
    }

    @PostMapping
    public PromptTemplate create(@Valid @RequestBody PromptTemplateRequest request) {
        return repository.createPromptTemplate(
                null, "system", request.name(), request.description(),
                request.systemPrompt(), request.enabled()
        );
    }

    @PatchMapping("/{templateId}")
    public ResponseEntity<Void> update(
            @PathVariable UUID templateId,
            @Valid @RequestBody PromptTemplateRequest request
    ) {
        return repository.updateSystemPromptTemplate(
                templateId, request.name(), request.description(),
                request.systemPrompt(), request.enabled()
        ) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> disable(@PathVariable UUID templateId) {
        PromptTemplate current = repository.listSystemPromptTemplates().stream()
                .filter(template -> template.id().equals(templateId)).findFirst().orElse(null);
        if (current == null) {
            return ResponseEntity.notFound().build();
        }
        repository.updateSystemPromptTemplate(
                templateId, current.name(), current.description(), current.systemPrompt(), false
        );
        return ResponseEntity.noContent().build();
    }
}
