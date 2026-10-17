import type { KnowledgeDocument } from './knowledge.ts';

// 人工阅读官方来源后的简短中文摘要，不是全文或医学诊断规则。
// 官网角色页正文已核实；身份与身世独立标记剧透，不作为现实健康依据。
export const CURATED_KNOWLEDGE: readonly Readonly<KnowledgeDocument>[] = Object.freeze([
  Object.freeze({
    id: 'who_physical_activity', library: 'health', title: 'WHO：身体活动概览（人工摘要）',
    url: 'https://www.who.int/news-room/fact-sheets/detail/physical-activity',
    publishedAt: '2024-06-26', checkedAtMillis: Date.parse('2026-09-23T03:32:32Z'),
    // 本地人工复核期限，不是 WHO 声明的失效日期；不会自动续期。
    expiresAtMillis: Date.parse('2026-10-23T03:32:32Z'), version: 1,
    status: 'active', spoiler: false,
    content: '身体活动包括休闲、出行、工作和家务中的身体运动。步行、骑行、体育运动和主动娱乐都属于常见方式。\n\n' +
      '任何数量的身体活动都比完全不活动好，各种身体活动都可以计入；各年龄段都应限制久坐，肌肉强化活动也有益。WHO 按不同年龄与人群提供活动建议，不能将一般资料当作个人训练处方。',
  }),
  Object.freeze({
    id: 'firefly_official_profile', library: 'firefly', title: '流萤：官网角色介绍与萤火意象（人工摘要）',
    url: 'https://hsr.hoyoverse.com/zh-tw/character',
    // 官网 CMS 的上线时间不等于文章发布日期，故不据此填充发布日期。
    publishedAt: null, checkedAtMillis: Date.parse('2026-09-23T07:30:14Z'),
    expiresAtMillis: Date.parse('2026-10-23T07:30:14Z'), version: 1,
    status: 'active', spoiler: false,
    content: '流萤是《崩坏：星穹铁道》的角色，繁体中文官网名为「流螢」，英文官网名为 Firefly。\n\n' +
      '流萤官网的角色寄语以萤火虫为意象：即使生命可能短暂，也能在夜晚绽放光芒。这是虚构角色的文学表达，不是现实生物学或健康知识。',
  }),
  Object.freeze({
    id: 'firefly_official_story', library: 'firefly', title: '流萤：官网身份与身世（含剧透，人工摘要）',
    url: 'https://hsr.hoyoverse.com/zh-tw/character',
    publishedAt: null, checkedAtMillis: Date.parse('2026-09-23T07:30:14Z'),
    expiresAtMillis: Date.parse('2026-10-23T07:30:14Z'), version: 1,
    status: 'active', spoiler: true,
    content: '流萤属于星核猎手，身着名为「萨姆」（SAM）的机械装甲。官网介绍她作为兵器诞生，基因改造使她承受「失熵」的痛苦；她为了寻找生命的意义加入星核猎手，并寻求抗争命运的可能。这些都是游戏世界的设定，不能用于解释或诊断现实疾病。',
  }),
]);
