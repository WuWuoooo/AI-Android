# 贡献指南（Contributing）

欢迎为 **Ai Android** 提 PR / Issue。提交前请先阅读并遵守以下约定。

## 代码风格

- **Kotlin**：遵循 [Kotlin 官方代码风格](https://kotlinlang.org/docs/coding-conventions.html)，使用 `kotlin.code.style=official`（见 `gradle.properties`）。
- 提交前用 IDEA / Android Studio 自动格式化，保证无多余空行与未使用 import。
- 命名：`CamelCase`（类/对象）、`lowerCamelCase`（函数/属性）、`SCREAMING_SNAKE`（常量）。

## 提交信息（Commit Message）

采用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：

```
<type>: <简短描述>

<body 可选，说明动机与影响>
```

`type` 取值：`feat` / `fix` / `docs` / `refactor` / `perf` / `test` / `chore`。

示例：
```
feat(agent): 新增网页抓取工具
fix(ui): 修复终端滚动条在长输出下溢出
```

## 分支

- 从 `main` 拉新分支：`feat/xxx`、`fix/xxx`、`docs/xxx`
- 不要直接推 `main`，通过 Pull Request 合并
- 合并前本地跑通：

```sh
./gradlew assembleDebug
./gradlew test
```

## 版本与变更日志

- 每次功能性改动后，在 `CHANGELOG.md` 顶部未发布（Unreleased）区追加一条记录。
- 发版时更新 `app/build.gradle` 的 `versionCode`（自增）与 `versionName`（语义化）。

## 安全与机密

⚠️ **绝对不要**把以下内容提交进仓库：

- 签名文件 `*.jks` / `*.keystore`（已在 `.gitignore` 忽略）
- API Key、密钥、个人配置（`.env`、`local.properties`）
- 构建缓存 `build/`、`.gradle/`

提交前用 `git status` 确认没有意外文件。

## 拉取请求（PR）

1. 简短描述改动目的
2. 关联对应 Issue（`Fixes #123`）
3. 说明是否涉及 UI / 权限 / 构建脚本改动
4. 贴出本地验证结果（构建/测试/运行截图）

## 代码组织（模块约定）

| 包 | 职责 |
|---|---|
| `agent/` `agent/tools/` | Agent 核心与工具实现 |
| `service/` | 无障碍 / 前台服务、Boot 接收器 |
| `ui/` | Compose 界面（chat / terminal / settings / theme） |
| `model/` `storage/` `provider/` | 数据模型、持久化、模型 Provider |
| `util/` `skills/` | 工具函数、技能模块 |

新增工具类放在 `agent/tools/`，并在 `ToolRegistry` 注册。

---

感谢你的贡献！ 🙏
