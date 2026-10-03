[English](./README_en.md) | **中文**

# Ai Android

运行在 Android 手机上的智能助手 Agent 应用（Compose + Kotlin），具备聊天对话、手机界面操控（无障碍服务）、终端执行、文件管理等能力。

## 功能特性

- 🤖 **Agent 对话**：大模型驱动的对话式任务执行
- 📱 **手机操控**：通过无障碍服务完成点按、滑动、输入、应用启动等操作
- 💻 **内置终端**：常驻 shell 终端，支持长任务与 Python 执行
- 📁 **文件工具**：读写、搜索、移动、目录树浏览
- 🧠 **长期记忆**：跨会话保存与读取记忆条目
- 🔬 **公式渲染**：内置 JLaTeXMath 支持 LaTeX 数学公式

## 技术栈

| 组件 | 版本 |
|---|---|
| AGP | 8.1.4 |
| Kotlin | 1.9.22 |
| Compose | 1.7.0 / Material3 1.3.0 |
| Room | 2.6.1 |
| OkHttp (SSE) | 4.12.0 |
| kotlinx.serialization | 1.6.3 |

- `minSdk 24` / `targetSdk 34` / Java 17

## 构建

### 环境要求

- Android Studio Hedgehog 或更高（AGP 8.1.4 需要 JBR 17）
- JDK 17

### 编译

```sh
./gradlew assembleDebug      # Debug 包
./gradlew assembleRelease    # Release 包（需配置签名）
```

### Release 签名（可选）

自行创建 keystore，**不要**把 `*.jks` / `*.keystore` 提交到仓库（已被 .gitignore 忽略）。

## 项目结构

```
app/
├── src/main/
│   ├── kotlin/com/ai/android/
│   │   ├── agent/        # Agent 核心与工具（tools/）
│   │   ├── skills/       # 技能模块
│   │   ├── service/      # 无障碍服务、前台服务、Boot 接收器
│   │   ├── ui/           # Compose UI（chat / terminal / settings / theme）
│   │   ├── model/ storage/ provider/ util/ ...
│   ├── assets/           # 内置运行时（bootstrap、proot 等，体积较大）
│   └── jniLibs/          # arm64 原生库
```

## 版本

当前版本：`1.0.0`（versionCode 1）

## License

本项目（Ai Android）采用 **MIT License** 发布，完整条款见根目录 [`LICENSE.md`](./LICENSE.md)。

Copyright (c) 2026 Ai Android Contributors
