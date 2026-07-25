package com.fongmi.android.tv.player;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackAutoContextStoreTest {

    @Test
    public void newSessionAtomicallyReplacesFactsAndRejectsOldUpdates() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken first = store.beginSession("p-abc-1", 100);
        PlaybackAutoContext.Fact<PlaybackAutoContext.Kernel> mpv = kernel(PlaybackAutoContext.Kernel.MPV, 110);
        assertTrue(store.publishPlaybackFacts(first, mpv, decode(true, 110), path("https://cdn.example.com/a"), 110));
        PlaybackAutoContext before = store.snapshot();

        PlaybackAutoContext.SessionToken second = store.beginSession("p-abc-2", 200);
        PlaybackAutoContext after = store.snapshot();

        assertTrue(second.active());
        assertTrue(second.generation() > first.generation());
        assertEquals(first, before.session());
        assertEquals(second, after.session());
        assertEquals(PlaybackAutoContext.Kernel.UNKNOWN, after.kernel().value());
        assertEquals(0, after.revision());
        assertFalse(store.publishDeviceFacts(first, PlaybackAutoContext.DeviceFacts.unknown(), 210));
        assertFalse(store.clear(first));
        assertEquals(second, store.snapshot().session());
    }

    @Test
    public void clearOnlyRemovesTheCurrentSession() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken session = store.beginSession("p-abc-3", 300);

        assertTrue(store.clear(session));
        assertFalse(store.snapshot().active());
        assertFalse(store.clear(session));
    }

    @Test
    public void publishingCreatesANewImmutableSnapshot() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken session = store.beginSession("p-abc-immutable", 350);
        PlaybackAutoContext before = store.snapshot();

        assertTrue(store.publishPathFacts(session, path("https://cdn.example.com/a"), 360));
        PlaybackAutoContext after = store.snapshot();

        assertEquals(0, before.revision());
        assertEquals(PlaybackRoute.OTHER, before.path().route().value());
        assertEquals(1, after.revision());
        assertEquals(PlaybackRoute.DIRECT_REMOTE_HTTP, after.path().route().value());
    }

    @Test
    public void concurrentPublishersKeepAllCategoriesAndUseMonotonicRevisions() throws Exception {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken session = store.beginSession("p-abc-4", 400);
        int iterations = 120;
        int writers = 3;
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writers + 1);
        List<Future<?>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> {
            await(start);
            for (int i = 0; i < iterations; i++) {
                if (store.publishPathFacts(session, path(i % 2 == 0 ? "https://cdn.example.com/a" : "http://127.0.0.1:7777/a"), 500 + i)) accepted.incrementAndGet();
            }
        }));
        futures.add(executor.submit(() -> {
            await(start);
            for (int i = 0; i < iterations; i++) {
                PlaybackAutoContext.RuntimeFacts runtime = new PlaybackAutoContext.RuntimeFacts(
                        PlaybackAutoContext.Fact.forSession(PlaybackAutoContext.PlaybackPhase.READY, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH, 500 + i),
                        PlaybackAutoContext.Fact.unknown(0L),
                        PlaybackAutoContext.Fact.unknown(0L),
                        PlaybackAutoContext.Fact.unknown(0L),
                        PlaybackAutoContext.Fact.unknown(0f),
                        PlaybackAutoContext.Fact.unknown(0L),
                        PlaybackAutoContext.Fact.unknown(0));
                if (store.publishRuntimeFacts(session, runtime, 500 + i)) accepted.incrementAndGet();
            }
        }));
        futures.add(executor.submit(() -> {
            await(start);
            for (int i = 0; i < iterations; i++) {
                if (store.publishDeviceFacts(session, PlaybackAutoContext.DeviceFacts.unknown(), 500 + i)) accepted.incrementAndGet();
            }
        }));
        futures.add(executor.submit(() -> {
            await(start);
            long previousRevision = -1;
            for (int i = 0; i < iterations * 3; i++) {
                PlaybackAutoContext snapshot = store.snapshot();
                if (!session.equals(snapshot.session()) || snapshot.revision() < previousRevision) {
                    throw new AssertionError("snapshot regressed");
                }
                previousRevision = snapshot.revision();
            }
        }));
        start.countDown();
        for (Future<?> future : futures) future.get();
        executor.shutdownNow();

        PlaybackAutoContext snapshot = store.snapshot();
        assertEquals(iterations * writers, accepted.get());
        assertEquals(accepted.get(), snapshot.revision());
        assertTrue(snapshot.path().route().value() == PlaybackRoute.DIRECT_REMOTE_HTTP
                || snapshot.path().route().value() == PlaybackRoute.EXTERNAL_LOOPBACK_PROXY);
        assertEquals(PlaybackAutoContext.PlaybackPhase.READY, snapshot.runtime().phase().value());
    }

    private static PlaybackAutoContext.Fact<PlaybackAutoContext.Kernel> kernel(PlaybackAutoContext.Kernel kernel, long sampledAt) {
        return PlaybackAutoContext.Fact.forSession(kernel, PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH, sampledAt);
    }

    private static PlaybackAutoContext.Fact<PlaybackAutoContext.DecodeMode> decode(boolean hardware, long sampledAt) {
        return PlaybackAutoContext.Fact.forSession(hardware ? PlaybackAutoContext.DecodeMode.HARDWARE : PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH, sampledAt);
    }

    private static PlaybackAutoContext.PathFacts path(String url) {
        return PlaybackAutoContext.PathFacts.fromResolution(PlaybackRoute.resolve(url), 500);
    }

    private static void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
