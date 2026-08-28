# P1：MPV 格式与 shader correctness

## Recovery anchor

- Objective: decide whether the first MPV native rebuild should add the four low-risk correctness topics already identified in master assessment checkpoint 42.4.
- Acceptance: every in-scope upstream commit has a full-identity disposition; current WebHTV native, Vulkan, Matroska, HLS, subtitle/OSD, ELF, and rollback contracts are recorded; alternatives and approval gates are explicit; no production source, lock, patch, JNI, or asset is changed before approval.
- Status: assessment complete; implementation pending explicit user approval.
- Task ID/lane: `P1-MPV-FORMAT-SHADER-CORRECTNESS` / `assessment`.
- Workspace: branch `fongmi-sync`, HEAD `98f872eed700213ce03345d7d20d794c8ec4123a`.
- Protected pre-existing dirty paths: `.codex/scripts/task_guard.sh`, `AGENTS.md`, `docs/agents-md-effective-constraints-review-2026-08-21.md`.
- Next action: obtain approval for the proposed P1 source/lock/artifact stage, then start a new `upstream` guard session with the approved scope.

## 1. Decision packet

### Question

Should WebHTV's first controlled MPV native rebuild add packed 10-bit RGB format identity, Matroska EBML default handling, HLS program-level edition selection, and libplacebo alpha preservation, while retaining the existing FFmpeg namespace, Vulkan/AImageReader, Dolby Vision, Matroska seek, AudioTrack, OSD, and two-ABI contracts?

Current hypothesis: these are narrow correctness fixes with visible input or rendering value and no App/JNI API change. Counter-hypothesis: one or more changes may be incompatible with WebHTV's generated Matroska descriptors, program metadata, packed-format interpretation, or transparent OSD/shader behavior and therefore should be adapted or deferred.

### Scope and authority

- Assessment-only changes: this document and the P1 row/recovery text in `docs/upstream-player-dependency-merge-assessment-2026-08-20.md`.
- Candidate repositories: `FongMi/mpv` and `FongMi/libplacebo`; source range and full commit identities are listed below.
- Implementation scope, if approved later: `third_party/mpv-native-lock.json`, the corresponding MPV/libplacebo source/patch inputs, both ARM native asset sets, and only the tests/build records required by the approved substage. `libplayer.so`, App Java/Kotlin APIs, and Exo AARs are excluded.
- No lock update, source checkout migration, native rebuild, APK publication, push, or local production patch is authorized by this assessment.

## 2. Complete candidate ledger

| Substage | Full commit | Disposition | Decision boundary |
| --- | --- | --- | --- |
| P1-1 packed RGB10 RA | `7b8915bc1d04c7e1b61184e00c7fbfaab1911e75` | candidate; recommend narrow merge | `video/out/placebo/ra_pl.c`; add `X2BGR10`/`X2RGB10` special format identity only |
| P1-2 EBML generator prerequisite | `52bb166f309c8bb55ab34b2b0bc5c8ead05370e4` | dependency-only within P1-2; do not release alone | `TOOLS/matroska.py`; required so generated descriptors can carry defaults |
| P1-2 EBML defaults | `e167836802da6d5a4301bd4c4eeb3c5c3c17ccb8` | candidate; recommend narrow merge with `52bb...` | `TOOLS/matroska.py`, `demux/ebml.c`, `demux/ebml.h`; zero-length values use EBML/RFC/context defaults |
| P1-3 HLS edition | `e7191f2a65d64af266c5c80793e79d2f4b92b789` | candidate; recommend merge with metadata fallback gate | `demux/demux_lavf.c`; select by program `variant_bitrate` before any compatibility fallback |
| P1-4 libplacebo alpha | `22ee762e8e0890fc54068beb670310f0edce7263` | candidate; recommend搭载 | `pl_shader_extract_features()` preserves extracted alpha instead of forcing `1.0` |

The master assessment records the parent/tree and patch-id evidence for all five commits (checkpoint 34 and checkpoint 42.4). The two EBML commits are one implementation unit; the generator prerequisite has no independent runtime value.

## 3. Current WebHTV baseline and contracts

