import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { App } from './App';

describe('App', () => {
  it('渲染流萤三屏应用外壳', async () => {
    render(<App />);

    expect(await screen.findByRole('heading', { name: '跑步' })).toBeVisible();
    expect(screen.getByRole('navigation', { name: '主要导航' })).toBeVisible();
  });
});
