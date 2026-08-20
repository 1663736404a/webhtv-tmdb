package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.github.catvod.crawler.SpiderDebug;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reuses a foreground HLS manifest when a short-lived resolver URL can no longer be fetched. */
final class PlaybackManifestSnapshotDataSource implements DataSource {

    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_SNAPSHOTS = 8;
    private static final long SNAPSHOT_TTL_MS = 10 * 60 * 1000L;
    private static final byte[] HLS_HEADER = "#EXTM3U".getBytes(StandardCharsets.US_ASCII);
    private static final SnapshotStore STORE = new SnapshotStore();

    enum Mode {
        CAPTURE,
        REPLAY
    }

    static final class Factory implements DataSource.Factory {

        private final DataSource.Factory upstreamFactory;
        private final Mode mode;

        Factory(DataSource.Factory upstreamFactory, Mode mode) {
            this.upstreamFactory = upstreamFactory;
            this.mode = mode;
        }

        @Override
        public DataSource createDataSource() {
            return new PlaybackManifestSnapshotDataSource(
                    upstreamFactory.createDataSource(), mode);
        }
    }

    private final DataSource upstream;
    private final Mode mode;
    private final List<TransferListener> transferListeners = new ArrayList<>();
    @Nullable private DataSource active;
    @Nullable private Capture capture;

    PlaybackManifestSnapshotDataSource(DataSource upstream, Mode mode) {
        this.upstream = upstream;
        this.mode = mode;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        transferListeners.add(transferListener);
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        closeActive();
        if (mode == Mode.REPLAY) {
            byte[] manifest = STORE.get(dataSpec.uri.toString());
            if (manifest != null) {
                ByteArrayDataSource snapshot = new ByteArrayDataSource(manifest);
                for (TransferListener listener : transferListeners) {
                    snapshot.addTransferListener(listener);
                }
                active = snapshot;
                log("replay", dataSpec.uri.toString(), manifest.length);
                return snapshot.open(dataSpec);
            }
        }
        active = upstream;
        long openedLength = upstream.open(dataSpec);
        if (mode == Mode.CAPTURE && isCaptureCandidate(dataSpec)) {
            capture = new Capture(dataSpec.uri.toString(), openedLength);
        }
        return openedLength;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        DataSource source = active;
        if (source == null) return C.RESULT_END_OF_INPUT;
        int read = source.read(buffer, offset, length);
        Capture current = capture;
        if (current != null) {
            if (read > 0) current.append(buffer, offset, read);
            else if (read == C.RESULT_END_OF_INPUT) commitCapture(current);
        }
        return read;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return active == null ? null : active.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        DataSource source = active;
        if (source instanceof ByteArrayDataSource) {
            byte[] manifest = STORE.get(source.getUri() == null ? "" : source.getUri().toString());
            int length = manifest == null ? 0 : manifest.length;
            return Map.of(
                    "Content-Type", List.of("application/vnd.apple.mpegurl"),
                    "Content-Length", List.of(String.valueOf(length)));
        }
        return source == null ? Map.of() : source.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        Capture current = capture;
        // HLS resolver responses frequently omit Content-Length. The parser can
        // still have consumed the complete manifest before closing the source,
        // so commit any bounded capture that has a valid HLS header as well.
        if (current != null && current.completeOrEnded()) commitCapture(current);
        closeActive();
    }

    private void closeActive() throws IOException {
        DataSource source = active;
        active = null;
        capture = null;
        if (source != null) source.close();
    }

    private void commitCapture(Capture current) {
        if (capture != current || !current.isHlsManifest()) return;
        byte[] manifest = current.bytes();
        STORE.put(current.uri(), manifest);
        capture = null;
        log("capture", current.uri(), manifest.length);
    }

