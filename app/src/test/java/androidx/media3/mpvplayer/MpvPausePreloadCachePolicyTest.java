package androidx.media3.mpvplayer;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvPausePreloadCachePolicyTest {

    @Test
    public void pausedProgressiveVodExtendsToConfiguredAheadTarget() {
        MpvPausePreloadCachePolicy.Decision decision = resolve(
                30, 300, 60_000, 3_600_000,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD);

        assertTrue(decision.apply());
        assertEquals(300, decision.targetSeconds());
    }

    @Test
    public void wholeMediaUsesRemainingDuration() {
        MpvPausePreloadCachePolicy.Decision decision = resolve(
                30, 0, 600_000, 3_600_000,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD);

        assertTrue(decision.apply());
        assertEquals(3_000, decision.targetSeconds());
    }

    @Test
    public void segmentedAndLiveResourcesKeepTheirNormalCacheTarget() {
        assertFalse(resolve(
                30, 300, 0, 3_600_000,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD).apply());
        assertFalse(resolve(
                30, 300, 0, 3_600_000,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.LIVE).apply());
    }

    @Test
    public void targetNeverShrinksTheNormalCache() {
        MpvPausePreloadCachePolicy.Decision decision = resolve(
                300, 60, 0, 3_600_000,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD);

        assertFalse(decision.apply());
        assertEquals(300, decision.targetSeconds());
    }

    private static MpvPausePreloadCachePolicy.Decision resolve(
            int baselineSeconds,
            int aheadSeconds,
            long positionMs,
            long durationMs,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind) {
        return MpvPausePreloadCachePolicy.resolve(
                new MpvPausePreloadCachePolicy.Request(
                        true, true, true, true, true,
                        protocol, streamKind,
                        baselineSeconds, aheadSeconds,
                        positionMs, durationMs));
    }
}
