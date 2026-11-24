import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { App } from './App';

describe('App', () => {
  it('渲染以简洁对话为入口的应用外壳', async () => {
    render(<App />);

    expect(await screen.findByRole('heading', { name: '今天想把哪件事交给流萤？' })).toBeVisible();
    expect(screen.getByRole('navigation', { name: '页面切换' })).toBeVisible();
  });
});
