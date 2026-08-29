# P4-3: MPV terminal Surface teardown ordering

## Recovery anchor

- Objective: prevent MPV from starting disposable MediaCodec decoders after the Android video Surface has been destroyed during terminal playback exit, while preserving normal picture-in-picture, background/foreground, configuration change, decoder switch, and Surface recreation behavior.
- Lane: assessment only. The user requested this bug be fixed separately after C0-M closure on 2026-08-29; runtime implementation still waits for approval of the concrete design below.
- Baseline: branch `fongmi-sync`, HEAD `8e942a2b868dd2352b5c1e49db078e040a05528e`.
- Protected dirty path: `AGENTS.md`; it remains outside this task.
- Current evidence: C0-M device logcat, pre-C0-M device logs, current WebHTV Java/JNI flow, locked FFmpeg/mpv-android sources, Android SurfaceHolder documentation, and upstream mpv-android history.
- Status: decision-ready assessment. Recommendation is the narrow WebHTV terminal-release signal in section 6.
- Next action: obtain explicit implementation approval, then create the baseline recovery tag and start one `upstream` task guard scoped only to the approved Java policy/service/player files, focused test, this document, and the assessment index.

## 1. User-visible capability

When the user closes playback or exits picture-in-picture, the player should shut down directly instead of briefly creating and destroying new hardware video decoders after the display Surface is already gone. The fix removes avoidable exit-time decoder work and error logs, reducing the chance of a vendor MediaCodec/Surface race without changing picture quality, supported formats, normal playback, or frame-time performance.

## 2. Observed failure and root cause

The focused vivo V2453A run on Android 15 produced this terminal sequence on 2026-08-29:

1. `12:20:36.364-389`: the Surface freed dequeued buffers and its BufferQueue became abandoned.
2. `12:20:36.393-418`: the existing codec and video output began releasing and emitted video reconfiguration events.
3. `12:20:36.422` and `12:20:36.536`: FFmpeg logged `h264_mediacodec: Both surface and native_window are NULL`; each attempt created a `c2.qti.avc.decoder` component and immediately released it.
4. `12:20:36.818-822`: MPV delivered normal `end-file` and `shutdown`. There was no port-starvation precursor, Java crash, SIGSEGV, SIGABRT, or destroyed-mutex report.

This message predates C0-M in saved device logs, so FFmpeg 9.0.1 did not introduce it. The exact responsibility chain is:

- `PlaybackActivity.finishPlayback()` marks terminal exit and asks `PlaybackService.shutdown()` to stop/clear the current item, but final service/player release can occur after the Activity Surface starts disappearing.
- `MpvPlayer.surfaceDestroyed()` always calls `detachMpvSurface()`. That method queues `set vo null`, `set force-window no`, optional OSD detach, and video Surface detach.
- P4-1 correctly serializes those mutations with shutdown, but an MPV property reply does not prove asynchronous VO/decoder teardown has completed. The `vo=null` transition can therefore trigger video reconfiguration while the Android Surface has already become unusable.
- Locked FFmpeg `libavcodec/mediacodec_surface.c` logs the observed error and returns `NULL` when decoder initialization receives neither a Java `Surface` nor an `ANativeWindow`. Hiding this log would not remove the invalid initialization attempt.

## 3. Source and evidence record

