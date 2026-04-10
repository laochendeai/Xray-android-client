# Xray Android Client

独立 Android 客户端仓库，用来承接当前 `Xray-core-laochendeai` 之外的移动端开发、打包和后续发布工作。

## Overview

- Product: 独立 Android 客户端
- Primary users: 需要在手机端导入订阅、查看节点、后续接入代理控制的用户
- Primary outcome: 先建立独立仓库、最小可运行 Android 工程和 APK 构建链路
- Current stage: bootstrap

## Current Scope

- 单独 Git 仓库
- 最小 Android app module
- 本地 `assembleDebug` 构建能力
- GitHub Actions debug APK artifact

暂未实现：

- Xray core 内嵌与 JNI 封装
- 真正的透明代理/TUN 控制
- 签名 release APK / AAB
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

### Output

```bash
app/build/outputs/apk/debug/app-debug.apk
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

## Next Milestones

1. 接入订阅导入与节点模型
2. 设计移动端节点池与目标站点绑定界面
3. 决定 Xray core 二进制集成方案
4. 接通 release 签名和正式发版流程
