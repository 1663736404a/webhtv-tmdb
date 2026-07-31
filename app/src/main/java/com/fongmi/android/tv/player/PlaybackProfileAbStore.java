package com.fongmi.android.tv.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded, versioned and expiring local sample store for V-05A. */
public final class PlaybackProfileAbStore {

    static final int STORE_VERSION = 1;
    static final int SAMPLE_VERSION = 1;
    static final long SAMPLE_TTL_MS = TimeUnit.DAYS.toMillis(30);
    static final long MAX_FUTURE_SKEW_MS = TimeUnit.MINUTES.toMillis(5);
    static final int MAX_GROUPS = 48;
    static final int MAX_SAMPLES_PER_ARM = 32;

    private static final String HEADER = "store-version=" + STORE_VERSION;

    private final Backend backend;
    private final Map<PlaybackProfileAbPolicy.GroupKey,
            EnumMap<PlaybackProfileAbPolicy.Arm, List<Sample>>> groups =
            new LinkedHashMap<>();
    private boolean loaded;

    public PlaybackProfileAbStore(Backend backend) {
        this.backend = backend == null ? Backend.NONE : backend;
    }

    public synchronized boolean record(Sample sample, long nowEpochMs) {
        if (sample == null || !sample.valid() || nowEpochMs <= 0) {
            return false;
        }
        ensureLoaded(nowEpochMs);
        prune(nowEpochMs);
        Sample stored = sample.recordedAtEpochMs() == nowEpochMs
                ? sample
                : sample.withRecordedAt(nowEpochMs);
        EnumMap<PlaybackProfileAbPolicy.Arm, List<Sample>> arms =
                groups.computeIfAbsent(
                        stored.groupKey(),
                        ignored -> new EnumMap<>(
                                PlaybackProfileAbPolicy.Arm.class));
        List<Sample> samples = arms.computeIfAbsent(
                stored.arm(), ignored -> new ArrayList<>());
        samples.add(stored);
        samples.sort(Comparator.comparingLong(
                Sample::recordedAtEpochMs).reversed());
        trimArm(samples);
        trimGroups();
        save();
        return true;
    }

    public synchronized Snapshot snapshot(long nowEpochMs) {
        ensureLoaded(nowEpochMs);
        if (prune(nowEpochMs)) save();
        return snapshotLocked();
    }

    public synchronized Snapshot snapshotForDevice(
            long nowEpochMs,
            String deviceDigest) {
        ensureLoaded(nowEpochMs);
        boolean changed = prune(nowEpochMs);
        if (!PlaybackProfileAbIdentity.validDigest(deviceDigest)) {
            if (changed) save();
            return new Snapshot(List.of());
        }
        changed |= groups.entrySet().removeIf(entry ->
                !deviceDigest.equals(entry.getKey().deviceDigest()));
        if (changed) save();
        return snapshotLocked();
    }

    private Snapshot snapshotLocked() {
        List<GroupSamples> result = new ArrayList<>();
        for (Map.Entry<PlaybackProfileAbPolicy.GroupKey,
                EnumMap<PlaybackProfileAbPolicy.Arm, List<Sample>>> entry
                : groups.entrySet()) {
            List<Sample> automatic = new ArrayList<>(entry.getValue()
                    .getOrDefault(PlaybackProfileAbPolicy.Arm.AUTO,
                            List.of()));
            List<Sample> recommended = new ArrayList<>(entry.getValue()
                    .getOrDefault(PlaybackProfileAbPolicy.Arm.RECOMMENDED,
                            List.of()));
            result.add(new GroupSamples(
                    entry.getKey(),
                    List.copyOf(automatic),
                    List.copyOf(recommended)));
        }
        result.sort(Comparator.comparing(
                group -> PlaybackProfileAbIdentity.groupDigest(
                        group.groupKey())));
        return new Snapshot(List.copyOf(result));
    }

