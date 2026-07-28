package androidx.media3.mpvplayer;

import java.io.IOException;

/** Generation gate that prevents cancelled MPV HLS preload work from resuming after recovery. */
final class MpvHlsPreloadGate {

    private volatile boolean allowed = true;
    private volatile long generation;

    synchronized Transition update(boolean allow) {
        if (allowed == allow) return Transition.UNCHANGED;
        allowed = allow;
        if (!allow && generation < Long.MAX_VALUE) generation++;
        return allow ? Transition.ALLOWED : Transition.BLOCKED;
    }

    synchronized long invalidate() {
        if (generation < Long.MAX_VALUE) generation++;
        return generation;
    }

    long acquire() {
        long current = generation;
        return allowed ? current : -1;
    }

    boolean allows(long expectedGeneration) {
        return expectedGeneration >= 0
                && allowed
                && expectedGeneration == generation;
    }

    synchronized boolean commitIfAllowed(
            long expectedGeneration,
            CommitAction action) throws IOException {
        if (action == null || !allows(expectedGeneration)) return false;
        return action.commit();
    }

    boolean allowed() {
        return allowed;
    }

    @FunctionalInterface
    interface CommitAction {
        boolean commit() throws IOException;
    }

    enum Transition {
        UNCHANGED("unchanged"),
        BLOCKED("blocked"),
        ALLOWED("allowed");

        private final String label;

        Transition(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        boolean changed() {
            return this != UNCHANGED;
        }
    }
}
