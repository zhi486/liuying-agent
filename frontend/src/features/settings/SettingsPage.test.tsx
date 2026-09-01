import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { SettingsPage } from './SettingsPage';

afterEach(() => {
  document.documentElement.dataset.theme = 'night';
});

describe('界面主题', () => {
  it('默认状态与夜萤主题一致，并可切换到晨光', () => {
    document.documentElement.dataset.theme = 'night';
    render(<SettingsPage />);

    expect(screen.getByRole('button', { name: /夜萤/ })).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(screen.getByRole('button', { name: /晨光/ }));

    expect(document.documentElement.dataset.theme).toBe('morning');
    expect(screen.getByRole('button', { name: /晨光/ })).toHaveAttribute('aria-pressed', 'true');
  });
});