    public synchronized void clear() {
        loaded = true;
        groups.clear();
        try {
            backend.clear();
        } catch (Throwable ignored) {
        }
    }

    private void ensureLoaded(long nowEpochMs) {
        if (loaded) return;
        loaded = true;
        String raw;
        try {
            raw = backend.read();
        } catch (Throwable ignored) {
            raw = "";
        }
        DecodeResult decoded = decode(raw);
        if (!decoded.validStore()) {
            groups.clear();
            try {
                backend.clear();
            } catch (Throwable ignored) {
            }
            return;
        }
        for (Sample sample : decoded.samples()) addLoaded(sample);
        boolean changed = decoded.rewriteRequired() || prune(nowEpochMs);
        if (changed) save();
    }

    private void addLoaded(Sample sample) {
        if (sample == null || !sample.valid()) return;
        EnumMap<PlaybackProfileAbPolicy.Arm, List<Sample>> arms =
                groups.computeIfAbsent(
                        sample.groupKey(),
                        ignored -> new EnumMap<>(
                                PlaybackProfileAbPolicy.Arm.class));
        List<Sample> samples = arms.computeIfAbsent(
                sample.arm(), ignored -> new ArrayList<>());
        samples.add(sample);
        samples.sort(Comparator.comparingLong(
                Sample::recordedAtEpochMs).reversed());
        trimArm(samples);
        trimGroups();
    }

    private boolean prune(long nowEpochMs) {
        if (nowEpochMs <= 0) return false;
        boolean changed = false;
        Iterator<Map.Entry<PlaybackProfileAbPolicy.GroupKey,
                EnumMap<PlaybackProfileAbPolicy.Arm, List<Sample>>>>
                groupIterator = groups.entrySet().iterator();
        while (groupIterator.hasNext()) {
            Map.Entry<PlaybackProfileAbPolicy.GroupKey,
                    EnumMap<PlaybackProfileAbPolicy.Arm, List<Sample>>>
                    group = groupIterator.next();
            for (List<Sample> samples : group.getValue().values()) {
                changed |= samples.removeIf(sample -> expired(
                        sample, nowEpochMs));
                int before = samples.size();
                trimArm(samples);
                changed |= before != samples.size();
            }
            group.getValue().entrySet().removeIf(
                    entry -> entry.getValue().isEmpty());
            if (group.getValue().isEmpty()) {
                groupIterator.remove();
                changed = true;
            }
        }
        int before = groups.size();
        trimGroups();
        return changed || before != groups.size();
    }

    private static boolean expired(Sample sample, long nowEpochMs) {
        if (sample == null || !sample.valid()) return true;
        long recordedAt = sample.recordedAtEpochMs();
        if (recordedAt > saturatingAdd(
                nowEpochMs, MAX_FUTURE_SKEW_MS)) return true;
        return nowEpochMs >= recordedAt
                && nowEpochMs - recordedAt >= SAMPLE_TTL_MS;
    }

    private static void trimArm(List<Sample> samples) {
        if (samples == null) return;
        while (samples.size() > MAX_SAMPLES_PER_ARM) {
            samples.remove(samples.size() - 1);
        }
    }

    private void trimGroups() {
        while (groups.size() > MAX_GROUPS) {
            PlaybackProfileAbPolicy.GroupKey victim = null;
            long oldestNewestSample = Long.MAX_VALUE;
            for (Map.Entry<PlaybackProfileAbPolicy.GroupKey,
                    EnumMap<PlaybackProfileAbPolicy.Arm, List<Sample>>> entry
                    : groups.entrySet()) {
                long newest = newestSampleAt(entry.getValue());
                if (victim == null || newest < oldestNewestSample) {
                    victim = entry.getKey();
                    oldestNewestSample = newest;
                }
            }
            if (victim == null) break;
            groups.remove(victim);
        }
    }

