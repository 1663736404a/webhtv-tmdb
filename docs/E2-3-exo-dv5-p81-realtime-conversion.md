# E2-3：Exo DV5 偏色与 P5 转 P8.1 实时转换评估

- 任务 ID：`E2-3`
- 类别：Exo / Dolby Vision / 新播放行为
- 状态：评估完成，未批准实现
- 当前结论：拒绝“只实时改写 Profile 5 RPU 和 codec/CSD，就作为 Profile 8.1 交给 MediaCodec 硬解”的生产方案。
- 推荐的下一动作：用户批准后，先实施独立的 `E2-3a` 安全修复，停止把不受支持的 DV5 当普通 HDR10/HEVC 播放；保留原生 DV5，无法原生播放时明确失败或切换到已验证的 MPV GPU Dolby Vision 路径。
- 唯一文档：`docs/E2-3-exo-dv5-p81-realtime-conversion.md`

## 1. 决策摘要

用户观察到的绿色/紫色偏色，与当前 WebHTV Exo 路径高度一致：平台 Dolby Vision renderer 无法认领 DV5 时，第二个 renderer 会把 `dvhe.05`/`dvh1.05` 无条件改报为普通 H.265、BT.2020 limited-range、ST 2084，然后把原始 Profile 5 access unit 送给普通 HEVC MediaCodec。Profile 5 的基础层是 Dolby IPT、full-range，不是 HDR10 兼容基础层；只改 `Format` 不会改视频像素语义，因此存在确定性的错误颜色风险。

libdovi 确实提供 Profile 5 RPU 到 Profile 8.1 RPU 的转换，但维护者和源码都明确其边界：它转换 RPU，不把 Profile 5 的 IPT 视频层转换成 HDR10 视频层。该功能用于把 P5 RPU 配合“同一内容已有的 HDR10 视频版本”使用；不能把一个原始 P5 视频原地变成标准 P8.1。

因此：

1. AI 回复中“P5 实时改 RPU后，P8.1 硬解即可得到正确颜色”的核心结论不成立。
2. ExoplayerHdrUtils 是有价值的 P7->P8 RPU 工具，但当前公开实现只在首帧识别为 Profile 7 时转换；它没有提供完整的 P5 视频色彩转换。
3. `SampleStream`、`Extractor` 或 `TrackOutput` 只是数据拦截位置选择，不能补上缺失的 IPT->HDR10 视频层变换。
4. 最小正确修复不是扩展 P5 转换，而是先禁止当前 P5 伪 HDR10 fallback，避免继续稳定地产生偏色。

结论置信度：高。尚缺的 Dolby 闭源规范全文和目标设备实机样片，不会改变“libdovi 的该功能只改 RPU、当前 App 只改 Format”这两个直接事实；它们只影响未来是否值得建立非标准设备实验。

## 2. 范围、基线与权限

- 权限：assessment-only；本轮不修改 App、Media3、nextlib、JNI、AAR、native lock 或运行时行为。
- 本地分支/HEAD：`exo-dv5` / `a20a31a7c6be2454459db68ec41b7cebf824d1a6`。
- 起始 worktree：无预先存在的脏文件。
- 本地 Media3：`e3e922d5c01bc0b564849940fe589daf37360d15`，版本 `1.11.0-alpha01-fongmi`。
- 本地 ExoplayerHdrUtils：Maven `0.4.0`；缓存 AAR SHA-256 `06979d42ebca6869514878e29b499f79e286bad18018420fe99c9567178f396e`。
- 上游 ExoplayerHdrUtils：`a791274f137b301fd9c0b7eb47d4fd18dbacbe30`，tag `v0.4.1`。
- 上游 dovi_tool/libdovi：`bbd5f56bdabc5386b80bcd6e1ff4d4c8efbb9d23`；发布版 `dovi_tool 2.3.3` / `libdovi 3.4.0`。
- 排除：MPV native 实现、真实转码服务、播放器自动切核策略、DRM 绕过、依赖升级和二进制发布。
- 回滚锚点：本轮仅文档；删除总索引新增行和本文件即可回滚评估记录。

