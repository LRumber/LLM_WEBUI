package com.lmplatform.knowledge.service;

import com.lmplatform.chat.client.AiServiceClient;
import com.lmplatform.knowledge.config.KnowledgeProperties;
import com.lmplatform.knowledge.model.KnowledgeSearchCandidate;
import com.lmplatform.knowledge.model.KnowledgeSearchResult;
import com.lmplatform.knowledge.repository.KnowledgeBaseRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Combines lexical and vector evidence while preserving authorization and source traceability. */
@Service
public class KnowledgeSearchService {

    private final KnowledgeBaseRepository repository;
    private final AiServiceClient aiServiceClient;
    private final KnowledgeProperties properties;

    public KnowledgeSearchService(
            KnowledgeBaseRepository repository,
            AiServiceClient aiServiceClient,
            KnowledgeProperties properties
    ) {
        this.repository = repository;
        this.aiServiceClient = aiServiceClient;
        this.properties = properties;
    }

    public List<KnowledgeSearchResult> search(
            UUID knowledgeBaseId, long userId, String rawQuery, int topK
    ) {
        String query = rawQuery.strip();
        long started = System.nanoTime();
        List<KnowledgeSearchCandidate> keyword = repository.searchKeywordCandidates(
                knowledgeBaseId, userId, query, Math.max(20, topK * 3)
        );
        Map<UUID, ScoredCandidate> merged = new LinkedHashMap<>();
        keyword.forEach(candidate -> merged.put(
                candidate.chunkId(), new ScoredCandidate(candidate, candidate.keywordScore(), 0)
        ));

        boolean hybrid = properties.embeddingModel() != null && !properties.embeddingModel().isBlank();
        if (hybrid) {
            float[] queryVector = aiServiceClient.embed(properties.embeddingModel(), List.of(query)).getFirst();
            for (KnowledgeSearchCandidate candidate : repository.listVectorCandidates(
                    knowledgeBaseId, userId, 2000
            )) {
                double vectorScore = cosine(queryVector, candidate.embedding());
                merged.compute(candidate.chunkId(), (id, current) -> current == null
                        ? new ScoredCandidate(candidate, 0, vectorScore)
                        : new ScoredCandidate(current.candidate(), current.keywordScore(), vectorScore));
            }
        }

        List<KnowledgeSearchResult> results = merged.values().stream()
                .map(value -> toResult(value, hybrid))
                .sorted(Comparator.comparingDouble(KnowledgeSearchResult::score).reversed())
                .limit(topK)
                .toList();
        int durationMs = Math.max(0, (int) ((System.nanoTime() - started) / 1_000_000));
        repository.recordRetrieval(
                knowledgeBaseId, userId, query, hybrid ? "hybrid" : "keyword",
                merged.size(), durationMs, results
        );
        return results;
    }

    private KnowledgeSearchResult toResult(ScoredCandidate value, boolean hybrid) {
        KnowledgeSearchCandidate candidate = value.candidate();
        double keywordScore = clamp(value.keywordScore());
        double vectorScore = hybrid ? clamp((value.vectorScore() + 1) / 2) : 0;
        double score = hybrid ? keywordScore * 0.45 + vectorScore * 0.55 : keywordScore;
        return new KnowledgeSearchResult(
                candidate.chunkId(), candidate.documentId(), candidate.fileId(),
                candidate.filename(), candidate.content(), candidate.pageNumber(),
                candidate.titlePath(), round(keywordScore), round(vectorScore), round(score)
        );
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) return -1;
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return leftNorm == 0 || rightNorm == 0 ? -1 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private record ScoredCandidate(
            KnowledgeSearchCandidate candidate, double keywordScore, double vectorScore
    ) {
    }
}
