package com.platform.tools.aireview.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResponseParserTest {

    @Test
    void parsesCleanJson() {
        String raw = """
            {"summary":"ok","findings":[
              {"severity":"major","file":"A.java","line":12,"title":"t","detail":"d","suggestion":"s"}
            ]}""";
        ReviewResult rr = ResponseParser.parse(raw);
        assertNotNull(rr);
        assertEquals("ok", rr.summary);
        assertEquals(1, rr.findings.size());
        assertEquals(Finding.Severity.MAJOR, rr.findings.get(0).sev());
        assertEquals(12, rr.findings.get(0).line());
    }

    @Test
    void recoversJsonWrappedInProseAndFences() {
        String raw = "Sure! Here is the review:\n```json\n{\"summary\":\"x\",\"findings\":[]}\n```\nThanks";
        ReviewResult rr = ResponseParser.parse(raw);
        assertNotNull(rr);
        assertEquals("x", rr.summary);
        assertTrue(rr.findings.isEmpty());
    }

    @Test
    void ignoresBracesInsideStrings() {
        String raw = "{\"summary\":\"a } b { c\",\"findings\":[]}";
        ReviewResult rr = ResponseParser.parse(raw);
        assertNotNull(rr);
        assertEquals("a } b { c", rr.summary);
    }

    @Test
    void skipsEmptyFindings() {
        String raw = "{\"summary\":\"\",\"findings\":[{\"severity\":\"info\"}]}";
        ReviewResult rr = ResponseParser.parse(raw);
        assertNotNull(rr);
        assertTrue(rr.findings.isEmpty(), "finding with no title/detail should be skipped");
    }

    @Test
    void returnsNullWhenNoJson() {
        assertNull(ResponseParser.parse("no json here at all"));
        assertNull(ResponseParser.parse(""));
        assertNull(ResponseParser.parse(null));
    }

    @Test
    void nullLineHandled() {
        String raw = "{\"summary\":\"s\",\"findings\":[{\"title\":\"t\",\"detail\":\"d\",\"line\":null}]}";
        ReviewResult rr = ResponseParser.parse(raw);
        assertNotNull(rr);
        assertEquals(1, rr.findings.size());
        assertNull(rr.findings.get(0).line());
    }
}
