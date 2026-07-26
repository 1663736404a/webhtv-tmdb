package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackAutoContextStore;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Session-safe process state for the target bytes actually selected by automatic LoadControl. */
final class ExoTargetBufferCoordinator {

    private static final ExoTargetBufferCoordinator PROCESS =
            new ExoTargetBufferCoordinator(PlaybackAutoContextStore.process());

    private final PlaybackAutoContextStore store;
    private final AtomicReference<State> state;

    ExoTargetBufferCoordinator(PlaybackAutoContextStore store) {
        this.store = Objects.requireNonNull(store);
        this.state = new AtomicReference<>(State.empty());
    }

    static ExoTargetBufferCoordinator process() {
        return PROCESS;
    }

    boolean publish(
            PlaybackAutoContext.SessionToken session,
            ExoTargetBufferPolicy.Decision decision,
            long publishedAtElapsedMs) {
        if (session == null || !session.active() || decision == null) return false;
        if (!isCurrentExoSession(session)) return false;
        State next = new State(session, decision, Math.max(0, publishedAtElapsedMs));
        state.set(next);
        if (isCurrentExoSession(session)) return true;
        state.compareAndSet(next, State.empty());
        return false;
    }

    ExoTargetBufferPolicy.Decision currentDecision() {
        PlaybackAutoContext.SessionToken session = store.snapshot().session();
        return currentDecision(session);
    }

    ExoTargetBufferPolicy.Decision currentDecision(PlaybackAutoContext.SessionToken session) {
        State current = state.get();
        if (session == null || !session.active() || !session.equals(current.session())) return null;
        if (!isCurrentExoSession(session)) return null;
        return current.decision();
    }

    int currentTargetBytesOr(int fallbackBytes) {
        ExoTargetBufferPolicy.Decision decision = currentDecision();
        return decision == null ? fallbackBytes : decision.targetBytes();
    }

    private boolean isCurrentExoSession(PlaybackAutoContext.SessionToken session) {
        PlaybackAutoContext context = store.snapshot();
        return session.equals(context.session())
                && (!context.kernel().hasValue()
                || context.kernel().value() == PlaybackAutoContext.Kernel.EXO);
    }

    private record State(
            PlaybackAutoContext.SessionToken session,
            ExoTargetBufferPolicy.Decision decision,
            long publishedAtElapsedMs) {

        private static State empty() {
            return new State(PlaybackAutoContext.SessionToken.none(), null, -1);
        }
    }
}
