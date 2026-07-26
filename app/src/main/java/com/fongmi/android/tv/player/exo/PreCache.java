package com.fongmi.android.tv.player.exo;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.source.preload.PreCacheHelper;

import com.fongmi.android.tv.player.PlaybackRoute;
import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackTelemetry;
import com.fongmi.android.tv.player.PlaybackTelemetryCoordinator;
import com.fongmi.android.tv.player.PlaybackTrace;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.PlayerSetting;

import java.io.IOException;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PreCache implements Player.Listener {

    private static final long TICK_MS = 5000;
    private static final long MIN_STEP_MS = 5000;
    private static final long MAX_STEP_MS = 30000;
    private static final long EXTERNAL_LOOPBACK_STEP_MS = 10000;
    private static final long BUFFER_GAP_MS = 1250;
    private static final int STEP_DIV = 4;

    private final PreloadLifecycleTracker lifecycle = new PreloadLifecycleTracker();
    private final PreCacheHelper.Listener preCacheListener = new PreCacheHelper.Listener() {
        @Override
        public void onPrepared(MediaItem originalMediaItem, MediaItem preparedMediaItem) {
            long sessionId = lifecycle.sessionId();
            taskPreparedDurationMs = taskStartRealtimeMs == C.TIME_UNSET ? C.TIME_UNSET : Math.max(0, SystemClock.elapsedRealtime() - taskStartRealtimeMs);
            if (sessionId > 0) PlaybackTrace.log("exo-preload", playbackTraceId, "event=helper-prepared session=%d generation=%d prepareMs=%d cacheBytesAdded=%d", sessionId, generation, taskPreparedDurationMs, taskCacheDelta());
        }

        @Override
        public void onPreCacheCompleted(MediaItem mediaItem) {
            finishTask(PreloadLifecycleTracker.TaskEvent.Outcome.COMPLETED, "completed", null);
        }

        @Override
        public void onPrepareError(MediaItem mediaItem, IOException exception) {
            handleTaskError(PreloadLifecycleTracker.TaskEvent.Outcome.PREPARE_ERROR, "prepare-error", exception);
        }

        @Override
        public void onDownloadError(MediaItem mediaItem, IOException exception) {
            handleTaskError(PreloadLifecycleTracker.TaskEvent.Outcome.DOWNLOAD_ERROR, "download-error", exception);
        }
    };
    private ThreadPoolExecutor executor;
    private PreCacheHelper helper;
    private Handler handler;
    private HandlerThread worker;
    private Player player;
    private PlaybackRoute route;
    private PlaybackRoute.Resolution routeResolution = PlaybackRoute.resolve(null);
    private volatile String playbackTraceId = PlaybackTrace.NONE;
    private Runnable scheduledTask;
    private int threads;
    private volatile long generation;
    private long lastStartMs;
    private long seekStartMs;
    private long taskStartRealtimeMs = C.TIME_UNSET;
    private long taskPreparedDurationMs = C.TIME_UNSET;
    private long taskCacheBytesBefore;
    private boolean playable;
    private boolean externalPreloadCircuitOpen;
    private boolean diskPreloadCircuitOpen;
    private BufferGate bufferGate;
    private AutoPreloadPolicy autoPolicy;

    public void start(Player player, MediaItem mediaItem, String playbackTraceId, PlaybackRoute.Resolution routeResolution) {
        stop("replace-media");
        this.playbackTraceId = PlaybackTrace.normalize(playbackTraceId);
        PriorityTaskDataSource.resetDiagnostics();
        if (!PreloadSetting.isPreload(PlayerSetting.EXO) || !canPreCache(mediaItem)) return;
        this.player = player;
        this.handler = new Handler(player.getApplicationLooper());
        this.routeResolution = routeResolution == null ? PlaybackRoute.resolve(mediaItem.localConfiguration.uri.toString()) : routeResolution;
        this.route = this.routeResolution.route();
        this.autoPolicy = PlaybackPerformanceSetting.isAuto(PlayerSetting.EXO) ? new AutoPreloadPolicy() : null;
        this.helper = createHelper(mediaItem);
        clearSeek();
        lastStartMs = C.TIME_UNSET;
        playable = false;
        externalPreloadCircuitOpen = false;
        diskPreloadCircuitOpen = false;
        bufferGate = BufferGate.FIRST_FRAME;
        this.player.addListener(this);
        PlaybackCacheMetrics.Snapshot cacheMetrics = PlaybackCacheMetrics.snapshot();
        logSession(lifecycle.beginSession(), "generation=%d %s configuredThreads=%d effectiveThreads=%d durationTargetMs=%d cacheCapacityBytes=%d cachedBytesRead=%d cacheSizeBytes=%d", generation, this.routeResolution.logSummary(), PreloadSetting.getPreloadThreads(PlayerSetting.EXO), threads, PreloadSetting.getPreloadDurationMs(PlayerSetting.EXO), MediaSourceFactory.getCacheCapacityBytes(), cacheMetrics.cachedBytesRead(), cacheMetrics.cacheSizeBytes());
        transition(PreloadLifecycleTracker.State.WAIT_FIRST_FRAME, "session-start", "generation=%d position=%d buffered=%d loading=%s", generation, player.getCurrentPosition(), player.getTotalBufferedDuration(), player.isLoading());
        check();
    }

    public void stop() {
        stop("player-stop");
    }

    public void stop(String reason) {
        boolean active = helper != null || player != null;
        PriorityTaskDataSource.DiagnosticSnapshot priority = active ? PriorityTaskDataSource.getDiagnosticSnapshot() : null;
        long stoppedGeneration = generation;
        stopCurrentTask(reason);
        if (active) {
            PlaybackCacheMetrics.Snapshot cacheMetrics = PlaybackCacheMetrics.snapshot();
            logSession(lifecycle.endSession(reason), "generation=%d nextGeneration=%d waitCount=%d waitTotalMs=%d cachedBytesRead=%d cacheSizeBytes=%d", stoppedGeneration, generation, priority.waitCount(), priority.waitTotalMs(), cacheMetrics.cachedBytesRead(), cacheMetrics.cacheSizeBytes());
        }
        if (player != null) player.removeListener(this);
        if (helper != null) helper.release(false);
        handler = null;
        helper = null;
        player = null;
        route = null;
        routeResolution = PlaybackRoute.resolve(null);
        playbackTraceId = PlaybackTrace.NONE;
        autoPolicy = null;
        clearSeek();
        lastStartMs = C.TIME_UNSET;
        playable = false;
        externalPreloadCircuitOpen = false;
        diskPreloadCircuitOpen = false;
        bufferGate = BufferGate.FIRST_FRAME;
    }

    public void release() {
        release(null);
    }

    public void release(Runnable completion) {
        stop("release");
        ThreadPoolExecutor retiringExecutor = executor;
        HandlerThread retiringWorker = worker;
        executor = null;
        worker = null;
        threads = 0;
        if (retiringWorker == null) {
            shutdownExecutor(retiringExecutor);
            completeRelease(completion);
            return;
        }
        // PreCacheHelper.release() posts cancellation to this same looper.
        // Queue resource teardown behind it so SegmentDownloader cannot submit
        // work to an executor which has already entered SHUTTING_DOWN.
        new Handler(retiringWorker.getLooper()).post(() -> {
            shutdownExecutor(retiringExecutor);
            retiringWorker.quitSafely();
            completeRelease(completion);
        });
    }

    private void completeRelease(Runnable completion) {
        if (completion == null) return;
        new Handler(Looper.getMainLooper()).post(completion);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_BUFFERING) {
            if (autoPolicy != null) autoPolicy.disrupt(SystemClock.elapsedRealtime());
            if (playable) bufferGate = BufferGate.RECOVERY;
            transition(PreloadLifecycleTracker.State.CANCELLED_BUFFERING, "buffering", "generation=%d position=%d buffered=%d loading=%s", generation, player.getCurrentPosition(), player.getTotalBufferedDuration(), player.isLoading());
            stopCurrentTask("buffering");
        } else if (state == Player.STATE_READY && playable) {
            check();
        } else if (isStopped(state)) {
            cancel();
        }
    }

    @Override
    public void onRenderedFirstFrame() {
        markPlayable();
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (!isPlaying || playable || player == null) return;
        if (!player.getCurrentTracks().containsType(C.TRACK_TYPE_VIDEO) && player.getCurrentTracks().containsType(C.TRACK_TYPE_AUDIO)) {
            markPlayable();
        }
    }

    @Override
    public void onIsLoadingChanged(boolean isLoading) {
        if (playable && bufferGate != BufferGate.OPEN) check();
    }

    @Override
    public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
        if (!isSeek(reason) || helper == null) return;
        transition(PreloadLifecycleTracker.State.CANCELLED_SEEK, "seek", "generation=%d oldPosition=%d newPosition=%d", generation, oldPosition.positionMs, newPosition.positionMs);
        if (autoPolicy != null) autoPolicy.disrupt(SystemClock.elapsedRealtime());
        stopCurrentTask("seek");
        markSeek(newPosition.positionMs);
        if (playable) bufferGate = BufferGate.RECOVERY;
        check();
    }

    private void check() {
        check(generation);
    }

    private void check(long expectedGeneration) {
        if (expectedGeneration != generation) {
            PlaybackTrace.log("exo-preload", playbackTraceId, "event=stale-skip session=%d expectedGeneration=%d currentGeneration=%d", lifecycle.sessionId(), expectedGeneration, generation);
            return;
        }
        cancel();
        if (update()) schedule(generation);
    }

    private boolean update() {
        if (helper == null || player == null) return false;
        if (!PreloadSetting.isPreload(PlayerSetting.EXO)) {
            stop("disabled");
            return false;
        }
        int state = player.getPlaybackState();
        if (isStopped(state)) return false;
        if (state != Player.STATE_READY) return true;
        if (!playable) {
            transition(PreloadLifecycleTracker.State.WAIT_FIRST_FRAME, "first-frame", "generation=%d position=%d buffered=%d loading=%s", generation, player.getCurrentPosition(), player.getTotalBufferedDuration(), player.isLoading());
            return true;
        }
        if (bufferGate != BufferGate.OPEN) {
            SafeBufferStatus status = getSafeBufferStatus();
            if (!status.safe()) {
                PreloadLifecycleTracker.State waitState = status.recovery() ? PreloadLifecycleTracker.State.WAIT_RECOVERY_BUFFER : PreloadLifecycleTracker.State.WAIT_INITIAL_BUFFER;
                transition(waitState, status.recovery() ? "recovery-watermark" : "initial-watermark", "generation=%d recovery=%s requiredMs=%d bufferedMs=%d loading=%s bitrate=%d effectiveCapacityBytes=%d capacityDurationMs=%d", generation, status.recovery(), status.requiredMs(), status.bufferedMs(), status.loading(), status.bitrate(), status.effectiveCapacityBytes(), status.capacityDurationMs());
                return true;
            }
        }
        bufferGate = BufferGate.OPEN;
        if (player.isCurrentMediaItemLive()) {
            transition(PreloadLifecycleTracker.State.SKIPPED, "live", "generation=%d", generation);
            stop("live");
            return false;
        }
        if (diskPreloadCircuitOpen) {
            transition(PreloadLifecycleTracker.State.PAUSED_STORAGE, "disk-preload-circuit-open", "generation=%d position=%d buffered=%d", generation, player.getCurrentPosition(), player.getTotalBufferedDuration());
            return false;
        }
        if (externalPreloadCircuitOpen) {
            transition(PreloadLifecycleTracker.State.PAUSED_AUTO, "external-preload-circuit-open", "generation=%d route=%s position=%d buffered=%d", generation, route, player.getCurrentPosition(), player.getTotalBufferedDuration());
            return true;
        }
        ExoCacheWritePolicy.Decision cacheDecision = MediaSourceFactory.getCacheWriteDecision();
        if (!cacheDecision.writeAllowed()) {
            pauseForStorage(cacheDecision);
            return true;
        }
        AutoPreloadPolicy.Decision autoDecision = getAutoDecision();
        if (autoDecision != null && !autoDecision.enabled()) {
            publishAutoPreloadDecision(autoDecision, PlaybackTelemetry.DecisionOutcome.SUPPRESSED,
                    "auto-" + autoDecision.mode());
            ObservedMediaBitrateEstimator.Estimate media = PlaybackAnalyticsListener.getMediaBitrateEstimate();
            ForwardBufferTrend.Snapshot trend = PlaybackAnalyticsListener.getBufferTrend();
            transition(PreloadLifecycleTracker.State.PAUSED_AUTO, "auto-" + autoDecision.mode(), "generation=%d route=%s mode=%s position=%d buffered=%d bandwidth=%d bitrate=%d bitrateSource=%s bitrateConfidence=%s average=%d averageSource=%s averageConfidence=%s burst=%d burstSource=%s burstConfidence=%s bufferSlope=%d slopeConfidence=%s slopeWindowMs=%d", generation, route, autoDecision.mode(), player.getCurrentPosition(), player.getTotalBufferedDuration(), PlaybackAnalyticsListener.getSnapshot().bandwidthEstimate(), getSelectedBitrate(), media.source().label(), media.confidence().label(), media.averageBitrateBitsPerSecond(), media.averageSource().label(), media.averageConfidence().label(), media.burstBitrateBitsPerSecond(), media.burstSource().label(), media.burstConfidence().label(), trend.slopeMsPerSecond(), trend.confidence().label(), trend.windowMs());
            return true;
        }
        if (autoDecision != null) setEffectiveThreads(autoDecision.threads());
        long startMs = getStart();
        long lengthMs = getLength(startMs, autoDecision == null ? PreloadSetting.getPreloadDurationMs(PlayerSetting.EXO) : autoDecision.durationMs());
        if (lengthMs <= 0) {
            transition(PreloadLifecycleTracker.State.NO_RANGE, "no-range", "generation=%d startMs=%d durationMs=%d", generation, startMs, player.getDuration());
            clearSeek();
            return true;
        }
        if (!shouldPreCache(startMs)) return true;
        long bitrate = getSelectedBitrate();
        long estimatedBytes = ExoPlaybackDiagnostics.estimateBytes(bitrate, lengthMs);
        ObservedMediaBitrateEstimator.Estimate media = PlaybackAnalyticsListener.getMediaBitrateEstimate();
        ForwardBufferTrend.Snapshot trend = PlaybackAnalyticsListener.getBufferTrend();
        PriorityTaskDataSource.DiagnosticSnapshot priority = PriorityTaskDataSource.getDiagnosticSnapshot();
        publishAutoPreloadDecision(autoDecision, PlaybackTelemetry.DecisionOutcome.REQUESTED, "task-start");
        transition(PreloadLifecycleTracker.State.PRELOADING, "task-start", "generation=%d route=%s threads=%d", generation, route, threads);
        for (PreloadLifecycleTracker.TaskEvent event : lifecycle.startTask(generation, startMs, lengthMs)) {
            if (event.type() == PreloadLifecycleTracker.TaskEvent.Type.END) {
                logTaskEnd(event, "next-range", null);
            } else {
                beginTaskMetrics();
                logTask(event, "estimatedBytes=%d bitrate=%d bitrateSource=%s bitrateConfidence=%s average=%d averageSource=%s averageConfidence=%s burst=%d burstSource=%s burstConfidence=%s p50=%d p90=%d position=%d buffered=%d loading=%s bufferSlope=%d slopeConfidence=%s slopeWindowMs=%d waitCount=%d waitTotalMs=%d", estimatedBytes, bitrate, media.source().label(), media.confidence().label(), media.averageBitrateBitsPerSecond(), media.averageSource().label(), media.averageConfidence().label(), media.burstBitrateBitsPerSecond(), media.burstSource().label(), media.burstConfidence().label(), media.p50BitsPerSecond(), media.p90BitsPerSecond(), player.getCurrentPosition(), player.getTotalBufferedDuration(), player.isLoading(), trend.slopeMsPerSecond(), trend.confidence().label(), trend.windowMs(), priority.waitCount(), priority.waitTotalMs());
            }
        }
        try {
            helper.preCache(startMs, lengthMs);
        } catch (RuntimeException | Error e) {
            PreloadLifecycleTracker.TaskEvent event = finishTask(PreloadLifecycleTracker.TaskEvent.Outcome.START_ERROR, "start-error", e);
            if (event != null && ExoCacheWriteErrorClassifier.isDiskWriteFailure(e)) {
                openDiskCircuit("start-error", e);
                return false;
            }
            throw e;
        }
        lastStartMs = startMs;
        clearSeek();
        return true;
    }

    private void schedule(long expectedGeneration) {
        if (handler == null || expectedGeneration != generation) return;
        scheduledTask = () -> check(expectedGeneration);
        handler.postDelayed(scheduledTask, TICK_MS);
    }

    private void cancel() {
        if (handler != null && scheduledTask != null) handler.removeCallbacks(scheduledTask);
        scheduledTask = null;
    }

    private void stopCurrentTask(String reason) {
        logTaskEnd(lifecycle.endTask(PreloadLifecycleTracker.TaskEvent.Outcome.CANCELLED), reason, null);
        generation++;
        cancel();
        if (helper != null) helper.stop();
        lastStartMs = C.TIME_UNSET;
    }

    private SafeBufferStatus getSafeBufferStatus() {
        long durationMs = player.getDuration();
        long positionMs = player.getCurrentPosition();
        long remainingMs = durationMs > 0 && positionMs >= 0 ? Math.max(0, durationMs - positionMs) : C.TIME_UNSET;
        boolean recovery = bufferGate == BufferGate.RECOVERY;
        long bitrate = getSelectedBitrate();
        int effectiveCapacityBytes = ExoUtil.getEffectiveTargetBufferBytes();
        long requiredMs = PreCachePolicy.safeBufferTargetMs(recovery, remainingMs, bitrate, effectiveCapacityBytes);
        long bufferedMs = player.getTotalBufferedDuration();
        boolean loading = player.isLoading();
        boolean safe = PreCachePolicy.hasSafeBuffer(bufferedMs, loading, requiredMs, recovery);
        return new SafeBufferStatus(safe, recovery, requiredMs, bufferedMs, loading, bitrate, effectiveCapacityBytes, ExoPlaybackDiagnostics.capacityDurationMs(effectiveCapacityBytes, bitrate));
    }

    private long getSelectedBitrate() {
        ObservedMediaBitrateEstimator.Estimate estimate = PlaybackAnalyticsListener.getMediaBitrateEstimate();
        if (estimate.reliable()) return estimate.bitrateBitsPerSecond();
        Format video = TrackUtil.selectedFormat(player.getCurrentTracks(), C.TRACK_TYPE_VIDEO);
        Format audio = TrackUtil.selectedFormat(player.getCurrentTracks(), C.TRACK_TYPE_AUDIO);
        return ExoPlaybackDiagnostics.combinedBitrate(video, audio);
    }

    private void markPlayable() {
        if (!playable) {
            playable = true;
            bufferGate = BufferGate.INITIAL;
        }
        check();
    }

    private void openExternalCircuit(String reason, Throwable error) {
        if (route != PlaybackRoute.EXTERNAL_LOOPBACK_PROXY || externalPreloadCircuitOpen) return;
        externalPreloadCircuitOpen = true;
        PlaybackTrace.log("exo-preload", playbackTraceId, "event=circuit-open session=%d generation=%d route=%s reason=%s error=%s action=stop-preload-keep-playback", lifecycle.sessionId(), generation, route, reason, error == null ? "-" : error.getClass().getSimpleName());
        stopCurrentTask("external-preload-circuit-open");
        transition(PreloadLifecycleTracker.State.PAUSED_AUTO, "external-preload-circuit-open", "generation=%d route=%s", generation, route);
    }

    private void handleTaskError(PreloadLifecycleTracker.TaskEvent.Outcome outcome, String reason, Throwable error) {
        if (finishTask(outcome, reason, error) == null) return;
        if (ExoCacheWriteErrorClassifier.isDiskWriteFailure(error)) openDiskCircuit(reason, error);
        else openExternalCircuit(reason, error);
    }

    private void openDiskCircuit(String reason, Throwable error) {
        if (diskPreloadCircuitOpen) return;
        diskPreloadCircuitOpen = true;
        ExoCacheWritePolicy.Decision decision = MediaSourceFactory.getCacheWriteDecision();
        publishStorageDecision(decision, PlaybackTelemetry.DecisionOutcome.FAILED, reason);
        PlaybackTrace.log("exo-preload", playbackTraceId, "event=disk-circuit-open session=%d generation=%d reason=%s error=%s policy=%s action=stop-preload-keep-playback", lifecycle.sessionId(), generation, reason, error == null ? "-" : error.getClass().getSimpleName(), decision.reason().label());
        stopCurrentTask("disk-preload-circuit-open");
        transition(PreloadLifecycleTracker.State.PAUSED_STORAGE, "disk-preload-circuit-open", "generation=%d policy=%s", generation, decision.reason().label());
    }

    private void pauseForStorage(ExoCacheWritePolicy.Decision decision) {
        String reason = "storage-" + decision.reason().label();
        publishStorageDecision(decision, PlaybackTelemetry.DecisionOutcome.SUPPRESSED, reason);
        if (lifecycle.hasActiveTask()) stopCurrentTask(reason);
        transition(PreloadLifecycleTracker.State.PAUSED_STORAGE, reason, "generation=%d actualCapacityBytes=%d safeCapacityBytes=%d cacheSizeBytes=%d availableBytes=%d reserveBytes=%d reclaimBytes=%d", generation, decision.actualCapacityBytes(), decision.effectiveCapacityBytes(), decision.existingCacheBytes(), decision.availableStorageBytes(), decision.reserveBytes(), decision.reclaimBytes());
    }

    private PreCacheHelper createHelper(MediaItem mediaItem) {
        DataSource.Factory upstreamFactory = MediaSourceFactory.createUpstreamDataSourceFactory(ExoUtil.extractHeaders(mediaItem));
        return new PreCacheHelper.Factory(MediaSourceFactory.getCache(), upstreamFactory, ExoUtil.buildRenderersFactory(), getWorker().getLooper())
                .setDownloadExecutor(getExecutor())
                .setListener(preCacheListener)
                .create(mediaItem);
    }

    private boolean canPreCache(MediaItem mediaItem) {
        if (mediaItem == null || mediaItem.localConfiguration == null) return false;
        MediaItem.LocalConfiguration local = mediaItem.localConfiguration;
        String scheme = local.uri.getScheme();
        String url = local.uri.toString();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && !MediaSourceFactory.isConcatenatingUrl(url);
    }

    private long getStart() {
        if (hasSeek()) return Math.max(0, seekStartMs);
        long bufferedPositionMs = player.getBufferedPosition();
        if (bufferedPositionMs < 0) return Math.max(0, player.getCurrentPosition());
        return bufferedPositionMs > Long.MAX_VALUE - BUFFER_GAP_MS ? bufferedPositionMs : bufferedPositionMs + BUFFER_GAP_MS;
    }

    private boolean shouldPreCache(long startMs) {
        if (hasSeek()) return true;
        if (lastStartMs == C.TIME_UNSET) return true;
        return Math.abs(startMs - lastStartMs) >= getStep();
    }

    private boolean isStopped(int state) {
        return state == Player.STATE_ENDED || state == Player.STATE_IDLE;
    }

    private long getLength(long startMs, long durationTargetMs) {
        long durationMs = player.getDuration();
        if (durationMs <= 0) return 0;
        long remainingMs = Math.max(0, durationMs - startMs);
        return PreCachePolicy.preloadLengthMs(durationTargetMs, remainingMs, getSelectedBitrate(), MediaSourceFactory.getCacheCapacityBytes());
    }

    private long getStep() {
        long stepMs = Math.clamp(PreloadSetting.getPreloadDurationMs(PlayerSetting.EXO) / STEP_DIV, MIN_STEP_MS, MAX_STEP_MS);
        return route == PlaybackRoute.EXTERNAL_LOOPBACK_PROXY ? Math.max(stepMs, EXTERNAL_LOOPBACK_STEP_MS) : stepMs;
    }

    private void markSeek(long startMs) {
        seekStartMs = startMs;
    }

    private void clearSeek() {
        seekStartMs = C.TIME_UNSET;
    }

    private boolean hasSeek() {
        return seekStartMs != C.TIME_UNSET;
    }

    private Executor getExecutor() {
        int requested = PreloadSetting.getPreloadThreads(PlayerSetting.EXO);
        int count = route == null ? requested : route.effectivePreloadThreads(requested);
        if (autoPolicy != null) count = route == null ? AutoPreloadPolicy.NORMAL_THREADS : route.effectivePreloadThreads(AutoPreloadPolicy.NORMAL_THREADS);
        if (executor != null) {
            setEffectiveThreads(count);
            return executor;
        }
        retireExecutor();
        threads = count;
        return executor = new ThreadPoolExecutor(count, count, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    private AutoPreloadPolicy.Decision getAutoDecision() {
        if (autoPolicy == null) return null;
        PlaybackAnalyticsListener.Snapshot snapshot = PlaybackAnalyticsListener.getSnapshot();
        return autoPolicy.evaluate(SystemClock.elapsedRealtime(), route, player.getTotalBufferedDuration(), getSelectedBitrate(), snapshot.bandwidthEstimate(), snapshot.rebufferCount(), player.isLoading(), PlaybackAnalyticsListener.getBufferTrend());
    }

    private void publishAutoPreloadDecision(
            AutoPreloadPolicy.Decision decision,
            PlaybackTelemetry.DecisionOutcome outcome,
            String reason) {
        if (player == null) return;
        PlaybackAnalyticsListener.Snapshot snapshot = PlaybackAnalyticsListener.getSnapshot();
        ForwardBufferTrend.Snapshot trend = PlaybackAnalyticsListener.getBufferTrend();
        String mode = decision == null ? "manual" : decision.mode();
        int selectedThreads = decision == null ? threads : decision.threads();
        long selectedDurationMs = decision == null
                ? PreloadSetting.getPreloadDurationMs(PlayerSetting.EXO) : decision.durationMs();
        PlaybackTelemetryCoordinator.process().publishDecision(playbackTraceId,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.PRELOAD,
                        outcome,
                        "preload-idle",
                        mode,
                        outcome == PlaybackTelemetry.DecisionOutcome.REQUESTED ? "task-requested" : "paused",
                        reason,
                        outcome == PlaybackTelemetry.DecisionOutcome.SUPPRESSED ? mode : "none",
                        List.of(
                                route == null ? PlaybackTelemetry.DecisionInput.unknown("route") : PlaybackTelemetry.DecisionInput.text(
                                        "route", route.name().toLowerCase(Locale.US), PlaybackAutoContext.ValueSource.ROUTE_CLASSIFIER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("threads", selectedThreads, PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("duration_ms", selectedDurationMs, PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("buffered_ms", Math.max(0, player.getTotalBufferedDuration()), PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                snapshot.bandwidthEstimate() > 0 ? PlaybackTelemetry.DecisionInput.number("bandwidth_bps", snapshot.bandwidthEstimate(), PlaybackAutoContext.ValueSource.ESTIMATOR, PlaybackAutoContext.Confidence.MEDIUM) : PlaybackTelemetry.DecisionInput.unknown("bandwidth_bps"),
                                getSelectedBitrate() > 0 ? PlaybackTelemetry.DecisionInput.number("media_bitrate_bps", getSelectedBitrate(), PlaybackAutoContext.ValueSource.ESTIMATOR, PlaybackAutoContext.Confidence.MEDIUM) : PlaybackTelemetry.DecisionInput.unknown("media_bitrate_bps"),
                                PlaybackTelemetry.DecisionInput.number("rebuffer_count", snapshot.rebufferCount(), PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("loading", player.isLoading(), PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                trend.known() ? PlaybackTelemetry.DecisionInput.number("buffer_slope_msps", trend.slopeMsPerSecond(), PlaybackAutoContext.ValueSource.ESTIMATOR, PlaybackAutoContext.Confidence.MEDIUM) : PlaybackTelemetry.DecisionInput.unknown("buffer_slope_msps"))),
                SystemClock.elapsedRealtime());
    }

    private void publishStorageDecision(
            ExoCacheWritePolicy.Decision decision,
            PlaybackTelemetry.DecisionOutcome outcome,
            String reason) {
        if (decision == null) return;
        PlaybackTelemetryCoordinator.process().publishDecision(playbackTraceId,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.CACHE,
                        outcome,
                        "cache-write",
                        "preload-write",
                        decision.writeAllowed() ? "allowed" : "blocked",
                        reason,
                        decision.reason().label(),
                        List.of(
                                PlaybackTelemetry.DecisionInput.bool("write_allowed", decision.writeAllowed(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("actual_capacity_bytes", decision.actualCapacityBytes(), PlaybackAutoContext.ValueSource.SYSTEM_API, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("safe_capacity_bytes", decision.effectiveCapacityBytes(), PlaybackAutoContext.ValueSource.SYSTEM_API, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("cache_size_bytes", decision.existingCacheBytes(), PlaybackAutoContext.ValueSource.SYSTEM_API, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("available_bytes", decision.availableStorageBytes(), PlaybackAutoContext.ValueSource.SYSTEM_API, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("reserve_bytes", decision.reserveBytes(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("reclaim_bytes", decision.reclaimBytes(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH))),
                SystemClock.elapsedRealtime());
    }

    private void setEffectiveThreads(int requested) {
        if (executor == null) return;
        int count = route == null ? requested : route.effectivePreloadThreads(requested);
        if (count == threads) return;
        if (count > threads) {
            executor.setMaximumPoolSize(count);
            executor.setCorePoolSize(count);
        } else {
            executor.setCorePoolSize(count);
            executor.setMaximumPoolSize(count);
        }
        threads = count;
        long sessionId = lifecycle.sessionId();
        if (sessionId > 0) PlaybackTrace.log("exo-preload", playbackTraceId, "event=threads session=%d generation=%d threads=%d route=%s", sessionId, generation, threads, route);
    }

    private void retireExecutor() {
        if (executor == null) return;
        ThreadPoolExecutor retiringExecutor = executor;
        executor = null;
        if (worker == null) shutdownExecutor(retiringExecutor);
        else new Handler(worker.getLooper()).post(() -> shutdownExecutor(retiringExecutor));
    }

    private void shutdownExecutor(ThreadPoolExecutor target) {
        if (target == null) return;
        target.shutdownNow();
    }

    private HandlerThread getWorker() {
        if (worker != null) return worker;
        worker = new HandlerThread("CurrentMediaPreCache");
        worker.start();
        return worker;
    }

    private boolean isSeek(int reason) {
        return reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT;
    }

    private void transition(PreloadLifecycleTracker.State state, String reason, String format, Object... args) {
        PreloadLifecycleTracker.StateEvent event = lifecycle.transition(state, reason);
        if (event == null) return;
        PlaybackTrace.log("exo-preload", playbackTraceId, "event=state session=%d from=%s to=%s reason=%s %s", event.sessionId(), event.from().label(), event.to().label(), event.reason(), detail(format, args));
    }

    private void logSession(PreloadLifecycleTracker.SessionEvent event, String format, Object... args) {
        if (event == null) return;
        String type = event.type() == PreloadLifecycleTracker.SessionEvent.Type.START ? "session-start" : "session-end";
        PlaybackTrace.log("exo-preload", playbackTraceId, "event=%s session=%d reason=%s %s", type, event.sessionId(), event.reason(), detail(format, args));
    }

    private void logTask(PreloadLifecycleTracker.TaskEvent event, String format, Object... args) {
        if (event == null) return;
        String type = event.type() == PreloadLifecycleTracker.TaskEvent.Type.START ? "task-start" : "task-end";
        String outcome = event.outcome() == null ? "-" : event.outcome().label();
        PlaybackTrace.log("exo-preload", playbackTraceId, "event=%s session=%d task=%d generation=%d outcome=%s startMs=%d lengthMs=%d %s", type, event.sessionId(), event.taskId(), event.generation(), outcome, event.startMs(), event.lengthMs(), detail(format, args));
    }

    private PreloadLifecycleTracker.TaskEvent finishTask(PreloadLifecycleTracker.TaskEvent.Outcome outcome, String reason, Throwable error) {
        PreloadLifecycleTracker.TaskEvent event = lifecycle.endTask(outcome);
        if (event == null) return null;
        logTaskEnd(event, reason, error);
        PreloadLifecycleTracker.State state = outcome == PreloadLifecycleTracker.TaskEvent.Outcome.COMPLETED ? PreloadLifecycleTracker.State.WAIT_NEXT_RANGE : PreloadLifecycleTracker.State.WAIT_RETRY;
        transition(state, reason, "generation=%d task=%d", event.generation(), event.taskId());
        return event;
    }

    private void beginTaskMetrics() {
        taskStartRealtimeMs = SystemClock.elapsedRealtime();
        taskPreparedDurationMs = C.TIME_UNSET;
        taskCacheBytesBefore = MediaSourceFactory.getCache().getCacheSpace();
    }

    private void logTaskEnd(PreloadLifecycleTracker.TaskEvent event, String reason, Throwable error) {
        if (event == null) return;
        long elapsedMs = taskStartRealtimeMs == C.TIME_UNSET ? C.TIME_UNSET : Math.max(0, SystemClock.elapsedRealtime() - taskStartRealtimeMs);
        long cacheBytesAdded = taskCacheDelta();
        PlaybackCacheMetrics.Snapshot cacheMetrics = PlaybackCacheMetrics.snapshot();
        if (error == null) {
            logTask(event, "reason=%s elapsedMs=%d prepareMs=%d cacheBytesAdded=%d cachedBytesRead=%d", reason, elapsedMs, taskPreparedDurationMs, cacheBytesAdded, cacheMetrics.cachedBytesRead());
        } else {
            logTask(event, "reason=%s error=%s elapsedMs=%d prepareMs=%d cacheBytesAdded=%d cachedBytesRead=%d", reason, error.getClass().getSimpleName(), elapsedMs, taskPreparedDurationMs, cacheBytesAdded, cacheMetrics.cachedBytesRead());
        }
        taskStartRealtimeMs = C.TIME_UNSET;
        taskPreparedDurationMs = C.TIME_UNSET;
        taskCacheBytesBefore = 0;
    }

    private long taskCacheDelta() {
        if (taskStartRealtimeMs == C.TIME_UNSET) return 0;
        return Math.max(0, MediaSourceFactory.getCache().getCacheSpace() - taskCacheBytesBefore);
    }

    private static String detail(String format, Object... args) {
        if (format == null || format.isBlank()) return "";
        try {
            return String.format(Locale.US, format, args);
        } catch (Throwable ignored) {
            return "detail-format-error";
        }
    }

    private record SafeBufferStatus(boolean safe, boolean recovery, long requiredMs, long bufferedMs, boolean loading, long bitrate, int effectiveCapacityBytes, long capacityDurationMs) {
    }

    private enum BufferGate {
        FIRST_FRAME,
        INITIAL,
        RECOVERY,
        OPEN
    }

}
