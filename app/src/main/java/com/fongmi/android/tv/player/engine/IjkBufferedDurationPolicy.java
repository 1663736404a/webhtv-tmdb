package com.fongmi.android.tv.player.engine;

/** Conservative playable-duration estimate for IJK's native audio/video queues. */
final class IjkBufferedDurationPolicy {

    private IjkBufferedDurationPolicy() {
    }

    static long resolve(
            boolean hasAudioTrack,
            boolean hasVideoTrack,
            long audioDurationMs,
            long videoDurationMs) {
        long audio = Math.max(0, audioDurationMs);
        long video = Math.max(0, videoDurationMs);
        if (hasAudioTrack && hasVideoTrack) return Math.min(audio, video);
        if (hasAudioTrack) return audio;
        if (hasVideoTrack) return video;
        return audio > 0 && video > 0 ? Math.min(audio, video) : 0;
    }
}
