package com.fongmi.android.tv.player;

import android.os.SystemClock;

import com.fongmi.android.tv.utils.Task;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Best-effort system GPU load sampler. It never substitutes CPU or frame-rate data. */
public final class GpuLoadMonitor {

    private static final long SAMPLE_INTERVAL_MS = 900;
    private static final long REDISCOVERY_INTERVAL_MS = 30_000;
    private static final int AVERAGE_WINDOW = 10;
    private static final GpuLoadMonitor INSTANCE = new GpuLoadMonitor();

    private final AtomicBoolean sampling = new AtomicBoolean();
    private final Deque<Double> recentLoads = new ArrayDeque<>();
    private volatile Snapshot snapshot = Snapshot.pending();
    private volatile Source source;
    private double recentLoadSum;
    private long lastRequestMs;
    private long lastDiscoveryMs;
    private int consecutiveFailures;

    private GpuLoadMonitor() {
    }

    public static GpuLoadMonitor process() {
        return INSTANCE;
    }

    /** Schedules one non-blocking sample. Call only while diagnostics are visible. */
    public void requestSample() {
        long now = SystemClock.elapsedRealtime();
        long interval = source == null ? REDISCOVERY_INTERVAL_MS : SAMPLE_INTERVAL_MS;
        if ((lastRequestMs > 0 && now - lastRequestMs < interval)
                || !sampling.compareAndSet(false, true)) return;
        lastRequestMs = now;
        Task.executor().execute(() -> {
            try {
                sampleInBackground();
            } finally {
                sampling.set(false);
            }
        });
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    private void sampleInBackground() {
        long now = SystemClock.elapsedRealtime();
        Source current = source;
        if (current == null && (lastDiscoveryMs == 0
                || now - lastDiscoveryMs >= REDISCOVERY_INTERVAL_MS)) {
            lastDiscoveryMs = now;
            current = discover();
            source = current;
        }
        if (current == null) {
            snapshot = Snapshot.unsupported();
            return;
        }
        try {
            Double percent = current.readPercent();
            if (percent == null || !Double.isFinite(percent)) return;
            percent = Math.max(0, Math.min(100, percent));
            recentLoads.addLast(percent);
            recentLoadSum += percent;
            while (recentLoads.size() > AVERAGE_WINDOW) {
                recentLoadSum -= recentLoads.removeFirst();
            }
            consecutiveFailures = 0;
            snapshot = new Snapshot(Status.AVAILABLE, percent,
                    recentLoadSum / Math.max(1, recentLoads.size()),
                    current.readFrequencyHz(), current.label());
        } catch (Throwable ignored) {
            if (++consecutiveFailures >= 3) {
                source = null;
                recentLoads.clear();
                recentLoadSum = 0;
                snapshot = Snapshot.unsupported();
            }
        }
    }

    private Source discover() {
        List<Source> candidates = new ArrayList<>();
        candidates.add(Source.percent("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "KGSL"));
        candidates.add(Source.ratio("/sys/class/kgsl/kgsl-3d0/gpubusy",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "KGSL"));
        candidates.add(Source.percent("/sys/module/ged/parameters/gpu_loading",
                "/sys/kernel/gpufreq/gpufreq_cur_freq", "MTK GED"));
        candidates.add(Source.percent("/sys/kernel/ged/hal/gpu_utilization",
                "/sys/kernel/gpufreq/gpufreq_cur_freq", "MTK GED"));
        candidates.add(Source.percent("/sys/class/misc/mali0/device/utilization",
                "", "Mali"));
        discoverDevfreq(candidates);
        for (Source candidate : candidates) {
            try {
                Double value = candidate.readPercent();
                if ((value != null && Double.isFinite(value))
                        || candidate.hasUsableBaseline()) return candidate;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void discoverDevfreq(List<Source> candidates) {
        File root = new File("/sys/class/devfreq");
        File[] entries;
        try {
            entries = root.listFiles();
        } catch (Throwable ignored) {
            return;
        }
        if (entries == null) return;
        for (File entry : entries) {
            String identity;
            try {
                identity = (entry.getName() + " " + entry.getCanonicalPath())
                        .toLowerCase(Locale.US);
            } catch (Throwable ignored) {
                identity = entry.getName().toLowerCase(Locale.US);
            }
            if (!containsGpuIdentity(identity)) continue;
            String base = entry.getAbsolutePath();
            String label = "devfreq " + entry.getName();
            candidates.add(Source.percent(base + "/load", base + "/cur_freq", label));
            candidates.add(Source.counters(base + "/busy_time", base + "/total_time",
                    base + "/cur_freq", label));
        }
    }

    private boolean containsGpuIdentity(String value) {
        return value.contains("gpu") || value.contains("kgsl")
                || value.contains("mali") || value.contains("3d00000")
                || value.contains("gpufreq");
    }

    public enum Status {
        PENDING,
        AVAILABLE,
        UNSUPPORTED
    }

    public record Snapshot(Status status, double percent, double averagePercent,
                           long frequencyHz, String source) {

        static Snapshot pending() {
            return new Snapshot(Status.PENDING, 0, 0, 0, "");
        }

        static Snapshot unsupported() {
            return new Snapshot(Status.UNSUPPORTED, 0, 0, 0, "");
        }

        public boolean available() {
            return status == Status.AVAILABLE;
        }
    }

    private static final class Source {

        private enum Kind {
            PERCENT,
            WINDOW_RATIO,
            CUMULATIVE_COUNTERS
        }

        private final Kind kind;
        private final String loadPath;
        private final String totalPath;
        private final String frequencyPath;
        private final String label;
        private long previousBusy = -1;
        private long previousTotal = -1;

        private Source(Kind kind, String loadPath, String totalPath,
                       String frequencyPath, String label) {
            this.kind = kind;
            this.loadPath = loadPath;
            this.totalPath = totalPath;
            this.frequencyPath = frequencyPath;
            this.label = label;
        }

        static Source percent(String path, String frequencyPath, String label) {
            return new Source(Kind.PERCENT, path, "", frequencyPath, label);
        }

        static Source ratio(String path, String frequencyPath, String label) {
            return new Source(Kind.WINDOW_RATIO, path, "", frequencyPath, label);
        }

        static Source counters(String busyPath, String totalPath,
                               String frequencyPath, String label) {
            return new Source(Kind.CUMULATIVE_COUNTERS, busyPath, totalPath,
                    frequencyPath, label);
        }

        String label() {
            return label;
        }

        boolean hasUsableBaseline() {
            return kind == Kind.CUMULATIVE_COUNTERS
                    && previousBusy >= 0 && previousTotal >= 0;
        }

        Double readPercent() throws Exception {
            return switch (kind) {
                case PERCENT -> firstNumber(read(loadPath));
                case WINDOW_RATIO -> ratio(read(loadPath));
                case CUMULATIVE_COUNTERS -> counterRatio();
            };
        }

        long readFrequencyHz() {
            if (frequencyPath == null || frequencyPath.isBlank()) return 0;
            try {
                Double value = firstNumber(read(frequencyPath));
                if (value == null || value <= 0) return 0;
                long frequency = Math.round(value);
                // Some MediaTek nodes expose kHz while devfreq/KGSL expose Hz.
                return frequency < 10_000_000L ? frequency * 1000L : frequency;
            } catch (Throwable ignored) {
                return 0;
            }
        }

        private Double counterRatio() throws Exception {
            long busy = Math.round(requiredNumber(read(loadPath)));
            long total = Math.round(requiredNumber(read(totalPath)));
            if (previousBusy < 0 || previousTotal < 0 || busy < previousBusy
                    || total <= previousTotal) {
                previousBusy = busy;
                previousTotal = total;
                return null;
            }
            long busyDelta = busy - previousBusy;
            long totalDelta = total - previousTotal;
            previousBusy = busy;
            previousTotal = total;
            return totalDelta <= 0 ? null : busyDelta * 100.0 / totalDelta;
        }

        private static Double ratio(String value) {
            List<Double> numbers = numbers(value);
            if (numbers.size() < 2 || numbers.get(1) <= 0) return null;
            return numbers.get(0) * 100.0 / numbers.get(1);
        }

        private static double requiredNumber(String value) {
            Double number = firstNumber(value);
            if (number == null) throw new IllegalArgumentException("missing counter");
            return number;
        }

        private static Double firstNumber(String value) {
            List<Double> values = numbers(value);
            return values.isEmpty() ? null : values.get(0);
        }

        private static List<Double> numbers(String value) {
            List<Double> result = new ArrayList<>();
            if (value == null) return result;
            StringBuilder token = new StringBuilder();
            for (int i = 0; i <= value.length(); i++) {
                char c = i < value.length() ? value.charAt(i) : ' ';
                if ((c >= '0' && c <= '9') || c == '.') {
                    token.append(c);
                } else if (token.length() > 0) {
                    try {
                        result.add(Double.parseDouble(token.toString()));
                    } catch (NumberFormatException ignored) {
                    }
                    token.setLength(0);
                }
            }
            return result;
        }

        private static String read(String path) throws Exception {
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                String value = reader.readLine();
                if (value == null) throw new IllegalStateException("empty node");
                return value.trim();
            }
        }
    }
}
