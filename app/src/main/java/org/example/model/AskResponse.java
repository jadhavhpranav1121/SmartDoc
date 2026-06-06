package org.example.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AskResponse {

    private String answer;
    private int sourcesUsed;
    private String sourceFile;
    private long timeTakenMs;
}
