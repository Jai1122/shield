package com.platform.tools.aireview.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** OpenAI-compatible chat-completion DTOs (matches the self-hosted vLLM wire format). */
public final class Chat {
    private Chat() {}

    public record Message(String role, String content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(
            String model,
            List<Message> messages,
            double temperature,
            @JsonProperty("max_tokens") int maxTokens,
            boolean stream,
            @JsonProperty("response_format") Map<String, Object> responseFormat) {

        public static Request of(String model, List<Message> messages, double temperature,
                                 int maxTokens, boolean jsonMode) {
            Map<String, Object> rf = jsonMode ? Map.of("type", "json_object") : null;
            return new Request(model, messages, temperature, maxTokens, false, rf);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            String id,
            String model,
            String object,
            List<Choice> choices,
            Usage usage) {

        public String firstContent() {
            if (choices == null || choices.isEmpty()) return null;
            Choice c = choices.get(0);
            return (c == null || c.message() == null) ? null : c.message().content();
        }

        public String firstFinishReason() {
            if (choices == null || choices.isEmpty()) return null;
            return choices.get(0).finishReason();
        }
    }

    public record Choice(
            int index,
            @JsonProperty("finish_reason") String finishReason,
            Message message) {}

    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {}
}
