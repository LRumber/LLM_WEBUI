package com.lmplatform.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lmplatform.knowledge.model.KnowledgeBaseRequest;
import com.lmplatform.knowledge.model.KnowledgeBaseSummary;
import com.lmplatform.knowledge.repository.KnowledgeBaseRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for personal knowledge-space normalization and owner-only mutations. */
class KnowledgeBaseServiceTest {

    @Test
    void createsPersonalKnowledgeBaseWithNormalizedFields() {
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        UUID id = UUID.randomUUID();
        KnowledgeBaseSummary summary = summary(id, "产品资料");
        when(repository.createPersonal(9L, "产品资料", "内部文档", null)).thenReturn(id);
        when(repository.findAccessible(id, 9L)).thenReturn(Optional.of(summary));

        KnowledgeBaseSummary result = service(repository).create(
                9L, new KnowledgeBaseRequest("  产品资料  ", " 内部文档 ", " ")
        );

        assertThat(result).isEqualTo(summary);
        verify(repository).createPersonal(9L, "产品资料", "内部文档", null);
    }

    @Test
    void hidesUnauthorizedUpdateAsNotFound() {
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        UUID id = UUID.randomUUID();
        when(repository.updateOwned(id, 9L, "资料", null, null)).thenReturn(false);

        assertThatThrownBy(() -> service(repository).update(
                id, 9L, new KnowledgeBaseRequest("资料", null, null)
        )).isInstanceOf(KnowledgeBaseService.KnowledgeBaseNotFoundException.class);
    }

    @Test
    void listsOnlyRepositoryAuthorizedSpaces() {
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        KnowledgeBaseSummary summary = summary(UUID.randomUUID(), "个人资料");
        when(repository.listAccessible(9L)).thenReturn(java.util.List.of(summary));

        assertThat(service(repository).list(9L)).containsExactly(summary);
    }

    @Test
    void attachesOnlyRepositoryAuthorizedDocument() {
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        UUID baseId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        var document = new com.lmplatform.knowledge.model.KnowledgeDocumentSummary(
                UUID.randomUUID(), fileId, "guide.pdf", "application/pdf", 12L,
                "ready", "waiting_parse", 0, null, OffsetDateTime.now()
        );
        when(repository.attachDocument(baseId, fileId, 9L)).thenReturn(Optional.of(document));

        KnowledgeIndexingService indexing = mock(KnowledgeIndexingService.class);
        assertThat(new KnowledgeBaseService(repository, indexing).attachDocument(baseId, fileId, 9L))
                .isEqualTo(document);
        verify(indexing).indexDocument(document.id());
    }

    @Test
    void verifiesMembershipBeforeListingDocuments() {
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        UUID baseId = UUID.randomUUID();
        KnowledgeBaseSummary base = summary(baseId, "资料");
        when(repository.findAccessible(baseId, 9L)).thenReturn(Optional.of(base));
        when(repository.listDocuments(baseId, 9L)).thenReturn(List.of());

        assertThat(service(repository).listDocuments(baseId, 9L)).isEmpty();
        verify(repository).findAccessible(baseId, 9L);
    }

    @Test
    void createsTeamKnowledgeBaseThroughInheritedMembership() {
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        UUID teamId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        KnowledgeBaseSummary summary = new KnowledgeBaseSummary(
                baseId, "团队资料", null, "team", "team", "owner", 0,
                OffsetDateTime.now(), OffsetDateTime.now()
        );
        when(repository.createTeam(9L, teamId, "团队资料", null, "team"))
                .thenReturn(Optional.of(baseId));
        when(repository.findAccessible(baseId, 9L)).thenReturn(Optional.of(summary));

        assertThat(service(repository).createTeam(
                9L, teamId, new KnowledgeBaseRequest("团队资料", null, "team")
        )).isEqualTo(summary);
    }

    private KnowledgeBaseSummary summary(UUID id, String name) {
        OffsetDateTime now = OffsetDateTime.now();
        return new KnowledgeBaseSummary(
                id, name, null, null, "personal", "owner", 0, now, now
        );
    }

    private KnowledgeBaseService service(KnowledgeBaseRepository repository) {
        return new KnowledgeBaseService(repository, mock(KnowledgeIndexingService.class));
    }
}
