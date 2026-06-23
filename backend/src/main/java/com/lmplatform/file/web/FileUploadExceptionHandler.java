package com.lmplatform.file.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Converts multipart parser failures into the same JSON error shape as normal file uploads. */
@RestControllerAdvice(assignableTypes = FileController.class)
public class FileUploadExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<UploadError> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new UploadError("文件超过平台允许的上传大小"));
    }

    private record UploadError(String message) {
    }
}
