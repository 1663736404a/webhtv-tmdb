package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoNetworkProtectionPolicyTest {

    @Test
    public void disabledModePreservesHardwareOutputPaths() {
        ExoNetworkProtectionPolicy.Decision decision = ExoNetworkProtectionPolicy.resolve(ExoNetworkProtectionPolicy.MODE_OFF);

        assertFalse(decision.enabled());
        assertEquals(1.0f, decision.minimumSpeed(), 0.0001f);
    }

    @Test
    public void explicitSingleRateRescueUsesStrictLightFloor() {
        ExoNetworkProtectionPolicy.Decision decision = ExoNetworkProtectionPolicy.resolve(ExoNetworkProtectionPolicy.MODE_SINGLE_RATE_RESCUE);

        assertTrue(decision.enabled());
        assertEquals(0.97f, decision.minimumSpeed(), 0.0001f);
        assertEquals(0.97f, ExoNetworkProtectionPolicy.PREFERRED_MIN_SPEED, 0.0001f);
        assertEquals(ExoNetworkProtectionPolicy.MODE_OFF, ExoNetworkProtectionPolicy.defaultMode());
    }

    @Test
    public void unknownAndLegacyModeValuesFailClosed() {
        assertEquals(ExoNetworkProtectionPolicy.MODE_OFF, ExoNetworkProtectionPolicy.resolve(-1).mode());
        assertEquals(ExoNetworkProtectionPolicy.MODE_OFF, ExoNetworkProtectionPolicy.resolve(99).mode());
    }
}
