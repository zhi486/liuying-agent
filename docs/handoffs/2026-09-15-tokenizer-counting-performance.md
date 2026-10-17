# 精确计数轻量路径：隔离性能对照

日期：2026-09-15，云请求约20:37–20:40（UTC+8）。用户已批准小范围性能 Spike；复用唯一免费 Worker/D1 `liuying-free-spike-20260915`，不修改生产代码，不调用真实模型，不上传真实运动数据，不升级套餐、不扩大权限、不删除资源。

## 本批结论

| 验收项 | 结果 |
| --- | --- |
| 轻量路径精确性 | 146组完整 token ID 对照，以及3条当前 Agent 请求的完整 ID 对照均通过 |
| 云端计数请求 | 两个模式各12条，计数均正确；轻量路径CPU为2–21 ms，仍有7/12条超过10 ms |
| 完整假模型 Agent | 原路径单轮/三轮CPU39/55 ms；轻量路径19/28 ms，均超过10 ms |
| 任务与账务 | 独立真实D1回读确认4条任务completed、结果保留，8份模拟收据均仅结算一次 |
| 内存峰值 | 仍未知；本批无exceededMemory事件，不等于得到精确峰值或证明所有负载安全 |
| 收尾 | 公网及Preview均API回读false；恢复轻量入口，配置关闭临时日志，移除PERF_MODE |
| 需要用户配合 | 本批无需手机；手机真实断网/UI恢复仍未测试 |

**不能把此候选接入“Free稳定生产”验收。** 轻量路径本轮多项CPU较低，但两组路由机房不同、样本少，不能把全部差值归因于优化，更不能承诺稳定降幅。下一步应先评估精确计数实现/执行边界的替代方案，再批准下一批实现；不要继续无界重跑云测，也不要自动改为付费服务。

## 实现范围与可信边界

锁定 `@huggingface/tokenizers@0.2.0` 与既有官方资产。原路径为 `encode(prompt, {add_special_tokens:false}).ids.length`；候选使用公开 `tokenize(prompt, {add_special_tokens:false}).length`。它省略后续ID映射及attention_mask分配，**没有跳过BPE、ByteLevel或正则处理**，没有使用内部私有API。

`counting-candidate.test.mjs` 对146组完整ID逐例做LE uint32 SHA-256对照，不只比较数量；三个实际Agent请求另做相同对照。计时请求不重新生成ID哈希，避免把校验成本混入模式差异。

完整Agent路径仍由现有 `createAgentService` 执行，只在隔离 `agentProbe` 注入计数端口。先在本地捕获三轮**实际序列化请求原始字符串**；Python参考渲染canonical prompt并计算原始UTF-8字符串SHA-256。云端只有当前请求哈希完全命中固定参考，才执行该prompt的BPE；未知请求抛错，不回退到100。

三轮参考计数397/471/562。此方式是**原始请求哈希绑定的固定canonical-prompt实验**，不是已经把完整消息模板移植到JS，也不能处理任意生产请求。Python stdin/stdout显式UTF-8，避免Windows默认GBK改变参考输入。

假供应商仍固定usage100/40，费率也是虚构，每次账单78微美元。它用于检验收据/结算流程，**不是实际API消费，也不是精确预计数与真实供应商usage一致性验证**。

## 真云CPU证据

数据来自实际Wrangler Tail顶层`cpuTime`，非客户端计时；包含鉴权、计数、少量日志/响应等整条请求开销。各部署按六例顺序执行两遍；不接受用户文本、循环次数或SQL。每个模式独立部署，并以`/perf/ready`的协议和模式校验就绪；业务请求失败不重试。

| 用例 | tokens | encode第一遍/第二遍CPU ms | count第一遍/第二遍CPU ms |
| --- | ---: | ---: | ---: |
| production-round-1 | 347 | 15 / 2 | 21 / 2 |
| production-round-2 | 421 | 20 / 7 | 14 / 5 |
| production-round-3 | 512 | 7 / 4 | 5 / 2 |
| loop-large-round-1 | 2390 | 35 / 30 | 20 / 13 |
| loop-large-round-2 | 3789 | 35 / 26 | 19 / 13 |
| loop-large-round-3 | 3924 | 23 / 32 | 14 / 9 |

