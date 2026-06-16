package org.example.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {

    /** OpenAI role: "system", "user", or "assistant" */
    private String role;

    private String content;
}
