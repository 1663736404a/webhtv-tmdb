package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoNetworkRescueLimiterTest {

    @Test
    public void oneActivationIsCappedAtSixtySeconds() {
        ExoNetworkRescueLimiter limiter = new ExoNetworkRescueLimiter();

        ExoNetworkRescueLimiter.Decision started = limiter.apply(0, 0.97f);
        ExoNetworkRescueLimiter.Decision active = limiter.apply(59_999, 0.97f);
        ExoNetworkRescueLimiter.Decision expired = limiter.apply(60_000, 0.97f);

        assertEquals(ExoNetworkRescueLimiter.Action.ACTIVATED, started.action());
        assertEquals(ExoNetworkRescueLimiter.Action.ACTIVE, active.action());
        assertEquals(ExoNetworkRescueLimiter.Action.TIME_LIMIT, expired.action());
        assertEquals(1f, expired.targetSpeed(), 0.0001f);
        assertEquals(60_000, expired.totalActiveMs());
        assertEquals(60_000, expired.cooldownRemainingMs());
    }

    @Test
    public void secondActivationRequiresCooldownAndThenExhaustsMediaBudget() {
        ExoNetworkRescueLimiter limiter = new ExoNetworkRescueLimiter();
        limiter.apply(0, 0.97f);
        limiter.apply(60_000, 0.97f);

        assertEquals(ExoNetworkRescueLimiter.Action.COOLDOWN,
                limiter.apply(119_999, 0.97f).action());
        assertEquals(ExoNetworkRescueLimiter.Action.ACTIVATED,
                limiter.apply(120_000, 0.97f).action());
        ExoNetworkRescueLimiter.Decision expired = limiter.apply(180_000, 0.97f);
        ExoNetworkRescueLimiter.Decision exhausted = limiter.apply(240_000, 0.97f);

        assertEquals(ExoNetworkRescueLimiter.Action.TIME_LIMIT, expired.action());
        assertTrue(expired.exhausted());
        assertEquals(ExoNetworkRescueLimiter.Action.EXHAUSTED, exhausted.action());
        assertEquals(2, exhausted.activationCount());
        assertEquals(ExoNetworkRescueLimiter.MAX_TOTAL_ACTIVE_MS,
                exhausted.totalActiveMs());
    }

    @Test
    public void naturalRecoveryAndInterruptRestoreUnitSpeedWithoutRefundingCount() {
        ExoNetworkRescueLimiter limiter = new ExoNetworkRescueLimiter();
        limiter.apply(1_000, 0.98f);
        ExoNetworkRescueLimiter.Decision recovered = limiter.apply(11_000, 1f);

        assertEquals(ExoNetworkRescueLimiter.Action.RECOVERED, recovered.action());
        assertEquals(10_000, recovered.totalActiveMs());
        assertEquals(1, recovered.activationCount());
        assertFalse(recovered.active());

        limiter.apply(71_000, 0.98f);
        ExoNetworkRescueLimiter.Decision interrupted = limiter.interrupt(76_000);
        assertEquals(ExoNetworkRescueLimiter.Action.INTERRUPTED, interrupted.action());
        assertEquals(1f, interrupted.targetSpeed(), 0.0001f);
        assertEquals(2, interrupted.activationCount());
        assertTrue(interrupted.exhausted());
    }

    @Test
    public void resetStartsANewMediaBudgetAndTimeNeverMovesBackward() {
        ExoNetworkRescueLimiter limiter = new ExoNetworkRescueLimiter();
        limiter.apply(10_000, 0.97f);
        ExoNetworkRescueLimiter.Decision monotonic = limiter.apply(5_000, 0.97f);
        assertEquals(0, monotonic.activeElapsedMs());

        limiter.reset();
        ExoNetworkRescueLimiter.Decision restarted = limiter.apply(0, 0.97f);
        assertEquals(ExoNetworkRescueLimiter.Action.ACTIVATED, restarted.action());
        assertEquals(1, restarted.activationCount());
        assertEquals(0, restarted.totalActiveMs());
    }
}
