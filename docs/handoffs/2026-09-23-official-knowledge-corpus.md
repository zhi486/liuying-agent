# 官方流萤语料补充

后续状态：[手机知识选择、剧透许可与来源展示](2026-09-23-mobile-knowledge.md)已完成本地接线验收；本文件保留本批语料取证与当时状态，不代表生产已部署。

## 范围与结果

用户要求“补官方语料”。本批仅补本地可信目录与检索回归：两份流萤人工摘要，加上原有 WHO 一份，共三份。未扩展来源白名单、检索协议或生产接线；未读密钥、请求模型、部署、操作手机、提交或推送。健康内容未扩写成个性化训练处方。

流萤基础介绍收录中繁英名字与官网寄语的萤火意象；身份、装甲、身世和动机在独立 `spoiler: true` 文档中，默认不提供。中文摘要为人工转述，非全文复制；角色虚构设定不能用作现实医学解释。

## 官方正文证据与复核路径

核验时间：2026-09-23 07:30:14 UTC。面向读者的来源是 [官网角色页](https://hsr.hoyoverse.com/zh-tw/character)，动态页面需选择流萤；链接不会直接定位角色。英文名字另核对 [英文官网角色页](https://hsr.hoyoverse.com/en-us/character)。

本次只读请求链（公开，无登录或凭据，不执行远程脚本）：

1. 官网 `/en-us/character` HTML 引用 `/_nuxt/63a3c6f.js`；该脚本配置 CMS 应用 `113fe6d3b4514cdd`、角色频道 `242`。
2. `/_nuxt/8bb4810.js` 把角色页 chunk 2 映射至 `/_nuxt/0fe0160.js`；后者实际调用 `getContentList`，传 `iChanId`、`iPageSize`、`sLangKey`，并展示条目的 `sExt.story`、名字和角色寄语。
3. [英文频道公开接口](https://sg-public-api-static.hoyoverse.com/content_v2_user/app/113fe6d3b4514cdd/getContentList?iChanId=242&iPage=1&iPageSize=999&sLangKey=en-us&isPreview=0) 返回 `retcode: 0`，找到 `sTitle: Firefly`、`iInfoId: 123957`。
4. [中文单条公开接口](https://sg-public-api-static.hoyoverse.com/content_v2_user/app/113fe6d3b4514cdd/getContent?iInfoId=123957&sLangKey=zh-tw&isPreview=0) 返回 `retcode: 0`，`sTitle` 为流螢。解析 `sExt` JSON 后实际阅读 `name`、`story`、`subtitleText`，据此编写摘要。

字段映射：`name` / `sTitle` → 基础名字；`subtitleText` → 萤火意象（文学转述）；`story` → 含剧透身份、身世、动机。不从资源文件名推断属性/命途，不从合并配音字段推断各演员角色归属。

CMS `dtStartTime` 为 2024-06-19 06:50:00、`dtCreateTime` 为 2024-06-03 15:56:33，未当作文章发布日期，目录 `publishedAt: null`。CMS `dtEndTime` 也未当作事实有效期。本项目另设 30 天人工复核期，到期检索排除，不能只延后时间戳。

公开脚本哈希与接口可能变化；后续更新须重新从官网实际引用追踪并核验正文、对比摘要、检查剧透标记、递增变更文档版本并重建索引。接口请求只用于本次取证，未加入 App 抓取路径或运行时 URL 白名单。没有自动更新或定时任务。

## 验证与限制

新增正式语料固定题集，覆盖简繁英名字、寄语、身份身世的默认过滤与允许剧透、双库隔离、不支持词项、来源/未知日期及过期行为；本地 workerd 用例也验证角色命中和剧透过滤。

`corepack pnpm check` 通过：资产校验、Node/Workers 双类型检查、275 项后端测试（新增4项语料题集）。`corepack pnpm test:runtime` 通过：4文件、28通过、1既有诊断跳过；既有合成回执断流用例仍输出两条预期异常，不是本批失败。全部是本地验证。

词项命中只代表提供了相关片段，不证明能回答整个问题。例如含“流萤”的未收录剧情问题仍可能命中基础介绍；未知答案必须由回答层说明资料不足。本批没有真实模型回答评测，不能宣称事实准确率或完整剧情覆盖。

下一步是手机知识库选择、剧透许可和来源展示，与单次授权/任务快照一起接线；正式模型配置与消费仍须明确许可。自动官方资料更新尚未实现。
