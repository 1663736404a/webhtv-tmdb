# E3-1a：Exo Pixel E-AC3 JOC capability guard

- 任务 ID：`E3-1a`
- 所属分类：Exo
- 状态：已实施并完成文档闭环
- 唯一任务文档：`docs/E3-1a-exo-pixel-eac3-joc-guard.md`
- 用户授权：2026-08-26 明确批准实施
- 上游基线：`FongMi/media@e3e922d5c01bc0b564849940fe589daf37360d15`
- 目标行为来源：`FongMi/media@53ac10154ec1c6085627bf2dca8d224eab7bdf65` 与 `FongMi/media@1066f642a64434e7c3c0be687d3e94a4ca2815d7`
- 实施基线 tag：`recovery/E3-1a-baseline/20260826-c410bf4f40a0`

## Recovery anchor

- 目标：Google/Pixel 设备遇到 E-AC3 JOC（Dolby Atmos 空间音频）时，不再尝试已知不支持 JOC 的普通平台 E-AC3 decoder；原生 JOC decoder 仍优先，无原生 decoder 时由现有 FFmpeg renderer 解码为普通二维 PCM。非 Google 设备继续保留普通 E-AC3 二维降级。
- 接受标准：只在现有五个 Media3 patch 之后追加 E3-1a patch；两条 manufacturer 行为测试和 `:lib-exoplayer:compileDebugJavaWithJavac` 在 JDK 21 下通过；只发布 `media3-exoplayer` AAR/sources；主项目 Mobile/Leanback arm64 Debug Java 编译通过；初始脏文件不被覆盖或提交。
- 允许路径：本文件、主评估索引、`scripts/build_media_deps.sh`、`third_party/media-lock.json`、`third_party/patches/media3-exo-pixel-eac3-joc-guard.patch`、`third_party/maven/androidx/media3/media3-exoplayer/1.11.0-alpha01-fongmi/`。
- 保护路径：`AGENTS.md`、`.codex/scripts/task_guard.sh`、`docs/agents-md-effective-constraints-review-2026-08-21.md`；`third_party/sources/media` 是预存 dirty checkout，始终不直接修改。
- 已完成：基线 tag 与 task guard 已建立；上游/本地差异及现有 fallback 架构已核对。
- 当前状态：guard/test 已在干净 Media3 checkout 中实现，源码 diff 与六补丁顺序重放通过；定向测试、`lib-exoplayer` Java 编译、独立 exoplayer AAR 发布和主项目 Mobile/Leanback arm64 Debug Java 编译均已通过；实现提交与 recovery tag 已创建，待文档索引闭环。
- 未解决风险：manufacturer 字符串判断覆盖范围比具体机型宽；FFmpeg 不可用时 Google 设备仍可能无可用 decoder；软件二维解码可能增加 CPU，且尚无 Pixel 实机证据。上述风险不改变本阶段 guard 的正确性目标。
- 下一动作：在文档 guard 中记录实现提交/tag，更新总索引为已完成并指向 E3-1b。

## 实际能力与范围

本阶段改善的是部分 Pixel 手机播放带 Atmos/JOC 标记的 E-AC3 文件时的“选错解码器”问题。播放器会先尝试真正支持 JOC 的硬件 decoder；如果设备没有该能力，则跳过已知会拒绝 JOC 数据的普通 E-AC3 平台 decoder，让现有 FFmpeg 音频 renderer 接管并输出可播放的二维声音。这样不会凭空产生空间声场，但能避免直接失败。Samsung 等非 Google 设备继续按现有策略尝试 E-AC3 二维解码。

不包含：nextlib/FFmpeg 版本或 ABI、MPV native、直通/offload 策略、DTS/TrueHD、Dolby Vision、App renderer 架构或音频输出 API 改造。

## 上游提交台账与关联

