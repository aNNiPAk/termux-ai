package com.termux.app.mcp.a11y;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class QueryResultFingerprintTest {

    @Test
    public void identicalSemanticResultsHaveSameHash() {
        QueryResultFingerprint first = new QueryResultFingerprint();
        first.add("id/title", "  Mirror\u00a0 80x85 ", "", "TextView",
            false, false, false, false, true);
        QueryResultFingerprint second = new QueryResultFingerprint();
        second.add("id/title", "Mirror 80x85", "", "TextView",
            false, false, false, false, true);

        assertEquals(first.finish(), second.finish());
    }

    @Test
    public void meaningfulFieldChangeChangesHash() {
        QueryResultFingerprint first = new QueryResultFingerprint();
        first.add("id/title", "Mirror 80x85", "", "TextView",
            false, false, false, false, true);
        QueryResultFingerprint second = new QueryResultFingerprint();
        second.add("id/title", "Mirror 80x90", "", "TextView",
            false, false, false, false, true);

        assertNotEquals(first.finish(), second.finish());
    }

    @Test
    public void resultOrderIsPartOfFingerprint() {
        QueryResultFingerprint first = new QueryResultFingerprint();
        first.add("id/title", "A", "", "TextView",
            false, false, false, false, true);
        first.add("id/title", "B", "", "TextView",
            false, false, false, false, true);
        QueryResultFingerprint second = new QueryResultFingerprint();
        second.add("id/title", "B", "", "TextView",
            false, false, false, false, true);
        second.add("id/title", "A", "", "TextView",
            false, false, false, false, true);

        assertNotEquals(first.finish(), second.finish());
    }
}
