package androidx.media3.mpvplayer;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Pure policy for temporarily extending MPV's in-memory cache while paused. */
final class MpvPausePreloadCachePolicy {

    private static final int MAX_TARGET_SECONDS = 24 * 60 * 60;

    private MpvPausePreloadCachePolicy() {
    }

    static Decision resolve(Request request) {
        Request current = request == null ? Request.inactive() : request;
        if (!current.paused()) return hold(current, Reason.PLAYING);
        if (!current.preloadConfigured()) return hold(current, Reason.PRELOAD_DISABLED);
        if (!current.pauseAllowed()) return hold(current, Reason.PAUSE_POLICY);
        if (!current.performanceOptionsPriority()) return hold(current, Reason.CONFIG_PRIORITY);
        if (!current.cacheEnabled()) return hold(current, Reason.CACHE_DISABLED);
        if (current.protocol() != PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP) {
            return hold(current, Reason.NOT_PROGRESSIVE);
        }
        if (current.streamKind() == PlaybackAutoContext.StreamKind.LIVE
                || current.streamKind() == PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE) {
            return hold(current, Reason.LIVE_STREAM);
        }
        if (current.streamKind() != PlaybackAutoContext.StreamKind.VOD
                && current.durationMs() <= 0) {
            return hold(current, Reason.DURATION_UNKNOWN);
        }
        long remainingMs = current.durationMs() > 0
                ? Math.max(0, current.durationMs() - current.positionMs()) : 0;
        int targetSeconds;
        if (current.aheadSeconds() == 0) {
            if (remainingMs <= 0) return hold(current, Reason.DURATION_UNKNOWN);
            targetSeconds = ceilSeconds(remainingMs);
        } else {
            targetSeconds = current.aheadSeconds();
            if (remainingMs > 0) targetSeconds = Math.min(targetSeconds, ceilSeconds(remainingMs));
        }
        targetSeconds = Math.clamp(targetSeconds, 0, MAX_TARGET_SECONDS);
        if (targetSeconds <= current.baselineSeconds()) return hold(current, Reason.ALREADY_AHEAD);
        return new Decision(true, targetSeconds, Reason.EXTEND_CACHE, current);
    }

    private static Decision hold(Request request, Reason reason) {
        return new Decision(false, request.baselineSeconds(), reason, request);
    }

    private static int ceilSeconds(long milliseconds) {
        if (milliseconds <= 0) return 0;
        long seconds = milliseconds / 1000 + (milliseconds % 1000 == 0 ? 0 : 1);
        return seconds >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }

    record Request(
            boolean paused,
            boolean preloadConfigured,
            boolean pauseAllowed,
            boolean performanceOptionsPriority,
            boolean cacheEnabled,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind,
            int baselineSeconds,
            int aheadSeconds,
            long positionMs,
            long durationMs) {

        Request {
            protocol = protocol == null ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            streamKind = streamKind == null
                    ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
            baselineSeconds = Math.max(0, baselineSeconds);
            aheadSeconds = Math.max(0, aheadSeconds);
            positionMs = Math.max(0, positionMs);
            durationMs = Math.max(0, durationMs);
        }

        static Request inactive() {
            return new Request(false, false, false, false, false,
                    PlaybackAutoContext.Protocol.UNKNOWN,
                    PlaybackAutoContext.StreamKind.UNKNOWN,
                    0, 0, 0, 0);
        }
    }

    record Decision(boolean apply, int targetSeconds, Reason reason, Request request) {

        Decision {
            targetSeconds = Math.max(0, targetSeconds);
            reason = reason == null ? Reason.PRELOAD_DISABLED : reason;
            request = request == null ? Request.inactive() : request;
        }
    }

    enum Reason {
        PLAYING,
        PRELOAD_DISABLED,
        PAUSE_POLICY,
        CONFIG_PRIORITY,
        CACHE_DISABLED,
        NOT_PROGRESSIVE,
        LIVE_STREAM,
        DURATION_UNKNOWN,
        ALREADY_AHEAD,
        EXTEND_CACHE
    }
}