| 仓库 | 完整 commit | 关联与处置 |
| --- | --- | --- |
| `FongMi/media` | `53ac10154ec1c6085627bf2dca8d224eab7bdf65` | Google/Pixel E-AC3 JOC guard 与原始两条 manufacturer 测试的直接来源；窄移植其行为。 |
| `FongMi/media` | `1066f642a64434e7c3c0be687d3e94a4ca2815d7` | 随后的多 MIME fallback API 提交把上述 guard 适配为 list 返回值，并同时包含 DTS fallback 变更。当前 fork 已由 `0592f21c689d325b03f8fed4461d15e29f9ea7f4`、`07cc217a1148f139af0c3480e6be05b082239516` 覆盖多 MIME 主体；本阶段只采用 guard 的 list 语义与两条测试。 |
| `FongMi/media` | `e3e922d5c01bc0b564849940fe589daf37360d15` | WebHTV 当前 Media3 fork 基线；其中 `getAlternativeCodecMimeTypes` 对 JOC 仍无条件返回 `audio/eac3`，确认 guard 尚未实现。 |
| WebHTV | `c84d7c204f3899fd09af1bdd1e5f7e74f9b35b2f` | 现有 `CompatFfmpegAudioRenderer` PCM fallback 接线；作为本阶段接管路径，不修改其架构。 |
| WebHTV | `877837c893ac5569c557d5f3fe4aa0c4371ad90d` | FFmpeg JOC 二维解码历史；证明 guard 后仍有可用软件 fallback，但不在本阶段重建 FFmpeg。 |

## 设计决策

### 当前项目已有实现

- `ExoUtil.getAudioRenderMode()` 固定启用 extension renderer；`FfmpegRenderersFactory` 始终加入 `CompatFfmpegAudioRenderer`。
- FFmpeg renderer 可识别 `audio/eac3-joc` 并输出 PCM，因此 Google guard 后存在明确接管路径。
- Media3 当前 fork 已将 `getAlternativeCodecMimeType` 扩展为 `getAlternativeCodecMimeTypes`，并已保留 DTS-HD、多 MIME、DV 和 MV-HEVC fallback；不能重做整个上游提交。
- 当前 `MediaCodecUtil.getAlternativeCodecMimeTypes()` 对 JOC 无条件返回 `audio/eac3`，在受影响设备上会把 JOC 数据交给已知不兼容的平台 decoder。

### 备选方案

1. 不变：继续让 Google/Pixel 尝试普通 E-AC3 decoder，保留 JOC 播放失败风险；不采用。
2. 原样移植 `1066f642...`：会重复覆盖当前 fork 已有的多 MIME/DTS 变更，扩大冲突和回归面；不采用。
3. WebHTV 窄适配：保留现有多 MIME API，只在 JOC 分支按 `Build.MANUFACTURER` 跳过 Google 设备的 `audio/eac3` alternative，并加入 Google/非 Google 测试；采用。

### 最佳实践结论

采用方案 3。平台能力差异应在 codec candidate selection 阶段表达，避免把已知会拒绝输入的 decoder 当成可用 fallback；同时保留原生 JOC 优先级和已有软件 fallback。用 manufacturer guard 是上游已验证的最小兼容策略，但它不是完整机型数据库，后续如出现非 Google 同类问题应另立任务并以设备证据扩展，不能在本阶段扩大范围。

## 风险、影响与验收

- 收益：Google/Pixel 上 E-AC3 JOC 播放失败时可落到现有 FFmpeg 二维 PCM；真正支持 JOC 的平台 decoder 不受影响；非 Google 设备行为保持不变。
- 缺点与风险：Google 设备可能失去原本可用的普通 E-AC3 hardware path，但这是为避免已知 JOC 拒绝而付出的选择性代价；FFmpeg fallback 需要已打包且可加载；软件解码会增加 CPU/功耗并只保留二维声道；manufacturer gate 可能误伤未来兼容 Google OEM。
- 与现有功能关系：只改变 JOC alternative MIME candidate；不改变 DTS/DV/MV-HEVC fallback、passthrough/offload、音频轨道选择、FFmpeg renderer、nextlib、MPV 或 native ABI。
- 兼容性：Media3 公共 API/坐标不变；普通 E-AC3、AAC、DTS、TrueHD 和非 JOC 格式路径不变。Google 设备上的 JOC 将由“尝试平台 E-AC3 后失败”变为“跳过该候选并等待 FFmpeg/其他 decoder”。
- 性能与包体积：增加一次字符串比较和分支；AAR 代码增量很小，无新线程、网络或 native 库。只有 fallback 实际触发时才可能增加 CPU/功耗。
- 是否调整上游原方案：是。仅移植 guard/test，不重复上游已被本地提交覆盖的多 MIME/DTS 改动，不调整 renderer 或 FFmpeg 配置。
- 回滚：优先恢复 E3-1a 原子提交及对应 `media3-exoplayer` AAR；必要时回到 `recovery/E3-1a-baseline/20260826-c410bf4f40a0`。不回滚 E1/E2、MPV 或受保护脏文件。

