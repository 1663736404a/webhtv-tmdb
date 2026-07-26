package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;

public class AutoTargetLoadControlTest {

    @Test
    public void selectedAudioAndVideoFormatsAreCombined() {
        Format video = new Format.Builder()
                .setAverageBitrate(20_000_000)
                .setPeakBitrate(30_000_000)
                .build();
        Format audio = new Format.Builder()
                .setAverageBitrate(192_000)
                .setPeakBitrate(256_000)
                .build();

        ExoTargetBufferPolicy.MediaDemand demand = AutoTargetLoadControl.resolveMediaDemand(
                new ExoTrackSelection[]{selection(video), selection(audio)},
                ObservedMediaBitrateEstimator.Estimate.unknown());

        assertEquals(20_192_000L, demand.averageBitsPerSecond());
        assertEquals(30_256_000L, demand.burstBitsPerSecond());
        assertEquals(ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK, demand.averageSource());
        assertEquals(PlaybackAutoContext.Confidence.MEDIUM, demand.averageConfidence());
    }

    @Test
    public void reliableEstimatorAverageAndBurstTakePriority() {
        Format selected = new Format.Builder()
                .setAverageBitrate(8_000_000)
                .setPeakBitrate(10_000_000)
                .build();
        ObservedMediaBitrateEstimator.Estimate estimate = estimate(
                20_000_000L,
                ObservedMediaBitrateEstimator.Source.CONTENT_LENGTH,
                ObservedMediaBitrateEstimator.Confidence.HIGH,
                50_000_000L,
                ObservedMediaBitrateEstimator.Source.OBSERVED_LOAD,
                ObservedMediaBitrateEstimator.Confidence.HIGH);

        ExoTargetBufferPolicy.MediaDemand demand = AutoTargetLoadControl.resolveMediaDemand(
                new ExoTrackSelection[]{selection(selected)},
                estimate);

        assertEquals(20_000_000L, demand.averageBitsPerSecond());
        assertEquals(50_000_000L, demand.burstBitsPerSecond());
        assertEquals(ExoTargetBufferPolicy.DemandSource.CONTENT_LENGTH, demand.averageSource());
        assertEquals(ExoTargetBufferPolicy.DemandSource.OBSERVED_LOAD, demand.burstSource());
    }

    @Test
    public void lowConfidenceEstimatorCannotOverrideSelectedTrack() {
        Format selected = new Format.Builder()
                .setAverageBitrate(8_000_000)
                .setPeakBitrate(10_000_000)
                .build();
        ObservedMediaBitrateEstimator.Estimate estimate = estimate(
                200_000_000L,
                ObservedMediaBitrateEstimator.Source.OBSERVED_LOAD,
                ObservedMediaBitrateEstimator.Confidence.LOW,
                400_000_000L,
                ObservedMediaBitrateEstimator.Source.OBSERVED_LOAD,
                ObservedMediaBitrateEstimator.Confidence.LOW);

        ExoTargetBufferPolicy.MediaDemand demand = AutoTargetLoadControl.resolveMediaDemand(
                new ExoTrackSelection[]{selection(selected)},
                estimate);

        assertEquals(8_000_000L, demand.averageBitsPerSecond());
        assertEquals(10_000_000L, demand.burstBitsPerSecond());
        assertEquals(ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK, demand.averageSource());
        assertEquals(ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK, demand.burstSource());
    }

    @Test
    public void unknownSelectionsStayUnknown() {
        ExoTargetBufferPolicy.MediaDemand demand = AutoTargetLoadControl.resolveMediaDemand(
                new ExoTrackSelection[]{null, selection(new Format.Builder().build())},
                ObservedMediaBitrateEstimator.Estimate.unknown());

        assertEquals(0, demand.averageBitsPerSecond());
        assertEquals(0, demand.burstBitsPerSecond());
        assertEquals(ExoTargetBufferPolicy.DemandSource.UNKNOWN, demand.averageSource());
    }

    private static ObservedMediaBitrateEstimator.Estimate estimate(
            long average,
            ObservedMediaBitrateEstimator.Source averageSource,
            ObservedMediaBitrateEstimator.Confidence averageConfidence,
            long burst,
            ObservedMediaBitrateEstimator.Source burstSource,
            ObservedMediaBitrateEstimator.Confidence burstConfidence) {
        return new ObservedMediaBitrateEstimator.Estimate(
                Math.max(average, burst),
                burstSource,
                lower(averageConfidence, burstConfidence),
                average,
                averageSource,
                averageConfidence,
                burst,
                burstSource,
                burstConfidence,
                average,
                burst,
                3,
                30_000,
                30_000,
                0,
                C.TIME_UNSET);
    }

    private static ObservedMediaBitrateEstimator.Confidence lower(
            ObservedMediaBitrateEstimator.Confidence first,
            ObservedMediaBitrateEstimator.Confidence second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    private static ExoTrackSelection selection(Format format) {
        return (ExoTrackSelection) Proxy.newProxyInstance(
                ExoTrackSelection.class.getClassLoader(),
                new Class<?>[]{ExoTrackSelection.class},
                (proxy, method, args) -> {
                    if ("getSelectedFormat".equals(method.getName())) return format;
                    if ("toString".equals(method.getName())) return "selection";
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }
}
