package org.example.service;

import java.util.ArrayList;
import java.util.List;

import org.example.model.ConversationMessage;
import org.example.model.DocumentChunkDocument;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatModel chatModel;
    // Having some issue
    private static final String SYSTEM_PROMPT
            = "You are a helpful assistant. Answer the user's question using ONLY the context "
            + "provided below. If the answer is not in the context, say "
            + "'I could not find an answer in the uploaded document.' "
            + "Do not make up information. "
            + "You have access to the conversation history - use it to answer follow-up questions "
            + "and resolve pronouns or references to earlier turns.";

    public String askQuestion(String question, List<DocumentChunkDocument> chunks) {
        return askQuestion(question, chunks, List.of());
    }

    public String askQuestion(String question,
            List<DocumentChunkDocument> chunks,
            List<ConversationMessage> history) {
        log.info("Calling Spring AI Chat: question='{}', chunks={}, historyTurns={}",
                question, chunks.size(), history.size() / 2);

        List<Message> messages = buildMessages(question, chunks, history);
        String answer = chatModel.call(new Prompt(messages)).getResult().getOutput().getText();

        log.info("Received answer from Spring AI ({} chars).", answer.length());
        return answer;
    }

    private List<Message> buildMessages(String question,
            List<DocumentChunkDocument> chunks,
            List<ConversationMessage> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemContent(chunks)));
        for (ConversationMessage msg : history) {
            if ("user".equals(msg.getRole())) {
                messages.add(new UserMessage(msg.getContent()));
            } else {
                messages.add(new AssistantMessage(msg.getContent()));
            }
        }
        messages.add(new UserMessage(question));
        return messages;
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