## 实施计划与验证

1. 在干净 checkout `e3e922d5c01bc0b564849940fe589daf37360d15` 中按现有五个 patch 顺序重放。
2. 在 `MediaCodecUtil.java` 的 JOC 分支加入 Google manufacturer guard；在 `MediaCodecUtilTest.java` 保留非 Google 返回 `audio/eac3`、Google 返回空列表两条测试。
3. 生成 `media3-exo-pixel-eac3-joc-guard.patch`，验证六个 patch 顺序可重复应用。
4. JDK 21 运行目标 `MediaCodecUtilTest` 与 `:lib-exoplayer:compileDebugJavaWithJavac`；发布后计算 AAR/sources SHA-256 并更新 lock。
5. 运行主项目 Mobile/Leanback arm64 Debug Java 编译，检查现有音频 renderer 接线未断。
6. 原子提交、立即创建 recovery tag；随后闭环本文件和总索引。

## 实施记录

### 2026-08-26：实施启动

- 基线：分支 `fongmi-sync`，HEAD `c410bf4f40a0ef7babb5b6281b97fa4bc621c24d`；恢复 tag `recovery/E3-1a-baseline/20260826-c410bf4f40a0`。
- 保护：`.codex/scripts/task_guard.sh`、`AGENTS.md`、`docs/agents-md-effective-constraints-review-2026-08-21.md`，以及独立 dirty `third_party/sources/media` checkout。
- 证据：当前 fork 的多 MIME 主体已存在；JOC 分支仍无条件返回 `audio/eac3`；`CompatFfmpegAudioRenderer` 提供 PCM fallback。
- 实施编辑：临时仓库 `/private/tmp/e31a-media-repo.HQ4sv1` 的 `MediaCodecUtil.java` 增加 `Objects.equals(Build.MANUFACTURER, "Google")` guard；`MediaCodecUtilTest.java` 增加 Google/Samsung 断言；仅两处文件变更，`git diff --check` 通过。
- 环境记录：Media3 wrapper 初次写受限用户 Gradle 目录、发行包下载和 Plugin Portal/Maven TLS 均曾阻塞；改用主仓库已有 `.gradle/media-deps` 缓存与代理后已完成全部目标验证，不构成产品风险。
- 补丁：`third_party/patches/media3-exo-pixel-eac3-joc-guard.patch`，最终 SHA-256 `3c5b6ca8294603a1dfa12404a236513497afd0a78cef938360304c8484f28791`。
- 重放：从 `e3e922d5c01bc0b564849940fe589daf37360d15` 干净 Git checkout 按既有五个 patch 加 E3-1a 顺序 `git apply --check/apply` 成功；guard 与两条测试均在结果树中可见。
- 定向验证：JDK 21、Gradle 9.1.0、主仓库 `.gradle/media-deps` 缓存和代理环境；`:lib-exoplayer:testDebugUnitTest --tests androidx.media3.exoplayer.mediacodec.MediaCodecUtilTest :lib-exoplayer:compileDebugJavaWithJavac`，`BUILD SUCCESSFUL in 3m 55s`。
- 发布：仅执行 `:lib-exoplayer:publishReleasePublicationToMavenRepository`，`BUILD SUCCESSFUL in 1m 20s`；未重发其它 Media3 模块。
- 产物：`media3-exoplayer-1.11.0-alpha01-fongmi.aar` SHA-256 `d7c79ed8e3e61821c7b01b4b998b999bb5a74deba6bd7517ca52a4527c126bcb`；sources SHA-256 `1e37d176ffdb6c4a5a41c0fc5d8dce8ca3fb289e39ceb92ec3e8ebfeb232eda2`。sources 内已确认 guard 代码存在。
- 主项目接线验证：`bash gradlew --no-daemon --console=plain :app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac`，`BUILD SUCCESSFUL in 42s`。
- 实现提交：`cda1ac8cf2f5d4d9c3beec68b0b520d6f7c218ec`；恢复标签：`recovery/E3-1a/20260826175658-cda1ac8cf2f5`。
- 提交内容：仅包含 E3-1a patch、`media3-exoplayer` AAR/sources 及校验文件、构建脚本、lock、任务文档和总索引；受保护 dirty 路径未纳入。
- 下一动作：完成本次文档提交后，开始 E3-1b 评估。
