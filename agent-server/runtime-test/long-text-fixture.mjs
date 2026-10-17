// 产品允许长度内的新输入；计数期望由锁定 Python 参考独立生成，不由待测 JS 计算。
export const longPrompts = [
  Array.from({ length: 32 }, (_, i) =>
    `第${i + 1}段：流萤🏃‍♀️，帮我对照跑步记录；配速6′${String(i % 60).padStart(2, '0')}″，保持节奏。\n`).join('').padEnd(2000, '稳'),
  Array.from({ length: 28 }, (_, i) =>
    `观察${i}：emoji🌙、e\u0301、普通<image>文字</image>与换行。\n`).join('').padEnd(1999, '好'),
];
