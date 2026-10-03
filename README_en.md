**English** | [中文](./README.md)

# AI Android

An intelligent assistant Agent app running on Android phones (Compose + Kotlin), with capabilities including chat conversation, phone UI control (accessibility service), terminal execution, file management, and more.

## Features

- 🤖 **Agent Chat**: LLM-driven conversational task execution
- 📱 **Phone Control**: Perform taps, swipes, text input, app launching, and other operations via accessibility service
- 💻 **Built-in Terminal**: Persistent shell terminal, supporting long-running tasks and Python execution
- 📁 **File Tools**: Read/write, search, move, directory tree browsing
- 🧠 **Long-term Memory**: Save and read memory entries across sessions
- 🔬 **Formula Rendering**: Built-in JLaTeXMath supports LaTeX math formulas

## Tech Stack

| Component | Version |
|---|---|
| AGP | 8.1.4 |
| Kotlin | 1.9.22 |
| Compose | 1.7.0 / Material3 1.3.0 |
| Room | 2.6.1 |
| OkHttp (SSE) | 4.12.0 |
| kotlinx.serialization | 1.6.3 |

- `minSdk 24` / `targetSdk 34` / Java 17

## Build

### Requirements

- Android Studio Hedgehog or later (AGP 8.1.4 requires JBR 17)
- JDK 17

### Compile

```sh
./gradlew assembleDebug      # Debug build
./gradlew assembleRelease    # Release build (signing configuration required)
```

### Release Signing (Optional)

Create your own keystore. **Do not** commit `*.jks` / `*.keystore` to the repository (already ignored by .gitignore).

## Project Structure

```
app/
├── src/main/
│   ├── kotlin/com/ai/android/
│   │   ├── agent/        # Agent core and tools (tools/)
│   │   ├── skills/       # Skill modules
│   │   ├── service/      # Accessibility service, foreground service, boot receiver
│   │   ├── ui/           # Compose UI (chat / terminal / settings / theme)
│   │   ├── model/ storage/ provider/ util/ ...
│   ├── assets/           # Built-in runtime (bootstrap, proot, etc.; large in size)
│   └── jniLibs/          # arm64 native libraries
```

## Version

Current version: `1.0.0` (versionCode 1)

## License

This project (AI Android) is released under the **MIT License**. See [`LICENSE.md`](./LICENSE.md) for the full terms.

Copyright (c) 2026 Ai Android Contributors