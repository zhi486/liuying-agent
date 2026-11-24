// 产品允许长度内的新输入；计数期望由锁定 Python 参考独立生成，不由待测 JS 计算。
export const longPrompts = [
  Array.from({ length: 32 }, (_, i) =>
    `第${i + 1}段：流萤🏃‍♀️，帮我对照跑步记录；配速6′${String(i % 60).padStart(2, '0')}″，保持节奏。\n`).join('').padEnd(2000, '稳'),
  Array.from({ length: 28 }, (_, i) =>
    `观察${i}：emoji🌙、e\u0301、普通<image>文字</image>与换行。\n`).join('').padEnd(1999, '好'),
];

// 2026-10-19 自然聊天提示词变更后，由真实 Loop／适配器导出请求，锁定 Python 官方模板＋BPE 独立验证。
// 提示词变化时必须重新独立核对，不能直接照抄 workerd 的实测值。
export const longPromptReferences = [
  { inputTokens: 1768, promptSha256: '60bed1f8878fe5e1f016ce263aade869f14792957c231bc8c94fff698bf79abb' },
  { inputTokens: 1564, promptSha256: 'e2a51d04d1c338ec6ccd9651649d3dcddb735c2d157d07e84883d38f01190f2f' },
];
