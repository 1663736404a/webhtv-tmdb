package com.fongmi.android.tv.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackExperimentPolicyTest {

    @Test
    public void missingConfigurationInitializesStableAndFailsClosed() {
        PlaybackExperimentPolicy.Resolution result =
                PlaybackExperimentPolicy.resolve(null);

        assertEquals(PlaybackExperimentPolicy.Status.UNINITIALIZED,
                result.status());
        assertTrue(result.writeBack());
        assertTrue(result.failClosed());
        assertFalse(result.state().enabled());
        assertTrue(result.state().exoEnabled());
        assertTrue(result.state().mpvEnabled());
        assertTrue(result.state().ijkEnabled());
    }

    @Test
    public void currentStablePolicyKeepsSafetyAndTelemetryEnabled() {
        PlaybackExperimentPolicy.State state =
                PlaybackExperimentPolicy.State.stable();

        assertTrue(state.allows(
                PlaybackExperimentPolicy.Action.DISK_CAPACITY_PROTECTION));
        assertTrue(state.allows(
                PlaybackExperimentPolicy.Action.MEMORY_PRESSURE_SHRINK));
        assertTrue(state.allows(
                PlaybackExperimentPolicy.Action.READ_ONLY_TELEMETRY));
        assertFalse(state.allows(
                PlaybackExperimentPolicy.Action.EXO_NETWORK_SPEED));
        assertFalse(state.allows(
                PlaybackExperimentPolicy.Action.MPV_AUTO_PRELOAD));
        assertFalse(state.allows(
                PlaybackExperimentPolicy.Action.IJK_RUNTIME_KERNEL_FALLBACK));
        assertEquals(PlaybackExperimentPolicy.STABLE_STRATEGY_ID,
                state.strategyId());
    }

    @Test
    public void globalSwitchOverridesAllDomainSwitches() {
        PlaybackExperimentPolicy.State state =
                new PlaybackExperimentPolicy.State(1, false,
                        true, true, true);

        assertFalse(state.allows(
                PlaybackExperimentPolicy.Action.EXO_AUTO_PRELOAD));
        assertFalse(state.allows(
                PlaybackExperimentPolicy.Action.MPV_HLS_RUNTIME_RELOAD));
        assertFalse(state.allows(
                PlaybackExperimentPolicy.Action.IJK_DECODE_REBUILD));
    }

    @Test
    public void domainsAreIsolatedUnderExperimentalStrategy() {
        PlaybackExperimentPolicy.State state =
                new PlaybackExperimentPolicy.State(1, true,
                        true, false, true);

        assertTrue(state.allows(
                PlaybackExperimentPolicy.Action.EXO_AUTO_PRELOAD));
        assertFalse(state.allows(
                PlaybackExperimentPolicy.Action.MPV_CACHE_EXPANSION));
        assertTrue(state.allows(
                PlaybackExperimentPolicy.Action.IJK_BUFFER_RELOAD));
        assertEquals(PlaybackExperimentPolicy.EXPERIMENT_STRATEGY_ID,
                state.strategyId());
    }

    @Test
    public void corruptCurrentConfigurationIsReplacedByStablePolicy() {
        PlaybackExperimentPolicy.Resolution result =
                PlaybackExperimentPolicy.resolve(
                        new PlaybackExperimentPolicy.RawState(
                                1, true, true, "bad", true));

        assertEquals(PlaybackExperimentPolicy.Status.CORRUPT,
                result.status());
        assertTrue(result.writeBack());
        assertTrue(result.failClosed());
        assertFalse(result.state().enabled());
    }

    @Test
    public void corruptSchemaTypeIsReplacedByStablePolicy() {
        PlaybackExperimentPolicy.Resolution result =
                PlaybackExperimentPolicy.resolve(
                        new PlaybackExperimentPolicy.RawState(
                                "one", true, true, true, true));

        assertEquals(PlaybackExperimentPolicy.Status.CORRUPT,
                result.status());
        assertTrue(result.writeBack());
        assertTrue(result.failClosed());
    }

    @Test
    public void fractionalSchemaNumberCannotMasqueradeAsCurrentVersion() {
        PlaybackExperimentPolicy.Resolution result =
                PlaybackExperimentPolicy.resolve(
                        new PlaybackExperimentPolicy.RawState(
                                1.5d, true, true, true, true));

        assertEquals(PlaybackExperimentPolicy.Status.CORRUPT,
                result.status());
        assertTrue(result.writeBack());
        assertTrue(result.failClosed());
        assertFalse(result.state().enabled());
    }

    @Test
    public void futureSchemaFailsClosedWithoutDowngradingStoredData() {
        PlaybackExperimentPolicy.Resolution result =
                PlaybackExperimentPolicy.resolve(
                        new PlaybackExperimentPolicy.RawState(
                                99, true, true, true, true));

        assertEquals(PlaybackExperimentPolicy.Status.FUTURE_SCHEMA,
                result.status());
        assertFalse(result.writeBack());
        assertTrue(result.failClosed());
        assertFalse(result.state().allows(
                PlaybackExperimentPolicy.Action.EXO_VIDEO_CONSTRAINT));
    }

    @Test
    public void schemaZeroMigratesOnlyExperimentState() {
        PlaybackExperimentPolicy.Resolution result =
                PlaybackExperimentPolicy.resolve(
                        new PlaybackExperimentPolicy.RawState(
                                0, true, null, null, null));

        assertEquals(PlaybackExperimentPolicy.Status.MIGRATED,
                result.status());
        assertTrue(result.writeBack());
        assertTrue(result.state().enabled());
        assertTrue(result.state().exoEnabled());
        assertTrue(result.state().mpvEnabled());
        assertTrue(result.state().ijkEnabled());
    }

    @Test
    public void incompleteSchemaZeroFailsClosedInsteadOfGuessingState() {
        PlaybackExperimentPolicy.Resolution result =
                PlaybackExperimentPolicy.resolve(
                        new PlaybackExperimentPolicy.RawState(
                                0, null, null, null, null));

        assertEquals(PlaybackExperimentPolicy.Status.CORRUPT,
                result.status());
        assertTrue(result.writeBack());
        assertTrue(result.failClosed());
        assertFalse(result.state().enabled());
    }

    @Test
    public void registryHasUniqueVersionedActionIds() {
        assertTrue(PlaybackExperimentPolicy.registryIsValid());
    }
}
