package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;

/** Session-local evidence for the Dolby Vision path actually used for playback. */
public final class ExoDolbyVisionPlaybackState {

    private volatile Snapshot snapshot = Snapshot.inactive();
    private volatile boolean hdr10FallbackRequested;

    public void activate(Format sourceFormat, Format outputFormat) {
        snapshot = new Snapshot(true, false, sourceFormat, outputFormat);
    }

    public void activateP81(Format sourceFormat, Format outputFormat) {
        snapshot = new Snapshot(false, true, sourceFormat, outputFormat);
    }

    public void reset() {
        hdr10FallbackRequested = false;
        resetAttempt();
    }

    /** Clears the active renderer/extractor snapshot while keeping a pending retry policy. */
    public void resetAttempt() {
        snapshot = Snapshot.inactive();
    }

    public void requestHdr10Fallback() {
        hdr10FallbackRequested = true;
    }

    public boolean isHdr10FallbackRequested() {
        return hdr10FallbackRequested;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public record Snapshot(
            boolean hdr10FallbackActive,
            boolean p81ConversionActive,
            @Nullable Format sourceFormat,
            @Nullable Format outputFormat) {

        private static Snapshot inactive() {
            return new Snapshot(false, false, null, null);
        }
    }
}
