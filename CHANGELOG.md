# Changelog

本文件记录 Ai Android 的正式版本变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0-beta5] - 2026-10-04
**versionCode 6**

### Added（新增）
- **AI 操控手机（后台操作 + 实时镜像）**：
  - `ToolRegistry` 在执行 `accessibility_control` 前做后台操控守卫（`AiControlGuard`）：目标 App 不在前台时，按设置「操控前是否需要确认」决定是否先 `ask_user`，为关则自动 `launch_app` 切换；允许 AI 后台注入手势而不干扰用户正在使用的前台 App
  - 新增 `MirrorService`（前台服务，`mediaProjection` 类型）：MediaProjection + VirtualDisplay + ImageReader 以约 2fps 持续截取屏幕，画面渲染进悬浮窗扩展面板（200×300dp，复用 FloatingService 的 WindowManager），叠加半透明遮罩「AI 操作中…（点击暂停）」，点击即调用 `MainViewModel.stop()` 暂停 Agent
  - 未开悬浮窗权限时跳过镜像，仅保留通知栏「AI 正在操作中」
  - 新增 `MediaProjectionBridge` 透明 Activity 处理投屏授权弹窗
- 设置页「AI 操控」卡片：3 个开关（允许后台操控 / 显示镜像小窗 / 操控前需确认），存入 `SettingsRepository`
- 设置页「Token 统计」卡片：当前对话累计 prompt / completion / cache / 总消耗；支持 SAF 导出 JSON、一键清除统计
- 对话列表右键菜单新增「查看 Token 统计」→ Dialog 显示该对话累计值
- 设置页「插件管理」卡片：SAF 导入 `.zip` 挂件包（manifest.json + 图标资源），解压到 `filesDir/plugins/<id>/`；支持启用 / 禁用 / 删除
- 导入插件后悬浮窗显示插件图标挂件，点击展开面板显示 Token 统计 + Agent 状态 + 暂停按钮
- 插件接口抽象层（`com.ai.android.plugin` 包，纯 Kotlin 接口/数据类，为 beta6 JS 插件生态铺路）：
  - `PluginManifest`（id / name / version / author / capabilities / entryPoint / iconPath）
  - `WidgetSlot`（render / onBind / onUnbind）
  - `ThemeProvider`（name / colorScheme / widgetShape）
  - `AgentEventHook`（OnTokenUpdate / OnAgentState / OnToolCall / OnMirrorFrame）
  - `PluginRegistry`（register / scan / import / remove，MainApp.onCreate 初始化并打印日志）

### Added（beta5 增强 / 修复批次）
- **悬浮窗重做（参考 AndLua 悬浮球布局）**：46dp 可拖动悬浮球 ↔ 200dp 可拖动面板；**创建新进度悬浮窗前清除上一个 overlay（单一实例幂等）**；面板新增「实时输出滚动区」，折叠显示 AI 的思考 / 生成内容，随内容增加自动上下滚动（滚底）
- **悬浮球可由插件定制**：首个启用 WIDGET 的插件渲染为悬浮球（隐藏默认 "AI" 圆球），其余插件渲染为球旁挂件；插件导入 / 启停 / 删除后自动重建
- **软件内"折叠深度思考"同步升级**：思考面板在流式生成中默认展开并跟随自动滚底（可滚动，高度封顶 300dp），结束后仍可手动展开 / 折叠
- **Termux 风格终端屏**：对话顶栏新增终端按钮（加号左侧最左），点击进入 `TerminalScreen`（黑底等宽字、实时滚底输出区 + 底部输入行回车执行）；**与 AI 共享同一常驻会话、按当前对话绑定**（AI 的 terminal_exec / terminal_read 与终端屏共用），历史保留，除非输入 `clear` 清屏
- 设置页「AI 行为」卡片：**AI 自动总结标题**开关（默认开）

### Fixed（修复）
- **AI 自动总结标题"时有时无"**：旧实现用"标题长度 ≤ 4"判定首轮，但发送时标题已被设为用户首条前 16 字，导致多数情况不触发。现改为**首轮对话（USER 消息恰 1 条）结束即并发触发**（独立协程，不阻塞 Agent 主循环），并用集合去重（同一对话只总结一次），可被设置开关关闭
- **终端按对话隔离**：`TerminalSession` 由全局单例改为「按 convId 隔离的会话池」（`TerminalSessionManager`），AI 工具经 `ToolRegistry.convIdProvider` 绑定当前对话，与终端屏共享同一进程；崩溃自动重启 + 重试逻辑保留

### Changed（变更）
- `versionCode` 保持 6，`versionName` 保持 `1.0.0-beta5`（本批次为 beta5 内的增强 / 修复，不升版本号）
- `FloatingService` 重构：新增实时输出滚动区、插件定制球、单实例清旧逻辑；`MainViewModel` 在思考 / 生成增量事件推送实时内容到悬浮窗
- `AgentCore.summarizeTitle` 维持不变；触发逻辑下沉到 `MainViewModel` 首轮结束并发处理

### Changed（变更）
- `versionCode` 由 5 提升至 6，`versionName` 升至 `1.0.0-beta5`
- `FloatingService` 升级：保留原生状态球，新增插件挂件区（`HostStaticWidget` 按插件 manifest 渲染）与镜像小窗面板，面板增加 Token 行与「暂停 AI」按钮
- `Conversation` 新增 `totalTokens()` 汇总方法，`ChatMessage` 旁新增 `TokenTotals` 数据类

### Notes（说明）
- beta5 阶段插件仅支持静态挂件（读取 manifest.json 的 iconPath 渲染），不运行 JS；真正的 JS 插件执行在 beta6 落地
- 宿主保持精简：挂件造型全部由导入的插件 zip 决定，宿主只留 WidgetSlot 插槽接口

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
