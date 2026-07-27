package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;

import com.fongmi.android.tv.setting.ExoPerformanceSetting;

final class AutoLoadControl implements LoadControl {

    private final AutoTargetLoadControl delegate;
    private final int streamingStartBufferMs;

    AutoLoadControl(
            AutoTargetLoadControl delegate,
            ExoLoadControlPolicy.AutomaticConfiguration configuration) {
        this.delegate = delegate;
        this.streamingStartBufferMs = configuration.streamingStartBufferMs();
    }

    @Override
    public void onPrepared(PlayerId playerId) {
        delegate.onPrepared(playerId);
    }

    @Override
    public void onTracksSelected(Parameters parameters, TrackGroupArray trackGroups, ExoTrackSelection[] trackSelections) {
        delegate.onTracksSelected(parameters, trackGroups, trackSelections);
    }

    @Override
    public void onStopped(PlayerId playerId) {
        delegate.onStopped(playerId);
    }

    @Override
    public void onReleased(PlayerId playerId) {
        delegate.onReleased(playerId);
    }

    @Override
    public Allocator getAllocator(PlayerId playerId) {
        return delegate.getAllocator(playerId);
    }

    @Override
    public long getBackBufferDurationUs(PlayerId playerId) {
        return delegate.getBackBufferDurationUs(playerId);
    }

    @Override
    public boolean retainBackBufferFromKeyframe(PlayerId playerId) {
        return delegate.retainBackBufferFromKeyframe(playerId);
    }

    @Override
    public boolean shouldContinueLoading(Parameters parameters) {
        return delegate.shouldContinueLoading(parameters);
    }

    @Override
    public boolean shouldStartPlayback(Parameters parameters) {
        boolean delegateReady = delegate.shouldStartPlayback(parameters);
        ExoLoadControlModePolicy.Decision mode = delegate.currentModeDecision(parameters.playerId);
        if (mode.mode().controlledTimePriority()) {
            int configuredThresholdMs = parameters.rebuffering
                    ? ExoPerformanceSetting.getAutoSessionRebufferMs()
                    : streamingStartBufferMs;
            int controlledThresholdMs = controlledTimeThresholdMs(configuredThresholdMs);
            boolean controlledReady = reachedAdaptiveThreshold(
                    parameters.bufferedDurationUs,
                    parameters.playbackSpeed,
                    parameters.targetLiveOffsetUs,
                    controlledThresholdMs);
            boolean adaptiveReady = parameters.rebuffering
                    && reachedAdaptiveThreshold(
                            parameters.bufferedDurationUs,
                            parameters.playbackSpeed,
                            parameters.targetLiveOffsetUs,
                            ExoPerformanceSetting.getAutoSessionRebufferMs());
            return shouldStartControlledPlayback(
                    delegateReady,
                    parameters.rebuffering,
                    controlledReady,
                    delegate.canContinueControlledRescue(parameters.playerId),
                    adaptiveReady);
        }
        if (!parameters.rebuffering || delegateReady) return delegateReady;
        return reachedAdaptiveThreshold(parameters.bufferedDurationUs, parameters.playbackSpeed, parameters.targetLiveOffsetUs, ExoPerformanceSetting.getAutoSessionRebufferMs());
    }

    @Override
    public boolean shouldContinuePreloading(PlayerId playerId, Timeline timeline, MediaSource.MediaPeriodId mediaPeriodId, long bufferedDurationUs) {
        return delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs);
    }

    static boolean reachedAdaptiveThreshold(long bufferedDurationUs, float playbackSpeed, long targetLiveOffsetUs, int rebufferMs) {
        long requiredUs = rebufferMs * 1_000L;
        if (targetLiveOffsetUs != C.TIME_UNSET) requiredUs = Math.min(requiredUs, targetLiveOffsetUs / 2);
        long playoutBufferedUs = Util.getPlayoutDurationForMediaDuration(bufferedDurationUs, playbackSpeed);
        return playoutBufferedUs >= requiredUs;
    }

    static int controlledTimeThresholdMs(int configuredThresholdMs) {
        return Math.min(
                Math.max(0, configuredThresholdMs),
                ExoLoadControlModePolicy.SINGLE_TRACK_RESCUE_BUFFER_MS);
    }

    static boolean shouldStartControlledPlayback(
            boolean delegateReady,
            boolean rebuffering,
            boolean controlledReady,
            boolean rescueCanContinue,
            boolean adaptiveReady) {
        if (controlledReady || rescueCanContinue) return controlledReady;
        if (!rebuffering || delegateReady) return delegateReady;
        return adaptiveReady;
    }
}
