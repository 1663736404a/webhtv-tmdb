package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ForwardBufferTrendTest {

    @Test
    public void reportsForwardBufferGrowthPerSecond() {
        ForwardBufferTrend trend = new ForwardBufferTrend();
        trend.observe(0, 12_000, true);
        trend.observe(5_000, 17_000, true);

        ForwardBufferTrend.Snapshot snapshot = trend.snapshot();
        assertTrue(snapshot.known());
        assertEquals(1_000, snapshot.slopeMsPerSecond());
        assertEquals(ForwardBufferTrend.Confidence.LOW, snapshot.confidence());
    }

    @Test
    public void stablePlaybackCanExposeSustainedDecline() {
        ForwardBufferTrend trend = new ForwardBufferTrend();
        trend.observe(0, 20_000, true);
        trend.observe(15_000, 11_000, true);

        ForwardBufferTrend.Snapshot snapshot = trend.snapshot();
        assertEquals(-600, snapshot.slopeMsPerSecond());
        assertEquals(ForwardBufferTrend.Confidence.MEDIUM, snapshot.confidence());
    }

    @Test
    public void disruptionClearsTrendConfidence() {
        ForwardBufferTrend trend = new ForwardBufferTrend();
        trend.observe(0, 20_000, true);
        trend.observe(10_000, 15_000, true);
        trend.observe(11_000, 0, false);

        assertFalse(trend.snapshot().known());
    }

    @Test
    public void fastAndSlowEwmaUsePessimisticEstimate() {
        ForwardBufferTrend trend = new ForwardBufferTrend();
        trend.observe(0, 20_000, true);
        trend.observe(10_000, 19_000, true);
        trend.observe(20_000, 19_000, true);

        ForwardBufferTrend.Snapshot snapshot = trend.snapshot();
        assertTrue(snapshot.fastSlopeMsPerSecond() > snapshot.slowSlopeMsPerSecond());
        assertEquals(snapshot.slowSlopeMsPerSecond(), snapshot.slopeMsPerSecond());
    }

    @Test
    public void snapshotKeepsLastStableBufferForTimeToEmpty() {
        ForwardBufferTrend trend = new ForwardBufferTrend();
        trend.observe(0, 20_000, true);
        trend.observe(10_000, 15_000, true);

        ForwardBufferTrend.Snapshot snapshot = trend.snapshot();

        assertEquals(15_000, snapshot.lastBufferedMs());
        assertEquals(10_000, snapshot.sampledAtElapsedMs());
        assertEquals(30_000, snapshot.timeToEmptyMs());
    }
}
