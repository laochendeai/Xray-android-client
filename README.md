# Xray Android Client

独立 Android 客户端仓库，用来承接当前 `Xray-core-laochendeai` 之外的移动端开发、打包和后续发布工作。

## Documentation Map

- [Android 迁移背景](docs/MIGRATION_CONTEXT.md)
- [Android 路线图](docs/ROADMAP.md)
- [项目规则](CLAUDE.md)

## Overview

- Product: 独立 Android 客户端
- Primary users: 需要在手机端导入订阅、查看节点、后续接入代理控制的用户
- Primary outcome: 先建立独立仓库、最小可运行 Android 工程和 APK 构建链路
- Current stage: bootstrap

## Current Scope

- 单独 Git 仓库
- 最小 Android app module
- 本地 `assembleDebug` 构建能力
- 本地 release APK 打包能力
- GitHub Actions debug APK artifact

这一步也明确了与核心仓库的边界：

- 核心仓库当前仍以 runtime 和内嵌 WebPanel 为主
- 核心仓库的 Android release 目前还是 CLI binary zip，不是 APK
- 真正的 Android 客户端交付、交互和后续发版改由本仓库承担

暂未实现：

- Xray core 内嵌与 JNI 封装
- 真正的透明代理/TUN 控制
- 正式 release AAB 与商店分发链路
- 扫码订阅、节点池同步、策略路由

## Quick Start

### Prerequisites

- Java 17
- Android SDK，至少包含 `platforms;android-36` 和对应 build-tools
- Linux / macOS / Windows 均可，当前脚本默认优先读取 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`

### Local Build

```bash
cp local.properties.example local.properties
./scripts/build-debug-apk.sh
```

如果 `local.properties` 已存在，脚本不会覆盖。

### Test

```bash
./scripts/test-debug-unit.sh
```

### Local Release APK

默认可生成本地 unsigned release APK：

```bash
./scripts/build-release-apk.sh
```

如果要生成 signed release APK：

```bash
cp keystore.properties.example keystore.properties
# 填入你的签名信息
./scripts/build-release-apk.sh
```

默认脚本会跳过一部分 release lint report 相关任务，优先保证本地可打包。
如果你要尽量保留完整 release 任务链路：

```bash
XRAY_ANDROID_RELEASE_WITH_LINT=1 ./scripts/build-release-apk.sh
```

### Output

```bash
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
# or
app/build/outputs/apk/release/app-release.apk
```

## GitHub Actions

仓库内置 `android-debug-apk` workflow，会在 `push`、`pull_request` 和手动触发时构建 debug APK 并上传 artifact。

注意：这只是调试包，不是签名后的正式发布包。

## Repository Structure

```text
.
├── .github/
├── app/
├── docs/
├── scripts/
├── build.gradle
├── settings.gradle
└── CLAUDE.md
```

## Collaboration Rules

- 项目规则以 `CLAUDE.md` 为准
- 非 trivial 需求优先走 issue 闭环
- 改动后必须做针对性构建或测试验证
- 不要把 SDK 路径、签名材料、APK 构建产物提交进仓库
- `keystore.properties` 与 keystore 文件只保留在本机，不要入库

## Product Inheritance

Android 客户端不会机械复制桌面 WebPanel，但会继承这些核心语义：

- 订阅导入支持远程 URL、手工粘贴、本地文件，后续补二维码
- 节点池保持候选、验证中、活跃、隔离、已移除五池模型
- 只有活跃池可以进入后续自动选择和运行态候选
- 节点池是主操作面，诊断页不承担主控制流程

## Next Milestones

1. 接入订阅导入与本地持久化
2. 落地五池节点模型与移动端节点池 UI
3. 实现目标站点绑定与命中校验
4. 决定 Xray core 集成与真实运行态方案

详细分阶段规划见 [docs/ROADMAP.md](docs/ROADMAP.md)。
