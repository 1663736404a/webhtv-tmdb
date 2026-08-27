# E9-3: Exo DV5 Vulkan renderer

状态：实施已获用户授权，先实现默认关闭的硬解 GPU 映射原型；真机验收前不替换现有生产路径。

## 1. 目标与边界

- 目标：在没有原生 Dolby Vision Profile 5 显示能力、但普通 HEVC MediaCodec 能输出可采样 10-bit `AHardwareBuffer` 的设备上，让 Exo 使用硬解并通过 Vulkan/libplacebo 恢复正确色彩。
- 保留：Exo 的解封装、网络、音频、字幕、轨道、时钟、帧释放、掉帧统计和 PlayerView 协议。
- 不做：P5->P8.1 码流伪转换、把 P5 冒充 HDR10 后 Surface 直出、复用 `libmpv.so` 私有状态、DV7 FEL 双层合成、DRM 安全视频输出。
- 首个实施单元：独立 `VideoSink`/native renderer 原型，默认关闭，仅 Profile 5、非 DRM、Vulkan 能力通过时可被显式启用。

## 2. 基线

- App：`742de1b08b01e6022517f6200a7502d1326301c9`，分支 `exo-dv5`，开始时工作树干净。
- Media3：WebHTV `e3e922d5c01bc0b564849940fe589daf37360d15`，版本 `1.11.0-alpha01-fongmi`。
- MPV 实证链：MPV `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`、FFmpeg `04482c8d13ac27b2a9fe93f5d388929eef8af5f4`、libplacebo `b694a21bf2dc176c1e98b8a13c6421a0de5f3da5`。
- 设备证据：用户确认当前 WebHTV MPV 的 `MediaCodec + Vulkan + gpu-next` 在目标设备播放 DV5 时颜色正常，至少不再紫绿。
- 当前 Exo 缺口：`ExoUtil.DolbyVisionHdr10FallbackRenderer` 把 Profile 5 改报普通 HEVC/HDR10 并直接输出 Surface，没有进行 Dolby IPT/RPU 映射。

## 3. 决策证据

访问日期均为 2026-08-27。

| 证据 | 等级 | 支持的结论 | 局限/影响 |
| --- | --- | --- | --- |
| Android `MediaFormat.KEY_COLOR_TRANSFER_REQUEST` 官方 API 文档 | A | API 31+ codec 可接受 transfer/tone-map 请求；请求必须在配置后验证 | 只适用于设备已有可工作的 DV decoder，不解决普通 HEVC decoder 的 raw DV5 GPU 映射 |
| Android NDK `AImageReader` / `AHardwareBuffer` 与 Vulkan Android external-memory API | A | codec 可输出到 `AIMAGE_FORMAT_PRIVATE` reader，buffer 可用 GPU sampled usage 导入 Vulkan | 具体 external format、10-bit 精度、fence 和驱动行为依设备而异 |
| Media3 `VideoSink` / `MediaCodecVideoRenderer.Builder.setVideoSink`，WebHTV 锁定线 `e3e922d...` | A | 可保留 Media3 renderer 的 codec/时钟/掉帧逻辑，仅替换 codec 输入 Surface 和最终渲染 | `VideoSink` 是 Unstable API，升级 Media3 时必须有编译契约测试 |
| FongMi/media `0cefd3ceec27444cf8faf02486b472bab39109fe` | A | Profile 5 不能作为标准 HDR10 BL；有 DV codec 时优先请求并验证 codec tone-map | GPU fallback 必须位于 codec tone-map 之后，不能替代原生能力 |
| FongMi/FFmpeg `eb107bbafe37442065e42b4f2d410f371b758143` 与 `15b73698835285d68f9615691dd4dfc04422f28e` | A | MediaCodec 硬解链需保留逐帧 RPU/解析元数据，才能在 GPU 阶段应用 DV5 映射 | Exo 原生 MediaCodec 不会自动提供 FFmpeg `AVFrame` side data，需在输入 AU 上自行提取 |
| FongMi/mpv `c7fef70644b3d506340e113689a5923f324c861d` 及当前锁定树 | A | `gpu-next` 能对 raw MediaCodec DV5 帧应用 GPU mapping | mpv 的 VO/queue/decoder 状态机不能直接嵌进 Exo |
| 当前 `hwdec_aimagereader.c` 与 Vulkan direct/stable 实现 | A | 使用 PTS 匹配 AImage；DV5 采样必须保持 full range、RGB identity 和原始 Y/Cb/Cr 分量语义 | 需要保留 AImage 到 GPU fence 完成，不能在回调中提前释放 |
| libplacebo `b694a21bf2dc176c1e98b8a13c6421a0de5f3da5` | A | 支持 `PL_COLOR_SYSTEM_DOLBYVISION`、RPU matrix/reshape、tone mapping 和 Vulkan 输出 | 必须独立构建/链接，不能依赖 `libmpv.so` 内部生命周期 |
| FongMi/media `7d0d1e3c572aee885ffbbfd6d8317f1f3a581910` | B | GLES/libplacebo renderer 提供了 Media3 Surface/lifecycle 参考和 Dolby metadata 映射参考 | 该提交是 FFmpeg 软件解码，不满足本任务硬解目标，只取 renderer 生命周期设计 |
| mpv-player/mpv issue #10287 与 mpv-android issue #1081 | B | 普通 MediaCodec-copy/错误 Surface 转换会紫绿；Android 硬解 GPU DV 路径具有设备依赖 | issue 不是规范，不能替代本项目真机验收 |

