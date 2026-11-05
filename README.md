# Android Isolated Node Provider

Android isolatedProcess + APK-owned Node 的执行研究：通过 Binder 传递只读库描述符和 stdio pipes，用独立 UID 执行有界脚本，提供 Process 生命周期与取消适配。

## 内容

- process/：原产品 IsolatedProviderProcess 及其 JVM 测试，可独立运行。
- reference/product/：真实 Service、Endpoint、Runtime 与 Application guard。它们是原产品接线源码，尚未提炼为通用 Android SDK。
- native/：JNI/NDK 入口源码。
- reference/host/：JSON-RPC stdio、包存储、pin/结果契约参考。
- probe/：历史独立 Android 探针；构建需原始 pin 对应的运行时和 preparation 输出。
- examples/providers/：独立本地协议示例。

## 检查与集成条件

JDK17、Gradle8.9：`gradle :process:test`。这只检查 Process facade，不能代替 isolatedProcess 真机证明。
完整 Android 集成需 NDK、SDK35、固定 Node24.18.0 和库清单、匹配的 native ABI。二进制未随仓分发。
IsolatedNodeService 依赖 AgentApplication 的 isolated graph guard；集成时必须保留，不得创建空 guard 或回退 app UID 来使构建通过。
probe 的 preparation 脚本在 reference/preparation/；其原产品路径需按来源映射准备。未声称 probe 已变成一键独立构建。

## 历史测量与限制

证据包括真实 isolated UID、stdio JSON-RPC、访问自有 sentinel/loopback 被拒绝、Binder 死亡与取消；完整模型/provider/Gateway 关联在主仓库。
特定 ABI、脚本大小/时间/返回体预算均保留。它不是任意脚本的完整沙箱保证，不包含 Root/Shizuku/Python 后端。
本地 JSON-RPC 2.0 与产品 ZCode 协议是两套边界，不合并。

# 本仓库的身份 / Identity

结项日期：2026-11-05。项目已停止持续开发，本仓库为冻结的研究成果。
This is a frozen research archive, with no maintenance or production-safety commitment.

自有代码 MIT；第三方依赖保留原许可。源码与原产品的对应关系见 SOURCE_MAP.json。
设备验收报告是指定历史源码/APK/设备上的证据，不代表拆分后新跑了同一真机测试。
本次新跑的导出测试会单列在 EXPORT_VERIFICATION.md；没有测试的内容明确列出。
主索引：https://github.com/Alita23333/android-agent-research
