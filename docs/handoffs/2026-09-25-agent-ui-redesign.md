# 流萤手机前端重构交接

日期：2026-09-25。用户希望首页参考主流 Agent 工具的输入优先布局，跑步与记录参考 Keep 的数据阅读方式，并保持简洁、优雅、适合运动。

## 本轮实现

- 保留现有 React、CSS Modules、路由和日夜主题，没有增加运行时依赖。
- 首页将提问框作为首屏主元素，新增只填草稿的快捷提问；语音输入、仅保存、在线执行及单次授权沿用原流程。
- 首页以低对比度同心波纹呼应用户提供的 Shape Waves 参考；背景静态，不使用持续动画。知识库与记忆选择保持可见、可键盘操作，复选框沿用原生控件。
- 底部四栏导航调整为文字标签与当前页底色；加入键盘跳到主要内容的入口。统一字体、间距、卡片、按钮和轻触反馈。原跑步页、记录页与设置页继续使用同一主题变量。
- 在线记录标题简化为“最近对话”，同时保留原始在线配置和记录操作。

## 参考与取舍

- [Aceternity UI](https://ui.aceternity.com/components)：参考组件节奏，没有复制动效组件。
- [React Bits Shape Waves](https://reactbits.dev/backgrounds/shape-waves)：提取波纹意象，改为静态 CSS 背景，避免运动场景中持续消耗手机性能。
- [Uiverse Checkboxes](https://uiverse.io/checkboxes)：该页本机访问返回 403；未复制代码，保持原生复选框的可访问性。
- [Anthropic frontend-design skill](https://github.com/anthropics/skills/tree/main/skills/frontend-design)：采用单一视觉重点与克制装饰。
- [Vercel web-design-guidelines skill](https://github.com/vercel-labs/agent-skills/tree/main/skills/web-design-guidelines)：核对触控目标、焦点、标签和移动端溢出。
- 本机 `design-taste-frontend` skill 用于审计既有品牌与日夜主题；其营销页规则不直接套用到运动数据页。

## 验证与边界

- TypeScript、ESLint、Prettier 与 Vite 构建通过。单线程完整前端单测 226/226 通过；先前并发运行的跑后曲线异步用例单独复测也通过。
- Chrome 手机视口 320、390、430 像素无横向溢出；已检查首页日夜主题、跑步中、记录与设置首屏。快捷提问只回填草稿，路由不变。
- Android `:app:assembleDebug` 离线构建通过，新前端资源已进入调试包。APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA256 `ABF8BCA94D4510605AA56603A7411BAB773898E776A86C7C40B49E6FB61836CD`。
- 预览图在 `frontend/build/ui-redesign/`。浏览器预览没有 Android Bridge，相关按钮显示禁用是预期状态；本轮尚未安装真机，也未发布云端。
