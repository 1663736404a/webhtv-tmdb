package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.media.MediaCrypto;
import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.util.List;

/** Diagnostic-only MediaCodec renderer targeting {@link ExoDv5VideoSink}. */
final class ExoDv5GpuRenderer extends MediaCodecVideoRenderer {

    private final ExoDv5VideoSink sink;

    ExoDv5GpuRenderer(
            Context context,
            MediaCodecAdapter.Factory codecAdapterFactory,
            MediaCodecSelector mediaCodecSelector,
            long allowedJoiningTimeMs,
            boolean enableDecoderFallback,
            @Nullable Handler eventHandler,
            @Nullable VideoRendererEventListener eventListener,
            ExoFrameSchedulingExperimentPolicy.Decision frameSchedulingDecision,
            ExoDv5VideoSink sink) {
        super(ExoFrameSchedulingRendererSettings.from(frameSchedulingDecision)
                .apply(new Builder(context)
                        .setCodecAdapterFactory(codecAdapterFactory)
                        .setMediaCodecSelector(mediaCodecSelector)
                        .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
                        .setEnableDecoderFallback(enableDecoderFallback)
                        .setEventHandler(eventHandler)
                        .setEventListener(eventListener)
                        .setVideoSink(sink)));
        this.sink = sink;
    }

    @Override
    public String getName() {
        return "MediaCodecVideoRenderer-DV5-AImageReader-Diagnostic";
    }

    @Override
    protected int supportsFormat(MediaCodecSelector selector, Format format)
            throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {
        if (!ExoDv5GpuMappingPolicy.isProfile5(
                format.sampleMimeType, format.codecs)
                || format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return C.FORMAT_UNSUPPORTED_TYPE;
        }
        return super.supportsFormat(selector, asHevc(format));
    }

    @Override
    protected List<MediaCodecInfo> getDecoderInfos(
            MediaCodecSelector selector, Format format, boolean secure)
            throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {
        if (secure || !ExoDv5GpuMappingPolicy.isProfile5(
                format.sampleMimeType, format.codecs)) {
            return List.of();
        }
        return super.getDecoderInfos(selector, asHevc(format), false);
    }

    @Override
    protected MediaCodecAdapter.Configuration getMediaCodecConfiguration(
            MediaCodecInfo info, Format format, MediaCrypto crypto, float rate) {
        return super.getMediaCodecConfiguration(info, asHevc(format), crypto, rate);
    }

    ExoDv5Native.Stats diagnosticStats() {
        return sink.stats();
    }

    static Format asHevc(Format format) {
        return format.buildUpon()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs(null)
                .setInitializationData(
                        DolbyVisionP81ExtractorsFactory.removeDolbyVisionCsd(
                                format.initializationData))
                .build();
    }
}
