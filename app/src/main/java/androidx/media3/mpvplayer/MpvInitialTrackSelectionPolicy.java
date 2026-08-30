package androidx.media3.mpvplayer;

final class MpvInitialTrackSelectionPolicy {

    private MpvInitialTrackSelectionPolicy() {
    }

    static boolean shouldPauseNativePlayback(
            boolean playWhenReady, boolean trackSelectionPending) {
        return !playWhenReady || trackSelectionPending;
    }
}