encode中8/12条、count中7/12条CPU高于10ms；两组合计4/4 Agent请求高于10ms。所有28条业务请求HTTP200/outcome=ok，但不作为配额合格证明。[Workers官方限制](https://developers.cloudflare.com/workers/platform/limits/)规定Free HTTP CPU10ms；偶发超限容忍不是持续超限保证。

两组各观察到两个不同isolate；模式间marker不重合。每组production-round-1的第二遍换了isolate，因此`seenCaseBefore=false`，**不能把第二遍统称warm**。其余五例第二遍在同一isolate且seen=true；首次访问case也可能已被其他case预热共同BPE片段。`isolateCall`仅是本探针BPE调用序号，不是全部HTTP调用序号或初始化计时。

encode第一条cf-ray后缀SIN，其后CDG；count全为SIN。这不是严格同机房A/B，没有p95、并发压力、长时间缓存/GC或稳定冷启动分布证据。

| 整条假模型Agent | encode CPU / wall ms | count CPU / wall ms |
| --- | ---: | ---: |
| 单轮 | 39 / 4135 | 19 / 363 |
| 三轮 | 55 / 7336 | 28 / 551 |

这些请求包含合成账户/设备初始化、任务/预算/D1操作、原始请求哈希查询、精确BPE及假供应商响应。不含真实HTTP供应商开销或动态JS模板渲染，**不是生产稳态CPU，也不是生产CPU严格上界**。CDG/SIN及D1往返差异尤其影响wall，不能把wall下降说成分词优化效果。

两个包均8606.89 KiB、gzip2059.81 KiB。平台部署启动验证encode202ms、count191ms，与请求CPU不同。版本：encode `518db50a-52b7-4639-9c68-2e9cca364385`；count `1d760d92-6462-4c01-9698-be0467bb60f1`。

## 本地性能与回归

本地每case/模式创建独立Tokenizer，交替测试顺序；构造成本和完整ID校验不计入循环。50次重复的Node wall中位数（encode→count，ms）分别为0.745→0.643、0.788→0.743、0.981→0.900、7.805→6.626、9.349→9.349、10.349→9.623。它不是Cloudflare CPU或内存，不能据此判定免费档通过。

- 隔离工具36项测试通过，其中一个测试覆盖146组完整ID。
- 后端163项测试与Node/Workers两套TypeScript检查通过。
- 计数候选、可注入计数端口、性能入口和云runner均先观察针对性失败再实现通过。
- 云runner限制就绪最多4次，仅就绪可等待；只执行12条计数、单轮/三轮各1条，不自动重试建任务。
- `verify-counting-evidence.mjs`将28条客户端结果与平台日志按mode/marker/序号或taskId一一匹配，并检查D1和关闭公网状态。
- 独立只读审查复跑36项测试和云证据校验通过，无Critical/Important问题；两处报告措辞/舍入小问题已修正，README已补全首次创建`.output`目录的前置步骤。

## 任务与原始证据

| 模式/轮次 | taskId | 收据数 |
| --- | --- | ---: |
| encode单轮 | 7db08af6-25a0-456a-8b43-1003652076c5 | 1 |
| encode三轮 | 6586eb7e-fb21-423a-be7c-05d5b21ff56e | 3 |
| count单轮 | d5cc2d3c-da5c-47c7-96e0-90a6e63cf781 | 1 |
| count三轮 | ffb9cc9e-fead-448b-b95d-a042c3559931 | 3 |

4条任务均completed/result available，model_requested数1/3/1/3；8条预留均settled、applied=1、charged=78、执行关联一致、violation=0。查询只读、rows_written=0。没有重建或重发上述任务。

证据均在被Git忽略的`agent-server/tools/cloud-spike/.output/`：

- `counting-local-1789475049110.json`
- `counting-cloud-encode-1789475824142.json`、`tail-1789475809428.json`
- `counting-cloud-count-1789475978587.json`、`tail-1789475950212.json`
- `agent-tokenizer-cases.json`、`counting-final-evidence.json`

原始Tail请求头未保存；只白名单指标和固定虚构日志落盘。不得展示或提交`session-token`。

## 收尾

本批获批的新固定窗口截至2026-09-15 21:35（UTC+8），不是沿用已过期的19:30窗口。测试结束即提前关闭URL；随后恢复轻量版本`811de23d-783f-4817-a31f-9ad0d11cd66a`，配置observability=false、workers_dev=false、preview_urls=false，移除PERF_MODE。GET subdomain明确回读两项false；settings未返回observability字段，不写成“API明确返回false”。

未删除Worker/D1/证据，未提交或推送Git，未触碰既有Android/React修改。上一批真实客户端TCP中断/收据恢复证据仍有效且未重做；手机飞行模式、进程终止与UI恢复仍待单独安排。
