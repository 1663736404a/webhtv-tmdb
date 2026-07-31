package com.fongmi.android.tv.player.exo;

/** Per-media duration, count and cooldown budget for single-rate speed rescue. */
public final class ExoNetworkRescueLimiter {

    public static final int MAX_ACTIVATIONS_PER_MEDIA = 2;
    public static final long MAX_ACTIVATION_MS = 60_000L;
    public static final long MAX_TOTAL_ACTIVE_MS =
            MAX_ACTIVATIONS_PER_MEDIA * MAX_ACTIVATION_MS;
    public static final long REENTRY_COOLDOWN_MS = 60_000L;

    private static final long UNSET = Long.MIN_VALUE;
    private static final float UNIT_EPSILON = 0.0005f;

    private long lastNowMs = UNSET;
    private long activeSinceMs = UNSET;
    private long cooldownUntilMs = UNSET;
    private long completedActiveMs;
    private int activationCount;

    public void reset() {
        lastNowMs = UNSET;
        activeSinceMs = UNSET;
        cooldownUntilMs = UNSET;
        completedActiveMs = 0;
        activationCount = 0;
    }

    public Decision apply(long nowMs, float requestedSpeed) {
        long now = normalizeNow(nowMs);
        float requested = clamp(requestedSpeed, 0.25f, 1f);
        if (requested >= 1f - UNIT_EPSILON) {
            if (active()) {
                finishActive(now);
                return decision(1f, Action.RECOVERED, now);
            }
            return decision(1f, Action.IDLE, now);
        }

        if (active()) {
            if (activeElapsedMs(now) >= currentActivationBudgetMs()) {
                finishActive(now);
                return decision(1f, Action.TIME_LIMIT, now);
            }
            return decision(requested, Action.ACTIVE, now);
        }
        if (exhausted()) return decision(1f, Action.EXHAUSTED, now);
        if (cooldownRemainingMs(now) > 0) {
            return decision(1f, Action.COOLDOWN, now);
        }

        activationCount++;
        activeSinceMs = now;
        return decision(requested, Action.ACTIVATED, now);
    }

    public Decision interrupt(long nowMs) {
        long now = normalizeNow(nowMs);
        if (!active()) return decision(1f, Action.IDLE, now);
        finishActive(now);
        return decision(1f, Action.INTERRUPTED, now);
    }

    public Snapshot snapshot(long nowMs) {
        long now = normalizeNow(nowMs);
        return new Snapshot(
                active(),
                exhausted(),
                activationCount,
                totalActiveMs(now),
                activeElapsedMs(now),
                activeRemainingMs(now),
                cooldownRemainingMs(now));
    }

    private Decision decision(float targetSpeed, Action action, long now) {
        Snapshot snapshot = snapshot(now);
        return new Decision(
                targetSpeed,
                action,
                snapshot.active(),
                snapshot.exhausted(),
                snapshot.activationCount(),
                snapshot.totalActiveMs(),
                snapshot.activeElapsedMs(),
                snapshot.activeRemainingMs(),
                snapshot.cooldownRemainingMs());
    }

    private void finishActive(long now) {
        long budget = currentActivationBudgetMs();
        long elapsed = Math.min(activeElapsedMs(now), budget);
        completedActiveMs = Math.min(
                MAX_TOTAL_ACTIVE_MS,
                saturatedAdd(completedActiveMs, elapsed));
        activeSinceMs = UNSET;
        cooldownUntilMs = saturatedAdd(now, REENTRY_COOLDOWN_MS);
    }

    private long normalizeNow(long nowMs) {
        long now = Math.max(0, nowMs);
        if (lastNowMs != UNSET) now = Math.max(lastNowMs, now);
        lastNowMs = now;
        return now;
    }

    private boolean active() {
        return activeSinceMs != UNSET;
    }

    private boolean exhausted() {
        return activationCount >= MAX_ACTIVATIONS_PER_MEDIA
                || completedActiveMs >= MAX_TOTAL_ACTIVE_MS;
    }

    private long currentActivationBudgetMs() {
        return Math.max(
                0,
                Math.min(
                        MAX_ACTIVATION_MS,
                        MAX_TOTAL_ACTIVE_MS - completedActiveMs));
    }

    private long activeElapsedMs(long now) {
        return active() ? Math.max(0, now - activeSinceMs) : 0;
    }

    private long activeRemainingMs(long now) {
        return active()
                ? Math.max(0, currentActivationBudgetMs() - activeElapsedMs(now))
                : 0;
    }

    private long totalActiveMs(long now) {
        return Math.min(
                MAX_TOTAL_ACTIVE_MS,
                saturatedAdd(
                        completedActiveMs,
                        Math.min(activeElapsedMs(now), currentActivationBudgetMs())));
    }

    private long cooldownRemainingMs(long now) {
        if (active() || cooldownUntilMs == UNSET) return 0;
        return Math.max(0, cooldownUntilMs - now);
    }

    private static long saturatedAdd(long first, long second) {
        if (second <= 0) return first;
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.min(Math.max(value, minimum), maximum);
    }

    public enum Action {
        IDLE("idle"),
        ACTIVATED("activated"),
        ACTIVE("active"),
        RECOVERED("recovered"),
        INTERRUPTED("interrupted"),
        TIME_LIMIT("time-limit"),
        COOLDOWN("cooldown"),
        EXHAUSTED("exhausted");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Decision(
            float targetSpeed,
            Action action,
            boolean active,
            boolean exhausted,
            int activationCount,
            long totalActiveMs,
            long activeElapsedMs,
            long activeRemainingMs,
            long cooldownRemainingMs) {

        public Decision {
            targetSpeed = clamp(targetSpeed, 0.25f, 1f);
            action = action == null ? Action.IDLE : action;
            activationCount = Math.max(0, activationCount);
            totalActiveMs = Math.max(0, totalActiveMs);
            activeElapsedMs = Math.max(0, activeElapsedMs);
            activeRemainingMs = Math.max(0, activeRemainingMs);
            cooldownRemainingMs = Math.max(0, cooldownRemainingMs);
        }
    }

    public record Snapshot(
            boolean active,
            boolean exhausted,
            int activationCount,
            long totalActiveMs,
            long activeElapsedMs,
            long activeRemainingMs,
            long cooldownRemainingMs) {
    }
}
