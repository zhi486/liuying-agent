# 正式模型配置与费率上传

后续：用户单独授权的[首次手机真实知识问答](2026-09-23-first-live-knowledge-answer.md)已完成，Firefly回答与官方来源回执保存通过；下方未执行模型描述是配置上传批次当时边界。

用户明确允许私密读取已填写配置、核验当前官方费率并上传至现有正式Worker；不授权真实模型生成。本轮未改变手机，不读取设备Bearer，不调用DeepSeek接口。

## 核验与上传

- 通过[DeepSeek官方价格页](https://api-docs.deepseek.com/quick_start/pricing)直接HTTPS读取，HTTP200。模型别名 `deepseek-flash` 对应 DeepSeek-V4.1-Flash，与现有计数profile一致；尚未通过真实usage验证在线计数。
- 单位每百万token、美元：缓存命中高峰0.006/非高峰0.003，未命中高峰0.30/非高峰0.15，输出高峰1.20/非高峰0.60。账本只有输入/输出两价，采用高峰未命中保守上界，不展示为供应商精确账单。
- 公开快照：[费率JSON](../evidence/2026-09-23-deepseek-flash-rate.json)。实际核验2026-09-23T14:46:50Z，到期2026-09-24T14:46:50Z（UTC+8次日22:46:50）；按既有24小时规则，到期拒绝新任务，不自动刷新。未修改有效期策略。
- 原私密文件仍仅当前Windows用户有权限且被Git忽略。只用流式私密读取，初始报告密钥格式有效、费率缺失；与公开快照在内存合并后完整预检通过。未改写原私密文件，不复制密钥至新的配置文件。
- 按Wrangler技能，读取项目锁定4.131.2帮助、[官方Secrets文档](https://developers.cloudflare.com/workers/configuration/secrets/)，使用 `secret bulk --config wrangler.production.jsonc` 的stdin一次上传两项。关闭该子进程日志落盘与遥测、保持脱敏；捕获并丢弃原始输出，只输出退出码及成功的字段名。未使用命令参数传密钥。
- 上传exit0，部署前Secret清单为空；部署后仅 `DEEPSEEK_API_KEY`、`RATE_CARD_JSON`，类型均 `secret_text`。
- Secret变更新版本 `d6a08a76-0437-445c-b724-60964eb0d2a2`，创建2026-09-23T14:50:29.243Z，部署状态100%流量，代码沿用前版 `f27ed58e-a634-4ded-b7a7-98664d5d6320`。未创建资源或迁移数据库。
- 生产配置恢复两项 `secrets.required`，只列名称不含值；正式配置类型重新生成，Workers类型检查通过。
- 8项配置/私密预检回归全部通过，正式配置dry-run通过（6418.21 KiB／gzip1907.43 KiB）。经现有代理发送无凭据GET，仍返回401 unauthenticated及no-store；该检查不访问模型。

## 验收边界

已确认格式、公开现价和Cloudflare配置安装。没有验证密钥在DeepSeek真实有效、账户余额、手机在线回答、流量/VPN网络或真实usage；没有生成请求和Fish调用，不更改预算、套餐或续费。24小时后如果没有再次核验和更新费率，新任务将不可执行。

下一步另行允许一次手机真实问答，保持剧透关闭，先用“流萤的英文名字是什么？”验证官方资料回答与结构化来源；真实测试不能用模拟答案代替。未提交或推送Git。
