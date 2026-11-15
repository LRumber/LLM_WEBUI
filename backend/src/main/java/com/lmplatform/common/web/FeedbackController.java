package com.lmplatform.common.web;

import com.lmplatform.auth.web.UserSession;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class FeedbackController {
    private final JdbcTemplate jdbc;
    public FeedbackController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostMapping("/api/developer/feedback")
    public ResponseEntity<?> submit(HttpSession session, @Valid @RequestBody Feedback body) {
        if (!(session.getAttribute(UserSession.USER_ID_KEY) instanceof Number userId)) {
            return ResponseEntity.status(401).build();
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_feedback(id,user_id,content) VALUES (?,?,?)", id, userId.longValue(), body.content().strip());
        return ResponseEntity.ok(Map.of("id", id));
    }

    @GetMapping("/api/admin/feedback")
    public Map<String, Object> list(@RequestParam(defaultValue="0") int page) {
        int index = Math.max(0, Math.min(page, 1000000));
        var items = jdbc.queryForList("""
                SELECT f.id, u.display_name AS "displayName", u.user_number AS "userNumber",
                    f.content, f.created_at AS "createdAt"
                FROM user_feedback f JOIN app_user u ON u.id=f.user_id
                ORDER BY f.created_at DESC, f.id DESC LIMIT 20 OFFSET ?
                """, (long) index * 20);
        return Map.of("items", items, "total", jdbc.queryForObject("SELECT COUNT(*) FROM user_feedback", Long.class));
    }
    public record Feedback(@NotBlank @Size(max=4000) String content) {}
}
