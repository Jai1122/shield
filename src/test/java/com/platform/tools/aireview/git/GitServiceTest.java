package com.platform.tools.aireview.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Integration tests over a real throwaway git repo (the tool shells to git anyway). */
class GitServiceTest {

    private GitService git;

    private void writeCommit(Path root, String relPath, String content, String msg) throws Exception {
        Path f = root.resolve(relPath);
        Files.createDirectories(f.getParent() == null ? root : f.getParent());
        Files.writeString(f, content);
        assertTrue(git.run("add", "-A").ok());
        assertTrue(git.run("-c", "user.email=t@t", "-c", "user.name=t",
                "commit", "-q", "-m", msg).ok());
    }

    @Test
    void changedFilesMatchesRootLevelViaGlob(@TempDir Path root) throws Exception {
        git = new GitService(root.toFile(), 10_000);
        assertTrue(git.run("init", "-q").ok());

        // base commit
        writeCommit(root, "src/main/java/com/Foo.java", "class Foo {}\n", "base");
        String base = git.resolveCommit("HEAD");

        // change a nested file AND a root-level file (the :(glob) regression)
        writeCommit(root, "src/main/java/com/Foo.java", "class Foo { int x; }\n", "edit nested");
        writeCommit(root, "Root.java", "class Root {}\n", "add root-level");
        String tip = git.resolveCommit("HEAD");

        List<String> changed = git.changedFiles(base, tip, List.of("**/*.java"));
        assertTrue(changed.contains("Root.java"),
                "root-level file must match **/*.java via :(glob); got " + changed);
        assertTrue(changed.contains("src/main/java/com/Foo.java"), "nested file should match; got " + changed);
    }

    @Test
    void fileAtCommitReturnsPostChangeBody(@TempDir Path root) throws Exception {
        git = new GitService(root.toFile(), 10_000);
        assertTrue(git.run("init", "-q").ok());
        writeCommit(root, "A.java", "class A { void m() { return; } }\n", "v1");
        String tip = git.resolveCommit("HEAD");

        String body = git.fileAtCommit(tip, "A.java");
        assertNotNull(body);
        assertTrue(body.contains("class A"));

        assertNull(git.fileAtCommit(tip, "Missing.java"), "unreadable path returns null");
    }

    @Test
    void changedFilesExcludesDeletions(@TempDir Path root) throws Exception {
        git = new GitService(root.toFile(), 10_000);
        assertTrue(git.run("init", "-q").ok());
        writeCommit(root, "Gone.java", "class Gone {}\n", "add");
        writeCommit(root, "Keep.java", "class Keep {}\n", "add keep");
        String base = git.resolveCommit("HEAD");
        Files.delete(root.resolve("Gone.java"));
        Files.writeString(root.resolve("Keep.java"), "class Keep { int y; }\n");
        assertTrue(git.run("add", "-A").ok());
        assertTrue(git.run("-c", "user.email=t@t", "-c", "user.name=t",
                "commit", "-q", "-m", "delete + edit").ok());
        String tip = git.resolveCommit("HEAD");

        List<String> changed = git.changedFiles(base, tip, List.of("**/*.java"));
        assertFalse(changed.contains("Gone.java"), "deleted file has no post-change body; got " + changed);
        assertTrue(changed.contains("Keep.java"));
    }
}
