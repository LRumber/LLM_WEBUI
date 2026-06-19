package com.lmplatform.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.chat.model.PromptTemplate;
import com.lmplatform.chat.model.PromptTemplateRequest;
import com.lmplatform.chat.repository.ConversationRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for administrator ownership of system prompt templates. */
class AdminPromptTemplateControllerTest {

    @Test
    void createsPlatformOwnedTemplate() {
        ConversationRepository repository = mock(ConversationRepository.class);
        PromptTemplateRequest request = new PromptTemplateRequest("审查", "代码审查", "检查风险", true);
        PromptTemplate created = new PromptTemplate(
                UUID.randomUUID(), "审查", "代码审查", "检查风险", "system", true
        );
        when(repository.createPromptTemplate(
                null, "system", "审查", "代码审查", "检查风险", true
        )).thenReturn(created);

        assertThat(new AdminPromptTemplateController(repository).create(request)).isEqualTo(created);
    }

    @Test
    void updatesOnlySystemTemplate() {
        ConversationRepository repository = mock(ConversationRepository.class);
        UUID id = UUID.randomUUID();
        PromptTemplateRequest request = new PromptTemplateRequest("审查", "", "检查风险", false);
        when(repository.updateSystemPromptTemplate(id, "审查", "", "检查风险", false))
                .thenReturn(true);

        assertThat(new AdminPromptTemplateController(repository).update(id, request)
                .getStatusCode().value()).isEqualTo(204);
        verify(repository).updateSystemPromptTemplate(id, "审查", "", "检查风险", false);
    }
}
