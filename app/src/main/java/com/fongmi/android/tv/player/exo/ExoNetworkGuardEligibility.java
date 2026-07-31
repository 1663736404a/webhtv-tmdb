package com.fongmi.android.tv.player.exo;

/** Ensures network protection never changes an output feature selected by the user. */
public final class ExoNetworkGuardEligibility {

    private ExoNetworkGuardEligibility() {
    }

    public static Decision resolve(Request request) {
        if (request == null || !request.enabled()) return Decision.blocked("disabled");
        if (!request.userOptIn()) return Decision.blocked("opt-in-required");
        if (!request.exo()) return Decision.blocked("exo-only");
        if (!request.vod()) return Decision.blocked("vod-only");
        if (!request.userUnitSpeed()) return Decision.blocked("user-speed");
        if (!request.speedCommandAvailable()) return Decision.blocked("speed-unsupported");
        if (request.tunnelingRequested()) return Decision.blocked("preserve-tunneling");
        if (request.audioPassthroughRequested()) return Decision.blocked("preserve-passthrough");
        if (request.resourceMode() == ExoNetworkResourcePolicy.Mode.ADAPTIVE_VARIANTS) {
            return Decision.blocked("prefer-abr");
        }
        if (request.resourceMode() == ExoNetworkResourcePolicy.Mode.UNKNOWN) {
            return Decision.blocked("resource-unknown");
        }
        if (!request.resourceMode().singleRate()) {
            return Decision.blocked("single-rate-only");
        }
        return new Decision(true, "eligible");
    }

    public record Request(
            boolean enabled,
            boolean userOptIn,
            boolean exo,
            boolean vod,
            boolean userUnitSpeed,
            boolean speedCommandAvailable,
            boolean tunnelingRequested,
            boolean audioPassthroughRequested,
            ExoNetworkResourcePolicy.Mode resourceMode) {

        public Request {
            resourceMode = resourceMode == null
                    ? ExoNetworkResourcePolicy.Mode.UNKNOWN : resourceMode;
        }
    }

    public record Decision(boolean eligible, String reason) {

        private static Decision blocked(String reason) {
            return new Decision(false, reason);
        }
    }
}
