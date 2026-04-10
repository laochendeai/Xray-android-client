# CLAUDE.md

This repository uses Codex/Claude as an execution agent, not just a chat assistant.

## Project Identity

- Product: Xray Android Client
- Users: 需要在 Android 端管理订阅、节点与透明代理配置的个人用户
- Primary outcome: 提供独立于核心仓库的 Android 客户端代码仓库与可持续演进的移动端交付面
- Current phase: 仓库初始化与最小可运行客户端骨架

## Runbook

### Start

```bash
./scripts/build-debug-apk.sh
```

### Test

```bash
./scripts/test-debug-unit.sh
```

### Build / Package

```bash
./gradlew assembleDebug
```

## Architecture Boundaries

- `app/` 是唯一 Android application module，先保持单模块，避免过早拆分。
- 当前仓库只负责 Android 客户端 UI、配置导入、节点展示与后续移动端运行控制。
- Xray core 二进制集成、签名发布、商店分发暂未落地，不能假装已经支持。
- GitHub Actions 目前只产出 debug APK artifact，不等同于正式 release 包。

## Repository Workflow

Use issue-centered closed-loop delivery by default:

1. 非 trivial 改动先锁定 issue、范围和验证方式。
2. 改动前先读本仓库 `CLAUDE.md` 与相关 issue。
3. 使用独立分支完成单一需求，避免混做。
4. 只实现 issue 范围内内容，不静默扩 scope。
5. 提交前至少跑最相关的本地验证。
6. 文档、构建命令、运行方式与代码一起更新。
7. 合并后同步本地默认分支，保持仓库状态清晰。

## Definition Of Done

A task is done only when:

- 代码实现完成
- 相关构建或测试通过
- 受影响运行路径完成最小验证
- 文档同步更新
- 仓库状态清晰，没有无关脏改动

## Rules

- 不要覆盖用户本地的签名配置、SDK 路径或 keystore。
- 不要把临时调试输出、构建产物、APK/AAB 提交进仓库。
- 不要在没有 issue 或明确目标时扩展架构分层。
- 需要引入新的 Android module、JNI 或内嵌 core 时，先补设计说明。

## Local Runtime Notes

- Required env vars: 可选 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`
- Data/runtime config paths: 本地 SDK 路径通过 `local.properties` 提供
- Build JDK: Java 17
- Android SDK baseline: API 36

## Dangerous Actions

Require extra caution before:

- 覆盖 `local.properties`
- 提交 keystore、签名密码或订阅样本
- 删除用户本地缓存或导出的节点数据
- 修改 release signing / package name / applicationId

## Promotion Rules

- 项目稳定协作规则写入本文件
- 个人偏好不要写入本仓库
- 临时调试记录不要沉淀到仓库规则层
