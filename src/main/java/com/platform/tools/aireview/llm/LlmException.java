package com.platform.tools.aireview.llm;

/** Thrown for LLM transport/protocol failures. Carries whether a retry makes sense. */
public final class LlmException extends Exception {
    public final boolean retriable;
    public final int httpStatus; // -1 if not an HTTP status

    public LlmException(String message, boolean retriable, int httpStatus, Throwable cause) {
        super(message, cause);
        this.retriable = retriable;
        this.httpStatus = httpStatus;
    }

    public LlmException(String message, boolean retriable, int httpStatus) {
        this(message, retriable, httpStatus, null);
    }
}