## 3. 决策问题与可证伪假设

决策问题：能否在不重编码 Profile 5 HEVC 视频层的前提下，只改写每帧 RPU、codec string 和 Dolby Vision configuration record，生成可由 Android Profile 8.1 MediaCodec 正确显示的标准码流？

- 提议假设：P5 和 P8.1 的差异可由 RPU/信令改写补齐，原 HEVC VCL 可保持不变。
- 反假设：P5 的视频基础层颜色表示与 P8.1 HDR10 基础层不同；RPU-only 转换不能生成标准 P8.1 视频，普通 HEVC fallback 也会偏色。
- 区分证据：libdovi 实际改动范围、维护者对 P5->P8.1 用途的说明、P5/P8.1 profile 定义、本地 renderer/Extractor 的真实数据流，以及离线转换前后是否只有 RPU 发生变化。

证据支持反假设。

## 4. 当前 WebHTV 因果链

### 4.1 原生路径优先是正确的

`ExoUtil.FfmpegRenderersFactory.buildVideoRenderers()` 先添加平台 MediaCodec renderer，再追加 `DolbyVisionHdr10FallbackRenderer`。注释也说明后者只应在平台 renderer 无法认领 DV5/DV7 时使用：

- `app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java:829`
- `app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java:847`

所以支持 DV5 的设备仍应走原生 Dolby Vision decoder；这部分策略应保留。

### 4.2 P5 fallback 只改声明，不改码流或像素

`shouldUseDolbyVisionHdr10Fallback()` 对 Profile 5 无条件返回 `true`，不受 DV7 设置开关影响：

- `app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java:977`
- `app/src/test/java/com/fongmi/android/tv/player/exo/ExoDolbyVisionFallbackPolicyTest.java:21`

fallback renderer 随后用普通 HEVC `Format` 查询和配置 decoder：

- `app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java:920`
- `app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java:929`
- `app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java:935`

`asHdr10()` 只把 MIME 改成 `video/hevc`、清空 codecs，并把颜色标成 BT.2020 limited/ST2084：

- `app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java:964`

这不会把 P5 IPT/full-range 视频层转换成 HDR10 YCbCr/limited-range。用户观察到的绿紫偏色与该错误解释一致。

### 4.3 现有逐帧转换明确只服务 Profile 7

`DolbyVisionP81ExtractorsFactory` 的路径判断、codec/CSD 改写和 transformer 激活均只接受 Profile 7：

- `app/src/main/java/com/fongmi/android/tv/player/exo/DolbyVisionP81ExtractorsFactory.java:121`
- `app/src/main/java/com/fongmi/android/tv/player/exo/DolbyVisionP81ExtractorsFactory.java:148`
- `app/src/main/java/com/fongmi/android/tv/player/exo/DolbyVisionP81ExtractorsFactory.java:156`
- `app/src/main/java/com/fongmi/android/tv/player/exo/DolbyVisionP81ExtractorsFactory.java:385`

逐 access unit 调用 `HevcFrameTransformer` 后只验证输出中存在 Profile 8 RPU；它不检查或转换 VCL 视频层的颜色表示：

- `app/src/main/java/com/fongmi/android/tv/player/exo/DolbyVisionP81ExtractorsFactory.java:489`
- `app/src/main/java/com/fongmi/android/tv/player/exo/DolbyVisionP81ExtractorsFactory.java:553`

因此把 `isProfile7()` 扩成 P5、再重用这条链，会得到“Profile 8 RPU + 原 P5 IPT VCL”，没有解决根因。

## 5. AI 回复逐项核验

