package org.example.controller;

import org.example.model.AskRequest;
import org.example.model.AskResponse;
import org.example.model.ConversationMessage;
import org.example.model.DocumentChunkDocument;
import org.example.service.ChatService;
import org.example.service.ConversationStore;
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
    private final ConversationStore     conversationStore;

    // -----------------------------------------------------------------------
    // POST /api/ask
    // -----------------------------------------------------------------------

    /**
     * RAG + conversation-aware Q&A endpoint.
     *
     * Request body:
     * <pre>
     * {
     *   "question": "What is the refund policy?",
     *   "topK":     3,          // optional, default 3
     *   "sessionId": "uuid"     // optional; omit for stateless, include for multi-turn
     * }
     * </pre>
     *
     * Conversation behaviour:
     * <ul>
     *   <li>No sessionId → stateless single-turn (original behaviour).</li>
     *   <li>sessionId = "new" (or any unknown ID) → server auto-creates a session
     *       and returns the new sessionId in the response.</li>
     *   <li>Known sessionId → history is fetched, sent to the LLM, and updated
     *       with this turn.</li>
     * </ul>
     */
    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody AskRequest askRequest) {
        log.info("Received /ask: question='{}', topK={}, sessionId={}",
                askRequest.getQuestion(), askRequest.getTopK(), askRequest.getSessionId());

        // ---- Validation ----
        if (askRequest.getQuestion() == null || askRequest.getQuestion().isBlank()) {
            log.warn("/ask rejected — question is blank.");
            return ResponseEntity.badRequest().body(Map.of("error", "Question must not be blank."));
        }

        int topK = askRequest.getTopK() > 0 ? askRequest.getTopK() : 3;

        // ---- Session resolution ----
        String sessionId = resolveSession(askRequest.getSessionId());

        // ---- RAG pipeline ----
        try {
            long startTime = System.currentTimeMillis();

            // 1. Semantic search
            List<DocumentChunkDocument> chunks =
                    semanticSearchService.findSimilarChunks(askRequest.getQuestion(), topK);

            if (chunks.isEmpty()) {
                log.warn("No relevant chunks found for question: '{}'", askRequest.getQuestion());
                String noDocAnswer = "I could not find an answer in the uploaded document.";

                if (sessionId != null) {
                    conversationStore.addTurn(sessionId, askRequest.getQuestion(), noDocAnswer);
                }

                return ResponseEntity.ok(AskResponse.builder()
                        .answer(noDocAnswer)
                        .sourcesUsed(0)
                        .sourceFile("")
                        .timeTakenMs(System.currentTimeMillis() - startTime)
                        .sessionId(sessionId)
                        .build());
            }

            // 2. Fetch conversation history (empty list if stateless)
            List<ConversationMessage> history = sessionId != null
                    ? conversationStore.getHistory(sessionId)
                    : List.of();

            // 3. Generate answer via LLM (with history for multi-turn)
            String answer = chatService.askQuestion(askRequest.getQuestion(), chunks, history);

            // 4. Persist this turn
            if (sessionId != null) {
                conversationStore.addTurn(sessionId, askRequest.getQuestion(), answer);
            }

            long elapsed    = System.currentTimeMillis() - startTime;
            String srcFile  = chunks.get(0).getSourceFileName();

            log.info("Answered in {}ms, {} chunks from '{}', sessionId={}",
                    elapsed, chunks.size(), srcFile, sessionId);

            return ResponseEntity.ok(AskResponse.builder()
                    .answer(answer)
                    .sourcesUsed(chunks.size())
                    .sourceFile(srcFile)
                    .timeTakenMs(elapsed)
                    .sessionId(sessionId)
                    .build());

        } catch (Exception e) {
            log.error("Error processing /ask for question '{}': {}", askRequest.getQuestion(), e.getMessage(), e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process question: " + e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // DELETE /api/conversation/{sessionId}   — clear session history
    // -----------------------------------------------------------------------

    /**
     * Clears the conversation history for a session without invalidating the session.
     * Useful for a "start fresh" button in a UI while keeping the same session ID.
     */
    @DeleteMapping("/conversation/{sessionId}")
    public ResponseEntity<Map<String, String>> clearConversation(
            @PathVariable String sessionId) {

        if (!conversationStore.sessionExists(sessionId)) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found: " + sessionId));
        }

        conversationStore.clearSession(sessionId);
        log.info("Cleared conversation history for session: {}", sessionId);
        return ResponseEntity.ok(Map.of(
                "status",    "CLEARED",
                "sessionId", sessionId
        ));
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Resolves the session ID from the request:
     * <ul>
     *   <li>null / blank → stateless, return null</li>
     *   <li>known ID    → return as-is (history already stored)</li>
     *   <li>unknown ID  → auto-create and return the new ID</li>
     * </ul>
     */
    private String resolveSession(String requestedSessionId) {
        if (requestedSessionId == null || requestedSessionId.isBlank()) {
            return null;  // stateless mode
        }
        if (conversationStore.sessionExists(requestedSessionId)) {
            return requestedSessionId;
        }
        // Unknown ID (includes "new") — create a fresh session
        String newId = conversationStore.createSession();
        log.info("Auto-created session '{}' for requested sessionId='{}'",
                newId, requestedSessionId);
        return newId;
    }
}
