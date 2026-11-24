import { createKnowledgeIndex } from '../src/knowledge.ts';
import { CURATED_KNOWLEDGE } from '../src/knowledge-corpus.ts';

// 只展示人工公共语料的检索结果；不读配置、不保存查询、不请求模型或网络。
const [library, query, ...extra] = process.argv.slice(2);
if ((library !== 'health' && library !== 'firefly') || !query || extra.length) {
  console.error('用法：node --experimental-strip-types tools/knowledge-demo.ts health|firefly "问题"');
  process.exitCode = 1;
} else {
  try {
    const result = createKnowledgeIndex(CURATED_KNOWLEDGE).search({ library, query, allowSpoilers: false, now: Date.now() });
    console.log(JSON.stringify(result, null, 2));
  } catch {
    console.error('检索输入或资料无效；未发送请求。');
    process.exitCode = 1;
  }
}
