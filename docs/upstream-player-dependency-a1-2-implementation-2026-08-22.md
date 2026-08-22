# Exo A1-2：DV7 转 P8.1 同步重写 CSD

状态：已实施并通过针对性单测。

## 范围与来源

- 类别：Exo App 适配层，不重建 Media3/nextlib AAR，不修改 FFmpeg、MPV 或 native lock。
- 上游参考：FFmpeg `177f090e0503b7e013922ca903bde14b1c375f18` 的 `dovi_rpu convert=p81` 配置语义。
- 当前基线：`9f946cfb003e721c2c36dde1a197c4ce86422cee`；E1 已完成提交 `0b09fc0944a0ef3c21f423e470ece93f3193690c`，恢复 tag 为 `recovery/exo-e1-ffmpeg-9.0.1/20260822093504-0b09fc0944a0`。
- 本阶段 task guard：`exo-a1-2-dv-csd`。

## 目标

现有 DV7→P8.1 流程只改 codec string，可能产生“P8.1 codec string + P7 CSD”的不一致。本阶段在同一个 `Format` 中同步写入 Dolby Vision configuration record：

- profile = 8；
- level 保持源 codec string 的 level；
- `rpu_present = 1`、`el_present = 0`、`bl_present = 1`；
- base-layer signal compatibility ID = 1；
- metadata compression = 0。

这只是元数据一致性修正，不是启用 FFmpeg `dovi_rpu` BSF。现有硬件能力判断、加密禁用、P8.1 会话锁定、转换失败中止、HDR10 策略和诊断/fallback 均保持不变。

## 实现

- `DolbyVisionP81ExtractorsFactory.asProfile81()` 只处理 DV7，使用 Media3 四参数 `buildDolbyVisionInitializationData(8, level, 1, 0)`。
- `csd-2` 已是 DV CSD 时替换；已有其它初始化数据时在 index 2 插入并保留原数据；不足 3 项时用空数组补齐。
- 非 DV7 格式保持原对象语义，不修改 codec 或 CSD。

## 验证与风险

已运行：

```text
bash ./gradlew :app:testMobileArm64_v8aDebugUnitTest \\
  --tests 'com.fongmi.android.tv.player.exo.DolbyVisionP81ExtractorsFactoryTest'
```

结果：`BUILD SUCCESSFUL`；`DolbyVisionP81ExtractorsFactoryTest` 通过。

已覆盖的单测场景：codec/CSD 同步、level 保留、CSD flags、非 DV index 2 保留、已有 DV CSD 替换、缺失 CSD 补齐、非 DV7 不修改。

未覆盖：真实 DV7 MEL/FEL 样片、各厂商硬解实际接受的 CSD、跨 seek/segment 实机行为。该风险不改变本阶段的 App 层范围；失败时回滚本阶段提交即可，不能回滚 E1 或改变 MPV native。

## 回滚与下一步

- 预实施回滚点：`9f946cfb003e721c2c36dde1a197c4ce86422cee`。
- task guard 将创建原子提交和 `recovery/exo-a1-2-dv-csd/<timestamp>` tag，并在此处补充提交号和 tag。
- 下一步：完成 task guard 提交/tag；不重建 AAR/native，不运行完整测试矩阵。
