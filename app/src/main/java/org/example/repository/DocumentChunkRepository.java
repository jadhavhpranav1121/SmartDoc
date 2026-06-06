package org.example.repository;

import org.example.model.DocumentChunkDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentChunkRepository
        extends ElasticsearchRepository<DocumentChunkDocument, String> {
    // KNN search is performed via ElasticsearchOperations (NativeQuery) in SemanticSearchService.
    // Standard CRUD operations are inherited from ElasticsearchRepository.
}
