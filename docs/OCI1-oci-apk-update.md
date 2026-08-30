# OCI1: OCI APK update source

## Recovery anchor

- Objective: publish each release APK as an OCI artifact and let Android update through OCI Registry mirrors without Docker or an ORAS binary on-device.
- Authority: implementation approved by the user on 2026-08-31.
- Branch / baseline: `dev` / `332f8b26c89e69d19f287b1d911a780826149619`.
- Lane / scope: `upstream`; task guard `OCI1-oci-apk-update` owns the paths declared at guard start.
- Protected pre-existing dirty paths: `app/.cxx/` and all paths recorded by the task guard.
- Acceptance: GitHub discovery remains available; download routes are `auto`, `github`, and `oci`; `auto` tries OCI then GitHub; OCI verifies manifest and layer digests, APK identity and signer; progress, cancel, fallback, and mobile/leanback settings work.
- Rollback anchor: baseline commit above. Published clients can be moved back to GitHub by omitting the `downloads.oci` object from a later update manifest.
- Current status: implementation and targeted verification complete; pending atomic commit and local recovery tag.
- Next action: finish task guard with the recorded verification evidence.

## Product decision

CNB is not part of the new download route because releases are no longer being published there. Existing CNB helpers may remain for unrelated legacy consumers, but update discovery and APK transfer must not depend on CNB.

The version track and transport source are independent:

- Track: `stable` or `beta`.
- Source: `auto`, `github`, or `oci`.
- `auto`: OCI first when a valid OCI descriptor exists, then GitHub.
- Explicit `github`: GitHub first, then OCI when fallback is enabled.
- Explicit `oci`: OCI first, then GitHub when fallback is enabled.

The existing update JSON remains the discovery and release-notes control plane. Legacy `apk`, `size`, and `sha256` fields remain so older clients continue to update.

## Best-practice review

Decision question: should WebHTV embed an upstream ORAS implementation or implement the narrow OCI Distribution pull flow required for one APK layer?

Hypothesis: a narrow HTTP implementation is safer and smaller on Android because the app only needs anonymous/public manifest and blob pulls, while official ORAS remains appropriate in CI for publishing.

Counter-hypothesis: embedding an ORAS binary or general client library reduces protocol implementation work enough to justify its size, native packaging, maintenance, and security surface.

### Evidence

| Claim | Source | Grade | Applicability and decision impact |
| --- | --- | --- | --- |
| Registry pulls are manifest and blob HTTP operations with standard digest descriptors | OCI Distribution Specification, `https://github.com/opencontainers/distribution-spec/blob/main/spec.md`, accessed 2026-08-31 | A | Supports a narrow Android client instead of a Docker/ORAS runtime |
| An OCI image manifest can describe an artifact type and one APK layer | OCI Image Manifest Specification, `https://github.com/opencontainers/image-spec/blob/main/manifest.md`, accessed 2026-08-31 | A | Defines the exact accepted manifest shape |
| ORAS supports arbitrary artifact media types and is the maintained publishing tool | ORAS documentation, `https://oras.land/docs/how_to_guides/pushing_and_pulling`, accessed 2026-08-31 | A | Use official ORAS in CI; do not reimplement push in the app |
| Public registries commonly use a `WWW-Authenticate: Bearer` token exchange | Distribution token authentication specification, `https://distribution.github.io/distribution/spec/auth/token/`, accessed 2026-08-31 | A | Android client must parse and strictly scope bearer challenges |
| APK identity and signing information are available through Android `PackageManager` / `SigningInfo` | Android SDK API and current WebHTV package validation path, accessed 2026-08-31 | A | Final downloaded bytes must be checked against the installed app identity and signer |
| Content-addressed artifacts still need a trusted root digest | OCI descriptors plus WebHTV update JSON data flow | A/inference | The manifest digest from the update control plane must be verified before parsing proxy content |
| `dockerproxy.net` served a 490,640,394-byte layer in 24.94 seconds and the SHA-256 matched | Reproducible 2026-08-30 proxy test retained in task context | A field test | Suitable first built-in mirror; Range cannot be required because this blob returned 200 |
| `docker.1panel.live`, `docker.jiaxin.site`, `free.hubfast.cn`, and `proxy.vvvv.ee` accepted non-image artifact media types with materially lower or variable speed | Reproducible 2026-08-30 proxy tests retained in task context | A field test | Only qualified alternatives should be presets; availability is not a security assertion |
| `dockerproxy.com` redirected to `dockerproxy.net` and direct TLS was unreliable | Reproducible 2026-08-30 proxy test | A field test | Canonicalize the preset to `.net`; do not ship duplicate `.com` entry |

Applicable source classes covered: exact specifications, official project documentation, current WebHTV code, mature ORAS implementation behavior, and reproducible field tests. Academic papers are inapplicable because this decision implements a standardized content-addressed transport rather than an algorithmic or performance technique. No private-registry implementation is included because credentials must not be embedded in the APK.

