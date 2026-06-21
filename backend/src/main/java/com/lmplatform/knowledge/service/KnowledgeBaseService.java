package com.lmplatform.knowledge.service;

import com.lmplatform.knowledge.model.KnowledgeBaseRequest;
import com.lmplatform.knowledge.model.KnowledgeBaseSummary;
import com.lmplatform.knowledge.model.KnowledgeDocumentSummary;
import com.lmplatform.knowledge.repository.KnowledgeBaseRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Applies first-phase personal-space rules independently from HTTP and persistence details. */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;
    private final KnowledgeIndexingService indexingService;

    public KnowledgeBaseService(
            KnowledgeBaseRepository repository,
            KnowledgeIndexingService indexingService
    ) {
        this.repository = repository;
        this.indexingService = indexingService;
    }

    public KnowledgeBaseSummary create(long userId, KnowledgeBaseRequest request) {
        UUID id = repository.createPersonal(
                userId, normalizeName(request.name()), normalizeOptional(request.description()),
                normalizeOptional(request.icon())
        );
        return repository.findAccessible(id, userId).orElseThrow();
    }

    public List<KnowledgeBaseSummary> list(long userId) {
        return repository.listAccessible(userId);
    }

    public KnowledgeBaseSummary find(UUID knowledgeBaseId, long userId) {
        return repository.findAccessible(knowledgeBaseId, userId)
                .orElseThrow(() -> new KnowledgeBaseNotFoundException(knowledgeBaseId));
    }

    public void update(UUID knowledgeBaseId, long userId, KnowledgeBaseRequest request) {
        boolean updated = repository.updateOwned(
                knowledgeBaseId, userId, normalizeName(request.name()),
                normalizeOptional(request.description()), normalizeOptional(request.icon())
        );
        if (!updated) throw new KnowledgeBaseNotFoundException(knowledgeBaseId);
    }

    public void delete(UUID knowledgeBaseId, long userId) {
        if (!repository.softDeleteOwned(knowledgeBaseId, userId)) {
            throw new KnowledgeBaseNotFoundException(knowledgeBaseId);
        }
    }

    public List<KnowledgeDocumentSummary> listDocuments(UUID knowledgeBaseId, long userId) {
        // Resolve the space first so an inaccessible and an empty knowledge base behave differently internally.
        find(knowledgeBaseId, userId);
        return repository.listDocuments(knowledgeBaseId, userId);
    }

    public KnowledgeDocumentSummary attachDocument(UUID knowledgeBaseId, UUID fileId, long userId) {
        KnowledgeDocumentSummary document = repository.attachDocument(knowledgeBaseId, fileId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Document must be an owned file and the knowledge base must be editable"
                ));
        indexingService.indexDocument(document.id());
        return document;
    }

    public void removeDocument(UUID knowledgeBaseId, UUID documentId, long userId) {
        if (!repository.removeDocument(knowledgeBaseId, documentId, userId)) {
            throw new KnowledgeBaseNotFoundException(knowledgeBaseId);
        }
    }

    private String normalizeName(String value) {
        String normalized = value.strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException("Knowledge base name is required");
        return normalized;
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Avoids revealing whether an inaccessible knowledge base exists. */
    public static class KnowledgeBaseNotFoundException extends RuntimeException {
        public KnowledgeBaseNotFoundException(UUID knowledgeBaseId) {
            super("Knowledge base not found: " + knowledgeBaseId);
        }
    }
}
