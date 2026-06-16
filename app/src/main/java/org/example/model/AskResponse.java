package org.example.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AskResponse {

    private String answer;
    private int    sourcesUsed;
    private String sourceFile;
    private long   timeTakenMs;

    /**
     * Session ID echoed back so the client can pass it in subsequent requests.
     * Null when the request had no session ID and the caller did not start one.
     */
    private String sessionId;
}
