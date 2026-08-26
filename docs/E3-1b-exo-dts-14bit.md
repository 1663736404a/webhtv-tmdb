# E3-1b：Exo DTS 14-bit frame-size correction

- 任务 ID：`E3-1b`
- 所属分类：Exo
- 状态：已实施并完成验证
- 唯一任务文档：`docs/E3-1b-exo-dts-14bit.md`
- 用户授权：2026-08-26 明确要求开始下一个任务并优先快速完成
- 实施基线：`recovery/E3-1b-baseline/20260826-9c347cc688c2`
- 上游来源：`FongMi/media@d500eb27ea994ffaa8ea2b48863a0c7aaef0e4b4`

## Recovery anchor

- 目标：修正 14-bit DTS core 帧大小的整数换算，避免 DTS-CD WAV、raw DTS 和 TS 中帧边界多算 1 字节，导致后续 sync word 错位和音频损坏。
- 范围：只移植 `DtsUtil.getDtsFrameSize` 的 14-bit 公式和最小边界测试；不移植上游无关的 `findDtsCoreSync`、WAV 探测或 reader 主体。
- 接受标准：补丁按现有六个 Media3 patch 后可重放；14-bit BE/LE 与 `FSIZE+1=3585` 测试通过；`lib-extractor` 定向测试和 Java 编译通过；只更新 `media3-extractor` AAR/sources 及 lock；初始 dirty 文件不被覆盖或提交。
- 当前状态：窄补丁、DtsUtilTest、`lib-extractor` Java 编译、AAR 最小更新和 App 接线编译已完成；实现提交 `27b85eeeed5ceb55e56a67ae3b5cf8ff64b8da40`，恢复标签 `recovery/E3-1b/20260826201735-27b85eeeed5c` 已创建。
- 下一动作：完成本次修正提交后，开始 E4-1 评估。

## 实际能力

播放使用 14-bit DTS 编码的音乐 CD WAV、raw DTS 或 TS 时，播放器会正确计算每帧真实占用的物理字节数，连续帧不再因向下取整顺序错误而错位。普通 16-bit DTS 和其他音频格式行为不变。

## 证据与设计决策

- 评估索引 `docs/upstream-player-dependency-merge-assessment-2026-08-20.md` 第 9.3 节已确认当前 fork 的 WAV 探测、第二音轨和 `DtsReader` 接入已存在，缺口仅为 14-bit frame-size 整数换算。
- 上游 `d500eb27ea994ffaa8ea2b48863a0c7aaef0e4b4` 将公式从 `fsize * 16 / 14` 改为 `(fsize * 8 / 14) * 2`，先取得完整 14-bit word 数再换算容器字节；`FSIZE+1=3585` 时结果从 4097 修正为 4096。
- 当前 `DtsUtil.java` 仍使用旧公式；`DtsUtilTest` 只有 DTS:X marker 测试，没有 14-bit frame-size 覆盖。
- 采用窄适配，不移植上游 `findDtsCoreSync`，因为当前 fork 尚未需要该 API，扩大范围会增加容器和输入探测风险。

## 收益、风险与回滚

- 收益：修复 14-bit DTS 连续帧边界，避免后续音频静音、噪声或解析失败。
- 风险：错误的测试样例或公式适配可能影响 14-bit raw DTS/WAV/TS；软件/硬件 decoder 不受影响。变更只增加整数运算，性能和包体积影响可忽略。
- 兼容性：16-bit DTS、DTS-HD、DTS:X、E-AC3、TrueHD 和现有 extractor 接线不变。
- 回滚：恢复 E3-1b 实现提交和 `media3-extractor` AAR；必要时回到 `recovery/E3-1b-baseline/20260826-9c347cc688c2`。

## 实施记录

- 补丁：`third_party/patches/media3-exo-dts-14bit-frame-size.patch`，SHA-256 `328fe823b824fd36e359ea16eea4c77334ea707676e9a2803258f29b661e3d77`。
- 定向验证：独立 Temurin JDK `21.0.12.1+1`、Gradle 9.1.0、工作区 Gradle 缓存和代理；`:lib-extractor:testDebugUnitTest --tests androidx.media3.extractor.DtsUtilTest :lib-extractor:compileDebugJavaWithJavac`，`BUILD SUCCESSFUL in 7m 45s`。
- App 接线验证：独立 Temurin JDK `21.0.12.1+1`、`bash gradlew --no-daemon --console=plain :app:compileMobileArm64_v8aDebugJavaWithJavac :app:compileLeanbackArm64_v8aDebugJavaWithJavac`，`BUILD SUCCESSFUL in 4m 56s`。
- AAR：仅替换既有已验证 `media3-extractor` AAR 内的 `DtsUtil.class`，保留 E-SP2/E2-1 其他 classes；SHA-256 `33109a547e7f27c1110e785ae77e8ab1e9584a24a9f904573ce770129aa4475a`。
- sources：仅替换 `DtsUtil.java`，SHA-256 `b4d65656b5d56ea8a66580d03178b98dbecac3840791a30820ae184f3d1ca416`。
- 发布限制：完整 `:lib-extractor:publishReleasePublicationToMavenRepository` 在现有 dirty Media3 checkout 被既有 `MatroskaExtractor.samplesHaveSupplementalData` 静态上下文错误阻塞；未修改受保护 checkout，采用已验证 AAR 的最小 class/source 替换并通过 ZIP、源码和校验文件验证。
