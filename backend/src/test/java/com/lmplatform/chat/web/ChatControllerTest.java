package com.lmplatform.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.chat.model.BranchConversationRequest;
import com.lmplatform.chat.model.EditMessageRequest;
import com.lmplatform.chat.model.PromptTemplateRequest;
import com.lmplatform.chat.repository.ConversationRepository;
import com.lmplatform.chat.service.ChatService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Unit tests for conversation-management HTTP outcomes. */
class ChatControllerTest {

    @Test
    void deletesOwnedConversation() {
        ConversationRepository repository = mock(ConversationRepository.class);
        ChatController controller = new ChatController(
                mock(ChatService.class), repository, mock(AiServiceClient.class)
        );
        UUID conversationId = UUID.randomUUID();
        when(repository.ensureDevelopmentUser()).thenReturn(7L);
        when(repository.softDeleteConversation(conversationId, 7L)).thenReturn(true);

        assertThat(controller.deleteConversation(conversationId).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        verify(repository).softDeleteConversation(conversationId, 7L);
    }

    @Test
    void returnsNotFoundWhenConversationCannotBeDeleted() {
        ConversationRepository repository = mock(ConversationRepository.class);
        ChatController controller = new ChatController(
                mock(ChatService.class), repository, mock(AiServiceClient.class)
        );
        UUID conversationId = UUID.randomUUID();
        when(repository.ensureDevelopmentUser()).thenReturn(7L);

        assertThat(controller.deleteConversation(conversationId).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void editsOwnedUserMessage() {
        ConversationRepository repository = mock(ConversationRepository.class);
        ChatController controller = new ChatController(
                mock(ChatService.class), repository, mock(AiServiceClient.class)
        );
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(repository.ensureDevelopmentUser()).thenReturn(7L);
        when(repository.editUserMessage(conversationId, messageId, 7L, "新问题")).thenReturn(true);

        assertThat(controller.editMessage(
                conversationId, messageId, new EditMessageRequest("新问题")
        ).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void createsConversationBranch() {
        ConversationRepository repository = mock(ConversationRepository.class);
        ChatController controller = new ChatController(
                mock(ChatService.class), repository, mock(AiServiceClient.class)
        );
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        when(repository.ensureDevelopmentUser()).thenReturn(7L);
        when(repository.branchConversation(conversationId, messageId, 7L)).thenReturn(branchId);

        assertThat(controller.branchConversation(
                conversationId, new BranchConversationRequest(messageId)
        ).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updatesOnlyOwnedPersonalTemplate() {
        ConversationRepository repository = mock(ConversationRepository.class);
        ChatController controller = new ChatController(
                mock(ChatService.class), repository, mock(AiServiceClient.class)
        );
        UUID templateId = UUID.randomUUID();
        when(repository.ensureDevelopmentUser()).thenReturn(7L);
        when(repository.updatePersonalPromptTemplate(
                templateId, 7L, "我的模板", "说明", "系统提示", true
        )).thenReturn(true);

        assertThat(controller.updatePromptTemplate(
                templateId, new PromptTemplateRequest("我的模板", "说明", "系统提示", true)
        ).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