- Native graph is pinned by `third_party/mpv-native-lock.json`: MPV `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`, FFmpeg `04482c8d13ac27b2a9fe93f5d388929eef8af5f4`, libplacebo `b694a21bf2dc176c1e98b8a13c6421a0de5f3da5`, mpv-android `99a60ad2141d5ace94453590903c2c6b9a0a2443`, NDK r29/API 24, and both `arm64-v8a`/`armeabi-v7a` assets.
- MPV FFmpeg libraries use the `libmv*`/`libmw*` SONAME and `DT_NEEDED` namespace; Exo keeps separate `libav*`/`libsw*` assets. Any approved P1 rebuild must rebuild the coherent graph and preserve this separation.
- Existing local behavior that must remain: `mpv-matroska-segment-end.patch` for seek metadata, `mpv-dovi-profile7-hdr10-base-layer.patch` and DV BlockAdditional handling, `mpv-android-vulkan-{conversion-default,smart-backend,legacy-backend}.patch`, the stable AImageReader override/fence ownership, optional OSD and timestamped MediaCodec release, TrueHD 7.1 workaround, FFmpeg starvation/Range patches, and the ten-library per-ABI packaging contract.
- P0 verification has already passed once at HEAD: `bash scripts/verify_mpv_native_assets.sh --require-elf` checked both ABIs, NDK r29 `llvm-readelf`, shader contract, markers, SONAME/`DT_NEEDED`, and package contents. That result is the baseline, not proof that P1 behavior is implemented.
- App already exposes `hls-bitrate`/automatic bitrate policy and consumes Matroska track/chapter/metadata and subtitle/OSD output. P1 does not change these APIs; it changes native parsing/selection/format identity beneath them.

## 4. Substage decisions

### P1-1: packed RGB10 RA identity

The locked libplacebo API already describes Vulkan `rgb10a2`/`bgr10a2`; the gap is mpv's libplacebo RA wrapper not recognizing packed `IMGFMT_X2BGR10` and `IMGFMT_X2RGB10` as special formats. The proposed hunk adds one-plane RGB descriptors with explicit channel order.

Recommendation: merge as a narrow mpv change, only in the first controlled native rebuild. It should be exercised through Vulkan direct, stable, generic/conversion, and automatic fallback, then compared with OpenGL `gpu-next`. The key failure mode is red/blue reversal or HDR gradient distortion on little-endian packed layouts. No local Vulkan backend or shader ownership patch may be removed.

### P1-2: EBML zero-length/default semantics

The current parser rejects or empties some legal zero-length EBML elements. The upstream pair adds generated `context_default` descriptors and parser handling so explicit zero length, missing element, and explicit non-zero value remain distinguishable. Context-derived `DisplayWidth`/`DisplayHeight` and `OutputSamplingFrequency` must continue to use existing demux fallback rather than being written as zero.

Recommendation: merge both commits as one unit. The fixture must cover header defaults, `TimecodeScale`, track flags/language/lacing, display dimensions, sampling/channels, colour, `BlockAddID`/DV, content encoding, chapters, and tags, each in missing/zero-length/explicit forms. Keep the local segment-end seek and DV BlockAdditional paths intact. A failure rolls back only P1-2.

### P1-3: HLS program-level edition selection

The App already sets `hls-bitrate`; the current native selection can read a per-stream bitrate that is absent or misleading when variants share audio/subtitle groups. The candidate reads FFmpeg's program-level `variant_bitrate` metadata and ignores empty programs.

Recommendation: merge after recording actual FFmpeg 9.0-fongmi behavior when program metadata is absent. The default rule is program metadata first; only if the complete program set lacks usable metadata may a WebHTV-adapted stream fallback be added. Preserve explicit edition selection and `flatten-editions`. Validate threshold boundaries, shared groups, audio-only and empty programs, invalid/missing metadata, and dynamic App reload from 15 Mbps to 8 Mbps.

### P1-4: libplacebo alpha preservation

The candidate changes `pl_shader_extract_features()` so alpha extracted from the shader feature set is not overwritten with `1.0`. This is a small shader correctness change relevant to transparent subtitle/OSD overlays and screenshots; it does not change libplacebo API level.

Recommendation:搭载 with P1, but keep its source hunk and test evidence independently reversible. Validate transparent OSD/subtitle compositing, alpha overlays, HDR shader paths, screenshots, and both OpenGL and Vulkan output. A regression must not be “fixed” by disabling alpha globally.

## 5. Best-practice evidence

The mandatory research was completed in the master assessment checkpoints 5, 34, 42, and the upstream-integration-governor evidence record. It was not repeated because source heads and local contracts are unchanged.

| Claim | Source/revision | Grade | WebHTV applicability and impact |
| --- | --- | --- | --- |
| The five commits are real endpoint-tree deltas, not merely rebase duplicates | `docs/upstream-player-dependency-merge-assessment-2026-08-20.md`, checkpoints 5.2, 34, 42; full hashes above; accessed 2026-08-28 | A | Supports a narrow P1 stage while leaving covered API-375 and Android GPU behavior untouched |
| MPV contribution changes should be split, tested, and accompanied by disclosed test scope | `https://github.com/mpv-player/mpv/blob/master/DOCS/contribute.md`, current `master`, accessed 2026-08-28 | B | Requires four independently attributable substages and no claim beyond the selected playback matrix |
| FFmpeg/container changes need deterministic regression coverage | `https://ffmpeg.org/developer.html` and `https://ffmpeg.org/fate.html`, accessed 2026-08-28 | A/B | Drives synthetic EBML fixtures and explicit HLS/Matroska regression cases rather than a build-only gate |
| Android behavior requires tests matched to the affected behavior and device path | `https://developer.android.com/studio/test`, accessed 2026-08-28 | A | Packed RGB/alpha and native selection require APK/device evidence; verifier output alone is insufficient |
| Native artifacts need reconstructable source/toolchain/provenance and ABI identity | `https://slsa.dev/spec/v1.0/` and current `third_party/mpv-native-build.md`, accessed 2026-08-28 | A | Any implementation must rebuild both ABIs from the declared lock, preserve SONAME/DT_NEEDED, and retain artifact hashes |

