package com.lmplatform.knowledge.web;

import com.lmplatform.knowledge.service.KnowledgeBaseService.KnowledgeBaseNotFoundException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts domain failures into stable knowledge-base API responses. */
@RestControllerAdvice(assignableTypes = KnowledgeBaseController.class)
public class KnowledgeBaseExceptionHandler {

    @ExceptionHandler(KnowledgeBaseNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(404).body(Map.of("message", "Knowledge base not found"));
    }
}
