package org.example.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "document_chunks")
public class DocumentChunkDocument {

    @Id
    private String id;

    @Field(type = FieldType.Text)
    private String content;

    @Field(type = FieldType.Keyword)
    private String sourceFileName;

    @Field(type = FieldType.Integer)
    private int chunkIndex;

    @Field(type = FieldType.Integer)
    private int wordCount;

    // dense_vector field — mapped via index JSON in ElasticsearchConfig, not via annotation
    private float[] embedding;
}
