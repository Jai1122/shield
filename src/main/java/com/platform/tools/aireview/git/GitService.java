package com.platform.tools.aireview.git;

import com.platform.tools.aireview.util.Logs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Thin wrapper over the git CLI. Runs from a fixed working directory. */
public final class GitService {

    private final File workDir;
    private final long perCommandTimeoutMs;

    public GitService(File workDir, long perCommandTimeoutMs) {
        this.workDir = workDir;
        this.perCommandTimeoutMs = perCommandTimeoutMs;
    }

    /** Result of a git invocation. */
    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() { return exitCode == 0; }
    }

    public Result run(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workDir != null) pb.directory(workDir);
            pb.environment().put("GIT_PAGER", "cat");
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process proc = pb.start();
            // Drain streams to avoid deadlock on large output.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread to = pump(proc.getInputStream(), out);
            Thread te = pump(proc.getErrorStream(), err);
            boolean finished = proc.waitFor(perCommandTimeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                Logs.warn("git timed out: " + String.join(" ", args));
                return new Result(124, "", "timeout");
            }
            to.join(1000);
            te.join(1000);
            return new Result(proc.exitValue(),
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logs.error("git failed: " + String.join(" ", args), e);
            return new Result(-1, "", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private static Thread pump(java.io.InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            try { in.transferTo(sink); } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ---- high-level helpers ----

    public String topLevel() {
        Result r = run("rev-parse", "--show-toplevel");
        return r.ok() ? r.stdout().trim() : null;
    }

    public String originUrl() {
        Result r = run("config", "--get", "remote.origin.url");
        return r.ok() ? r.stdout().trim() : null;
    }

    /** Commits in base..tip (exclusive base). */
    public int countCommits(String range) {
        Result r = run("rev-list", "--count", range);
        try { return r.ok() ? Integer.parseInt(r.stdout().trim()) : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    /** Commits on tip not present on any remote (for new branches). */
    public List<String> newCommits(String tip) {
        Result r = run("rev-list", tip, "--not", "--remotes");
        if (!r.ok()) return List.of();
        List<String> out = new ArrayList<>();
        for (String l : r.stdout().split("\n")) if (!l.isBlank()) out.add(l.trim());
        return out;
    }

    public String mergeBase(String a, String b) {
        Result r = run("merge-base", a, b);
        return r.ok() ? r.stdout().trim() : null;
    }

    /** Resolve a commit-ish to a full SHA, or null if it doesn't exist. */
    public String resolveCommit(String id) {
        Result r = run("rev-parse", "--verify", "--quiet", id + "^{commit}");
        return r.ok() && !r.stdout().isBlank() ? r.stdout().trim() : null;
    }

    /** First-parent SHA of a commit, or null if it's a root commit (no parent). */
    public String parentSha(String commit) {
        Result r = run("rev-parse", "--verify", "--quiet", commit + "^1");
        return r.ok() && !r.stdout().isBlank() ? r.stdout().trim() : null;
    }

    /** Commit subject + body for a single commit. */
    public String commitMessage(String commit) {
        Result r = run("log", "-1", "--no-color", "--format=* %h %s%n%b", commit);
        return r.ok() ? r.stdout().trim() : "";
    }

    public boolean isAncestor(String maybeAncestor, String descendant) {
        return run("merge-base", "--is-ancestor", maybeAncestor, descendant).exitCode() == 0;
    }

    /**
     * Unified diff of base..tip restricted to the given globs. Each glob is wrapped in git's
     * {@code :(glob)} pathspec magic so {@code **} matches across path segments AND zero segments
     * (i.e. top-level files match too) — without it, {@code **}/{@code *.java} would require a
     * directory separator and silently miss root-level files.
     */
    public String diff(String base, String tip, int contextLines, List<String> globs) {
        List<String> args = new ArrayList<>(List.of(
                "diff", "--no-color", "--unified=" + contextLines, base + ".." + tip, "--"));
        for (String g : globs) args.add(":(glob)" + g);
        Result r = run(args.toArray(new String[0]));
        return r.ok() ? r.stdout() : "";
    }

    /**
     * Paths changed in base..tip, restricted to the given globs, excluding deletions
     * ({@code --diff-filter=d}) since deleted files have no post-change body. Uses the same
     * {@code :(glob)} pathspec magic as {@link #diff} so {@code **} matches root-level files.
     * For renames/copies this yields the new path.
     */
    public List<String> changedFiles(String base, String tip, List<String> globs) {
        List<String> args = new ArrayList<>(List.of(
                "diff", "--name-only", "--diff-filter=d", "--no-color", base + ".." + tip, "--"));
        for (String g : globs) args.add(":(glob)" + g);
        Result r = run(args.toArray(new String[0]));
        List<String> out = new ArrayList<>();
        if (!r.ok()) return out;
        for (String l : r.stdout().split("\n")) if (!l.isBlank()) out.add(l.trim());
        return out;
    }

    /**
     * Full content of {@code path} as it exists at {@code commit} (the post-change version when
     * {@code commit} is the tip). Returns null if the blob can't be read (e.g. binary, missing).
     */
    public String fileAtCommit(String commit, String path) {
        Result r = run("show", "--no-color", commit + ":" + path);
        return r.ok() ? r.stdout() : null;
    }

    /** numstat lines: "<added>\t<deleted>\t<path>". */
    public String numstat(String base, String tip) {
        Result r = run("diff", "--numstat", "--no-color", base + ".." + tip);
        return r.ok() ? r.stdout() : "";
    }

    /** Commit messages for the range, oldest→newest. */
    public String log(String range) {
        Result r = run("log", "--no-color", "--reverse", "--format=* %h %s%n%b", range);
        return r.ok() ? r.stdout().trim() : "";
    }

    /** Word-boundary grep for a symbol across the repo. */
    public List<String> grepSymbol(String symbol, int maxHits) {
        Result r = run("grep", "-n", "-w", "--", symbol);
        List<String> hits = new ArrayList<>();
        if (!r.ok()) return hits;
        for (String l : r.stdout().split("\n")) {
            if (l.isBlank()) continue;
            hits.add(l.trim());
            if (hits.size() >= maxHits) break;
        }
        return hits;
    }
}
