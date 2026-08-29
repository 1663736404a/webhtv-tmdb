# C2-DV7-P81-BSF：MPV Profile 7 到 P8.1 的显式转换

## Recovery anchor

- 目标：按设备能力自动选择 DV7 原生、P8.1 或 HDR10：原生 DV7 始终优先；设备不支持 DV7 时按用户选择尝试 P8.1 或直接 HDR10；P8.1 不支持/失败必须自动回退 HDR10。
- 任务范围：MPV native patch/build 接线、MPV 两态用户设置与本任务文档/总索引；原生 DV7 保留仅作为内部能力结果，不作为用户选项；不修改 Exo、JNI、Vulkan、AudioTrack、网络或现有 HDR10/Surface 安全补丁。
- 基线：`fongmi-sync` @ `5e90c2ed76830f5c45988d8597d14ffd599dba34`；恢复 tag：`upstream/mpv/c2-dv7-p81-bsf-baseline-20260829`。
- 保护 dirty 路径：无。
- 验收：patch 可按当前锁定 MPV 树应用；Java 编译通过；两 ABI native 产物/ELF/资产校验通过；原生 DV7、P8.1、HDR10、seek/flush/换源和失败回退有证据。
- 当前状态：代码、native 资产、双端 arm64 Debug 包和定向单测已完成；实机回归因当前无线 ADB 地址拒绝连接而未执行，待设备重新连接后补测。
- 已完成：创建 baseline tag；完成 C2 patch、两态 UI、原生 DV7 优先、P8.1 能力门和 HDR10 自动回退；修复 P8.1 packet/CSD 不一致黑屏；两个 ABI native/ELF/资产校验、Mobile/Leanback arm64 Debug 构建和策略单测均通过。
- 未完成：本轮非 DV 设备实机开播/回退回归，以及提交和候选恢复 tag。
- 下一动作：设备可用后执行一次最小播放回归；若仍不可用，保留已记录的环境风险并完成 task guard 提交/tag。

## 决策与来源

### 上游来源

| 仓库 | 完整 commit | 作用 | WebHTV 处置 |
| --- | --- | --- | --- |
| `FongMi/FFmpeg` | `177f090e0503b7e013922ca903bde14b1c375f18` | `dovi_rpu` BSF 的 `convert=p81`，改写 DV metadata/CSD、删除 EL NAL | 复用已锁定并已由 C0-M 验证的输入；只在 MPV 显式模式调用 |
| `FongMi/mpv` | `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42` | 当前 `dovi_split`、lavf/Matroska demux 生命周期和 decoder/output 消费者 | 在本地锁定树上窄适配，不替换整树 |
| `FongMi/mpv-android` | `eabfaf9501fc08fb726953a9328da43ae4154d35` | 当前 Android native 构建框架和 ABI 打包 | 只沿用现有构建链；无 JNI/API 变化 |

FFmpeg 的 `dovi_rpu` 必须与 MPV 的 `dovi_split` 共享同一 AVCodecParameters/packet 生命周期。C0-M 已把 MPV FFmpeg 锁定到 `177f090...`，因此 C2 不再改 lock；MPV 与 Exo 仍分别构建和回滚。

### 本地现状

- `mpv-dovi-profile7-hdr10-base-layer.patch` 已实现 `preserve|hdr10` 两态、BL-only packet 过滤、`par_out`/metadata 同步、错误传播和 `INT_MAX` 防护。
- MPV 的 `demux/dovi_split.c` 目前只创建 `dovi_split`，不能调用 `dovi_rpu`；App 只把 MPV 旧布尔值映射成 `hdr10|preserve`。
- Exo 已有独立 `DolbyVisionP81ExtractorsFactory`/libdovi 链路，C2 不替换该实现。

## 最终行为

MPV UI 只保留两种非原生兼容策略：

- `升级P8.1`：默认；设备不能原生播放 DV7、但声明支持对应 P8.1 时使用 FFmpeg `dovi_rpu` 转换；
- `降级HDR10`：设备不能原生播放 DV7 时直接使用 BL-only HDR10 fallback。

设备能够原生播放当前 DV7 profile/level/分辨率时，无论上述 fallback 选择为何都保留原始 DV7。P8.1 静态能力不成立、转换失败或解码运行时失败时只自动重试一次 HDR10，不能保持黑屏；`preserve` 仍是内部自动结果，不再是用户选项。

