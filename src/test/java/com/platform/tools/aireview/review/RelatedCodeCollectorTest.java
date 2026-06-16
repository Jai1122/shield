package com.platform.tools.aireview.review;

import com.platform.tools.aireview.config.Config;
import com.platform.tools.aireview.git.GitService;
import com.platform.tools.aireview.privacy.Redactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RelatedCodeCollectorTest {

    // ---- signatures() skeleton (pure) ----

    @Test
    void signaturesKeepsDeclarationsAndDropsBodies() {
        String src = """
            package com.x;
            import java.util.List;
            public class Foo {
                private int count;
                public int add(int a, int b) {
                    int s = a + b;
                    return s;
                }
            }
            """;
        String sig = RelatedCodeCollector.signatures(src);
        assertTrue(sig.contains("package com.x;"));
        assertTrue(sig.contains("import java.util.List;"));
        assertTrue(sig.contains("private int count;"), "field declaration preserved");
        assertTrue(sig.contains("public int add(int a, int b)"), "method signature preserved");
        assertFalse(sig.contains("int s = a + b;"), "method body collapsed");
        assertTrue(sig.contains("/* … */"), "placeholder inserted");
    }

    @Test
    void signaturesSurvivesUnbalancedBraces() {
        assertDoesNotThrow(() -> RelatedCodeCollector.signatures("class A { void m() { }}}}"));
    }

    // ---- collect() over a real temp repo ----

    private GitService initRepo(Path root) {
        GitService git = new GitService(root.toFile(), 10_000);
        assertTrue(git.run("init", "-q").ok());
        return git;
    }

    private String commit(GitService git, Path root, String rel, String content) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        assertTrue(git.run("add", "-A").ok());
        assertTrue(git.run("-c", "user.email=t@t", "-c", "user.name=t",
                "commit", "-q", "-m", "c").ok());
        return git.resolveCommit("HEAD");
    }

    @Test
    void collectIncludesFullBodyForSmallFile(@TempDir Path root) throws Exception {
        GitService git = initRepo(root);
        String tip = commit(git, root, "src/A.java", "class A { void m() { int x = 1; } }\n");

        Config.RelatedCode rc = new Config.RelatedCode();
        String out = RelatedCodeCollector.collect(
                git, tip, List.of("src/A.java"), rc, new Redactor(List.of()), false);

        assertTrue(out.contains("// ===== src/A.java ====="), "labelled header");
        assertTrue(out.contains("int x = 1;"), "full body included when under budget");
    }

    @Test
    void collectDegradesOversizedFileToSignatures(@TempDir Path root) throws Exception {
        GitService git = initRepo(root);
        String body = "class Big { void m() { " + "int n = 0; ".repeat(50) + " } }\n";
        String tip = commit(git, root, "Big.java", body);

        Config.RelatedCode rc = new Config.RelatedCode();
        rc.maxFileChars = 40; // force degradation
        String out = RelatedCodeCollector.collect(
                git, tip, List.of("Big.java"), rc, new Redactor(List.of()), false);

        assertTrue(out.contains("signatures only"), "oversized file flagged");
        assertTrue(out.contains("class Big"), "declaration still present");
        assertFalse(out.contains("int n = 0; int n = 0;"), "body collapsed");
    }

    @Test
    void collectDisabledReturnsEmpty(@TempDir Path root) throws Exception {
        GitService git = initRepo(root);
        String tip = commit(git, root, "A.java", "class A {}\n");
        Config.RelatedCode rc = new Config.RelatedCode();
        rc.enabled = false;
        assertEquals("", RelatedCodeCollector.collect(
                git, tip, List.of("A.java"), rc, new Redactor(List.of()), false));
    }

    @Test
    void collectFailSoftOnUnreadableFile(@TempDir Path root) throws Exception {
        GitService git = initRepo(root);
        String tip = commit(git, root, "A.java", "class A {}\n");
        Config.RelatedCode rc = new Config.RelatedCode();
        // Missing path must be skipped, not throw, and yield empty (no readable bodies).
        assertEquals("", RelatedCodeCollector.collect(
                git, tip, List.of("Missing.java"), rc, new Redactor(List.of()), false));
    }
}
