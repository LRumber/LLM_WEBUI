package com.lmplatform.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.file.config.ObjectStorageProperties;
import com.lmplatform.file.model.FileAsset;
import com.lmplatform.file.model.StoredFileAsset;
import com.lmplatform.file.repository.FileRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** Unit tests for upload validation, private object storage, and parsing-task scheduling. */
class FileServiceTest {

    @Test
    void platformPolicyRejectsUploadBeforeAnyStorageOrProcessing() throws Exception {
        var storage = mock(ObjectStorageService.class);
        var repository = mock(FileRepository.class);
        var processor = mock(FileProcessingService.class);
        var settings = mock(com.lmplatform.common.service.PlatformSettingsService.class);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("文件类型不允许"))
                .when(settings).validateUpload("file.exe", 4);
        var service = new FileService(new ObjectStorageProperties("", "", "", "files", 1024), storage, repository, processor, settings);
        assertThatThrownBy(() -> service.upload(9L, new MockMultipartFile("file", "file.exe", "application/octet-stream", new byte[4])))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(storage, repository, processor);
    }

    @Test
    void storesAndSchedulesSupportedUpload() throws Exception {
        ObjectStorageService storage = mock(ObjectStorageService.class);
        FileRepository repository = mock(FileRepository.class);
        FileProcessingService processor = mock(FileProcessingService.class);
        ObjectStorageProperties properties = new ObjectStorageProperties(
                "http://localhost:9000", "user", "secret", "files", 1024
        );
        UUID fileId = UUID.randomUUID();
        FileAsset asset = new FileAsset(
                fileId, "guide.md", "text/markdown", 6, "document",
                "uploaded", null, OffsetDateTime.now()
        );
        when(repository.create(
                eq(9L), anyString(), eq("guide.md"), eq("text/markdown"),
                eq(6L), anyString(), eq("document")
        )).thenReturn(fileId);
        when(repository.list(9L)).thenReturn(List.of(asset));
        var upload = new MockMultipartFile(
                "file", "guide.md", "text/markdown", "hello!".getBytes()
        );

        FileAsset result = new FileService(properties, storage, repository, processor, mock(com.lmplatform.common.service.PlatformSettingsService.class))
                .upload(9L, upload);

        assertThat(result.id()).isEqualTo(fileId);
        verify(storage).put(anyString(), eq("hello!".getBytes()), eq("text/markdown"));
        verify(processor).process(fileId, "hello!".getBytes(), "guide.md", "text/markdown");
    }

    @Test
    void rejectsOversizedUploadBeforeObjectStorage() {
        ObjectStorageProperties properties = new ObjectStorageProperties(
                "http://localhost:9000", "user", "secret", "files", 3
        );
        var upload = new MockMultipartFile("file", "large.txt", "text/plain", "large".getBytes());

        assertThatThrownBy(() -> new FileService(
                properties, mock(ObjectStorageService.class), mock(FileRepository.class),
                mock(FileProcessingService.class), mock(com.lmplatform.common.service.PlatformSettingsService.class)
        ).upload(9L, upload)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Confirms retries reuse the private object and do not require a duplicate browser upload. */
    @Test
    void retriesStoredUploadFromObjectStorage() throws Exception {
        ObjectStorageService storage = mock(ObjectStorageService.class);
        FileRepository repository = mock(FileRepository.class);
        FileProcessingService processor = mock(FileProcessingService.class);
        ObjectStorageProperties properties = new ObjectStorageProperties(
                "http://localhost:9000", "user", "secret", "files", 1024
        );
        UUID fileId = UUID.randomUUID();
        FileAsset asset = new FileAsset(
                fileId, "guide.md", "text/markdown", 6, "document",
                "uploaded", null, OffsetDateTime.now()
        );
        when(repository.findStored(fileId, 9L)).thenReturn(Optional.of(
                new StoredFileAsset(fileId, "9/private/guide.md", "guide.md", "text/markdown")
        ));
        when(storage.get("9/private/guide.md", 1024)).thenReturn("hello!".getBytes());
        when(repository.list(9L)).thenReturn(List.of(asset));

        FileAsset result = new FileService(properties, storage, repository, processor, mock(com.lmplatform.common.service.PlatformSettingsService.class)).retry(9L, fileId);

        assertThat(result.id()).isEqualTo(fileId);
        verify(repository).requeue(fileId);
        verify(processor).process(fileId, "hello!".getBytes(), "guide.md", "text/markdown");
    }

    /** Confirms downloads read only the object resolved through the owned-file repository lookup. */
    @Test
    void downloadsOwnedPrivateObject() throws Exception {
        ObjectStorageService storage = mock(ObjectStorageService.class);
        FileRepository repository = mock(FileRepository.class);
        ObjectStorageProperties properties = new ObjectStorageProperties(
                "http://localhost:9000", "user", "secret", "files", 1024
        );
        UUID fileId = UUID.randomUUID();
        when(repository.findStored(fileId, 9L)).thenReturn(Optional.of(
                new StoredFileAsset(fileId, "9/private/guide.md", "guide.md", "text/markdown")
        ));
        when(storage.get("9/private/guide.md", 1024)).thenReturn("hello!".getBytes());

        var result = new FileService(
                properties, storage, repository, mock(FileProcessingService.class), mock(com.lmplatform.common.service.PlatformSettingsService.class)
        ).download(9L, fileId);

        assertThat(result.filename()).isEqualTo("guide.md");
        assertThat(result.content()).isEqualTo("hello!".getBytes());
    }
}
