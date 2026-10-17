# 知识选择新版正式部署与手机覆盖安装

后续：[正式模型配置与费率](2026-09-23-production-runtime-secrets.md)已另行获准上传两项Secret；本文Secret为空是部署批次当时状态，真实模型调用仍未执行。

用户在连接手机后明确批准更新现有 Free 账户正式后端、保留数据覆盖安装；本轮不新增资源、不上传供应商 Secret、不调用真实模型。使用 Wrangler/Workers 技能核对锁定版本帮助、显式生产配置、预演和旧版本。在线文档工具返回404，命令依据本地 Wrangler 4.131.2 帮助；未升级工具或兼容日期。

## 正式云

- 目标：现有 `liuying-agent`，`wrangler.production.jsonc`；D1 `liuying-agent-production` 与原 DO 绑定不变，兼容日期2026-09-18，Cron关闭。
- 原版本：`3c7eb331-430c-4777-b807-642c320dcefa`。部署前已读取版本详情和100%流量；回退可用项目锁定 Wrangler 的 `rollback <version-id> --config wrangler.production.jsonc`，但本轮没有执行。代码回退不会逆转手机数据库迁移。
- D1远端 `migrations list` 无待执行迁移；未执行迁移或登记新设备。
- 正式配置dry-run通过：6418.21 KiB，gzip1907.43 KiB。部署使用 `--keep-vars --strict`，未传Secrets文件。
- 新版本：`f27ed58e-a634-4ded-b7a7-98664d5d6320`，创建2026-09-23T14:23:41.828Z；部署后状态核对100%流量。启动时间报告2ms，不作为CPU或内存验收。
- 原有代理下部署遇到fetch failed；只读核实仍是旧版后，本命令取消代理，直连部署成功；未修改系统网络。正式workers.dev直连GET超时，经已有代理GET成功。
- 对合法随机请求UUID发送无凭据GET，返回401 `unauthenticated`、`Cache-Control: no-store`。仅验证可达性与认证拒绝；未验证正确凭据或真实知识回答，未发送任务POST。
- 部署前后 `secret list` 均为空。在线生成仍缺供应商密钥与费率，不能因代码部署成功宣称在线回答已可用。私密配置文件未读取或上传。

## 手机与数据保护

- 用户连接的 vivo V2241A 定向覆盖安装，安装前无运行服务、0未结束运动。
- 本地受限备份目录：`build/device-tests/knowledge-production-20260923`，仅当前Windows用户访问；保存前后数据库tar、解包副本及前后APK。未备份密钥目录、未上传健康记录，未删除已有内容。
- 新旧APK签名一致：`ebad2b92cff0324810062be3e8116645cfd352780ad79141be61be148774c603`。
- `adb install -r` 返回Success，两次冷启动Status ok；没有卸载、清数据或安装测试包。
- 跑步数据库保持v3：4条跑步、7条分段、7295路线点、0指标样本。Agent数据库v5→v6：任务/消息/行动/在线意图各1、回执/记忆各0。
- 两库integrity_check均ok；逐表按旧列与rowid比较，所有原字段内容完全不变；旧在线意图的knowledgeJson为null，没有扩大原授权或重新发送。
- 从真机读回APK，SHA256与本地已测包一致：`132F635D650149520F3C0E4820535983C3ECE591AFAF757D915BD646D84A24CB`。
- 最后已重新打开App。用户随后确认已选择流萤官方资料、剧透许可默认未勾选，且未点击在线执行；真机入口与默认许可验收通过。不播放语音、不生成运动记录、不点击在线执行。

## 下一步

本轮完成部署、覆盖安装及用户确认的真机入口验收，不是完整真实模型验收。供应商密钥与有效费率的私密核验和正式Secret上传需单独明确授权，真实模型请求也另行确认。语料仍三份人工摘要、无自动更新；来源回答展示的模拟验收见[手机知识接线](2026-09-23-mobile-knowledge.md)。未提交或推送Git。