P8.1 仅对 HEVC、Profile 7、存在有效 RPU+BL 配置记录的轨道生效。BSF 初始化、send/receive、损坏 NAL 或内存失败均不会静默改变默认路径；当前 demux 调用方会丢弃失败 AU 并记录明确错误，换源、seek、flush 继续复用现有 reset 生命周期。

## 方案比较

- 不变：风险最低，但 MPV 无法使用已锁定 FFmpeg 的 P8.1 转换能力。
- 直接整合上游：代码量少，但会绕过 WebHTV 的双态用户策略、packet ownership 和现有 HDR10 decoder gate，容易改变默认行为。
- 本次窄适配：只增加一个 BSF 选择和一条 packet 替换路径，默认仍 `hdr10`，并复用已有错误/flush/Surface 保护；这是本项目的推荐方案。

## 收益、风险与影响

- 收益：不支持 DV7 原生解码但支持 P8.1 的设备可显式播放动态 RPU，色彩和高光映射优于丢弃全部 RPU 的 HDR10 fallback；转换在 demux 层完成，不增加 renderer 循环开销。
- 缺点/风险：每个 DV7 AU 需要一次 BSF 解析/重写；损坏或非标准 RPU 可能失败；厂商对 Profile 8.1 CSD 的接受度仍需实机覆盖。
- 兼容性：原生 DV7 能力优先；旧 `true` 映射 HDR10、旧 `false` 映射 P8.1；P8.1 默认仅在能力门通过后启用，否则自动 HDR10。无 JNI/API/ABI 名称变化。
- 性能/包体积：native 代码复用已编译的 FFmpeg `dovi_rpu`，不新增独立库；P8.1 模式增加 CPU 解析成本，其他模式无额外路径。
- 最佳实践：遵循 FFmpeg 官方 BSF 的配置/初始化/flush/packet ownership 约定，同时保留 WebHTV 的失败回退和会话策略，优于盲目 cherry-pick。
- 上游调整：需要。上游 BSF 本身不负责 MPV 的用户策略、demux packet 所有权和 Android decoder/output 选择，必须由本地适配补齐。

## 实施阶段与验证

1. 增加 `mpv-dovi-profile7-p81.patch`，扩展 demux option、`dovi_split` 选择和 P8.1 参数同步；在构建脚本中按现有 patch 顺序应用并增加 marker 校验。
2. 将 MPV 设置从旧布尔值迁移为两个用户选项，`MpvPlayerEngine` 内部仅传递 `preserve|p81|hdr10`；不改变 Exo 两态行为。
3. 运行 `git apply --check`/shell syntax/Java 编译；随后按同一 lock 做 arm64-v8a 与 armeabi-v7a native build 或在环境缺失时记录阻塞。
4. 设备验证：DV7 原生/P8.1/HDR10 开播、seek、暂停恢复、换源和 EOF；无 RPU、无 BL、损坏 NAL、BSF 初始化失败回退；普通 HDR10/DV5/音频样片无行为变化。

## 回滚

先恢复 `upstream/mpv/c2-dv7-p81-bsf-baseline-20260829`；发布后按原子 commit 恢复 C2 patch、设置接线和 native assets，保留 C0-M/P2-2/P3/P4 已验证能力。

## 实施记录

### 2026-08-29 14:42 CST

- 已启动 `task_guard`：`C2-DV7-P81-BSF`，范围锁定为本文件、总索引、C2 patch、MPV build/verify 脚本和四个 App 设置/engine 文件。
- 已创建 baseline tag；尚未修改代码或 lock。

### 2026-08-29 15:18 CST：实现与静态验证

- 已完成 C2 patch：`demuxer-dovi-profile7=p81` 仅在 HEVC/Profile 7 且有效 RPU+BL 配置存在时选择 `dovi_rpu`，同步 `par_out`/Profile 8 参数，复用已有 packet ownership、flush/seek 和错误路径；`preserve`/`hdr10` 逻辑保持。
- 已完成 App 三态：旧 MPV 布尔设置兼容映射为 `hdr10`（默认）/`preserve`，新增显式 `p81`；Exo 两态 API 不变。
- 已通过：`bash .codex/scripts/task_guard.sh check`、`bash -n scripts/build_mpv_native.sh`、`git diff --check`、在现有 HDR10 patch 后的临时 MPV 树上 `git apply --check --recount`。
- Gradle 尝试：`:app:compileDebugJavaWithJavac` 不存在；任务枚举在隔离缓存首次下载 Gradle 时无输出，已中止，待使用项目实际任务或仅记录环境限制。

