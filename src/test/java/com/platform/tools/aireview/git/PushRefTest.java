package com.platform.tools.aireview.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PushRefTest {

    private static final String SHA = "1111111111111111111111111111111111111111";

    @Test
    void parsesWellFormedLine() {
        PushRef r = PushRef.parseLine("refs/heads/main " + SHA + " refs/heads/main " + PushRef.ZERO);
        assertNotNull(r);
        assertEquals("refs/heads/main", r.localRef());
        assertEquals(SHA, r.localOid());
        assertTrue(r.isNewBranch());
        assertFalse(r.isDeletion());
    }

    @Test
    void detectsDeletion() {
        PushRef r = PushRef.parseLine("(delete) " + PushRef.ZERO + " refs/heads/old " + SHA);
        assertNotNull(r);
        assertTrue(r.isDeletion());
    }

    @Test
    void detectsTag() {
        PushRef r = PushRef.parseLine("refs/tags/v1 " + SHA + " refs/tags/v1 " + PushRef.ZERO);
        assertNotNull(r);
        assertTrue(r.isTag());
    }

    @Test
    void rejectsMalformedLine() {
        assertNull(PushRef.parseLine("only three fields"));            // 3 tokens
        assertNull(PushRef.parseLine("a b c d e"));                    // 5 tokens
        assertNull(PushRef.parseLine(""));
        assertNull(PushRef.parseLine(null));
    }

    @Test
    void normalUpdateIsNeitherNewNorDelete() {
        PushRef r = PushRef.parseLine("refs/heads/main " + SHA + " refs/heads/main "
                + "2222222222222222222222222222222222222222");
        assertNotNull(r);
        assertFalse(r.isNewBranch());
        assertFalse(r.isDeletion());
        assertFalse(r.isTag());
    }
}
