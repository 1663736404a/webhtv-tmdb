package androidx.media3.mpvplayer;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvAutoCacheBaselineStateTest {

    @Test
    public void stagesValidatedOptionsUntilNativeContextIsReady() {
        MpvAutoCacheBaselineState state = new MpvAutoCacheBaselineState();

        assertTrue(state.stage(true, 48L * 1024 * 1024, 0));

        Map<String, String> options = state.snapshot();
        assertEquals(String.valueOf(48L * 1024 * 1024),
                options.get("demuxer-max-bytes"));
        assertEquals("0", options.get("demuxer-max-back-bytes"));
    }

    @Test
    public void stagedBaselineSurvivesNativeContextReplacement() {
        MpvAutoCacheBaselineState state = new MpvAutoCacheBaselineState();
        assertTrue(state.stage(true, 64L * 1024 * 1024, 0));

        Map<String, String> firstContext = state.snapshot();
        Map<String, String> replacementContext = state.snapshot();

        assertEquals(firstContext, replacementContext);
        assertFalse(replacementContext.isEmpty());
    }

    @Test
    public void rejectedOrClearedBaselineCannotLeakIntoLaterContext() {
        MpvAutoCacheBaselineState state = new MpvAutoCacheBaselineState();
        assertTrue(state.stage(true, 48L * 1024 * 1024, 0));

        assertFalse(state.stage(false, 48L * 1024 * 1024, 0));
        assertTrue(state.snapshot().isEmpty());

        assertTrue(state.stage(true, 24L * 1024 * 1024, 0));
        state.clear();
        assertTrue(state.snapshot().isEmpty());
    }
}
