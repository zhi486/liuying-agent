import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, it } from 'vitest';

const semanticRadiusTokens = [
  'line',
  'marker',
  'choice',
  'inset',
  'compact-surface',
  'control-surface',
  'drawer',
  'row',
  'input',
  'button',
  'tile',
  'notice',
  'message',
  'message-tail',
  'card',
  'panel',
  'dialog',
  'sheet',
  'sheet-expanded',
  'pill',
  'circle',
  'none',
] as const;

function stylesheets(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return entry.name === 'design-lab' ? [] : stylesheets(path);
    return path.endsWith('.css') ? [path] : [];
  });
}

function declarations(path: string) {
  const css = readFileSync(path, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  return [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].flatMap(([, selector, body]) =>
    [...body.matchAll(/border(?:-[a-z]+)*-radius\s*:\s*([^;]+);/g)].map(([declaration, value]) => ({
      selector: selector.trim(),
      declaration,
      value: value.trim(),
    })),
  );
}

it('圆角底层尺度只由语义角色引用，正式页面不得直接消费尺度值', () => {
  const tokenPath = join('src', 'design-system', 'tokens.css');
  const tokenCss = readFileSync(tokenPath, 'utf8');
  const definitions = new Map(
    [...tokenCss.matchAll(/(--radius-[a-z0-9-]+)\s*:\s*([^;]+);/g)].map(([, name, value]) => [
      name,
      value.trim(),
    ]),
  );

  expect([...definitions.keys()].filter((name) => name.startsWith('--radius-scale-'))).toHaveLength(
    16,
  );
  for (const role of semanticRadiusTokens) {
    expect(definitions.has(`--radius-${role}`), `缺少语义圆角 --radius-${role}`).toBe(true);
  }

  const violations = stylesheets('src').flatMap((path) => {
    const css = readFileSync(path, 'utf8');
    if (path === tokenPath) return [];
    return [
      ...[...css.matchAll(/--radius-[a-z0-9-]+\s*:/g)].map(
        ([declaration]) => `${path}: 不允许页面自定义 ${declaration}`,
      ),
      ...[...css.matchAll(/var\((--radius-scale-[a-z0-9-]+)\)/g)].map(
        ([reference]) => `${path}: 不允许直接引用 ${reference}`,
      ),
    ];
  });
  expect(violations).toEqual([]);
});

it('正式界面圆角只引用已声明的语义令牌，裸数值和旧别名均被拒绝', () => {
  const tokenPath = join('src', 'design-system', 'tokens.css');
  const tokenCss = readFileSync(tokenPath, 'utf8');
  const declared = new Set(
    [...tokenCss.matchAll(/(--radius-[a-z0-9-]+)\s*:/g)].map(([, name]) => name),
  );
  const legacy = /^--radius-(?:xs|sm|md|lg|compact|detail|control|surface-tight)$/;
  const violations = stylesheets('src')
    .filter((path) => path !== tokenPath)
    .flatMap((path) =>
      declarations(path).flatMap(({ declaration, value }) => {
        const refs = [...value.matchAll(/var\((--radius-[a-z0-9-]+)\)/g)].map(([, name]) => name);
        const residual = value
          .replace(/var\(--radius-[a-z0-9-]+\)/g, '')
          .replace(/\b(?:inherit|initial|unset|revert|revert-layer)\b/g, '')
          .replace(/[\s,]/g, '');
        const issues = [
          residual ? '包含裸数值或未知表达式' : '',
          ...refs.filter((name) => legacy.test(name)).map((name) => `引用旧别名 ${name}`),
          ...refs.filter((name) => !declared.has(name)).map((name) => `引用未声明令牌 ${name}`),
        ].filter(Boolean);
        return issues.map((issue) => `${path}: ${declaration} (${issue})`);
      }),
    );
  expect(violations).toEqual([]);
});

it('文本输入、普通操作按钮与特殊形状按语义角色归类', () => {
  const violations = stylesheets('src').flatMap((path) =>
    declarations(path)
      .flatMap(({ selector, value }) => {
        const withoutNegations = selector.replace(/:not\([^)]*\)/g, '');
        const isTextInput =
          /\b(?:input|textarea|select)\b/.test(withoutNegations) &&
          !/\[type=['"]?(?:checkbox|radio|range|color|file|hidden)/.test(withoutNegations);
        if (isTextInput && value !== 'var(--radius-input)') {
          return `${path}: ${selector} 的文本输入圆角应为 --radius-input，实际为 ${value}`;
        }

        if (/\bbutton\b/.test(selector)) {
          const allowed = new Set([
            'var(--radius-button)',
            'var(--radius-row)',
            'var(--radius-card)',
            'var(--radius-tile)',
            'var(--radius-pill)',
            'var(--radius-circle)',
            'var(--radius-none)',
            'inherit',
          ]);
          if (!allowed.has(value)) {
            return `${path}: ${selector} 的按钮圆角使用了非按钮角色 ${value}`;
          }
        }

        if (
          /\b(?:primary-button|secondary-button|quiet-button|danger-button)\b/.test(selector) &&
          value !== 'var(--radius-button)'
        ) {
          return `${path}: ${selector} 的通用按钮圆角应为 --radius-button，实际为 ${value}`;
        }
        return undefined;
      })
      .filter((violation): violation is string => Boolean(violation)),
  );
  expect(violations).toEqual([]);
});
