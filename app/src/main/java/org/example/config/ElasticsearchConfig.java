package org.example.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.StringReader;

@Slf4j
@Configuration
public class ElasticsearchConfig {

    private static final String INDEX_NAME = "document_chunks";

    /**
     * Full index mapping.  The dense_vector field is NOT annotated on the entity
     * (Spring Data ES does not support dense_vector annotations in 3.x), so we
     * create/verify the index manually at startup with this JSON.
     */
    private static final String INDEX_MAPPING = """
            {
              "mappings": {
                "properties": {
                  "content":        { "type": "text"    },
                  "sourceFileName": { "type": "keyword" },
                  "chunkIndex":     { "type": "integer" },
                  "wordCount":      { "type": "integer" },
                  "embedding": {
                    "type":       "dense_vector",
                    "dims":       1536,
                    "index":      true,
                    "similarity": "cosine"
                  }
                }
              }
            }
            """;

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String elasticsearchUri;

    // -----------------------------------------------------------------------
    // Bean definition
    // -----------------------------------------------------------------------

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // Parse URI into host / port / scheme
        boolean secure  = elasticsearchUri.startsWith("https");
        String  scheme  = secure ? "https" : "http";
        String  noProto = elasticsearchUri.replaceFirst("https?://", "");
        String[] parts  = noProto.split(":");
        String   host   = parts[0];
        int      port   = parts.length > 1 ? Integer.parseInt(parts[1]) : 9200;

        RestClient restClient = RestClient
                .builder(new HttpHost(host, port, scheme))
                .build();

        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());

        ElasticsearchClient client = new ElasticsearchClient(transport);
        log.info("ElasticsearchClient initialised → {}", elasticsearchUri);

        // Ensure the index exists with the correct dense_vector mapping
        ensureIndex(client);

        return client;
    }

    // -----------------------------------------------------------------------
    // Index initialisation
    // -----------------------------------------------------------------------

    private void ensureIndex(ElasticsearchClient client) {
        try {
            boolean exists = client.indices()
                    .exists(ExistsRequest.of(r -> r.index(INDEX_NAME)))
                    .value();

            if (exists) {
                log.info("Elasticsearch index '{}' already exists — skipping creation.", INDEX_NAME);
                return;
            }

            log.info("Creating Elasticsearch index '{}' with dense_vector mapping …", INDEX_NAME);
            client.indices().create(
                    CreateIndexRequest.of(r -> r
                            .index(INDEX_NAME)
                            .withJson(new StringReader(INDEX_MAPPING))));
            log.info("Elasticsearch index '{}' created successfully.", INDEX_NAME);

        } catch (IOException e) {
            log.error("Failed to initialise Elasticsearch index '{}': {}", INDEX_NAME, e.getMessage(), e);
            throw new RuntimeException("Could not initialise Elasticsearch index: " + INDEX_NAME, e);
        }
    }
}
