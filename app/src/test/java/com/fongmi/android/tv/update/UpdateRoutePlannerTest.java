package com.fongmi.android.tv.update;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public class UpdateRoutePlannerTest {

    private final OciArtifact artifact = new OciArtifact(
            "registry-1.docker.io",
            "fish2018/webhtv-apk",
            "v1-mobile-arm64_v8a",
            "sha256:" + "1".repeat(64),
            "sha256:" + "2".repeat(64),
            1024);
    private final GithubProxy.Config direct = GithubProxy.resolve(GithubProxy.DIRECT, "", GithubProxy.MODE_FULL_URL);

    @Test
    public void autoTriesOciThenGithub() {
        List<UpdateTarget> routes = UpdateRoutePlanner.plan(
                UpdateSource.AUTO,
                true,
                "https://github.com/fish2018/webhtv/releases/download/v1/app.apk",
                artifact,
                direct,
                "https://dockerproxy.net");
        assertEquals(2, routes.size());
        assertEquals(UpdateTarget.Kind.OCI, routes.get(0).kind);
        assertEquals(UpdateTarget.Kind.GITHUB, routes.get(1).kind);
    }

    @Test
    public void explicitGithubWithoutFallbackUsesOneRoute() {
        List<UpdateTarget> routes = UpdateRoutePlanner.plan(
                UpdateSource.GITHUB,
                false,
                "https://github.com/fish2018/webhtv/releases/download/v1/app.apk",
                artifact,
                direct,
                "https://dockerproxy.net");
        assertEquals(1, routes.size());
        assertEquals(UpdateTarget.Kind.GITHUB, routes.get(0).kind);
    }

    @Test
    public void autoWithoutFallbackUsesOnlyPreferredAvailableRoute() {
        List<UpdateTarget> routes = UpdateRoutePlanner.plan(
                UpdateSource.AUTO,
                false,
                "https://github.com/fish2018/webhtv/releases/download/v1/app.apk",
                artifact,
                direct,
                "https://dockerproxy.net");
        assertEquals(1, routes.size());
        assertEquals(UpdateTarget.Kind.OCI, routes.get(0).kind);
    }

    @Test
    public void missingOciMetadataFallsBackToGithub() {
        List<UpdateTarget> routes = UpdateRoutePlanner.plan(
                UpdateSource.OCI,
                true,
                "https://github.com/fish2018/webhtv/releases/download/v1/app.apk",
                null,
                direct,
                "https://dockerproxy.net");
        assertEquals(1, routes.size());
        assertEquals(UpdateTarget.Kind.GITHUB, routes.get(0).kind);
    }

    @Test
    public void explicitOciWithoutFallbackDoesNotUseGithubWhenMetadataIsMissing() {
        List<UpdateTarget> routes = UpdateRoutePlanner.plan(
                UpdateSource.OCI,
                false,
                "https://github.com/fish2018/webhtv/releases/download/v1/app.apk",
                null,
                direct,
                "https://dockerproxy.net");
        assertEquals(0, routes.size());
    }
}
