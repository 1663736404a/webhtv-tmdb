package com.fongmi.android.tv.player;

import java.util.Locale;
import java.util.Objects;

public record PlaybackAutoContext(
        SessionToken session,
        long startedAtElapsedMs,
        long revision,
        long publishedAtElapsedMs,
        Fact<Kernel> kernel,
        Fact<DecodeMode> decodeMode,
        DeviceFacts device,
        ResourceFacts resource,
        PathFacts path,
        RuntimeFacts runtime) {

    public PlaybackAutoContext {
        session = session == null ? SessionToken.none() : session;
        kernel = kernel == null ? Fact.unknown(Kernel.UNKNOWN) : kernel;
        decodeMode = decodeMode == null ? Fact.unknown(DecodeMode.UNKNOWN) : decodeMode;
        device = device == null ? DeviceFacts.unknown() : device;
        resource = resource == null ? ResourceFacts.unknown() : resource;
        path = path == null ? PathFacts.unknown() : path;
        runtime = runtime == null ? RuntimeFacts.unknown() : runtime;
    }

    public static PlaybackAutoContext empty() {
        return new PlaybackAutoContext(SessionToken.none(), -1, 0, -1,
                Fact.unknown(Kernel.UNKNOWN),
                Fact.unknown(DecodeMode.UNKNOWN),
                DeviceFacts.unknown(),
                ResourceFacts.unknown(),
                PathFacts.unknown(),
                RuntimeFacts.unknown());
    }

    static PlaybackAutoContext begin(SessionToken session, long startedAtElapsedMs) {
        long startedAt = Math.max(0, startedAtElapsedMs);
        return new PlaybackAutoContext(session, startedAt, 0, startedAt,
                Fact.unknown(Kernel.UNKNOWN),
                Fact.unknown(DecodeMode.UNKNOWN),
                DeviceFacts.unknown(),
                ResourceFacts.unknown(),
                PathFacts.unknown(),
                RuntimeFacts.unknown());
    }

    public boolean active() {
        return session.active();
    }

    PlaybackAutoContext withPlaybackFacts(Fact<Kernel> kernel, Fact<DecodeMode> decodeMode, PathFacts path) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device, resource, path, runtime);
    }

    PlaybackAutoContext withPlaybackFacts(Fact<Kernel> kernel, Fact<DecodeMode> decodeMode, ResourceFacts resource, PathFacts path) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device, resource, path, runtime);
    }

    PlaybackAutoContext withDeviceFacts(DeviceFacts device) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device, resource, path, runtime);
    }

    PlaybackAutoContext withResourceFacts(ResourceFacts resource) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device, resource, path, runtime);
    }

    PlaybackAutoContext withPathFacts(PathFacts path) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device, resource, path, runtime);
    }

    PlaybackAutoContext withRuntimeFacts(RuntimeFacts runtime) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, revision, publishedAtElapsedMs,
                kernel, decodeMode, device, resource, path, runtime);
    }

    PlaybackAutoContext withPublication(long revision, long publishedAtElapsedMs) {
        return new PlaybackAutoContext(session, startedAtElapsedMs, Math.max(0, revision),
                Math.max(startedAtElapsedMs, publishedAtElapsedMs), kernel, decodeMode, device, resource, path, runtime);
    }

    public String logSummary() {
        return "generation=" + session.generation() +
                " revision=" + revision +
                " kernel=" + factLabel(kernel, kernel.value().label()) +
                " decode=" + factLabel(decodeMode, decodeMode.value().label()) +
                " " + path.logSummary() +
                " protocol=" + factLabel(resource.protocol(), resource.protocol().value().label()) +
                " stream=" + factLabel(resource.streamKind(), resource.streamKind().value().label()) +
                " transfer=" + factLabel(resource.transferUnit(), resource.transferUnit().value().label()) +
                " manifest=" + factLabel(resource.manifest(), resource.manifest().value().kind().label()) +
                " runtime=" + factLabel(runtime.phase(), runtime.phase().value().label()) +
                " memory=" + factLabel(device.memoryPressure(), device.memoryPressure().value().label());
    }

    private static String factLabel(Fact<?> fact, String value) {
        String safeValue = fact.hasValue() ? value : "unknown";
        return safeValue + "/" + fact.source().label() + "/" + fact.confidence().label() + "/" + fact.expiryRule().label();
    }

    public record SessionToken(String traceId, long generation) {

        public SessionToken {
            traceId = PlaybackTrace.normalize(traceId);
            if (PlaybackTrace.NONE.equals(traceId) || generation <= 0) {
                traceId = PlaybackTrace.NONE;
                generation = 0;
            }
        }

        public static SessionToken none() {
            return new SessionToken(PlaybackTrace.NONE, 0);
        }

        public boolean active() {
            return generation > 0 && !PlaybackTrace.NONE.equals(traceId);
        }
    }

    public record Fact<T>(
            T value,
            ValueSource source,
            Confidence confidence,
            long sampledAtElapsedMs,
            ExpiryRule expiryRule,
            long expiresAtElapsedMs) {

        public Fact {
            value = Objects.requireNonNull(value);
            source = source == null ? ValueSource.UNKNOWN : source;
            confidence = confidence == null ? Confidence.UNKNOWN : confidence;
            expiryRule = expiryRule == null ? ExpiryRule.UNKNOWN : expiryRule;
            if (expiryRule == ExpiryRule.TTL) {
                if (sampledAtElapsedMs < 0 || expiresAtElapsedMs <= sampledAtElapsedMs) {
                    throw new IllegalArgumentException("TTL fact must expire after its sample");
                }
            } else {
                expiresAtElapsedMs = -1;
            }
        }

        public static <T> Fact<T> unknown(T value) {
            return new Fact<>(value, ValueSource.UNKNOWN, Confidence.UNKNOWN, -1, ExpiryRule.UNKNOWN, -1);
        }

        public static <T> Fact<T> forSession(T value, ValueSource source, Confidence confidence, long sampledAtElapsedMs) {
            return new Fact<>(value, source, confidence, Math.max(0, sampledAtElapsedMs), ExpiryRule.SESSION, -1);
        }

        public static <T> Fact<T> untilReplaced(T value, ValueSource source, Confidence confidence, long sampledAtElapsedMs) {
            return new Fact<>(value, source, confidence, Math.max(0, sampledAtElapsedMs), ExpiryRule.UNTIL_REPLACED, -1);
        }

        public static <T> Fact<T> withTtl(T value, ValueSource source, Confidence confidence, long sampledAtElapsedMs, long validForMs) {
            long sampledAt = Math.max(0, sampledAtElapsedMs);
            long duration = Math.max(1, validForMs);
            long expiresAt = sampledAt > Long.MAX_VALUE - duration ? Long.MAX_VALUE : sampledAt + duration;
            if (expiresAt <= sampledAt) throw new IllegalArgumentException("TTL fact duration overflow");
            return new Fact<>(value, source, confidence, sampledAt, ExpiryRule.TTL, expiresAt);
        }

        public boolean hasValue() {
            return source != ValueSource.UNKNOWN;
        }

        public boolean isExpired(long elapsedRealtimeMs) {
            return switch (expiryRule) {
                case UNKNOWN -> true;
                case TTL -> Math.max(0, elapsedRealtimeMs) >= expiresAtElapsedMs;
                case SESSION, UNTIL_REPLACED -> false;
            };
        }

        public boolean isUsable(long elapsedRealtimeMs) {
            return hasValue() && confidence != Confidence.UNKNOWN && !isExpired(elapsedRealtimeMs);
        }

        public long ageMs(long elapsedRealtimeMs) {
            return sampledAtElapsedMs < 0 ? -1 : Math.max(0, elapsedRealtimeMs - sampledAtElapsedMs);
        }
    }

    public record DeviceFacts(
            Fact<MemoryPressure> memoryPressure,
            Fact<ThermalState> thermalState,
            Fact<PowerState> powerState,
            Fact<NetworkCost> networkCost) {

        public DeviceFacts {
            memoryPressure = memoryPressure == null ? Fact.unknown(MemoryPressure.UNKNOWN) : memoryPressure;
            thermalState = thermalState == null ? Fact.unknown(ThermalState.UNKNOWN) : thermalState;
            powerState = powerState == null ? Fact.unknown(PowerState.UNKNOWN) : powerState;
            networkCost = networkCost == null ? Fact.unknown(NetworkCost.UNKNOWN) : networkCost;
        }

        public static DeviceFacts unknown() {
            return new DeviceFacts(
                    Fact.unknown(MemoryPressure.UNKNOWN),
                    Fact.unknown(ThermalState.UNKNOWN),
                    Fact.unknown(PowerState.UNKNOWN),
                    Fact.unknown(NetworkCost.UNKNOWN));
        }
    }

    public record ResourceFacts(
            Fact<Protocol> protocol,
            Fact<StreamKind> streamKind,
            Fact<RangeSupport> rangeSupport,
            Fact<TransferUnit> transferUnit,
            Fact<ManifestFacts> manifest) {

        public ResourceFacts(
                Fact<Protocol> protocol,
                Fact<StreamKind> streamKind,
                Fact<RangeSupport> rangeSupport,
                Fact<TransferUnit> transferUnit) {
            this(protocol, streamKind, rangeSupport, transferUnit, Fact.unknown(ManifestFacts.unknown()));
        }

        public ResourceFacts {
            protocol = protocol == null ? Fact.unknown(Protocol.UNKNOWN) : protocol;
            streamKind = streamKind == null ? Fact.unknown(StreamKind.UNKNOWN) : streamKind;
            rangeSupport = rangeSupport == null ? Fact.unknown(RangeSupport.UNKNOWN) : rangeSupport;
            transferUnit = transferUnit == null ? Fact.unknown(TransferUnit.UNKNOWN) : transferUnit;
            manifest = manifest == null ? Fact.unknown(ManifestFacts.unknown()) : manifest;
        }

        public static ResourceFacts unknown() {
            return new ResourceFacts(
                    Fact.unknown(Protocol.UNKNOWN),
                    Fact.unknown(StreamKind.UNKNOWN),
                    Fact.unknown(RangeSupport.UNKNOWN),
                    Fact.unknown(TransferUnit.UNKNOWN),
                    Fact.unknown(ManifestFacts.unknown()));
        }
    }

    public record PathFacts(
            Fact<PlaybackRoute> route,
            Fact<PlaybackRoute.Owner> owner,
            Fact<Boolean> loopback,
            Fact<PlaybackRouteCapabilities.ObservedLeg> observedLeg,
            Fact<PlaybackRouteCapabilities.UpstreamVisibility> upstreamVisibility,
            Fact<PlaybackRouteCapabilities.ControlScope> controlScope,
            Fact<PathKind> playerPath,
            Fact<PathKind> upstreamPath,
            Fact<UpstreamState> upstreamState) {

        public PathFacts(
                Fact<PlaybackRoute> route,
                Fact<PlaybackRoute.Owner> owner,
                Fact<Boolean> loopback,
                Fact<PlaybackRouteCapabilities.ObservedLeg> observedLeg,
                Fact<PlaybackRouteCapabilities.UpstreamVisibility> upstreamVisibility,
                Fact<PlaybackRouteCapabilities.ControlScope> controlScope) {
            this(route, owner, loopback, observedLeg, upstreamVisibility, controlScope,
                    Fact.unknown(PathKind.UNKNOWN), Fact.unknown(PathKind.UNKNOWN), Fact.unknown(UpstreamState.UNKNOWN));
        }

        public PathFacts {
            route = route == null ? Fact.unknown(PlaybackRoute.OTHER) : route;
            owner = owner == null ? Fact.unknown(PlaybackRoute.Owner.UNKNOWN) : owner;
            loopback = loopback == null ? Fact.unknown(false) : loopback;
            observedLeg = observedLeg == null ? Fact.unknown(PlaybackRouteCapabilities.ObservedLeg.SOURCE_SPECIFIC) : observedLeg;
            upstreamVisibility = upstreamVisibility == null ? Fact.unknown(PlaybackRouteCapabilities.UpstreamVisibility.UNKNOWN) : upstreamVisibility;
            controlScope = controlScope == null ? Fact.unknown(PlaybackRouteCapabilities.ControlScope.NONE) : controlScope;
            playerPath = playerPath == null ? Fact.unknown(PathKind.UNKNOWN) : playerPath;
            upstreamPath = upstreamPath == null ? Fact.unknown(PathKind.UNKNOWN) : upstreamPath;
            upstreamState = upstreamState == null ? Fact.unknown(UpstreamState.UNKNOWN) : upstreamState;
        }

        public static PathFacts unknown() {
            return new PathFacts(
                    Fact.unknown(PlaybackRoute.OTHER),
                    Fact.unknown(PlaybackRoute.Owner.UNKNOWN),
                    Fact.unknown(false),
                    Fact.unknown(PlaybackRouteCapabilities.ObservedLeg.SOURCE_SPECIFIC),
                    Fact.unknown(PlaybackRouteCapabilities.UpstreamVisibility.UNKNOWN),
                    Fact.unknown(PlaybackRouteCapabilities.ControlScope.NONE),
                    Fact.unknown(PathKind.UNKNOWN),
                    Fact.unknown(PathKind.UNKNOWN),
                    Fact.unknown(UpstreamState.UNKNOWN));
        }

        public static PathFacts fromResolution(PlaybackRoute.Resolution resolution, long sampledAtElapsedMs) {
            PlaybackRoute.Resolution safe = resolution == null ? PlaybackRoute.resolve(null) : resolution;
            PlaybackRouteCapabilities capabilities = PlaybackRouteCapabilities.resolve(safe);
            Confidence confidence = Confidence.fromRoute(safe.confidence());
            PathKind playerPath = pathKind(safe.location());
            PathKind upstreamPath = switch (playerPath) {
                case LOCAL, LAN_PRIVATE, REMOTE -> playerPath;
                default -> PathKind.UNKNOWN;
            };
            UpstreamState upstreamState = playerPath == PathKind.LOCAL
                    ? UpstreamState.NOT_APPLICABLE : upstreamState(capabilities.upstreamVisibility());
            return new PathFacts(
                    Fact.forSession(safe.route(), ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(safe.owner(), ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(safe.loopback(), ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(capabilities.observedLeg(), ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(capabilities.upstreamVisibility(), ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(capabilities.controlScope(), ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(playerPath, ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    upstreamPath == PathKind.UNKNOWN ? Fact.unknown(PathKind.UNKNOWN) : Fact.forSession(upstreamPath, ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs),
                    Fact.forSession(upstreamState, ValueSource.ROUTE_CLASSIFIER, confidence, sampledAtElapsedMs));
        }

        private String logSummary() {
            String routeValue = route.hasValue() ? route.value().name().toLowerCase(Locale.US) : "unknown";
            String ownerValue = owner.hasValue() ? owner.value().label() : "unknown";
            String playerValue = playerPath.hasValue() ? playerPath.value().label() : "unknown";
            String upstreamValue = upstreamPath.hasValue() ? upstreamPath.value().label() : "unknown";
            return "route=" + factLabel(route, routeValue) + " owner=" + factLabel(owner, ownerValue)
                    + " playerPath=" + factLabel(playerPath, playerValue)
                    + " upstreamPath=" + factLabel(upstreamPath, upstreamValue)
                    + " upstreamState=" + factLabel(upstreamState, upstreamState.value().label());
        }

        private static PathKind pathKind(PlaybackRoute.Location location) {
            if (location == null) return PathKind.UNKNOWN;
            return switch (location) {
                case LOCAL -> PathKind.LOCAL;
                case LAN_PRIVATE -> PathKind.LAN_PRIVATE;
                case REMOTE -> PathKind.REMOTE;
                case APP_INTERNAL_SERVICE -> PathKind.APP_INTERNAL_SERVICE;
                case EXTERNAL_LOOPBACK -> PathKind.EXTERNAL_LOOPBACK;
                case UNKNOWN -> PathKind.UNKNOWN;
            };
        }

        private static UpstreamState upstreamState(PlaybackRouteCapabilities.UpstreamVisibility visibility) {
            if (visibility == null) return UpstreamState.UNKNOWN;
            return switch (visibility) {
                case REQUEST_LEVEL_ONLY, APP_SERVICE_PATH -> UpstreamState.VISIBLE;
                case OPAQUE_EXTERNAL_PROCESS -> UpstreamState.OPAQUE;
                case UNKNOWN -> UpstreamState.UNKNOWN;
            };
        }
    }

    public record ManifestFacts(
            ManifestKind kind,
            Boolean endList,
            Long targetDurationMs,
            Long partDurationMs,
            Long holdBackMs,
            Integer variantCount,
            Boolean byteRange,
            Boolean lowLatency) {

        public ManifestFacts {
            kind = kind == null ? ManifestKind.UNKNOWN : kind;
            targetDurationMs = nonNegativeOrNull(targetDurationMs);
            partDurationMs = nonNegativeOrNull(partDurationMs);
            holdBackMs = nonNegativeOrNull(holdBackMs);
            variantCount = variantCount == null ? null : Math.max(0, variantCount);
        }

        public static ManifestFacts unknown() {
            return new ManifestFacts(ManifestKind.UNKNOWN, null, null, null, null, null, null, null);
        }

        public static ManifestFacts none() {
            return new ManifestFacts(ManifestKind.NONE, null, null, null, null, null, null, null);
        }

        private static Long nonNegativeOrNull(Long value) {
            return value == null || value < 0 ? null : value;
        }
    }

    public record RuntimeFacts(
            Fact<PlaybackPhase> phase,
            Fact<Long> bufferedDurationMs,
            Fact<Long> bandwidthBitsPerSecond,
            Fact<Long> mediaBitrateBitsPerSecond,
            Fact<Float> renderedFrameRate,
            Fact<Long> droppedFrames,
            Fact<Integer> rebufferCount) {

        public RuntimeFacts {
            phase = phase == null ? Fact.unknown(PlaybackPhase.UNKNOWN) : phase;
            bufferedDurationMs = bufferedDurationMs == null ? Fact.unknown(0L) : bufferedDurationMs;
            bandwidthBitsPerSecond = bandwidthBitsPerSecond == null ? Fact.unknown(0L) : bandwidthBitsPerSecond;
            mediaBitrateBitsPerSecond = mediaBitrateBitsPerSecond == null ? Fact.unknown(0L) : mediaBitrateBitsPerSecond;
            renderedFrameRate = renderedFrameRate == null ? Fact.unknown(0f) : renderedFrameRate;
            droppedFrames = droppedFrames == null ? Fact.unknown(0L) : droppedFrames;
            rebufferCount = rebufferCount == null ? Fact.unknown(0) : rebufferCount;
        }

        public static RuntimeFacts unknown() {
            return new RuntimeFacts(
                    Fact.unknown(PlaybackPhase.UNKNOWN),
                    Fact.unknown(0L),
                    Fact.unknown(0L),
                    Fact.unknown(0L),
                    Fact.unknown(0f),
                    Fact.unknown(0L),
                    Fact.unknown(0));
        }
    }

    public enum Kernel {
        EXO("exo"),
        IJK("ijk"),
        MPV("mpv"),
        UNKNOWN("unknown");

        private final String label;

        Kernel(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum DecodeMode {
        HARDWARE("hardware"),
        SOFTWARE("software"),
        UNKNOWN("unknown");

        private final String label;

        DecodeMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum ValueSource {
        PLAYER_MANAGER("player-manager"),
        PLAYBACK_REQUEST("playback-request"),
        MANIFEST("manifest"),
        PROXY("proxy"),
        DATA_SOURCE("data-source"),
        ROUTE_CLASSIFIER("route-classifier"),
        PLAYER_CALLBACK("player-callback"),
        SYSTEM_API("system-api"),
        ESTIMATOR("estimator"),
        UNKNOWN("unknown");

        private final String label;

        ValueSource(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Confidence {
        HIGH("high"),
        MEDIUM("medium"),
        LOW("low"),
        UNKNOWN("unknown");

        private final String label;

        Confidence(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        static Confidence fromRoute(PlaybackRoute.Confidence confidence) {
            if (confidence == null) return UNKNOWN;
            return switch (confidence) {
                case CONFIRMED -> HIGH;
                case INFERRED -> MEDIUM;
                case UNKNOWN -> UNKNOWN;
            };
        }
    }

    public enum ExpiryRule {
        SESSION("session"),
        TTL("ttl"),
        UNTIL_REPLACED("until-replaced"),
        UNKNOWN("unknown");

        private final String label;

        ExpiryRule(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum MemoryPressure {
        NORMAL("normal"),
        MODERATE("moderate"),
        CRITICAL("critical"),
        UNKNOWN("unknown");

        private final String label;

        MemoryPressure(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum ThermalState {
        NOMINAL("nominal"),
        MODERATE("moderate"),
        SEVERE("severe"),
        CRITICAL("critical"),
        UNKNOWN("unknown");

        private final String label;

        ThermalState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum PowerState {
        NORMAL("normal"),
        POWER_SAVE("power-save"),
        UNKNOWN("unknown");

        private final String label;

        PowerState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum NetworkCost {
        UNMETERED("unmetered"),
        METERED("metered"),
        ROAMING("roaming"),
        UNKNOWN("unknown");

        private final String label;

        NetworkCost(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A path classification that does not imply a change to the legacy route policy. */
    public enum PathKind {
        LOCAL("local"),
        LAN_PRIVATE("lan-private"),
        REMOTE("remote"),
        APP_INTERNAL_SERVICE("app-internal-service"),
        EXTERNAL_LOOPBACK("external-loopback"),
        UNKNOWN("unknown");

        private final String label;

        PathKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum UpstreamState {
        VISIBLE("visible"),
        OPAQUE("opaque"),
        NOT_APPLICABLE("not-applicable"),
        UNKNOWN("unknown");

        private final String label;

        UpstreamState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Protocol {
        LOCAL("local"),
        PROGRESSIVE_HTTP("progressive-http"),
        HLS("hls"),
        DASH("dash"),
        RTSP("rtsp"),
        RTMP("rtmp"),
        OTHER("other"),
        UNKNOWN("unknown");

        private final String label;

        Protocol(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum StreamKind {
        VOD("vod"),
        LIVE("live"),
        LOW_LATENCY_LIVE("low-latency-live"),
        UNKNOWN("unknown");

        private final String label;

        StreamKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum RangeSupport {
        SUPPORTED("supported"),
        UNSUPPORTED("unsupported"),
        UNKNOWN("unknown");

        private final String label;

        RangeSupport(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum TransferUnit {
        CONTINUOUS("continuous"),
        SEGMENT("segment"),
        PART("part"),
        UNKNOWN("unknown");

        private final String label;

        TransferUnit(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum ManifestKind {
        NONE("none"),
        HLS_MASTER("hls-master"),
        HLS_MEDIA("hls-media"),
        DASH_STATIC("dash-static"),
        DASH_DYNAMIC("dash-dynamic"),
        UNKNOWN("unknown");

        private final String label;

        ManifestKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum PlaybackPhase {
        IDLE("idle"),
        PREPARING("preparing"),
        BUFFERING("buffering"),
        READY("ready"),
        ENDED("ended"),
        ERROR("error"),
        UNKNOWN("unknown");

        private final String label;

        PlaybackPhase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
