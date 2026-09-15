# 独立 token 计数验证工具

这是离线参考工具，**没有接入生产 `countInputTokens`，也没有证明与在线 API 账单完全一致**。不读取 API key，不发送模型请求。采用固定版本 DeepSeek V4.1 官方 `encoding.py` 与模型词表，先渲染完整消息/工具模板，再调用 `tokenizers` 编码，不能将请求 JSON 直接分词。

## 已有验证

16 项 Python 测试、7 组固定样本。三组由真实 `src/deepseek.ts` 适配器导出的虚构请求，分别为 **347 / 421 / 512 input tokens**；它们使用简短测试 system 文本，不代表真实用户请求或生产整段系统提示的用量。

`golden.json` 是直接调用已校验官方参考源码生成后保存的固定样本，不由待测函数在测试时计算预期值；比较 token 数、prompt UTF-8 SHA-256、token IDs 的 little-endian uint32 字节 SHA-256。生产协议发生变化时，测试会重新导出请求与固定结果比较。不要为了让测试变绿而自动刷新这些预期。

## Windows 准备（首次联网，仅下载公开资源）

在本目录执行，需要 Node 22.22.2；`python` 需为真正的 Python 3.10+ x64，而非 Windows Store 占位命令：

```powershell
node prepare-assets.mjs
python -m venv .venv
& '.venv/Scripts/python.exe' -m pip --isolated install --disable-pip-version-check --no-deps --require-hashes --only-binary=:all: -r requirements-win.txt
```

已经有 `.venv` 时不要重建。依赖只装入工具虚拟环境，不修改系统 Python 或后端 Node 包。当前固定 wheel 为 Windows x64；其他平台应另行固定并核验同版 wheel，不要直接取消哈希限制。

`prepare-assets.mjs` 使用 `assets.lock.json` 的固定提交、大小及 SHA-256；只下载约 6.4 MB 的词表和约 37 KB 编码参考，不下载模型权重。相同文件跳过下载，已有异文拒绝覆盖，文件创建使用独占模式。`.assets/` 与 `.venv/` 不提交 Git。下载的 DeepSeek 资源按原 MIT 许可证使用，原文见 `LICENSE.deepseek`。

## 离线运行

```powershell
& '.venv/Scripts/python.exe' -B -m unittest -v
& '.venv/Scripts/python.exe' -B count_tokens.py --self-test
& '.venv/Scripts/python.exe' -B count_tokens.py --request 'D:/local/request.json'
```

也可使用 `--stdin` 接收 UTF-8 JSON。生产虚构请求导出：

```powershell
node --experimental-strip-types production-fixtures.mjs
```

导出只在真实适配器的计数预检中捕获 JSON 并停止；测试 transport 禁止联网。用户自行提供的请求文件不要加入 Git；默认输出只有版本、输入 token 数、是否超过 20,000 上限及内容摘要哈希，不输出原提示词或 token IDs。哈希也不要当作绝对匿名数据公开。

## 支持范围与失败规则

- 必须是当前后端形状的完整请求：`model=deepseek-flash`、`thinking.type=disabled`、`stream=false`、`max_tokens=1..600`、`messages`、`tools`，拒绝未知顶层字段。
- 只支持首条 system、用户文字、assistant 工具调用、对应工具结果；工具为 `runs_list` / `runs_summary`，不支持图片、命名空间、思考内容、JSON 输出格式或任意新协议字段。
- 拒绝重复 JSON 键、NaN/Infinity、无效 UTF-8、孤立代理字符、超过 64 KiB 输入和不完整工具历史。图片特殊标记也拒绝；普通角色标记按官方参考处理。
- `add_special_tokens=False`，防止重复添加已经存在于模板中的 BOS/EOS。超过 20,000 token 仍报告实际数量并返回 `withinInputLimit=false`，不截断输入伪造合格结果。
- 缺少资源、哈希/依赖版本错误、输入不支持或固定对照不一致时，命令非零退出；不输出用户内容或第三方异常堆栈。

返回的 `assurance=local_reference_only` 是硬边界：即使全部本地样本通过，也尚未认证线上模板、供应商隐藏处理或 Workers 的 CPU/内存成本。下一阶段需经授权以虚构输入核对真实 `usage.prompt_tokens`，版本/别名变化后重新认证；不要把字符估算或安全系数冒充精确计数。

上游来源：[官方编码参考](https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash/blob/dba1be0a40aa45a94ad051997016db3960a90277/encoding/encoding.py)、[官方词表](https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash/blob/dba1be0a40aa45a94ad051997016db3960a90277/tokenizer.json)。完整来源哈希见 `assets.lock.json`。
