package com.fongmi.android.tv.player.exo;

import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackAutoContextStore;
import com.fongmi.android.tv.player.PlaybackTelemetry;
import com.fongmi.android.tv.player.PlaybackTelemetryCoordinator;

import java.util.Collections;
import java.util.List;

/** DefaultLoadControl extension that calculates automatic target bytes at track-selection edges. */
final class AutoTargetLoadControl extends DefaultLoadControl {

    private final ExoBufferBudget.Budget fallbackBudget;
    private final int configuredTargetBytes;
    private final ExoTargetBufferCoordinator coordinator;

    AutoTargetLoadControl(
            ExoLoadControlPolicy.BufferDurations durations,
            int startBufferMs,
            int rebufferMs,
            int backBufferMs,
            boolean prioritizeTime,
            int configuredTargetBytes,
            ExoBufferBudget.Budget fallbackBudget) {
        this(
                durations,
                startBufferMs,
                rebufferMs,
                backBufferMs,
                prioritizeTime,
                configuredTargetBytes,
                fallbackBudget,
                ExoTargetBufferCoordinator.process());
    }

    AutoTargetLoadControl(
            ExoLoadControlPolicy.BufferDurations durations,
            int startBufferMs,
            int rebufferMs,
            int backBufferMs,
            boolean prioritizeTime,
            int configuredTargetBytes,
            ExoBufferBudget.Budget fallbackBudget,
            ExoTargetBufferCoordinator coordinator) {
        super(
                new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                durations.minBufferMs(),
                durations.minBufferMs(),
                durations.maxBufferMs(),
                durations.maxBufferMs(),
                startBufferMs,
                startBufferMs,
                rebufferMs,
                rebufferMs,
                C.LENGTH_UNSET,
                prioritizeTime,
                prioritizeTime,
                backBufferMs,
                true,
                Collections.singletonMap(PlayerId.PRELOAD.name, DEFAULT_TARGET_BUFFER_BYTES_FOR_PRELOAD));
        this.configuredTargetBytes = Math.max(0, configuredTargetBytes);
        this.fallbackBudget = fallbackBudget;
        this.coordinator = coordinator;
    }

    @Override
    protected int calculateTargetBufferBytes(
            LoadControl.Parameters parameters,
            ExoTrackSelection[] trackSelections) {
        long now = SystemClock.elapsedRealtime();
        PlaybackAutoContext context = PlaybackAutoContextStore.process().snapshot();
        PlaybackAutoContext.SessionToken session = currentExoSession(context);
        PlaybackAutoContext.DeviceFacts device = session.active()
                ? context.device() : PlaybackAutoContext.DeviceFacts.unknown();
        ExoTargetBufferPolicy.Decision previous = coordinator.currentDecision(session);
        ExoTargetBufferPolicy.Decision decision = calculateDecision(
                trackSelections,
                PlaybackAnalyticsListener.getMediaBitrateEstimate(),
                device,
                now);
        boolean published = coordinator.publish(session, decision, now);
        ExoPlaybackDiagnostics.logTargetDecision(decision);
        if (published) publishTelemetry(session, previous, decision, context, now);
        return decision.targetBytes();
    }

    ExoTargetBufferPolicy.Decision calculateDecision(
            ExoTrackSelection[] trackSelections,
            ObservedMediaBitrateEstimator.Estimate estimate,
            PlaybackAutoContext.DeviceFacts deviceFacts,
            long elapsedRealtimeMs) {
        return ExoTargetBufferPolicy.resolve(
                resolveMediaDemand(trackSelections, estimate),
                configuredTargetBytes,
                fallbackBudget,
                deviceFacts,
                elapsedRealtimeMs);
    }

    static ExoTargetBufferPolicy.MediaDemand resolveMediaDemand(
            ExoTrackSelection[] trackSelections,
            ObservedMediaBitrateEstimator.Estimate estimate) {
        ExoTargetBufferPolicy.MediaDemand selected = selectedTrackDemand(trackSelections);
        long average = selected.averageBitsPerSecond();
        ExoTargetBufferPolicy.DemandSource averageSource = selected.averageSource();
        PlaybackAutoContext.Confidence averageConfidence = selected.averageConfidence();
        long burst = selected.burstBitsPerSecond();
        ExoTargetBufferPolicy.DemandSource burstSource = selected.burstSource();
        PlaybackAutoContext.Confidence burstConfidence = selected.burstConfidence();

        if (estimate != null && estimate.averageReliable()) {
            average = estimate.averageBitrateBitsPerSecond();
            averageSource = demandSource(estimate.averageSource());
            averageConfidence = confidence(estimate.averageConfidence());
        }
        if (estimate != null && estimate.burstReliable()) {
            burst = estimate.burstBitrateBitsPerSecond();
            burstSource = demandSource(estimate.burstSource());
            burstConfidence = confidence(estimate.burstConfidence());
        }
        if (average > 0 && (burst <= 0 || burst < average)) {
            burst = average;
            burstSource = averageSource;
            burstConfidence = averageConfidence;
        }
        return new ExoTargetBufferPolicy.MediaDemand(
                average,
                averageSource,
                averageConfidence,
                burst,
                burstSource,
                burstConfidence);
    }

