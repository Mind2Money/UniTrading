# 代理规则

## 提交与 CI

- 提交信息不得包含 `[skip ci]`、`[ci skip]`、`[no ci]`、`[skip actions]`、`[actions skip]`，也不得以 `skip-checks: true` 尾注结尾。推送的最新 commit 带任一标记时，GitHub 不会为这次 push 创建 [build](.github/workflows/build.yml) 运行。
- 不要用 `gh workflow run` 手动触发代替 push 运行：`sign-android` 与 `notify-telegram` 只在 `github.event_name == 'push'` 时执行，手动触发的运行不会签名，也不会推送到 Telegram。
- 确需某次推送不跑 CI 时，先征得用户同意。

## 发布

- 版本号同时改 `androidApp/build.gradle.kts`（`versionName`、`versionCode`）、`composeApp/build.gradle.kts`（`packageVersion`）和 `RithmicSession.DEFAULT_APP_VERSION`。
- 发布说明写在 [docs/release-notes.md](docs/release-notes.md) 的 `# vX.Y.Z` 小节，两种语言都要有；未发布的改动先记在 `# Unreleased`。
- `v*` tag 触发 Windows 打包和 GitHub Release，tag 必须等于 `v<versionName>`。完整步骤见[本地构建与 CI](docs/local-build.md#发布版本)。
- GitHub Release 成功后，CI 用 `DISCORD_RELEASE_WEBHOOK` secret 发 Discord 公告，只发 `## 简体中文` 小节。Webhook URL 不得写进受跟踪文件、日志或发布说明。发送记录是 commit status；状态为 `pending` 时先看频道，不要盲目重发，见 [Discord 公告](docs/local-build.md#discord-公告)。
- `scripts/build-local.ps1` 读到本机 `local-build.json` 时默认签名并发 Telegram；只做回归时传 `-Sign:$false -NotifyTelegram:$false`。