| Evidence | Revision / access | Grade | Supported conclusion and caveat |
| --- | --- | --- | --- |
| WebHTV post-C0-M logcat `/private/tmp/C0-M-posthoc-logcat-20260829.txt` | device buffer captured 2026-08-29 | A, direct observation | Surface abandonment precedes two no-Surface decoder creations; normal shutdown follows. One device/API only. |
| WebHTV pre-C0-M log `/private/tmp/webhtv-dv5-auto-20260829/app-debug-log.txt` | captured before `9b7cf9cfbbeac00b0e5a342d4c6071c2c2d7a223` | A, direct observation | The same FFmpeg error exists before C0-M, so this is not a 9.0.1 regression. Other occurrences also follow decoder fallback and must not be globally suppressed. |
| Android `SurfaceHolder.Callback.surfaceDestroyed()` | <https://developer.android.com/reference/android/view/SurfaceHolder.Callback#surfaceDestroyed(android.view.SurfaceHolder)>, accessed 2026-08-29 | A, platform contract | After the callback returns, code must no longer access the Surface and rendering threads must no longer touch it. It does not prescribe WebHTV's shutdown API. |
| FongMi/mpv-android locked tree | `99a60ad2141d5ace94453590903c2c6b9a0a2443` | A, exact source | `BaseMPVView.surfaceDestroyed()` uses `vo=null` then detach and explicitly states a race may remain because setting the property may not wait for VO deinit. The sample App has no separate terminal-release state. |
| mpv-android background-output history | `4e7916ea995e07ad09eb4285c2b2f23c4f891cd1`, `e185cdf53429653e3923a16f7453d7c523310319`, `cc30506e012a49ac6721baab54ee2421ad468860` | A, upstream history | `vo=null` was selected to preserve cache/background behavior, not as a terminal shutdown primitive; copying it unchanged cannot distinguish transient and terminal Surface loss. |
| mpv-android issue 1107 | <https://github.com/mpv-android/mpv-android/issues/1107>, accessed 2026-08-29 | B, maintainer issue | Background Surface/VO transitions can overwrite intended output state. It corroborates the need to preserve local VO ownership but does not supply this teardown fix. A second bounded search found no direct upstream race fix. |
| WebHTV P4-1 | `907bfca982a4b1d4d9ee0eeddd05d02226b8f9bb` | A, current local implementation | Shutdown and Surface mutations share one FIFO and native cleanup releases global Surface references. P4-3 should use that cleanup rather than rebuild JNI. |
| FongMi/FFmpeg | `177f090e0503b7e013922ca903bde14b1c375f18` | A, exact source | `ff_mediacodec_surface_ref()` emits the error when both handles are absent and returns `NULL`. Changing FFmpeg would only move or hide the symptom. |

Independent papers, benchmarks, and general technical blogs are not decision-relevant here: the disputed contract is concrete Android Surface ownership and the locked mpv/FFmpeg lifecycle implementation, not a codec algorithm or performance model. No applicable academic evidence was found or required.

## 4. Current contracts that must survive

- Transient Surface destruction must still detach native output so configuration changes, PlayerView replacement, decoder/output switches, and real background/foreground recreation do not retain stale Java Surface references.
- Picture-in-picture entry and return must continue playing and reusing/rebinding the intended output path.
- P4-1 FIFO ordering, pending request cancellation, `MPV_EVENT_SHUTDOWN`, force-wakeup fallback, and one-time JNI Surface global-reference cleanup must remain unchanged.
- `vo=null` background behavior must remain available outside terminal release; no cache, decoder, renderer, DV/HDR, OSD/subtitle, audio, network, ABI, or binary ownership policy changes.
- No frame-loop branch or native rebuild is justified. The exit-only branch should reduce codec work and have no playback performance cost.

## 5. Alternatives

| Alternative | Benefit | Defect / risk | Decision |
| --- | --- | --- | --- |
| No change | Zero code risk | Keeps unnecessary decoder creation after Surface loss and leaves a vendor lifecycle race | Reject |
| Suppress or downgrade the FFmpeg log | Small patch | Hides a real invalid initialization and also masks non-terminal decoder-fallback occurrences | Reject |
| Always use `vid=no`, reorder detach, or delay commands | Can stop decoding before detach in some cases | Changes cache/background semantics, still depends on asynchronous VO timing, and can slow or break normal Surface recreation | Reject |
| Copy upstream `surfaceDestroyed()` unchanged | Matches sample App | That source contains an explicit race FIXME and has no terminal-release distinction | Reject |
| Narrow WebHTV terminal-release signal | Separates permanent exit from transient Surface loss; shutdown owns final native reference cleanup | Requires a small service-to-player signal and focused lifecycle verification | Select |

## 6. Recommended WebHTV adaptation

