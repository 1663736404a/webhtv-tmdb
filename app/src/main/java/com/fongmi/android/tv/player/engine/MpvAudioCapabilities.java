package com.fongmi.android.tv.player.engine;

import android.content.Context;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioCapabilities;

import com.github.catvod.crawler.SpiderDebug;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntPredicate;

@UnstableApi
final class MpvAudioCapabilities {

    private MpvAudioCapabilities() {
    }

    static String getAudioSpdifCodecs(Context context) {
        AudioCapabilities capabilities = AudioCapabilities.getCapabilities(
                context.getApplicationContext(), AudioAttributes.DEFAULT, null);
        String codecs = getAudioSpdifCodecs(capabilities::supportsEncoding);
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("mpv-audio", "media3 passthrough codecs=%s maxChannels=%d capabilities=%s",
                    codecs, capabilities.getMaxChannelCount(), capabilities);
        }
        return codecs;
    }

    static String getAudioSpdifCodecs(IntPredicate supportsEncoding) {
        Set<String> codecs = new LinkedHashSet<>();
        if (supportsEncoding.test(C.ENCODING_AC3)) codecs.add("ac3");
        if (supportsEncoding.test(C.ENCODING_E_AC3)
                || supportsEncoding.test(C.ENCODING_E_AC3_JOC)) codecs.add("eac3");
        boolean dtsHd = supportsEncoding.test(C.ENCODING_DTS_HD)
                || supportsEncoding.test(C.ENCODING_DTS_HD_MA);
        if (supportsEncoding.test(C.ENCODING_DTS) || dtsHd) codecs.add("dts");
        if (dtsHd) codecs.add("dts-hd");
        if (supportsEncoding.test(C.ENCODING_DOLBY_TRUEHD)) codecs.add("truehd");
        return String.join(",", codecs);
    }
}
