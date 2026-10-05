# Changelog

本文件记录 Ai Android 的正式版本变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.1.0] - 2026-10-5
**versionCode 8**

### Added

- **请求体新手化（JSON → 逐条添加）**
  - `ProviderConfig` 新增 `@Serializable ParamRow(key, value, type)` + `extraParams: List<ParamRow>`（带默认值，旧配置反序列化兼容）
  - 常见参数预设（`temperature` / `top_p` / `max_tokens` / `stream` / `stop`），一键添加
  - `EditProviderDialog` 新增逐条参数区块：单条 键 / 值 / 类型表单，支持上移 / 下移 / 删除
  - `rowsToJson` / `jsonToRows` 双向同步：表单保存自动拼 JSON；高级 JSON 改过可一键回写逐条参数，不丢数据

- **多语言切换 + 语言包导入**
  - 新增 `i18n/I18nManager`：中 / 英 / 跟随系统，持久化 + 热切换（`LocaleWrapper` + `Resources.updateConfiguration`）
  - 支持导入自定义 `.json` 语言包（key → 翻译），运行时覆盖指定文案；URI 持久化，重启保留
  - 设置页新增「语言」卡片：切换 chip + 导入 / 清除语言包
  - 资源：`values/strings.xml`（中文）+ `values-en/strings.xml`（英文）

- **插件功能大升级**
  - `FloatingStyle` 扩展：`ballShape` / `ballSize` / `ballIcon` / `panelLayout` + 新增 `ChatBubbleStyle`（气泡圆角 / 配色 / 字号 / 留白）
  - `PluginRegistry` 解析 `theme.json` 新字段；`MessageBubble` 读取插件气泡样式覆盖默认
  - `PluginManifest.capabilities` 支持 `"FUNCTION"`（插件可声明功能开关清单）
  - `FloatingService` 悬浮球按形状 / 尺寸 / 图标重建；形状 / 尺寸变化可热更新
  - 导入 zip（manifest + theme.json + 图标）即可改 App 主题 + 聊天气泡 + 悬浮球外观 + 面板布局 + 功能集，无需改代码

- **Shizuku 提权适配**
  - 新增 `service/ShizukuManager`：使用 `dev.rikka.shizuku:api:13.1.5` + `provider:13.1.5` 官方 API
  - `AndroidManifest.xml` 声明 `ShizukuProvider` + `moe.shizuku.manager.permission.API_V23`（未声明时 Shizuku 授权列表不显示本 App）
  - `isAvailable()` 基于 `Shizuku.pingBinder()` + `checkSelfPermission()` 判断 binder 可达 + 授权状态
  - `ensureAuthorized()` 调 `Shizuku.requestPermission()` 弹授权对话框
  - `executeShell()`：未装 / 未授权自动回退 app 沙箱 `sh -c`，带提示前缀
  - `ShellTool`（`run_shell_command`）改经 `ShizukuManager.executeShell`
  - 设置页新增「Shizuku 权限」卡片
  - 真 shell uid 2000 命令执行需 `IShizukuUserService`（AIDL user-service），本轮预留 TODO，当前已授权时仍走本地沙箱

- **用户消息编辑 + 重新生成**
  - `MainViewModel.resendUserMessage(msgId, newContent)`：丢弃该 USER 消息之后的旧 AI 回复，用编辑后内容重发并触发 Agent 重新跑一轮
  - `MessageBubble` 编辑对话框新增「保存并重新生成」按钮（原「保存」仅改显示不重新生成，保留）

### Fixed

- **Agent 流式输出中途断流导致整个循环终止**（`agent/AgentCore.kt`）
  - 旧逻辑：流式输出收到任意增量后置 `gotAny=true`，若 SSE 连接中途断一次（网络抖动 / 5xx / 读超时），直接 `return@channelFlow` 放弃整轮，AI 说到一半戛然而止
  - 修复：网络类错误（非 4xx）自动重试；4xx（除 408 / 429）才放弃；重试耗尽时保留已生成部分内容并明确提示「连接中断」
  - 影响：正常网络波动不再导致 Agent 工作中途停止

