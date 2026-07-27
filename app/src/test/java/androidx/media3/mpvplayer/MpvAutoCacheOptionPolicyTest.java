package androidx.media3.mpvplayer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvAutoCacheOptionPolicyTest {

    @Test
    public void performancePriorityUpdatesOnlyOwnedByteLimits() {
        Map<String, String> options = MpvAutoCacheOptionPolicy.resolve(
                true, 48L * 1024 * 1024, 0);

        assertEquals(List.of("demuxer-max-back-bytes", "demuxer-max-bytes"),
                new ArrayList<>(options.keySet()));
        assertEquals("0", options.get("demuxer-max-back-bytes"));
        assertEquals(String.valueOf(48L * 1024 * 1024),
                options.get("demuxer-max-bytes"));
        assertFalse(options.containsKey("cache-secs"));
        assertFalse(options.containsKey("speed"));
        assertFalse(options.containsKey("sub-font"));
        assertFalse(options.containsKey("glsl-shaders"));
    }

    @Test
    public void configPriorityReturnsNoRuntimeOptions() {
        assertTrue(MpvAutoCacheOptionPolicy.resolve(
                false, 48L * 1024 * 1024, 0).isEmpty());
    }

    @Test
    public void rejectsOutOfRangeOrInconsistentLimits() {
        assertTrue(MpvAutoCacheOptionPolicy.resolve(true, 8L * 1024 * 1024, 0).isEmpty());
        assertTrue(MpvAutoCacheOptionPolicy.resolve(true, 256L * 1024 * 1024, 0).isEmpty());
        assertTrue(MpvAutoCacheOptionPolicy.resolve(true, 24L * 1024 * 1024, -1).isEmpty());
        assertTrue(MpvAutoCacheOptionPolicy.resolve(true, 24L * 1024 * 1024,
                32L * 1024 * 1024).isEmpty());
    }
}
