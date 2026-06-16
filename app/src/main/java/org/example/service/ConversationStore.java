package org.example.service;

import org.example.model.ConversationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory conversation store.
 *
 * Each session is identified by a UUID string. History is kept as an ordered
 * deque of (role, content) pairs. When the deque exceeds {@code maxTurns * 2}
 * entries (each turn = 1 user + 1 assistant message), the oldest pair is evicted
 * so the context window stays bounded.
 */
@Slf4j
@Service
public class ConversationStore {

    /** Number of past Q&A turns to retain per session (configurable). */
    @Value("${conversation.max-turns:10}")
    private int maxTurns;

    private final ConcurrentHashMap<String, Deque<ConversationMessage>> sessions =
            new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Session lifecycle
    // -----------------------------------------------------------------------

    /** Creates a new session and returns its ID. */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new ArrayDeque<>());
        log.info("Created conversation session: {}", sessionId);
        return sessionId;
    }

    /**
     * Returns the session's history as an ordered list (oldest first).
     * Returns an empty list if the session does not exist.
     */
    public List<ConversationMessage> getHistory(String sessionId) {
        Deque<ConversationMessage> deque = sessions.get(sessionId);
        if (deque == null) {
            log.debug("Session '{}' not found — returning empty history.", sessionId);
            return List.of();
        }
        return new ArrayList<>(deque);
    }

    /**
     * Appends a user message then the assistant reply to the session.
     * Evicts the oldest turn when the cap is exceeded.
     * Auto-creates the session if it does not exist yet.
     */
    public void addTurn(String sessionId, String userMessage, String assistantReply) {
        Deque<ConversationMessage> deque =
                sessions.computeIfAbsent(sessionId, id -> new ArrayDeque<>());

        // Evict oldest turn (2 messages) when over cap
        int maxMessages = maxTurns * 2;
        while (deque.size() >= maxMessages) {
            deque.pollFirst(); // remove oldest user msg
            deque.pollFirst(); // remove oldest assistant msg
        }

        deque.addLast(new ConversationMessage("user",      userMessage));
        deque.addLast(new ConversationMessage("assistant", assistantReply));

        log.debug("Session '{}': added turn, history size now {} messages.", sessionId, deque.size());
    }

    /** Clears history for a session without deleting the session itself. */
    public void clearSession(String sessionId) {
        Deque<ConversationMessage> deque = sessions.get(sessionId);
        if (deque != null) {
            deque.clear();
            log.info("Cleared conversation history for session: {}", sessionId);
        }
    }

    /** Returns true if the session exists. */
    public boolean sessionExists(String sessionId) {
        return sessions.containsKey(sessionId);
    }
}
