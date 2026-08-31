import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { App } from './App';

describe('App', () => {
  it('renders the local Liuying shell', () => {
    render(<App />);

    expect(screen.getByRole('heading', { name: '流萤' })).toBeInTheDocument();
    expect(screen.getByText('正在唤醒你的跑步伴侣')).toBeInTheDocument();
  });
});
