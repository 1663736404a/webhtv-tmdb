package com.fongmi.android.tv.player;

import com.fongmi.android.tv.setting.PreloadSetting;

/** Pure policy for deciding whether background preload may continue while paused. */
public final class PreloadPausePolicy {

    private PreloadPausePolicy() {
    }

    public static Decision evaluate(
            boolean playWhenReady,
            int policy,
            PlaybackAutoContext.NetworkSnapshot network) {
        if (playWhenReady) return new Decision(true, Reason.PLAYING);
        if (policy == PreloadSetting.PAUSE_PRELOAD_ALWAYS) {
            return new Decision(true, Reason.ALWAYS_ALLOWED);
        }
        if (policy == PreloadSetting.PAUSE_PRELOAD_OFF) {
            return new Decision(false, Reason.PAUSED_DISABLED);
        }
        PlaybackAutoContext.NetworkSnapshot snapshot = network == null
                ? PlaybackAutoContext.NetworkSnapshot.unknown() : network;
        if (!Boolean.TRUE.equals(snapshot.available())) {
            return new Decision(false, Reason.NETWORK_UNAVAILABLE);
        }
        if (!Boolean.TRUE.equals(snapshot.validated())) {
            return new Decision(false, Reason.NETWORK_UNVALIDATED);
        }
        if (!Boolean.FALSE.equals(snapshot.metered())) {
            return new Decision(false, Reason.NETWORK_METERED_OR_UNKNOWN);
        }
        if (!Boolean.FALSE.equals(snapshot.roaming())) {
            return new Decision(false, Reason.NETWORK_ROAMING_OR_UNKNOWN);
        }
        if (snapshot.dataSaverState() == PlaybackAutoContext.DataSaverState.ENABLED
                || snapshot.dataSaverState() == PlaybackAutoContext.DataSaverState.UNKNOWN) {
            return new Decision(false, Reason.DATA_SAVER_OR_UNKNOWN);
        }
        return new Decision(true, Reason.UNMETERED_ALLOWED);
    }

    public record Decision(boolean allowed, Reason reason) {

        public Decision {
            reason = reason == null ? Reason.PAUSED_DISABLED : reason;
        }
    }

    public enum Reason {
        PLAYING("playing"),
        ALWAYS_ALLOWED("always"),
        UNMETERED_ALLOWED("unmetered"),
        PAUSED_DISABLED("paused-disabled"),
        NETWORK_UNAVAILABLE("network-unavailable"),
        NETWORK_UNVALIDATED("network-unvalidated"),
        NETWORK_METERED_OR_UNKNOWN("network-metered-or-unknown"),
        NETWORK_ROAMING_OR_UNKNOWN("network-roaming-or-unknown"),
        DATA_SAVER_OR_UNKNOWN("data-saver-or-unknown");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
