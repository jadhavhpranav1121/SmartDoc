package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class EmbeddingService {

    @Value("${openai.api.key:YOUR_OPENAI_API_KEY_HERE}")
    private String apiKey;

    @Value("${openai.embedding.url:https://api.openai.com/v1/embeddings}")
    private String embeddingUrl;

    @Value("${openai.embedding.model:text-embedding-ada-002}")
    private String embeddingModel;

    private final HttpClient    httpClient;
    private final ObjectMapper  objectMapper;

    public EmbeddingService() {
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns a 1 536-dimension embedding for the given text as a {@code List<Float>}.
     *
     * @throws RuntimeException on HTTP or JSON errors
     */
    public List<Float> getEmbedding(String text) {
        log.debug("Requesting embedding for text of length {} chars", text.length());

        try {
            // Build request body
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", embeddingModel);
            body.put("input", text);
            String requestBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("OpenAI Embeddings API returned HTTP {}: {}", response.statusCode(), response.body());
                throw new RuntimeException(
                        "OpenAI Embeddings API error — HTTP " + response.statusCode()
                                + ": " + response.body());
            }

            // Parse response: data[0].embedding
            JsonNode root      = objectMapper.readTree(response.body());
            JsonNode dataArray = root.path("data");

            if (!dataArray.isArray() || dataArray.isEmpty()) {
                log.error("Unexpected OpenAI response structure: {}", response.body());
                throw new RuntimeException("OpenAI Embeddings API returned no data array.");
            }

            JsonNode embeddingArray = dataArray.get(0).path("embedding");
            if (!embeddingArray.isArray()) {
                throw new RuntimeException("OpenAI Embeddings API: 'embedding' field is not an array.");
            }

            List<Float> embedding = new ArrayList<>(embeddingArray.size());
            for (JsonNode dim : embeddingArray) {
                embedding.add((float) dim.asDouble());
            }

            log.debug("Received embedding with {} dimensions.", embedding.size());
            return embedding;

        } catch (RuntimeException e) {
            throw e; // re-throw without wrapping
        } catch (Exception e) {
            log.error("Error calling OpenAI Embeddings API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to obtain embedding from OpenAI: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the embedding as a primitive {@code float[]} (required by Elasticsearch).
     */
    public float[] getEmbeddingAsArray(String text) {
        List<Float> list  = getEmbedding(text);
        float[]     array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }
}
