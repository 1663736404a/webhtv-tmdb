# Exo 起播速度评估：远程大体积 Matroska/MKV

评估日期：2026-08-22（Asia/Tokyo）
评估范围：Exo 起播速度、远程百度网盘 MKV、Matroska Cues/索引、首帧与 `STATE_READY` 的关系。
当前状态：E-SP1 已实施并通过 Mobile/Leanback Java 编译；E-SP2 深度研究与本地代码审阅已完成，方案已获本次用户指令授权进入独立实现，当前尚未修改 Media3 依赖、AAR、APK 或 native 二进制。

## 结论先行

当前 Exo 起播慢的主要瓶颈不是网络连接建立、`DefaultLoadControl` 的启动缓冲阈值，也不是 Exo 预加载抢占，而是 `MatroskaExtractor` 为建立可 seek 时间轴主动读取位于文件尾部的 Cues。

针对当前项目，结论分为四类：

| 类别 | 决定 | 说明 |
| --- | --- | --- |
| 推荐实施 | 首帧可见与完整 `READY` 分层 | 这是用户体感优化，不改变解码、seek 或音频能力；当前代码已有首帧事件和 `PlaybackStartupPolicy`，但 UI 消费仍需单独核对。 |
| 批准实施 | 远程大 MKV 延后 Cues 读取，保留首次 seek 时按需建索引 | 成熟播放器普遍采用“启动先读 Tracks/首个 Cluster，索引按需读取或增量建立”；Media3 现成 flag 会直接让媒体不可 seek，最终方案必须增加按需建索引状态机。 |
| 暂缓 | 直接降低 `startMs`/`rebufferMs`、禁用 TrueHD 初始化、关闭 Exo 预加载 | 当前证据不能证明这些措施减少首帧等待，且会增加卡顿、音频能力或缓存行为风险。 |
| 忽略 | 通过增加连接超时、重试次数或替换 OkHttp 解决本问题 | 日志中 Range 请求正常返回 `206`；这类调整不会消除尾部 Cues 读取。 |

本轮没有得到“可以立即合并一行配置就加速”的安全结论。最终采用 Media3 deferred Cues：起播时延后读取 Cues，第一次随机访问时按需建立完整索引，并以独立 commit/tag 保持可回滚。

## 1. 当前基线与可重复证据

### 1.1 项目与回滚基线

- 历史评估基线：`f2721c43b6654ae7307647ebaaaa4248a50a9ab7`（Checkpoint 1 记录；不是当前实现 HEAD）。
- 当前实现 HEAD：`c07e2b27eddbbee3240ed25fd6e2c8e5a64c5c7e`；E-SP1 recovery tag：`recovery/exo-sp1-first-frame-visible-20260822/20260822224344-c07e2b27eddb`。
- E-SP2 评估阶段没有生产代码或依赖变更；评估文档将作为独立提交和 recovery tag 后再进入实现。
- 预先存在的脏路径保持不变：`.gitignore`、`third_party/fongmi-repositories-lock.json`、`.codex/`、`AGENTS.md`、已有上游评估文档。

### 1.2 Exo 远程大 MKV 实测

证据文件：`/Users/macbookpro/Downloads/webhtv-debug-log (19).txt`，trace `p-2vl4f6-g`，从约 140 秒断点启动。

关键时间线：

| 事件 | 观测 |
| --- | ---: |
| `stage=request` | 9 ms |
| `stage=prepare` | 60 ms |
| 首个 `bytes=0-` Range 完成 | 约 890 ms |
| Cues 疑似尾部 Range：`bytes=72937784060-` | 从 21:16:03.602 开始，直到轨道阶段前持续等待 |
| `stage=tracks` | 7518 ms |
| 视频解码器 `c2.mtk.hevc.decoder` 初始化 | 97 ms |
| TrueHD AudioTrack 初始化 | 约 1.9 s（轨道阶段后） |
| `stage=first-frame` | 9877 ms |
| 首帧时 Exo 状态 | 仍为 `BUFFERING`，前向缓冲约 187 ms |
| `stage=ready` | 本次未在首帧后及时到达；会话后续被切换/停止 |

启动期间至少出现以下 Range：

```text
bytes=0-
bytes=72937784060-
bytes=867350-
bytes=1326257783-
```

