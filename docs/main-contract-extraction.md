# 主力合约查询独立模块

已将项目的“上一完整交易日成交量主力”方法抽离到 [tradovate-main-contracts](../tradovate-main-contracts/README.md)。整个目录可复制到其他项目，Python 3.11+ 运行，无 UniTrading 模块依赖。

输入 Tradovate accessToken，默认遍历当前服务器目录中全部可见的 Futures 产品，逐项返回 `@连续代码 → 实际月份主力名`。无法确认的品种返回错误；不使用近月或旧映射补齐。

- [安装、token 输入、完整输出和 Python 接入示例](../tradovate-main-contracts/README.md)
- [方法展示、原代码对应关系、协议及跨语言移植规则](../tradovate-main-contracts/METHOD.md)
- [原项目连续合约规则](continuous-contracts.md)

独立版采用串行、无持久化缓存的全量查询，保留月份完整性、交易日历、完整日线、唯一正最大成交量与有效期校验。原项目券商实现保持原状。全量目录和行情权限分开处理，目录可见不等于日线可访问。

独立版 `1.0.1` 已同步至 `cf6f75d`：固定日期查询中，即使仅返回较新日线，只要 `RealTime` 成功回执、有效历史通道、无历史包溢出及 `eoh` 齐全，目标日无日线按零成交量处理。较新成交量不参与排名。原项目的证据缓存版本 2 迁移不适用于无持久化缓存的独立版。

验证方式：在独立目录运行 `python -m unittest discover -s tests -v`。测试使用模拟数据和 socket，不读取真实凭据；本次抽离未执行真实账户全品种扫描。
