import { create } from 'zustand';
import type { KnowledgeSelection } from '../../bridge/cloudAgent';

type Preferences = { health: boolean; firefly: boolean; allowSpoilers: boolean };
const defaults: Preferences = { health: true, firefly: true, allowSpoilers: false };
const key = 'liuying:knowledge-preferences:v1';
function read(): Preferences {
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return defaults;
    const value = JSON.parse(raw);
    if (
      typeof value.health === 'boolean' &&
      typeof value.firefly === 'boolean' &&
      typeof value.allowSpoilers === 'boolean'
    )
      return value;
  } catch {
    /* 存储不可用时沿用默认值。 */
  }
  return defaults;
}
export const useKnowledgePreferences = create<
  Preferences & { update: (value: Partial<Preferences>) => void }
>((set) => ({
  ...read(),
  update(value) {
    set((previous) => {
      const next = {
        health: previous.health,
        firefly: previous.firefly,
        allowSpoilers: previous.allowSpoilers,
        ...value,
      };
      try {
        window.localStorage.setItem(key, JSON.stringify(next));
      } catch {
        /* 当前会话仍可使用选择。 */
      }
      return next;
    });
  },
}));
export function selectedKnowledge(value: Preferences): KnowledgeSelection | undefined {
  if (!value.health && !value.firefly) return undefined;
  return {
    version: 'curated_knowledge_v1',
    library: value.health ? (value.firefly ? 'both' : 'health') : 'firefly',
    allowSpoilers: value.firefly && value.allowSpoilers,
  };
}
