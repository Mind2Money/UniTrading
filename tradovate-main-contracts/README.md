# Tradovate 主力合约查询工具

输入 Tradovate `accessToken`，自动遍历服务器返回的全部 `productType == "Futures"` 品种，逐项输出连续代码（如 `@MES`）对应的实际主力合约名。

主力定义沿用 UniTrading：**同品种全部已挂牌、未到期月份中，上一完整交易日成交量唯一最大的合约**。成交量为日线 `upVolume + downVolume`。成交量并列、全零或证据不完整时输出失败记录。

当前版本 `1.0.1`，方法同步至仓库 `cf6f75d`：固定日期请求在实时权限、有效历史通道和历史结束标志齐全时，目标日无日线按零处理，包含仅返回较新日线的情况。较新日线的成交量不参与排名；请求失败或证据不完整仍返回错误。

本文件夹可整体复制使用。只依赖 Python 3.11+ 和 `websocket-client`，不需要 UniTrading、JDK、Gradle、数据库或原项目登录文件。只请求合约元数据与日线，不调用订单接口。

## 安装与运行

在本文件夹打开终端：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
# 交互式输入 accessToken，终端不回显；默认 demo，自动查询全部品种
.\.venv\Scripts\python.exe -m main_contracts
# 实盘 token 应显式选择 live
.\.venv\Scripts\python.exe -m main_contracts --environment live
```

macOS/Linux 对应使用 `.venv/bin/python`。在非交互环境设置 `TRADOVATE_TOKEN`；也可通过 `--token-stdin` 从标准输入读取一行 token。程序不自动登录、续期或读取其他应用保存的凭据。

输出形式：

```powershell
# 仅输出连续代码与主力名；失败和汇总输出到 stderr
.\.venv\Scripts\python.exe -m main_contracts --format names
# 完整结果逐行写入 UTF-8 文件，不等待所有品种完成
.\.venv\Scripts\python.exe -m main_contracts --output results.jsonl
# 帮助
.\.venv\Scripts\python.exe -m main_contracts --help
```

token 输入优先级：显式 `--token-stdin` → `TRADOVATE_TOKEN` → 终端隐藏输入。调用接口时 token 只保存在内存中，结果文件不包含 token。

默认用同一 `accessToken` 访问 REST 和行情网关，与项目网页登录会话当前做法一致。若你的会话要求独立行情令牌，可设置 `TRADOVATE_MD_TOKEN`。`--market-environment live` 可显式使用实盘行情网关（例如某些 demo 会话的行情来自 live 网关）；默认行情环境跟随 `--environment`。工具不会自动猜测 token 环境或轮流尝试网关。

## 输出约定

默认格式为 JSONL，每完成一个品种立即输出一行。下面为格式示例，名称、日期和数值均为演示值：

```json
{"status":"ok","continuous_symbol":"@MES","contract_name":"MESH7","contract_id":123,"product_id":456,"trade_date":"2026-11-02","volume":1000,"checked_at":"2026-11-03T06:00:00Z","valid_until":"2026-11-03T22:00:00Z","calendar_tail":false,"candidates":[{"contract_name":"MESZ6","contract_id":122,"volume":10},{"contract_name":"MESH7","contract_id":123,"volume":1000}]}
{"status":"error","continuous_symbol":"@GC","product_id":789,"code":"MARKET_DATA_DENIED","message":"该品种日线不可访问"}
{"status":"summary","succeeded":1,"failed":1,"complete":true}
```

`--format names` 的成功行：

```text
@MES=MESH7
```

- `continuous_symbol`：工具生成的 `@` + 产品根代码；不表示已验证这个别名的连续图表权限。
- `contract_name`：可以交给其他项目使用的实际月份合约名。
- `trade_date`：参与比较的服务端交易日期。
- `checked_at`：该品种开始查询的 UTC 时刻。
- `valid_until`：结果有效期的 UTC 上界，到此时刻必须重新查询。
- `calendar_tail`：日历没有覆盖后续时段，已启用 72 小时保护及最多 15 分钟有效期。
- `candidates`：全部候选的名称、ID 和成交量，便于核对排名。
- `complete`：是否已遍历全部目录。即使为 `true`，`failed` 仍可能大于零。

退出码：`0` 全部品种成功；`2` 完成遍历但存在失败；`1` 目录、授权、参数或 I/O 等全局错误；`130` 用户中断。授权失效会停止剩余查询，已有结果保留。失败记录没有主力名称，不会以近月或旧结果填充。

全量扫描可能跨过早先结果的有效期。接入方使用每条结果前需重新检查 `valid_until`；`names` 适合人工查看，自动化接入使用 JSONL。每个品种独立取查询时刻，不要求整批品种使用同一个交易日期。

## 在其他 Python 项目调用

两种接入方式任选其一：

1. 将 `main_contracts/` 复制进目标项目，并安装 `requirements.txt`。
2. 将整个文件夹复制过去，通过 `python -m pip install ./tradovate-main-contracts` 安装；也会注册 `tradovate-main-contracts` 命令。

```python
import os
from contextlib import closing
from main_contracts import ResolutionError, resolve_all

