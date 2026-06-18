package com.lmplatform.common.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight liveness endpoint used by local tooling and deployment probes. */
@RestController
@RequestMapping("/api")
public class HealthController {

    /** Returns service identity, status, and current server time without touching dependencies. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "service", "lm-platform-backend",
                "status", "UP",
                "time", Instant.now().toString()
        );
    }
}
