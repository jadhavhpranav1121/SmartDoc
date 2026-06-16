package org.example.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public List<Float> getEmbedding(String text) {
        log.debug("Requesting embedding for text of length {} chars", text.length());
        float[] array = getEmbeddingAsArray(text);
        List<Float> result = new ArrayList<>(array.length);
        for (float f : array) {
            result.add(f);
        }
        log.debug("Received embedding with {} dimensions.", result.size());
        return result;
    }

    public float[] getEmbeddingAsArray(String text) {
        return embeddingModel.embed(text);
    }
}
