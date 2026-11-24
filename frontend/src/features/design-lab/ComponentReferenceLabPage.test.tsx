import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ComponentReferenceLabPage } from './ComponentReferenceLabPage';

const NAV_STORAGE_KEY = 'healthy-agent:ui-reference:back-navigation';
const COMPOSER_STORAGE_KEY = 'healthy-agent:ui-reference:composer';
const MESSAGE_STORAGE_KEY = 'healthy-agent:ui-reference:messages';
const CONVERSATION_STORAGE_KEY = 'healthy-agent:ui-reference:conversations';
const MANAGEMENT_STORAGE_KEY = 'healthy-agent:ui-reference:management';
const READINESS_STORAGE_KEY = 'healthy-agent:ui-reference:running-readiness-v4';
const ACTIVE_RUN_STORAGE_KEY = 'healthy-agent:ui-reference:running-active-v1';
const POST_RUN_STORAGE_KEY = 'healthy-agent:ui-reference:running-complete-v1';
const HISTORY_STORAGE_KEY = 'healthy-agent:ui-reference:run-history-v1';
const SEND_DELAY_MS = 900;

type PreviewGroup =
  | 'history'
  | 'postRun'
  | 'readiness'
  | 'activeRun'
  | 'composer'
  | 'messages'
  | 'conversations'
  | 'management'
  | 'settingStatus'
  | 'settingsToggle';

function renderPage(group: PreviewGroup = 'composer') {
  const view = render(
    <MemoryRouter>
      <ComponentReferenceLabPage />
    </MemoryRouter>,
  );
  if (group === 'composer') openGroup('查看第 04 组：聊天输入区');
  if (group === 'messages') openGroup('查看第 05 组：消息流');
  if (group === 'conversations') openGroup('查看第 06 组：会话侧栏行');
  if (group === 'management') openGroup('查看第 07 组：会话管理抽屉');
  if (group === 'readiness') openGroup('查看第 08 组：跑步跑前');
  if (group === 'activeRun') openGroup('查看第 09 组：跑步跑中');
  if (group === 'postRun') openGroup('查看第 10 组：跑步跑后');
  if (group === 'history') openGroup('查看第 11 组：跑步记录');
  if (group === 'settingStatus') openGroup('查看第 12 组：设置行状态值');
  if (group === 'settingsToggle') openGroup('查看第 13 组：设置项开关');
  return view;
}

function openGroup(label: string) {
  fireEvent.click(screen.getByRole('button', { name: label }));
}

function getComposerCard(name: string) {
  const heading = screen.getByRole('heading', { name, level: 3 });
  const card = heading.closest('article');
  if (!card) throw new Error(`未找到组件卡：${name}`);
  return card as HTMLElement;
}

function getComposerInput(card: HTMLElement) {
  return within(card).getByLabelText('给流萤的消息');
}