- **底部 Ctrl/Alt toggle + 软键盘不生效**（`ui/terminal/PtyTerminal.kt`）
  - 根因：`TermViewClient.readControlKey()` / `readAltKey()` 硬编码 `false`，不知道底部快捷键条的 toggle 状态
  - 修复：加 `@Volatile ctrlDown / altDown`；`PtyTerminalScreen` 把 client 提升到 Screen 层，`LaunchedEffect(ctrlActive, altActive)` 同步

### Changed

- 管道版终端 Ctrl 按钮改为纯文字 `PlainKey`（与 PTY 版 `TermKeyBar` 同风格）
- 无障碍权限引导：AI 操控开关首次开启且服务未开时弹引导对话框 + 跳系统设置
- 界面文案国际化：设置页主要卡片 / 按钮 / 标签接入 `stringResource`
- `versionCode 7 → 8`、`versionName "1.0.0-Stable" → "1.1.0"`

## [1.0.0-Stable] - 2026-10-05
**versionCode 7**

### Added（新增）
- **完整 PTY 终端模块接入（com.termux:terminal-view + terminal-emulator，可选启用）**：
  - 项目根目录新增 `terminal-emulator/`、`terminal-view/` 两个 Gradle 模块（从 `termux-app` 仓库裁剪，仅保留所需，去掉了 `termux-shared` 及其 markwon/guava/hiddenapibypass 重依赖）
  - `settings.gradle` 引入 `:terminal-emulator` / `:terminal-view`；根 `build.gradle` 补 `com.android.library` 8.1.4；`app` 依赖 `project(':terminal-view')`
  - `terminal-emulator` 经 NDK r29（`ndkVersion=29.0.14206865`）编译出 `libtermux.so`（pty forkpty + SIGINT/SIGTSTP 信号）
  - 两个模块补 ProGuard `consumerProguardFiles`（keep JNI native 方法 + 核心类，防 release 混淆破坏）
  - `AndroidManifest` 加 `extractNativeLibs="true"`，保证 `libtermux.so` 解压到磁盘可被 `System.loadLibrary` 加载
  - 新增 `PtyTerminal.kt`（`PtyTerminalController` + `PtyTerminalScreen`）：用 `TerminalView` + `TerminalSession` 把 proot `bash -i` 接到真 pty；支持**真 Ctrl-C/D/Z（SIGINT/EOF/SIGTSTP）、jobs/fg/bg、vim/top、ANSI 彩色、宽字符、软键盘 Ctrl**
  - `TerminalScreen` 按设置 `terminalUsePty()` 分流：开 → 完整 PTY 屏；关（默认）→ 稳定管道版
  - 设置页「终端环境」新增「完整 PTY 终端」开关（默认关）
- **终端 v1.0.0-Stable（交互化 + Ctrl + 键盘跟随 + pkg 工具）**：
  - `ProotRunner` 新增 `buildInteractiveCommand()`（交互式 `bash -i`，带 job control）供 **PTY 版**使用；**管道版（AI 工具 / 默认终端）保持非交互 `bash -c`**，无 prompt 污染、稳定兜底；`/dev` 全量映射（含 ptmx/tty/urandom）为 PTY 铺路
  - `TerminalSession` 新增 `sendControlChar(ch)`：向常驻 shell 发送控制字符（Ctrl-C=`\u0003` / Ctrl-D=`\u0004` / Ctrl-Z=`\u001A`）
  - `TerminalScreen` 新增 **Ctrl-C / Ctrl-D / Ctrl-Z / 清屏 / 重启会话** 快捷按钮行（Termux 风格小方块），可一键发控制字符 / 重置会话
  - `TerminalScreen` 新增 `imePadding()`：软键盘弹起时输入框自动上移、输出区自适应压缩（修复"输入框不随键盘弹起"）
  - `ProotRunner` 新增 `buildInteractiveCommand()`；非交互路径保留 `bash -c` 向后兼容
