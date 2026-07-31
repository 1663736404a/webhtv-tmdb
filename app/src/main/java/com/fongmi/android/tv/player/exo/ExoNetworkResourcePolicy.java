package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Fail-closed resource classification for the opt-in single-rate VOD rescue. */
public final class ExoNetworkResourcePolicy {

    private static final int UNKNOWN_COUNT = -1;

    private ExoNetworkResourcePolicy() {
    }

    public static Decision resolve(
            PlaybackAutoContext.ResourceFacts resource,
            int availableVideoFormats,
            long nowElapsedMs) {
        PlaybackAutoContext.ResourceFacts facts = resource == null
                ? PlaybackAutoContext.ResourceFacts.unknown() : resource;
        int formats = Math.max(0, availableVideoFormats);
        long now = Math.max(0, nowElapsedMs);
        int manifestVariants = manifestVariantCount(facts.manifest(), now);

        if (manifestVariants > 1) {
            return new Decision(
                    Mode.ADAPTIVE_VARIANTS,
                    Reason.MANIFEST_VARIANTS,
                    formats,
                    manifestVariants);
        }
        if (formats > 1) {
            return new Decision(
                    Mode.ADAPTIVE_VARIANTS,
                    Reason.MULTIPLE_VIDEO_FORMATS,
                    formats,
                    manifestVariants);
        }
        if (formats != 1) {
            return new Decision(
                    Mode.UNKNOWN,
                    Reason.VIDEO_FORMATS_UNKNOWN,
                    formats,
                    manifestVariants);
        }

        PlaybackAutoContext.Fact<PlaybackAutoContext.Protocol> protocolFact =
                facts.protocol();
        if (protocolFact == null || !protocolFact.isUsable(now)) {
            return new Decision(
                    Mode.UNKNOWN,
                    Reason.PROTOCOL_UNKNOWN,
                    formats,
                    manifestVariants);
        }
        PlaybackAutoContext.Protocol protocol = protocolFact.value();
        if (protocol == PlaybackAutoContext.Protocol.HLS
                || protocol == PlaybackAutoContext.Protocol.DASH) {
            return new Decision(
                    Mode.SEGMENTED_SINGLE,
                    Reason.ONE_AVAILABLE_VIDEO_FORMAT,
                    formats,
                    manifestVariants);
        }
        if (protocol == PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP) {
            return new Decision(
                    Mode.PROGRESSIVE_SINGLE,
                    Reason.ONE_AVAILABLE_VIDEO_FORMAT,
                    formats,
                    manifestVariants);
        }
        return new Decision(
                Mode.UNKNOWN,
                Reason.PROTOCOL_NOT_ELIGIBLE,
                formats,
                manifestVariants);
    }

    private static int manifestVariantCount(
            PlaybackAutoContext.Fact<PlaybackAutoContext.ManifestFacts> fact,
            long now) {
        if (fact == null || !fact.isUsable(now)) return UNKNOWN_COUNT;
        Integer count = fact.value().variantCount();
        return count == null ? UNKNOWN_COUNT : Math.max(0, count);
    }

    public enum Mode {
        ADAPTIVE_VARIANTS("adaptive-variants"),
        SEGMENTED_SINGLE("segmented-single"),
        PROGRESSIVE_SINGLE("progressive-single"),
        UNKNOWN("unknown");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean singleRate() {
            return this == SEGMENTED_SINGLE || this == PROGRESSIVE_SINGLE;
        }
    }

    public enum Reason {
        MANIFEST_VARIANTS("manifest-variants"),
        MULTIPLE_VIDEO_FORMATS("multiple-video-formats"),
        VIDEO_FORMATS_UNKNOWN("video-formats-unknown"),
        PROTOCOL_UNKNOWN("protocol-unknown"),
        PROTOCOL_NOT_ELIGIBLE("protocol-not-eligible"),
        ONE_AVAILABLE_VIDEO_FORMAT("one-available-video-format");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Decision(
            Mode mode,
            Reason reason,
            int availableVideoFormats,
            int manifestVariantCount) {

        public Decision {
            mode = mode == null ? Mode.UNKNOWN : mode;
            reason = reason == null ? Reason.PROTOCOL_UNKNOWN : reason;
            availableVideoFormats = Math.max(0, availableVideoFormats);
            manifestVariantCount = Math.max(UNKNOWN_COUNT, manifestVariantCount);
        }
    }
}
