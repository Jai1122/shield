package com.platform.tools.aireview;

import com.platform.tools.aireview.cache.ReviewCache;
import com.platform.tools.aireview.config.Config;
import com.platform.tools.aireview.config.ConfigLoader;
import com.platform.tools.aireview.doctor.Doctor;
import com.platform.tools.aireview.git.GitService;
import com.platform.tools.aireview.git.PushRef;
import com.platform.tools.aireview.llm.MiniMaxClient;
import com.platform.tools.aireview.output.Renderer;
import com.platform.tools.aireview.privacy.Redactor;
import com.platform.tools.aireview.review.ReviewService;
import com.platform.tools.aireview.util.Ansi;
import com.platform.tools.aireview.util.Logs;
import com.platform.tools.aireview.util.Version;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * CLI entrypoint. Subcommands: pre-push | doctor | review | --version | --help.
 *
 * ADVISORY GUARANTEE: in MVP this process exits 0 in ALL cases (SPEC §9/§14).
 */
public final class Main {

    public static void main(String[] args) {
        int code = 0;
        try {
            code = dispatch(args);
        } catch (Throwable t) {
            // Catch-all: a reviewer must never break a push.
            Logs.error("unhandled error in main", t);
            System.err.println("aireview: internal error — skipped (see ~/.cache/aireview/aireview.log)");
            code = 0;
        }
        System.exit(code);
    }

    private static int dispatch(String[] args) {
        if (args.length == 0) { printHelp(); return 0; }
        for (String a : args) if (a.equals("--debug")) Logs.setDebug(true);

        return switch (args[0]) {
            case "pre-push" -> runPrePush();
            case "review" -> runDryRun();
            case "commit" -> runCommit(args);
            case "doctor" -> Doctor.run();
            case "--version", "-v" -> { System.out.println("aireview " + Version.get()); yield 0; }
            default -> { printHelp(); yield 0; }
        };
    }

    private static int runPrePush() {
        List<PushRef> refs = readPushRefs();
        if (refs.isEmpty()) return 0; // nothing on stdin → nothing to do
        return reviewAndRender(refs);
    }

    /** review --dry-run equivalent: review HEAD~1..HEAD without a real push. */
    private static int runDryRun() {
        File cwd = new File(System.getProperty("user.dir"));
        GitService git = new GitService(cwd, 10000);
        String head = firstLine(git.run("rev-parse", "HEAD").stdout());
        String prev = firstLine(git.run("rev-parse", "HEAD~1").stdout());
        if (head == null || prev == null) {
            System.err.println("aireview: cannot resolve HEAD~1..HEAD for dry-run");
            return 0;
        }
        PushRef synthetic = new PushRef("refs/heads/HEAD", head, "refs/heads/HEAD", prev);
        return reviewAndRender(List.of(synthetic));
    }

    @FunctionalInterface
    private interface ReviewOp { ReviewService.Outcome run(ReviewService s) throws Exception; }

    private static int reviewAndRender(List<PushRef> refs) {
        return execute(s -> s.review(refs));
    }

    private static int reviewCommitAndRender(String commitId, String jira) {
        return execute(s -> s.reviewCommit(commitId, jira));
    }