## Alternatives

### No change

Keep GitHub APK URLs only. This has the lowest implementation risk but does not solve unreliable or slow domestic GitHub downloads.

### Embed ORAS or a general OCI client

This closely follows upstream tooling but introduces a broad dependency/native binary, more media/platform behavior than the app needs, additional package size, and a larger update-time security surface.

### WebHTV-adapted design (selected)

Use official ORAS CLI only in GitHub Actions. Implement manifest authentication, validation, and one blob stream with the existing OkHttp dependency on Android. Keep routing, progress, cancellation, APK validation, and UI in WebHTV-owned code.

## Release representation

- Manifest media type: `application/vnd.oci.image.manifest.v1+json`.
- Artifact type: `application/vnd.webhtv.apk.v1`.
- Config media type: `application/vnd.oci.empty.v1+json`.
- Exactly one layer with media type `application/vnd.android.package-archive`.
- One artifact per mode/ABI APK. OCI indexes are intentionally deferred.
- Tag: `<release-tag>-<mode>-<abi>`; clients fetch and pin by manifest digest.

The release workflow reads optional repository configuration from `vars.OCI_REPOSITORY` and credentials from `secrets.OCI_USERNAME` / `secrets.OCI_TOKEN`. Missing OCI configuration is fail-open for the existing GitHub release: no invalid OCI metadata is emitted. Once an OCI push succeeds, descriptor verification is fail-closed before it can enter update JSON.

## Update manifest extension

```json
{
  "apk": "https://github.com/.../mobile-arm64_v8a.apk",
  "size": 12345678,
  "sha256": "hex",
  "downloads": {
    "github": { "url": "https://github.com/.../mobile-arm64_v8a.apk" },
    "oci": {
      "registry": "registry-1.docker.io",
      "repository": "owner/webhtv-apk",
      "reference": "v1.2.3-202608310030-mobile-arm64_v8a",
      "manifestDigest": "sha256:hex",
      "layerDigest": "sha256:hex",
      "size": 12345678
    }
  }
}
```

The layer digest must equal the SHA-256 of the APK bytes. The client accepts legacy manifests without `downloads`, using the legacy GitHub `apk` URL.

## Android design

New `com.fongmi.android.tv.update` classes own:

- immutable direct/OCI download targets;
- source preference and route ordering;
- GitHub proxy URL rewriting with explicit full-URL-prefix or strip-scheme modes;
- OCI endpoint normalization and built-in mirror presets;
- a dedicated platform-TLS OkHttp client;
- bearer challenge parsing, manifest validation, and blob streaming;
- a cancellable transfer interface shared by direct and OCI downloads.

`Updater` remains the lifecycle and UI orchestrator. It receives an ordered route list, starts one transfer, preserves progress/cancel behavior, validates the downloaded APK, and tries the next route once on failure.

## Security contracts

- HTTPS is required by default. Custom endpoints cannot contain credentials, query strings, or fragments.
- OCI requests use a dedicated OkHttp client with platform trust. The shared trust-all client is prohibited.
- Manifest requests use `Accept-Encoding: identity` and an exact OCI manifest `Accept` value.
- Raw manifest bytes are SHA-256 verified against the update JSON before JSON parsing.
- Only schema version 2, the expected manifest/artifact media types, and exactly one expected APK layer are accepted.
- Descriptor size/digest and streamed APK size/digest are verified.
- Authentication credentials/tokens are scoped to the exact registry request. Cross-origin redirects never carry `Authorization`.
- Bearer realm URLs must be HTTPS and must match an explicitly allowed authentication host rule.
- No private Registry credential is stored in the app.
- APK package name, version code/name where available, and signing certificate lineage are checked before opening the installer.
- A Range request returning 200 discards partial state and restarts. Resume is deferred from the first implementation because mirror behavior is inconsistent.

Third-party GitHub proxies terminate TLS and can alter unsigned metadata. This implementation therefore applies them to GitHub asset URLs; direct GitHub API/update-manifest discovery remains the trusted default until detached update-manifest signing is introduced as a separate, explicitly reviewed security stage.

## Built-in endpoints

Initial OCI presets:

1. `https://dockerproxy.net` (default).
2. `https://free.hubfast.cn`.
3. `https://docker.jiaxin.site`.

`dockerproxy.com`, endpoints with certificate failures, internal-only DNS, whitelist responses, HTML responses, or confirmed unusable throughput are excluded. Presets are availability hints only; all bytes remain untrusted until digest and APK signature verification succeeds.

GitHub proxy presets retain an explicit rewrite mode. Custom proxy input must pass HTTPS and URL-shape validation before persistence.

## Verification plan

Focused JVM tests with MockWebServer:

