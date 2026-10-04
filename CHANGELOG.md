# Changelog

本文件记录 Ai Android 的正式版本变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0-beta3] - 2026-10-03
**versionCode 4**

### Added（新增）
- 项目整体整理为 GitHub 标准结构（`.gitignore` / `README.md` / `CHANGELOG.md` / `CONTRIBUTING.md`）
- 内置终端常驻 shell，支持长任务与 Python 执行
- 定时任务（一次性 / 周期 / 间隔）
- 长期记忆（跨会话保存与读取）
- LaTeX 数学公式渲染（JLaTeXMath）
- Web 搜索与网页抓取工具

### Changed（变更）
- versionCode 由 2 提升至 4
- Gradle 使用 `org.gradle.parallel` / `caching` 优化构建

### Notes（说明）
- 内置运行时资源（`bootstrap-aarch64.zip`、`bootstrap-arm.zip`、`proot`）体积较大，已纳入版本控制
- 无障碍服务、前台服务、Boot 接收器均已声明

## [1.0.0-beta2] - 2026-10-03
**versionCode 2**

### Added
- Agent 对话与工具执行核心（agent / tools）
- 手机界面操控（无障碍服务）
- 多模型 Provider（OpenAI / Anthropic）
- Compose 聊天 / 终端 / 设置界面

### Changed
- 调整根 `build.gradle` 插件配置（AGP 8.1.4 / Kotlin 1.9.22）

## [1.0.0] - 2026-10-03
**versionCode 1**

### Added
- 项目初始版本（v1.0.0-beta1 基准）
- 基础 Compose 架构与 MainApp / MainActivity 入口

---

> 版本历史备份目录：`/storage/emulated/0/下载/项目备份/Ai Android/v1.0.0-beta*`（仅只读参考，不属于本仓库）
