# Workers 类型兼容修复与持续检查

日期：2026-09-15。用户“进行下一步”批准前轮建议后，以 bounded 小改动直接实施；主代理按 TDD 修复，子代理只读审查。保留当前 `codex/five-second-zones` 工作区和原有脏修改，未提交、推送、部署、付费调用或操作手机。

## 已交付

| 项目         | 结果                                                                                   |
| ------------ | -------------------------------------------------------------------------------------- |
| 定时器清理   | `agent-service.ts` 判定 timer 非 undefined 后清理，保留数字 0 的合法清理，不延长截止   |
| UTF-8 解码   | `deepseek.ts` 显式 `ignoreBOM:false`，保留原 BOM 默认行为与 `fatal:true`               |
| 日常类型检查 | `pnpm check` 顺序增加 `typecheck:workers`，任一步失败停止；不依赖 Spike 环境           |
| 环境隔离     | 全部生产 src 和 D1/WaitUntil/服务/恢复参数契约；完整 Node/DOM 混入均被反向类型检查拒绝 |
| 后端验证     | Node 类型、Workers 类型与 163/163 离线测试通过，新增 2 项解码行为回归                  |

## 改动与边界

新建 `tsconfig.workers.json` 和 `type-tests/workers.ts`；前者继承原严格检查选项，覆盖 `lib` 为 ES2022、`types` 为固定官方 Workers 声明，编译全部生产源码。后者只编译不执行，不被 Node 测试文件 glob 包含。正式配置没有 `as any`、`skipLibCheck`、DOM lib 或完整 Node 类型兜底。

注意官方 `@cloudflare/workers-types 5.20260915.1` 自带 `process:any` / `Buffer:any`。初版 `void process` 反向检查因此误报 unused directive；实际加载文件清单确认未混入 `@types/node`，故改用不存在的 `NodeJS.Process` 命名空间，加上 `document` 检测页面 DOM。不能把这些静态检查当成真实 nodejs_compat 开关、权限或运行时认证。

后端只增加同版 Workers **开发依赖**，pnpm 锁定完整 SRI；没有引入 JS 分词运行时依赖、改端点或移植 Python 模板。包管理器生成的发布等待期例外已移除，`pnpm-workspace.yaml` 仅保留空列表 `minimumReleaseAgeExclude: []`；离线 frozen-lockfile 安装重新成功，没有放宽全局或项目供应链检查。

`tools/workers-spike` 的旧类型假设脚本保留为历史取证材料，已在 README 停止推荐运行；当前正式类型检查不再要求源码保持那两条错误。旧报告增加当前交接指针，不改写历史测量结果。

## RED / GREEN 与反向验证

1. 正式 Workers 编译在生产改动前准确重现两条 TS2345：clearTimeout 参数可 undefined、TextDecoder 选项缺 ignoreBOM。两处最小修复后归零。
2. 新增前导 BOM 分块响应回归：得到正确 final，保留正文内部的 U+FEFF，usage 恰好保存一次；新增合法 JSON 字符串内含孤立 0x80 的回归：静态失败、usage 不保存、只请求一次。它们是原行为的保留测试，不声称旧解码行为有 bug。
3. 在隔离 Node 子进程中强制错误解码选项，分别令 `ignoreBOM:true`、`fatal:false`，两条测试分别按预期失败（exit 1）；没有覆盖源文件。这证实回归能捕获错误选项，而非只检查源码文字。
4. 临时编译参数分别混入完整 Node、DOM，并仅在该反向试验中加 `skipLibCheck`；依旧分别产生 TS2578（exit 2）。正式配置未改变，这也验证即使意外跳过依赖声明检查，环境反向断言仍能发现混入。
5. 去掉所有反向参数后，`corepack pnpm check` 通过 163 项测试；保留原 SQLite/MockTimers ExperimentalWarning。

本次是本地持续命令，不是已经创建 GitHub Actions 或云端持续集成。实际宿主部署时仍需按兼容日期、flags、绑定生成/核验宿主类型。

收尾：格式化后再次通过全量 163 项后端回归、16 项 Python 工具测试与 7 组固定样本、146 组 JS BPE ID 对照；本批格式/空白检查通过。独立复审重新通过 Node/Workers 类型及 70 项相关测试，确认全部 13 个生产源码受检查，反向环境注入被拒绝，无 Critical/Important。HEAD 仍为 `b2cdb7d70b7ee06c2982f34738a59c869ed6c634`。

## 下一步与授权边界

下一项建议是最小 Free 真云探针：只建 Free 测试资源、仅虚构数据与假供应商，验证 batch 计量、CPU/内存、迁移和断连记账。创建资源、登录配置和部署需要用户单独授权；不得自动升级付费方案、调用真实模型或上传旧手机任务。

当前完整 JS 模板、供应商 usage 对照、HTTP/手机在线闭环仍未完成。D1 正常三轮的 41 次 API / 76 SQL 未优化，batch 扣数口径仍待真云核验。免费托管不等于模型/语音免费，全 App $2 封顶尚未成立。