    private static ExoTargetBufferPolicy.MediaDemand selectedTrackDemand(
            ExoTrackSelection[] trackSelections) {
        long average = 0;
        long burst = 0;
        if (trackSelections != null) {
            for (ExoTrackSelection selection : trackSelections) {
                if (selection == null) continue;
                Format format = selection.getSelectedFormat();
                if (format == null) continue;
                long formatAverage = averageBitrate(format);
                long formatBurst = peakBitrate(format, formatAverage);
                average = safeAdd(average, formatAverage);
                burst = safeAdd(burst, formatBurst);
            }
        }
        if (average <= 0 && burst <= 0) return ExoTargetBufferPolicy.MediaDemand.unknown();
        if (average <= 0) average = burst;
        if (burst < average) burst = average;
        return new ExoTargetBufferPolicy.MediaDemand(
                average,
                ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK,
                PlaybackAutoContext.Confidence.MEDIUM,
                burst,
                ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK,
                PlaybackAutoContext.Confidence.MEDIUM);
    }

    private static long averageBitrate(Format format) {
        if (format.averageBitrate > 0) return format.averageBitrate;
        if (format.bitrate > 0) return format.bitrate;
        return Math.max(0, format.peakBitrate);
    }

    private static long peakBitrate(Format format, long averageBitrate) {
        return format.peakBitrate > 0 ? Math.max(format.peakBitrate, averageBitrate) : averageBitrate;
    }

    private static PlaybackAutoContext.SessionToken currentExoSession(PlaybackAutoContext context) {
        if (context == null || !context.active()) return PlaybackAutoContext.SessionToken.none();
        if (!context.session().traceId().equals(PlaybackAnalyticsListener.getPlaybackTraceId())) {
            return PlaybackAutoContext.SessionToken.none();
        }
        if (context.kernel().hasValue() && context.kernel().value() != PlaybackAutoContext.Kernel.EXO) {
            return PlaybackAutoContext.SessionToken.none();
        }
        return context.session();
    }