- direct and proxied GitHub URL construction;
- route ordering and fallback settings;
- OCI anonymous manifest/blob flow;
- bearer challenge/token retry;
- bad realm, cross-origin redirect, digest mismatch, wrong media type, multiple layers, wrong size, and canceled stream;
- a Range response returning 200 is not treated as a resumed response when resume is later enabled.

Build checks:

- focused update unit tests;
- compile mobile ARM64 debug Java;
- compile leanback ARM64 debug Java.

Device checks:

- open update settings on mobile/leanback-compatible UI;
- select OCI and a mirror, then cancel a real download;
- verify OCI-to-GitHub fallback using a deliberately invalid mirror;
- download a valid APK, verify it, and open the package installer;
- inspect logcat for crashes, leaked credentials, cleartext traffic, and lifecycle errors.

## Rollout and rollback

1. Publish OCI metadata for beta releases only.
2. Ship the client with `auto` defaulting to OCI then GitHub, while explicit GitHub remains available.
3. Promote OCI metadata to stable after one beta cycle and device/network evidence.
4. Operational rollback: omit `downloads.oci` from a later update JSON; all clients use GitHub.
5. Code rollback: revert the atomic task commit/recovery tag; release artifacts and legacy JSON fields remain compatible.

## Implementation checkpoint 0: 2026-08-31 00:30 CST

- Completed: repository/branch/dirty-state recovery, protocol and product decision, proxy evidence consolidation, task guard start.
- Source identities: WebHTV `332f8b26c89e69d19f287b1d911a780826149619`.
- Workspace: `dev`; protected pre-existing `app/.cxx/` paths.
- Validation: assessment only; no code changed before this document.
- Rollback: baseline commit above.
- Unresolved: live OCI publishing requires repository variables/secrets not present in source control.
- Next action: implement release metadata and Android transport types.

## Implementation checkpoint 1: 2026-08-31 01:15 CST

- Completed: optional ORAS release publishing, OCI metadata emission, Android OCI/GitHub routing, independent proxy settings, progress/cancel/fallback, APK identity and signer validation, update settings UI, and focused tests. CNB was removed from update discovery and APK transfer; the optional legacy workflow input remains disabled by default.
- Source identity: ORAS `v1.3.4` resolves to `db9e29505c3059f2b8fde34ae8cae266c5c765e9` (annotated tag object `2f11c9ec2d4816bf0a7a709f7a51ed5ca5d2d5c5`).
- OCI representation: ORAS 1.3.4 was exercised against a local OCI layout. The generated artifact used manifest `application/vnd.oci.image.manifest.v1+json`, artifact type `application/vnd.webhtv.apk.v1`, config `application/vnd.oci.empty.v1+json`, and one `application/vnd.android.package-archive` layer. Verified test manifest digest: `sha256:fdfc0f6efbedf87b00d9a9d2417e8c6e109be6ffdd4bf33d7274e09104d3a221`.
- Validation: `:app:testMobileArm64_v8aDebugUnitTest --tests 'com.fongmi.android.tv.update.*'`, mobile ARM64 Java compilation, leanback ARM64 Java compilation, and `:app:assembleMobileArm64_v8aDebug` passed. After the final authentication hardening, the complete update test package passed, followed by focused `OciRegistryClientTest` coverage for a redirected Bearer challenge and rejection of another repository's scope. A final `UpdateRoutePlannerTest` pass verifies that explicit OCI mode never silently uses GitHub when fallback is disabled.
- Release checks: `bash -n .github/scripts/publish-oci-apks.sh` passed; missing OCI configuration exited successfully and wrote exactly `{}`; `.github/workflows/android-release.yml` parsed as YAML; the Android update path contains no CNB reference.
- Artifact: `app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk`, 144 MB, SHA-256 `413d2d2b34533638d850bd8d69ac8d647fe49373cd88df5d48640d2bd1734bc8`.
- Device limitation: no ADB device was connected, `adb connect 192.168.1.9:5555` was refused, and no emulator command was available. Installer UI, live mirror transfer, cancellation, and real fallback remain beta rollout checks rather than locally verified claims.
- Workspace: branch `dev`, baseline `332f8b26c89e69d19f287b1d911a780826149619`; protected pre-existing `app/.cxx/` content remains unchanged. ARM64 caches generated by task builds were moved to `/private/tmp/webhtv-OCI1-cxx-9aTdgo` instead of being committed or deleting pre-existing data.
- Rollback: revert the atomic task commit or omit `downloads.oci` from update JSON. Legacy GitHub fields remain sufficient for older and rolled-back clients.
- Unresolved: live authenticated OCI publishing and device behavior require repository credentials plus a reachable test device; neither blocks the source-controlled beta-capable implementation.
- Next action: run the final scoped diff/safety check, then create the atomic commit and annotated local recovery tag.
