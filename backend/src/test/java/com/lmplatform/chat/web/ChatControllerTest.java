package com.lmplatform.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.chat.client.AiServiceClient;
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
}
