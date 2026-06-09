package org.example.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AskRequest {

    private String question;
    private int    topK = 3;

    /**
     * Optional session ID for multi-turn conversation.
     * If null or blank, each request is treated as a standalone query.
     * If non-blank but unknown, a new session is created and its ID is returned.
     */
    private String sessionId;
}