    private static void publishTelemetry(
            PlaybackAutoContext.SessionToken session,
            ExoTargetBufferPolicy.Decision previous,
            ExoTargetBufferPolicy.Decision decision,
            PlaybackAutoContext context,
            long now) {
        ExoTargetBufferPolicy.MediaDemand media = decision.mediaDemand();
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> memorySnapshot =
                context.device().memorySnapshot();
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> memoryPressure =
                context.device().memoryPressure();
        PlaybackTelemetryCoordinator.process().publishDecision(
                session,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.LOAD_CONTROL,
                        PlaybackTelemetry.DecisionOutcome.APPLIED,
                        previous == null ? "unknown" : Integer.toString(previous.targetBytes()),
                        Integer.toString(decision.targetBytes()),
                        Integer.toString(decision.targetBytes()),
                        decision.limitingFactor().label(),
                        "none",
                        List.of(
                                bitrateInput("average_bps", media.averageBitsPerSecond(), media.averageSource(), media.averageConfidence()),
                                bitrateInput("burst_bps", media.burstBitsPerSecond(), media.burstSource(), media.burstConfidence()),
                                computedInput("average_need_bytes", decision.averageDemandBytes(), media.averageSource(), media.averageConfidence()),
                                computedInput("burst_need_bytes", decision.burstDemandBytes(), media.burstSource(), media.burstConfidence()),
                                PlaybackTelemetry.DecisionInput.number("media_tier_bytes", decision.mediaTierBytes(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("heap_budget_bytes", decision.heapBudgetBytes(), PlaybackAutoContext.ValueSource.SYSTEM_API, PlaybackAutoContext.Confidence.MEDIUM),
                                memoryBudgetInput("java_headroom_budget_bytes", decision.javaHeadroomBudgetBytes(), memorySnapshot),
                                memoryBudgetInput("system_budget_bytes", decision.systemBudgetBytes(), memorySnapshot),
                                deviceBudgetInput(decision, memorySnapshot, memoryPressure),
                                decision.configuredCapBytes() > 0
                                        ? PlaybackTelemetry.DecisionInput.number("configured_cap_bytes", decision.configuredCapBytes(), PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST, PlaybackAutoContext.Confidence.HIGH)
                                        : PlaybackTelemetry.DecisionInput.text("configured_cap_bytes", "auto", PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("guard_bytes", ExoTargetBufferPolicy.GUARD_TARGET_BYTES, PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                pressureInput(decision, memoryPressure))),
                now);
    }

    private static PlaybackTelemetry.DecisionInput bitrateInput(
            String name,
            long value,
            ExoTargetBufferPolicy.DemandSource source,
            PlaybackAutoContext.Confidence confidence) {
        if (value <= 0 || source == ExoTargetBufferPolicy.DemandSource.UNKNOWN
                || confidence == PlaybackAutoContext.Confidence.UNKNOWN) {
            return PlaybackTelemetry.DecisionInput.unknown(name);
        }
        return PlaybackTelemetry.DecisionInput.number(name, value, source.telemetrySource(), confidence);
    }

    private static PlaybackTelemetry.DecisionInput computedInput(
            String name,
            long value,
            ExoTargetBufferPolicy.DemandSource source,
            PlaybackAutoContext.Confidence confidence) {
        return value <= 0 ? PlaybackTelemetry.DecisionInput.unknown(name)
                : PlaybackTelemetry.DecisionInput.number(name, value, source.telemetrySource(), confidence);
    }

    private static PlaybackTelemetry.DecisionInput memoryBudgetInput(
            String name,
            long value,
            PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> fact) {
        if (value < 0 || fact == null || !fact.hasValue()) return PlaybackTelemetry.DecisionInput.unknown(name);
        return PlaybackTelemetry.DecisionInput.number(name, value, fact.source(), fact.confidence());
    }

    private static PlaybackTelemetry.DecisionInput pressureInput(
            ExoTargetBufferPolicy.Decision decision,
            PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> fact) {
        if (!decision.memoryPressureUsable() || fact == null || !fact.hasValue()) {
            return PlaybackTelemetry.DecisionInput.unknown("memory_pressure");
        }
        return PlaybackTelemetry.DecisionInput.text(
                "memory_pressure",
                decision.memoryPressure().label(),
                fact.source(),
                fact.confidence());
    }

    private static PlaybackTelemetry.DecisionInput deviceBudgetInput(
            ExoTargetBufferPolicy.Decision decision,
            PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> snapshot,
            PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressure) {
        if (decision.memorySnapshotUsable() && snapshot != null && snapshot.hasValue()) {
            return PlaybackTelemetry.DecisionInput.number(
                    "device_budget_bytes", decision.deviceBudgetBytes(), snapshot.source(), snapshot.confidence());
        }
        if (decision.memoryPressureUsable() && pressure != null && pressure.hasValue()) {
            return PlaybackTelemetry.DecisionInput.number(
                    "device_budget_bytes", decision.deviceBudgetBytes(), pressure.source(), pressure.confidence());
        }
        return PlaybackTelemetry.DecisionInput.number(
                "device_budget_bytes",
                decision.deviceBudgetBytes(),
                PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.MEDIUM);
    }

    private static ExoTargetBufferPolicy.DemandSource demandSource(
            ObservedMediaBitrateEstimator.Source source) {
        if (source == null) return ExoTargetBufferPolicy.DemandSource.UNKNOWN;
        return switch (source) {
            case CONTENT_LENGTH -> ExoTargetBufferPolicy.DemandSource.CONTENT_LENGTH;
            case FORMAT -> ExoTargetBufferPolicy.DemandSource.FORMAT;
            case OBSERVED_LOAD -> ExoTargetBufferPolicy.DemandSource.OBSERVED_LOAD;
            case BYTE_SLOPE -> ExoTargetBufferPolicy.DemandSource.BYTE_SLOPE;
            case OBSERVED -> ExoTargetBufferPolicy.DemandSource.OBSERVED;
            case HYBRID -> ExoTargetBufferPolicy.DemandSource.HYBRID;
            case UNKNOWN -> ExoTargetBufferPolicy.DemandSource.UNKNOWN;
        };
    }

    private static PlaybackAutoContext.Confidence confidence(
            ObservedMediaBitrateEstimator.Confidence confidence) {
        if (confidence == null) return PlaybackAutoContext.Confidence.UNKNOWN;
        return switch (confidence) {
            case HIGH -> PlaybackAutoContext.Confidence.HIGH;
            case MEDIUM -> PlaybackAutoContext.Confidence.MEDIUM;
            case LOW -> PlaybackAutoContext.Confidence.LOW;
            case UNKNOWN -> PlaybackAutoContext.Confidence.UNKNOWN;
        };
    }

    private static long safeAdd(long first, long second) {
        if (first <= 0) return Math.max(0, second);
        if (second <= 0) return first;
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }
}