    /** aireview commit &lt;commitId&gt; [--jira "..." | --jira-file path]  (else reads stdin). */
    private static int runCommit(String[] args) {
        String commitId = null, jira = null, jiraFile = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--jira" -> { if (i + 1 < args.length) jira = args[++i]; }
                case "--jira-file" -> { if (i + 1 < args.length) jiraFile = args[++i]; }
                case "--debug" -> { /* handled in dispatch */ }
                default -> { if (!a.startsWith("--") && commitId == null) commitId = a; }
            }
        }
        if (commitId == null) {
            System.err.println("usage: aireview commit <commitId> [--jira \"text\" | --jira-file <path>]");
            return 0;
        }
        if (jira == null && jiraFile != null) jira = readFile(jiraFile);
        // If no Jira text supplied and input is piped (not a TTY), read it from stdin.
        if (jira == null && jiraFile == null && System.console() == null) jira = readStdin();
        return reviewCommitAndRender(commitId, jira);
    }

    private static int execute(ReviewOp op) {
        File cwd = new File(System.getProperty("user.dir"));
        Config cfg = ConfigLoader.load(resolveRepoRoot(cwd));
        configureColor(cfg);

        long budget = cfg.guardrails.totalTimeBudgetMs;
        GitService git = new GitService(cwd, Math.min(10000, budget));
        Path repoRoot = resolveRepoRoot(cwd);
        if (repoRoot == null) return 0; // not a git repo → silent skip

        MiniMaxClient llm = new MiniMaxClient(cfg.llm);
        if (!llm.hasCredentials()) {
            System.err.println("aireview: no API credentials configured — skipping review (run: aireview doctor)");
            return 0;
        }

        Redactor redactor = new Redactor(null);
        ReviewCache cache = new ReviewCache(cfg.guardrails.cache.enabled, cfg.guardrails.cache.ttlHours);
        ReviewService service = new ReviewService(cfg, git, repoRoot, llm, redactor, cache);
        Renderer renderer = new Renderer(cfg.output);

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aireview-review");
            t.setDaemon(true);
            return t;
        });
        Spinner spinner = new Spinner(cfg);
        long started = System.nanoTime();
        Future<ReviewService.Outcome> future = exec.submit(() -> op.run(service));
        spinner.start();
        try {
            ReviewService.Outcome outcome = future.get(budget, TimeUnit.MILLISECONDS);
            spinner.stop();
            if (outcome.isSkip()) {
                if (outcome.skipMessage() != null) System.err.println(outcome.skipMessage());
            } else {
                outcome.result().elapsedMs = (System.nanoTime() - started) / 1_000_000;
                renderer.render(outcome.result());
                Logs.info("reviewed " + outcome.result().rangeLabel
                        + " findings=" + outcome.result().findings.size()
                        + " cache=" + outcome.result().fromCache
                        + " tokens=" + outcome.result().promptTokens + "/" + outcome.result().completionTokens
                        + " ms=" + outcome.result().elapsedMs);
            }
        } catch (TimeoutException e) {
            spinner.stop();
            future.cancel(true);
            System.err.println("⏱ aireview: review skipped (time budget exceeded)");
            Logs.warn("time budget exceeded (" + budget + "ms)");
        } catch (Exception e) {
            spinner.stop();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Logs.error("review failed", cause);
            System.err.println("aireview: review unavailable — skipped (" + cause.getClass().getSimpleName() + ")");
        } finally {
            exec.shutdownNow();
        }
        return 0; // ALWAYS advisory
    }

    // ---- helpers ----

    private static List<PushRef> readPushRefs() {
        List<PushRef> refs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                PushRef r = PushRef.parseLine(line);
                if (r != null) refs.add(r);
            }
        } catch (Exception e) {
            Logs.warn("could not read pre-push stdin: " + e.getClass().getSimpleName());
        }
        return refs;
    }

    private static String readFile(String path) {
        try {
            return java.nio.file.Files.readString(Path.of(path));
        } catch (Exception e) {
            System.err.println("aireview: could not read --jira-file " + path + " (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static String readStdin() {
        try {
            byte[] b = System.in.readAllBytes();
            if (b.length == 0) return null;
            String s = new String(b, StandardCharsets.UTF_8).strip();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static Path resolveRepoRoot(File cwd) {
        GitService git = new GitService(cwd, 5000);
        String top = git.topLevel();
        return top == null ? null : Path.of(top);
    }

    private static void configureColor(Config cfg) {
        boolean on = switch (cfg.output.color == null ? "auto" : cfg.output.color) {
            case "always" -> true;
            case "never" -> false;
            default -> System.console() != null; // auto: only when attached to a TTY
        };
        Ansi.setEnabled(on);
    }

    private static String firstLine(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t.split("\n", 2)[0].trim();
    }

    private static void printHelp() {
        System.err.println("""
            aireview — advisory AI code review (pre-push)

            Usage:
              aireview pre-push <remoteName> <remoteURL>   (invoked by the git hook; reads stdin)
              aireview commit <commitId> [--jira "text" | --jira-file <path>]
                                                           review one commit; Jira text as intent
                                                           (Jira text may also be piped via stdin)
              aireview review                              dry-run review of HEAD~1..HEAD
              aireview doctor                              environment self-check
              aireview --version

            Advisory only: this tool never blocks a push.
            """);
    }

    /** Minimal stderr spinner so a synchronous review never looks hung. */
    private static final class Spinner {
        private final boolean active;
        private volatile boolean running = false;
        private Thread thread;

        Spinner(Config cfg) {
            this.active = System.console() != null
                    && !"never".equalsIgnoreCase(cfg.output.color)
                    && !"json".equalsIgnoreCase(cfg.output.format);
        }

        void start() {
            if (!active) return;
            running = true;
            thread = new Thread(() -> {
                String[] frames = {"⠋","⠙","⠹","⠸","⠼","⠴","⠦","⠧","⠇","⠏"};
                int i = 0;
                long t0 = System.currentTimeMillis();
                while (running) {
                    long s = (System.currentTimeMillis() - t0) / 1000;
                    System.err.print("\r" + frames[i++ % frames.length] + " aireview reviewing… " + s + "s ");
                    System.err.flush();
                    try { Thread.sleep(120); } catch (InterruptedException e) { break; }
                }
            }, "aireview-spinner");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            if (thread != null) thread.interrupt();
            if (active) { System.err.print("\r\033[2K"); System.err.flush(); }
        }
    }
}
