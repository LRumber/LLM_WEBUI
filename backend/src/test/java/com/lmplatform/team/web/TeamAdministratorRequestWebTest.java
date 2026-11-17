package com.lmplatform.team.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lmplatform.admin.config.AdminAuthenticationInterceptor;
import com.lmplatform.admin.model.AdminPrincipal;
import com.lmplatform.admin.web.AdminSession;
import com.lmplatform.auth.config.UserAuthenticationInterceptor;
import com.lmplatform.auth.web.UserSession;
import com.lmplatform.chat.repository.ConversationRepository;
import com.lmplatform.team.repository.TeamAdministratorRequestRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TeamAdministratorRequestWebTest {
    @Test
    void applicationUsesSessionIdentityAndValidatesReason() throws Exception {
        var requests = mock(TeamAdministratorRequestRepository.class);
        var users = mock(ConversationRepository.class);
        when(users.ensureDevelopmentUser()).thenReturn(9L);
        when(requests.submit(9L, "项目协作")).thenReturn(UUID.randomUUID());
        var mvc = MockMvcBuilders.standaloneSetup(new TeamAdministratorRequestController(requests, users))
                .addInterceptors(new UserAuthenticationInterceptor()).build();
        mvc.perform(post("/api/teams/administrator-request").contentType("application/json")
                .content("{\"reason\":\"项目协作\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/teams/administrator-request").sessionAttr(UserSession.USER_ID_KEY, 9L)
                .contentType("application/json").content("{\"reason\":\"  \"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(requests);
        mvc.perform(post("/api/teams/administrator-request").sessionAttr(UserSession.USER_ID_KEY, 9L)
                .contentType("application/json").content("{\"reason\":\"项目协作\",\"userId\":99}"))
                .andExpect(status().isOk());
        verify(requests).submit(9L, "项目协作");
    }

    @Test
    void onlyAdministratorsCanReviewAndDecisionIsRequired() throws Exception {
        var requests = mock(TeamAdministratorRequestRepository.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AdminTeamAdministratorRequestController(requests))
                .addInterceptors(new AdminAuthenticationInterceptor()).build();
        UUID id = UUID.randomUUID();
        String url = "/api/admin/team-administrator-requests/" + id + "/review";
        mvc.perform(get("/api/admin/team-administrator-requests").sessionAttr(UserSession.USER_ID_KEY, 9L))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).sessionAttr(UserSession.USER_ID_KEY, 9L).contentType("application/json")
                .content("{\"approved\":true}")).andExpect(status().isUnauthorized());
        var admin = new AdminPrincipal(1L, "admin", "管理员", "local");
        mvc.perform(post(url).sessionAttr(AdminSession.PRINCIPAL_KEY, admin).contentType("application/json")
                .content("{}")).andExpect(status().isBadRequest());
        verifyNoInteractions(requests);
        mvc.perform(post(url).sessionAttr(AdminSession.PRINCIPAL_KEY, admin).contentType("application/json")
                .content("{\"approved\":true,\"note\":\"同意\"}")).andExpect(status().isNoContent());
        verify(requests).review(id, true, "同意", "local:1:管理员");
    }
}
