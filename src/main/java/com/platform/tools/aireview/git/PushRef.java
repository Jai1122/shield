package com.platform.tools.aireview.git;

/**
 * One line of pre-push stdin: {@code <localRef> <localSHA> <remoteRef> <remoteSHA>}.
 * Per Git docs, a zero OID means "ref does not exist" on the respective side.
 */
public record PushRef(String localRef, String localOid, String remoteRef, String remoteOid) {

    public static final String ZERO = "0000000000000000000000000000000000000000";

    /** Local side is zero → this ref is being deleted; nothing to review. */
    public boolean isDeletion() { return isZero(localOid); }

    /** Remote side is zero → new branch/ref on the remote (no remote ancestor). */
    public boolean isNewBranch() { return isZero(remoteOid); }

    public boolean isTag() { return localRef != null && localRef.startsWith("refs/tags/"); }

    private static boolean isZero(String oid) {
        return oid == null || oid.chars().allMatch(ch -> ch == '0');
    }

    /** Parse a single stdin line; returns null if malformed. */
    public static PushRef parseLine(String line) {
        if (line == null) return null;
        String[] p = line.trim().split("\\s+");
        if (p.length != 4) return null;
        return new PushRef(p[0], p[1], p[2], p[3]);
    }
}
