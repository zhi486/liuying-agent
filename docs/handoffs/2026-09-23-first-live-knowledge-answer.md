# 首次手机真实知识问答验收

用户明确允许一次手机真实问答。先确认手机连接及费率仍有效，指导用户仅发送“流萤的英文名字是什么？”，选择流萤官方资料、关闭剧透、不选记忆；用户随后回复“firefly”。本轮未追加第二次任务，也未请求Fish。

## 证据

- 手机只读Agent库快照保存在 `build/device-tests/knowledge-live-20260923` 当前用户受限目录；未读取密钥目录、运动库或修改手机数据库。抓取前后数据库相关文件大小/修改时间一致，快照integrity_check为ok。
- 原有在线任务1条，验收后2条；2026-09-23T14:59:15Z开始后仅新增1条。
- 测试请求 `74a7400f-d17a-41c0-babf-af130c3a501d`，云任务 `5d617943-4f4e-4d6e-9e53-42a6a4a6b013`；单次授权版本selected_knowledge_v1，library为firefly，allowSpoilers为false，memoryRefsJson为null。
- 回执status为completed、reason为null；resultState为acknowledged、ackPending为0。回答已保存在本机，不是模拟验收正文；actions记录一个model_requested步骤。
- 回答明确英文名Firefly及繁体中文名「流螢」，正文提及 `firefly_official_profile_v1_1`。
- 结构化sources含 `firefly_official_profile_v1_1` 与 `_v1_2` 两个基础资料片段，均为[流萤官网角色页](https://hsr.hoyoverse.com/zh-tw/character)的人工摘要；核验日期2026-09-23，发布日期null。没有含剧透story片段。

## 结论与边界

真实手机单次授权→正式云模型回答→官方资料检索来源→手机保存与接收确认已通过本次样例。用户已看到Firefly回答，随后明确确认能看到“本次检索来源”及流萤官网地址；App重启后视觉尚未单独确认。未查询供应商账单，不把一条模型步骤当成精确账单证明，也不泛化为所有问题/网络的成功保证。

当前费率有效期仍至2026-09-24 22:46:50（UTC+8），到期停止新任务；没有自动续期。语料仍为三份人工摘要、非完整剧情库、无自动更新。未做第二次模型调用、户外网络测试或Git提交。

后续用户要求已改为[费用仅估算与记账](2026-09-24-report-only-cost.md)，本地取消预算／费率到期停用，但尚未部署；上述到期门槛仍描述线上旧版本。