    private static long newestSampleAt(
            EnumMap<PlaybackProfileAbPolicy.Arm, List<Sample>> arms) {
        long newest = -1;
        if (arms == null) return newest;
        for (List<Sample> samples : arms.values()) {
            if (samples == null || samples.isEmpty()) continue;
            newest = Math.max(newest,
                    samples.get(0).recordedAtEpochMs());
        }
        return newest;
    }

    private void save() {
        try {
            if (groups.isEmpty()) backend.clear();
            else backend.write(encode(groups));
        } catch (Throwable ignored) {
        }
    }

    private static DecodeResult decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return new DecodeResult(true, false, List.of());
        }
        String[] lines = raw.split("\\r?\\n");
        if (lines.length == 0 || !HEADER.equals(lines[0].trim())) {
            return new DecodeResult(false, false, List.of());
        }
        List<Sample> samples = new ArrayList<>();
        boolean rewriteRequired = false;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) continue;
            Sample sample = decodeSample(line);
            if (sample == null) {
                rewriteRequired = true;
                continue;
            }
            samples.add(sample);
        }
        return new DecodeResult(
                true, rewriteRequired, List.copyOf(samples));
    }

    private static Sample decodeSample(String line) {
        try {
            String[] values = line.split("\\|", -1);
            if (values.length != 20) return null;
            PlaybackProfileAbPolicy.GroupKey key =
                    new PlaybackProfileAbPolicy.GroupKey(
                            values[1],
                            PlaybackAutoContext.Kernel.valueOf(values[2]),
                            PlaybackAutoContext.DecodeMode.valueOf(values[3]),
                            values[4],
                            PlaybackAutoContext.Protocol.valueOf(values[5]),
                            PlaybackAutoContext.StreamKind.valueOf(values[6]),
                            PlaybackProfileAbPolicy.VideoMimeClass.valueOf(
                                    values[7]),
                            PlaybackAutoContext.HdrType.valueOf(values[8]),
                            PlaybackAutoContext.PathKind.valueOf(values[9]));
            Sample sample = new Sample(
                    Integer.parseInt(values[0]),
                    key,
                    PlaybackProfileAbPolicy.Arm.valueOf(values[10]),
                    Long.parseLong(values[11]),
                    Integer.parseInt(values[12]),
                    Long.parseLong(values[13]),
                    Long.parseLong(values[14]),
                    Long.parseLong(values[15]),
                    Long.parseLong(values[16]),
                    Long.parseLong(values[17]),
                    parseBoolean(values[18]),
                    Long.parseLong(values[19]));
            return sample.valid() ? sample : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean parseBoolean(String value) {
        if ("1".equals(value)) return true;
        if ("0".equals(value)) return false;
        throw new IllegalArgumentException("invalid boolean");
    }

    private static String encode(
            Map<PlaybackProfileAbPolicy.GroupKey,
                    EnumMap<PlaybackProfileAbPolicy.Arm, List<Sample>>>
                    groups) {
        List<Sample> samples = new ArrayList<>();
        for (EnumMap<PlaybackProfileAbPolicy.Arm, List<Sample>> arms
                : groups.values()) {
            for (List<Sample> armSamples : arms.values()) {
                samples.addAll(armSamples);
            }
        }
        samples.sort(Comparator
                .comparing((Sample sample) ->
                        PlaybackProfileAbIdentity.groupDigest(
                                sample.groupKey()))
                .thenComparing(sample -> sample.arm().name())
                .thenComparing(Sample::recordedAtEpochMs,
                        Comparator.reverseOrder()));
        StringBuilder result = new StringBuilder(HEADER);
        for (Sample sample : samples) {
            PlaybackProfileAbPolicy.GroupKey key = sample.groupKey();
            result.append('\n')
                    .append(sample.version()).append('|')
                    .append(key.deviceDigest()).append('|')
                    .append(key.kernel().name()).append('|')
                    .append(key.decodeMode().name()).append('|')
                    .append(key.decoderDigest()).append('|')
                    .append(key.protocol().name()).append('|')
                    .append(key.streamKind().name()).append('|')
                    .append(key.videoMime().name()).append('|')
                    .append(key.hdrType().name()).append('|')
                    .append(key.pathKind().name()).append('|')
                    .append(sample.arm().name()).append('|')
                    .append(sample.firstFrameMs()).append('|')
                    .append(sample.rebufferCount()).append('|')
                    .append(sample.rebufferTotalMs()).append('|')
                    .append(sample.activePlaybackMs()).append('|')
                    .append(sample.peakPssBytes()).append('|')
                    .append(sample.maxDroppedFrames()).append('|')
                    .append(sample.maxLiveLagMs()).append('|')
                    .append(sample.errorObserved() ? 1 : 0).append('|')
                    .append(sample.recordedAtEpochMs());
        }
        return result.toString();
    }

    private static long saturatingAdd(long first, long second) {
        if (second <= 0) return Math.max(0, first);
        return first > Long.MAX_VALUE - second
                ? Long.MAX_VALUE : Math.max(0, first) + second;
    }

    public interface Backend {

        Backend NONE = new Backend() {
            @Override
            public String read() {
                return "";
            }

            @Override
            public void write(String value) {
            }

            @Override
            public void clear() {
            }
        };

        String read();

        void write(String value);

        void clear();
    }

    public record Sample(
            int version,
            PlaybackProfileAbPolicy.GroupKey groupKey,
            PlaybackProfileAbPolicy.Arm arm,
            long firstFrameMs,
            int rebufferCount,
            long rebufferTotalMs,
            long activePlaybackMs,
            long peakPssBytes,
            long maxDroppedFrames,
            long maxLiveLagMs,
            boolean errorObserved,
            long recordedAtEpochMs) {

        public Sample {
            firstFrameMs = Math.max(-1, firstFrameMs);
            rebufferCount = Math.max(-1, rebufferCount);
            rebufferTotalMs = Math.max(-1, rebufferTotalMs);
            activePlaybackMs = Math.max(0, activePlaybackMs);
            peakPssBytes = Math.max(-1, peakPssBytes);
            maxDroppedFrames = Math.max(-1, maxDroppedFrames);
            maxLiveLagMs = Math.max(-1, maxLiveLagMs);
            recordedAtEpochMs = Math.max(0, recordedAtEpochMs);
        }

        public boolean valid() {
            return version == SAMPLE_VERSION
                    && groupKey != null && groupKey.valid()
                    && arm != null
                    && recordedAtEpochMs > 0
                    && (errorObserved || firstFrameMs >= 0);
        }

        Sample withRecordedAt(long nowEpochMs) {
            return new Sample(
                    version,
                    groupKey,
                    arm,
                    firstFrameMs,
                    rebufferCount,
                    rebufferTotalMs,
                    activePlaybackMs,
                    peakPssBytes,
                    maxDroppedFrames,
                    maxLiveLagMs,
                    errorObserved,
                    nowEpochMs);
        }
    }

    public record GroupSamples(
            PlaybackProfileAbPolicy.GroupKey groupKey,
            List<Sample> automatic,
            List<Sample> recommended) {

        public GroupSamples {
            automatic = automatic == null
                    ? List.of() : List.copyOf(automatic);
            recommended = recommended == null
                    ? List.of() : List.copyOf(recommended);
        }
    }

    public record Snapshot(List<GroupSamples> groups) {

        public Snapshot {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }

        public int automaticSampleCount() {
            int count = 0;
            for (GroupSamples group : groups) {
                count += group.automatic().size();
            }
            return count;
        }

        public int recommendedSampleCount() {
            int count = 0;
            for (GroupSamples group : groups) {
                count += group.recommended().size();
            }
            return count;
        }
    }

    private record DecodeResult(
            boolean validStore,
            boolean rewriteRequired,
            List<Sample> samples) {
    }
}
