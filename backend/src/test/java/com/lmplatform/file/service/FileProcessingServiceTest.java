package com.lmplatform.file.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.file.repository.FileRepository;
import com.lmplatform.knowledge.service.KnowledgeIndexingService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for durable parser task completion on runtime dependency failures. */
class FileProcessingServiceTest {

    /** A linkage failure must mark the task failed instead of leaving the UI in processing forever. */
    @Test
    void marksTaskFailedOnLinkageError() {
        FileRepository repository = mock(FileRepository.class);
        AiServiceClient client = mock(AiServiceClient.class);
        UUID fileId = UUID.randomUUID();
        when(client.parseDocument(any(byte[].class), eq("guide.md"), eq("text/markdown")))
                .thenThrow(new NoClassDefFoundError("missing-runtime-class"));

        new FileProcessingService(repository, client, mock(KnowledgeIndexingService.class),
                new LegacyOfficeParser(new ObjectMapper()))
                .process(fileId, new byte[]{1}, "guide.md", "text/markdown");

        verify(repository).markProcessing(fileId);
        verify(repository).fail(fileId, "missing-runtime-class");
    }

    @Test
    void legacyOfficeDoesNotCallAiParser() throws Exception {
        FileRepository repository = mock(FileRepository.class);
        AiServiceClient client = mock(AiServiceClient.class);
        LegacyOfficeParser legacyOfficeParser = mock(LegacyOfficeParser.class);
        UUID fileId = UUID.randomUUID();
        when(legacyOfficeParser.supports("legacy.doc")).thenReturn(true);
        when(legacyOfficeParser.parse(any(byte[].class), eq("legacy.doc"), eq("application/msword")))
                .thenReturn(new ObjectMapper().createObjectNode().put("text", "legacy text"));

        new FileProcessingService(repository, client, mock(KnowledgeIndexingService.class), legacyOfficeParser)
                .process(fileId, new byte[]{1}, "legacy.doc", "application/msword");

        verify(client, never()).parseDocument(any(), any(), any());
        verify(repository).complete(eq(fileId), any());
    }
}
