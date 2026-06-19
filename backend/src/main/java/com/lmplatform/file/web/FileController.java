package com.lmplatform.file.web;

import com.lmplatform.chat.repository.ConversationRepository;
import com.lmplatform.file.model.FileAsset;
import com.lmplatform.file.service.FileService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** User-owned upload and processing-status APIs; binary objects remain private in MinIO. */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;
    private final ConversationRepository conversationRepository;

    public FileController(FileService fileService, ConversationRepository conversationRepository) {
        this.fileService = fileService;
        this.conversationRepository = conversationRepository;
    }

    /** Stores one bounded object and returns immediately after asynchronous parsing is queued. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            long userId = conversationRepository.ensureDevelopmentUser();
            return ResponseEntity.accepted().body(fileService.upload(userId, file));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(new UploadError(exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(new UploadError("File storage failed"));
        }
    }

    /** Lists metadata and processing state for the current user's recent files. */
    @GetMapping
    public List<FileAsset> files() {
        return fileService.list(conversationRepository.ensureDevelopmentUser());
    }

    private record UploadError(String message) {
    }
}
