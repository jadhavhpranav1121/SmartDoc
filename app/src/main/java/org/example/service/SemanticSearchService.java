package org.example.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.example.model.DocumentChunkDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticSearchService {

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingService    embeddingService;

    /**
     * Embeds {@code question}, runs a KNN search against the {@code document_chunks} index
     * using the low-level {@link ElasticsearchClient}, and returns the top-K results.
     *
     * <p>We call {@code client.search()} directly with {@code .knn(...)} rather than going
     * through Spring Data's {@code ElasticsearchOperations} / {@code NativeQuery}, which have
     * unstable KNN builder APIs across 5.x patch versions.</p>
     *
     * @param question plain-text question from the user
     * @param topK     maximum number of chunks to return
     * @return list of matching {@link DocumentChunkDocument}, most relevant first
     */
    public List<DocumentChunkDocument> findSimilarChunks(String question, int topK) {
        log.info("Semantic search: question='{}', topK={}", question, topK);

        // 1. Embed the question
        List<Float> queryVector = embeddingService.getEmbedding(question);
        log.debug("Question embedded to {} dimensions.", queryVector.size());

        try {
            // 2. Build and execute the KNN search request via the typed Java client.
            //    .knn() maps directly to the top-level "knn" field in the ES request body.
            //    numCandidates controls HNSW graph exploration; topK * 5 is a safe default.
            SearchResponse<DocumentChunkDocument> response = elasticsearchClient.search(
                    s -> s
                            .index("document_chunks")
                            .knn(k -> k
                                    .field("embedding")
                                    .queryVector(queryVector)
                                    .numCandidates((long) topK * 5)
                                    .k((long) topK)
                            )
                            .size(topK),
                    DocumentChunkDocument.class
            );

            log.info("KNN search returned {} hits (requested topK={}).",
                    response.hits().total() != null ? response.hits().total().value() : "?",
                    topK);

            // 3. Extract source documents from hit wrappers
            List<DocumentChunkDocument> results = response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (results.isEmpty()) {
                log.warn("No similar chunks found for question: '{}'", question);
            } else {
                log.debug("Top result: file='{}', chunkIndex={}, wordCount={}",
                        results.get(0).getSourceFileName(),
                        results.get(0).getChunkIndex(),
                        results.get(0).getWordCount());
            }

            return results;

        } catch (IOException e) {
            log.error("Elasticsearch KNN search failed: {}", e.getMessage(), e);
            throw new RuntimeException("KNN search failed: " + e.getMessage(), e);
        }
    }
}