| AI 主张 | 核验结果 | 决策影响 |
| --- | --- | --- |
| libdovi mode 3 能把 P5 转 P8.1 | 只对 RPU 成立。当前 CLI 的 mode 3 映射到 `ConversionMode::To81`；当前 C API 的数字 2/3 又都映射到 To81，数字随接口/版本不同。 | 不能把 CLI 数字直接写进 JNI 设计；更不能把 RPU 转换称为视频转换。 |
| 保持 HEVC 切片不变，只替换 RPU | 对该工具的实现描述成立，但不足以得到标准 P8.1。P5 VCL 是 IPT 基础层，P8.1要求 HDR10 基础层。 | 核心方案拒绝。 |
| 修改 VUI/配置即可补齐 | 错误。VUI/CSD 是信令，不会变换已编码像素。把 P5 标成 BT.2020 limited/ST2084 会强化错误解释。 | 不允许以 metadata rewrite 代替颜色变换。 |
| 推荐 SampleStream 包装，少改 Exo | ExoplayerHdrUtils 的包装器确实能改 buffer，但公开实现不改 `RESULT_FORMAT_READ`，转换判型也只支持 P7。WebHTV 已有更早的 TrackOutput 格式/样本协同点。 | 拦截层不是主要问题；直接照搬会丢失本地 capability/CSD/session/fallback 约束。 |
| MP4/AVCC、MKV/Annex-B 都在转换器自行处理 | 需要按 Media3 extractor 的实际输出验证，不能从容器输入封装直接推断 decoder sample 格式。现有 JNI NAL scanner按 Annex-B 工作。 | 必须用各容器真实 fixture 验证，不应先造双解析器。 |
| 转换失败可原样返回 | 对已将 Format 锁为 P8.1 的会话不安全。WebHTV 当前 P7 路径正确地把无效 P8.1 当 fatal。 | P5 实验也必须原子失败，不能输出“P8.1 声明 + P5 RPU/VCL”。 |
| 不支持 DV5 的设备可得到接近 HDR10 的正确颜色 | 无证据且与 P5 基础层定义、维护者说明和当前偏色现象冲突。 | 不能作为产品目标或验收结论。 |

## 6. 上游与外部证据

访问日期均为 2026-08-27。

