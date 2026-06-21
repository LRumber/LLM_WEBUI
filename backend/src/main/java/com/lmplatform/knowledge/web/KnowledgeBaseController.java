package com.lmplatform.knowledge.web;

import com.lmplatform.chat.repository.ConversationRepository;
import com.lmplatform.knowledge.model.KnowledgeBaseRequest;
import com.lmplatform.knowledge.model.KnowledgeBaseSummary;
import com.lmplatform.knowledge.model.KnowledgeDocumentRequest;
import com.lmplatform.knowledge.model.KnowledgeDocumentSummary;
import com.lmplatform.knowledge.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import java.net.URI;
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

/** Personal knowledge-space CRUD API; current-user resolution will later reuse OAuth2 sessions. */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;
    private final ConversationRepository users;

    public KnowledgeBaseController(KnowledgeBaseService service, ConversationRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public List<KnowledgeBaseSummary> list() {
        return service.list(users.ensureDevelopmentUser());
    }

    @GetMapping("/{knowledgeBaseId}")
    public KnowledgeBaseSummary find(@PathVariable UUID knowledgeBaseId) {
        return service.find(knowledgeBaseId, users.ensureDevelopmentUser());
    }

    @PostMapping
    public ResponseEntity<KnowledgeBaseSummary> create(@Valid @RequestBody KnowledgeBaseRequest request) {
        KnowledgeBaseSummary created = service.create(users.ensureDevelopmentUser(), request);
        return ResponseEntity.created(URI.create("/api/knowledge-bases/" + created.id())).body(created);
    }

    @PatchMapping("/{knowledgeBaseId}")
    public ResponseEntity<Void> update(
            @PathVariable UUID knowledgeBaseId,
            @Valid @RequestBody KnowledgeBaseRequest request
    ) {
        service.update(knowledgeBaseId, users.ensureDevelopmentUser(), request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{knowledgeBaseId}")
    public ResponseEntity<Void> delete(@PathVariable UUID knowledgeBaseId) {
        service.delete(knowledgeBaseId, users.ensureDevelopmentUser());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{knowledgeBaseId}/documents")
    public List<KnowledgeDocumentSummary> documents(@PathVariable UUID knowledgeBaseId) {
        return service.listDocuments(knowledgeBaseId, users.ensureDevelopmentUser());
    }

    @PostMapping("/{knowledgeBaseId}/documents")
    public ResponseEntity<KnowledgeDocumentSummary> attachDocument(
            @PathVariable UUID knowledgeBaseId,
            @Valid @RequestBody KnowledgeDocumentRequest request
    ) {
        KnowledgeDocumentSummary document = service.attachDocument(
                knowledgeBaseId, request.fileId(), users.ensureDevelopmentUser()
        );
        return ResponseEntity.created(URI.create(
                "/api/knowledge-bases/" + knowledgeBaseId + "/documents/" + document.id()
        )).body(document);
    }

    @DeleteMapping("/{knowledgeBaseId}/documents/{documentId}")
    public ResponseEntity<Void> removeDocument(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID documentId
    ) {
        service.removeDocument(knowledgeBaseId, documentId, users.ensureDevelopmentUser());
        return ResponseEntity.noContent().build();
    }
}
