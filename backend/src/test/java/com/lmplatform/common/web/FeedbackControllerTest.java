package com.lmplatform.common.web;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lmplatform.auth.web.UserSession;
import com.lmplatform.admin.config.AdminAuthenticationInterceptor;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FeedbackControllerTest {
    @Test
    void feedbackUsesAuthenticatedIdentityAndRejectsEmptyContent() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var mvc = MockMvcBuilders.standaloneSetup(new FeedbackController(jdbc)).build();
        mvc.perform(post("/api/developer/feedback").contentType("application/json")
                .content("{\"content\":\"test\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/developer/feedback").sessionAttr(UserSession.USER_ID_KEY, 7L)
                .contentType("application/json").content("{\"content\":\"  suggestion  \",\"userId\":8}"))
                .andExpect(status().isOk());
        verify(jdbc).update(anyString(), any(UUID.class), eq(7L), eq("suggestion"));
        mvc.perform(post("/api/developer/feedback").sessionAttr(UserSession.USER_ID_KEY, 7L)
                .contentType("application/json").content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }
    @Test
    void ordinaryUsersCannotReadOtherFeedback() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var mvc = MockMvcBuilders.standaloneSetup(new FeedbackController(jdbc))
                .addInterceptors(new AdminAuthenticationInterceptor()).build();
        mvc.perform(get("/api/admin/feedback").sessionAttr(UserSession.USER_ID_KEY, 7L))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(jdbc);
    }
}
