package com.platform.tools.aireview.config;

/** One managed repository entry from the {@code repositories[]} array. */
public final class RepositoryConfig {
    public String name = "";
    public Match match = new Match();
    public String trunkBranch = null;       // null → inherit review.trunkBranch
    public String agentsFile = "AGENTS.md";
    public String bestPracticesFile = null; // null → repo-local / global / bundled
    public boolean enabled = true;

    public static final class Match {
        public String path = null;       // absolute worktree path
        public String remoteUrl = null;  // origin URL (substring match)
    }
}
