package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.github.catvod.crawler.SpiderDebug;
import com.suyashbelekar.exoplayerhdrutils.video.transformers.DoviStrategy;
import com.suyashbelekar.exoplayerhdrutils.video.transformers.Hdr10PlusStrategy;
import com.suyashbelekar.exoplayerhdrutils.video.transformers.HevcFrameTransformer;
import com.suyashbelekar.exoplayerhdrutils.video.transformers.TransformStrategy;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Rewrites unsupported DV7 tracks to DV8.1 before Exo builds its track groups. */
@UnstableApi
final class DolbyVisionP81ExtractorsFactory implements ExtractorsFactory {

    private static final int TRANSFORM_GROWTH_BYTES = 10 * 1024;
    private static final TransformStrategy P81_STRATEGY = new TransformStrategy(
            DoviStrategy.CONVERT_TO_P8,
            DoviStrategy.CONVERT_TO_P8,
            Hdr10PlusStrategy.KEEP);

    private static volatile Boolean converterAvailable;

    private final ExtractorsFactory delegate;

    DolbyVisionP81ExtractorsFactory(ExtractorsFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Extractor[] createExtractors() {
        return wrap(delegate.createExtractors());
    }

    @Override
    public Extractor[] createExtractors(
            Uri uri, Map<String, List<String>> responseHeaders) {
        return wrap(delegate.createExtractors(uri, responseHeaders));
    }

    private Extractor[] wrap(Extractor[] extractors) {
        Extractor[] wrapped = new Extractor[extractors.length];
        for (int i = 0; i < extractors.length; i++) {
            Extractor extractor = extractors[i];
            if (extractor instanceof MatroskaExtractor
                    && PlaybackPerformanceSetting.isDv7P81Enabled()) {
                extractor = new MatroskaExtractor(
                        new DefaultSubtitleParserFactory(),
                        MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
                        true);
            }
            wrapped[i] = new DolbyVisionExtractor(extractor);
        }
        return wrapped;
    }

    static boolean shouldConvert(Format source) {
        if (!PlaybackPerformanceSetting.isDv7P81Enabled()
                || !isProfile7(source)
                || source.cryptoType != C.CRYPTO_TYPE_NONE
                || !isConverterAvailable()) return false;
        Format p81 = asProfile81(source);
        boolean sourceSupported = hasHardwareDecoder(source);
        boolean p81Supported = hasHardwareDecoder(p81);
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("exo-dv", "DV7 P8.1 decision source=%s p81=%s sourceHw=%s p81Hw=%s size=%dx%d",
                    source.codecs, p81.codecs, sourceSupported, p81Supported,
                    source.width, source.height);
        }
        return !sourceSupported && p81Supported;
    }

