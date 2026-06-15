package com.platform.tools.aireview.privacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedactorTest {

    private final Redactor r = new Redactor(null);

    @Test
    void redactsAwsAccessKey() {
        String out = r.redact("+ String k = \"AKIAIOSFODNN7EXAMPLE\";");
        assertFalse(out.contains("AKIAIOSFODNN7EXAMPLE"));
        assertTrue(out.contains("redacted"));
    }

    @Test
    void redactsPasswordAssignment() {
        String out = r.redact("password = hunter2secret");
        assertFalse(out.contains("hunter2secret"));
        assertTrue(out.toLowerCase().contains("password"), "key name should remain");
    }

    @Test
    void redactsJdbcCredentials() {
        String out = r.redact("jdbc:postgresql://user:p4ssw0rd@db:5432/app");
        assertFalse(out.contains("user:p4ssw0rd"));
        assertTrue(out.contains("jdbc:postgresql://"));
    }

    @Test
    void leavesOrdinaryCodeAlone() {
        String src = "+ public int add(int a, int b) { return a + b; }";
        assertEquals(src, r.redact(src));
    }

    @Test
    void handlesNullAndEmpty() {
        assertNull(r.redact(null));
        assertEquals("", r.redact(""));
    }
}
