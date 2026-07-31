package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExoNetworkResourcePolicyTest {

    private static final long NOW = 1_000L;

    @Test
    public void manifestVariantsOverrideAFilteredSingleTrack() {
        ExoNetworkResourcePolicy.Decision decision = ExoNetworkResourcePolicy.resolve(
                resource(PlaybackAutoContext.Protocol.HLS, 4, NOW), 1, NOW);

        assertEquals(ExoNetworkResourcePolicy.Mode.ADAPTIVE_VARIANTS, decision.mode());
        assertEquals(4, decision.manifestVariantCount());
    }

    @Test
    public void multipleAvailableVideoFormatsAlwaysPreferAbr() {
        ExoNetworkResourcePolicy.Decision decision = ExoNetworkResourcePolicy.resolve(
                resource(PlaybackAutoContext.Protocol.DASH, null, NOW), 2, NOW);

        assertEquals(ExoNetworkResourcePolicy.Mode.ADAPTIVE_VARIANTS, decision.mode());
        assertEquals(ExoNetworkResourcePolicy.Reason.MULTIPLE_VIDEO_FORMATS, decision.reason());
    }

    @Test
    public void oneAvailableSegmentedOrProgressiveFormatIsExplicitSingleRate() {
        ExoNetworkResourcePolicy.Decision hls = ExoNetworkResourcePolicy.resolve(
                resource(PlaybackAutoContext.Protocol.HLS, null, NOW), 1, NOW);
        ExoNetworkResourcePolicy.Decision dash = ExoNetworkResourcePolicy.resolve(
                resource(PlaybackAutoContext.Protocol.DASH, null, NOW), 1, NOW);
        ExoNetworkResourcePolicy.Decision progressive = ExoNetworkResourcePolicy.resolve(
                resource(PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP, null, NOW), 1, NOW);

        assertEquals(ExoNetworkResourcePolicy.Mode.SEGMENTED_SINGLE, hls.mode());
        assertEquals(ExoNetworkResourcePolicy.Mode.SEGMENTED_SINGLE, dash.mode());
        assertEquals(ExoNetworkResourcePolicy.Mode.PROGRESSIVE_SINGLE, progressive.mode());
        assertTrue(hls.mode().singleRate());
        assertTrue(progressive.mode().singleRate());
    }

    @Test
    public void missingTracksUnknownProtocolAndExpiredFactsFailClosed() {
        assertEquals(ExoNetworkResourcePolicy.Mode.UNKNOWN,
                ExoNetworkResourcePolicy.resolve(
                        resource(PlaybackAutoContext.Protocol.HLS, null, NOW), 0, NOW).mode());
        assertEquals(ExoNetworkResourcePolicy.Mode.UNKNOWN,
                ExoNetworkResourcePolicy.resolve(
                        resource(PlaybackAutoContext.Protocol.UNKNOWN, null, NOW), 1, NOW).mode());
        assertEquals(ExoNetworkResourcePolicy.Mode.UNKNOWN,
                ExoNetworkResourcePolicy.resolve(
                        resource(PlaybackAutoContext.Protocol.HLS, null, NOW), 1, NOW + 60_001).mode());
    }

    private static PlaybackAutoContext.ResourceFacts resource(
            PlaybackAutoContext.Protocol protocol,
            Integer variants,
            long sampledAt) {
        PlaybackAutoContext.Fact<PlaybackAutoContext.ManifestFacts> manifest =
                variants == null
                        ? PlaybackAutoContext.Fact.unknown(
                        PlaybackAutoContext.ManifestFacts.unknown())
                        : PlaybackAutoContext.Fact.withTtl(
                        new PlaybackAutoContext.ManifestFacts(
                                PlaybackAutoContext.ManifestKind.HLS_MASTER,
                                null, null, null, null, variants, false, false),
                        PlaybackAutoContext.ValueSource.MANIFEST,
                        PlaybackAutoContext.Confidence.HIGH,
                        sampledAt,
                        60_000);
        return new PlaybackAutoContext.ResourceFacts(
                PlaybackAutoContext.Fact.withTtl(
                        protocol,
                        PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST,
                        PlaybackAutoContext.Confidence.HIGH,
                        sampledAt,
                        60_000),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.StreamKind.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.RangeSupport.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.TransferUnit.UNKNOWN),
                manifest);
    }
}
