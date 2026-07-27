package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoLoadControlTest {

    @Test
    public void usesAdaptiveVodRebufferThreshold() {
        assertFalse(AutoLoadControl.reachedAdaptiveThreshold(2_999_000, 1f, C.TIME_UNSET, 3_000));
        assertTrue(AutoLoadControl.reachedAdaptiveThreshold(3_000_000, 1f, C.TIME_UNSET, 3_000));
        assertFalse(AutoLoadControl.reachedAdaptiveThreshold(7_999_000, 1f, C.TIME_UNSET, 8_000));
        assertTrue(AutoLoadControl.reachedAdaptiveThreshold(8_000_000, 1f, C.TIME_UNSET, 8_000));
        assertFalse(AutoLoadControl.reachedAdaptiveThreshold(14_999_000, 1f, C.TIME_UNSET, 15_000));
        assertTrue(AutoLoadControl.reachedAdaptiveThreshold(15_000_000, 1f, C.TIME_UNSET, 15_000));
    }

    @Test
    public void playbackSpeedUsesPlayoutDuration() {
        assertFalse(AutoLoadControl.reachedAdaptiveThreshold(3_999_000, 2f, C.TIME_UNSET, 2_000));
        assertTrue(AutoLoadControl.reachedAdaptiveThreshold(4_000_000, 2f, C.TIME_UNSET, 2_000));
    }

    @Test
    public void liveOffsetKeepsMedia3HalfOffsetLimit() {
        assertFalse(AutoLoadControl.reachedAdaptiveThreshold(1_999_000, 1f, 4_000_000, 8_000));
        assertTrue(AutoLoadControl.reachedAdaptiveThreshold(2_000_000, 1f, 4_000_000, 8_000));
    }

    @Test
    public void controlledSingleTrackThresholdNeverExceedsThreeSeconds() {
        assertEquals(0, AutoLoadControl.controlledTimeThresholdMs(-1));
        assertEquals(1_500, AutoLoadControl.controlledTimeThresholdMs(1_500));
        assertEquals(3_000, AutoLoadControl.controlledTimeThresholdMs(3_000));
        assertEquals(3_000, AutoLoadControl.controlledTimeThresholdMs(15_000));
    }

    @Test
    public void controlledStartFallsBackWhenRescueCannotContinue() {
        assertFalse(AutoLoadControl.shouldStartControlledPlayback(
                true, false, false, true, false));
        assertTrue(AutoLoadControl.shouldStartControlledPlayback(
                true, false, false, false, false));
        assertTrue(AutoLoadControl.shouldStartControlledPlayback(
                false, true, false, false, true));
        assertTrue(AutoLoadControl.shouldStartControlledPlayback(
                false, true, true, true, false));
    }
}