Inapplicable categories: no new App API, JNI contract, or security boundary is proposed in P1, so no separate API migration or threat-model stage is needed. Device-specific rendering and HLS metadata uncertainty remain implementation gates, not assumptions.

## 6. Alternatives

| Alternative | Result |
| --- | --- |
| No change | Keeps the known-good native assets, but leaves legal EBML zero-length inputs, packed RGB10 identity, program-level HLS selection, and alpha extraction gaps unresolved. Acceptable only if no P1 rebuild is approved. |
| Adopt the upstream commits/tree wholesale | Rejected. It risks dropping WebHTV's Vulkan/AImageReader ownership, DV7 packet/Surface safeguards, Matroska segment seek, FFmpeg namespace, and local AudioTrack/OSD behavior. |
| Narrow WebHTV-adapted P1 | Recommended. Carry the five commits as four independently testable source units, add the HLS metadata fallback only if evidence requires it, and preserve the current native graph and rollback boundaries. |

## 7. Acceptance and rollback

Before implementation approval, acceptance criteria are:

1. User explicitly approves the P1 source/lock/artifact stage; no code is changed before that approval.
2. P1 rebuild uses one declared FFmpeg revision for MPV, reapplies all MPV-specific patches, and rebuilds both ARM ABIs as a coherent graph. `libplayer.so` remains unchanged unless a later API diff proves otherwise.
3. `scripts/verify_mpv_native_assets.sh --require-elf` passes once for the candidate assets, including version markers, shader contract, SONAME/`DT_NEEDED`, static dependency rules, and package manifest.
4. P1-1 passes packed RGB10 red/green/blue order, 10-bit gradient, HDR/LUT, Vulkan direct/stable/generic/auto, and OpenGL comparison checks.
5. P1-2 passes missing/zero-length/explicit EBML fixtures, DAR/timebase/audio/chapter/tag behavior, DV `BlockAddID`, segment-end seek, and subtitle/track selection checks.
6. P1-3 passes shared audio/subtitle groups, audio-only/empty programs, metadata missing/invalid cases, bitrate threshold boundaries, explicit edition, `flatten-editions`, and dynamic reload checks.
7. P1-4 passes transparent subtitle/OSD/overlay and HDR shader screenshot checks on both rendering families.
8. Lock, patch order, source identities, artifact hashes, device/API/GPU/settings, logs, and per-substage results are recorded before commit/tag.

Rollback is one native candidate rollback to the P0 asset/lock state. Within the candidate source tree, remove only the failing P1 hunk/unit: P1-1 RA mapping, P1-2 both EBML commits, P1-3 edition selection/fallback, or P1-4 alpha extraction. Never roll back by deleting the shared FFmpeg namespace or local Vulkan/DV/Matroska safety patches.

## 8. Approval gate and next action

This document recommends approval of P1-1, P1-2, P1-3, and P1-4 for one controlled MPV native rebuild, with separate source commits, tests, results, and rollback notes. It does not authorize implementation. P2 generic UV/DV7, P3 AudioTrack mask, P4 JNI shutdown, Android BL+EL, C2 DV7 conversion, and maintenance-only items remain separate decisions.

Next action: wait for explicit user approval; if approved, start a new `upstream` task-guard session declaring the exact lock, patch, source, two-ABI asset, and verification paths.

## 9. Assessment validation

Validated on 2026-08-28 at HEAD `98f872eed700213ce03345d7d20d794c8ec4123a`:

- `git diff --check` passed.
- `bash .codex/scripts/task_guard.sh check` passed for `P1-MPV-FORMAT-SHADER-CORRECTNESS`; only the two declared documentation paths are task-owned, and the three pre-existing dirty paths remain protected.
- `bash .codex/skills/upstream-integration-governor/scripts/verify_upstream_checkpoint.sh docs/upstream-player-dependency-merge-assessment-2026-08-20.md` passed with zero errors/warnings, including 436 unique full commit IDs and the latest recovery anchor.
- Static checks confirmed the unique P1 document exists, the master index links it, and all five P1 source commits are recorded with full 40-character identities.
