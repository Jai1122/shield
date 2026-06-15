package com.platform.tools.aireview.llm;

/** Abstraction over the chat-completion backend so the server-side phase can reuse it. */
public interface LlmClient {
    /**
     * @param system system message content
     * @param user   user message content
     * @return the assistant's raw content string (expected to be JSON per the prompt)
     * @throws LlmException on transport/protocol failure
     */
    Result complete(String system, String user) throws LlmException;

    /** Carries content plus token usage and finish reason for logging/telemetry. */
    record Result(String content, String finishReason,
                  int promptTokens, int completionTokens, int totalTokens) {}
}
