package com.fongmi.android.tv.player.engine;

import com.fongmi.android.tv.setting.IjkPerformanceSetting;

import java.util.Locale;

final class IjkInputBufferPolicy {

    private static final int MEBIBYTE = 1024 * 1024;

    private IjkInputBufferPolicy() {
    }

    static Decision resolve(String url, int scene, int configuredBufferMb) {
        int normalizedScene = Math.min(Math.max(scene, IjkPerformanceSetting.SCENE_AUTO), IjkPerformanceSetting.SCENE_LIVE_LOW_LATENCY);
        int bufferMb = configuredBufferMb <= 4 ? 4 : configuredBufferMb <= 8 ? 8 : 15;
        return new Decision(isRealtimeUrl(url), normalizedScene, bufferMb, bufferMb * (long) MEBIBYTE, false);
    }

    private static boolean isRealtimeUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.startsWith("rtsp") || lower.startsWith("rtp") || lower.startsWith("udp") || lower.startsWith("rtmp");
    }

    record Decision(boolean realtime, int scene, int bufferMb, long maxBufferBytes, boolean infiniteBuffer) {
    }
}
