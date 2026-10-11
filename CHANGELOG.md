# Changelog

本文件记录 Ai Android 的正式版本变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.2.0] - 2026-10-05
**versionCode 9**

### Fixed
- Shizuku 检测与提权：包名补全 `moe.shizuku.privileged.api` 等；sticky 监听；`Sui.init`；`isPreV11` 检查；user-service 真提权（uid 2000，突破沙箱读 `Android/data`）。
- 工作流静默停：`StreamEvent.FinishInfo`；max_tokens 截断自动续写（≤3 轮）；4xx 保留 partial 重试。
- 语音输入状态机：点-点模式；`begin()` 返回 Boolean；最低 500ms；权限/识别器错误 Toast；不静默。
- 菜单问题：挤占布局、位置错误、无动画 → 全部改 material3 `DropdownMenu`（锚定触发控件右下、浮层不占布局、自带过渡）。
- 文生图 404：新增 `ZhipuImageMultimodal`，归一化端点，响应 URL 下载转 base64。
- TTS 无声：MiMo 音色白名单，默认空 voice，`SettingsRepository` 自动绑 MiMo。
- 状态栏/导航栏与背景同色（`Theme.kt` SideEffect）。
- 自定义 API 默认参数可删改（`extraParams` 分层初始化，`rowsToJson` 跳空 string）。
- 编译修复：`AnimatedContent` 用 `togetherWith`；补 `}`；括号平衡；Coil `AsyncImage`；`align` 层级；extended 图标；`Popup` 无 `alignment`。

### Added
- 语音输入 + 输入框重做（`VoiceInputController`，ASR 优先，系统 `SpeechRecognizer` 兜底）。
- 上下文窗口字段（`contextWindow`，UI 可编辑）。
- 对话历史侧边抽屉（搜索、分组、⋯ 菜单）。
- 对话历史「项目」分区 + 项目独立记忆（`MemoryStore` 项目作用域）。
- 自定义文件选择器底部抽屉（`FilePickDrawer`）。
- 自绘图片选择器 `ImagePickDrawer`（MediaStore 全量，Coil 缩略图）。
- 悬浮窗内直接回复（`AgentBridge`）。
- JS 动态悬浮窗（`JsPluginRuntime`，`window.host.*`，`createBall`/`updateBall`/`removeBall`）。
- 悬浮窗美化（描边、圆角、主题）。
- 多模态 Agent 工具：`generate_image` / `generate_speech` / `analyze_image`。
- 通用 `AppMenuPanel` / `AppDialog` 自绘菜单组件。
- 对话草稿恢复、长对话秒开、空对话合并。
- 过渡动画：抽屉、设置页、菜单（`animation:1.7.0`）。

### Changed
- 菜单自绘替换原生 `DropdownMenu`（模型切换、加号、历史⋯、多模态绑定、参数类型）。
- 能力互斥：语音（TTS/ASR/REALTIME）与对话（CHAT/VISION/IMAGE/SEARCH）不能同选。
- `versionCode 8 → 9`，`versionName "1.1.0" → "1.2.0"`。

### Notes
- 所有改动不 git commit，由维护者 RV2IDE 编译验证后手动上传。
- Shizuku user-service 已实测打通。

## [1.1.0] - 2026-10-05
**versionCode 8**

### Added
- 请求体新手化：`ParamRow` + `PRESET_PARAMS` + JSON 双向同步。
- 多语言 + 语言包导入：`I18nManager`，`applyLocale`，支持导入/清除。
- 插件大升级：`FloatingStyle`、`ChatBubbleStyle`、`features`。
- Shizuku 权限适配：官方 API + Provider，`run_shell_command` 自动回退沙箱。
- 用户消息编辑 + 重新生成：`resendUserMessage`，「保存并重新生成」。

### Fixed
- Agent 流式断流：网络类错误自动重试；重试耗尽保留 partial。
- 底部 Ctrl/Alt toggle + 软键盘不生效：`@Volatile ctrlDown/altDown` 同步。

