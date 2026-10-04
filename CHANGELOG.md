# Changelog

本文件记录 Ai Android 的正式版本变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0-beta4] - 2026-10-04
**versionCode 5**

### Added（新增）
- 对话历史右上角菜单新增「导出对话」：通过 SAF 另存为 JSON
- 对话列表左上角新增「导入对话」按钮：读取 JSON 恢复对话（自动重生成 id，避免冲突）
- 终端工具 `terminal_exec` 增加**自动重试**：进程崩溃/未就绪时自动重启会话并再试一次（默认 2 次）
- 终端会话健康检查（`isHealthy` / `ensureHealthy`）
- 设置 → Agent 参数新增「流式/HTTP 失败自动重试次数」（0–10，默认 2），接入 `AgentConfig.networkRetries`
- 设置 → Agent 参数新增「上下文自动压缩轮次」（0=关，默认 0），接入 `AgentConfig.contextCompressRounds`，超长对话自动总结旧轮次压缩上下文
- 输入框左侧「＋」菜单新增「语音通话（GLM-4-Voice）」「视频通话（GLM-Realtime）」两项，进入全屏通话界面
- 通话引擎（`provider/RealtimeCall.kt`）：智谱 GLM-4-Voice 单轮语音对话 + GLM-Realtime WebSocket 双向流式（ServerVAD + 视频帧），含录音 / WAV 封装 / 播放 / 摄像头帧采集
- 多模态能力绑定新增「CALL（音视频通话）」项，可指定通话使用的 Provider

### Changed（变更）
- `versionCode` 由 4 提升至 5，`versionName` 升至 `1.0.0-beta4`
- 终端等待期检测到进程已死会提前结束并交由自动重试，不再空等到超时

### Fixed（修复）
- 终端进程在命令执行中途崩溃时，原来会一直等到超时；现在自动重启 + 重试，减少"假超时"
- 导出/导入的 JSON 兼容裸 `Conversation` 与 `{data:{...}}` 两种格式
- **HTTP 400 根因修复**：发请求前统一做「工具调用配对清理」（`AIProvider.sanitizeToolMessages`），剔除「孤儿 tool 结果 / 无结果的 tool_call」，OpenAI 与 Anthropic 两套 Provider 共用，避免历史残留脏数据导致每次请求 400
- 移除设置中已无实际作用的「技能（Skills）」板块及相关引用（`skills/` 目录、MainApp/MainViewModel/SettingsScreen 引用）

### Notes（说明）
- 音视频通话为 beta4 新增实验功能，接口按智谱 GLM-4-Voice / GLM-Realtime 官方文档实现，实际联调以 API Key 配置为准；需要 `RECORD_AUDIO` / `CAMERA` 运行时权限

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