    static boolean isProfile7(@Nullable Format format) {
        if (format == null
                || !MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)
                || format.codecs == null) return false;
        String codec = firstCodec(format.codecs).toLowerCase(Locale.US);
        return codec.startsWith("dvhe.07.") || codec.startsWith("dvh1.07.");
    }

    static Format asProfile81(Format source) {
        return source.buildUpon().setCodecs(rewriteProfile81(source.codecs)).build();
    }

    static String rewriteProfile81(@Nullable String codecs) {
        if (codecs == null || codecs.isBlank()) return codecs;
        return codecs.replaceFirst("(?i)(dvhe|dvh1)\\.07\\.", "$1.08.");
    }

    private static String firstCodec(String codecs) {
        int comma = codecs.indexOf(',');
        return comma < 0 ? codecs.trim() : codecs.substring(0, comma).trim();
    }

    private static boolean hasHardwareDecoder(Format format) {
        try {
            for (MediaCodecInfo info : MediaCodecSelector.DEFAULT.getDecoderInfos(
                    MimeTypes.VIDEO_DOLBY_VISION, false, false)) {
                if (info.hardwareAccelerated
                        && info.isFormatSupported(App.get(), format)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isConverterAvailable() {
        Boolean known = converterAvailable;
        if (known != null) return known;
        synchronized (DolbyVisionP81ExtractorsFactory.class) {
            known = converterAvailable;
            if (known != null) return known;
            try {
                new HevcFrameTransformer(P81_STRATEGY);
                converterAvailable = true;
            } catch (Throwable error) {
                converterAvailable = false;
                if (SpiderDebug.isEnabled()) {
                    SpiderDebug.log("exo-dv", "DV7 P8.1 converter unavailable error=%s",
                            error.getClass().getSimpleName());
                }
            }
            return converterAvailable;
        }
    }

    private static final class DolbyVisionExtractor implements Extractor {

        private final Extractor delegate;

        DolbyVisionExtractor(Extractor delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean sniff(ExtractorInput input) throws IOException {
            return delegate.sniff(input);
        }

        @Override
        public void init(ExtractorOutput output) {
            delegate.init(new DolbyVisionExtractorOutput(output));
        }

        @Override
        public int read(ExtractorInput input, PositionHolder seekPosition)
                throws IOException {
            return delegate.read(input, seekPosition);
        }

        @Override
        public void seek(long position, long timeUs) {
            delegate.seek(position, timeUs);
        }

        @Override
        public void release() {
            delegate.release();
        }

        @Override
        public Extractor getUnderlyingImplementation() {
            return delegate.getUnderlyingImplementation();
        }
    }

    private static final class DolbyVisionExtractorOutput
            implements ExtractorOutput {

        private final ExtractorOutput delegate;

        DolbyVisionExtractorOutput(ExtractorOutput delegate) {
            this.delegate = delegate;
        }

        @Override
        public TrackOutput track(int id, int type) {
            TrackOutput output = delegate.track(id, type);
            return type == C.TRACK_TYPE_VIDEO
                    ? new DolbyVisionTrackOutput(output) : output;
        }

        @Override
        public void endTracks() {
            delegate.endTracks();
        }

        @Override
        public void seekMap(SeekMap seekMap) {
            delegate.seekMap(seekMap);
        }
    }

    private static final class DolbyVisionTrackOutput implements TrackOutput {

        private final TrackOutput delegate;
        private final ParsableByteArray outputData = new ParsableByteArray();

        private ByteBuffer pending = ByteBuffer.allocateDirect(1024 * 1024);
        private byte[] inputScratch = new byte[16 * 1024];
        private byte[] outputScratch = new byte[1024 * 1024];
        @Nullable private HevcFrameTransformer transformer;
        private boolean converting;

        DolbyVisionTrackOutput(TrackOutput delegate) {
            this.delegate = delegate;
        }

        @Override
        public void durationUs(long durationUs) {
            delegate.durationUs(durationUs);
        }

        @Override
        public void format(Format format) {
            converting = shouldConvert(format);
            transformer = converting
                    ? new HevcFrameTransformer(P81_STRATEGY) : null;
            delegate.format(converting ? asProfile81(format) : format);
        }

        @Override
        public int sampleData(
                DataReader input,
                int length,
                boolean allowEndOfInput,
                int sampleDataPart) throws IOException {
            if (!converting || sampleDataPart != SAMPLE_DATA_PART_MAIN) {
                return delegate.sampleData(
                        input, length, allowEndOfInput, sampleDataPart);
            }
            inputScratch = ensureCapacity(inputScratch, length);
            int read = input.read(inputScratch, 0, length);
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT;
                throw new EOFException();
            }
            if (read > 0) {
                pending = ensureCapacity(pending, pending.position() + read);
                pending.put(inputScratch, 0, read);
            }
            return read;
        }

        @Override
        public void sampleData(
                ParsableByteArray data, int length, int sampleDataPart) {
            if (!converting || sampleDataPart != SAMPLE_DATA_PART_MAIN) {
                delegate.sampleData(data, length, sampleDataPart);
                return;
            }
            pending = ensureCapacity(pending, pending.position() + length);
            pending.put(data.getData(), data.getPosition(), length);
            data.skipBytes(length);
        }

        @Override
        public void sampleMetadata(
                long timeUs,
                int flags,
                int size,
                int offset,
                @Nullable CryptoData cryptoData) {
            if (!converting || pending.position() == 0) {
                delegate.sampleMetadata(
                        timeUs, flags, size, offset, cryptoData);
                return;
            }

            int pendingLength = pending.position();
            int carrySize = Math.max(0, Math.min(offset, pendingLength));
            int sampleLength = pendingLength - carrySize;
            byte[] carry = carrySize == 0 ? null : new byte[carrySize];
            if (carry != null) {
                pending.limit(pendingLength).position(sampleLength);
                pending.get(carry);
            }

            int outputLength = sampleLength;
            pending.limit(sampleLength).position(sampleLength);
            if (converting && transformer != null && sampleLength > 0) {
                pending = ensureCapacity(
                        pending, sampleLength + TRANSFORM_GROWTH_BYTES);
                pending.limit(sampleLength).position(sampleLength);
                try {
                    outputLength = transformer.transformFrame(
                            pending, sampleLength);
                } catch (Throwable error) {
                    throw new IllegalStateException(
                            "DV7 to P8.1 conversion failed", error);
                }
            }

            outputScratch = ensureCapacity(outputScratch, outputLength);
            pending.limit(outputLength).position(0);
            pending.get(outputScratch, 0, outputLength);
            outputLength = stripEnhancementLayerNalus(
                    outputScratch, outputLength);
            outputData.reset(outputScratch, outputLength);
            delegate.sampleData(
                    outputData, outputLength, SAMPLE_DATA_PART_MAIN);
            delegate.sampleMetadata(
                    timeUs, flags, outputLength, 0, cryptoData);

            pending.clear();
            if (carry != null) pending.put(carry);
        }

        private static ByteBuffer ensureCapacity(
                ByteBuffer current, int requiredCapacity) {
            if (current.capacity() >= requiredCapacity) return current;
            int capacity = current.capacity();
            while (capacity < requiredCapacity) capacity *= 2;
            ByteBuffer expanded = ByteBuffer.allocateDirect(capacity);
            current.limit(current.position()).position(0);
            expanded.put(current);
            return expanded;
        }

        private static byte[] ensureCapacity(
                byte[] current, int requiredCapacity) {
            if (current.length >= requiredCapacity) return current;
            int capacity = current.length;
            while (capacity < requiredCapacity) capacity *= 2;
            return new byte[capacity];
        }
    }

    static int stripEnhancementLayerNalus(byte[] data, int length) {
        int firstStart = findStartCode(data, 0, length);
        if (firstStart < 0) return length;
        int writeOffset = 0;
        if (firstStart > 0) {
            System.arraycopy(data, 0, data, 0, firstStart);
            writeOffset = firstStart;
        }
        int start = firstStart;
        while (start >= 0 && start < length) {
            int startCodeLength = data[start + 2] == 1 ? 3 : 4;
            int payload = start + startCodeLength;
            int next = findStartCode(data, payload + 2, length);
            int end = next < 0 ? length : next;
            boolean enhancementLayer = false;
            if (payload + 1 < end) {
                int firstHeader = data[payload] & 0xFF;
                int secondHeader = data[payload + 1] & 0xFF;
                int nalType = (firstHeader & 0x7E) >> 1;
                int layerId = ((firstHeader & 0x01) << 5)
                        | ((secondHeader >> 3) & 0x1F);
                enhancementLayer = nalType == 63 || layerId > 0;
            }
            if (!enhancementLayer) {
                int count = end - start;
                System.arraycopy(data, start, data, writeOffset, count);
                writeOffset += count;
            }
            start = next;
        }
        return writeOffset;
    }

    private static int findStartCode(byte[] data, int offset, int length) {
        int start = Math.max(0, offset);
        for (int i = start; i + 2 < length; i++) {
            if (data[i] != 0 || data[i + 1] != 0) continue;
            if (i + 3 < length && data[i + 2] == 0
                    && data[i + 3] == 1) return i;
            if (data[i + 2] == 1) return i;
        }
        return -1;
    }

}
