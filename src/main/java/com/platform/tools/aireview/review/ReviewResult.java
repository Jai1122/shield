package com.platform.tools.aireview.review;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/** The parsed model output: a summary plus zero or more findings. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ReviewResult {
    public String summary = "";
    public List<Finding> findings = new ArrayList<>();

    // Populated by the pipeline (not from the model), for rendering/telemetry.
    public transient String rangeLabel = "";
    public transient int filesChanged = 0;
    public transient int linesChanged = 0;
    public transient long elapsedMs = 0;
    public transient boolean fromCache = false;
    public transient boolean truncated = false;
    public transient int promptTokens = 0;
    public transient int completionTokens = 0;

    public static ReviewResult empty() { return new ReviewResult(); }
}
