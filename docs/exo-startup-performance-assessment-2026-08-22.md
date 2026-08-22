# Exo 起播速度评估：远程大体积 Matroska/MKV

评估日期：2026-08-22（Asia/Tokyo）
评估范围：Exo 起播速度、远程百度网盘 MKV、Matroska Cues/索引、首帧与 `STATE_READY` 的关系。
当前状态：仅评估，未修改生产代码、依赖锁、AAR、APK 或 native 二进制。

## 结论先行

当前 Exo 起播慢的主要瓶颈不是网络连接建立、`DefaultLoadControl` 的启动缓冲阈值，也不是 Exo 预加载抢占，而是 `MatroskaExtractor` 为建立可 seek 时间轴主动读取位于文件尾部的 Cues。

针对当前项目，结论分为四类：

| 类别 | 决定 | 说明 |
| --- | --- | --- |
| 推荐实施 | 首帧可见与完整 `READY` 分层 | 这是用户体感优化，不改变解码、seek 或音频能力；当前代码已有首帧事件和 `PlaybackStartupPolicy`，但 UI 消费仍需单独核对。 |
| 仅实验 | 远程大 MKV 延后 Cues 读取，保留首次 seek 时按需建索引 | 成熟播放器普遍采用“启动先读 Tracks/首个 Cluster，索引按需读取或增量建立”；Media3 现成 flag 会直接让媒体不可 seek，不能直接全局打开。 |
| 暂缓 | 直接降低 `startMs`/`rebufferMs`、禁用 TrueHD 初始化、关闭 Exo 预加载 | 当前证据不能证明这些措施减少首帧等待，且会增加卡顿、音频能力或缓存行为风险。 |
| 忽略 | 通过增加连接超时、重试次数或替换 OkHttp 解决本问题 | 日志中 Range 请求正常返回 `206`；这类调整不会消除尾部 Cues 读取。 |

本轮没有得到“可以立即合并一行配置就加速”的安全结论。真正可能减少实际耗时的方向是“Cues 延后/并行预取”，必须先做可回滚实验。

## 1. 当前基线与可重复证据

### 1.1 项目与回滚基线

- 当前 HEAD：`f2721c43b6654ae7307647ebaaaa4248a50a9ab7`
- 当前最新 recovery tag：`recovery/exo-dv7-timeout-after-first-frame/20260822212742-f2721c43b665`
- 本轮没有生产代码或依赖变更；仅提交了本评估文档，并创建文档 recovery tag。
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

## 6. 最小实施顺序

1. 先实施/验证 E-SP1（首帧可见分层），不触碰依赖和 extractor。
2. 若仍需降低实际首帧耗时，再批准 E-SP2 的实验分支/feature flag；实验只覆盖远程大 MKV、起始位置 0。
3. E-SP2 通过同设备/同资源/同设置的基线比较后，才能讨论是否进入 Media3 fork、AAR 和 Exo 依赖锁。
4. Exo 阶段稳定后，再按既定顺序评估 MPV；不得因为 MPV 更快而直接复制其 demux 实现到 Exo。

## 7. 研究来源与证据等级

| 来源 | 类型 | 等级 | 用途 |
| --- | --- | --- | --- |
| Media3 `MatroskaExtractor.java` | 官方源码/契约 | A | 确认 `FLAG_DISABLE_SEEK_FOR_CUES` 的精确语义 |
| Media3 `DefaultLoadControl.java` | 官方源码 | A | 确认启动缓冲只作用于已获得的 buffered duration |
| Media3 `MatroskaExtractorTest.java` | 官方测试 | A | 确认官方默认测试路径不等于关闭 Cues |
| mpv `demux_mkv.c` + options | 成熟开源实现/源码 | A/B | 确认 Cues 延后、按需读取和索引模式 |
| FFmpeg `matroskadec.c` | 成熟开源实现/源码 | A/B | 确认 seekhead/Cues 延后到真正 seek |
| VLC `mkv.cpp` | 成熟开源实现/源码 | A/B | 确认 seekability、fast-seekability、preload 分层 |
| WebHTV trace `p-2vl4f6-g` | 本地可重复日志 | A | 确认当前实际尾部 Range 与阶段耗时 |

## 8. 当前未决事项

- 是否批准 E-SP1 的 UI/启动状态适配？
- 是否批准 E-SP2 的实验性 Media3 extractor/代理预取方案？
- E-SP2 采用“Media3 fork 延后 Cues”还是“代理/DataSource 有界预取”，需要先冻结实验边界再设计。

本评估完成后，下一步只有一个：等待用户选择 E-SP1、E-SP2 或两者的实施授权；在授权前不修改生产代码。

## Checkpoint 1：2026-08-22 Exo 起播性能评估

- 完成：完成 Exo 远程大 MKV 起播性能评估：确认尾部 Cues Range 是主要实耗时；对照 Media3、mpv、FFmpeg、VLC；提出 E-SP1 首帧分层推荐、E-SP2 Cues 延后仅实验，缓冲/TrueHD/预加载/网络库暂缓或忽略。
- 基线：`f2721c43b6654ae7307647ebaaaa4248a50a9ab7`；最新 recovery tag 为 `recovery/exo-dv7-timeout-after-first-frame/20260822212742-f2721c43b665`。
- 工作区：只新增本评估文档；既有脏路径未触碰。
- 证据：WebHTV trace `p-2vl4f6-g`；Media3、mpv、FFmpeg、VLC 官方源码链接见上文。
- 验证：文档 `git diff --check` 通过；本轮未改生产代码，未执行 APK/native 构建。
- 回滚：删除本新增文档即可；生产代码与依赖锁无变化。
- 未决：等待用户批准 E-SP1/E-SP2；批准前不改生产代码。
- 下一步：等待用户批准 E-SP1/E-SP2；批准前不改生产代码。
