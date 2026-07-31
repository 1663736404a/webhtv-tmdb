package com.fongmi.android.tv.player.exo;

/** Product boundaries for dynamically activated EXO network protection. */
public final class ExoNetworkProtectionPolicy {

    public static final int MODE_OFF = 0;
    public static final int MODE_SINGLE_RATE_RESCUE = 1;
    /** Kept as a source-compatible alias; automatic profiles no longer enable it. */
    public static final int MODE_AUTO = MODE_SINGLE_RATE_RESCUE;
    public static final float PREFERRED_MIN_SPEED = 0.97f;
    public static final float RESCUE_MIN_SPEED = 0.97f;
    /** Kept for the existing controller contract. */
    public static final float AUTO_MIN_SPEED = RESCUE_MIN_SPEED;

    private ExoNetworkProtectionPolicy() {
    }

    public static int defaultMode() {
        return MODE_OFF;
    }

    public static Decision resolve(int mode) {
        int normalized = mode == MODE_SINGLE_RATE_RESCUE
                ? MODE_SINGLE_RATE_RESCUE : MODE_OFF;
        boolean enabled = normalized != MODE_OFF;
        return new Decision(normalized, enabled, enabled ? RESCUE_MIN_SPEED : 1.0f);
    }

    public record Decision(int mode, boolean enabled, float minimumSpeed) {
    }
}
