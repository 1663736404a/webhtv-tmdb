package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoDv5GpuRendererTest {

    @Test
    public void rendererFactoryRequiresExplicitOptInAndCompleteProbe() {
        ExoDv5Native.Probe available = new ExoDv5Native.Probe(
                true, ExoDv5Native.REQUIRED_CAPABILITIES, "available");
        ExoDv5Native.Probe unavailable = new ExoDv5Native.Probe(
                false, ExoDv5Native.CAPABILITY_IMAGE_READER, "missing-capability");

        assertFalse(ExoDv5GpuRendererFactory.shouldCreate(false, available));
        assertFalse(ExoDv5GpuRendererFactory.shouldCreate(true, unavailable));
        assertFalse(ExoDv5GpuRendererFactory.shouldCreate(true, null));
        assertTrue(ExoDv5GpuRendererFactory.shouldCreate(true, available));
    }

    @Test
    public void frameActionWaitsSchedulesAndDropsWithinBounds() {
        assertEquals(
                ExoDv5VideoSink.FrameAction.WAIT,
                ExoDv5VideoSink.frameAction(false, false, 0));
        assertEquals(
                ExoDv5VideoSink.FrameAction.RENDER,
                ExoDv5VideoSink.frameAction(false, true, 0));
        assertEquals(
                ExoDv5VideoSink.FrameAction.WAIT,
                ExoDv5VideoSink.frameAction(true, false, 50_001));
        assertEquals(
                ExoDv5VideoSink.FrameAction.RENDER,
                ExoDv5VideoSink.frameAction(true, false, 50_000));
        assertEquals(
                ExoDv5VideoSink.FrameAction.RENDER,
                ExoDv5VideoSink.frameAction(true, false, -30_000));
        assertEquals(
                ExoDv5VideoSink.FrameAction.DROP,
                ExoDv5VideoSink.frameAction(true, false, -30_001));
    }

    @Test
    public void imageTimestampUsesPresentationTimeAndSaturates() {
        assertEquals(1_234_000L,
                ExoDv5VideoSink.imageTimestampNsFor(1_234));
        assertEquals(-1_234_000L,
                ExoDv5VideoSink.imageTimestampNsFor(-1_234));
        assertEquals(Long.MAX_VALUE,
                ExoDv5VideoSink.imageTimestampNsFor(Long.MAX_VALUE));
        assertEquals(Long.MIN_VALUE,
                ExoDv5VideoSink.imageTimestampNsFor(Long.MIN_VALUE));
    }

    @Test
    public void probeCapabilityBitsAreIndependentlyVisible() {
        ExoDv5Native.Probe probe = new ExoDv5Native.Probe(
                false,
                ExoDv5Native.CAPABILITY_IMAGE_READER
                        | ExoDv5Native.CAPABILITY_AHB_IMPORT,
                "missing-capability");

        assertTrue(probe.has(ExoDv5Native.CAPABILITY_IMAGE_READER));
        assertTrue(probe.has(ExoDv5Native.CAPABILITY_AHB_IMPORT));
        assertFalse(probe.has(ExoDv5Native.CAPABILITY_YCBCR_CONVERSION));
    }
}