| 主张 | 来源 | 等级 | WebHTV 适用性与影响 |
| --- | --- | --- | --- |
| P5 是 IPT 基础层；P8.1 是 HDR10 基础层 | `quietvoid/dovi_tool@bbd5f56bdabc5386b80bcd6e1ff4d4c8efbb9d23`, `dolby_vision/src/rpu/generate.rs` | A | 直接否定“同一 VCL 只换信令即成为标准 P8.1”。 |
| P5->P8.1 函数只改 RPU header/mapping/系数 | 同 revision，`dolby_vision/src/rpu/dovi_rpu.rs::p5_to_p81()` | A | 函数不接触 HEVC VCL；不能完成视频层颜色转换。 |
| libdovi 有确定的 P5 RPU->P8 RPU 测试向量 | 同 revision，`src/tests/rpu.rs::profile5_to_p81*` | A | 证明 RPU 操作真实存在，但测试不声称视频画面正确。 |
| mode 编号不是稳定跨接口契约 | 同 revision，`src/commands/mod.rs`、`dolby_vision/src/rpu/mod.rs`、`capi.rs` | A | CLI mode 3、Rust/C API mode 2/3 和旧文档不可混用。 |
| “Profile 5 video as well”不能由 mode 3 转换 | [dovi_tool issue #64](https://github.com/quietvoid/dovi_tool/issues/64#issuecomment-960882323)；维护者后续明确 P5 视频需处理回普通 HDR10 | B | 与源码范围一致，直接反驳 AI 的实时 bitstream-only 目标。 |
| P5 RPU 转 P8 的原始用途是配合同一内容的 HDR10 视频版本 | [dovi_tool PR #16](https://github.com/quietvoid/dovi_tool/pull/16#issuecomment-850835856) | B | 原始 P5 VCL 不是该转换的合格输入视频层。 |
| Profile 5 按 Dolby 文档使用 IPT、PQ、full range | [dovi_tool PR #16 维护者引用](https://github.com/quietvoid/dovi_tool/pull/16#issuecomment-851080568) | B | Dolby 正式文档页面当前未能直接下载；该引用与 libdovi 源码/当前输入向量相符。 |
| ExoplayerHdrUtils 公开能力是 P7 FEL/MEL->P8 | `suyash192/ExoplayerHdrUtils@a791274f137b301fd9c0b7eb47d4fd18dbacbe30`, README 与 `HevcFrameTransformer.kt` | A/B | 没有 P5 product path；`doviProfile != 7` 时保持 RPU。 |
| Android 能按具体 Dolby profile/level查询 decoder | Android `MediaCodecInfo.CodecProfileLevel` 与 `CodecCapabilities.isFormatSupported()`；本地 Media3 profile mapping | A | 应先查询真正的 P5 Dolby decoder；普通 HEVC decoder 不能替代该能力证明。 |
| 成熟的正确颜色 fallback 需要 Dolby mapping 渲染链 | 本仓库 `third_party/mpv-native-build.md` 的 MPV/libplacebo GPU DV5 mapping、AImageReader/AHardwareBuffer 约束 | B（成熟相关项目实现） | WebHTV 已有更合适的 MPV 实验/回退基础，不应在 Exo SampleStream 重造颜色处理链。 |
| 用户实际看到绿紫偏色 | 本任务用户报告；本地代码静态因果链 | C/A 组合 | 与 P5 被普通 HEVC/HDR10解释的预期故障一致，仍需真实日志确认所选 renderer/decoder。 |

### 未取得或不适用的证据类别

- Dolby Profile and Level 正式规范全文：Media3 源码引用的 Dolby Salesforce/professional-support 页面在本次环境未提供可直接读取的全文。该缺口禁止声称某个自定义 P5->P8.1 流“标准合规”，但不影响判断当前代码和 libdovi 都没有转换视频层。
- 学术论文：该问题是闭源 Dolby profile/设备实现与具体 bitstream contract，不存在能替代规范、源码或实机验证的适用论文；不以无关色彩科学论文扩大评估。
- 独立 benchmark：当前阶段拒绝的是正确性不成立的方案，性能数据不能修复颜色语义；只有未来 GPU/软件映射实验获批后才需要 CPU/GPU/功耗基线。

## 7. 离线验证

使用上游发布的 `dovi_tool 2.3.3`（universal macOS）和仓库测试向量 `assets/tests/profile5.bin`：

```text
dovi_tool info -i profile5.bin -f 0
# dovi_profile=5, vdr_rpu_profile=0, bl_video_full_range_flag=true

dovi_tool editor -i profile5.bin \
  -j assets/editor_examples/p5_to_p81.json \
  --rpu-out profile5-p81.bin

dovi_tool info -i profile5-p81.bin -f 0
# dovi_profile=8, vdr_rpu_profile=1, bl_video_full_range_flag=false
```

- 输入 SHA-256：`f2e6a33cdcad3bbe2be1baf0af6ff53a41dc63431477d29d138dbe4167dc13f2`
- 输出 SHA-256：`3a493b711375dc9a20801b3c23ea0c56dce1f1af2b8c2f2c8e602aed82bcf513`
- 结论：RPU 可确定性改写为 Profile 8；该命令没有视频 VCL 输入，也没有生成 HDR10 视频层，不能验证或宣称正确画面。
- 补充：直接从源码运行 `cargo test profile5_to_p81` 因本机 Rust `1.92.0` 低于上游要求 `1.95.0` 而未运行；未把该检查记为通过。

## 8. 方案比较

| 方案 | 正确性 | 范围/维护 | 性能 | 结论 |
| --- | --- | --- | --- | --- |
| A. 保持现状 | 不支持 P5 的设备会把 IPT VCL 当 HDR10/HEVC，已观察到偏色 | 无改动，但保留错误行为 | 硬解低成本 | 拒绝。 |
| B. AI 提案：P5 RPU-only 转 P8.1并改 codec/CSD | 生成的 RPU可为 P8，但原 P5 VCL不是 HDR10基础层；标准正确性不成立 | 需 fork JNI/库和扩展本地 DV 状态机 | RPU开销小，但无意义 | 拒绝作为生产方案；最多作为明确标注的厂商非标准实验。 |
| C. 窄安全修复：原生 P5 或明确不支持 | 不再制造错误颜色；支持 P5 的设备保持原生 | 只收窄 Exo fallback policy、诊断和测试 | 无新增热路径 | 推荐先做 `E2-3a`。 |
| D. 选择真实 HDR10/P8.1 替代源 | 若服务器/资源本身有 HDR10视频层，可正确播放 | 需要源选择/转码服务，不属于本 Exo bitstream patch | 客户端硬解；服务端有存储/转码成本 | 可行，但属于新上游/服务能力。 |
| E. Exo 自建完整 IPT->显示映射 | 理论可行：解码 P5、保留 RPU、对 raw frame 做 Dolby mapping后输出 | 需要自定义 renderer、ImageReader/AHB/纹理和 libplacebo等价映射；远超 SampleStream | 4K实时性能、功耗、Surface生命周期风险高 | 仅在用户明确要求 Exo 专用实验且 MPV 不能满足时立项。 |
| F. 复用现有 MPV GPU DV5 mapping | 已有 libplacebo、RPU 和 raw frame mapping 架构基础 | 跨播放器选择策略需另评估，但不需在 Exo 重造图形管线 | 仍需目标设备实测 | 对不支持原生 DV5 的非 DRM 内容，比方案 E 更现实。 |

## 9. 推荐实施阶段：E2-3a

本评估只推荐一个最小、独立、可回滚的 Exo 安全修复；用户批准前不得实现。

目标行为：

1. 平台 Dolby Vision renderer 可支持 `dvhe.05`/`dvh1.05` 时保持原始 Format、CSD、RPU 和 access unit，走原生 DV5。
2. 平台 renderer 不支持 P5 时，`DolbyVisionHdr10FallbackRenderer` 不再认领该轨道，不再把 P5 标成 HEVC/HDR10。
3. 非 DRM 且产品已有明确的播放器切换入口时，可提示/触发现有 MPV 路径；没有已验证替代时返回明确的“设备不支持 DV5”错误，优先于错误颜色。
4. DV7->P8.1、DV7 HDR10基础层 fallback、现有 P8.1 session lock、CSD 修复、DRM和 MPV native 均保持不变。

预计代码范围（需在批准实现时重新声明）：

- `app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java`
- `app/src/test/java/com/fongmi/android/tv/player/exo/ExoDolbyVisionFallbackPolicyTest.java`
- 必要时的用户可见错误/诊断映射一处
- 本任务文档

不应在 `E2-3a` 中修改 ExoplayerHdrUtils、libdovi、Media3 AAR、nextlib或 native 二进制。

## 10. 若未来批准非标准 P5->P8.1 实验

只有用户明确接受“设备兼容性实验、非标准合规方案”的定位后，才可另开实施单元。实验也不能只证明 decoder 初始化成功，必须满足：

- 真实 P5 MP4/MKV样片；每个样片保留 hash、profile/level、RPU CM版本、分辨率/帧率/码率和参考画面。
- 至少三类设备：原生 P5支持、P8.1支持但P5不支持、仅普通 HDR10/HEVC支持。
- 记录实际 renderer、decoder name、输入/输出 codec/CSD、RPU profile、首帧、READY、seek/flush、换集和 decoder error。
- 与原生 DV5或可信软件映射参考逐场景对比；禁止只看“DV标志亮了”或“能出帧”。肤色、中性灰、黑白字幕、暗场、饱和红绿蓝必须无绿紫偏色。
- 普通 HDR10/HEVC decoder 不得被列为 P5正确 fallback。
- DRM P5不做 sample/RPU 改写；只能原生支持或明确不支持。
- 若改 codec/CSD，必须保证信令、RPU和视频层三者一致；任何一帧转换失败立即终止该实验会话，不得原样混出。
- 4K样片测量启动、seek、掉帧、CPU、内存、JNI分配、功耗/温度；至少三次可比运行后再讨论性能。

只要任一设备出现偏色、黑屏、decoder接受但无首帧，或只有修改信令才能点亮 DV 标志而画面无参考一致性，该实验即判失败并回滚。

## 11. 验收与回滚

### E2-3a 最小验收

- 单测：P5不再由 HDR10 fallback renderer认领；P7开关行为不变。
- 编译：受影响 Mobile/Leanback Java/Kotlin target。
- 实机：原生 P5支持设备仍选择 Dolby Vision decoder且颜色正确；不支持设备不再选择普通 HEVC renderer输出绿紫画面。
- 邻接回归：DV7原生、DV7->P8.1、DV7 HDR10 BL、HDR10、普通 HEVC各一条。
- 诊断：明确记录 source profile、实际 decoder/renderer和“不支持 P5”原因。

### 回滚

- E2-3a必须是独立原子提交和 recovery tag。
- 回滚只恢复 P5 renderer认领策略和对应诊断/测试；不得回滚 `E2-1`、`E2-2`、Media3/nextlib AAR或 MPV native。

## 12. 当前决定与下一动作

- 观察：当前 P5 fallback 与 P5基础层语义冲突，能解释用户的绿紫偏色。
- 验证事实：libdovi P5->P8.1功能转换 RPU，不转换视频层。
- 推断：目标设备很可能没有由平台 renderer认领 P5，随后落入普通 HEVC fallback；需用播放日志确认实际 decoder name。
- 推荐：拒绝 AI 的 RPU-only生产方案，批准 `E2-3a` 先消除错误颜色路径。
- 用户决定：待定。
- 唯一下一动作：用户决定是否批准 `E2-3a` 安全修复；未批准前停止在本任务中修改运行代码。

## Checkpoint 1：2026-08-27 assessment handoff

- Completed：完成本地 Exo DV5/DV7/P8.1 数据流审查、ExoplayerHdrUtils 与 libdovi 源码核验、dovi_tool 2.3.3 RPU-only 离线转换和方案比较。
- Source identities：Media3 fork `e3e922d5c01bc0b564849940fe589daf37360d15`；ExoplayerHdrUtils `a791274f137b301fd9c0b7eb47d4fd18dbacbe30`；dovi_tool `bbd5f56bdabc5386b80bcd6e1ff4d4c8efbb9d23`。
- Decisions/evidence：P5 是 IPT/full-range 基础层，P8.1 是 HDR10 基础层；libdovi mode 3/To81 只改 RPU；当前 P5 fallback 只改 Format 并保留 P5 VCL，足以解释绿紫偏色；拒绝 RPU-only 生产转码。
- Workspace：分支 `exo-dv5`，HEAD `a20a31a7c6be2454459db68ec41b7cebf824d1a6`；任务守卫 `E2-3` assessment active；未发现受保护脏路径。
- Files/artifacts changed：`docs/E2-3-exo-dv5-p81-realtime-conversion.md`、总评估索引；无代码、锁、AAR、native artifact 变化。
- Validation：`git diff --check` 通过；索引与任务文档已核对；`dovi_tool 2.3.3` 将测试 RPU 从 profile 5 改为 profile 8（输入/输出 SHA-256 记录于上文）；源码 cargo test 因 Rust 1.92 < 1.95 未运行；正确 checkpoint 脚本待本次最终联合验证执行。
- Rollback anchor：当前 HEAD；assessment 记录可通过回滚本任务新增文档和索引行恢复。
- Unresolved：缺少目标 P5 样片、目标设备实机日志和 Dolby 规范全文；这些只影响未来实验，不支持当前方案上线。
- Next action：等待用户批准或拒绝 `E2-3a`；批准前不改运行代码。
