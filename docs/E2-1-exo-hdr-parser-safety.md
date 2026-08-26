# E2-1：Exo HDR / Dolby Vision parser safety

- 任务 ID：`E2-1`
- 所属分类：Exo
- 状态：已完成
- 唯一任务文档：`docs/E2-1-exo-hdr-parser-safety.md`
- 上游基线：`FongMi/media@e3e922d5c01bc0b564849940fe589daf37360d15`
- 目标行为来源：`FongMi/media@f70e4b6f14d9f3b38ef953be80c53184f9c50bed` 与 `FongMi/media@0cefd3ceec27444cf8faf02486b472bab39109fe`
- 实施恢复点：`recovery/e2-1-baseline-20260826-b2eccc357662`

## Recovery anchor

- 目标：让 Exo 遇到截断、过短或越界的 Dolby Vision/HDR 配置时安全拒绝输入，并按 CTA-861 规范编码 Matroska 最低 mastering luminance；正常 MP4/MKV、现有 DV RPU 和 fallback 行为保持不变。
- 接受标准：Media3 container/extractor 定向测试通过；独立 parser-safety patch 可在现有四个 Media3 补丁之后重放；只发布受影响的 `media3-container`/`media3-extractor` AAR 与 sources；App Mobile/Leanback Java 编译通过；无初始脏文件被覆盖或提交。
- 允许路径：本任务文档、主评估索引、`third_party/patches/media3-exo-hdr-parser-safety.patch`、`scripts/build_media_deps.sh`、`third_party/media-lock.json`、Media3 container/extractor Maven 产物。
- 保护路径：`AGENTS.md`、`docs/agents-md-effective-constraints-review-2026-08-21.md`；另有主仓库 `third_party/sources/media` 的预存修改，始终不直接操作。
- 当前状态：已完成实施、定向测试、五个 patch 顺序重放、Media3 release AAR 发布、主项目 Mobile/Leanback Java 编译、原子提交和 recovery tag；E2-1 已完成。
- 已完成证据：JDK 21 下三个定向测试类与 `:lib-extractor:compileDebugJavaWithJavac` 通过（`BUILD SUCCESSFUL in 5m 8s`）；五个 patch 顺序重放成功；`lib-container`/`lib-extractor` 发布成功（`BUILD SUCCESSFUL in 3m 30s`）；AAR/sources 哈希已写入 lock。
- 当前未解决风险：真实厂商 DV codec 和设备级 HDR 输出尚未验证；这些属于设备验收风险，不阻塞本阶段 parser safety 合并。治理守卫已移除文件数门禁，E2-1 可按单一逻辑单元提交。
- 下一动作：进入队列中的下一项 Exo 任务 `E3-1a`，先评估再等待用户批准。

## 实际能力与范围

本阶段改善的是“坏的媒体文件不会把播放器拖崩”：短 Dolby Vision 配置记录、声明错误版本的配置，以及 MP4 中超出 sample entry/输入边界的 DV box 会被明确拒绝并转为受控解析错误；Matroska 的最低 mastering luminance 会按标准的 `0.0001 cd/m²` 单位写入 HDR 静态元数据。因此正常 HDR/DV 文件继续播放，损坏文件更可能得到可诊断的解析失败，而不是越界读、异常崩溃或错误 HDR 亮度。

不包含：renderer/output policy/tone mapping、DV7→P8.1 CSD（已由 E2-2 完成）、FFmpeg `dovi_rpu` BSF、MPV native、现有 Matroska RPU、字幕、网络与 deferred Cues 行为。

## 上游提交台账与关联

| 仓库 | 完整 commit | 关联与处置 |
| --- | --- | --- |
| `FongMi/media` | `f70e4b6f14d9f3b38ef953be80c53184f9c50bed` | Matroska minimum mastering luminance 单位修复；窄移植 `MatroskaExtractor` hunk。 |
| `FongMi/media` | `0cefd3ceec27444cf8faf02486b472bab39109fe` | 同时包含 parser safety、DV CSD/compatible BL、renderer/output policy；本阶段只取短 DV config、major-version 和 MP4 box-boundary hunk，明确不取 CSD/output policy。 |
| `FongMi/media` | `b63139c6432caa3f058e7f0496f0d754aa0eaa93` | HLS/TS DV 语义已由当前 fork 等价覆盖，仅登记，不重复移植。 |
| `FongMi/media` | `249774647b026e16b56467eb5d79479816f79f11` | TS DV descriptor 已由当前 fork 语义覆盖，仅登记。 |
| `FongMi/media` | `08c664eb8a213a956ff2c8b3d0fcea49902a81fa` | H.265 config parsing 已由当前 fork 等价覆盖，仅登记。 |
| `FongMi/FFmpeg` | `177f090e0503b7e013922ca903bde14b1c375f18` | E1 已采用的 FFmpeg 9.0.1 同源 revision；`dovi_rpu convert=p81` 仍是 C2 参考，不在 E2-1 调用。 |

## 设计决策

### 当前项目已有实现

- `DolbyVisionConfig.parse()` 已能解析合法配置，但旧路径对长度和 `dv_version_major` 缺少硬性拒绝。
- `BoxParser` 已接入 `dvcC/dvvC/dvwC`，但旧路径直接从父 atom 读取配置，malformed child atom 可能越过 sample entry 或输入 limit。
- `MatroskaExtractor` 已写入 mastering luminance，但最低值按错误的 1 nits 量纲写入，低亮度 HDR 元数据会失真。
- E2-2 的 `DolbyVisionP81ExtractorsFactory`、现有 HDR10 fallback、Matroska Dolby Vision RPU patch、deferred Cues 和 WebHTV datasource/seek safeguards 必须保持原样。