### 2026-08-29 17:04 CST：首轮实机失败与方案纠正

- 双 ABI native ELF/marker/依赖校验已通过，`:app:assembleMobileArm64_v8aDebug` 成功，ADB 安装成功。
- vivo V2453A（Android 15、设备无 DV 解码能力）选择首轮“升级P8.1”后黑屏；日志持续出现 `hevc_mediacodec: No start code is found`。检查确认 C2 patch 更新了 P8.1 codec parameters，却遗漏让 `mp_dovi_split_filter_base()` 在 `convert_p81` 时替换原 packet，形成 Annex-B 参数与原 NALFF packet 不一致。
- 用户纠正产品策略：删除多余的“保留DV7”选项；设备支持 DV7 时始终原样播放；否则只提供 P8.1/HDR10 两态，P8.1 不支持或失败自动 HDR10。P8.1 作为默认 fallback，重置按钮也必须恢复该默认值。

### 2026-08-29 17:20 CST：C2 收敛修正

- Native packet 过滤必须同时覆盖 `base_only` 与 `convert_p81`；否则 P8.1 codec parameters 已切到 Annex-B，而原始 Matroska NALF packet 仍会送入 MediaCodec，复现黑屏和 `No start code is found`。
- P8.1 请求缺少有效 Profile 7 RPU+BL 配置，或 `dovi_rpu` 不可用时，native 层改用 HDR10 base-layer 过滤，不再保留不受支持的 DV7 bitstream。
- 待完成：应用最终 patch、策略单测、双 ABI native 重建与 `mobileArm64_v8aDebug`/`leanbackArm64_v8aDebug` 构建，然后在非 DV 设备确认默认 P8.1 自动落到 HDR10 且无 `No start code`。

### 2026-08-29 19:59 CST：最终构建与收尾验证

- `bash scripts/verify_mpv_native_assets.sh --require-elf` 通过：`arm64-v8a`、`armeabi-v7a` 的 ELF、SONAME、DT_NEEDED、C2 marker 和资产集合均匹配；使用 NDK `29.0.14206865` 的 `llvm-readelf`。
- `bash ./gradlew :app:assembleMobileArm64_v8aDebug :app:assembleLeanbackArm64_v8aDebug --no-daemon` 通过，耗时 3 分 43 秒。产物：`app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk`（SHA-256 `2c5b6ef8b0e73d9b79b95f9b655bc5fa619018d2cdd3384ec373037fa97271e6`）和 `app/build/outputs/apk/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug.apk`（SHA-256 `3867a8c7fb352815c7bfe4882301aefdd88fe6cc88c648ad9b03353c6ccdd392`）。
- `bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.player.engine.MpvDolbyVisionFallbackPolicyTest --no-daemon` 通过，耗时 1 分 26 秒；覆盖原生 DV7 优先、P8.1 能力门和 HDR10 回退策略。
- 尝试 `adb devices -l` 和 `adb connect 192.168.1.9:5555`；当前无在线设备，连接返回 `Connection refused`，因此未宣称本轮实机播放通过。此前首轮黑屏根因及修正已由日志和静态 patch 检查确认，最终包仍需设备在线后验证默认 P8.1/HDR10 实际解码路径。

### 2026-08-29 20:00 CST：提交与恢复锚点

- C2 原子提交：`ae337b81e44657d85050bee3a9f92a780fb418ab`（`mpv: complete DV7 to P8.1 fallback integration`）。
- 恢复 tag：`recovery/C2-DV7-P81-BSF/20260829200055-ae337b81e446`。
- 状态：实现、静态验证、native 资产验证、双端 arm64 包构建和策略单测已完成；实机验证因设备离线保留为明确剩余风险。回滚锚点为 `upstream/mpv/c2-dv7-p81-bsf-baseline-20260829`。