论文类证据不适用：本任务的决定因素是 Android codec/BufferQueue/Vulkan ABI 和实际开源实现，不是新的色彩科学算法；Dolby 专有规范也不提供可用于 Android 公共实现的完整生产契约。

## 4. 方案比较

### A. 不改

- 优点：无新增 native 风险。
- 缺点：无 DV5 decoder 的设备继续紫绿，当前 HEVC/HDR10 伪装语义错误。
- 结论：不能满足目标。

### B. P5->P8.1/RPU-only 转换

- 优点：Extractor 层改动小。
- 缺点：只改 RPU，不能把 P5 的 Dolby IPT 基础层转换成 HDR10 基础层。
- 结论：拒绝。

### C. 原样移植 MPV 播放器或调用 `libmpv.so` 内部 libplacebo

- 优点：目标设备已有成功实证。
- 缺点：会引入第二套 demux/clock/track/lifecycle；`libmpv.so` 的 libplacebo 符号和上下文不构成稳定 Exo API；二进制与回滚耦合。
- 结论：拒绝原样集成，只移植已经验证的 AImageReader/Vulkan/DV 表示规则。

### D. WebHTV 适配的 Media3 `VideoSink` + 独立 native Vulkan/libplacebo

- 优点：保留 Exo 主体；codec 输出 Surface、帧时序和显示 Surface 有清晰所有权；可以按能力和内容窄启用；MPV/Exo 二进制独立回滚。
- 缺点：需要新的 native 资产、RPU 提取、PTS 配对、Vulkan/AImageReader 生命周期和真机矩阵。
- 结论：推荐并实施。

## 5. 推荐架构

```text
Media3 Extractor / SampleStream
        |
        | HEVC access unit + input PTS
        v
MediaCodecVideoRenderer (ordinary HEVC decoder view for P5)
        |
        | codec output Surface
        v
ExoDoviVideoSink.getInputSurface()
        |
        v
AImageReader PRIVATE + GPU_SAMPLED_IMAGE
        |
        | AImage timestamp -> input PTS metadata map
        v
AHardwareBuffer -> Vulkan external image
        |
        | RGB_IDENTITY, full range, raw Cr/Y/Cb mapping
        v
libplacebo Dolby Vision reshape + tone map
        |
        v
PlayerView output Surface
```

### 5.1 Media3 ownership

- 用 `MediaCodecVideoRenderer.Builder.setVideoSink()` 接入，不复制 `MediaCodecRenderer` 状态机。
- `VideoSink.handleInputFrame()` 保存 renderer 提供的释放 handler；到计划释放时调用 handler，将 codec buffer 送进 AImageReader Surface。
- AImage timestamp 必须与 codec buffer PTS 精确匹配；seek/flush/stream change 清空帧、RPU 和 handler 队列。
- tunneling 在该路径禁用；自定义 GPU 渲染与 tunneling 不兼容。

