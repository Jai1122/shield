package com.platform.tools.aireview.llm;

import com.platform.tools.aireview.config.Config;
import com.platform.tools.aireview.util.Json;
import com.platform.tools.aireview.util.Logs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Self-hosted MiniMax (OpenAI-compatible / vLLM) client using the JDK HttpClient.
 * Implements the wire format documented in SPEC §12.6.
 */
public final class MiniMaxClient implements LlmClient {

    private final Config.Llm cfg;
    private final HttpClient http;
    private final String authHeaderName;
    private final String authHeaderValue; // null if credentials are missing
    private volatile Boolean jsonModeSupported = null; // null = unknown, probe once

    public MiniMaxClient(Config.Llm cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(5000, cfg.requestTimeoutMs)))
                .build();
        this.authHeaderName = cfg.auth.header;
        this.authHeaderValue = resolveAuthValue(cfg);
    }

    /** True when the credential required by the configured scheme is present. */
    public boolean hasCredentials() { return authHeaderValue != null; }

    private static String resolveAuthValue(Config.Llm cfg) {
        String scheme = cfg.auth.scheme == null ? "bearer" : cfg.auth.scheme.toLowerCase();
        switch (scheme) {
            case "bearer" -> {
                String t = env("AIREVIEW_API_TOKEN");
                return t == null ? null : "Bearer " + t;
            }
            case "custom" -> {
                String t = env("AIREVIEW_API_TOKEN");
                return t; // verbatim
            }
            case "basic" -> {
                if (cfg.auth.preEncoded) {
                    String b = env("AIREVIEW_API_BASIC");
                    return b == null ? null : "Basic " + b;
                }
                String u = env("AIREVIEW_API_USER");
                String p = env("AIREVIEW_API_PASSWORD");
                if (u == null || p == null) return null;
                String b64 = Base64.getEncoder()
                        .encodeToString((u + ":" + p).getBytes(StandardCharsets.UTF_8));
                return "Basic " + b64;
            }
            default -> {
                Logs.warn("unknown auth scheme '" + scheme + "', defaulting to bearer");
                String t = env("AIREVIEW_API_TOKEN");
                return t == null ? null : "Bearer " + t;
            }
        }
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    @Override
    public Result complete(String system, String user) throws LlmException {
        if (authHeaderValue == null) {
            throw new LlmException("missing credentials for scheme " + cfg.auth.scheme, false, 401);
        }
        List<Chat.Message> messages = List.of(
                new Chat.Message("system", system),
                new Chat.Message("user", user));

        boolean tryJson = jsonModeSupported == null || jsonModeSupported;
        try {
            return send(messages, tryJson);
        } catch (LlmException e) {
            // If JSON mode was rejected (400), disable it and retry once without it.
            if (tryJson && e.httpStatus == 400) {
                Logs.warn("server rejected response_format json_object; falling back to plain mode");
                jsonModeSupported = false;
                return send(messages, false);
            }
            throw e;
        }
    }

    private Result send(List<Chat.Message> messages, boolean jsonMode) throws LlmException {
        Chat.Request body = Chat.Request.of(cfg.model, messages, cfg.temperature,
                cfg.maxOutputTokens, jsonMode);
        String json;
        try {
            json = Json.MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new LlmException("failed to serialize request", false, -1, e);
        }

        String url = cfg.baseUrl.replaceAll("/+$", "") + cfg.chatPath;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(cfg.requestTimeoutMs))
                .header("Content-Type", "application/json")
                .header(authHeaderName, authHeaderValue)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new LlmException("request timed out", true, -1, e);
        } catch (Exception e) {
            throw new LlmException("network error: " + e.getMessage(), true, -1, e);
        }

        int sc = resp.statusCode();
        if (sc == 200) {
            return parse(resp.body(), jsonMode);
        }
        boolean retriable = sc == 429 || (sc >= 500 && sc < 600);
        throw new LlmException("HTTP " + sc + " from LLM", retriable, sc);
    }

    private Result parse(String bodyStr, boolean jsonMode) throws LlmException {
        try {
            Chat.Response r = Json.MAPPER.readValue(bodyStr, Chat.Response.class);
            String content = r.firstContent();
            if (content == null) throw new LlmException("no choices/content in response", false, 200);
            if (jsonMode && jsonModeSupported == null) jsonModeSupported = true; // probe succeeded
            Chat.Usage u = r.usage();
            return new Result(content, r.firstFinishReason(),
                    u == null ? 0 : u.promptTokens(),
                    u == null ? 0 : u.completionTokens(),
                    u == null ? 0 : u.totalTokens());
        } catch (LlmException le) {
            throw le;
        } catch (Exception e) {
            throw new LlmException("failed to parse LLM response envelope", false, 200, e);
        }
    }
}
