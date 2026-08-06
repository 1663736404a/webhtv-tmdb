package com.fongmi.android.tv.player;

import com.fongmi.android.tv.setting.PreloadSetting;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreloadPausePolicyTest {

    @Test
    public void activePlaybackAlwaysAllowsPreload() {
        assertTrue(PreloadPausePolicy.evaluate(
                true,
                PreloadSetting.PAUSE_PRELOAD_OFF,
                PlaybackAutoContext.NetworkSnapshot.unknown()).allowed());
    }

    @Test
    public void disabledPolicyStopsPausedPreload() {
        assertFalse(PreloadPausePolicy.evaluate(
                false,
                PreloadSetting.PAUSE_PRELOAD_OFF,
                unmetered()).allowed());
    }

    @Test
    public void unmeteredPolicyRequiresCompleteSafeEvidence() {
        assertTrue(PreloadPausePolicy.evaluate(
                false,
                PreloadSetting.PAUSE_PRELOAD_UNMETERED,
                unmetered()).allowed());
        assertFalse(PreloadPausePolicy.evaluate(
                false,
                PreloadSetting.PAUSE_PRELOAD_UNMETERED,
                snapshot(true, true, true, false, PlaybackAutoContext.DataSaverState.DISABLED)).allowed());
        assertFalse(PreloadPausePolicy.evaluate(
                false,
                PreloadSetting.PAUSE_PRELOAD_UNMETERED,
                snapshot(true, false, false, false, PlaybackAutoContext.DataSaverState.DISABLED)).allowed());
        assertFalse(PreloadPausePolicy.evaluate(
                false,
                PreloadSetting.PAUSE_PRELOAD_UNMETERED,
                snapshot(true, true, false, false, PlaybackAutoContext.DataSaverState.ENABLED)).allowed());
        assertFalse(PreloadPausePolicy.evaluate(
                false,
                PreloadSetting.PAUSE_PRELOAD_UNMETERED,
                PlaybackAutoContext.NetworkSnapshot.unknown()).allowed());
    }

    @Test
    public void alwaysPolicyAllowsPausedPreloadWithoutNetworkEvidence() {
        assertTrue(PreloadPausePolicy.evaluate(
                false,
                PreloadSetting.PAUSE_PRELOAD_ALWAYS,
                PlaybackAutoContext.NetworkSnapshot.unknown()).allowed());
    }

    private static PlaybackAutoContext.NetworkSnapshot unmetered() {
        return snapshot(true, true, false, false, PlaybackAutoContext.DataSaverState.DISABLED);
    }

    private static PlaybackAutoContext.NetworkSnapshot snapshot(
            Boolean available,
            Boolean validated,
            Boolean metered,
            Boolean roaming,
            PlaybackAutoContext.DataSaverState dataSaver) {
        return new PlaybackAutoContext.NetworkSnapshot(
                available,
                validated,
                metered,
                roaming,
                PlaybackAutoContext.NetworkTransport.WIFI,
                dataSaver);
    }
}