describe('组件参考台', () => {
  it('B+C 分组审阅每次只展开一条记忆', () => {
    renderPage('history');
    openGroup('查看第 14 组：长期记忆页');
    const card = screen.getByRole('article', { name: 'B 分组审阅 · B+C' });
    const rows = within(card).getAllByRole('button', { expanded: false });
    expect(within(card).getAllByRole('button', { expanded: true })).toHaveLength(1);
    fireEvent.click(rows[0]);
    expect(within(card).getAllByRole('button', { expanded: true })).toHaveLength(1);
    expect(rows[0]).toHaveAttribute('aria-expanded', 'true');
    expect(within(card).getAllByRole('button', { name: /^编辑：/ })).toHaveLength(1);
    fireEvent.click(rows[0]);
    expect(within(card).queryByRole('button', { expanded: true })).not.toBeInTheDocument();
  });

  it('第 14 组只在合成记忆中新增、编辑与确认删除', () => {
    renderPage('history');
    openGroup('查看第 14 组：长期记忆页');
    const card = screen.getByRole('article', { name: 'A 记忆清单' });
    fireEvent.click(within(card).getByRole('button', { name: '新增' }));
    fireEvent.change(within(card).getByRole('textbox', { name: '记忆内容' }), {
      target: { value: '合成测试偏好' },
    });
    fireEvent.click(within(card).getByRole('button', { name: '保存' }));
    expect(within(card).getByText('合成测试偏好')).toBeVisible();
    fireEvent.click(within(card).getByRole('button', { name: '编辑：合成测试偏好' }));
    fireEvent.change(within(card).getByRole('textbox', { name: '记忆内容' }), {
      target: { value: '修改后的合成偏好' },
    });
    fireEvent.click(within(card).getByRole('button', { name: '保存' }));
    fireEvent.click(within(card).getByRole('button', { name: '删除：修改后的合成偏好' }));
    expect(within(card).getByText('修改后的合成偏好')).toBeVisible();
    fireEvent.click(within(card).getByRole('button', { name: '确认删除' }));
    expect(within(card).queryByText('修改后的合成偏好')).not.toBeInTheDocument();
    fireEvent.change(within(card).getByRole('textbox', { name: '搜索记忆' }), {
      target: { value: '没有这条记忆' },
    });
    expect(within(card).getByText('没有匹配的记忆')).toBeVisible();
  });

  beforeEach(() => {
    window.localStorage.clear();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('默认进入第 11 组并展示记录列表三案', () => {
    renderPage('history');

    expect(screen.getByRole('heading', { name: '组件参考台' })).toBeVisible();
    expect(screen.getByRole('button', { name: '查看第 11 组：跑步记录' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('heading', { name: '跑步记录页的三种浏览方式' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '最近优先' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '日期时间线' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '月度训练账本' })).toBeVisible();
    expect(screen.getByTestId('history-previews').children).toHaveLength(3);
    expect(screen.getByText('已确认并接入：A 最近优先')).toBeVisible();
  });

  it('第 11 组三案共享空记录、加载更多与记录选择状态', () => {
    renderPage('history');

    fireEvent.click(screen.getByRole('button', { name: '空记录' }));
    expect(screen.getAllByText('还没有跑步记录')).toHaveLength(3);
    expect(screen.getByText('暂无记录')).toBeVisible();
    expect(screen.queryByText('共 5 次')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '加载更多记录' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '常态' }));
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }));
    expect(screen.getAllByText('正在读取更多记录')).toHaveLength(3);
    expect(
      screen
        .getAllByRole('button', { name: '正在读取更多记录' })
        .every((button) => button.hasAttribute('disabled')),
    ).toBe(true);

    const recent = getComposerCard('最近优先');
    fireEvent.click(within(recent).getByRole('button', { name: /查看 9月30日 06:42 的跑步总结/ }));
    expect(within(recent).getByRole('status')).toHaveTextContent('已选中 9月30日 06:42 的合成记录');
  });

  it('第 11 组把选择保存为记录页候选，并保持 A 为正式基线', () => {
    const { unmount } = renderPage('history');

    fireEvent.click(screen.getByRole('button', { name: '选择日期时间线作为记录列表基线' }));

    expect(window.localStorage.getItem(HISTORY_STORAGE_KEY)).toBe('timeline');
    expect(screen.getByRole('button', { name: '日期时间线已选为记录列表基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('对比候选：B 日期时间线；正式历史页仍为 A 最近优先')).toBeVisible();
    expect(
      within(screen.getByRole('button', { name: '查看第 11 组：跑步记录' })).getByText(
        'B 日期时间线 · 候选',
      ),
    ).toBeVisible();

    unmount();
    renderPage('history');

    expect(screen.getByRole('button', { name: '日期时间线已选为记录列表基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('第 12 组状态徽标三案共享合成列表并切换忙碌与错误反馈', () => {
    renderPage('settingStatus');

    expect(screen.getByRole('heading', { name: '设置行状态值的三种表达' })).toBeVisible();
    expect(
      screen.getByTestId('setting-status-previews').querySelectorAll('[data-variant]'),
    ).toHaveLength(3);
    expect(screen.getAllByRole('button', { name: '聊天授权，已授权' })).toHaveLength(3);
    expect(screen.getAllByText('12 条')).toHaveLength(3);

    fireEvent.click(
      within(screen.getByRole('group', { name: '预览状态' })).getByRole('button', {
        name: '同步中',
      }),
    );
    expect(
      within(screen.getByTestId('setting-status-previews')).getAllByText('同步中'),
    ).toHaveLength(3);
    fireEvent.click(screen.getByRole('button', { name: '连接失败' }));
    const statusPreviews = within(screen.getByTestId('setting-status-previews'));
    expect(statusPreviews.getAllByText('连接失败')).toHaveLength(2);
    expect(statusPreviews.getByText('重试')).toBeVisible();

    fireEvent.click(screen.getAllByRole('button', { name: '聊天授权，已授权' })[0]);
    expect(screen.getByRole('status')).toHaveTextContent('已点按“聊天授权”');
    const firstPreview = screen.getByTestId('setting-status-previews').querySelector('article');
    if (!firstPreview) throw new Error('找不到 A 案预览');
    fireEvent.click(within(firstPreview).getByRole('button', { name: '选择此方案' }));
    expect(window.localStorage.getItem('healthy-agent:ui-reference:setting-status')).toBe('badge');
    expect(within(firstPreview).getByRole('button', { name: '已选为候选基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('第 13 组开关三案共享本地切换状态并保存候选', () => {
    renderPage('settingsToggle');

    expect(screen.getByRole('heading', { name: '设置项开关的三种反馈' })).toBeVisible();
    const previews = screen.getByTestId('settings-toggle-previews');
    expect(previews.querySelectorAll('[data-variant]')).toHaveLength(3);
    expect(within(previews).getAllByRole('switch', { name: '跑步语音播报' })).toHaveLength(3);
    expect(within(previews).getAllByRole('switch', { name: '跑步语音播报' })[0]).toHaveAttribute(
      'aria-checked',
      'true',
    );

    fireEvent.click(within(previews).getAllByRole('switch', { name: '跑步语音播报' })[1]);
    expect(
      within(previews)
        .getAllByRole('switch', { name: '跑步语音播报' })
        .every((item) => item.getAttribute('aria-checked') === 'false'),
    ).toBe(true);
    expect(screen.getByRole('status')).toHaveTextContent('没有写入真实设置');

    const firstTogglePreview = previews.querySelector('article');
    if (!firstTogglePreview) throw new Error('找不到 A 案开关预览');
    fireEvent.click(within(firstTogglePreview).getByRole('button', { name: '选择此方案' }));
    expect(window.localStorage.getItem('healthy-agent:ui-reference:settings-toggle')).toBe(
      'switch',
    );
    expect(within(previews).getByRole('button', { name: '已选为候选基线' })).toBeVisible();
  });

  it('第 10 组保留跑后页 A 为已接入基线', () => {
    renderPage('postRun');

    expect(screen.getByRole('heading', { name: '组件参考台' })).toBeVisible();
    expect(screen.getByRole('button', { name: '查看第 10 组：跑步跑后' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('heading', { name: '跑后页的三种收尾方式' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '冲线成绩单' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '训练回执' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '复盘手账' })).toBeVisible();
    expect(screen.getByTestId('post-run-previews').children).toHaveLength(3);
    expect(screen.getByRole('button', { name: '冲线成绩单已选为跑后页面基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('已确认并接入：A 冲线成绩单')).toBeVisible();
  });

  it('第 10 组三案共享保存、同步异常和动作反馈', () => {
    renderPage('postRun');

    fireEvent.click(screen.getByRole('button', { name: '同步异常' }));
    expect(screen.getAllByText(/同步失败/).length).toBeGreaterThanOrEqual(3);
    expect(screen.getAllByRole('alert').length).toBeGreaterThanOrEqual(3);

    fireEvent.click(screen.getByRole('button', { name: '保存中' }));
    expect(screen.getAllByText(/正在保存/).length).toBeGreaterThanOrEqual(3);
    expect(screen.getAllByRole('button', { name: '查看记录' })[0]).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: '常态' }));

    const scorecard = getComposerCard('冲线成绩单');
    fireEvent.click(within(scorecard).getByRole('button', { name: '让流萤复盘本次跑步' }));
    expect(within(scorecard).getByText(/已触发“让流萤复盘本次跑步”/)).toBeVisible();
  });

  it('第 10 组把选择保存为跑后页候选，不冒充已接入', () => {
    const { unmount } = renderPage('postRun');

    fireEvent.click(screen.getByRole('button', { name: '选择复盘手账作为跑后页面基线' }));

    expect(window.localStorage.getItem(POST_RUN_STORAGE_KEY)).toBe('review');
    expect(screen.getByRole('button', { name: '复盘手账已选为跑后页面基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('对比候选：C 复盘手账；正式跑后页仍为 A 冲线成绩单')).toBeVisible();
    expect(
      within(screen.getByRole('button', { name: '查看第 10 组：跑步跑后' })).getByText(
        'C 复盘手账 · 对比候选',
      ),
    ).toBeVisible();

    unmount();
    renderPage('postRun');

    expect(screen.getByRole('button', { name: '复盘手账已选为跑后页面基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('第 09 组保留跑中页 A 为已接入基线', () => {
    renderPage('activeRun');

    expect(screen.getByRole('heading', { name: '组件参考台' })).toBeVisible();
    expect(screen.getByRole('button', { name: '查看第 09 组：跑步跑中' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('heading', { name: '跑中页的三种信息节奏' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '夜色驾驶舱' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '双窗里程表' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '节奏轨带' })).toBeVisible();
    expect(screen.getByTestId('active-run-previews').children).toHaveLength(3);
    expect(screen.getByRole('button', { name: '夜色驾驶舱已选为跑中页面基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('已确认并接入：A 夜色驾驶舱')).toBeVisible();
  });

  it('第 09 组三案共享暂停、结束确认与弱信号状态', () => {
    renderPage('activeRun');
    const cockpitCard = getComposerCard('夜色驾驶舱');

    fireEvent.click(within(cockpitCard).getByRole('button', { name: '暂停' }));
    expect(within(cockpitCard).getByRole('button', { name: '继续' })).toBeVisible();
    expect(within(cockpitCard).getByText(/已暂停：正式页面会发送 run\.pause/)).toBeVisible();

    fireEvent.click(within(cockpitCard).getByRole('button', { name: '结束' }));
    expect(within(cockpitCard).getByRole('alertdialog', { name: '结束本次跑步？' })).toBeVisible();
    expect(within(cockpitCard).getByRole('button', { name: '继续跑' })).toHaveFocus();

    const dialog = within(cockpitCard).getByRole('alertdialog', { name: '结束本次跑步？' });
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
    expect(within(cockpitCard).getByRole('button', { name: '确认结束' })).toHaveFocus();
    fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(within(cockpitCard).getByRole('button', { name: '继续跑' })).toHaveFocus();

    fireEvent.click(within(cockpitCard).getByRole('button', { name: '确认结束' }));
    expect(within(cockpitCard).getByText(/已确认结束：正式页面会发送 run\.stop/)).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '弱信号' }));
    expect(screen.getAllByText('GPS 延迟 6 秒')).toHaveLength(3);
  });

  it('第 09 组把选择保存为跑中页候选，不冒充已接入', () => {
    const { unmount } = renderPage('activeRun');

    fireEvent.click(screen.getByRole('button', { name: '选择节奏轨带作为跑中页面基线' }));

    expect(window.localStorage.getItem(ACTIVE_RUN_STORAGE_KEY)).toBe('tempo');
    expect(screen.getByRole('button', { name: '节奏轨带已选为跑中页面基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('对比候选：C 节奏轨带；正式跑中页仍为 A 夜色驾驶舱')).toBeVisible();
    expect(
      within(screen.getByRole('button', { name: '查看第 09 组：跑步跑中' })).getByText(
        'C 节奏轨带 · 对比候选',
      ),
    ).toBeVisible();

    unmount();
    renderPage('activeRun');

    expect(screen.getByRole('button', { name: '节奏轨带已选为跑中页面基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('第 08 组把 B 显示为已接入跑前基线', () => {
    renderPage('readiness');

    expect(screen.getByRole('heading', { name: '组件参考台' })).toBeVisible();
    expect(screen.getByRole('button', { name: '查看第 08 组：跑步跑前' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('heading', { name: '跑前页的三种 Keep 式结构' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '地图起跑' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '里程仪表' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '训练清单' })).toBeVisible();
    expect(screen.getByTestId('readiness-previews').children).toHaveLength(3);
    expect(screen.getByRole('button', { name: '里程仪表已选为跑前页面基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('已确认并接入：B 里程仪表')).toBeVisible();
    expect(screen.getByText('B 里程仪表 · 已接入')).toBeVisible();
  });

  it('保留正式 B 基线，并把其他跑前方案写成对比候选', () => {
    const { unmount } = renderPage('readiness');

    fireEvent.click(screen.getByRole('button', { name: '选择地图起跑作为跑前页面基线' }));

    expect(window.localStorage.getItem(READINESS_STORAGE_KEY)).toBe('console');
    expect(screen.getByRole('button', { name: '地图起跑已选为跑前页面基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('对比候选：A 地图起跑；正式跑前页仍为 B 里程仪表')).toBeVisible();
    expect(screen.getByText('A 地图起跑 · 对比候选')).toBeVisible();

    unmount();
    renderPage('readiness');

    expect(screen.getByRole('button', { name: '地图起跑已选为跑前页面基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('对比候选：A 地图起跑；正式跑前页仍为 B 里程仪表')).toBeVisible();
  });

  it('第 08 组手环连接只更新当前方案，并在完成后保留可选定位状态', () => {
    renderPage('readiness');
    const consoleCard = getComposerCard('地图起跑');
    const targetCard = getComposerCard('里程仪表');

    fireEvent.click(within(consoleCard).getByRole('button', { name: '连接 Keep B5' }));
    expect(within(consoleCard).getByRole('button', { name: '正在连接手环…' })).toBeDisabled();
    expect(within(targetCard).getByRole('button', { name: '连接 Keep B5' })).toBeEnabled();

    act(() => vi.advanceTimersByTime(800));

    expect(within(consoleCard).getByRole('button', { name: 'Keep B5 已连接' })).toBeDisabled();
    expect(within(consoleCard).getByText('心率数据可用 · 电量 82%')).toBeVisible();
    expect(within(targetCard).getByRole('button', { name: '连接 Keep B5' })).toBeEnabled();
  });

  it('第 08 组异常状态禁用三案开跑动作并显示长设备名', () => {
    renderPage('readiness');
    fireEvent.click(screen.getByRole('button', { name: '异常状态' }));

    const startButtons = screen.getAllByRole('button', { name: '等待定位' });
    expect(startButtons).toHaveLength(3);
    expect(startButtons.every((button) => button.hasAttribute('disabled'))).toBe(true);
    expect(screen.getAllByText('Keep B5 2A19 · 客厅充电位')).toHaveLength(3);
  });

  it('会话管理支持重命名、归档和取消删除', () => {
    renderPage('management');
    const card = getComposerCard('Codex 命令单');

    fireEvent.click(within(card).getByRole('button', { name: /重命名对话/ }));
    const input = within(card).getByRole('textbox', { name: '会话名称' });
    expect(input).toHaveFocus();
    fireEvent.change(input, { target: { value: '恢复跑安排' } });
    fireEvent.click(within(card).getByRole('button', { name: '保存名称' }));
    expect(within(card).getByText('恢复跑安排')).toBeVisible();

    fireEvent.click(within(card).getByRole('button', { name: /归档对话/ }));
    expect(within(card).getByRole('button', { name: /恢复对话/ })).toBeVisible();
    fireEvent.click(within(card).getByRole('button', { name: /删除对话/ }));
    expect(within(card).getByRole('region', { name: '确认删除对话' })).toBeVisible();
    fireEvent.click(within(card).getByRole('button', { name: '取消删除' }));
    expect(within(card).queryByRole('region', { name: '确认删除对话' })).not.toBeInTheDocument();
  });

  it('安全确认栈把永久删除推进到独立确认页', () => {
    renderPage('management');
    const card = getComposerCard('安全确认栈');

    fireEvent.click(within(card).getByRole('button', { name: '删除对话' }));
    const confirm = within(card).getByRole('region', { name: '确认删除对话' });
    expect(confirm).toHaveTextContent('永久删除“训练恢复建议”？');
    expect(within(confirm).getByRole('button', { name: '保留对话' })).toBeVisible();
    expect(within(confirm).getByRole('button', { name: '确认永久删除' })).toBeVisible();
    fireEvent.click(within(confirm).getByRole('button', { name: '保留对话' }));
    expect(within(card).queryByRole('region', { name: '确认删除对话' })).not.toBeInTheDocument();
  });

  it('固定 B 为已接入基线，并把其他选择保留为候选', () => {
    const { unmount } = renderPage('management');

    expect(screen.getByRole('button', { name: '分组玻璃面板已选为会话管理基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    fireEvent.click(screen.getByRole('button', { name: '选择安全确认栈作为会话管理基线' }));

    expect(window.localStorage.getItem(MANAGEMENT_STORAGE_KEY)).toBe('confirm');
    expect(screen.getByRole('button', { name: '安全确认栈已选为会话管理基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('当前候选：C 安全确认栈；正式管理仍为 B 分组玻璃面板')).toBeVisible();

    unmount();
    renderPage('management');

    expect(screen.getByRole('button', { name: '安全确认栈已选为会话管理基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('当前候选：C 安全确认栈；正式管理仍为 B 分组玻璃面板')).toBeVisible();
  });

  it('会话行切换当前项，管理菜单支持聚焦与 Esc 回焦', () => {
    renderPage('conversations');

    const card = getComposerCard('Codex 线程行');
    const week = within(card).getByRole('button', { name: /打开会话：本周训练安排/ });
    fireEvent.click(week);
    expect(week).toHaveAttribute('aria-current', 'page');
    expect(within(card).getByRole('status')).toHaveTextContent('当前会话：已切换合成会话');

    const trigger = within(card).getByRole('button', {
      name: '管理对话：训练恢复建议',
    });
    fireEvent.click(trigger);
    const menu = within(card).getByRole('menu', { name: '管理 训练恢复建议' });
    expect(within(menu).getByRole('menuitem', { name: '重命名会话' })).toHaveFocus();
    fireEvent.keyDown(menu, { key: 'Escape' });
    expect(within(card).queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('会话行长标题状态不会改变行结构或横向溢出', () => {
    renderPage('conversations');

    fireEvent.click(screen.getByRole('button', { name: '长标题' }));
    const previews = screen.getByTestId('conversation-row-previews');
    expect(previews.querySelectorAll('[data-preview-state="long"]')).toHaveLength(3);
    const card = getComposerCard('Codex 线程行');
    expect(
      within(card).getByRole('button', {
        name: /打开会话：聊天持续授权与历史记录管理的设置确认和归档清理/,
      }),
    ).toBeVisible();
  });

  it('把会话侧栏 B 记为候选并同步刷新后的选择', () => {
    const { unmount } = renderPage('conversations');

    fireEvent.click(screen.getByRole('button', { name: '选择玻璃分组行作为会话侧栏基线' }));

    expect(window.localStorage.getItem(CONVERSATION_STORAGE_KEY)).toBe('glass');
    expect(screen.getByRole('button', { name: '玻璃分组行已选为会话侧栏基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('当前候选：B 玻璃分组行；正式侧栏仍为 C 双行预览行')).toBeVisible();

    unmount();
    renderPage('conversations');

    expect(screen.getByRole('button', { name: '玻璃分组行已选为会话侧栏基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('当前候选：B 玻璃分组行；正式侧栏仍为 C 双行预览行')).toBeVisible();
  });

  it('消息流三案都只播放合成流式状态，不调用模型', () => {
    renderPage('messages');

    const card = getComposerCard('轨道通栏');
    const play = within(card).getByRole('button', { name: '播放流式回答' });
    fireEvent.click(play);

    expect(within(card).getByRole('button', { name: '正在播放流式回答' })).toBeDisabled();
    expect(within(card).getByRole('status')).toHaveTextContent('正在回答');
    expect(within(card).getByText(/今天先以恢复为主/)).toBeVisible();

    act(() => vi.advanceTimersByTime(1100));

    expect(within(card).getByRole('button', { name: '播放流式回答' })).toBeEnabled();
    expect(within(card).getByRole('status')).toHaveTextContent('已完成 · 合成回答');
    expect(within(card).getByText(/不替代诊断/)).toBeVisible();
  });

  it('消息流长回答状态与来源披露保持可检查', () => {
    renderPage('messages');

    fireEvent.click(screen.getByRole('button', { name: '长回答' }));
    const previews = screen.getByTestId('message-previews');
    expect(previews.querySelectorAll('[data-preview-state="long"]')).toHaveLength(3);

    const card = getComposerCard('双列气泡');
    const source = within(card).getByText('来源与操作');
    fireEvent.click(source);
    expect(within(card).getByText(/合成演示资料/)).toBeVisible();
  });

  it('把消息流 B 写成本地候选基线并同步刷新后的选择', () => {
    const { unmount } = renderPage('messages');

    fireEvent.click(screen.getByRole('button', { name: '选择对话稿纸作为消息流基线' }));

    expect(window.localStorage.getItem(MESSAGE_STORAGE_KEY)).toBe('paper');
    expect(screen.getByRole('button', { name: '对话稿纸已选为消息流基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('当前候选：B 对话稿纸；正式页仍为 C 双列气泡')).toBeVisible();

    unmount();
    renderPage('messages');

    expect(screen.getByRole('button', { name: '对话稿纸已选为消息流基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('当前候选：B 对话稿纸；正式页仍为 C 双列气泡')).toBeVisible();
  });

  it('切换 320px 后让三案共享宽度标尺', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: '320px' }));
    expect(screen.getByRole('button', { name: '320px' })).toHaveAttribute('aria-pressed', 'true');

    const previews = screen.getByTestId('composer-previews');
    expect(previews.querySelectorAll('[data-width="320"]')).toHaveLength(3);

    fireEvent.click(screen.getByRole('button', { name: '显示尺寸标尺' }));
    expect(within(previews).getAllByRole('img', { name: /尺寸标尺/ })).toHaveLength(3);
  });

  it('Enter 发送，Shift + Enter 与输入法组合态都不会误发送', () => {
    renderPage();

    const card = getComposerCard('Codex 单框悬浮坞');
    const input = getComposerInput(card);
    fireEvent.change(input, { target: { value: '今晚轻松跑多久？' } });

    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
    expect(within(card).getByRole('button', { name: '发送消息' })).toBeEnabled();
    expect(input).toBeEnabled();

    fireEvent.keyDown(input, { key: 'Enter', isComposing: true });
    expect(within(card).getByRole('button', { name: '发送消息' })).toBeEnabled();
    expect(within(card).queryByRole('button', { name: '正在发送消息' })).not.toBeInTheDocument();

    fireEvent.keyDown(input, { key: 'Enter' });
    expect(within(card).getByRole('button', { name: '正在发送消息' })).toBeDisabled();
    expect(input).toBeDisabled();
    expect(within(card).getByRole('status')).toHaveTextContent(
      '正在交给在线 Agent，演示中阻止重复提交',
    );

    act(() => vi.advanceTimersByTime(SEND_DELAY_MS));

    expect(input).toHaveValue('');
    expect(within(card).getByRole('button', { name: '消息已发送' })).toBeDisabled();
    expect(within(card).getByRole('status')).toHaveTextContent('没有调用真实模型或写入用户数据');
  });

  it('发送失败保留原文，重试成功后清空草稿', () => {
    renderPage();

    const card = getComposerCard('Codex 单框悬浮坞');
    const input = getComposerInput(card);
    fireEvent.click(within(card).getByRole('button', { name: '打开输入工具' }));
    fireEvent.click(within(card).getByRole('menuitem', { name: '模拟一次发送失败' }));
    fireEvent.change(input, { target: { value: '保留这段训练问题' } });

    fireEvent.click(within(card).getByRole('button', { name: '发送消息' }));
    act(() => vi.advanceTimersByTime(SEND_DELAY_MS));

    expect(input).toHaveValue('保留这段训练问题');
    expect(within(card).getByRole('status')).toHaveTextContent('原文已保留');
    expect(within(card).getByRole('button', { name: '重试发送' })).toBeEnabled();

    fireEvent.click(within(card).getByRole('button', { name: '重试发送' }));
    act(() => vi.advanceTimersByTime(SEND_DELAY_MS));

    expect(input).toHaveValue('');
    expect(within(card).getByRole('status')).toHaveTextContent('已发送到演示会话');
  });

  it('工具菜单首次打开自动聚焦，并支持完整键盘导航和 Esc 回焦', () => {
    renderPage();

    const card = getComposerCard('Codex 单框悬浮坞');
    const trigger = within(card).getByRole('button', { name: '打开输入工具' });
    fireEvent.click(trigger);

    const menu = within(card).getByRole('menu', { name: '单框悬浮坞输入工具' });
    const items = within(menu).getAllByRole('menuitem');
    expect(items[0]).toHaveFocus();

    fireEvent.keyDown(items[0], { key: 'ArrowUp' });
    expect(items.at(-1)).toHaveFocus();

    fireEvent.keyDown(items.at(-1)!, { key: 'ArrowDown' });
    expect(items[0]).toHaveFocus();

    fireEvent.keyDown(items[0], { key: 'End' });
    expect(items.at(-1)).toHaveFocus();

    fireEvent.keyDown(items.at(-1)!, { key: 'Home' });
    expect(items[0]).toHaveFocus();

    fireEvent.keyDown(items[0], { key: 'Escape' });
    expect(within(card).queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('语音入口只演示本地状态，不申请麦克风权限', () => {
    const originalMediaDevices = Object.getOwnPropertyDescriptor(navigator, 'mediaDevices');
    const getUserMedia = vi.fn();
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia },
    });

    try {
      renderPage();
      const card = getComposerCard('Codex 单框悬浮坞');
      const voice = within(card).getByRole('button', { name: '开始语音输入演示' });

      fireEvent.click(voice);

      expect(voice).toHaveAttribute('aria-pressed', 'true');
      expect(within(card).getByRole('button', { name: '结束语音输入演示' })).toBeVisible();
      expect(within(card).getByRole('status')).toHaveTextContent('不会录音');
      expect(getUserMedia).not.toHaveBeenCalled();

      fireEvent.click(within(card).getByRole('button', { name: '结束语音输入演示' }));
      expect(within(card).getByRole('status')).toHaveTextContent('没有访问麦克风');
      expect(getUserMedia).not.toHaveBeenCalled();
    } finally {
      if (originalMediaDevices) {
        Object.defineProperty(navigator, 'mediaDevices', originalMediaDevices);
      } else {
        Reflect.deleteProperty(navigator, 'mediaDevices');
      }
    }
  });

  it('双行任务稿纸切换上下文时可移除并恢复跑步摘要，草稿保持不变', () => {
    renderPage();

    const card = getComposerCard('双行任务稿纸');
    const input = getComposerInput(card);
    fireEvent.change(input, { target: { value: '请不要改掉这段草稿' } });

    fireEvent.click(within(card).getByRole('button', { name: '切换到记忆上下文' }));
    expect(within(card).getByRole('button', { name: '切换到记忆上下文' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(within(card).getByText('长期记忆')).toBeVisible();
    expect(within(card).getByRole('status')).toHaveTextContent('目标心率 145 来自合成数据');

    fireEvent.click(within(card).getByRole('button', { name: '切换到跑步上下文' }));
    fireEvent.click(within(card).getByRole('button', { name: '移除跑步摘要' }));

    expect(within(card).getByText('未附加跑步摘要')).toBeVisible();
    expect(input).toHaveValue('请不要改掉这段草稿');
    expect(within(card).getByRole('status')).toHaveTextContent('输入内容保持不变');

    fireEvent.click(within(card).getByRole('button', { name: '恢复跑步摘要' }));
    expect(within(card).getByRole('button', { name: '移除跑步摘要' })).toBeVisible();
    expect(within(card).getByRole('status')).toHaveTextContent('已恢复昨晚轻松跑摘要');
  });

  it('上下文堆栈卡按实际项目同步记忆开关和跑步摘要数量', () => {
    renderPage();

    const card = getComposerCard('上下文堆栈卡');
    expect(within(card).getAllByText('4 项')).toHaveLength(2);

    fireEvent.click(within(card).getByRole('button', { name: '关闭记忆引用' }));
    expect(within(card).getByRole('button', { name: '开启记忆引用' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    expect(within(card).getAllByText('3 项')).toHaveLength(2);

    fireEvent.click(within(card).getByRole('button', { name: '移除跑步摘要' }));
    expect(within(card).getByText('未附加跑步摘要')).toBeVisible();
    expect(within(card).getAllByText('2 项')).toHaveLength(2);
    expect(within(card).getByRole('status')).toHaveTextContent('输入草稿未改变');

    fireEvent.click(within(card).getByRole('button', { name: '恢复跑步摘要' }));
    expect(within(card).getAllByText('3 项')).toHaveLength(2);

    fireEvent.click(within(card).getByRole('button', { name: '开启记忆引用' }));
    expect(within(card).getAllByText('4 项')).toHaveLength(2);
    expect(within(card).getByRole('status')).toHaveTextContent(
      '已开启长期记忆引用；仅使用合成心率偏好',
    );
  });

  it('把 C 写成本地候选基线，并同步页头、导航和刷新后的选择', () => {
    const { unmount } = renderPage();

    fireEvent.click(screen.getByRole('button', { name: '选择上下文堆栈卡作为聊天输入区基线' }));

    expect(window.localStorage.getItem(COMPOSER_STORAGE_KEY)).toBe('stack');
    expect(screen.getByRole('button', { name: '上下文堆栈卡已选为本次基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('当前候选：C 上下文堆栈卡；正式页仍为 A 单框悬浮坞')).toBeVisible();
    expect(
      within(screen.getByRole('button', { name: '查看第 04 组：聊天输入区' })).getByText(
        'C 上下文堆栈卡 候选',
      ),
    ).toBeVisible();
    expect(screen.getByText(/正式聊天页仍采用 A/)).toBeVisible();

    unmount();
    renderPage();

    expect(screen.getByRole('button', { name: '上下文堆栈卡已选为本次基线' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('当前候选：C 上下文堆栈卡；正式页仍为 A 单框悬浮坞')).toBeVisible();
  });

  it('发送中卸载会清理演示计时器', () => {
    const { unmount } = renderPage();
    const card = getComposerCard('Codex 单框悬浮坞');
    const input = getComposerInput(card);

    fireEvent.change(input, { target: { value: '卸载前发送' } });
    fireEvent.click(within(card).getByRole('button', { name: '发送消息' }));
    expect(vi.getTimerCount()).toBe(1);

    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('实现明细标签支持方向键、Home 与 End', () => {
    renderPage();

    const logicTab = screen.getByRole('tab', { name: 'React 逻辑' });
    logicTab.focus();

    fireEvent.keyDown(logicTab, { key: 'ArrowRight' });
    const cssTab = screen.getByRole('tab', { name: 'CSS' });
    expect(cssTab).toHaveAttribute('aria-selected', 'true');
    expect(cssTab).toHaveFocus();

    fireEvent.keyDown(cssTab, { key: 'End' });
    const a11yTab = screen.getByRole('tab', { name: '无障碍' });
    expect(a11yTab).toHaveAttribute('aria-selected', 'true');
    expect(a11yTab).toHaveFocus();

    fireEvent.keyDown(a11yTab, { key: 'Home' });
    expect(logicTab).toHaveAttribute('aria-selected', 'true');
    expect(logicTab).toHaveFocus();
  });

  it('前 03 组已确认基线保持固定，不读取 Composer 候选记录', () => {
    window.localStorage.setItem(COMPOSER_STORAGE_KEY, 'stack');
    renderPage();

    openGroup('查看第 02 组：设置与列表行');
    expect(screen.getByRole('heading', { name: '列表行动的三种取舍' })).toBeVisible();
    expect(screen.getByText('已选为下一版基线')).toBeVisible();
    expect(screen.getAllByText('候选仅供对照')).toHaveLength(2);

    openGroup('查看第 03 组：主题与外观');
    expect(screen.getByRole('heading', { name: '主题选择器的三种取舍' })).toBeVisible();
    expect(screen.getByText('已确认并接入')).toBeVisible();
    expect(screen.getAllByText('候选仅供对照')).toHaveLength(2);

    const firstMorning = screen.getAllByRole('radio', { name: '晨光，清爽浅色' })[0];
    firstMorning.focus();
    fireEvent.keyDown(firstMorning, { key: 'ArrowRight' });
    expect(screen.getAllByRole('radio', { name: '夜萤，沉静深色' })[0]).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(screen.getAllByRole('radio', { name: '夜萤，沉静深色' })[0]).toHaveFocus();

    openGroup('查看第 01 组：顶栏与返回');
    fireEvent.click(screen.getByRole('button', { name: '文字返回胶囊已选为下一版基线' }));
    expect(window.localStorage.getItem(NAV_STORAGE_KEY)).toBe('capsule');
    expect(screen.getByRole('status')).toHaveTextContent('已按 A 方案接入');
  });
});
