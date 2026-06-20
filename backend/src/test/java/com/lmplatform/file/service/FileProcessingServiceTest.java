package com.lmplatform.file.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.file.repository.FileRepository;
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

        new FileProcessingService(repository, client)
                .process(fileId, new byte[]{1}, "guide.md", "text/markdown");

        verify(repository).markProcessing(fileId);
        verify(repository).fail(fileId, "missing-runtime-class");
    }
}
