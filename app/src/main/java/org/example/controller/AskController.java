package org.example.controller;

import org.example.model.AskRequest;
import org.example.model.AskResponse;
import org.example.model.DocumentChunkDocument;
import org.example.service.ChatService;
import org.example.service.SemanticSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@CrossOrigin("*")
@RequiredArgsConstructor
public class AskController {

    private final SemanticSearchService semanticSearchService;
    private final ChatService           chatService;

    /**
     * POST /api/ask
     * Body: { "question": "...", "topK": 3 }
     * Returns an {@link AskResponse} with the generated answer and metadata.
     */
    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody AskRequest askRequest) {
        log.info("Received /ask request: question='{}', topK={}",
                askRequest.getQuestion(), askRequest.getTopK());

        // ---- Validation ----
        if (askRequest.getQuestion() == null || askRequest.getQuestion().isBlank()) {
            log.warn("/ask rejected — question is blank.");
            return ResponseEntity
                    .badRequest()
                    .body(Map.of("error", "Question must not be blank."));
        }

        int topK = askRequest.getTopK() > 0 ? askRequest.getTopK() : 3;

        // ---- RAG pipeline ----
        try {
            long startTime = System.currentTimeMillis();

            // 1. Semantic search — retrieve relevant chunks
            List<DocumentChunkDocument> chunks =
                    semanticSearchService.findSimilarChunks(askRequest.getQuestion(), topK);

            if (chunks.isEmpty()) {
                log.warn("No relevant chunks found for question: '{}'", askRequest.getQuestion());
                return ResponseEntity.ok(AskResponse.builder()
                        .answer("I could not find an answer in the uploaded document.")
                        .sourcesUsed(0)
                        .sourceFile("")
                        .timeTakenMs(System.currentTimeMillis() - startTime)
                        .build());
            }

            // 2. Generate answer via LLM
            String answer = chatService.askQuestion(askRequest.getQuestion(), chunks);

            long elapsed = System.currentTimeMillis() - startTime;

            String sourceFile = chunks.get(0).getSourceFileName();

            AskResponse askResponse = AskResponse.builder()
                    .answer(answer)
                    .sourcesUsed(chunks.size())
                    .sourceFile(sourceFile)
                    .timeTakenMs(elapsed)
                    .build();

            log.info("Answered question in {}ms using {} chunks from '{}'.",
                    elapsed, chunks.size(), sourceFile);

            return ResponseEntity.ok(askResponse);

        } catch (Exception e) {
            log.error("Error processing /ask for question '{}': {}",
                    askRequest.getQuestion(), e.getMessage(), e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process question: " + e.getMessage()));
        }
    }
}
