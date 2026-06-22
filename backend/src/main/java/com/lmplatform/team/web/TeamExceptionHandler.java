package com.lmplatform.team.web;

import com.lmplatform.team.service.TeamService.TeamAccessException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Produces stable errors without exposing whether inaccessible teams or users exist. */
@RestControllerAdvice(assignableTypes = TeamController.class)
public class TeamExceptionHandler {

    @ExceptionHandler(TeamAccessException.class)
    public ResponseEntity<Map<String, String>> forbidden(TeamAccessException exception) {
        return ResponseEntity.status(403).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }
}
