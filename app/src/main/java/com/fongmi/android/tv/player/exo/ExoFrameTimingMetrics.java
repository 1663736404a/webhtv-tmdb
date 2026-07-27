package com.fongmi.android.tv.player.exo;

/** Aggregates Media3 frame processing offsets without logging every rendered frame. */
public final class ExoFrameTimingMetrics {

    private long totalProcessingOffsetUs;
    private long frameCount;
    private int lateBatchCount;
    private int codecErrorCount;
    private String lastCodecError = "";
    private long totalReleaseLeadUs;
    private long releaseFrameCount;
    private long lateReleaseFrameCount;
    private long maxLateReleaseUs;
    private long totalReleaseJitterUs;
    private long releaseJitterSampleCount;
    private long totalCallbackGapUs;
    private long callbackGapSampleCount;
    private long maxCallbackGapUs;
    private long previousPresentationTimeUs = Long.MIN_VALUE;
    private long previousReleaseTimeNs = Long.MIN_VALUE;
    private long previousCallbackTimeNs = Long.MIN_VALUE;

    synchronized void reset() {
        totalProcessingOffsetUs = 0;
        frameCount = 0;
        lateBatchCount = 0;
        codecErrorCount = 0;
        lastCodecError = "";
        totalReleaseLeadUs = 0;
        releaseFrameCount = 0;
        lateReleaseFrameCount = 0;
        maxLateReleaseUs = 0;
        totalReleaseJitterUs = 0;
        releaseJitterSampleCount = 0;
        totalCallbackGapUs = 0;
        callbackGapSampleCount = 0;
        maxCallbackGapUs = 0;
        previousPresentationTimeUs = Long.MIN_VALUE;
        previousReleaseTimeNs = Long.MIN_VALUE;
        previousCallbackTimeNs = Long.MIN_VALUE;
    }

    synchronized void observeProcessingOffset(long batchOffsetUs, int batchFrameCount) {
        if (batchFrameCount <= 0) return;
        totalProcessingOffsetUs = saturatedAdd(totalProcessingOffsetUs, batchOffsetUs);
        frameCount = saturatedAdd(frameCount, batchFrameCount);
        if (batchOffsetUs < 0) lateBatchCount++;
    }

    synchronized void observeCodecError(Exception error) {
        codecErrorCount++;
        lastCodecError = error == null ? "unknown" : error.getClass().getSimpleName();
    }

    /** Starts a new continuous playback segment without discarding accumulated diagnostics. */
    synchronized void resetReleaseContinuity() {
        previousPresentationTimeUs = Long.MIN_VALUE;
        previousReleaseTimeNs = Long.MIN_VALUE;
        previousCallbackTimeNs = Long.MIN_VALUE;
    }

    /**
     * Records the renderer's scheduled release time. This is a timing proxy only: Android does
     * not expose HWC composition or MediaCodec queue occupancy to the app.
     */
    synchronized void observeFrameRelease(long presentationTimeUs, long releaseTimeNs, long nowNs) {
        if (releaseTimeNs <= 0 || nowNs <= 0) return;
        long leadUs = (releaseTimeNs - nowNs) / 1_000L;
        totalReleaseLeadUs = saturatedAdd(totalReleaseLeadUs, leadUs);
        releaseFrameCount = saturatedAdd(releaseFrameCount, 1);
        if (leadUs < 0) {
            lateReleaseFrameCount = saturatedAdd(lateReleaseFrameCount, 1);
            maxLateReleaseUs = Math.max(maxLateReleaseUs, -leadUs);
        }
        if (previousReleaseTimeNs != Long.MIN_VALUE) {
            long callbackGapUs = Math.max(0, (nowNs - previousCallbackTimeNs) / 1_000L);
            totalCallbackGapUs = saturatedAdd(totalCallbackGapUs, callbackGapUs);
            callbackGapSampleCount = saturatedAdd(callbackGapSampleCount, 1);
            maxCallbackGapUs = Math.max(maxCallbackGapUs, callbackGapUs);
            if (previousPresentationTimeUs != Long.MIN_VALUE) {
                long releaseDeltaUs = (releaseTimeNs - previousReleaseTimeNs) / 1_000L;
                long presentationDeltaUs = presentationTimeUs - previousPresentationTimeUs;
                long jitterUs = Math.abs(releaseDeltaUs - presentationDeltaUs);
                totalReleaseJitterUs = saturatedAdd(totalReleaseJitterUs, jitterUs);
                releaseJitterSampleCount = saturatedAdd(releaseJitterSampleCount, 1);
            }
        }
        previousPresentationTimeUs = presentationTimeUs;
        previousReleaseTimeNs = releaseTimeNs;
        previousCallbackTimeNs = nowNs;
    }

    synchronized Snapshot snapshot() {
        long averageOffsetUs = frameCount <= 0 ? 0 : totalProcessingOffsetUs / frameCount;
        long averageReleaseLeadUs = releaseFrameCount <= 0 ? 0 : totalReleaseLeadUs / releaseFrameCount;
        long averageReleaseJitterUs = releaseJitterSampleCount <= 0 ? 0 : totalReleaseJitterUs / releaseJitterSampleCount;
        long averageCallbackGapUs = callbackGapSampleCount <= 0 ? 0 : totalCallbackGapUs / callbackGapSampleCount;
        return new Snapshot(averageOffsetUs, frameCount, lateBatchCount, codecErrorCount, lastCodecError,
                releaseFrameCount, averageReleaseLeadUs, lateReleaseFrameCount, maxLateReleaseUs,
                averageReleaseJitterUs, releaseJitterSampleCount, averageCallbackGapUs,
                callbackGapSampleCount, maxCallbackGapUs);
    }

    private static long saturatedAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        if (second < 0 && first < Long.MIN_VALUE - second) return Long.MIN_VALUE;
        return first + second;
    }

    public record Snapshot(long averageOffsetUs, long frameCount, int lateBatchCount, int codecErrorCount,
                           String lastCodecError, long releaseFrameCount, long averageReleaseLeadUs,
                           long lateReleaseFrameCount, long maxLateReleaseUs, long averageReleaseJitterUs,
                           long releaseJitterSampleCount, long averageCallbackGapUs,
                           long callbackGapSampleCount, long maxCallbackGapUs) {
    }
}