    private static boolean isCaptureCandidate(DataSpec dataSpec) {
        if (dataSpec.position != 0 || dataSpec.uri == null) return false;
        String scheme = dataSpec.uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static boolean hasHlsPrefix(byte[] data, int length) {
        if (length <= 0) return false;
        int start = length >= 3
                && (data[0] & 0xff) == 0xef
                && (data[1] & 0xff) == 0xbb
                && (data[2] & 0xff) == 0xbf ? 3 : 0;
        if (length - start < HLS_HEADER.length) return false;
        for (int i = 0; i < HLS_HEADER.length; i++) {
            if (data[start + i] != HLS_HEADER[i]) return false;
        }
        return true;
    }

    private static boolean canStillBeHls(byte[] data, int length) {
        if (length <= 0) return true;
        int start = 0;
        if ((data[0] & 0xff) == 0xef) {
            if (length == 1) return true;
            if ((data[1] & 0xff) != 0xbb) return false;
            if (length == 2) return true;
            if ((data[2] & 0xff) != 0xbf) return false;
            start = 3;
        }
        int comparable = Math.min(length - start, HLS_HEADER.length);
        for (int i = 0; i < comparable; i++) {
            if (data[start + i] != HLS_HEADER[i]) return false;
        }
        return true;
    }

    private static void log(String action, String uri, int bytes) {
        if (!SpiderDebug.isEnabled()) return;
        SpiderDebug.log("exo-manifest-snapshot", "action=%s key=%s bytes=%d",
                action, Integer.toHexString(uri.hashCode()), bytes);
    }

    private static final class Capture {

        private final String uri;
        private final long expectedLength;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        private boolean rejected;

        Capture(String uri, long expectedLength) {
            this.uri = uri;
            this.expectedLength = expectedLength;
        }

        void append(byte[] buffer, int offset, int length) {
            if (rejected || length <= 0) return;
            if (output.size() + length > MAX_MANIFEST_BYTES) {
                rejected = true;
                output.reset();
                return;
            }
            output.write(buffer, offset, length);
            byte[] bytes = output.toByteArray();
            if (!canStillBeHls(bytes, bytes.length)) {
                rejected = true;
                output.reset();
            }
        }

        boolean completeOrEnded() {
            if (rejected || output.size() < HLS_HEADER.length || !isHlsManifest()) return false;
            return expectedLength == C.LENGTH_UNSET
                    || expectedLength < 0
                    || output.size() >= expectedLength;
        }

        boolean isHlsManifest() {
            byte[] bytes = output.toByteArray();
            return !rejected && hasHlsPrefix(bytes, bytes.length);
        }

        byte[] bytes() {
            return output.toByteArray();
        }

        String uri() {
            return uri;
        }
    }

    private static final class SnapshotStore {

        private final LinkedHashMap<String, Snapshot> snapshots =
                new LinkedHashMap<>(MAX_SNAPSHOTS, 0.75f, true);

        synchronized void put(String uri, byte[] bytes) {
            if (uri == null || uri.isEmpty() || bytes == null || bytes.length == 0) return;
            pruneExpired();
            snapshots.put(uri, new Snapshot(Arrays.copyOf(bytes, bytes.length),
                    System.currentTimeMillis()));
            while (snapshots.size() > MAX_SNAPSHOTS) {
                String eldest = snapshots.keySet().iterator().next();
                snapshots.remove(eldest);
            }
        }

        @Nullable
        synchronized byte[] get(String uri) {
            if (uri == null || uri.isEmpty()) return null;
            pruneExpired();
            Snapshot snapshot = snapshots.get(uri);
            return snapshot == null ? null
                    : Arrays.copyOf(snapshot.bytes(), snapshot.bytes().length);
        }

        private void pruneExpired() {
            long cutoff = System.currentTimeMillis() - SNAPSHOT_TTL_MS;
            snapshots.entrySet().removeIf(entry -> entry.getValue().createdAtMs() < cutoff);
        }
    }

    private record Snapshot(byte[] bytes, long createdAtMs) {
    }
}