### Changed
- 管道版终端 Ctrl 按钮改纯文字 `PlainKey`。
- 无障碍引导对话框。
- 设置页文案接入 `stringResource`。
- `versionCode 7 → 8`，`versionName "1.0.0-Stable" → "1.1.0"`。

## [1.0.0-Stable] - 2026-10-05
**versionCode 7**

### Added
- 完整 PTY 终端模块（`terminal-emulator` + `terminal-view`，NDK r29，`libtermux.so`）。
- 终端交互化：Ctrl-C/D/Z、清屏、重启、`imePadding`。
- pkg/apt 工具优化：`run_shell_command` 60s 上限提示，`terminal_exec` 120s 后台，`read_file` 二进制识别。
- 悬浮窗常驻 + 关闭按钮。
- 悬浮窗 AI 完成后拖不动修复。
- 终端键盘弹起输入框不动修复。
- AI 读二进制乱码修复。
- AI terminal_read 输出残留修复。
- AI 终端 pkg/长脚本卡 60s 修复。

### Changed
- `versionCode 6 → 7`，`versionName → "1.0.0-Stable"`。
- `ProotRunner` 新增交互式 `buildInteractiveCommand()`。
- `TerminalScreen` 新增 Ctrl 按钮行、`imePadding`，按 `terminalUsePty()` 分流。
- `FloatingService` 面板加关闭按钮，`finishWork` 不自动 stop。

## [1.0.0-beta5] - 2026-10-04
**versionCode 6**

### Added
- AI 操控手机（后台操作 + 实时镜像）：`AiControlGuard`、`MirrorService`、`MediaProjectionBridge`。
- 设置页「AI 操控」「Token 统计」「插件管理」卡片。
- 插件接口抽象层（`PluginManifest` / `WidgetSlot` / `ThemeProvider` / `AgentEventHook` / `PluginRegistry`）。
- 悬浮窗重做（AndLua 风格悬浮球 + 面板，实时输出滚动区）。
- 悬浮球插件定制。
- 折叠深度思考同步升级。
- Termux 风格终端屏。
- AI 自动总结标题开关。

### Fixed
- AI 自动总结标题时有时无：改为首轮结束并发触发 + 去重。
- 终端按对话隔离：`TerminalSessionManager`。

### Changed
- `versionCode 5 → 6`，`versionName → "1.0.0-beta5"`。
- `FloatingService` 重构，`Conversation` 加 `totalTokens()`。

## [1.0.0-beta4] - 2026-10-04
**versionCode 5**

### Added
- 对话导出/导入 JSON。
- `terminal_exec` 自动重试。
- Agent 参数：流式/HTTP 失败重试次数、上下文自动压缩轮次。
- 音视频通话（GLM-4-Voice / GLM-Realtime）。
- 多模态能力绑定 CALL。

### Fixed
- 终端进程中途崩溃假超时。
- 导出/导入 JSON 兼容两种格式。
- HTTP 400 根因修复：`sanitizeToolMessages`。
- 移除技能板块。

### Changed
- `versionCode 4 → 5`，`versionName → "1.0.0-beta4"`。

## [1.0.0-beta3] - 2026-10-03
**versionCode 4**

### Added
- GitHub 标准结构。
- 内置终端常驻 shell。
- 定时任务、长期记忆、LaTeX 渲染、Web 搜索。

### Changed
- `versionCode 2 → 4`。
- Gradle 并行/缓存优化。

## [1.0.0-beta2] - 2026-10-03
**versionCode 2**

### Added
- Agent 对话与工具执行核心。
- 手机界面操控（无障碍）。
- 多模型 Provider（OpenAI / Anthropic）。
- Compose 聊天/终端/设置界面。

### Changed
- 根 `build.gradle` 插件配置（AGP 8.1.4 / Kotlin 1.9.22）。

## [1.0.0] - 2026-10-03
**versionCode 1**

### Added
- 项目初始版本。
- 基础 Compose 架构与 MainApp / MainActivity 入口。