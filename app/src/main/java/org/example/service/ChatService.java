package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.model.ConversationMessage;
import org.example.model.DocumentChunkDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Slf4j
@Service
public class ChatService {

    @Value("${openai.api.key:YOUR_OPENAI_API_KEY_HERE}")
    private String apiKey;

    @Value("${openai.chat.url:https://api.openai.com/v1/chat/completions}")
    private String chatUrl;

    @Value("${openai.chat.model:gpt-4o-mini}")
    private String chatModel;

    private static final String SYSTEM_PROMPT =
            "You are a helpful assistant. Answer the user's question using ONLY the context "
          + "provided below. If the answer is not in the context, say "
          + "'I could not find an answer in the uploaded document.' "
          + "Do not make up information. "
          + "You have access to the conversation history — use it to answer follow-up questions "
          + "and resolve pronouns or references to earlier turns.";

    private final HttpClient   httpClient;
    private final ObjectMapper objectMapper;

    public ChatService() {
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Single-turn variant (no history). Kept for backward compatibility.
     */
    public String askQuestion(String question, List<DocumentChunkDocument> chunks) {
        return askQuestion(question, chunks, List.of());
    }

    /**
     * Multi-turn variant. {@code history} is the ordered list of prior
     * (user, assistant) message pairs for this session.
     *
     * Message order sent to OpenAI:
     *   system (with injected context)
     *   [history user/assistant pairs...]
     *   user (current question)
     *
     * @param question the current user question
     * @param chunks   relevant document chunks from semantic search
     * @param history  prior conversation turns (may be empty)
     * @return the assistant's answer text
     */
    public String askQuestion(String question,
                              List<DocumentChunkDocument> chunks,
                              List<ConversationMessage> history) {
        log.info("Calling OpenAI Chat API: question='{}', chunks={}, historyTurns={}",
                question, chunks.size(), history.size() / 2);

        try {
            String requestBody = buildRequestBody(question, chunks, history);
            log.debug("Chat request body length: {} chars", requestBody.length());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(chatUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("OpenAI Chat API returned HTTP {}: {}", response.statusCode(), response.body());
                throw new RuntimeException(
                        "OpenAI Chat API error — HTTP " + response.statusCode()
                                + ": " + response.body());
            }

            JsonNode root    = objectMapper.readTree(response.body());
            JsonNode choices = root.path("choices");

            if (!choices.isArray() || choices.isEmpty()) {
                log.error("OpenAI Chat API returned no choices: {}", response.body());
                throw new RuntimeException("OpenAI Chat API returned no choices.");
            }

            String answer = choices.get(0).path("message").path("content").asText();
            log.info("Received answer from OpenAI ({} chars).", answer.length());
            return answer;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error calling OpenAI Chat API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get answer from OpenAI: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String buildRequestBody(String question,
                                    List<DocumentChunkDocument> chunks,
                                    List<ConversationMessage> history) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model",       chatModel);
        body.put("max_tokens",  1000);
        body.put("temperature", 0.2);

        ArrayNode messages = body.putArray("messages");

        // 1. System message with injected context
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role",    "system");
        systemMsg.put("content", buildSystemContent(chunks));

        // 2. Prior conversation turns (oldest first)
        for (ConversationMessage msg : history) {
            ObjectNode histMsg = messages.addObject();
            histMsg.put("role",    msg.getRole());
            histMsg.put("content", msg.getContent());
        }

        // 3. Current user question
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role",    "user");
        userMsg.put("content", question);

        return objectMapper.writeValueAsString(body);
    }

    private String buildSystemContent(List<DocumentChunkDocument> chunks) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        sb.append("\n\nContext:\n");
        for (DocumentChunkDocument chunk : chunks) {
            sb.append(chunk.getContent()).append("\n\n");
        }
        return sb.toString();
    }
}