try:
    with closing(resolve_all(os.environ["TRADOVATE_TOKEN"], environment="demo")) as records:
        for record in records:
            if record["status"] == "ok":
                print(record["continuous_symbol"], record["contract_name"], record["valid_until"])
            else:
                print(record["continuous_symbol"], record["code"], record["message"])
except ResolutionError as error:
    print(error.code, error.message)
```

`resolve_all()` 是生成器。消费完毕或用 `closing()` 关闭时释放连接；提前退出也应关闭。可选参数：`md_token`、`market_environment`、`request_timeout`（默认 15 秒）、`product_timeout`（默认 180 秒）。单品种上限在各网络请求前后检查，正在执行的请求仍受单次超时限制。

其他语言可直接调用命令并解析 JSONL，或按 [METHOD.md](METHOD.md) 重写算法。返回值表示成交量映射，接入方自行处理行情、图表和下单。

## 查询范围与限制

- “全部”指当前 token 在 `product/list` 中可见的全部 Futures 产品。没有币种或硬编码品种名单过滤；非 Futures 产品不参与。产品目录中可能包含无有效月份或没有行情权限的品种，这些均单独报告失败。
- 产品目录可读不代表该品种的历史行情、实时行情或连续图表可读。无法确认的品种不会出现在成功映射中。
- 查询顺序按产品根代码排序；每个品种的月份串行请求，读完即取消图表订阅，降低同时订阅数。全量扫描请求量取决于品种数和挂牌月份数，可能较慢。
- 每次调用全量重新读取，不持久化成交量缓存。连接失败或 token 到期不会自动续期；更新 token 后重新运行。
- 日历尾部的 72 小时窗口依赖服务器日历覆盖情况，无法仅靠日线证明服务器没有遗漏其他完整交易日。详细规则见方法文档。

## 离线验证

```powershell
python -m unittest discover -s tests -v
```

测试使用固定交易日、模拟 REST 与行情 socket，不使用真实账户或 token。覆盖完整月份排名、交易日历、日线证据、早到行情包、限流、订阅清理、全品种遍历及 CLI 输出。真实 token 的全品种权限和线上结果需要在使用方环境核实；本次抽离没有执行真实账户扫描。

2026-11-03 在 Windows、Python 3.14、websocket-client 1.9.2 上验证 `1.0.1`：64 项离线测试通过；整个目录复制到仓库外后，在独立虚拟环境中构建并安装成功，安装后的命令帮助与全部 64 项测试通过。安装包从虚拟环境的 `site-packages` 加载，没有引用原仓库路径。

## 文件职责

| 文件 | 职责 |
| --- | --- |
| `main_contracts/core.py` | 纯算法、交易日历、日线成交量验证 |
| `main_contracts/client.py` | 固定官方地址的 REST 与行情 WebSocket |
| `main_contracts/service.py` | 全品种发现、逐项隔离失败、有效期复查 |
| `main_contracts/__main__.py` | token 输入、JSONL/名称输出、退出码 |
| `METHOD.md` | 算法展示、协议和跨语言移植要求 |
| `tests/` | 无真实账户依赖的回归验证 |
