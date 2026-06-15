package com.platform.tools.aireview.doctor;

import com.platform.tools.aireview.config.Config;
import com.platform.tools.aireview.config.ConfigLoader;
import com.platform.tools.aireview.llm.MiniMaxClient;
import com.platform.tools.aireview.util.Version;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** `aireview doctor` — environment self-diagnosis. Always exits 0. */
public final class Doctor {
    private Doctor() {}

    public static int run() {
        var out = System.err;
        out.println("aireview doctor (v" + Version.get() + ")");

        out.println("  java: " + System.getProperty("java.version"));

        Config cfg = ConfigLoader.load(null);
        out.println("  config.schemaVersion: " + cfg.schemaVersion);
        out.println("  llm.baseUrl: " + cfg.llm.baseUrl);
        out.println("  llm.model: " + cfg.llm.model);
        out.println("  llm.auth.scheme: " + cfg.llm.auth.scheme);

        MiniMaxClient client = new MiniMaxClient(cfg.llm);
        out.println("  credentials present: " + (client.hasCredentials() ? "yes" : "NO (review will skip)"));

        out.println("  repositories configured: " + cfg.repositories.size());

        // Lightweight reachability probe (HEAD/GET on base URL, non-fatal).
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.llm.baseUrl))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
            out.println("  endpoint reachable: yes (HTTP " + r.statusCode() + ")");
        } catch (Exception e) {
            out.println("  endpoint reachable: could not connect (" + e.getClass().getSimpleName() + ")");
        }

        out.println("Done.");
        return 0;
    }
}
