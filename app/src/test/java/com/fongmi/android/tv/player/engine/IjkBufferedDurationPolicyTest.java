package com.fongmi.android.tv.player.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class IjkBufferedDurationPolicyTest {

    @Test
    public void audioVideoUsesShorterPlayableQueue() {
        assertEquals(0, IjkBufferedDurationPolicy.resolve(
                true, true, 4_000, 0));
        assertEquals(2_000, IjkBufferedDurationPolicy.resolve(
                true, true, 4_000, 2_000));
    }

    @Test
    public void singleTrackUsesItsOwnQueue() {
        assertEquals(3_000, IjkBufferedDurationPolicy.resolve(
                true, false, 3_000, 8_000));
        assertEquals(5_000, IjkBufferedDurationPolicy.resolve(
                false, true, 9_000, 5_000));
    }

    @Test
    public void unknownTracksDoNotTrustOneSidedQueue() {
        assertEquals(0, IjkBufferedDurationPolicy.resolve(
                false, false, 3_000, 0));
        assertEquals(2_000, IjkBufferedDurationPolicy.resolve(
                false, false, 3_000, 2_000));
    }
}