Add an idempotent terminal-release signal that is set synchronously when `PlaybackService.shutdown()` commits to permanent shutdown, before `stopAndClear()` and before the Activity Surface can disappear.

Planned behavior:

1. `PlayerManager` exposes a narrow terminal-release preparation method and forwards it only when the current engine is MPV. `MpvPlayerEngine` forwards to `MpvPlayer`.
2. `MpvPlayer` records `terminalReleaseRequested`. After that point, Surface create/change callbacks do not reattach output.
3. On terminal video/OSD `surfaceDestroyed`, clear only Java-side callback/state references and mark the native attachment state as owned by shutdown. Do not enqueue `vo=null`, `force-window`, or Surface-detach mutations.
4. The existing P4-1 shutdown path remains responsible for final MPV destruction and JNI global-reference cleanup. Normal non-terminal Surface loss continues through the current detach/rebind path unchanged.
5. `PlayerManager.release()` repeats the signal as an idempotent fail-safe for service destruction paths that bypass the ordinary `shutdown()` entry.

Expected implementation scope:

- `app/src/main/java/com/fongmi/android/tv/service/PlaybackService.java`
- `app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java`
- `app/src/main/java/com/fongmi/android/tv/player/engine/MpvPlayerEngine.java`
- `app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java`
- one focused package-level policy/test under `app/src/main/java/androidx/media3/mpvplayer/` and `app/src/test/java/androidx/media3/mpvplayer/` if needed for deterministic transient-versus-terminal coverage
- this document and the master assessment status

Excluded: FFmpeg/mpv/mpv-android source revisions, JNI/C++, native assets, renderer/decoder policy, Exo/IJK behavior, and unrelated playback-service refactoring.

## 7. Impact and risk

- Benefit: avoids two observed decoder create/release cycles and the no-Surface error on terminal exit; reduces exposure to vendor codec/BufferQueue races.
- Compatibility: no format, ABI, Android API, or package-size impact. The new method is internal App code only.
- Performance: no frame-path work; terminal exit should do less work. Startup, seek, steady playback, and normal PiP performance are unchanged by design.
- Main regression risk: incorrectly classifying transient Surface loss as terminal could leave playback without output. Mitigation is an explicit service shutdown signal, not inference from pause, stop, `mediaItem == null`, or Activity state.
- Secondary risk: pending OSD/video Surface requests may already be in the P4-1 FIFO when shutdown begins. They remain ordered ahead of shutdown and are bounded; the implementation must prevent any new attachment after the terminal flag and leave final reference cleanup to native shutdown.
- Best practice: explicit ownership state plus idempotent terminal transition is preferable to timing delays, log suppression, or accessing a destroyed Surface. The WebHTV adaptation is narrower than modifying upstream/native code and preserves the upstream transient-background intent.

## 8. Acceptance and rollback

Minimum verification after approval:

1. Focused unit tests prove transient Surface loss still requests native detach, terminal Surface loss skips detach/rebind, and repeated terminal signaling is idempotent.
2. Compile the affected Mobile arm64 Java/App target once; no native or dual-ABI rebuild is required.
3. On the connected V2453A, use command-driven playback of one local H.264/TS or MKV sample. Verify normal HOME picture-in-picture and return continue playback with Surface recreation.
4. Exit playback terminally and require: `end-file` plus `shutdown`; no decoder component creation after BufferQueue abandonment; no `Both surface and native_window are NULL` in that terminal window; no crash/ANR/native fatal/destroyed-mutex signal.
5. One rapid reopen after exit must create the next MPV context normally, proving P4-1 cleanup and the terminal flag are instance-local.

Rollback is a revert of the one atomic P4-3 commit and its App-only Java/test/document paths. Existing C0-M binaries and P4-1 JNI assets remain untouched.

## 9. Recommendation and decision

Recommendation: **implement** the narrow terminal-release signal. Do not patch FFmpeg, change global `surfaceDestroyed()` semantics, add sleeps, or rebuild native libraries.

User decision: pending explicit approval of this recorded design.