### 5.2 RPU 所有权

- 在 MediaCodec 输入 AU 入队前扫描 HEVC NAL type 62，保留原样 AU 给 decoder，同时把 RPU 副本按 input PTS 送入 native metadata queue。
- MP4 length-prefixed 与 Annex-B 都要支持；解析失败为单帧失败，连续失败触发整次播放受控回退。
- RPU 解析和 `pl_dovi_metadata` 生成在 native 层完成；Java 不复制 Dolby 结构体。
- 不把 ExoplayerHdrUtils 0.4.0 当作 metadata API：其公开 JNI 只提供 frame transform/profile 信息，没有输出可供 libplacebo 使用的 RPU 映射结构。

### 5.3 Native 资产

- 新库独立命名为 `libexo_dovi_renderer.so`，两 ABI 成套构建。
- 只包含本路径需要的 AImageReader、Vulkan、libplacebo、RPU 解析和同步代码；不链接 mpv demux/player/VO 状态机。
- 首选 Vulkan direct AHardwareBuffer sampling；失败可回退 stable GPU conversion；均失败则声明能力不可用并回到下一 Exo renderer/MPV。
- 禁止运行时链接 `libmpv.so` 的内部 libplacebo 状态。

### 5.4 策略顺序

1. 显示和 decoder 均支持原生 DV5：原生 DV。
2. API 31+ DV decoder 接受 `KEY_COLOR_TRANSFER_REQUEST`：codec tone-map。
3. 非 DRM、普通 HEVC decoder、Vulkan/AHB probe 通过且用户/实验允许：本 E9-3 GPU mapping。
4. 否则：MPV `gpu-next` 或服务端转码；不得再把 Profile 5 无条件冒充 HDR10。

## 6. 安全与兼容约束

- DRM：`cryptoType != NONE` 一律不声明支持；安全 decoder/secure Surface 不进入自定义 GPU。
- API：最低运行 API 26，Vulkan external-memory/AHardwareBuffer 能力逐项探测；仅检查 Vulkan 版本号不够。
- 精度：源 buffer 必须证明为可用的 10-bit/external format；不允许静默降为 8-bit 后仍标记成功。
- 生命周期：Surface replacement、后台/前台、seek、flush、换集和 release 必须停止接收回调并等待/取消有限 fence。
- 队列：输入 RPU、codec handler、AImage 三者均有上限；按 PTS 丢弃 stale 项，不允许无界积累。
- 故障：初始化失败在 codec 启动前返回不支持；运行时连续映射失败报告可分类错误，由 App 只重建一次并回退。
- 输出：SDR 屏默认输出 BT.709 SDR；HDR10 屏可后续增加 BT.2020 PQ，首个单元不同时扩展两种输出策略。

## 7. 实施阶段

### E9-3a：契约与能力门控

- 新增纯 Java 的 Profile 5/DRM/API/Vulkan 路由策略、失败原因和单测。
- 新增 `VideoSink` 壳和 native capability bridge；native 不可用时不得抢占 track。
- 将现有 Profile 5 HEVC/HDR10 fallback 收窄为最后的显式兼容选项，不在本单元直接删除。

### E9-3b：硬解 Surface 与帧同步

- 独立 native 库创建 AImageReader Surface。
- 实现 MediaCodec handler -> AImage timestamp 的有界配对、flush/release。
- 先以不做 DV mapping 的诊断图/帧计数验证 10-bit AHB 导入和稳定出帧。

### E9-3c：RPU 与 libplacebo 映射

- 输入 AU 提取 RPU并按 PTS入队。
- libplacebo raw DV representation、reshape、BT.709 SDR 输出。
- 目标 DV5 样片与 MPV 同帧截图/色彩对照。

### E9-3d：受控接线

- 默认关闭的实验开关、设备失败记忆和一次性回退。
- 通过设备矩阵后才允许自动策略选择；未通过时生产默认仍走原生 DV/codec tone-map/MPV。

