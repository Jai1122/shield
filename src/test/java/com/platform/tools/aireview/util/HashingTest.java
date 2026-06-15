package com.platform.tools.aireview.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HashingTest {

    @Test
    void stableForSameInput() {
        assertEquals(Hashing.sha256("a", "b", "c"), Hashing.sha256("a", "b", "c"));
    }

    @Test
    void differsForDifferentInput() {
        assertNotEquals(Hashing.sha256("a", "b"), Hashing.sha256("a", "c"));
    }

    @Test
    void partBoundariesMatter() {
        // "ab" + "c" must not collide with "a" + "bc" thanks to the separator
        assertNotEquals(Hashing.sha256("ab", "c"), Hashing.sha256("a", "bc"));
    }

    @Test
    void handlesNullParts() {
        assertNotNull(Hashing.sha256("a", null, "c"));
    }
}
