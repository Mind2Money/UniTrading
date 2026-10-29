# 代理规则

## 提交与 CI

- 提交信息不得包含 `[skip ci]`、`[ci skip]`、`[no ci]`、`[skip actions]`、`[actions skip]`，也不得以 `skip-checks: true` 尾注结尾。推送的最新 commit 带任一标记时，GitHub 不会为这次 push 创建 [build](.github/workflows/build.yml) 运行。
- 不要用 `gh workflow run` 手动触发代替 push 运行：`sign-android` 与 `notify-telegram` 只在 `github.event_name == 'push'` 时执行，手动触发的运行不会签名，也不会推送到 Telegram。
- 确需某次推送不跑 CI 时，先征得用户同意。