- **pkg / apt 工具优化（不预装逆向工具）**：
  - `run_shell_command` 工具描述明确「60s 上限，超 60s 请改用 terminal_exec（120s 后台继续运行）」
  - `terminal_exec` 工具描述补充「装包 / pip / apt / 跑 Python 脚本」场景；`terminal_read` 新增 `since_last` 参数
  - `read_file` 工具新增**二进制自动识别**：抽样前 4KB NUL 字节占比，命中则返回 hex 预览 + 建议（xxd/strings/python）；`binary=true` 直接给前 4KB hex dump（修复读 .dex/.so/AXML 乱码痛点）
  - `ProotRunner` 交互式 bash 的 env 已含 `PATH/PREFIX/TERM/LANG`，`pkg` 命令可经 `bootstrap` 的 `usr/bin` 直跑
- **悬浮窗常驻 + 关闭按钮**：
  - `FloatingService.finishWork` 不再 5s 后自动 `stop`；AI 完成后悬浮球常驻，直到用户手动点「✕ 关闭悬浮窗」或 App 被系统回收
  - 面板新增「✕ 关闭悬浮窗」按钮（红字，位于「暂停 AI」下方）

### Fixed（修复）
- **悬浮窗 AI 完成后拖不动 bug**：
  - 旧实现 `toggleExpand` 用 `INVISIBLE`（还占布局空间）导致 root 宽度突增、`updateViewLayout` 后 x/y 偏移落空 → 改为 `GONE`（不占位）+ `rootView.post { clampToScreen() }` 自动收边
  - 旧实现 `handleDrag` 拖动无边界 clamp，拖出屏幕后整个悬浮窗"卡住"无法继续拖 → 改为参考 AndLua 示例 `math.max/min`，`coerceIn(0, maxX/maxY)` 始终在屏幕内
  - 补 `ACTION_CANCEL` 处理（拖到屏幕外 / 来电打断时不残留 dragMoved=true）
- **终端键盘弹起输入框不动**：`TerminalScreen` 的 `Box` 加 `.imePadding()`，软键盘出现时整块内容上移，输入框保持可见
- **AI 读二进制文件乱码**：`read_file` 新增二进制识别 + hex dump 预览 + `binary=true` 参数
- **AI terminal_read 输出残留混杂**：`TerminalSession.read` 新增 `sinceLast` 参数，按 `lastReadNo` 行号增量读，避免多次调用时把旧命令输出混进新结果
- **AI 终端执行 pkg / 长 Python 脚本常卡 60s**：`run_shell_command` 工具描述明确指向 `terminal_exec`；后者已 120s 上限且超时不杀进程（可后台继续），避免 `pip install` / `pkg` 被强制 kill

### Changed（变更）
- `versionCode` 由 6 提升至 7，`versionName` 升至 `1.0.0-Stable`
- `ProotRunner` 新增交互式 `buildInteractiveCommand()`（供 PTY 版）；管道版保持非交互 `bash -c`（稳定兜底，无 prompt 污染）
- `TerminalScreen` 新增 Ctrl-C/D/Z 按钮行、清屏 / 重启按钮；整体布局加 `imePadding`；按 `terminalUsePty()` 分流到 PTY 版 / 管道版
- `FloatingService` 面板加「✕ 关闭悬浮窗」按钮；`finishWork` 不再自动 stop

### Notes（说明）
- **完整 PTY 终端已接入（`terminal-emulator` + `terminal-view` 两模块）**，`libtermux.so` 由 NDK r29 在构建时编译。
  设置里「完整 PTY 终端」开关**默认关闭**——关闭时终端走稳定的管道版（Ctrl 按钮 / 重启会话 / 清屏 / 键盘跟随，AI 工具亦走此），
  打开后终端屏切换为真 pty（Ctrl-C/D/Z 真信号、jobs/fg/bg、vim/top、ANSI 彩色、宽字符）。

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