## 8. 验收标准

- 自动化：Profile 5/7/8、DRM、API、Vulkan probe、renderer 优先级、flush/PTS queue 的单测。
- 编译：Media3/App Java 编译；两个 ABI native clean build；ELF `SONAME`/`DT_NEEDED`/版本标记校验。
- 设备：目标设备同一 DV5 文件确认 `MediaCodec` 硬解、Vulkan renderer、生效 RPU 数、无紫绿；与 MPV gpu-next 截图对照。
- 生命周期：起播、seek、暂停恢复、前后台、换集、Surface 重建、连续 20 次退出进入，无黑屏/死锁/use-after-free。
- 性能：4K 代表片连续 10 分钟，无持续队列增长；掉帧、GPU 帧时和功耗不显著劣于同设备 MPV 路径。
- 负向：DRM、8-bit AHB、缺少 Vulkan external format、RPU 缺失/损坏均不错误宣称成功。

## 9. 回滚

- 每个子阶段独立 commit/recovery tag。
- E9-3a 回滚只移除策略/壳，不改变 Media3 AAR 或 MPV。
- E9-3b/c 回滚删除独立 native AAR/`.so` 和 renderer 注册；现有 Exo、nextlib、MPV assets 保持原版本。
- E9-3d 运行时可通过关闭实验开关立即停止选择该路径，无需删除媒体数据库或配置。

## 10. 当前检查点

- 已完成：方案、证据、替代比较、验收和回滚边界；用户已明确要求按最佳实践继续实现。E9-3a 已新增纯 Java 路由/能力策略，覆盖原生 DV、API 31+ codec tone-map、实验性 GPU mapping、显式旧 HDR10 兼容和不支持结果；现有 P5 兼容 renderer 已接入该策略但保持原默认行为。
- 未完成风险：尚未用目标设备验证 Exo 自建 AImageReader Surface 是否得到与 MPV 相同的 10-bit external format；这是 E9-3b 的硬门槛。
- 下一动作：完成 E9-3a 的定向 JVM 验证并提交恢复点，然后为 E9-3b 声明 native/Java/build 的独立范围。

## 11. E9-3a 实施记录

- 代码：新增 `ExoDv5GpuMappingPolicy`。GPU 路由要求 Profile 5、非 DRM、API 26+、非 tunneling、普通 HEVC 硬解器、独立 renderer native 库、Vulkan/AHardwareBuffer probe 和实验开关全部通过；原生 DV 和 API 31+ 已接受的 codec tone-map 优先。DRM 即使允许旧兼容也不会进入 GPU 或旧 HDR10 fallback。
- 接线：`ExoUtil.shouldUseDolbyVisionHdr10Fallback` 的 Profile 5 分支改由策略返回值决定。E9-3a 没有注册不存在的 `VideoSink`，现有非 DRM Profile 5 默认仍进入旧兼容 renderer，等待 E9-3b/c 真机成立后再调整生产优先级。
- 测试：独立 JUnit 4.13.2 执行 `ExoDv5GpuMappingPolicyTest`，9 个用例通过；`:app:compileMobileArm64_v8aDebugJavaWithJavac` 成功，证明 App/Media3 接线可编译。
- 已知验证阻断：`:app:testMobileArm64_v8aDebugUnitTest` 在执行测试前的 `processMobileArm64_v8aDebugResources` 失败，缺少既有 Material/Media3 style/attr/color 资源。本单元未修改资源或依赖，故没有扩大范围处理该基线问题，也不把新增 JUnit 结果表述为 Android Gradle 测试任务通过。
- 回滚：回退 E9-3a commit/tag 即恢复原 Profile 5 fallback 判断；没有 native 资产、Media3 AAR、设置或数据库变更。
- 下一动作：启动 E9-3b 独立实现单元，先完成 AImageReader/`AHardwareBuffer` capability bridge、`VideoSink` Surface 生命周期和有界 PTS 配对；DV 映射仍保持关闭。

## 12. E9-3b 实施记录

