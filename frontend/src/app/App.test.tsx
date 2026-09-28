import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { App } from './App';

describe('App', () => {
  it('渲染以简洁对话为入口的应用外壳', async () => {
    render(<App />);

    expect(await screen.findByRole('heading', { name: '开拓者，想聊些什么？' })).toBeVisible();
    expect(screen.getByRole('navigation', { name: '页面切换' })).toBeVisible();
  });
});
