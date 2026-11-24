import { expect, it } from 'vitest';
import { selectedKnowledge, useKnowledgePreferences } from './useKnowledgePreferences';

it('默认启用双库且不允许剧透', () => {
  expect(selectedKnowledge(useKnowledgePreferences.getState())).toEqual({
    version: 'curated_knowledge_v1',
    library: 'both',
    allowSpoilers: false,
  });
});
it('设置可独立关闭两个库，健康库不会携带剧透授权', () => {
  expect(
    selectedKnowledge({ health: true, firefly: false, allowSpoilers: true })?.allowSpoilers,
  ).toBe(false);
  expect(selectedKnowledge({ health: false, firefly: true, allowSpoilers: false })?.library).toBe(
    'firefly',
  );
  expect(
    selectedKnowledge({ health: false, firefly: false, allowSpoilers: false }),
  ).toBeUndefined();
});
