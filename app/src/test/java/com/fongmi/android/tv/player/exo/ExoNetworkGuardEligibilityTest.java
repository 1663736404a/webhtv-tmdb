package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoNetworkGuardEligibilityTest {

    @Test
    public void allowsOptedInSingleRateNaturalPcmNonTunneledExoVodOnly() {
        assertTrue(ExoNetworkGuardEligibility.resolve(request(false, false)).eligible());
    }

    @Test
    public void experimentPermissionDoesNotReplaceFeatureSpecificOptIn() {
        ExoNetworkGuardEligibility.Request request = request(false, false);
        assertFalse(ExoNetworkGuardEligibility.resolve(new ExoNetworkGuardEligibility.Request(
                true, false, request.exo(), request.vod(), request.userUnitSpeed(),
                request.speedCommandAvailable(), request.tunnelingRequested(),
                request.audioPassthroughRequested(), request.resourceMode())).eligible());
    }

    @Test
    public void adaptiveAndUnknownResourcesFailClosed() {
        ExoNetworkGuardEligibility.Request request = request(false, false);
        assertFalse(ExoNetworkGuardEligibility.resolve(withMode(
                request, ExoNetworkResourcePolicy.Mode.ADAPTIVE_VARIANTS)).eligible());
        assertFalse(ExoNetworkGuardEligibility.resolve(withMode(
                request, ExoNetworkResourcePolicy.Mode.UNKNOWN)).eligible());
    }

    @Test
    public void preservesTunnelingAndPassthroughInsteadOfDisablingThem() {
        assertFalse(ExoNetworkGuardEligibility.resolve(request(true, false)).eligible());
        assertFalse(ExoNetworkGuardEligibility.resolve(request(false, true)).eligible());
    }

    @Test
    public void userSpeedAndUnsupportedSpeedCommandRemainUntouched() {
        assertFalse(ExoNetworkGuardEligibility.resolve(new ExoNetworkGuardEligibility.Request(
                true, true, true, true, false, true, false, false,
                ExoNetworkResourcePolicy.Mode.PROGRESSIVE_SINGLE)).eligible());
        assertFalse(ExoNetworkGuardEligibility.resolve(new ExoNetworkGuardEligibility.Request(
                true, true, true, true, true, false, false, false,
                ExoNetworkResourcePolicy.Mode.PROGRESSIVE_SINGLE)).eligible());
    }

    private static ExoNetworkGuardEligibility.Request request(boolean tunneling, boolean passthrough) {
        return new ExoNetworkGuardEligibility.Request(
                true, true, true, true, true, true, tunneling, passthrough,
                ExoNetworkResourcePolicy.Mode.PROGRESSIVE_SINGLE);
    }

    private static ExoNetworkGuardEligibility.Request withMode(
            ExoNetworkGuardEligibility.Request request,
            ExoNetworkResourcePolicy.Mode mode) {
        return new ExoNetworkGuardEligibility.Request(
                request.enabled(), request.userOptIn(), request.exo(), request.vod(),
                request.userUnitSpeed(), request.speedCommandAvailable(),
                request.tunnelingRequested(), request.audioPassthroughRequested(), mode);
    }
}
