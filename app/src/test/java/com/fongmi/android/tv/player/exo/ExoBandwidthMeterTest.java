package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExoBandwidthMeterTest {

    @Test
    public void unknownNetworkUsesMedia3DefaultInitialEstimate() {
        DefaultBandwidthMeter meter = ExoUtil.buildEnhancedBandwidthMeter(null);

        assertEquals(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATE, meter.getBitrateEstimate());
    }

    @Test
    public void knownNetworksUseMedia3CountryAndNetworkDefaults() throws Exception {
        DefaultBandwidthMeter meter = ExoUtil.buildEnhancedBandwidthMeter(null);

        long wifiEstimate = initialEstimate(meter, C.NETWORK_TYPE_WIFI);
        assertTrue(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_WIFI.contains(wifiEstimate));
        assertEquals(wifiEstimate, initialEstimate(meter, C.NETWORK_TYPE_ETHERNET));
        assertTrue(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_2G.contains(initialEstimate(meter, C.NETWORK_TYPE_2G)));
        assertTrue(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_3G.contains(initialEstimate(meter, C.NETWORK_TYPE_3G)));
        assertTrue(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_4G.contains(initialEstimate(meter, C.NETWORK_TYPE_4G)));
        assertTrue(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_NSA.contains(initialEstimate(meter, C.NETWORK_TYPE_5G_NSA)));
        assertTrue(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_SA.contains(initialEstimate(meter, C.NETWORK_TYPE_5G_SA)));
        assertEquals(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATE, initialEstimate(meter, C.NETWORK_TYPE_OFFLINE));
        assertEquals(DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATE, initialEstimate(meter, C.NETWORK_TYPE_OTHER));
    }

    private long initialEstimate(DefaultBandwidthMeter meter, int networkType) throws Exception {
        Method method = DefaultBandwidthMeter.class.getDeclaredMethod("getInitialBitrateEstimateForNetworkType", int.class);
        method.setAccessible(true);
        return (long) method.invoke(meter, networkType);
    }
}
