package com.fongmi.android.tv.player.exo;

import android.graphics.Bitmap;
import android.media.MediaFormat;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.VideoSink;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * Experimental Media3 sink for MediaCodec -> AImageReader -> Vulkan/libplacebo output.
 */
final class ExoDv5VideoSink implements VideoSink {

    static final int MAX_PENDING_FRAMES = 8;
    static final long EARLY_RELEASE_THRESHOLD_US = 50_000L;
    static final long DROP_THRESHOLD_US = -30_000L;
    static final int MAX_CONSECUTIVE_RENDER_FAILURES = 3;

    private final Queue<PendingFrame> pendingFrames;
    private Listener listener;
    private Executor listenerExecutor;
    private VideoFrameMetadataListener metadataListener;
    @Nullable private ExoDv5Native.NativeRenderer nativeRenderer;
    @Nullable private Surface outputSurface;
    private Format inputFormat;
    private long bufferTimestampAdjustmentUs;
    private boolean initialized;
    private boolean started;
    private boolean allowBeforeStarted;
    private boolean inputEnded;
    private boolean released;
    private boolean firstFrameRendered;
    private long observedRenderedFrames;
    private long observedRenderFailures;
    private int consecutiveRenderFailures;

    ExoDv5VideoSink() {
        pendingFrames = new ArrayDeque<>();
        listener = Listener.NO_OP;
        listenerExecutor = Runnable::run;
        metadataListener = (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {};
        inputFormat = new Format.Builder().build();
    }

    @Override
    public void startRendering() {
        started = true;
    }

    @Override
    public void stopRendering() {
        started = false;
    }

    @Override
    public void setListener(Listener listener, Executor executor) {
        this.listener = listener == null ? Listener.NO_OP : listener;
        this.listenerExecutor = executor == null ? Runnable::run : executor;
    }

    @Override
    public boolean initialize(Format sourceFormat) throws VideoSinkException {
        if (initialized) return true;
        if (released || sourceFormat == null || sourceFormat.width <= 0
                || sourceFormat.height <= 0
                || !ExoDv5GpuMappingPolicy.isProfile5(
                        sourceFormat.sampleMimeType, sourceFormat.codecs)
                || sourceFormat.cryptoType != C.CRYPTO_TYPE_NONE) {
            throw new VideoSinkException(
                    new IllegalArgumentException("unsupported DV5 Vulkan format"),
                    sourceFormat == null ? inputFormat : sourceFormat);
        }
        try {
            nativeRenderer = ExoDv5Native.create(
                    sourceFormat.width, sourceFormat.height);
            if (outputSurface != null) {
                nativeRenderer.setOutputSurface(outputSurface);
            }
            inputFormat = sourceFormat;
            initialized = true;
            inputEnded = false;
            return true;
        } catch (Throwable error) {
            throw new VideoSinkException(error, sourceFormat);
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void redraw() {
        // The swapchain retains the most recently presented frame.
    }

    @Override
    public void flush(boolean resetPosition) {
        skipPendingFrames(false);
        ExoDv5Native.NativeRenderer renderer = nativeRenderer;
        if (renderer != null) renderer.clearFrames();
        inputEnded = false;
        if (resetPosition) allowBeforeStarted = false;
        if (resetPosition) resetRenderObservation();
    }

    @Override
    public boolean isReady(boolean otherwiseReady) {
        return initialized && otherwiseReady;
    }

    @Override
    public void signalEndOfCurrentInputStream() {
        inputEnded = true;
    }

    @Override
    public void signalEndOfInput() {
        inputEnded = true;
    }

    @Override
    public boolean isEnded() {
        ExoDv5Native.NativeRenderer renderer = nativeRenderer;
        return inputEnded && pendingFrames.isEmpty()
                && (renderer == null || renderer.stats().pendingFrames() == 0);
    }

    @Override
    public Surface getInputSurface() {
        ExoDv5Native.NativeRenderer renderer = nativeRenderer;
        if (!initialized || renderer == null) {
            throw new IllegalStateException("DV5 sink is not initialized");
        }
        return renderer.inputSurface();
    }

    @Override
    public void setVideoFrameMetadataListener(
            VideoFrameMetadataListener videoFrameMetadataListener) {
        metadataListener = videoFrameMetadataListener == null
                ? (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {}
                : videoFrameMetadataListener;
    }

    @Override
    public void setPlaybackSpeed(float speed) {
        // The player position passed to render() is already speed adjusted.
    }

    @Override
    public void setVideoEffects(List<Effect> videoEffects) {
        if (videoEffects != null && !videoEffects.isEmpty()) {
            throw new UnsupportedOperationException("DV5 diagnostic sink has no effects");
        }
    }

    @Override
    public void setBufferTimestampAdjustmentUs(long bufferTimestampAdjustmentUs) {
        this.bufferTimestampAdjustmentUs = bufferTimestampAdjustmentUs;
    }

    @Override
    public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
        this.outputSurface = outputSurface;
        resetRenderObservation();
        ExoDv5Native.NativeRenderer renderer = nativeRenderer;
        if (renderer != null) renderer.setOutputSurface(outputSurface);
    }

    @Override
    public void clearOutputSurfaceInfo() {
        outputSurface = null;
        resetRenderObservation();
        ExoDv5Native.NativeRenderer renderer = nativeRenderer;
        if (renderer != null) renderer.setOutputSurface(null);
    }

    @Override
    public void setChangeFrameRateStrategy(int changeFrameRateStrategy) {
        // Surface frame-rate hints are left to the outer Media3 renderer.
    }

    @Override
    public void onInputStreamChanged(
            int inputType,
            Format format,
            long startPositionUs,
            int firstFrameReleaseInstruction,
            List<Effect> videoEffects) {
        if (inputType != INPUT_TYPE_SURFACE) {
            throw new IllegalArgumentException("DV5 sink requires Surface input");
        }
        setVideoEffects(videoEffects);
        inputFormat = format;
        inputEnded = false;
        listenerExecutor.execute(() -> listener.onVideoSizeChanged(
                new VideoSize(format.width, format.height)));
    }

    @Override
    public void allowReleaseFirstFrameBeforeStarted() {
        allowBeforeStarted = true;
    }

    @Override
    public boolean handleInputFrame(
            long bufferPresentationTimeUs, VideoFrameHandler videoFrameHandler) {
        if (!initialized || released || videoFrameHandler == null
                || pendingFrames.size() >= MAX_PENDING_FRAMES) {
            return false;
        }
        pendingFrames.add(new PendingFrame(
                bufferPresentationTimeUs, videoFrameHandler));
        listenerExecutor.execute(listener::onFrameAvailableForRendering);
        return true;
    }

    @Override
    public boolean handleInputBitmap(
            Bitmap inputBitmap, TimestampIterator bufferTimestampIterator) {
        return false;
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs)
            throws VideoSinkException {
        updateRenderObservation();
        PendingFrame frame = pendingFrames.peek();
        if (frame == null) return;
        long earlyUs = frame.presentationTimeUs() - positionUs;
        FrameAction action = frameAction(started, allowBeforeStarted, earlyUs);
        if (action == FrameAction.WAIT) return;
        pendingFrames.remove();
        if (action == FrameAction.DROP) {
            frame.handler().skip();
            listenerExecutor.execute(listener::onFrameDropped);
            return;
        }

        long releaseTimeNs = System.nanoTime() + Math.max(0, earlyUs) * 1_000L;
        ExoDv5Native.NativeRenderer renderer = nativeRenderer;
        long imageTimestampNs = imageTimestampNsFor(
                frame.presentationTimeUs() + bufferTimestampAdjustmentUs);
        if (renderer == null || !renderer.queueFrame(
                imageTimestampNs, frame.presentationTimeUs())) {
            frame.handler().skip();
            listenerExecutor.execute(listener::onFrameDropped);
            return;
        }
        long presentationTimeUs = frame.presentationTimeUs()
                + bufferTimestampAdjustmentUs;
        metadataListener.onVideoFrameAboutToBeRendered(
                presentationTimeUs,
                releaseTimeNs,
                inputFormat.buildUpon()
                        .setSampleMimeType(MimeTypes.VIDEO_RAW)
                        .build(),
                (MediaFormat) null);
        frame.handler().render(releaseTimeNs);
    }

    @Override
    public void join(boolean renderNextFrameImmediately) {
        if (renderNextFrameImmediately) allowBeforeStarted = true;
    }

    @Override
    public void release() {
        if (released) return;
        released = true;
        skipPendingFrames(false);
        ExoDv5Native.NativeRenderer renderer = nativeRenderer;
        nativeRenderer = null;
        if (renderer != null) renderer.close();
        outputSurface = null;
        initialized = false;
    }

    ExoDv5Native.Stats stats() {
        ExoDv5Native.NativeRenderer renderer = nativeRenderer;
        return renderer == null ? ExoDv5Native.Stats.empty() : renderer.stats();
    }

    void queueRpu(long presentationTimeUs, byte[] rpu) {
        ExoDv5Native.NativeRenderer renderer = nativeRenderer;
        if (renderer != null) renderer.queueRpu(presentationTimeUs, rpu);
    }

    static FrameAction frameAction(
            boolean started, boolean allowBeforeStarted, long earlyUs) {
        if (!started && !allowBeforeStarted) return FrameAction.WAIT;
        if (earlyUs > EARLY_RELEASE_THRESHOLD_US) return FrameAction.WAIT;
        if (earlyUs < DROP_THRESHOLD_US) return FrameAction.DROP;
        return FrameAction.RENDER;
    }

    static long imageTimestampNsFor(long presentationTimeUs) {
        if (presentationTimeUs > Long.MAX_VALUE / 1_000L) return Long.MAX_VALUE;
        if (presentationTimeUs < Long.MIN_VALUE / 1_000L) return Long.MIN_VALUE;
        return presentationTimeUs * 1_000L;
    }

    private void updateRenderObservation() throws VideoSinkException {
        ExoDv5Native.NativeRenderer renderer = nativeRenderer;
        if (renderer == null || outputSurface == null) return;
        ExoDv5Native.Stats stats = renderer.stats();
        long renderedDelta = Math.max(0,
                stats.renderedFrames() - observedRenderedFrames);
        long failureDelta = Math.max(0,
                stats.renderFailures() - observedRenderFailures);
        observedRenderedFrames = stats.renderedFrames();
        observedRenderFailures = stats.renderFailures();
        if (renderedDelta > 0) {
            consecutiveRenderFailures = 0;
            if (!firstFrameRendered) {
                firstFrameRendered = true;
                listenerExecutor.execute(listener::onFirstFrameRendered);
            }
        } else if (failureDelta > 0) {
            consecutiveRenderFailures = (int) Math.min(
                    MAX_CONSECUTIVE_RENDER_FAILURES,
                    consecutiveRenderFailures + failureDelta);
        }
        if (consecutiveRenderFailures >= MAX_CONSECUTIVE_RENDER_FAILURES) {
            throw new VideoSinkException(
                    new IllegalStateException("DV5 Vulkan rendering failed"),
                    inputFormat);
        }
    }

    private void resetRenderObservation() {
        firstFrameRendered = false;
        consecutiveRenderFailures = 0;
        ExoDv5Native.NativeRenderer renderer = nativeRenderer;
        ExoDv5Native.Stats stats = renderer == null
                ? ExoDv5Native.Stats.empty() : renderer.stats();
        observedRenderedFrames = stats.renderedFrames();
        observedRenderFailures = stats.renderFailures();
    }

    private void skipPendingFrames(boolean notify) {
        while (!pendingFrames.isEmpty()) {
            pendingFrames.remove().handler().skip();
            if (notify) listenerExecutor.execute(listener::onFrameDropped);
        }
    }

    enum FrameAction { WAIT, RENDER, DROP }

    private record PendingFrame(
            long presentationTimeUs, VideoFrameHandler handler) {
    }
}
