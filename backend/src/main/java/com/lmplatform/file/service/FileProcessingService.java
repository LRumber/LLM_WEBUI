package com.lmplatform.file.service;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.file.repository.FileRepository;
import com.lmplatform.knowledge.service.KnowledgeIndexingService;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Executes document parsing off the request thread while persisting durable task transitions. */
@Service
public class FileProcessingService {

    private final FileRepository repository;
    private final AiServiceClient aiServiceClient;
    private final KnowledgeIndexingService knowledgeIndexingService;

    public FileProcessingService(
            FileRepository repository,
            AiServiceClient aiServiceClient,
            KnowledgeIndexingService knowledgeIndexingService
    ) {
        this.repository = repository;
        this.aiServiceClient = aiServiceClient;
        this.knowledgeIndexingService = knowledgeIndexingService;
    }

    @Async("fileTaskExecutor")
    public void process(UUID fileId, byte[] content, String filename, String mediaType) {
        repository.markProcessing(fileId);
        try {
            repository.complete(fileId, aiServiceClient.parseDocument(content, filename, mediaType));
            knowledgeIndexingService.indexFile(fileId);
        } catch (Exception | LinkageError exception) {
            // Linkage errors must also finish the durable task instead of leaving it stuck in processing.
            String message = exception.getMessage() == null ? "Document parsing failed" : exception.getMessage();
            repository.fail(fileId, message.substring(0, Math.min(message.length(), 1000)));
        }
    }
}
