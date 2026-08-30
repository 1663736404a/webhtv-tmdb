package androidx.media3.mpvplayer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvInitialTrackSelectionPolicyTest {

    @Test
    public void pendingTrackSelectionKeepsAutoplayPausedNatively() {
        assertTrue(MpvInitialTrackSelectionPolicy.shouldPauseNativePlayback(
                true, true));
    }

    @Test
    public void releasingGatePreservesOriginalPlaybackIntent() {
        assertFalse(MpvInitialTrackSelectionPolicy.shouldPauseNativePlayback(
                true, false));
        assertTrue(MpvInitialTrackSelectionPolicy.shouldPauseNativePlayback(
                false, false));
    }
}