其中约 73 GB 文件尾部的 `bytes=72937784060-` 与 Matroska `SeekHead -> Cues` 跳转高度吻合。它不是连接失败：响应为 `206 Partial Content`，且请求随后继续读取头部/Cluster 数据。

### 1.3 MPV 对照

证据文件：`/Users/macbookpro/Downloads/webhtv-debug-log (17).txt`，trace `p-2capni-9`。

- `stage=tracks`：2530 ms；
- `stage=ready`：5702 ms；
- `stage=first-frame`：5706 ms；
- 使用 `hwdec=mediacodec`、`vo=mediacodec_embed`、`mpv-surface-direct`；
- `rebufferCount=0`。

MPV 更快不能简单说明网络更好。它的 Matroska demuxer 允许启动时不读取仅用于 seek 的 Cues，并在需要 seek 时再读取/建立索引；Exo 默认行为不同。

## 2. 官方 Media3 语义

来源：

- [Media3 `MatroskaExtractor.java`](https://raw.githubusercontent.com/androidx/media/release/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java)
- [Media3 `DefaultLoadControl.java`](https://raw.githubusercontent.com/androidx/media/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java)
- [Media3 Matroska extractor tests](https://raw.githubusercontent.com/androidx/media/release/libraries/extractor/src/test/java/androidx/media3/extractor/mkv/MatroskaExtractorTest.java)

### 2.1 Cues 行为

官方 `MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES` 的定义是：

1. 默认情况下，如果 `SeekHead` 指向位于首个 Cluster 之后的 Cues，Extractor 会跳转读取 Cues；
2. 设置该 flag 后不再跳转读取 Cues；
3. 如果 Cues 在首个 Cluster 后，媒体会被视为不可 seek。

因此它不是“先播后索引”的开关。全局启用会直接影响：

- 断点续播和从 2 分钟等非零位置启动；
- 用户 seek、章节/时间轴跳转；
- Blu-ray/本地 MKV 的随机访问；
- 可能的字幕、章节和 DV/TrueHD 关联时间轴行为。

当前本地 `third_party/patches/media3-dolby-vision-matroska.patch` 只增加 Matroska DV BlockAdditional RPU 输出，没有改变 Cues 策略。`DolbyVisionP81ExtractorsFactory` 当前使用 `FLAG_EMIT_RAW_SUBTITLE_DATA` 和本地 DV 处理，不等于已经启用或验证了 Cues 优化。

### 2.2 LoadControl 不是本次主因

官方 `DefaultLoadControl.shouldStartPlayback` 只根据已获得的 `bufferedDurationUs`、播放速度、重缓冲状态和目标 buffer bytes 决定是否开始播放。它不能避免 Extractor 在轨道建立前发起尾部 Cues Range。

本次日志还明确记录了当前自动策略最终使用 `startMs=3000`、`rebufferMs=5000`，但首帧只等待约 187 ms 前向缓冲，说明首帧等待主要发生在轨道/解码建立，而不是 3 秒启动缓冲门槛。

## 3. 成熟开源实现对照

### 3.1 MPV 原生 Matroska demuxer

来源：[mpv `demux/demux_mkv.c`](https://raw.githubusercontent.com/mpv-player/mpv/master/demux/demux_mkv.c) 和 [mpv options](https://raw.githubusercontent.com/mpv-player/mpv/master/DOCS/man/options.rst)。

关键实现：

- `demux_mkv_open` 先读取 EBML、Info、Tracks 和可用头部；
- 如果未解析的尾部头部只有 Cues，则打印 `Deferring reading cues.`，不在打开阶段跳转；
- `read_deferred_cues` 只在索引确实需要时读取 Cues；
- 默认 `--index=default` 使用已有索引或按需构建；另有 `--index=recreate` 明确表示不读/不使用文件索引。

这是“启动路径与随机访问路径分离”的成熟做法。它并没有无条件放弃 seek，而是把代价推迟到第一次真正需要索引的操作。

### 3.2 FFmpeg Matroska demuxer

来源：[FFmpeg `libavformat/matroskadec.c`](https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavformat/matroskadec.c)。

关键实现：

- `matroska_execute_seekhead` 对 Cues 明确 `defer cues parsing until we actually need cue data`；
- `matroska_read_header` 建立流和基本元数据后，不必先解析完整 Cues；
- `matroska_read_seek` 在真正 seek 时才解析 Cues；
- `AVFMT_FLAG_IGNIDX` 是显式忽略索引的能力，而不是默认启动策略。

FFmpeg 与 MPV 的共同点不是“关闭索引”，而是“把索引解析放到需要随机访问的路径”。这比直接套 Media3 `FLAG_DISABLE_SEEK_FOR_CUES` 更符合当前产品需求，但 Exo `Extractor`/`SeekMap` 生命周期需要单独适配，不能盲目移植。

### 3.3 VLC Matroska demuxer

来源：[VLC `modules/demux/mkv/mkv.cpp`](https://raw.githubusercontent.com/videolan/vlc/master/modules/demux/mkv/mkv.cpp)。

VLC 将 MKV 的 seekability、fast-seekability、Cues/segment preload 分开管理，并把 linked segments 的本地目录预加载作为显式行为。它说明成熟播放器会根据输入是否可 seek、是否 fast-seekable 以及是否需要关联 segment 选择策略，而不是把“预加载/索引/起播”混成一个全局开关。

## 4. 当前项目链路核对

已确认的相关代码：

- `app/src/main/java/com/fongmi/android/tv/player/exo/MediaSourceFactory.java`
  - 使用 `DefaultMediaSourceFactory`、缓存 DataSource、`PriorityTaskDataSource`；
  - `setLoadOnlySelectedTracks(...)` 已存在；
  - `DefaultExtractorsFactory` 外包 `DolbyVisionP81ExtractorsFactory`。
- `app/src/main/java/com/fongmi/android/tv/player/exo/AutoTargetLoadControl.java`
  - 已有自动 target bytes、内存压力和预加载优先级协调；不应把它当作 Cues 读取优化。
- `app/src/main/java/com/fongmi/android/tv/player/engine/ExoPlayerEngine.java`
  - 已记录 `onRenderedFirstFrame`，并在首帧后取消通用启动超时；该修复解决误报，不改变 Extractor 读取顺序。
- `app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java`
  - 已记录 `TRACKS`、`FIRST_FRAME`、`READY` 三个独立阶段；
  - `PlaybackStartupPolicy` 当前偏向把视频首帧作为启动完成信号，但 UI shutter 的消费仍集中在 `PlaybackActivity.syncShutter`，需要单独设计“首帧可见、音频/READY 未完成”的状态边界。
- `app/src/main/java/com/fongmi/android/tv/player/PlaybackStartupPolicy.java`
  - 当前 `resolve` 只有 `ready=true` 且具备视频首帧信号时才返回 `FIRST_FRAME`；这限制了“首帧先显示”的进一步应用，不能仅改 loading 文案解决全部问题。
- `third_party/patches/media3-dolby-vision-matroska.patch`
  - 只负责 DV BlockAdditional RPU 注入；不提供 Cues 延迟索引。

## 5. 候选阶段与实施边界

### E-SP1（推荐）：首帧可见与完整 READY 分层

- 用户决策：待批准。
- 目标：视频首帧已经可靠输出后立即解除 Exo 黑屏/loading 体验；音频初始化、`READY`、后续缓冲仍由播放器内部继续完成。
- 不改变：DV7→P8.1/HDR10 fallback、TrueHD、seek、Range、缓存、软解降载和 Exo `STATE_*` 语义。
- 依赖：`PlaybackStartupPolicy`、`PlayerManager`、`PlaybackActivity`/`PlayerView` shutter 消费路径。
- 风险：首帧可能短暂静止或无声；必须区分“画面可见”和“完整可交互/音频已播放”。
- 最小验收：首帧后黑屏消失；首帧前仍保留错误/超时保护；首帧后 TrueHD 能正常出声；首帧后短暂 BUFFERING 不触发错误；音频-only 不误判为视频首帧。
- 建议：先以窄范围 UI/状态适配实现，独立于 Cues 实验。

### E-SP2（仅实验）：远程大 MKV 的 Cues 延后/按需索引

- 用户决策：待批准，默认不进正式依赖锁。
- 目标：从文件头或可接受的起始位置播放时，不因尾部 Cues 读取阻塞 Tracks/首帧；第一次 seek/断点启动时再读取 Cues 或建立局部索引。
- 不能直接做：全局设置 `FLAG_DISABLE_SEEK_FOR_CUES`。该 flag 会把 Cues 位于首个 Cluster 后的媒体标记为不可 seek。
- 推荐实验形态：在本地 Media3 fork 中增加“defer Cues”实验模式，或在 DataSource/代理层做有界的 Cues 预取；仅对满足以下条件的远程 VOD 开启：可识别完整文件大小、起播位置为 0、来源不是 ISO/光盘、用户未要求立即 seek、DV/TrueHD/字幕轨道仍正常。
- 首次实验不应覆盖：非零断点、用户 seek、章节跳转、未知长度 HTTP、live/分片流、Blu-ray/ISO、加密媒体。
- 关键难点：Media3 `SeekMap` 通常在 extractor 初始化阶段发布；要在不破坏 Exo seek contract 的情况下延后索引，可能需要 Media3 extractor/period 层联合修改，而不是只改 App Java。
- 最小验收：
  1. 起始位置 0 的远程大 MKV：记录 `tracks`、`first-frame`、`ready` 和尾部 Range 等待时间；
  2. 同一资源从 140 秒断点启动：必须不比基线更慢且能正常 seek；
  3. 前后 seek、章节、字幕、TrueHD/Atmos、DV7 P8.1/HDR10 fallback；
  4. 中断/重试/未知长度/尾部 Cues 缺失；
  5. 至少同一设备、同一资源、同一网络条件下基线与候选各 3 次，比较中位数和失败率。
- 建议：只做 feature flag + telemetry 实验，不直接更新正式 Media3 lock/AAR。

### E-SP3（暂缓）：调低起播缓冲阈值

- 当前证据：首帧前向缓冲只有约 187 ms，但轨道阶段已经耗时约 7.5 s；不是主瓶颈。
- 风险：Range 波动、TrueHD 和高码率 DV7 资源更容易起播后卡顿。
- 决定：除非 E-SP1/E-SP2 之后仍有明确的 `shouldStartPlayback` 等待证据，否则不实施。

### E-SP4（暂缓）：禁用/延迟 TrueHD

- 当前证据：TrueHD 初始化约 1.9 s，但发生在 Tracks 阶段之后，视频首帧主要延迟仍已形成。
- 风险：损失 TrueHD/Atmos、音画同步、直通/降级能力。
- 决定：不为追求起播速度默认禁用；只有单独的音频初始化 profiling 证明收益，才建立独立实验。

### E-SP5（忽略）：关闭预加载或替换网络库

- 当前 `PreCache` 在首帧前处于等待/取消状态，前台播放 DataSource 优先级更高；没有证据证明它抢占主读取。
- 远程请求为正常 `206`，连接建立不是主要耗时。
- 决定：不作为本次 Exo 起播优化方案。

## 6. E-SP1 实施记录

### 6.1 实施目标

E-SP1 解决的是“视频首帧已经由 Exo/Media3 渲染，但完整 `STATE_READY` 仍因音频或其他轨道初始化而延后”时的界面遮挡问题。它不承诺降低 `stage=first-frame` 的实际耗时，也不改变 Exo 的状态机、缓冲策略或错误判定。

### 6.2 实施内容

- `PlayerManager` 在 Exo `onRenderedFirstFrame()` 后新增 `onExoFirstFrame()` 回调；原有首帧 trace、超时取消和 telemetry 保持不变。
- `PlaybackService` 只转发该 Exo 专用回调；MPV/native 不进入该路径。
- `PlaybackActivity` 收到当前 owner 的 Exo 首帧后立即隐藏 `exo_shutter`、设置透明 shutter，并隐藏页面已有的 `R.id.progress` 启动遮罩。
- 不修改 `STATE_READY`、`PlaybackStartupPolicy`、`DefaultLoadControl`、解码器选择、DV7→P8.1/HDR10 fallback、TrueHD、seek、Range/cache、软解降载或 MPV 输出策略。

### 6.3 验证与回滚

- 验证：`bash ./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac`，结果 `BUILD SUCCESSFUL`。
- 静态检查：`git diff --check` 通过。
- 回滚：恢复 E-SP1 原子提交即可；不涉及依赖锁或二进制重建。
- 实施提交与 recovery tag：由本轮 task guard 在提交完成后记录；后续文档 checkpoint 会补充完整 40 位 commit/tag。

## 7. 最小实施顺序

1. 先实施/验证 E-SP1（首帧可见分层），不触碰依赖和 extractor。
2. 若仍需降低实际首帧耗时，再批准 E-SP2 的实验分支/feature flag；实验只覆盖远程大 MKV、起始位置 0。
3. E-SP2 通过同设备/同资源/同设置的基线比较后，才能讨论是否进入 Media3 fork、AAR 和 Exo 依赖锁。
4. Exo 阶段稳定后，再按既定顺序评估 MPV；不得因为 MPV 更快而直接复制其 demux 实现到 Exo。

## 8. 研究来源与证据等级

| 来源 | 类型 | 等级 | 用途 |
| --- | --- | --- | --- |
| Media3 `MatroskaExtractor.java` | 本地采用 revision `e3e922d5c01bc0b564849940fe589daf37360d15`；官方 release HEAD `2bc207851df311340767e913931ca7b28cab1794`（2026-08-22） | A | 确认 `FLAG_DISABLE_SEEK_FOR_CUES` 的精确语义 |
| Media3 `DefaultLoadControl.java` | 官方源码 | A | 确认启动缓冲只作用于已获得的 buffered duration |
| Media3 `MatroskaExtractorTest.java` | 官方测试 | A | 确认官方默认测试路径不等于关闭 Cues |
| mpv `demux_mkv.c` + options | 成熟开源实现/源码，HEAD `49418246f30a9c24af31ac184aa24f39755db89a`（2026-08-22） | A/B | 确认 Cues 延后、按需读取和索引模式 |
| FFmpeg `matroskadec.c` | 成熟开源实现/源码，HEAD `eb0bfa852e7b9c524960300607ba2c4617060a9b`（2026-08-22） | A/B | 确认 seekhead/Cues 延后到真正 seek |
| VLC `mkv.cpp` | 成熟开源实现/源码 | A/B | 确认 seekability、fast-seekability、preload 分层 |
| WebHTV trace `p-2vl4f6-g` | 本地可重复日志 | A | 确认当前实际尾部 Range 与阶段耗时 |

## 9. E-SP2 深度研究与最终方案决策

### 9.1 问题的精确定义

E-SP2 要解决的不是“让所有 MKV 都不可 seek”，也不是“关闭 Cues”。目标是把两个成本不同的动作分开：

1. 起始位置为 0 的远程、可 Range 的大体积 MKV，先读取 EBML/Info/Tracks 和首个 Cluster，尽快交给解码器；
2. 用户第一次 seek、章节跳转或非零断点启动时，再读取尾部 Cues，并用 Cues 计算精确的 Cluster 位置。

成功标准是首帧等待减少，同时首次随机访问仍使用正常 Matroska seek 语义；任何不能证明输入适合该路径的资源继续走现有默认流程。

### 9.2 外部证据（按决策价值排序）

| 结论 | 来源与完整 revision | 关键证据 | 对 WebHTV 的影响 |
| --- | --- | --- | --- |
| Cues 是用于按时间定位 Cluster 的索引，不是播放样本本身 | Matroska 规范：<https://www.matroska.org/technical/cues.html>（访问 2026-08-22） | `Cues` provides an index of `Cluster` elements; 视频 keyframe 应被 CuePoint 引用 | 可把索引读取从首帧路径移到随机访问路径；不能丢弃正常 seek 能力 |
| Media3 默认会在首个 Cluster 前跳到 Cues | 本地 Media3 `e3e922d5c01bc0b564849940fe589daf37360d15`，`MatroskaExtractor.java:929-941,2320-2340`；官方源码 <https://github.com/androidx/media/blob/release/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java> | `seekForCues` 触发 `RESULT_SEEK`；解析完成后再跳回原位置 | 当前日志尾部 `bytes=72937784060-` 与该逻辑一致，主瓶颈在 extractor，不是 LoadControl |
| `FLAG_DISABLE_SEEK_FOR_CUES` 会牺牲 seek | 同上，官方 API 文档 <https://developer.android.com/reference/androidx/media3/extractor/mkv/MatroskaExtractor> | Cues 在首个 Cluster 后时，设置 flag 会把媒体标为 unseekable | 不能全局设置；否则断点、时间条、章节和切轨会退回 0 或失效 |
| Media3 的 SeekMap 可以在准备后更新，但没有现成“延后 Cues”契约 | 本地 `ProgressiveMediaPeriod.java:940-1010`、`1088-1110` | `seekMap()` 回调可在 `prepared` 后再次刷新；seek 时由 `seekMap.getSeekPoints()` 计算 DataSpec 起点 | 可在 Media3 fork 中增加一个明确的 deferred seek-map 状态，而不改 App 的所有播放器路径 |
| FFmpeg 采用按需解析 Cues | FFmpeg `eb0bfa852e7b9c524960300607ba2c4617060a9b`，`libavformat/matroskadec.c`（master，访问 2026-08-22） | `matroska_execute_seekhead()` 明确跳过 Cues；`matroska_read_seek()` 首次 seek 时调用 `matroska_parse_cues()` | 证明“延后而非禁用”是成熟实现；可移植的是时机/状态，不是直接复制 C 代码 |
| mpv 采用启动延后、随机访问时读取 | mpv `49418246f30a9c24af31ac184aa24f39755db89a`，`demux/demux_mkv.c`（master，访问 2026-08-22） | `Deferring reading cues.`；`read_deferred_cues()` 在 seek/index 构建路径调用 | MPV 更快的根因得到独立源码印证；Exo 需要补生命周期适配 |
| SeekHead/Cues 组合存在真实兼容性缺陷，必须先修正顺序 | Media3 commit `859f7b3b5388378698ff23a667d3e2db5ac41aed`，issue #3377 <https://github.com/androidx/media/issues/3377> | Tracks 在 Clusters 后时旧逻辑会先建 Cues，`primarySeekTrackNumber` 尚未确定，时间线永久 unseekable；该提交将 `maybePrepareSeekMap()` 延后到 Tracks 已读 | E-SP2 patch 必须包含该修正或等价逻辑；否则“保留 seek”目标本身不成立 |
| 递归 SeekHead 需要完整处理，不能只取一个入口 | Media3 PR #2268 的 commit `ffca82f981e975d302c6480ba9cbce6e05260e74`、`a77bd285dcfe3ebd56eb5f487f55638625005e35` 等 <https://github.com/androidx/media/pull/2268> | PR 讨论指出多个/递归 SeekHead、IO 重试和未知长度输入会改变访问顺序；简单实现曾导致样本丢失 | 不在 App/DataSource 层猜测 Cues 偏移；复用 Media3 的解析状态和测试模型 |
| 当前项目的网络链路已支持 Range 和 EOF 恢复 | WebHTV `PlaybackBytePositionDataSource.java`、`HttpEofRecoveryDataSource.java`、本地 trace `p-2vl4f6-g` | 尾部请求返回 `206`；缓存/Range/重连已有独立诊断 | 不替换 OkHttp、不增加超时；优化点应留在 extractor 生命周期 |

### 9.3 方案比较

| 方案 | 首帧收益 | seek/断点 | 代码与回滚风险 | 决定 |
| --- | --- | --- | --- | --- |
| 不变更 | 无 | 完整 | 最低 | 不能解决当前 7-10 秒尾部等待 |
| 全局 `FLAG_DISABLE_SEEK_FOR_CUES` | 高 | 直接破坏 | 低实现成本、高产品风险 | 拒绝 |
| DataSource/代理后台预取尾部 | 通常无，前台请求仍可能竞争 | 完整 | 受缓存、并发和服务端 Range 行为影响；不能阻止 extractor 等待 | 拒绝为主方案 |
| 起播临时 unseekable，第一次 seek 重建播放器 | 高 | 可恢复，但会重建 Surface/音频状态 | App 状态复杂，首个 seek 可能丢状态 | 仅保底，不作为最终方案 |
| Media3 deferred Cues + 可更新 SeekMap | 高 | 先播；首次随机访问精确读 Cues | 需要 extractor/ProgressiveMediaPeriod 联合 patch；可用测试和 feature gate 控制 | 推荐 |

### 9.4 推荐实现的状态机

```text
prepare(position=0)
  -> provisional DeferredSeekMap(duration, isSeekable=true, points=START)
  -> Tracks/formats/endTracks
  -> Cluster samples -> first frame (不读尾部 Cues)

first seek/非零 prepare
  -> ProgressiveMediaPeriod 保留目标 timeUs，不把它归零
  -> extractor 从 0 重读头部，在需要时 RESULT_SEEK 到 Cues
  -> 发布完整 MatroskaSeekMap
  -> RESULT_SEEK 到精确 CueClusterPosition
  -> 正常样本读取/章节/字幕/TrueHD/DV 继续
```

实现约束：

- 只对 Matroska/WebM extractor 的显式实验 flag 生效；MP4、TS、HLS、DASH、RTSP、ISO、live 和未知长度非 VOD 不改变；
- `DeferredSeekMap` 初始只承诺“可接受 seek 请求”，其首个 byte point 是 0；实际 seek 由 extractor 在 Cues 建好后重新定位；
- Cues 解析前不改变 `Format`、DV BlockAdditional RPU、HDR10 fallback、AV3A、TrueHD/Atmos、字幕原始数据和软解降载；
- IO 重试必须清理“已访问 SeekHead/待处理跳转”状态，沿用 Media3 Extractor 的 unchanged-position contract；
- 先加入 `FLAG_DEFER_SEEK_FOR_CUES`，默认只对符合输入条件的远程大文件启用；不把该 flag 写入所有 extractor；
- 任何异常（Cues 缺失、未知长度、解析失败、首帧/seek 失败）回退到现有默认 extractor，不能回退到软解或改变 DV7/HDR10 策略。

### 9.5 结合 WebHTV 代码的审阅结论

- `MediaSourceFactory` 已集中创建 `DefaultExtractorsFactory` 和 `DolbyVisionP81ExtractorsFactory`，是唯一合适的 Exo extractor 注入点；不需要改 MPV 或共用 native 二进制。
- `DolbyVisionP81ExtractorsFactory` 目前只重建 Matroska extractor 以发出 DV RPU；新构造函数必须同时保留 `FLAG_EMIT_RAW_SUBTITLE_DATA`、DV BlockAdditional 开关和 P8.1/HDR10 状态，不能用一个裸 `MatroskaExtractor` 替换。
- `ProgressiveMediaPeriod` 的 `setSeekMap` 已支持 prepared 后更新，但 `seekToUs` 当前会把不可 seek 媒体的位置强制为 0；这是必须同步修正的第二个文件。
- `PreCache` 使用同一 MediaItem 做后台预加载，但前台优先级更高；不把 E-SP2 逻辑放进 PreCache，避免尾部索引和预加载互相竞争。
- `PlayerManager`、`PlaybackActivity`、MPV/IJK 路径不需要改；首帧 UI 由 E-SP1 独立负责，E-SP2 只改变 Exo extractor 的读取时机。

### 9.6 验收与回滚

生产合并前必须记录同一资源/设备/网络的基线与候选各至少 3 次中位数：`tracks`、`first-frame`、`ready`、尾部 Range 等待、首次 seek 完成时间、重缓冲次数、A/V sync、掉帧和失败率。

最小输入矩阵：起始 0 秒远程大 MKV、同资源 140 秒断点、首次/连续/章节 seek、字幕、TrueHD/Atmos、DV7→P8.1、DV7→HDR10、Cues 缺失/损坏、Tracks-after-Clusters、递归 SeekHead、未知长度 HTTP、中断重试和本地 MKV。任何现有能力退化即回滚整个 E-SP2 commit/tag。

推荐回滚锚点：E-SP1 `c07e2b27eddbbee3240ed25fd6e2c8e5a64c5c7e`；E-SP2 另建独立 commit 和 recovery tag，绝不与 MPV/native 合并。

## 10. 当前决策与实施边界

- E-SP1 已完成并独立提交/tag。
- E-SP2 已由本次用户指令批准实施，采用 9.4 的 Media3 deferred Cues 方案；不采用代理/DataSource 尾部预取，也不全局设置 `FLAG_DISABLE_SEEK_FOR_CUES`。
- 实现先补入上游 `859f7b3b5388378698ff23a667d3e2db5ac41aed` 的等价修正，再加入 deferred Cues 状态机、测试、Media3 AAR 和 App factory 接入，作为独立可回滚单元。
- E-SP2 不修改 MPV/native、LoadControl、PreCache、DV7→P8.1/HDR10 转换、AV3A、TrueHD 或软解降载策略。

## Checkpoint 1：2026-08-22 Exo 起播性能评估

- 完成：完成 Exo 远程大 MKV 起播性能评估；确认尾部 Cues Range 是主要实耗时；对照 Media3、mpv、FFmpeg、VLC；完成 E-SP1 首帧可见分层实现并通过双产品 Java 编译；E-SP2 仍保持实验边界。
- 基线：`f2721c43b6654ae7307647ebaaaa4248a50a9ab7`；最新 recovery tag 为 `recovery/exo-dv7-timeout-after-first-frame/20260822212742-f2721c43b665`。
- 工作区：只新增本评估文档；既有脏路径未触碰。
- 证据：WebHTV trace `p-2vl4f6-g`；Media3、mpv、FFmpeg、VLC 官方源码链接见上文。
- 验证：E-SP1 Java 编译 `BUILD SUCCESSFUL`；`git diff --check` 通过；未执行 APK/native 构建（本阶段不需要）。
- 回滚：回滚 E-SP1 原子提交即可；既有依赖锁和其他脏路径保持不变。
- 未决：E-SP2 采用 Media3 fork 延后 Cues 还是有界代理/DataSource 预取，必须完成研究与实验后再决定。
- 下一步：记录 E-SP1 提交/tag 后，启动独立 E-SP2 assessment/upstream 会话，先审阅本地 Media3 源码与实验边界。

## Checkpoint 2：2026-08-22 E-SP1 已实施

- 目标：首帧出现后立即解除 Exo 黑屏和启动遮罩，完整 `STATE_READY` 继续由播放器自行完成。
- 改动：`PlayerManager.java`、`PlaybackService.java`、`PlaybackActivity.java`；只新增 Exo 专用首帧通知链。
- 保护：MPV/native、音频-only、DV7 转换/fallback、AV3A、TrueHD、seek、Range/cache、软解降载均未改动。
- 验证：Mobile Arm64 与 Leanback Arm64 Java 编译通过；`git diff --check` 通过。
- 提交/tag：`c07e2b27eddbbee3240ed25fd6e2c8e5a64c5c7e`；`recovery/exo-sp1-first-frame-visible-20260822/20260822224344-c07e2b27eddb`。
- 下一步：进入 E-SP2 深度研究；不重复检索已确认的 E-SP1 事实。

## Checkpoint 3：2026-08-22 E-SP2 最终方案已冻结

- 完成：对照 Matroska 规范、Media3、FFmpeg、mpv、VLC、Media3 issue/PR 和 WebHTV 实际链路，确认尾部 Cues 同步读取是远程超大 MKV 从 0 秒起播的主要 extractor 延迟。
- Source identities：本地 Media3 `e3e922d5c01bc0b564849940fe589daf37360d15`；Media3 release `2bc207851df311340767e913931ca7b28cab1794`；FFmpeg `eb0bfa852e7b9c524960300607ba2c4617060a9b`；mpv `49418246f30a9c24af31ac184aa24f39755db89a`；必要兼容修复 `859f7b3b5388378698ff23a667d3e2db5ac41aed`。
- 决策：采用 Media3 deferred Cues + 可更新 SeekMap；拒绝全局禁用 Cues、代理尾部预取和播放器重建方案。
- 工作区：分支 `fongmi-sync`，基线 HEAD `c07e2b27eddbbee3240ed25fd6e2c8e5a64c5c7e`；本阶段只修改本评估文档，预存脏路径继续保护。
- 验证：外部 revision 已记录；文档完成后运行 checkpoint 校验与 `git diff --check`。
- 回滚：评估文档单独提交/tag；生产实现以 E-SP1 commit 为前置回滚锚点。
- Rollback anchor: `c07e2b27eddbbee3240ed25fd6e2c8e5a64c5c7e` (`recovery/exo-sp1-first-frame-visible-20260822/20260822224344-c07e2b27eddb`); revert the future E-SP2 source/AAR/App unit together if any acceptance gate regresses.
- 未决风险：deferred seek 请求与 extractor 重入、递归 SeekHead、未知长度和网络重试必须由定向测试覆盖；设备性能验证需在候选 AAR 接入后进行。
- 下一步：完成评估提交/tag，启动独立 E-SP2 upstream guard，修改 Media3 patch/测试、生成对应 AAR，并接入 `DolbyVisionP81ExtractorsFactory`。