### 备选方案

1. 不变：不增加 parser 防护，继续承受 malformed input 的崩溃/错误元数据风险；拒绝。
2. 原样移植 `0cefd3ce...`：会连带 CSD、compatible BL 和 renderer/output policy，超出本阶段已批准范围；拒绝。
3. WebHTV 窄适配：只加入 parser 输入长度、版本、box 边界检查和 CTA-861 luminance 单位修正，保留本地 DV/RPU/CSD/fallback/renderer 语义；采用。

### 最佳实践结论

采用方案 3。解析器在读取外部媒体数据前验证最小长度、版本和容器边界，是防止越界访问和错误状态传播的通用实践；HDR 静态元数据按规范单位编码可避免显示设备将低亮度值放大约四个数量级。对 WebHTV，必须把上游大提交拆成独立 hunk，避免覆盖本地 Dolby Vision RPU、P8.1 CSD 和用户策略。

## 风险、影响与验收

- 收益：畸形 DV 配置和截断 MP4 更早、可诊断地失败；合法 HDR10/DV 文件的 mastering luminance 更准确；不新增运行时线程、网络访问或 native ABI。
- 缺点与风险：过严的 boundary check 可能暴露此前被容忍的非标准文件；`ParserException` 传播路径需要定向测试；真实厂商 decoder、DV7 MEL/FEL 与设备 HDR 输出仍需后续样片/实机确认。
- 现有功能关系：不改变 codec string、DV7→P8.1 CSD、RPU 拼接、fallback 选择、字幕、网络、seek 或 MPV；只在输入非法时改变结果为受控失败。
- 兼容性：Media3 API/ABI 不变；只更新 container/extractor AAR 内容。合法文件格式兼容性目标不变，极端非标准 malformed 文件可能由“偶尔播放/崩溃”变为“明确失败”。
- 性能与包体积：每个 DV box 增加常数时间边界判断；AAR 代码增量约数十行，包体积影响可忽略；无额外 native 库。
- 是否调整上游原方案：是。只保留 parser safety/luminance hunk；不采用 output policy、tone mapping 或 CSD 方案，以符合 WebHTV 已实施的 E2-2 和本地 renderer/fallback 契约。
- 回滚：恢复 `recovery/e2-1-baseline-20260826-b2eccc357662`，或只回滚 E2-1 原子提交/对应 Media3 AAR；不回滚 E1、E2-2、MPV 或预存脏文件。

## 实施记录

### 2026-08-26：初始隔离编辑（未验证）

- 隔离 checkout：`/private/tmp/e21-media-clean`，基线 `e3e922d5c01bc0b564849940fe589daf37360d15`。
- 修改文件：`libraries/container/src/main/java/androidx/media3/container/DolbyVisionConfig.java`、`libraries/extractor/src/main/java/androidx/media3/extractor/mp4/BoxParser.java`、`libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java`。
- 内容：短配置/major version 拒绝；DV box 最小长度与 sample-entry/input-limit 边界检查；最低 mastering luminance 按 `10000` 倍缩放。
- 验证：三组测试源码已补齐且 Java 编译到测试执行阶段；首次 Robolectric 运行因未使用项目所需的 JDK 21 而环境失败，尚无产品断言失败。
- 下一动作：已使用 `/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` 重跑相同定向测试与 extractor 编译并通过；继续发布受影响 AAR 与 sources。

### 2026-08-26：定向验证与补丁固化

- 测试：使用 `/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`，运行 `:lib-container:testDebugUnitTest --tests androidx.media3.container.DolbyVisionConfigTest :lib-extractor:testDebugUnitTest --tests androidx.media3.extractor.mp4.BoxParserTest --tests androidx.media3.extractor.mkv.MatroskaExtractorNonParameterizedTest :lib-extractor:compileDebugJavaWithJavac`，结果 `BUILD SUCCESSFUL in 5m 8s`。
- 覆盖：短于 4 字节、major version 异常、合法 DV box、sample-entry 越界、输入截断、最低 mastering luminance `0.0001 -> 1` 与 `0.005 -> 50`。
- Patch：`third_party/patches/media3-exo-hdr-parser-safety.patch`；基线为四个既有 Media3 patch 已应用后的树；SHA-256 `a6a8bbf95630e70938dacde7e36c97ed330c5c426644893090cfc3280dc6ed20`。
- 重放：从 `e3e922d5c01bc0b564849940fe589daf37360d15` 新 checkout，按 `media3-danmaku-live`、`media3-dolby-vision-matroska`、`media3-upstream-playback-fixes-2026-08`、`media3-deferred-cues`、`media3-exo-hdr-parser-safety` 顺序 `git apply --check/apply` 成功。
- 备注：首次 Robolectric 运行因未使用项目所需的 JDK 21 而失败；切换到 JDK 21 后无测试失败，已作为环境问题记录，不改变产品代码。

### 2026-08-26：主项目接线验证（已通过）

- 在 JDK 21 下运行 `./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac --no-daemon`，结果 `BUILD SUCCESSFUL in 42s`；Mobile 与 Leanback arm64-v8a Debug Java 编译均通过。
- lock 已更新为补丁实际 SHA-256 `a6a8bbf95630e70938dacde7e36c97ed330c5c426644893090cfc3280dc6ed20`。
- 实施提交：`e19289a3c9871563f891500bdc2d42be6be23f3d`。
- 恢复 tag：`recovery/E2-1-final/20260826132916-e19289a3c987`。
- 完成状态：E2-1 已完成；真实厂商 DV codec 和设备级 HDR 输出仍属于后续设备验收风险，不阻塞本阶段合并。
