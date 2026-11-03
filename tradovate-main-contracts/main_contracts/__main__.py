"""CLI: token from environment, hidden terminal prompt, or explicit standard input."""

import argparse
from contextlib import ExitStack, closing
import getpass
import json
import os
import sys

from .core import ResolutionError
from .service import resolve_all


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="查询全部可见 Futures 品种的上一完整交易日成交量主力")
    result.add_argument("--environment", choices=("demo", "live"), default="demo", help="账户及 REST 环境（默认 demo）")
    result.add_argument("--market-environment", choices=("demo", "live"), help="行情网关，默认与账户环境一致")
    result.add_argument("--token-stdin", action="store_true", help="从标准输入读取一行 accessToken")
    result.add_argument("--format", choices=("jsonl", "names"), default="jsonl", help="逐品种 JSONL 或 @根代码=主力名")
    result.add_argument("--output", help="将结果写入 UTF-8 文件；省略则输出到标准输出")
    result.add_argument("--request-timeout", type=float, default=15, help="单次请求超时秒数（默认 15）")
    result.add_argument("--product-timeout", type=float, default=180, help="单品种查询时间上限（默认 180 秒）")
    return result


def _token(args) -> str:
    if args.token_stdin:
        return sys.stdin.readline(32_770).strip()
    token = os.environ.get("TRADOVATE_TOKEN", "").strip()
    if token:
        return token
    if not sys.stdin.isatty():
        raise ResolutionError("TOKEN_REQUIRED", "请设置 TRADOVATE_TOKEN 或使用 --token-stdin")
    return getpass.getpass("Tradovate accessToken: ").strip()


def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    args = parser().parse_args(argv)
    counts = {"succeeded": 0, "failed": 0}

    def write(record, stream):
        if args.format == "jsonl":
            stream.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
            stream.flush()
        elif record["status"] == "ok":
            stream.write(record["continuous_symbol"] + "=" + record["contract_name"] + "\n")
            stream.flush()
        else:
            print(json.dumps(record, ensure_ascii=False), file=sys.stderr, flush=True)

    try:
        with ExitStack() as stack:
            token = _token(args)
            output = stack.enter_context(open(args.output, "w", encoding="utf-8", newline="\n")) if args.output else sys.stdout
            records = stack.enter_context(closing(resolve_all(
                token, environment=args.environment,
                md_token=os.environ.get("TRADOVATE_MD_TOKEN") or None,
                market_environment=args.market_environment,
                request_timeout=args.request_timeout, product_timeout=args.product_timeout)))
            try:
                for record in records:
                    counts["succeeded" if record["status"] == "ok" else "failed"] += 1
                    write(record, output)
            except ResolutionError as error:
                write({"status": "error", "code": error.code, "message": error.message}, output)
                write({"status": "summary", **counts, "complete": False}, output)
                return 1
            write({"status": "summary", **counts, "complete": True}, output)
        return 2 if counts["failed"] else 0
    except ResolutionError as error:
        write({"status": "error", "code": error.code, "message": error.message}, sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("查询已中断；已输出记录保留，各自有效期仍需检查。", file=sys.stderr)
        return 130
    except (OSError, EOFError):
        print("输入、输出或连接失败；未输出原始异常和凭据。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
