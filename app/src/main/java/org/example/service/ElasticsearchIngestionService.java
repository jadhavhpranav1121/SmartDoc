package org.example.service;

import org.example.model.DocumentChunk;
import org.example.model.DocumentChunkDocument;
import org.example.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchIngestionService {

    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingService        embeddingService;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Embeds each {@link DocumentChunk}, builds an ES document, and persists it.
     *
     * <p>Individual chunk failures are logged and skipped so that a single bad chunk
     * does not abort the entire batch.</p>
     *
     * @return number of chunks successfully saved
     */
    public int saveChunks(List<DocumentChunk> chunks) {
        log.info("Starting ES ingestion for {} chunks.", chunks.size());

        int savedCount = 0;

        for (DocumentChunk chunk : chunks) {
            try {
                log.debug("Embedding chunk index={} from file='{}'",
                        chunk.getChunkIndex(), chunk.getSourceFileName());

                float[] embedding = embeddingService.getEmbeddingAsArray(chunk.getContent());

                DocumentChunkDocument doc = DocumentChunkDocument.builder()
                        .id(chunk.getId())
                        .content(chunk.getContent())
                        .sourceFileName(chunk.getSourceFileName())
                        .chunkIndex(chunk.getChunkIndex())
                        .wordCount(chunk.getWordCount())
                        .embedding(embedding)
                        .build();

                documentChunkRepository.save(doc);
                savedCount++;

                log.debug("Saved chunk id='{}' ({}/{})", chunk.getId(), savedCount, chunks.size());

            } catch (Exception e) {
                log.error("Failed to embed/save chunk id='{}' (index={}): {}",
                        chunk.getId(), chunk.getChunkIndex(), e.getMessage(), e);
                // Continue with next chunk — do not abort the batch
            }
        }

        log.info("ES ingestion complete. Saved {}/{} chunks.", savedCount, chunks.size());
        return savedCount;
    }
}