- 代码：新增独立 `libexo_dovi_renderer.so` CMake target（使用锁定 NDK r29，arm64-v8a 与 armeabi-v7a），并通过 `ExoDv5Native` 暴露能力探测、AImageReader Surface、有限 expected-frame 队列、AHardwareBuffer 使用/高位深统计和释放接口。
- Media3：新增 `ExoDv5VideoSink` 与 `ExoDv5GpuRenderer`，使用锁定 Media3 `VideoSink`/`MediaCodecVideoRenderer.Builder.setVideoSink()` 契约；视频帧 handler 的释放时间仍由 Media3 控制，AImage 时间戳按 presentation PTS（微秒转纳秒）配对，避免把渲染 deadline 错当 codec PTS。Renderer factory 仅支持显式诊断创建，当前未注册到生产 renderer 列表。
- 能力门控：native probe 要求同一 Vulkan 物理设备同时具备 Vulkan 1.1、`VK_ANDROID_external_memory_android_hardware_buffer`、`VK_EXT_queue_family_foreign`、sampler YCbCr conversion，并成功创建逻辑设备取得 AHB 导入函数；AImageReader 必须是 `AIMAGE_FORMAT_PRIVATE + AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE`。不满足时不会声明可用。
- 测试/构建：`:app:compileMobileArm64_v8aDebugJavaWithJavac` 成功；`:app:externalNativeBuildMobileArm64_v8aDebug` 与 `:app:externalNativeBuildMobileArmeabi_v7aDebug` 成功，产物分别确认 ELF 64-bit AArch64 与 ELF 32-bit ARM。新增纯逻辑测试覆盖显式 opt-in、完整 probe、队列时序和 PTS 纳秒换算；Android Gradle 单测仍受既有资源链接错误阻断。
- 当前限制：此单元只验证 codec→AImageReader/AHardwareBuffer 通路并统计 buffer，不导入 Vulkan 图像、不调用 libplacebo、不输出 PlayerView，也不提取/解析 RPU。因此不能宣称 DV5 色彩已恢复；这些属于 E9-3c/3d。
- 回滚：回退 E9-3b commit/tag 可移除 CMake/native 目标与诊断 sink，既有 Exo/MPV 二进制和默认路径不变。
- 下一动作：启动 E9-3c，接入独立 libdovi/RPU parser 与 libplacebo mapping API；先定义逐帧 metadata 所有权和 malformed-RPU 回退，再实现 Vulkan external image 到输出 Surface 的最小帧路径。

### E9-3b 检查点

- 完成：E9-3b diagnostic Media3 VideoSink and independent AImageReader/Vulkan capability bridge compile for arm64-v8a and armeabi-v7a; ELF SONAME, JNI exports and DT_NEEDED verified; production renderer remains unregistered.
- 分支/基线：`exo-dv5` / `7f665b7d858454fa19919b1e0b69b0f239ac9587`。
- 已改路径：`app/build.gradle`、`app/src/main/cpp/**`、四个 `ExoDv5*` Java 文件、新增测试和本任务文档；无起始脏文件。
- 验证：App Java、两 ABI CMake build 成功；ELF64 AArch64/ELF32 ARM、`libexo_dovi_renderer.so` SONAME、七个 JNI 导出和 `libandroid/libmediandk/libvulkan` 依赖已确认。
- 验证补充：使用 JDK 21 执行 `bash gradlew :app:testMobileArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.player.exo.ExoDv5GpuRendererTest --no-daemon`；任务在既有 `processMobileArm64_v8aDebugResources` 资源链接错误处终止，未进入测试执行。该错误涉及缺失的 Material/Media3 资源，本阶段未改动资源或依赖。
- 未解决风险：目标设备尚未运行 capability probe/codec Surface 出帧；AImageReader 回调销毁只完成静态审计，尚无设备压力验证；本阶段不具备显示输出和 DV mapping。
- 回滚锚点：`7f665b7d858454fa19919b1e0b69b0f239ac9587`。
- 下一动作：Run ExoDv5GpuRendererTest with Gradle JDK 21, finish release-lifecycle review, then commit and tag E9-3b.
