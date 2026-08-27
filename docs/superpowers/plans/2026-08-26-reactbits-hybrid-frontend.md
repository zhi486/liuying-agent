# 流萤 ReactBits 混合前端实施计划

> **给执行代理：** 默认在当前任务内按依赖顺序逐项实施；只有用户明确要求并行代理时才拆分任务。用复选框（`- [ ]`）追踪进度，并在每个提交前验证暂存范围。

**目标：** 将跑步、历史、设置三屏升级为 APK 内离线运行的 React 19 + ReactBits 展示层，同时让 Kotlin 原生核心成为唯一业务真相，并在 WebView 失效时保留可操作的原生安全页。

**架构：** React 通过 `HashRouter`、Zustand 和语义化动效组件渲染完整 UI；Kotlin `AppCoordinator` 持有传感器、跑步会话、规则、TTS、LLM、Room、DataStore、权限和前台服务。双方只通过受限的 Web Message Bridge v1 交换完整快照、命令结果和一次性事件。迁移期由 `WEB_UI_ENABLED` 保留 Compose 回退，验收后删除普通 Compose 页面，但永久保留 Compose 宿主与 `NativeSafetyScreen`。

**技术栈：** Kotlin 2.0.20、Coroutines 1.9、Koin 4.0.2、Room 2.6.1、AndroidX WebKit 1.17.0、React 19、TypeScript strict、Vite、React Router、Zustand、Zod、CSS Modules、Vitest、Testing Library、Playwright、pnpm 11.19.0、Node 22.22.2。

**规格：** [`docs/superpowers/specs/2026-08-26-reactbits-hybrid-frontend-design.md`](../specs/2026-08-26-reactbits-hybrid-frontend-design.md)

## 全局约束

- 设计判断：这是面向跑者的移动端产品重构，不是营销落地页；采用“萤火虫般克制、可感知但不喧闹”的产品语言，固定 `DESIGN_VARIANCE = 5`、`MOTION_INTENSITY = 6`、`VISUAL_DENSITY = 6`。动效只服务层级、反馈与状态过渡，不使用展示型滚动劫持或无意义循环。
- 当前 `master` 工作树已有用户未提交改动，尤其包含 `app/build.gradle.kts`、`MainActivity.kt`、`RunningViewModel.kt`、主题文件和未跟踪 UI 文件。不得 reset、checkout 或覆盖；每次提交前执行 `git diff --cached --name-only`，只提交当前任务列出的路径。
- 执行前门禁处理完既有改动后，记录精确的 `git rev-parse HEAD` 为 `IMPLEMENTATION_BASE`。最终 whitespace 检查使用 `git diff --check <IMPLEMENTATION_BASE>..HEAD` 和每次提交前的 `git diff --cached --check`，不得用既有用户工作树差异污染本次验收结论。
- 执行前必须判断是否能安全使用独立 worktree。若使用新 worktree，先确认现有未提交重构已被用户审阅并形成独立基线提交；否则在当前工作区逐文件实施。
- 每个实现任务先写失败测试，再写最小实现。每阶段结束做一次范围明确的代码审查；宣称完成前重新运行并记录最终验证命令，不用计划中的预期结果代替真实输出。
- Kotlin 是连接、会话、指标、规则、TTS、LLM、历史、设置、权限和安全状态的唯一写入者。React 不得自行计时、外推指标或乐观修改业务状态。
- Web 内容固定加载自 `https://appassets.androidplatform.net/assets/web/index.html#/run`。禁止 `addJavascriptInterface`、`evaluateJavascript`、远程字体、CDN 和 Web 端业务 API。
- Bridge 单条原始 UTF-8 消息上限为 262,144 字节；协议只接受版本 `1`、精确 appassets origin、主 frame 和握手后业务命令。
- 快照中的 API key 只能表现为 `fishApiKeyConfigured`、`llmApiKeyConfigured`；不得把完整 key 写入 React store、日志、fixture、截图或构建产物。
- 运行时只允许一个传感器 pipeline collector、一个活动会话和一个持续 Canvas/WebGL 层。WebView、Activity 或普通 ViewModel 销毁不得停止正在运行的原生链路。
- 前端业务页面不得直接 import `src/vendor/reactbits`；只能 import `src/motion` 的语义封装。
- 前端命令必须先 `Push-Location .\frontend`，再调用 `corepack pnpm ...`，最后 `Pop-Location`；不能从仓库根目录向 pnpm 传 `--dir`，因为 Corepack 会在切换目录前解析错误的 `packageManager`。Gradle 内调用 pnpm 时使用 frontend workingDir 与 `corepack.cmd pnpm`，不得依赖 PATH 中的裸 `pnpm`。
- 性能硬线：首路由 gzip JavaScript ≤ 450 KiB；Web 总静态资源 ≤ 2.5 MiB；后台停止装饰 RAF；跑步中不出现 50 ms 及以上 Web 主线程长任务。
- 规格中的“教练性格”只允许选择 gentle/balanced/direct 三种措辞后缀；不得改动既有流萤人格主 prompt、训练判断、规则阈值或安全指令。这是对“可编辑教练性格”与“不改写教练提示逻辑”的收敛解释。

## 执行前门禁

- [ ] 检查隔离方式并重新执行 `git status --short --branch` 与当前原生基线测试；记录 `IMPLEMENTATION_BASE` 后，只用 `git diff --check "$implementationBase..HEAD" -- .` 和每次提交前的 `git diff --cached --check` 评价本计划产生的差异，不让既有用户工作树差异成为门禁误报。
- [ ] 对当前未提交的 Compose/AI/Gradle 重构逐文件审阅。若它们是后续实现依赖，先取得用户同意并形成一个只含既有重构的独立基线提交；若用户不希望提交，则全程使用交互式暂存并把重叠文件留到单独审阅，不得把它们混进功能提交。
- [ ] 确认 Node `22.22.2`、Corepack、JDK、Android SDK、一个 Pixel 6 等效模拟器和 `adb` 可用。依赖安装或 Playwright 浏览器下载需要网络时，按权限流程申请，不静默改用未锁定版本。
- [ ] 将执行日志中的测试数量、模拟器 ID 和 bundle 预算作为验收证据；计划中的预期结果不替代真实输出。

## 完成定义

- [ ] `#/run`、`#/history`、`#/settings` 三屏都由 React 渲染并可在 APK 离线使用。
- [ ] Kotlin 与 TypeScript 对同一组 Bridge v1 fixtures 得出相同接受/拒绝结论。
- [ ] mock 传感器可完成连接、开始、暂停、继续、停止、历史写入和设置修改。
- [ ] WebView reload、Activity recreate、握手超时和渲染进程消失都不会重建或丢失活动会话。
- [ ] 活动跑步期间 Web 失效会进入原生安全页，可查看心率、暂停、继续和确认结束。
- [ ] 晨萤、夜萤、Reduced Motion 和四级 MotionBudget 均有自动化验证。
- [ ] 真实 ReactBits 源码有 commit、文件 SHA、许可和本地修改记录，且全部通过项目语义组件使用。
- [ ] 全量单测、前端 E2E、Android instrumentation、APK asset 扫描及性能检查通过。

---

## 阶段一：工程骨架与双端合同

### Task 1：固定前端工具链并建立可测试的 React 空壳

**Files:**

- Create: `frontend/.node-version`
- Create: `frontend/.npmrc`
- Create: `frontend/package.json`
- Create: `frontend/pnpm-lock.yaml`
- Create: `frontend/index.html`
- Create: `frontend/tsconfig.json`
- Create: `frontend/tsconfig.app.json`
- Create: `frontend/tsconfig.node.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/vitest.config.ts`
- Create: `frontend/eslint.config.mjs`
- Create: `frontend/.prettierrc.json`
- Create: `frontend/src/vite-env.d.ts`
- Create: `frontend/src/main.tsx`
- Create: `frontend/src/app/App.tsx`
- Create: `frontend/src/app/App.test.tsx`
- Create: `frontend/src/test/setup.ts`
- Create: `frontend/scripts/verify-toolchain.mjs`
- Modify: `.gitignore`
- Modify: `.gitattributes`

**Interfaces:**

- 产出脚本 `verify:toolchain`、`lint`、`format:check`、`typecheck`、`test:unit`、`build`、`verify:bundle`、`test:e2e`、`check`。
- 产出 `base: "./"` 的静态 Vite 入口；此时尚不依赖 Bridge 或原生层。
- 精确使用 Node `22.22.2` 和 pnpm `11.19.0`。

- [ ] **Step 1: 记录工作树边界，不改变现有文件内容**

运行：

```powershell
git status --short --branch
git diff -- app/build.gradle.kts app/src/main/java/com/healthyagent/running/MainActivity.kt
```

预期：能看到现有用户改动；本任务后续不得把这些既有差异加入暂存区。

- [ ] **Step 2: 写空壳失败测试**

创建 `frontend/src/app/App.test.tsx`：

```tsx
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
```

运行：

```powershell
Push-Location .\frontend
corepack pnpm test:unit
Pop-Location
```

预期：失败，因为前端 package 和 `App` 尚不存在。

- [ ] **Step 3: 创建固定版本的 package 与配置**

使用以下精确的 `frontend/package.json` 结构；首次安装生成并提交解析后的 lockfile：

```json
{
  "name": "liuying-frontend",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "packageManager": "pnpm@11.19.0",
  "engines": { "node": "22.22.2", "pnpm": "11.19.0" },
  "scripts": {
    "dev": "vite",
    "preview": "vite preview --host 127.0.0.1 --port 4173 --strictPort",
    "verify:toolchain": "node scripts/verify-toolchain.mjs",
    "lint": "eslint . --max-warnings 0",
    "format:check": "prettier --check .",
    "typecheck": "tsc -b --pretty false",
    "test:unit": "vitest run",
    "build": "vite build",
    "verify:bundle": "node scripts/verify-bundle.mjs",
    "test:e2e": "pnpm build && playwright test",
    "check": "pnpm verify:toolchain && pnpm lint && pnpm format:check && pnpm typecheck && pnpm test:unit && pnpm build"
  },
  "dependencies": {
    "react": "19.1.1",
    "react-dom": "19.1.1",
    "react-router-dom": "7.8.2",
    "zod": "4.1.5",
    "zustand": "5.0.8"
  },
  "devDependencies": {
    "@eslint/js": "9.34.0",
    "@playwright/test": "1.55.0",
    "@testing-library/jest-dom": "6.8.0",
    "@testing-library/react": "16.3.0",
    "@types/node": "22.18.0",
    "@types/react": "19.1.12",
    "@types/react-dom": "19.1.9",
    "@vitejs/plugin-react": "5.0.2",
    "eslint": "9.34.0",
    "eslint-plugin-react-hooks": "5.2.0",
    "eslint-plugin-react-refresh": "0.4.20",
    "globals": "16.3.0",
    "jsdom": "26.1.0",
    "prettier": "3.6.2",
    "typescript": "5.9.2",
    "typescript-eslint": "8.41.0",
    "vite": "7.1.3",
    "vitest": "3.2.4"
  }
}
```

将 `frontend/.node-version` 设为 `22.22.2`，`frontend/.npmrc` 设为 `engine-strict=true`，TypeScript 启用 strict，Vitest 环境设为 `jsdom`，Vite 配置为：

```ts
export default defineConfig({
  base: './',
  plugins: [react()],
  server: { hmr: false },
  build: { sourcemap: false, manifest: true },
});
```

`frontend/index.html` 只加入一个 CSP meta，并让它紧跟 `<meta charset="UTF-8">`，位于 viewport、preload、link、style、script 和其他可加载资源之前。内容精确为：`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; worker-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'`。`script-src` 不含 `unsafe-inline`/`unsafe-eval`，HTML 不含 inline script；`style-src 'unsafe-inline'` 是唯一例外。后续 ReactBits 不得放宽 `connect-src`、`worker-src` 或 frame 限制。由于开发态也使用同一 CSP，Vite 明确 `server.hmr = false`；README 注明本地视觉预览需手动刷新。

除非 `process.versions.node === '22.22.2'` 且 `npm_config_user_agent` 包含 `pnpm/11.19.0`，否则 `verify-toolchain.mjs` 必须以非零状态退出。

- [ ] **Step 4: 实现最小本地空壳并补忽略规则**

在 `App.tsx` 中仅实现已测试的标题和状态文案。加入以下仓库规则，不重排相邻内容：

```gitignore
.superpowers/
frontend/node_modules/
frontend/dist/
frontend/coverage/
frontend/playwright-report/
frontend/test-results/
```

追加到 `.gitattributes`：

```gitattributes
*.ts text eol=lf
*.tsx text eol=lf
*.js text eol=lf
*.mjs text eol=lf
*.css text eol=lf
*.json text eol=lf
```

- [ ] **Step 5: 安装并验证工具链**

运行：

```powershell
Push-Location .\frontend
corepack pnpm install
corepack pnpm exec node scripts/verify-toolchain.mjs
corepack pnpm lint
corepack pnpm format:check
corepack pnpm typecheck
corepack pnpm test:unit
corepack pnpm build
Pop-Location
```

预期：全部命令退出码为 0；Vitest 报告 1 个测试通过；`frontend/dist/index.html` 存在且只包含相对资源 URL。

- [ ] **Step 6: 提交工具链骨架**

```powershell
git add .gitignore .gitattributes frontend
git diff --cached --name-only
git commit -m "build: 建立 React 前端工具链"
```

预期暂存范围：仅 `.gitignore`、`.gitattributes` 和 `frontend/**`。

### Task 2：把前端构建接入 Android 生成 assets

**Files:**

- Create: `frontend/scripts/verify-bundle.mjs`
- Create: `frontend/scripts/verify-bundle.test.mjs`
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/java/com/healthyagent/running/build/WebAssetPackagingTest.kt`

**Interfaces:**

- 通过 `syncWebAssets` 产出 `app/build/generated/webAssets/web/index.html`。
- `preBuild` consumes `syncWebAssets`; no committed `dist` or copied asset.
- `WEB_UI_ENABLED`: debug default `true`, release default `false`, overridable by `-PwebUiEnabled=true|false`.
- bundle 校验器读取 `frontend/dist`；初始 gzip JS 超过 460,800 字节、总资源超过 2,621,440 字节，或出现远程 URL、source map、疑似密钥字面量时必须失败。

- [ ] **Step 1: 写 bundle verifier 的失败测试**

使用 `node:test` 创建 `verify-bundle.test.mjs`，fixture 放在临时目录；必须覆盖：

```js
test('拒绝远程资源', () => {
  const result = inspectBundle({
    'index.html': '<script src="https://cdn.example.com/app.js"></script>',
  });
  assert.deepEqual(result.errors, ['检测到远程资源：https://cdn.example.com/app.js']);
});

test('拒绝疑似密钥', () => {
  const result = inspectBundle({ 'assets/app.js': 'sk-ant-api03-secret-value' });
  assert.match(result.errors[0], /疑似密钥/);
});

test('接受预算内的相对资源', () => {
  const result = inspectBundle({
    'index.html': '<script type="module" src="./assets/app.js"></script>',
    'assets/app.js': 'console.log("流萤")',
  });
  assert.deepEqual(result.errors, []);
});

test('允许 JavaScript 中不会发起请求的文档 URL', () => {
  const result = inspectBundle({
    'index.html': validIndexHtml('./assets/app.js'),
    'assets/app.js': 'const help = "https://react.dev/errors/418"; items.map(renderItem);',
  });
  assert.deepEqual(result.errors, []);
});
```

运行：

```powershell
node --test frontend/scripts/verify-bundle.test.mjs
```

预期：失败，因为 `inspectBundle` 尚未实现。

- [ ] **Step 2: 实现 deterministic bundle 检查**

导出 `inspectBundle(files: Record<string, string | Buffer>)`，CLI 按排序后的路径遍历 `dist`。拒绝路径以 `.map` 结尾的文件，并在文本中扫描 `sourceMappingURL=`、`sk-ant-`、`Bearer `、`fish_audio_api_key`、`llm_api_key`；绝不裸匹配 `.map`，正常的 `items.map(...)` 必须通过。只报告文件和命中规则，绝不回显疑似密钥文本。远程资源检查只解析 HTML 的实际加载属性、CSS `url()/@import` 和 Vite manifest 资源字段；JavaScript 字符串中的 React/W3C/GSAP 文档 URL 属于 inert data，不得误报。Playwright 的 request audit 另行证明运行时没有非 self 请求。

解析最终 `index.html` 还要断言：只有一个 CSP meta；紧跟 charset 且早于所有加载资源；内容与 Task 1 完整字符串逐项相等；`script-src` 不含 `unsafe-inline`/`unsafe-eval`；不存在 inline script。体积计算从 Vite manifest 的 `isEntry` 节点递归遍历全部静态 `imports` 后合计 gzip，动态 `imports` 不计入首路由；Web 总资源预算仍统计 dist 全部文件。后续“初始 chunk 不含 GSAP”也检查整张静态可达图，而不是单一 entry 文件。

运行：

```powershell
node --test frontend/scripts/verify-bundle.test.mjs
Push-Location .\frontend
corepack pnpm build
corepack pnpm verify:bundle
Pop-Location
```

预期：远程引用、疑似密钥、CSP 顺序/唯一性、静态 import graph 与 inert URL 测试全部通过，真实空壳同时通过两项体积预算。

- [ ] **Step 3: 增加 Gradle task graph 与 feature flag**

把以下结构合入 `app/build.gradle.kts`，同时保留用户现有的注释修改：

```kotlin
val webUiOverride = providers.gradleProperty("webUiEnabled").orNull?.let { raw ->
    raw.toBooleanStrictOrNull()
        ?: throw GradleException("webUiEnabled 只能是 true 或 false，实际为：$raw")
}
val frontendDir = rootProject.layout.projectDirectory.dir("frontend")
val generatedWebAssets = layout.buildDirectory.dir("generated/webAssets")
val corepackExecutable = if (System.getProperty("os.name").startsWith("Windows")) {
    "corepack.cmd"
} else {
    "corepack"
}

android {
    buildTypes {
        debug {
            buildConfigField("boolean", "WEB_UI_ENABLED", (webUiOverride ?: true).toString())
        }
        release {
            buildConfigField("boolean", "WEB_UI_ENABLED", (webUiOverride ?: false).toString())
        }
    }
    sourceSets["main"].assets.srcDir(generatedWebAssets)
}

val installFrontendDependencies by tasks.registering(Exec::class) {
    workingDir(frontendDir.asFile)
    commandLine(corepackExecutable, "pnpm", "install", "--frozen-lockfile")
    inputs.files(frontendDir.file("package.json"), frontendDir.file("pnpm-lock.yaml"))
    outputs.dir(frontendDir.dir("node_modules"))
}

val checkFrontend by tasks.registering(Exec::class) {
    dependsOn(installFrontendDependencies)
    workingDir(frontendDir.asFile)
    commandLine(corepackExecutable, "pnpm", "run", "check")
    inputs.dir(frontendDir.dir("src"))
}

val verifyFrontendBundle by tasks.registering(Exec::class) {
    dependsOn(checkFrontend)
    workingDir(frontendDir.asFile)
    commandLine(corepackExecutable, "pnpm", "run", "verify:bundle")
}

val syncWebAssets by tasks.registering(Sync::class) {
    dependsOn(verifyFrontendBundle)
    from(frontendDir.dir("dist"))
    into(generatedWebAssets.map { it.dir("web") })
}

tasks.named("preBuild").configure { dependsOn(syncWebAssets) }
```

不要添加 Node Gradle 插件，也不要编辑 `settings.gradle.kts`。

运行 `\.\gradlew.bat :app:assembleDebug -PwebUiEnabled=typo`，预期以包含上述中文错误的非零状态失败；随后分别以 `true`、`false` assemble 成功。属性存在但非法时绝不能静默回退默认值。

- [ ] **Step 4: 写 APK 资源存在性测试**

创建 JVM 测试，通过系统属性接收 `web.assets.dir`，断言 `web/index.html` 及其引用的入口 chunk 均存在。配置 Gradle `testDebugUnitTest` 传入 `generatedWebAssets.get().asFile.absolutePath`，并依赖 `syncWebAssets`。

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.build.WebAssetPackagingTest"
.\gradlew.bat :app:assembleDebug
jar.exe tf .\app\build\outputs\apk\debug\app-debug.apk | Select-String 'assets/web/index.html'
```

预期：测试通过、assemble 成功，APK 列表包含 `assets/web/index.html` 和带 hash 的资源。

- [ ] **Step 5: 提交构建集成**

```powershell
git add -p app/build.gradle.kts
git add frontend/scripts/verify-bundle.mjs frontend/scripts/verify-bundle.test.mjs app/src/test/java/com/healthyagent/running/build/WebAssetPackagingTest.kt
git diff --cached --name-only
git commit -m "build: 将 React bundle 打包进 APK"
```

### Task 3：定义 Bridge v1 的 TypeScript 合同与共享 fixtures

**Files:**

- Create: `contracts/v1/README.md`
- Create: `contracts/v1/fixtures/valid/hello.json`
- Create: `contracts/v1/fixtures/valid/ready.json`
- Create: `contracts/v1/fixtures/valid/snapshot.json`
- Create: `contracts/v1/fixtures/valid/command.run-start.json`
- Create: `contracts/v1/fixtures/valid/command.history-query.json`
- Create: `contracts/v1/fixtures/valid/command.settings-update.json`
- Create: `contracts/v1/fixtures/valid/result.accepted.json`
- Create: `contracts/v1/fixtures/valid/result.error.json`
- Create: `contracts/v1/fixtures/valid/event.permission-result.json`
- Create: `contracts/v1/fixtures/valid/event.notice.json`
- Create: `contracts/v1/fixtures/invalid/missing-contract-version.json`
- Create: `contracts/v1/fixtures/invalid/unknown-envelope-type.json`
- Create: `contracts/v1/fixtures/invalid/unsupported-version.json`
- Create: `contracts/v1/fixtures/invalid/invalid-command-payload.json`
- Create: `contracts/v1/fixtures/invalid/noncanonical-uuid.json`
- Create: `contracts/v1/fixtures/invalid/invalid-uuid-variant.json`
- Create: `contracts/v1/fixtures/invalid/unknown-nested-field.json`
- Create: `frontend/src/bridge/contract.ts`
- Create: `frontend/src/bridge/schemas.ts`
- Create: `frontend/src/bridge/schemas.test.ts`
- Modify: `frontend/vitest.config.ts`

**Interfaces:**

- 产出 `BridgeEnvelope`、`AppSnapshot`、`CommandName`、`BridgeErrorCode`、`bridgeEnvelopeSchema`、`nativeToWebEnvelopeSchema`、`webToNativeEnvelopeSchema` 与 `parseInboundEnvelope(raw)`。
- 通过 Vitest fixture glob 使用仓库级 `contracts/v1/fixtures` 下的 JSON 文件。
- JSON key 使用 camelCase；wire 枚举使用小写值，命令名严格使用下列 dotted name。

- [ ] **Step 1: 写 fixture 驱动的失败测试**

```ts
const validFixtures = import.meta.glob('../../../contracts/v1/fixtures/valid/*.json', {
  eager: true,
  import: 'default',
});
const invalidFixtures = import.meta.glob('../../../contracts/v1/fixtures/invalid/*.json', {
  eager: true,
  import: 'default',
});

it.each(Object.entries(validFixtures))('接受合法样本 %s', (_, fixture) => {
  expect(() => bridgeEnvelopeSchema.parse(fixture)).not.toThrow();
});

it.each(Object.entries(invalidFixtures))('拒绝非法样本 %s', (_, fixture) => {
  expect(() => bridgeEnvelopeSchema.parse(fixture)).toThrow();
});
```

进入 `frontend` 后运行 `corepack pnpm test:unit -- schemas.test.ts`，再返回仓库根目录。

预期：失败，因为 schema 和 fixture 尚不存在。

- [ ] **Step 2: 写精确的快照和命令类型**

实现以下公开 TypeScript 类型及一一对应的 Zod schema：

```ts
export type CommandName =
  | 'device.connect'
  | 'device.disconnect'
  | 'run.start'
  | 'run.pause'
  | 'run.resume'
  | 'run.stop'
  | 'history.query'
  | 'settings.update'
  | 'permission.request';

export type BridgeErrorCode =
  | 'BRIDGE_VERSION_UNSUPPORTED'
  | 'INVALID_MESSAGE'
  | 'COMMAND_NOT_ALLOWED'
  | 'PERMISSION_REQUIRED'
  | 'SESSION_CONFLICT'
  | 'RESOURCE_UNAVAILABLE'
  | 'WEB_RUNTIME_FAILED'
  | 'INTERNAL_ERROR';

export interface AppSnapshot {
  revision: number;
  generatedAt: string;
  connection: {
    state: 'disconnected' | 'connecting' | 'connected' | 'error';
    deviceName: string | null;
    batteryPercent: number | null;
  };
  session: {
    id: string | null;
    state: 'idle' | 'preparing' | 'running' | 'paused' | 'finished' | 'error';
    startedAt: string | null;
    targetPaceSecPerKm: number | null;
    targetDistanceMeters: number | null;
  };
  metrics: {
    heartRate: number | null;
    paceSecPerKm: number | null;
    cadence: number | null;
    strideLengthCm: number | null;
    distanceMeters: number;
    elapsedSeconds: number;
    hrZone: 'z1' | 'z2' | 'z3' | 'z4' | 'z5' | null;
    isDataFresh: boolean | null;
  };
  latestCoachMessage: {
    id: string;
    text: string;
    priority: 'urgent' | 'split' | 'coach' | 'app';
    timestampMillis: number;
  } | null;
  settings: {
    targetPaceSecPerKm: number | null;
    targetDistanceKm: number | null;
    maxHeartRate: number;
    ttsEnabled: boolean;
    ttsMinIntervalSec: number;
    llmEnabled: boolean;
    mockScenario: 'EASY_RUN' | 'SPEED_TEST' | 'PROGRESSIVE' | 'HEART_RATE_SPIKE' | 'CADENCE_DROP';
    coachTone: 'gentle' | 'balanced' | 'direct';
    fishReferenceId: string;
    fishApiKeyConfigured: boolean;
    llmApiKeyConfigured: boolean;
    themePreference: 'system' | 'morning' | 'night';
    motionPreference: 'system' | 'expressive' | 'balanced' | 'reduced' | 'off';
  };
  permissions: {
    bluetooth: 'granted' | 'denied' | 'notRequested';
    bodySensors: 'granted' | 'denied' | 'notRequested';
    runningSensors: 'granted' | 'denied' | 'notRequested';
  };
  theme: 'morning' | 'night';
  motionPolicy: 'expressive' | 'balanced' | 'reduced' | 'off';
  safetyState: { level: 'normal' | 'warning' | 'emergency'; message: string | null };
}

export interface SettingsUpdateBody {
  targetPaceSecPerKm?: number | null;
  targetDistanceKm?: number | null;
  maxHeartRate?: number;
  ttsEnabled?: boolean;
  ttsMinIntervalSec?: number;
  llmEnabled?: boolean;
  mockScenario?: AppSnapshot['settings']['mockScenario'];
  coachTone?: AppSnapshot['settings']['coachTone'];
  fishApiKey?: string;
  fishReferenceId?: string;
  llmApiKey?: string;
  themePreference?: AppSnapshot['settings']['themePreference'];
  motionPreference?: AppSnapshot['settings']['motionPreference'];
}

export interface HelloPayload {
  buildId: string;
  supportedContractVersions: [1];
}

export interface ReadyPayload {
  helloId: string;
  buildId: string;
  capabilities: ['snapshot.v1', 'history.pagination', 'permission.request'];
  snapshot: AppSnapshot;
}

export interface CommandBodyMap {
  'device.connect': Record<string, never>;
  'device.disconnect': Record<string, never>;
  'run.start': Record<string, never>;
  'run.pause': Record<string, never>;
  'run.resume': Record<string, never>;
  'run.stop': Record<string, never>;
  'history.query': { page: number; pageSize: number };
  'settings.update': SettingsUpdateBody;
  'permission.request': { kind: 'runningSensors' };
}

export type CommandPayload<T extends CommandName = CommandName> = T extends CommandName
  ? { helloId: string; name: T; body: CommandBodyMap[T] }
  : never;

export type AppEventPayload =
  | {
      name: 'permission.result';
      requestId: string;
      kind: 'runningSensors';
      granted: boolean;
    }
  | { name: 'notice'; code: string; message: string };
```

对 `settings.update`，可选 key 缺失表示不变；`targetPaceSecPerKm` 和 `targetDistanceKm` 还接受显式 `null` 以清空。API key 只写。拒绝未知 key，并要求至少一个 key。

方向联合必须固定：Web → Native 只包含 `hello | command`，Native → Web 只包含 `ready | snapshot | result | event`。补测试证明 `nativeToWebEnvelopeSchema` 拒绝合法但方向错误的 hello/command，`webToNativeEnvelopeSchema` 拒绝 ready/snapshot/result/event。

- [ ] **Step 3: 固定 canonical fixtures**

使用固定 UUID 和时间戳。`snapshot.json` 必须包含上述全部字段且不含密钥。标准命令示例：

```json
{
  "contractVersion": 1,
  "type": "command",
  "id": "00000000-0000-4000-8000-000000000004",
  "sentAt": "2026-08-26T12:00:03Z",
  "payload": {
    "helloId": "00000000-0000-4000-8000-000000000001",
    "name": "run.start",
    "body": {}
  }
}
```

`hello.json` payload 固定包含 `buildId` 与 `supportedContractVersions: [1]`。`ready.json` payload 的 `helloId` 必须等于该 hello envelope ID，`buildId` 原样回显当前 Web build，`capabilities` 精确等于 `["snapshot.v1", "history.pagination", "permission.request"]`，`snapshot` 等于 `snapshot.json` 的完整 payload。result envelope 的 `id` 必须与对应 command envelope 的 `id` 相同，payload 为 `{ "status": "accepted", "data": null }` 或 `{ "status": "rejected", "error": { "code": "COMMAND_NOT_ALLOWED", "message": "当前状态不能执行该操作" } }`。`event.permission-result.json` 与 `event.notice.json` 使用独立 envelope ID；前者包含 `name: "permission.result"`、原始 `requestId`、`kind` 和 `granted`，后者包含 `name: "notice"`、稳定 `code` 与脱敏中文 `message`。

全部 command fixture 的 payload 都携带上述 hello envelope ID；Task 20 的 Mock Bridge 每次新 hello/restart 都更新 accepted `helloId`，并拒绝旧 helloId 命令。

- [ ] **Step 4: 实现严格 Zod 边界**

信封及每个嵌套 object schema 都必须使用 `.strict()`；ID 合同明确收敛为 UUID v4 并使用 `z.uuidv4()`，版本和时间分别使用 `z.literal(1)`、`z.iso.datetime({ offset: true })`，并对 `type` 和命令 `name` 使用可辨识联合。`unknown-nested-field.json` 必须证明 payload/body 深层未知字段也会失败。Web 侧的 `parseInboundEnvelope(raw)` 只能以 `nativeToWebEnvelopeSchema` 解码，并且必须：

1. reject `new TextEncoder().encode(raw).byteLength > 262_144`;
2. catch JSON parse errors;
3. return `Result`-style `{ ok: true, value } | { ok: false, code: 'INVALID_MESSAGE' }` without logging the body.

运行：

```powershell
Push-Location .\frontend
corepack pnpm test:unit -- schemas.test.ts
corepack pnpm typecheck
Pop-Location
```

预期：所有合法 fixture 通过，所有非法 fixture 被拒绝，生成的 262,145 字节消息也被拒绝。

- [ ] **Step 5: 提交合同与 TS 边界**

```powershell
git add contracts/v1 frontend/src/bridge frontend/vitest.config.ts
git diff --cached --name-only
git commit -m "feat: 定义 Bridge v1 前端合同"
```

### Task 4：让 Kotlin Serialization 验证同一组合同样本

**Files:**

- Create: `app/src/main/java/com/healthyagent/running/bridge/BridgeEnvelope.kt`
- Create: `app/src/main/java/com/healthyagent/running/bridge/BridgeCodec.kt`
- Create: `app/src/main/java/com/healthyagent/running/bridge/BridgeError.kt`
- Create: `app/src/test/java/com/healthyagent/running/bridge/BridgeCodecTest.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**

- 产出 `BridgeCodec.decode(raw: String): BridgeDecodeResult` 和 `BridgeCodec.encode(envelope): String`。
- `BridgeEnvelope.payload` 使用 `JsonObject`；命令专属解码留在 Gateway。
- 通过 `sourceSets["test"].resources.srcDir(...)` 使用仓库 `contracts/` 测试资源。

- [ ] **Step 1: 写 Kotlin fixture 失败测试**

```kotlin
@RunWith(Parameterized::class)
class BridgeCodecValidFixtureTest(private val resource: String) {
    @Test
    fun `合法合同样本可解码`() {
        val raw = checkNotNull(javaClass.getResource(resource)).readText()
        assertTrue(BridgeCodec.decode(raw) is BridgeDecodeResult.Accepted)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun fixtures() = listOf(
            "/v1/fixtures/valid/hello.json",
            "/v1/fixtures/valid/ready.json",
            "/v1/fixtures/valid/snapshot.json",
            "/v1/fixtures/valid/command.run-start.json",
            "/v1/fixtures/valid/command.history-query.json",
            "/v1/fixtures/valid/command.settings-update.json",
            "/v1/fixtures/valid/result.accepted.json",
            "/v1/fixtures/valid/result.error.json",
            "/v1/fixtures/valid/event.permission-result.json",
            "/v1/fixtures/valid/event.notice.json",
        ).map { arrayOf(it) }
    }
}
```

补充对称的非法样本测试和精确大小边界测试。在实现 codec 前运行测试类，预期编译失败。

- [ ] **Step 2: 实现信封与错误类型**

```kotlin
@Serializable
data class BridgeEnvelope(
    val contractVersion: Int,
    val type: BridgeMessageType,
    val id: String,
    val sentAt: String,
    val payload: JsonObject,
)

@Serializable
enum class BridgeMessageType {
    @SerialName("hello") HELLO,
    @SerialName("ready") READY,
    @SerialName("snapshot") SNAPSHOT,
    @SerialName("command") COMMAND,
    @SerialName("result") RESULT,
    @SerialName("event") EVENT,
}

sealed interface BridgeDecodeResult {
    data class Accepted(val envelope: BridgeEnvelope) : BridgeDecodeResult
    data class Rejected(val error: BridgeErrorCode) : BridgeDecodeResult
}
```

`BridgeCodec` 使用唯一 wire encoder/decoder：

```kotlin
Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    encodeDefaults = true
}
```

它在 parse 前检查 UTF-8 字节数，要求版本 1，再用与 `z.uuidv4()` 对齐的 canonical UUID v4 规则 `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$` 加 `UUID.fromString(id).toString().equals(id, ignoreCase = true)` 校验 ID，并用 `Instant.parse(sentAt)` 校验时间；短组 UUID、非 v4 和非法 variant 都拒绝。随后通过 type-specific 函数校验 payload，未知枚举和任意深度未知字段必须拒绝。`AppSnapshotTest`、ready/result fixture 和 Gateway 必须使用同一 `BridgeCodec`，确保 `null` 与默认字段不会在 wire 上丢失。

- [ ] **Step 3: 暴露双方一致的标准错误码**

```kotlin
@Serializable
enum class BridgeErrorCode {
    BRIDGE_VERSION_UNSUPPORTED,
    INVALID_MESSAGE,
    COMMAND_NOT_ALLOWED,
    PERMISSION_REQUIRED,
    SESSION_CONFLICT,
    RESOURCE_UNAVAILABLE,
    WEB_RUNTIME_FAILED,
    INTERNAL_ERROR,
}
```

线上的错误响应不得携带原始消息体或异常堆栈。内部日志只能记录消息 ID 和错误类别。

- [ ] **Step 4: 接入共享 test resources 并验证**

在 `android.sourceSets` 中仅加入：

```kotlin
sourceSets["test"].resources.srcDir(rootProject.file("contracts"))
```

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.bridge.*"
Push-Location .\frontend
corepack pnpm test:unit -- schemas.test.ts
Pop-Location
```

预期：Kotlin 与 TypeScript 都接受全部 `valid` fixture，并拒绝全部 `invalid` fixture。

- [ ] **Step 5: 提交 Kotlin 合同边界**

```powershell
git add -p app/build.gradle.kts
git add app/src/main/java/com/healthyagent/running/bridge/BridgeEnvelope.kt app/src/main/java/com/healthyagent/running/bridge/BridgeCodec.kt app/src/main/java/com/healthyagent/running/bridge/BridgeError.kt app/src/test/java/com/healthyagent/running/bridge/BridgeCodecTest.kt
git diff --cached --name-only
git commit -m "feat: 在原生侧校验 Bridge v1 合同"
```

---

## 阶段二：把业务所有权移出 Compose ViewModel

### Task 5：建立可测试、可原子更新的设置仓库

**Files:**

- Create: `app/src/main/java/com/healthyagent/running/data/local/SettingsRepository.kt`
- Create: `app/src/main/java/com/healthyagent/running/data/local/SettingsPatch.kt`
- Create: `app/src/main/java/com/healthyagent/running/core/model/CoachTone.kt`
- Modify: `app/src/main/java/com/healthyagent/running/core/model/RunConfig.kt`
- Modify: `app/src/main/java/com/healthyagent/running/data/local/SettingsDataStore.kt`
- Modify: `app/src/main/java/com/healthyagent/running/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/healthyagent/running/di/AppModule.kt`
- Create: `app/src/test/java/com/healthyagent/running/data/local/SettingsPatchTest.kt`
- Create: `app/src/androidTest/java/com/healthyagent/running/data/local/SettingsDataStoreTest.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**

```kotlin
interface SettingsRepository {
    val settings: Flow<SettingsSnapshot>
    suspend fun current(): SettingsSnapshot
    suspend fun update(patch: SettingsPatch)
}

sealed interface NullableSettingUpdate<out T> {
    data object Unchanged : NullableSettingUpdate<Nothing>
    data object Clear : NullableSettingUpdate<Nothing>
    data class Set<T>(val value: T) : NullableSettingUpdate<T>
}

data class SettingsPatch(
    val targetPaceSecPerKm: NullableSettingUpdate<Double> = NullableSettingUpdate.Unchanged,
    val targetDistanceKm: NullableSettingUpdate<Double> = NullableSettingUpdate.Unchanged,
    val maxHeartRate: Int? = null,
    val ttsEnabled: Boolean? = null,
    val ttsMinIntervalSec: Int? = null,
    val llmEnabled: Boolean? = null,
    val mockScenario: String? = null,
    val coachTone: CoachTone? = null,
    val fishApiKey: String? = null,
    val fishReferenceId: String? = null,
    val llmApiKey: String? = null,
    val themePreference: ThemePreference? = null,
    val motionPreference: MotionPreference? = null,
)
```

- `SettingsSnapshot` 仍是原生私有设置模型，可以包含凭据。
- 公开 Bridge 设置在 Task 7 映射，绝不直接复用 `SettingsSnapshot`。
- `current()` 通过 `first()` 只消费一次 DataStore emission；`update()` 只执行一次 `DataStore.edit` transaction。

- [ ] **Step 1: 写校验与一次性读取的失败测试**

`SettingsPatchTest` 必须断言：

```kotlin
@Test fun `最大心率必须处于合理范围`() {
    assertEquals(
        SettingsPatchError.INVALID_MAX_HEART_RATE,
        SettingsPatch(maxHeartRate = 20).validate().single(),
    )
}

@Test fun `空 patch 被拒绝`() {
    assertEquals(listOf(SettingsPatchError.EMPTY_PATCH), SettingsPatch().validate())
}

@Test fun `目标字段可显式清空`() {
    assertTrue(SettingsPatch(targetDistanceKm = NullableSettingUpdate.Clear).validate().isEmpty())
}
```

让 `SettingsDataStore` 的主构造器接收 `DataStore<Preferences>`，保留 `constructor(context: Context)` 供生产使用。Instrumentation 测试通过 `PreferenceDataStoreFactory.create` 和唯一临时文件注入隔离实例，在一秒超时内调用 `current()`，应用多字段 patch，并确认一次结果快照包含全部更新；不能依赖进程级 `preferencesDataStore("settings")` 委托隔离测试。

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.data.local.SettingsPatchTest"
```

预期：失败，因为 repository 和 patch 类型尚不存在。

- [ ] **Step 2: 实现设置模型、范围和 DataStore 映射**

使用以下校验范围：

- 目标配速：`120.0..1200.0` 秒/公里，或清空；
- 目标距离：`0.1..200.0` 公里，或清空；
- 最大心率：`80..240`；
- TTS 间隔：`0..300` 秒；
- API key：最多 512 个字符；
- reference ID：`1..128` 个字符；
- mock 场景：合同中的五个固定值之一。

新增核心枚举 `CoachTone { GENTLE, BALANCED, DIRECT }`，以及设置枚举 `ThemePreference { SYSTEM, MORNING, NIGHT }`、`MotionPreference { SYSTEM, EXPRESSIVE, BALANCED, REDUCED, OFF }`。在 `RunConfig` 增加 `coachTone: CoachTone = CoachTone.BALANCED`。三者默认值依次为 `BALANCED`、`SYSTEM`、`SYSTEM`。

将不会终止的 `loadAll()` collector 替换为：

```kotlin
override val settings: Flow<SettingsSnapshot> = dataStore.data.map(::toSnapshot)

override suspend fun current(): SettingsSnapshot = toSnapshot(dataStore.data.first())

@Deprecated("使用 current()")
suspend fun loadAll(): SettingsSnapshot = current()
```

`update` 在进入 `edit` 前校验，并在同一个 edit block 中应用全部变更。迁移期保留现有单字段 setter 和只读 Flow 作为薄适配器，全部从同一个 `settings` Flow 派生，确保 `SettingsViewModel` 与当前 Koin 图继续编译；主构造器内除注入的 `dataStore` 外不得再访问 `context.dataStore`。`AppModule.kt` 中仍需一次性读取 mock/TTS 配置的地方统一调用 `get<SettingsRepository>().current()`，不再直接调用 `SettingsDataStore.mockScenario.first()` 或其他单字段 DataStore Flow。

同一任务立即在 `AppModule.kt` 注册同一对象的两个视图，不能等到 Task 9：

```kotlin
single { SettingsDataStore(androidContext()) }
single<SettingsRepository> { get<SettingsDataStore>() }
```

- [ ] **Step 3: 保持 Compose 设置页兼容**

将 `SettingsViewModel` 构造器类型从 `SettingsDataStore` 改为 `SettingsRepository`。现有 StateFlow 从聚合 `settings` Flow 派生，现有 setter 创建单字段 `SettingsPatch`；本任务不重做 Compose UI。

- [ ] **Step 4: 验证 JVM 与 DataStore 行为**

仅在缺失时添加 `androidx.test:core-ktx` 和 `androidx.test:runner`，然后运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.data.local.*"
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.healthyagent.running.data.local.SettingsDataStoreTest"
```

预期：patch 测试通过；`current()` 正常结束；一次 patch emission 包含全部修改字段。

- [ ] **Step 5: 提交设置仓库**

```powershell
git add -p app/build.gradle.kts
git add app/src/main/java/com/healthyagent/running/data/local/SettingsRepository.kt app/src/main/java/com/healthyagent/running/data/local/SettingsPatch.kt app/src/main/java/com/healthyagent/running/data/local/SettingsDataStore.kt app/src/main/java/com/healthyagent/running/core/model/CoachTone.kt app/src/main/java/com/healthyagent/running/core/model/RunConfig.kt app/src/main/java/com/healthyagent/running/ui/settings/SettingsViewModel.kt app/src/test/java/com/healthyagent/running/data/local/SettingsPatchTest.kt app/src/androidTest/java/com/healthyagent/running/data/local/SettingsDataStoreTest.kt
git add -p app/src/main/java/com/healthyagent/running/di/AppModule.kt
git diff --cached --name-only
git commit -m "refactor: 统一原生设置仓库"
```

### Task 6：让会话状态与语音消息可恢复

**Files:**

- Modify: `app/src/main/java/com/healthyagent/running/core/model/CoachMessage.kt`
- Modify: `app/src/main/java/com/healthyagent/running/core/session/RunSessionManager.kt`
- Modify: `app/src/main/java/com/healthyagent/running/speech/SpeechBus.kt`
- Modify: `app/src/main/java/com/healthyagent/running/di/AppModule.kt`
- Modify: `app/src/test/java/com/healthyagent/running/core/session/RunSessionManagerTest.kt`
- Modify: `app/src/test/java/com/healthyagent/running/speech/SpeechBusTest.kt`
- Create: `app/src/test/java/com/healthyagent/running/testing/FakeTtsEngine.kt`

**Interfaces:**

```kotlin
data class CoachMessage(
    val id: String,
    val text: String,
    val priority: SpeechPriority,
    val timestampMillis: Long,
)

data class SpeechPolicy(val enabled: Boolean, val minIntervalSec: Int)

class SpeechBus(
    private val ttsEngine: TtsEngine,
    initialPolicy: SpeechPolicy = SpeechPolicy(enabled = true, minIntervalSec = 4),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newMessageId: () -> String = { UUID.randomUUID().toString() },
) {
    val latestMessage: StateFlow<CoachMessage?>
    fun configure(policy: SpeechPolicy)
    fun resetSession()
    suspend fun enqueue(trigger: RuleTrigger)
    fun stop()
    fun shutdown()
}
```

- 仅在 `TtsEngine.speak` 成功后产出稳定消息 ID。
- `pause()` 与 `resume()` 同时更新 `currentState` 和 `currentSession.runState`。

- [ ] **Step 1: 添加失败测试**

加入以下精确断言：

```kotlin
@Test fun `暂停和继续同步当前会话状态`() {
    manager.startSession(config)
    manager.pause()
    assertEquals(RunState.PAUSED, manager.currentState)
    assertEquals(RunState.PAUSED, manager.currentSession?.runState)
    manager.resume()
    assertEquals(RunState.RUNNING, manager.currentSession?.runState)
}

@Test fun `成功播报后发布具有稳定 ID 的消息`() = runTest {
    val fakeTts = FakeTtsEngine()
    val bus = SpeechBus(fakeTts, newMessageId = { "coach-1" }, nowMillis = { 42L })
    bus.enqueue(makeTrigger(text = "稳住节奏"))
    assertEquals(
        CoachMessage("coach-1", "稳住节奏", SpeechPriority.COACH, 42L),
        bus.latestMessage.value,
    )
}

@Test fun `播报失败不发布消息`() = runTest {
    val fakeTts = FakeTtsEngine().apply { shouldFail = true }
    val bus = SpeechBus(fakeTts)
    bus.enqueue(makeTrigger(text = "不会上屏"))
    assertNull(bus.latestMessage.value)
}

@Test fun `新会话清除上一条消息但不关闭引擎`() = runTest {
    val fakeTts = FakeTtsEngine()
    val bus = SpeechBus(fakeTts, newMessageId = { "coach-1" })
    bus.enqueue(makeTrigger(text = "上一场消息"))
    bus.resetSession()
    assertNull(bus.latestMessage.value)
    assertEquals(0, fakeTts.shutdownCount)
}
```

把既有私有嵌套 `FakeTtsEngine` 移到共享 `app/src/test/.../testing/FakeTtsEngine.kt`，保留 `makeTrigger(...)` helper、`shouldFail` 和 `stopCount`，新增只读 `shutdownCount` 与可选的 suspend `speakGate`。`stop()` 释放/取消正在等待的 speak，`shutdown()` 递增计数并把 `isSpeaking` 清为 false。Task 7 的并发/迟到测试复用该 fake，不引入另一套 `result`/`trigger` API。

运行两个测试类，预期失败。

- [ ] **Step 2: 实现最小状态同步和消息发布**

保留现有优先级、打断和去重行为。用可变 `SpeechPolicy` 取代构造器中的固定 `RunConfig`；`configure` 只影响后续 enqueue 决策。成功时更新历史和 `_latestMessage`，失败时两者都不更新。

`resetSession()` 清空 `speakHistory` 和 `latestMessage`，但不关闭 engine；Coordinator 在每次新跑步开始前精确调用一次。

同一任务把 `AppModule.kt` 的 `SpeechBus(ttsEngine = get(), config = get())` 改为 `SpeechBus(ttsEngine = get())`，使 Task 6 自身成为可编译检查点；Task 9 再移除其余固定 `RunConfig` 绑定。

- [ ] **Step 3: 验证现有语音行为没有回归**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.core.session.RunSessionManagerTest" --tests "com.healthyagent.running.speech.SpeechBusTest"
```

预期：既有优先级/去重测试和四个新测试全部通过。

- [ ] **Step 4: 提交可恢复状态**

```powershell
git add app/src/main/java/com/healthyagent/running/core/model/CoachMessage.kt app/src/main/java/com/healthyagent/running/core/session/RunSessionManager.kt app/src/main/java/com/healthyagent/running/speech/SpeechBus.kt app/src/test/java/com/healthyagent/running/core/session/RunSessionManagerTest.kt app/src/test/java/com/healthyagent/running/speech/SpeechBusTest.kt app/src/test/java/com/healthyagent/running/testing/FakeTtsEngine.kt
git add -p app/src/main/java/com/healthyagent/running/di/AppModule.kt
git diff --cached --name-only
git commit -m "refactor: 发布可恢复的跑步与教练状态"
```

### Task 7：创建应用级 AppCoordinator 并接管跑步编排

**Files:**

- Create: `app/src/main/java/com/healthyagent/running/coordinator/AppCommand.kt`
- Create: `app/src/main/java/com/healthyagent/running/coordinator/AppSnapshot.kt`
- Create: `app/src/main/java/com/healthyagent/running/coordinator/AppCoordinator.kt`
- Create: `app/src/main/java/com/healthyagent/running/coordinator/DefaultAppCoordinator.kt`
- Create: `app/src/main/java/com/healthyagent/running/coordinator/RunRuntimeFactory.kt`
- Create: `app/src/main/java/com/healthyagent/running/coordinator/RunForegroundController.kt`
- Create: `app/src/main/java/com/healthyagent/running/coordinator/RuntimePolicySource.kt`
- Create: `app/src/main/java/com/healthyagent/running/coordinator/PermissionStateSource.kt`
- Create: `app/src/main/java/com/healthyagent/running/coordinator/SafetyStateReducer.kt`
- Modify: `app/src/main/java/com/healthyagent/running/ai/AnalysisManager.kt`
- Modify: `app/src/main/java/com/healthyagent/running/ai/EmergencyPhraseCache.kt`
- Modify: `app/src/main/java/com/healthyagent/running/speech/SpeechBus.kt`
- Modify: `app/src/main/java/com/healthyagent/running/di/AppModule.kt`
- Create: `app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorTest.kt`
- Create: `app/src/test/java/com/healthyagent/running/coordinator/AppSnapshotTest.kt`
- Create: `app/src/test/java/com/healthyagent/running/ai/AnalysisManagerTest.kt`
- Create: `app/src/test/java/com/healthyagent/running/ai/EmergencyPhraseCacheTest.kt`
- Modify: `app/src/test/java/com/healthyagent/running/speech/SpeechBusTest.kt`

**Interfaces:**

```kotlin
interface AppCoordinator {
    val snapshot: StateFlow<AppSnapshot>
    val events: SharedFlow<AppEvent>
    val platformEffect: StateFlow<AppEffect?>
    suspend fun execute(command: AppCommand): CommandOutcome
}

sealed interface AppCommand {
    data object ConnectDevice : AppCommand
    data object DisconnectDevice : AppCommand
    data object StartRun : AppCommand
    data object PauseRun : AppCommand
    data object ResumeRun : AppCommand
    data object StopRun : AppCommand
    data class QueryHistory(val page: Int, val pageSize: Int) : AppCommand
    data class UpdateSettings(val patch: SettingsPatch) : AppCommand
    data class RequestPermission(val requestId: String, val kind: PermissionKind) : AppCommand
    data class CompletePermissionRequest(
        val requestId: String,
        val kind: PermissionKind,
        val result: PermissionGrantResult,
    ) : AppCommand
}

sealed interface CommandOutcome {
    data class Accepted(val payload: AppCommandPayload? = null) : CommandOutcome
    data class Rejected(val code: BridgeErrorCode, val message: String) : CommandOutcome
}

@Serializable
sealed interface AppCommandPayload

sealed interface AppEvent {
    data class PermissionResult(
        val requestId: String,
        val kind: PermissionKind,
        val granted: Boolean,
    ) : AppEvent
    data class Notice(val code: String, val message: String) : AppEvent
}

sealed interface AppEffect {
    data class RequestPermission(val requestId: String, val kind: PermissionKind) : AppEffect
}

fun interface RunRuntimeFactory {
    fun create(config: RunConfig): RunRuntime
}

data class RunRuntime(
    val pipeline: RunningDataPipeline,
    val ruleEngine: RuleEngine,
)

interface RunForegroundController {
    fun start()
    fun stop()
}

interface RuntimePolicySource {
    val state: StateFlow<RuntimePolicy>
}

data class RuntimePolicy(
    val isForeground: Boolean,
    val isPowerSaveMode: Boolean,
    val isLowBattery: Boolean,
    val isReducedMotionEnabled: Boolean,
    val thermalSeverity: ThermalSeverity,
    val isSystemDark: Boolean,
)

enum class ThermalSeverity { NORMAL, MODERATE, SEVERE }
enum class PermissionKind { RUNNING_SENSORS }
enum class PermissionStatus { GRANTED, DENIED, NOT_REQUESTED }
data class PermissionGrantResult(
    val bluetoothGranted: Boolean,
    val bodySensorsGranted: Boolean,
) {
    val runningSensorsGranted: Boolean get() = bluetoothGranted && bodySensorsGranted
}
data class PermissionSnapshot(
    val bluetooth: PermissionStatus,
    val bodySensors: PermissionStatus,
) {
    val runningSensors: PermissionStatus get() = when {
        bluetooth == PermissionStatus.GRANTED && bodySensors == PermissionStatus.GRANTED ->
            PermissionStatus.GRANTED
        bluetooth == PermissionStatus.NOT_REQUESTED || bodySensors == PermissionStatus.NOT_REQUESTED ->
            PermissionStatus.NOT_REQUESTED
        else -> PermissionStatus.DENIED
    }
}

interface PermissionStateSource {
    val state: StateFlow<PermissionSnapshot>
    fun recordResult(result: PermissionGrantResult)
}

class DefaultAppCoordinator(
    private val sensorDataSource: SensorDataSource,
    private val runtimeFactory: RunRuntimeFactory,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: RunSessionManager,
    private val repository: RunRepository,
    private val speechBus: SpeechBus,
    private val analysisManager: AnalysisManager,
    private val emergencyPhraseCache: EmergencyPhraseCache,
    private val foregroundController: RunForegroundController,
    private val permissionStateSource: PermissionStateSource,
    private val runtimePolicySource: RuntimePolicySource,
    private val scope: CoroutineScope,
    private val clock: java.time.Clock,
) : AppCoordinator
```

`AppSnapshot` 必须与 TypeScript shape 精确一致，并用 Kotlin Serialization `@SerialName` 固定 wire 枚举。`DefaultAppCoordinator` 属于 application scope，接收注入的 `CoroutineScope`，用独立 `lifecycleMutex` 串行化 connect/disconnect/start/pause/resume/stop，用 `stateMutex` 保护 frame/状态写入，且只持有一个 `pipelineJob`。Task 8 的历史查询不占 lifecycle lock；设置和权限分别使用短生命周期专属锁，保证长分页查询不会阻塞紧急 stop。

- [ ] **Step 1: 写状态机和单 collector 失败测试**

已有接口边界使用 fake；`SpeechBus`、`AnalysisManager`、`EmergencyPhraseCache` 等纯 Kotlin concrete 使用真实对象配合 `FakeTtsEngine`/`FakeLlmClient`，不要为了测试把类改成 open。Coordinator scope 精确传入 `backgroundScope` 或 `CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))`，依赖 application collector 的断言前调用 `advanceUntilIdle()`。必须覆盖：

```kotlin
@Test fun `重复连接只调用一次 sensor 且不提前创建 pipeline`() = runTest {
    permissions.grantRunningSensors()
    coordinator.execute(AppCommand.ConnectDevice)
    coordinator.execute(AppCommand.ConnectDevice)
    assertEquals(1, sensor.connectCalls)
    assertEquals(0, runtimeFactory.createdPipelines)
    assertEquals(0, sensor.samples.subscriptionCount.value)
}

@Test fun `重复开始只创建一个运行时和一个 collector`() = runTest {
    permissions.grantRunningSensors()
    coordinator.execute(AppCommand.ConnectDevice)
    advanceUntilIdle()
    coordinator.execute(AppCommand.StartRun)
    coordinator.execute(AppCommand.StartRun)
    assertEquals(1, runtimeFactory.createdPipelines)
    assertEquals(1, sensor.samples.subscriptionCount.value)
}

@Test fun `开始跑步捕获当时设置并启动前台服务`() = runTest {
    permissions.grantRunningSensors()
    settings.currentValue = settings.currentValue.copy(targetDistanceKm = 8.0)
    coordinator.execute(AppCommand.ConnectDevice)
    advanceUntilIdle()
    assertEquals(CommandOutcome.Accepted(), coordinator.execute(AppCommand.StartRun))
    assertEquals(8_000.0, runtimeFactory.lastConfig?.targetDistanceMeters)
    assertEquals(1, foreground.startCalls)
    assertEquals(RunState.RUNNING, coordinator.snapshot.value.session.state)
}

@Test fun `活动跑步期间新设置不改变本次配置`() = runTest {
    permissions.grantRunningSensors()
    coordinator.execute(AppCommand.ConnectDevice)
    advanceUntilIdle()
    coordinator.execute(AppCommand.StartRun)
    val captured = coordinator.snapshot.value.session.targetPaceSecPerKm
    settings.emit(settings.currentValue.copy(targetPaceSecPerKm = 500.0))
    assertEquals(captured, coordinator.snapshot.value.session.targetPaceSecPerKm)
}

@Test fun `停止只持久化一次并停止前台服务`() = runTest {
    permissions.grantRunningSensors()
    coordinator.execute(AppCommand.ConnectDevice)
    advanceUntilIdle()
    coordinator.execute(AppCommand.StartRun)
    coordinator.execute(AppCommand.StopRun)
    val second = coordinator.execute(AppCommand.StopRun)
    assertEquals(1, repository.savedSessions.size)
    assertTrue(second is CommandOutcome.Rejected)
    assertEquals(1, foreground.stopCalls)
}

@Test fun `历史保存失败仍完成跑步并释放所有运行资源`() = runTest {
    permissions.grantRunningSensors()
    coordinator.execute(AppCommand.ConnectDevice)
    advanceUntilIdle()
    coordinator.execute(AppCommand.StartRun)
    repository.saveFailure = IllegalStateException("db failed")
    val result = coordinator.execute(AppCommand.StopRun)
    assertEquals(CommandOutcome.Accepted(), result)
    assertEquals(RunState.FINISHED, coordinator.snapshot.value.session.state)
    assertEquals(1, foreground.stopCalls)
    assertEquals(1, sensor.disconnectCalls)
    assertEquals(0, sensor.samples.subscriptionCount.value)
}

@Test fun `紧急规则立即进入 emergency`() {
    val next = SafetyStateReducer.onTriggers(
        current = SafetyState.normal(),
        triggers = listOf(urgentTrigger("心率过高")),
    )
    assertEquals(SafetyLevel.EMERGENCY, next.level)
    assertEquals("心率过高", next.message)
}
```

同一测试类再用参数化矩阵固定状态转换：IDLE 可 connect；CONNECTED/IDLE 可 start；RUNNING 可 pause/stop，不能 disconnect；PAUSED 可 resume/stop，不能 disconnect；FINISHED 可 connect 后开始新 session；connection error 可 retry；其他 pause/resume/stop 组合返回 `COMMAND_NOT_ALLOWED`。每条允许路径断言 session ID、前台服务和 pipeline 计数，每条拒绝路径断言无副作用。

再增加权限门禁：`runningSensors != GRANTED` 时 connect/start 返回 `PERMISSION_REQUIRED`，不连接 sensor、不创建 runtime；测试 fixture 的普通 happy path 必须显式 `grantRunningSensors()`，不能靠默认已授权掩盖该分支。

单运行时门禁必须包含真正的并发：用两个 `async { coordinator.execute(StartRun) }` 同时放行，断言只创建一个 runtime/collector、只 start 一次 foreground，另一个得到稳定拒绝或 accepted no-op。再分别让 `runtimeFactory.create` 与 `foregroundController.start` 抛错，断言 active runtime/job/config、session state 和 service 全部回滚，随后一次正常 start 可以成功。

运行 `AppCoordinatorTest`，预期编译失败。

- [ ] **Step 2: 实现不可变 AppSnapshot**

为 `connection`、`session`、`metrics`、`latestCoachMessage`、脱敏 `settings`、`permissions`、`theme`、`motionPolicy`、`safetyState` 创建嵌套可序列化 DTO。初始快照 revision 为 `0`，连接断开、会话空闲、指标为 null/0、没有教练消息、公开设置为默认值、权限为 `notRequested`、主题已解析、动效为 balanced、安全状态为 normal。

每次原生事实变化都调用唯一的 `publish { old -> old.copy(revision = old.revision + 1, generatedAt = Instant.now(clock).toString(), ...) }`。绝不复用含格式化字符串的 `RunningDisplayState`。

- [ ] **Step 3: 将现有 RunningViewModel 编排逐行迁入 Coordinator**

在不改变规则阈值和 prompt 内容的前提下迁移：

1. connect/disconnect and `connectionState` collection;
2. running 状态下的 `pipeline.process(sensor.samples)` collection;
3. `RunSessionManager.onFrame`, split notification and LLM request;
4. `RuleContext` and last ten trigger texts;
5. urgent phrase substitution and `SpeechBus.enqueue`;
6. start/pause/resume/finish and repository save.

实现硬约束：

- 只在 Coordinator 内创建或取消 `pipelineJob`；`ConnectDevice` 只建立 sensor 连接，不创建 runtime 或订阅 samples。
- 成功 `StartRun` 后才捕获设置、创建一个 runtime 并启动唯一 collector；暂停时 collector 可继续接收但不累计会话/规则，停止时 cancel/join。
- 开始时从 `SettingsRepository.current()` 派生一个 `RunConfig`，配置 `SpeechBus`、创建一个 `RunRuntime`、重置规则状态、启动前台服务，再发布 running 状态。
- 暂停/继续前先校验状态，再操作 manager。
- 停止时在 `stateMutex` 内完成状态转换并取出 finalized session；随后先在 `NonCancellable` 中释放运行资源，再以 5 秒 timeout 持久化 finalized session。整个过程仍持有 `lifecycleMutex` 与私有 `stoppingSessionId` 门禁。持久化失败或超时后跑步仍是 finished，命令返回 accepted，并通过脱敏 `AppEvent.Notice` 告知历史保存失败。
- `shutdown()` 不是 UI 操作；只有 application 终止时可以调用 `SpeechBus.shutdown()`。
- 异常转换为稳定中文 `Rejected` 消息和安全的 `AppEvent.Notice`，不逃逸出 `execute`。
- `execute` 与每帧 `RunSessionManager`/`RuleEngine` 更新共享同一个 `stateMutex`；网络、TTS 和 Room 等长耗时工作在锁外执行，避免 stop/frame 数据竞态。
- `StartRun` 在连接未完成时返回 `RESOURCE_UNAVAILABLE`；重复 connect 是 accepted no-op，其他非法状态转换返回 `COMMAND_NOT_ALLOWED`。
- `StopRun` 从设置 `stoppingSessionId` 起一直持有 `lifecycleMutex`，直到持久化与全部清理结束；frame collector 在该 gate 存在时丢弃新 frame。对外可先发布 FINISHED，但 `ConnectDevice`/`StartRun` 必须等待旧 stop 完成，不能让旧 finally 断开新连接。
- stop cleanup 用 `withContext(NonCancellable)` 执行；pipeline cancel/join、`AnalysisManager.cancel()`、TTS stop、`EmergencyPhraseCache.clear()`、sensor disconnect、foreground stop，以及 active runtime/config/job 引用清空分别用独立 `runCatching`，任何一项失败都不能跳过其余项。持久化尝试结束后才清空 `stoppingSessionId`。增加“repository 保存挂起前资源已释放且并发 connect 仍等待”“调用方取消仍完成清理”“一个 cleanup 失败不阻止其他 cleanup”的测试。
- `AnalysisManager` 注入 `CoroutineScope` 和 timeout，保存 active `Job`、pending request 与 generation；`cancel()` 递增 generation、取消 active job 并清空 pending。新增测试：取消后即使 fake LLM 随后完成，也不能向 `SpeechBus` 入队旧会话建议。不得在类内硬编码 `Dispatchers.IO`。
- `EmergencyPhraseCache` 不再把 `LlmClient` 强转为 final `AnthropicLlmClient`；构造器改为注入 `generateRaw: suspend (systemPrompt: String, userPrompt: String) -> String?` 与 `CoroutineScope`。`clear()` 取消 active 预生成/回填 job 并递增 generation token；旧 generation 的迟到结果不能重新填回 cache。生产 Koin 用 `AnthropicLlmClient::generateRaw` 适配，测试直接注入 lambda。
- `SpeechBus` 用短临界区 state lock 管理 policy、reservation、history、priority、generation 与 latestMessage，另用 engine `Mutex` 串行实际 speak；绝不在 state lock 内跨越 suspend `speak`。URGENT 先在锁内登记，再在锁外调用 `ttsEngine.stop()`，使当前低优先级 speak 退出后立即取得 engine lock。`configure/resetSession/stop/shutdown` 不需要阻塞式获取 suspend Mutex；reset 递增 generation，旧 speak 迟到成功也不得发布。新增并发去重、URGENT 打断和 reset 后迟到结果测试。
- 同一 Task 在 Koin 以 qualifier `named("applicationScope")` 提前注册唯一 application `CoroutineScope(SupervisorJob() + Dispatchers.Default)`，Task 9 只能复用，不得再建第二个 scope。把 Anthropic 同一实例双绑定为 `single { AnthropicLlmClient() }` 与 `single<LlmClient> { get<AnthropicLlmClient>() }`；`EmergencyPhraseCache` 捕获该 concrete 实例的 `generateRaw` method reference。AI 使用 `CoroutineScope(get<CoroutineScope>(named("applicationScope")).coroutineContext + Dispatchers.IO)`，替换 dispatcher 但保留同一个 application Job；测试直接注入 `backgroundScope`。这样 Task 7 提交后的既有 Compose 路径可立即解析 Koin 图。
- Coordinator 启动收集原生 settings 时，先把未脱敏的 `llmApiKey` 恢复到 `LlmConfig.apiKey`，再生成公开快照；重启后已配置 key 不能失效。
- URGENT trigger 在调用 TTS 前立即把 `safetyState` 设为 emergency；Web/TTS 失败不得清除它。新 session 和 stop 将其重置为 normal，普通 warning 在对应条件解除后清除。新增这些状态写入/清除测试。

- [ ] **Step 4: 聚合语音、连接和运行策略 Flow**

在 application scope 收集 `SpeechBus.latestMessage`、`SensorDataSource.connectionState`、`SettingsRepository.settings`、`PermissionStateSource.state` 和 `RuntimePolicySource.state`。映射时对设置脱敏。根据用户偏好和系统深色模式解析主题，并按以下优先级计算原生动效上限：

```text
emergency > background/power-save/severe-thermal > active run > user preference
```

- [ ] **Step 5: 验证 Coordinator 与快照序列化**

`AppSnapshotTest` serializes a fully populated snapshot and compares its JSON tree with `contracts/v1/fixtures/valid/snapshot.json` payload, excluding only `revision` and `generatedAt` values supplied by the test clock.

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.coordinator.*"
.\gradlew.bat :app:testDebugUnitTest
```

预期：Coordinator 测试通过，既有原生测试套件保持绿色。

- [ ] **Step 6: 提交应用级业务核心**

```powershell
git add app/src/main/java/com/healthyagent/running/coordinator app/src/test/java/com/healthyagent/running/coordinator app/src/main/java/com/healthyagent/running/ai/AnalysisManager.kt app/src/main/java/com/healthyagent/running/speech/SpeechBus.kt app/src/main/java/com/healthyagent/running/di/AppModule.kt app/src/test/java/com/healthyagent/running/ai/AnalysisManagerTest.kt app/src/test/java/com/healthyagent/running/ai/EmergencyPhraseCacheTest.kt app/src/test/java/com/healthyagent/running/speech/SpeechBusTest.kt
git add -p app/src/main/java/com/healthyagent/running/ai/EmergencyPhraseCache.kt
git diff --cached --name-only
git commit -m "refactor: 用 AppCoordinator 持有跑步业务"
```

### Task 8：把历史、设置和权限命令并入 Coordinator

**Files:**

- Modify: `app/src/main/java/com/healthyagent/running/coordinator/AppCommand.kt`
- Modify: `app/src/main/java/com/healthyagent/running/coordinator/DefaultAppCoordinator.kt`
- Modify: `app/src/main/java/com/healthyagent/running/data/local/RunSessionDao.kt`
- Modify: `app/src/main/java/com/healthyagent/running/data/repository/RunRepository.kt`
- Modify: `app/src/main/java/com/healthyagent/running/ai/LlmConfig.kt`
- Modify: `app/src/main/java/com/healthyagent/running/ai/LlmModels.kt`
- Modify: `app/src/main/java/com/healthyagent/running/ai/PromptBuilder.kt`
- Modify: `app/src/main/java/com/healthyagent/running/ai/AnthropicLlmClient.kt`
- Create: `app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorHistorySettingsTest.kt`
- Modify: `app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorTest.kt`
- Create: `app/src/test/java/com/healthyagent/running/ai/CoachTonePromptSelectorTest.kt`
- Create: `app/src/androidTest/java/com/healthyagent/running/data/local/RunSessionDaoTest.kt`

**Interfaces:**

```kotlin
@Serializable
data class HistoryPagePayload(
    val page: Int,
    val pageSize: Int,
    val totalCount: Int,
    val items: List<RunSessionSummaryPayload>,
) : AppCommandPayload

@Serializable
data class RunSessionSummaryPayload(
    val id: String,
    val startTimeMillis: Long,
    val totalDistanceMeters: Double,
    val totalDurationSec: Long,
    val avgHeartRate: Int?,
    val avgCadence: Int?,
    val avgPaceSecPerKm: Double?,
    val splits: List<SplitSummaryPayload>,
)

@Serializable
data class SplitSummaryPayload(
    val index: Int,
    val durationSec: Long,
    val avgHeartRate: Int?,
    val avgCadence: Int?,
    val avgPaceSecPerKm: Double,
)

interface RunRepository {
    suspend fun saveSession(session: RunSession)
    suspend fun getAllSessions(): List<RunSession>
    suspend fun getSessionsPage(offset: Int, limit: Int): List<RunSession>
    suspend fun countSessions(): Int
    suspend fun getSessionWithSplits(sessionId: String): RunSession?
}
```

- `QueryHistory` 只接受 `page >= 1`、`pageSize in 1..50`；先用 `Long` 计算 `(page - 1L) * pageSize`，结果大于 `Int.MAX_VALUE` 时返回 `INVALID_MESSAGE`，通过后才转为 DAO offset。
- `UpdateSettings` validates, atomically persists, applies `LlmConfig.apiKey` in native memory, and returns no secrets.
- `RequestPermission` 在没有 pending 请求时设置供 Activity 消费的 `platformEffect`；已有 pending 请求时返回 `COMMAND_NOT_ALLOWED`。`CompletePermissionRequest` 必须匹配 pending request ID，随后更新原生权限状态和快照、清空 effect，并发出供 Gateway/React 消费的 `AppEvent.PermissionResult`。
- `QueryHistory` 不持有 `lifecycleMutex`；`UpdateSettings` 与 `StartRun` 捕获设置时共用 `settingsMutex` 形成确定的先后关系；权限 request/complete 共用 `permissionMutex`。增加一个挂起历史查询期间执行 `StopRun` 的测试，要求 stop 不等待分页结果。

- [ ] **Step 1: 写失败测试**

必须覆盖：

```kotlin
@Test fun `历史查询分页且不写入每秒快照`() = runTest {
    val revision = coordinator.snapshot.value.revision
    val result = coordinator.execute(AppCommand.QueryHistory(page = 2, pageSize = 10))
    assertEquals(10, repository.lastOffset)
    assertEquals(10, repository.lastLimit)
    assertTrue((result as CommandOutcome.Accepted).payload is HistoryPagePayload)
    assertEquals(revision, coordinator.snapshot.value.revision)
}

@Test fun `设置结果和快照不回传 API key`() = runTest {
    coordinator.execute(AppCommand.UpdateSettings(SettingsPatch(llmApiKey = "secret-value")))
    advanceUntilIdle()
    val json = jsonEncoder.encodeToString(coordinator.snapshot.value)
    assertFalse(json.contains("secret-value"))
    assertTrue(coordinator.snapshot.value.settings.llmApiKeyConfigured)
}

@Test fun `权限请求由 effect 交给 Activity`() = runTest {
    coordinator.execute(AppCommand.RequestPermission("request-1", PermissionKind.RUNNING_SENSORS))
    assertEquals(
        AppEffect.RequestPermission("request-1", PermissionKind.RUNNING_SENSORS),
        coordinator.platformEffect.value,
    )
}

@Test fun `权限完成后产生可发送给 React 的结果事件`() = runTest {
    coordinator.events.test {
        assertEquals(
            CommandOutcome.Accepted(),
            coordinator.execute(
                AppCommand.RequestPermission("request-1", PermissionKind.RUNNING_SENSORS),
            ),
        )
        assertEquals(
            CommandOutcome.Accepted(),
            coordinator.execute(
            AppCommand.CompletePermissionRequest(
                "request-1",
                PermissionKind.RUNNING_SENSORS,
                PermissionGrantResult(bluetoothGranted = true, bodySensorsGranted = true),
            ),
            ),
        )
        assertEquals(
            AppEvent.PermissionResult("request-1", PermissionKind.RUNNING_SENSORS, true),
            awaitItem(),
        )
    }
}
```

权限测试还必须覆盖：complete 后 `platformEffect == null`、`PermissionStateSource.recordResult` 精确调用一次且 permission snapshot/revision 已更新；错误 request ID 或 kind 返回 `COMMAND_NOT_ALLOWED`，不清 effect、不 record、不 emit；pending 时第二个 `RequestPermission` 被拒绝。历史测试增加会导致 offset 溢出的超大 page，断言 repository 从未被调用。

- [ ] **Step 2: 增加 Room 分页和计数**

精确新增以下 DAO 方法：

```kotlin
@Query("SELECT * FROM run_sessions ORDER BY startTimeMillis DESC LIMIT :limit OFFSET :offset")
suspend fun getSessionsPage(offset: Int, limit: Int): List<RunSessionEntity>

@Query("SELECT COUNT(*) FROM run_sessions")
suspend fun countSessions(): Int

@Transaction
suspend fun insertSessionWithSplits(
    session: RunSessionEntity,
    splits: List<SplitEntity>,
) {
    insertSession(session)
    if (splits.isNotEmpty()) insertSplits(splits)
}
```

Repository 的 `saveSession` 必须使用 `insertSessionWithSplits`，保证 session/splits 原子写入；instrumentation 增加故意制造 split insert 失败的测试，断言不会留下半条 session。分页查询只为当前返回页加载 splits。立即删除 DAO 的无界 `getAllSessions()`；Repository 暂时保留同名兼容方法，但内部按固定 50 条 page 循环调用分页 API，直到删除 `HistoryViewModel`。

扩展 `RunRepository` 的同一提交必须同步更新 `AppCoordinatorTest`、`AppCoordinatorHistorySettingsTest` 和所有现有 fake/anonymous implementation，至少为分页、计数和按 ID 查询提供可断言的内存实现；不能把编译修复推迟到后续任务。

- [ ] **Step 3: 实现命令验证和安全设置映射**

使用单一函数把领域设置映射为 `PublicSettingsSnapshot`。非空 API key 写入 DataStore 并更新 `LlmConfig.apiKey`；空字符串表示显式清除。Fish 凭据持久化后在下一次创建 TTS engine 时生效，与当前原生行为一致；UI 文案必须注明“下次启动生效”。

`coachTone` 在 `RunConfig` 中捕获，经 `PromptBuilder` 映射到 `LlmRunConfig.coach_tone`，并进入 LLM system-prompt selector，但不改变 rule-engine 文案。只在 `AnthropicLlmClient` 增加三段简短语气后缀，并只单测 selector；不要复制整份 prompt。编辑 `AnthropicLlmClient.kt` 前先审阅并保留既有未暂存差异，只暂存新增语气 selector 的 hunk。

- [ ] **Step 4: 验证历史与权限路径**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.coordinator.AppCoordinatorHistorySettingsTest"
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.healthyagent.running.data.local.RunSessionDaoTest"
```

预期：分页按最新优先；密钥永不序列化；权限 effect 带原始 request ID。

- [ ] **Step 5: 提交非跑步命令**

```powershell
git add app/src/main/java/com/healthyagent/running/coordinator/AppCommand.kt app/src/main/java/com/healthyagent/running/coordinator/DefaultAppCoordinator.kt app/src/main/java/com/healthyagent/running/data/local/RunSessionDao.kt app/src/main/java/com/healthyagent/running/data/repository/RunRepository.kt app/src/main/java/com/healthyagent/running/ai/LlmConfig.kt app/src/main/java/com/healthyagent/running/ai/LlmModels.kt app/src/main/java/com/healthyagent/running/ai/PromptBuilder.kt app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorHistorySettingsTest.kt app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorTest.kt app/src/test/java/com/healthyagent/running/ai/CoachTonePromptSelectorTest.kt app/src/androidTest/java/com/healthyagent/running/data/local/RunSessionDaoTest.kt
git add -p app/src/main/java/com/healthyagent/running/ai/AnthropicLlmClient.kt
git diff --cached --name-only
git commit -m "feat: 统一历史设置与权限命令"
```

### Task 9：接入应用 Scope、前台服务与 Compose 兼容适配器

**Files:**

- Create: `app/src/main/java/com/healthyagent/running/platform/AndroidRunForegroundController.kt`
- Create: `app/src/main/java/com/healthyagent/running/platform/AndroidRuntimePolicySource.kt`
- Create: `app/src/main/java/com/healthyagent/running/platform/AndroidPermissionStateSource.kt`
- Modify: `app/src/main/java/com/healthyagent/running/data/local/SettingsDataStore.kt`
- Modify: `app/src/main/java/com/healthyagent/running/data/sensor/RunningService.kt`
- Modify: `app/src/main/java/com/healthyagent/running/ui/running/RunningViewModel.kt`
- Modify: `app/src/main/java/com/healthyagent/running/di/AppModule.kt`
- Create: `app/src/test/java/com/healthyagent/running/testing/MainDispatcherRule.kt`
- Create: `app/src/test/java/com/healthyagent/running/ui/running/RunningViewModelAdapterTest.kt`
- Create: `app/src/androidTest/java/com/healthyagent/running/ui/ComposeFallbackContinuityTest.kt`

**Interfaces:**

- Koin 复用 Task 7 已注册的唯一 application `CoroutineScope(SupervisorJob() + Dispatchers.Default)`，并产出一个 `AppCoordinator` singleton；本任务不创建第二个 scope。
- `RunningViewModel` consumes only `AppCoordinator`; it maps `AppSnapshot` to existing `RunningUiState` and delegates five commands.
- `RunningViewModel.onCleared()` performs no sensor disconnect, TTS shutdown, pipeline cancellation or foreground-service stop.
- `AndroidRunForegroundController.start/stop` 是 `RunningService` 的幂等封装。

- [ ] **Step 1: 写 ViewModel 所有权失败测试**

使用 `MainDispatcherRule(StandardTestDispatcher())` 为 `viewModelScope/stateIn` 设置并恢复 `Dispatchers.Main`。使用 fake Coordinator，断言每个公开方法精确发送一条命令。通过 `ViewModelStore.clear()` 清理 ViewModel 后，断言 fake Coordinator 没有收到生命周期命令。还要断言数值快照能映射为现有格式化 `RunningDisplayState`，保持 Compose 回退外观。

运行测试，预期因构造器/API 不存在而失败。

- [ ] **Step 2: 实现原生平台适配器**

`AndroidRunForegroundController.start()` 使用 `ContextCompat.startForegroundService`，`stop()` 使用 `context.stopService`，并用 atomic started flag 使重复调用为空操作。`RunningService` 改为 `START_NOT_STICKY`：本规格不做进程死亡后的 session 恢复，系统不能在 Coordinator 已回到 IDLE 时单独重启一条虚假的“跑步进行中”通知。Service 不拥有 session state。

`AndroidRuntimePolicySource` 暴露前后台、省电、低电量、系统关闭动画/Reduced Motion、system dark 和 thermal severity。thermal listener 只在 API 29+ 注册，低版本返回 NORMAL；callback 在 application scope 只注册一次，并在 scope 取消时解除。`AndroidPermissionStateSource` 读取蓝牙/位置与 `BODY_SENSORS` 权限组，并接收 `CompletePermissionRequest` 的细分结果。

为区分进程重启后的 `notRequested` 与 `denied`，在 `SettingsDataStore` 增加不进入公开设置/Bridge 的内部布尔键 `runningSensorsPermissionRequested`。发起真实 Activity permission launcher 前原子标记为 true；`AndroidPermissionStateSource` 以系统 grant 状态和该持久标记合成快照。增加重建 repository/source 后仍保持 denied 的 DataStore instrumentation 测试，不能只在进程内记忆。

- [ ] **Step 3: 将 RunningViewModel 变成纯适配器**

最终构造器为：

```kotlin
class RunningViewModel(
    private val coordinator: AppCoordinator,
) : ViewModel()
```

使用 `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ...)` 完成 UI 映射。通过 `viewModelScope.launch { coordinator.execute(...) }` 委托方法；把拒绝映射到现有 `RunningUiEvent.ShowError`。移除全部旧业务依赖、`startPipeline`、规则上下文、触发历史和 `onCleared` 中的破坏性工作。

- [ ] **Step 4: 重写 Koin 图且保持单一事实源**

移除 singleton `RunConfig`、`RunningDataPipeline` 和固定配置的 `RuleEngine`。注册 `RunRuntimeFactory`，用 `run.start` 时捕获的设置创建它们。只保留一个共享 `SpeechBus`、repository、sensor source 和 session manager。将同一个 `SettingsDataStore` 实例同时注册为 concrete 和 `SettingsRepository`。

为 `AppCoordinator` 使用 Koin 的 `createdAtStart()`，让连接、策略和设置 collector 独立于 UI 创建而存在。

- [ ] **Step 5: 回归 Compose 模式和后台连续性**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.ui.running.RunningViewModelAdapterTest"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug -PwebUiEnabled=false
.\gradlew.bat :app:connectedDebugAndroidTest -PwebUiEnabled=false "-Pandroid.testInstrumentationRunnerArguments.class=com.healthyagent.running.ui.ComposeFallbackContinuityTest"
```

`ComposeFallbackContinuityTest` 自动执行连接 → 开始 → 前往设置 → 返回，并通过 UI 以及从 Koin 取得的同一 `AppCoordinator` 快照断言 session ID 和指标连续、前台通知保持到停止；单 collector 数量由 `AppCoordinatorTest` 的 fake runtime 计数负责，模拟器再做一次视觉抽查。

- [ ] **Step 6: 提交所有权迁移**

```powershell
git add app/src/main/java/com/healthyagent/running/platform app/src/main/java/com/healthyagent/running/data/sensor/RunningService.kt app/src/test/java/com/healthyagent/running/testing/MainDispatcherRule.kt app/src/test/java/com/healthyagent/running/ui/running/RunningViewModelAdapterTest.kt app/src/androidTest/java/com/healthyagent/running/ui/ComposeFallbackContinuityTest.kt
git add -p app/src/main/java/com/healthyagent/running/data/local/SettingsDataStore.kt
git add -p app/src/main/java/com/healthyagent/running/ui/running/RunningViewModel.kt app/src/main/java/com/healthyagent/running/di/AppModule.kt
git diff --cached --name-only
git commit -m "refactor: 将跑步生命周期提升到应用级"
```

- [ ] **阶段二审查点**

执行一次范围明确的代码审查，重点检查双业务所有者、重复 collector、ViewModel 清理副作用、凭据序列化、非法状态转换和前台服务泄漏。解决所有发现后再开始 WebView 工作。

---

## 阶段三：安全 Bridge、WebView 宿主与原生降级

### Task 10：实现命令映射、握手、快照节流和幂等 Gateway

**Files:**

- Create: `app/src/main/java/com/healthyagent/running/bridge/CommandDeduplicator.kt`
- Create: `app/src/main/java/com/healthyagent/running/bridge/BridgeCommandMapper.kt`
- Create: `app/src/main/java/com/healthyagent/running/bridge/SnapshotEmissionPolicy.kt`
- Create: `app/src/main/java/com/healthyagent/running/bridge/WebBridgeGateway.kt`
- Create: `app/src/main/java/com/healthyagent/running/bridge/DefaultWebBridgeGateway.kt`
- Create: `app/src/test/java/com/healthyagent/running/bridge/CommandDeduplicatorTest.kt`
- Create: `app/src/test/java/com/healthyagent/running/bridge/SnapshotEmissionPolicyTest.kt`
- Create: `app/src/test/java/com/healthyagent/running/bridge/WebBridgeGatewayTest.kt`

**Interfaces:**

```kotlin
interface WebBridgeGateway {
    val outboundMessages: Flow<OutboundBridgeMessage>
    val handshakeState: StateFlow<HandshakeState>

    suspend fun receive(
        rawMessage: String,
        sourceOrigin: BridgeOrigin,
        isMainFrame: Boolean,
        transportEpoch: Long,
    )

    fun beginTransport(): Long
    fun endTransport(transportEpoch: Long)
}

data class BridgeOrigin(
    val scheme: String,
    val host: String,
    val port: Int?,
) {
    companion object {
        val APP_ASSETS = BridgeOrigin("https", "appassets.androidplatform.net", null)
    }
}

data class OutboundBridgeMessage(
    val transportEpoch: Long,
    val rawMessage: String,
)

class CommandDeduplicator(private val capacity: Int = 128) {
    fun find(requestId: String, fingerprint: String): CachedCommandResult
    fun remember(requestId: String, fingerprint: String, encodedResult: String)
    fun clear()
}

sealed interface HandshakeState {
    data object Detached : HandshakeState
    data class Waiting(val transportEpoch: Long) : HandshakeState
    data class Negotiating(val transportEpoch: Long, val helloId: String, val buildId: String) : HandshakeState
    data class Ready(val transportEpoch: Long, val helloId: String, val buildId: String) : HandshakeState
    data class Failed(val transportEpoch: Long, val code: BridgeErrorCode) : HandshakeState
}
```

- 使用 `AppCoordinator`，绝不引用 Activity、ViewModel 或 WebView。
- 入站只允许 `hello` 和 `command`；出站只产生 `ready`、`snapshot`、`result`、`event`。
- `beginTransport()` 每次返回递增 epoch；新主文档只接收/发送当前 epoch 的消息。`endTransport()` 使该 epoch 失效，但两者都保留命令去重；仅进程死亡时清空去重缓存。
- Gateway 保持纯 JVM 可测，不引用 `android.net.Uri`。WebKit 宿主把回调的 Android `Uri` 规范化为 `BridgeOrigin(scheme.lowercase(), host.lowercase(), explicitPortOrNull)`；Gateway 只接受与 `BridgeOrigin.APP_ASSETS` 完全相等的值，拒绝 http、显式端口、userinfo、空 host 和任何解析失败。

- [ ] **Step 1: 写 128 项 LRU 和 payload 冲突失败测试**

```kotlin
@Test fun `相同 ID 与相同 payload 返回缓存结果`() {
    cache.remember("id-1", "sha256-a", "accepted")
    assertEquals(CachedCommandResult.Hit("accepted"), cache.find("id-1", "sha256-a"))
}

@Test fun `相同 ID 与不同 payload 被视为冲突`() {
    cache.remember("id-1", "sha256-a", "accepted")
    assertEquals(CachedCommandResult.Conflict, cache.find("id-1", "sha256-b"))
}

@Test fun `访问会刷新 LRU 次序`() {
    repeat(128) { cache.remember("id-$it", "hash-$it", "result-$it") }
    assertTrue(cache.find("id-0", "hash-0") is CachedCommandResult.Hit)
    cache.remember("id-128", "hash-128", "result-128")
    assertTrue(cache.find("id-0", "hash-0") is CachedCommandResult.Hit)
    assertEquals(CachedCommandResult.Miss, cache.find("id-1", "hash-1"))
}
```

预期：失败，直到基于访问顺序的 `LinkedHashMap` 实现完成。

- [ ] **Step 2: 写 Gateway 安全与握手失败测试**

必须断言：

- 错误 origin 和非主 frame 消息既不产生 outbound response，也不调用 Coordinator；
- hello 前 Coordinator snapshot/event 不产生任何 outbound；
- hello 前的命令返回 `COMMAND_NOT_ALLOWED`；
- 不兼容 hello 返回 `BRIDGE_VERSION_UNSUPPORTED` 并进入 failed state；
- 兼容 hello 发送一次包含原 `helloId`、回显 `buildId` 和当前完整快照的 ready；
- 同一 epoch 在 Ready/Failed 后收到新的合法 hello 时，以新 `helloId/buildId` 重新协商并发送新的 ready；旧 attempt 状态不能阻止显式 restart；
- ready 携带 revision N 后把 N 记为 lastSentRevision，不再额外发送同 revision snapshot；
- 重复 command ID 返回逐字节相同的缓存结果，并且 Coordinator 只调用一次；
- 同 ID 不同 payload 返回 `INVALID_MESSAGE`，不再次调用 Coordinator；
- `beginTransport()` 使握手回到 waiting、递增 epoch 但保留 dedupe；旧 epoch 的 hello、command 和 outbound 都被忽略；
- `endTransport(oldEpoch)` 在已有更新 current epoch 时是 no-op，不能把新 transport 置为 Detached；
- 旧 epoch 中较晚完成的 command result 只能写入 dedupe，不能进入新 epoch 的 outbound；
- fake Coordinator 挂起 command 时，`beginTransport()` 必须同步立即返回，不等待该 command；若新 epoch 没有重发该 ID，旧结果不外发；若新 epoch 显式重发同 ID/同 fingerprint，则等待同一 in-flight 结果并只向新请求所在 epoch 回放一次；
- 不同 request ID 不因 Gateway dedupe 互相串行：挂起 `history.query` 时，新的 `run.stop` 仍立即到达 Coordinator；
- Coordinator 的 `AppEvent.PermissionResult` 序列化为 `name: permission.result` 且包含原始 request ID；`AppEvent.Notice` 序列化为 `name: notice` 与脱敏 code/message；`AppEffect` 永不进入 Bridge；
- 小于或等于最近已发送 revision 的快照必须跳过。

运行 `WebBridgeGatewayTest`，预期失败。

- [ ] **Step 3: 实现严格命令映射**

`BridgeCommandMapper` decodes `payload.name` and `.body` using command-specific Kotlin Serialization DTOs with unknown keys rejected. It maps JSON `null` target fields to `NullableSettingUpdate.Clear`, absence to `Unchanged`, and values to `Set`. It validates page bounds and settings ranges before calling Coordinator.

Gateway 要求 command payload 的 `helloId` 与当前 `HandshakeState.Ready.helloId` 完全一致，旧 document 的迟到命令即使宿主误标当前 epoch 也必须拒绝。使用严格解码后命令 DTO 的 canonical 重编码字节计算 SHA-256 去重指纹，但 fingerprint 只含 `name/body`、不含 `helloId`，使新页面显式重发相同 request ID 时仍能命中结果。Timber 消息绝不能包含 API key 值。

Canonical JSON 算法固定为：先按具体命令 DTO 严格解码，保留 optional 字段的 absent/null/value 三态；再从类型化值重建 command `name` 与 `body`，对每层 object key 递归按 Unicode code point 升序排序，数组保持原顺序，数值按目标字段类型规范重编码，再对无额外空白的 UTF-8 做 SHA-256。新增测试证明字段顺序不同、以及 Double 字段写成 `360`/`360.0` 时命中同一 cache；absent 与显式 null 仍不同，命令名不同也不同。

- [ ] **Step 4: 实现握手、结果和事件**

收到合法 hello 后进入 ready，并发出 `BridgeEnvelope`：`contractVersion = 1`、`type = READY`、envelope ID 来自注入的 UUID factory、`sentAt = Instant.now(clock)`、payload `helloId` 等于收到的 hello envelope ID、`buildId` 原样回显、capabilities 精确为 `snapshot.v1`、`history.pagination`、`permission.request`，且 `snapshot = coordinator.snapshot.value`。单元测试注入 ready ID `00000000-0000-4000-8000-000000000101` 和时间 `2026-08-26T12:00:10Z`，再把完整 JSON tree 与 `ready.json` 比较。

每个命令结果复用 command envelope 的 `id` 作为结果 envelope `id`，payload 保持 Task 3 定义的 `{ status, data|error }`，BridgeClient 直接按 envelope ID 关联。把精确编码字符串存入去重缓存；Coordinator 的拒绝保持原错误码，意外异常映射为 `INTERNAL_ERROR` 和固定中文消息。

Gateway 用 `AtomicLong`/atomic current epoch 实现同步、非阻塞的 `beginTransport()`/`endTransport()`；每次 receive 在解析后、调用 Coordinator 前和发送 outbound 前都重新比较 epoch。dedupe `Mutex` 只保护短临界区的 LRU 与 `inFlight[requestId] = (fingerprint, CompletableDeferred<String>)`：第一个请求登记 leader 后在锁外执行 Coordinator，重复同 ID/同 fingerprint 的 follower 在锁外 await 同一 deferred，不同 fingerprint 立即冲突，不同 ID 可并行。leader 无论 accepted、rejected 或异常都先在 `NonCancellable` 短临界区完成 `encode → remember → remove inFlight → complete deferred`，再按该次 receive 的 epoch 是否仍为 current 决定是否发送；绝不跨 suspend Coordinator 调用持有 transport/dedupe 锁。

- [ ] **Step 5: 实现快照发送策略**

连接、session state/ID、coach message ID、设置、权限、主题、动效或安全状态变化时，`SnapshotEmissionPolicy` 立即发送；跑步中的纯指标变化最多每 1,000 ms 发送一次，idle 指标最多每 2,000 ms 一次。任何已发送 revision 都不得重复发送。

测试注入 monotonic clock；单元测试不使用 `delay`。

- [ ] **Step 6: 验证并提交 Gateway**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.bridge.*"
.\gradlew.bat :app:testDebugUnitTest
git add app/src/main/java/com/healthyagent/running/bridge app/src/test/java/com/healthyagent/running/bridge
git diff --cached --name-only
git commit -m "feat: 实现安全且幂等的 Web Bridge"
```

### Task 11：实现 React BridgeClient、revision store 和启动骨架

**Files:**

- Create: `frontend/src/bridge/BridgeTransport.ts`
- Create: `frontend/src/bridge/WebMessageTransport.ts`
- Create: `frontend/src/bridge/MockBridgeTransport.ts`
- Create: `frontend/src/bridge/BridgeClient.ts`
- Create: `frontend/src/bridge/SnapshotStore.ts`
- Create: `frontend/src/bridge/web-message.d.ts`
- Create: `frontend/src/bridge/BridgeClient.test.ts`
- Create: `frontend/src/bridge/SnapshotStore.test.ts`
- Create: `frontend/src/app/providers.tsx`
- Modify: `frontend/src/app/App.tsx`
- Modify: `frontend/src/app/App.test.tsx`

**Interfaces:**

```ts
export interface BridgeTransport {
  readonly messages: EventTarget;
  postMessage(raw: string): void;
}

export interface ExternalStore<T> {
  getSnapshot(): T;
  subscribe(listener: () => void): () => void;
}

export type BridgeClientState =
  | { phase: 'starting'; helloId: string }
  | { phase: 'ready'; helloId: string; buildId: string }
  | { phase: 'failed'; code: BridgeErrorCode; message: string };

export type CommandBody<T extends CommandName> = CommandBodyMap[T];
export type CommandResult<T extends CommandName> =
  | { status: 'accepted'; data: T extends 'history.query' ? unknown : null }
  | { status: 'rejected'; error: { code: BridgeErrorCode; message: string } };

export interface BridgeClient {
  readonly state: ExternalStore<BridgeClientState>;
  start(): void;
  restart(): void;
  stop(): void;
  subscribeEvent(listener: (event: AppEventPayload) => void): () => void;
  command<T extends CommandName>(name: T, body: CommandBody<T>): Promise<CommandResult<T>>;
}

export interface SnapshotState {
  snapshot: AppSnapshot | null;
  lastReceivedAt: number | null;
  stale: boolean;
}

export interface SnapshotStore {
  receiveSnapshot(snapshot: AppSnapshot, receivedAt: number): void;
  receiveReady(snapshot: AppSnapshot, receivedAt: number): void;
  markTransportStarting(): void;
}
```

- 只有 `WebMessageTransport` 可以访问 `window.HealthyAgentBridge`。
- ready 前禁用命令；handshake 5,000 ms 后超时。每条业务命令固定 10,000 ms timeout，超时以 `RESOURCE_UNAVAILABLE` 拒绝并删除 pending ID，随后迟到的 result 被忽略；`stop/restart` 清理同一组 timer、拒绝 pending promise 并移除 listener。
- 普通 snapshot 只接受严格递增的 revision；ready 中的完整快照若 revision 相等，仍刷新 `lastReceivedAt` 并清除 stale，但不重复业务更新；更旧 revision 仍拒绝。活动跑步 3,000 ms 未收到更新时只标记 stale，不修改指标。

- [ ] **Step 1: 写 BridgeClient 失败测试**

使用内存 fake transport 和 fake timer。必须覆盖：

```ts
it('先注册 listener 再发送 hello，并能接住同步 ready', async () => {
  transport.replySynchronouslyOnPost((hello) => readyFor(hello.id));
  client.start();
  expect(transport.sent).toHaveLength(1);
  expect(JSON.parse(transport.sent[0]).type).toBe('hello');
  transport.receive(validReady);
  expect(client.state.getSnapshot().phase).toBe('ready');
});

it('restart 只接受当前 helloId，且相同 revision 的 ready 恢复 freshness', () => {
  client.start();
  const oldHelloId = lastSentHelloId();
  snapshotStore.receive(snapshot(8), 1_000);
  snapshotStore.tick(5_000);
  client.restart();
  const currentHelloId = lastSentHelloId();
  transport.receive(readyFor(oldHelloId, snapshot(8)));
  expect(client.state.getSnapshot().phase).toBe('starting');
  transport.receive(readyFor(currentHelloId, snapshot(8)));
  expect(snapshotStore.getState()).toMatchObject({ stale: false, lastReceivedAt: 5_000 });
});

it('把 notice 作为一次性事件只投递一次', () => {
  const received: AppEventPayload[] = [];
  client.subscribeEvent((event) => received.push(event));
  transport.receive(validNoticeEvent);
  expect(received).toEqual([validNoticeEvent.payload]);
});

it('rejects commands before ready', async () => {
  await expect(client.command('run.start', {})).rejects.toMatchObject({
    code: 'COMMAND_NOT_ALLOWED',
  });
});

it('按相同 envelope ID 关联 result', async () => {
  readyClient();
  const pending = client.command('run.pause', {});
  transport.receive(resultForLastSentRequest());
  await expect(pending).resolves.toEqual({ status: 'accepted', data: null });
});

it('fails after a five second handshake timeout', () => {
  client.start();
  vi.advanceTimersByTime(5_001);
  expect(client.state.getSnapshot().phase).toBe('failed');
});

it('命令十秒无结果会清理 pending 且忽略迟到 result', async () => {
  readyClient();
  const pending = client.command('run.pause', {});
  const requestId = lastSentCommandId();
  vi.advanceTimersByTime(10_001);
  await expect(pending).rejects.toMatchObject({ code: 'RESOURCE_UNAVAILABLE' });
  const stateAfterTimeout = client.state.getSnapshot();
  transport.receive(acceptedResult(requestId));
  expect(client.state.getSnapshot()).toEqual(stateAfterTimeout);
});
```

- [ ] **Step 2: 写 revision 与 stale 失败测试**

```ts
it('ignores equal and older revisions', () => {
  store.receiveSnapshot(snapshot(4), 1_000);
  store.receiveSnapshot(snapshot(4), 1_100);
  store.receiveSnapshot(snapshot(3), 1_200);
  expect(store.getState().snapshot?.revision).toBe(4);
  expect(store.getState().lastReceivedAt).toBe(1_000);
});

it('marks an active run stale after three seconds without extrapolation', () => {
  store.receiveSnapshot(snapshot(5, { elapsedSeconds: 30, state: 'running' }), 1_000);
  store.tick(4_001);
  expect(store.getState().stale).toBe(true);
  expect(store.getState().snapshot?.metrics.elapsedSeconds).toBe(30);
});
```

- [ ] **Step 3: 实现 transport 和 client 生命周期**

在 `web-message.d.ts` 声明注入的 listener 对象：

```ts
interface Window {
  HealthyAgentBridge?: {
    postMessage(message: string): void;
  };
}
```

`WebMessageTransport.postMessage` 调用注入对象的 `postMessage`。Native-to-Web 消息来自 `window.addEventListener('message', ...)`，`stop()` 移除同一个 window listener。每次 `start/restart` 生成新的 hello envelope ID，并保存该 attempt 的 hello ID 与 build ID；只有 payload 的 `helloId`、`buildId` 同时精确匹配当前 attempt 才接受 ready。每条 command payload 自动携带当前 ready `helloId`，调用者不能覆盖。`BridgeClient.start()` 顺序固定为：注册 listener → 启动 handshake timer → 发送 hello；绝不能先发 hello。

普通浏览器没有该对象时，provider 使用仅开发环境可用的 `MockBridgeTransport`；production 构建显示本地失败状态，不伪造指标。

- [ ] **Step 4: 接入 React provider 与启动骨架**

`providers.tsx` 每个页面生命周期只构造一个 client，并暴露 `useBridgeState`、`useAppSnapshot`、`useCommand` 与一次性 `useBridgeEvent`。Notice 映射为可访问的本地 live-region/toast，permission result 交给设置页；事件不写入持久 store。`stop()`、握手失败和显式 restart 都要清除 `data-app-ready`、handshake timer 和全部 pending command timer；restart 保留 SnapshotStore 内容但调用 `markTransportStarting()` 停止旧 stale timer，收到当前 attempt ready 后重新启动 freshness timer。

- `starting` 阶段显示本地启动 skeleton；
- `ready` 后显示经过测试的本地占位；Task 14 只把该占位换成 router outlet；
- `failed` 后显示带重试操作的静态中文错误。

仅在 ready 后设置 `document.documentElement.dataset.appReady = 'true'`，让 Android instrumentation 可以确定性等待。

- [ ] **Step 5: 验证并提交前端桥接客户端**

```powershell
Push-Location .\frontend
corepack pnpm test:unit -- BridgeClient.test.ts SnapshotStore.test.ts App.test.tsx
corepack pnpm typecheck
corepack pnpm build
Pop-Location
git add frontend/src/bridge frontend/src/app
git diff --cached --name-only
git commit -m "feat: 建立 React Bridge 客户端"
```

### Task 12：构建受限 WebView 宿主和原生安全页

**Files:**

- Create: `app/src/main/java/com/healthyagent/running/ui/web/WebHostState.kt`
- Create: `app/src/main/java/com/healthyagent/running/ui/web/HealthyWebViewClient.kt`
- Create: `app/src/main/java/com/healthyagent/running/ui/web/HealthyWebMessageChannel.kt`
- Create: `app/src/main/java/com/healthyagent/running/ui/web/TrustedWebUri.kt`
- Create: `app/src/main/java/com/healthyagent/running/ui/web/WebHostLifecycleController.kt`
- Create: `app/src/main/java/com/healthyagent/running/ui/web/WebChannelAuditSink.kt`
- Create: `app/src/main/java/com/healthyagent/running/ui/web/WebAppHost.kt`
- Create: `app/src/main/java/com/healthyagent/running/ui/safety/NativeSafetyScreen.kt`
- Create: `app/src/main/java/com/healthyagent/running/ui/AppRoot.kt`
- Modify: `app/src/main/java/com/healthyagent/running/MainActivity.kt`
- Modify: `app/src/main/java/com/healthyagent/running/di/AppModule.kt`
- Modify: `app/build.gradle.kts`
- Create: `app/src/androidTest/java/com/healthyagent/running/ui/safety/NativeSafetyScreenTest.kt`
- Create: `app/src/test/java/com/healthyagent/running/ui/web/TrustedWebUriTest.kt`
- Create: `app/src/test/java/com/healthyagent/running/ui/web/WebHostLifecycleControllerTest.kt`

**Interfaces:**

```kotlin
sealed interface WebHostState {
    data object Starting : WebHostState
    data object Ready : WebHostState
    data class Failed(val reason: WebHostFailure) : WebHostState
}

@Composable
fun WebAppHost(
    gateway: WebBridgeGateway,
    onStateChanged: (WebHostState) -> Unit,
    modifier: Modifier = Modifier,
)

data class NativeSafetyUiState(
    val heartRateBpm: Int?,
    val hrZone: HrZone?,
    val runState: RunState,
    val isDataFresh: Boolean?,
    val failureMessage: String,
)
```

- `WebAppHost` 只传输消息并管理 WebView 生命周期。
- `AppRoot` 选择 feature-flag 回退、Web UI、空闲错误页或活动跑步安全页。
- MainActivity 观察 `AppCoordinator.platformEffect`、持有 Android 权限 launcher，并通过 `AppCommand.CompletePermissionRequest` 回传结果；Gateway 只收集 `events`。Activity 使用 `rememberSaveable` 保存最近已 launch 的 request ID，配置变更后仍能把 Activity Result 回调关联到 application-scope pending request；不得重复 launch。
- Koin 以 `single<WebBridgeGateway> { DefaultWebBridgeGateway(..., applicationScope = get()) }` 注册唯一 application singleton；WebView/Activity/retry 只借用它，不关闭它。Task 13 加 identity test，证明 recreate 前后取得同一实例且 dedupe 保留。

- [ ] **Step 1: 写原生安全页失败测试**

Compose UI 断言：

- active 状态显示心率、区间和数据新鲜度；
- paused 状态显示“继续”而不是“暂停”；
- stop 先打开确认对话框，只有确认才调用 callback；
- retry 调用 `onRetryWebUi`，不改变跑步状态；
- 所有主要操作都提供至少 48 dp 的触控目标和 content description。

运行 instrumentation 测试类，预期失败。

- [ ] **Step 2: 添加 WebKit 和安全配置**

添加 `implementation("androidx.webkit:webkit:1.17.0")`。创建 WebView 时：

```kotlin
settings.javaScriptEnabled = true
settings.allowFileAccess = false
settings.allowContentAccess = false
settings.domStorageEnabled = true
settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
settings.javaScriptCanOpenWindowsAutomatically = false
settings.setSupportMultipleWindows(false)
WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
```

DOM storage 只对 appassets origin 可用，用于保存最后播放的 coach message UUID；不保存指标或凭据。禁用 cookie：`CookieManager.getInstance().setAcceptCookie(false)` 和 `setAcceptThirdPartyCookies(webView, false)`。

同时要求 `WEB_MESSAGE_LISTENER` 和 `POST_WEB_MESSAGE`。任一缺失都在加载前报告 `WebHostFailure.UnsupportedFeature`。

用 `WebViewAssetLoader.Builder().setDomain(WebViewAssetLoader.DEFAULT_DOMAIN).setHttpAllowed(false)` 和 `AssetsPathHandler(context)` 在 `/assets/` 创建 loader。所有导航/资源决策复用不依赖 Android 的 `TrustedWebUri`（输入 raw string/`java.net.URI`）常量与纯函数，宿主只在边界把 `android.net.Uri` 转成字符串：只允许 HTTPS、默认 appassets host、无 userinfo、无显式 port，顶层 path 精确为 `/assets/web/index.html`，subresource 必须以 segment-safe `/assets/web/` 开头；拒绝 `/assets/webx`、http、其他 host/port、反斜杠、原始或 percent-encoded dot/slash traversal。合法 appassets 资源交给 loader，其余请求返回本地 403。

`TrustedWebUriTest` 覆盖上述正反例。构建测试复用 Task 2 verifier，断言最终 `dist/index.html` 的 CSP 恰好一个、内容完整、紧跟 charset 且早于所有资源；不能只检查五条子指令。

- [ ] **Step 3: 实现受限消息 listener 和发送端**

`HealthyWebMessageChannel` 集中封装 listener 的安装、轮换、入口过滤和移除。在 `loadUrl` 前注册名为 `HealthyAgentBridge`，allowed origins 精确为 `setOf("https://appassets.androidplatform.net")`。每次入口主文档 `onPageStarted`（普通 hash 变化不算）先 remove 旧 listener/end 旧 epoch，再调用 `beginTransport()`，然后 `rotate(newEpoch)` 重新注册，让 callback closure 捕获不可变 epoch；旧文档排队的迟到消息只能携带旧 epoch。只把主 frame 消息转发给 Gateway。收集 outbound 时只发送 epoch 匹配项，并以 `Uri.parse("https://appassets.androidplatform.net")` 为目标调用 `WebViewCompat.postWebMessage`；不使用 `evaluateJavascript`。

`WebChannelAuditSink` 是生产 no-op、测试可覆盖的诊断接口，按 WebView identity 记录 listener install/remove、outbound post 和 teardown，不记录消息正文。`WebHostLifecycleController` 依赖可在 JVM fake 的 `WebHostHandle` 操作接口/函数，不直接在 local unit test 实例化 Android `WebView`。其 `teardown(targetIdentity, failure?)` 是幂等且只处理当前实例的唯一释放路径：取消页面/握手 watchdog 与 outbound collector → remove listener → `endTransport` → 从父容器 detach → 清空 WebView/back 引用 → destroy → 记录 audit → 最后报告 failure。`DisposableEffect`、`AndroidView.onRelease`、watchdog/error 与 renderer-gone 全部调用它；不得复制半套清理逻辑。

在 `doUpdateVisitedHistory` 更新 Compose `canGoBack` 状态，并使用 `BackHandler(enabled = canGoBack) { webView.goBack() }`。返回导航绝不能发出 `run.stop`。

- [ ] **Step 4: 处理 5 秒超时和渲染进程消失**

本地入口主文档开始加载时启动 10 秒资源/页面 watchdog；收到同一 epoch 的合法 hello 后 Gateway 进入 `Negotiating`，此时再启动规格要求的 5 秒 handshake watchdog。`Ready` 取消两个 watchdog；`Failed(epoch, code)` 立即 teardown 并进入原生失败面。`onReceivedError`、`onReceivedHttpError` 只处理主 frame。AndroidView 承载自有 `FrameLayout`；`onRenderProcessGone` 调用统一 teardown、报告 `RendererGone(didCrash)` 并返回 `true`，绝不再使用失效 WebView。用户重试时递增 host instance key 并创建新实例，不自动循环。

watchdog scheduler/clock 可注入。JVM 测试用虚拟时钟覆盖：10 秒无页面完成、hello 后 5 秒无 ready、旧 epoch timer 不影响新实例、Ready/Failed/dispose 均取消 timer、重复 teardown 只产生一次 remove/destroy/end。Task 13 再用无 hello 的真实测试 asset 验证 fallback。

- [ ] **Step 5: 实现 AppRoot 决策顺序**

决策表：

| Flag / host | Native session | Surface |
|---|---|---|
| disabled | any | existing `AppNavHost` |
| enabled + starting | idle/finished | `WebAppHost` skeleton |
| enabled + starting | running/paused/preparing/error | `WebAppHost` + 可操作 `NativeSafetyScreen` overlay |
| enabled + ready | any | `WebAppHost` |
| enabled + failed | running/paused/preparing/error | `NativeSafetyScreen` |
| enabled + failed | idle/finished | native static error + retry |

把安全操作直接映射为 Coordinator 命令。`NativeSafetyScreen` 自身不注入 Koin 或 Gateway。

starting overlay 保持 WebView 挂载继续握手，但原生暂停/继续/停止始终可用；Ready 后原子撤下 overlay。增加 UI/instrumentation 测试：活动跑步 reload 期间确认 stop 立即执行，不等待 5/10 秒 watchdog。

React 模式移除无条件启动权限弹窗，只在 `AppEffect.RequestPermission` 后申请。`RUNNING_SENSORS` 在 API 31+ 映射为 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`，API 30 及以下映射为位置/旧蓝牙所需权限；API 26+ 始终同时检查并申请 dangerous 的 `BODY_SENSORS`。Compose 回退模式复用同一映射，不能只在 API 33+ 请求身体传感器权限。

- [ ] **Step 6: 验证构建和安全页**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug -PwebUiEnabled=true
.\gradlew.bat :app:assembleDebug -PwebUiEnabled=false
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.healthyagent.running.ui.safety.NativeSafetyScreenTest"
```

预期：两个 feature-flag APK 变体均能 assemble；原生安全操作通过 semantics 测试。

- [ ] **Step 7: 提交宿主与降级页**

```powershell
git add -p app/build.gradle.kts app/src/main/java/com/healthyagent/running/MainActivity.kt app/src/main/java/com/healthyagent/running/di/AppModule.kt
git add app/src/main/java/com/healthyagent/running/ui/AppRoot.kt app/src/main/java/com/healthyagent/running/ui/web app/src/main/java/com/healthyagent/running/ui/safety app/src/androidTest/java/com/healthyagent/running/ui/safety
git diff --cached --name-only
git commit -m "feat: 增加安全 WebView 宿主与原生降级页"
```

### Task 13：验证真实 WebView 握手、重载与故障连续性

**Files:**

- Modify: `app/src/main/java/com/healthyagent/running/HealthyAgentApp.kt`
- Create: `app/src/main/java/com/healthyagent/running/di/AppKoinBootstrap.kt`
- Create: `app/src/main/java/com/healthyagent/running/ui/web/RequestAuditSink.kt`
- Modify: `app/src/main/java/com/healthyagent/running/ui/web/HealthyWebViewClient.kt`
- Create: `app/src/androidTest/java/com/healthyagent/running/ui/web/WebAssetLoadingTest.kt`
- Create: `app/src/androidTest/java/com/healthyagent/running/bridge/WebBridgeRoundTripTest.kt`
- Create: `app/src/androidTest/java/com/healthyagent/running/ui/web/WebViewReloadContinuityTest.kt`
- Create: `app/src/androidTest/java/com/healthyagent/running/ui/web/WebRendererRecoveryTest.kt`
- Create: `app/src/androidTest/java/com/healthyagent/running/ui/web/WebRequestAuditTest.kt`
- Create: `app/src/androidTest/java/com/healthyagent/running/ui/web/WebHandshakeTimeoutTest.kt`
- Create: `app/src/androidTest/java/com/healthyagent/running/TestApplicationBootstrapTest.kt`
- Create: `app/src/androidTest/assets/web/bridge-frame-probe.html`
- Create: `app/src/androidTest/assets/web/bridge-frame-child.html`
- Create: `app/src/androidTest/assets/web/no-hello.html`
- Create: `app/src/androidTest/java/com/healthyagent/running/HealthyAgentTestRunner.kt`
- Create: `app/src/androidTest/java/com/healthyagent/running/HealthyAgentTestApplication.kt`
- Create: `app/src/androidTest/java/com/healthyagent/running/testing/TestAppProbe.kt`
- Create: `scripts/resolve-pixel6-device.ps1`
- Modify: `app/build.gradle.kts`

**Interfaces:**

- Instrumentation 使用真实生成的 Vite bundle 和真实 `WebViewAssetLoader`，但在 Activity 创建前注入 mock sensor、repository、runtime factory 与 foreground controller。
- `RequestAuditSink` 只接收请求的 scheme/host/path 与决策，不接收 header、body 或凭据；生产实现为空操作，测试实现记录内存事件。
- 测试 module 同时覆盖 `WebChannelAuditSink`，按 WebView identity 观察 listener install/remove/post/teardown 配对。
- 不向 `src/main` 添加故障注入器、调试 Web API 或可从 JavaScript 调用的测试入口。

- [ ] **Step 1: 固定 instrumentation 依赖并建立启动前测试注入**

固定加入 `androidx.test:core-ktx:1.6.1`、`androidx.test:runner:1.6.2`、`androidx.test:rules:1.6.1`、`androidx.test.ext:junit:1.2.1`、`androidx.test.espresso:espresso-core:3.6.1`、`androidx.test.espresso:espresso-web:3.6.1` 和 `androidTestUtil("androidx.test:orchestrator:1.5.1")`；把 `defaultConfig.testInstrumentationRunner` 精确改为 `com.healthyagent.running.HealthyAgentTestRunner`，启用 `ANDROIDX_TEST_ORCHESTRATOR` 与 `clearPackageData=true`，让每个测试得到全新的 application-scope Coordinator。不要向生产模块添加 Robolectric。

把 Koin 启动提取为 `startHealthyAgentKoin(context, overrideModules = emptyList())`。生产 `HealthyAgentApp` 只调用一次该函数；`HealthyAgentTestRunner.newApplication(...)` 改用 `HealthyAgentTestApplication`，后者在同一次 `startKoin` 中先装生产 module、再装 `allowOverride` 的测试 module，保证 `createdAtStart()` Coordinator 从未创建真实 BLE/runtime。`TestAppProbe.reset()` 在每个测试前清空计数和 fixture，并暴露：

```kotlin
data class TestAppCounts(
    val runtimeCreate: Int,
    val foregroundStart: Int,
    val foregroundStop: Int,
    val repositorySave: Int,
)
```

测试 module 覆盖既有接口，不让 Activity 或生产类依赖 `TestAppProbe`。`TestApplicationBootstrapTest` 位于本步骤实际执行的全量 suite 内，断言打开 Activity 后取得 test fake、Gateway recreate 前后 identity 相同，真实 sensor source 未被构造。

同一步创建 UTF-8 BOM 的 `scripts/resolve-pixel6-device.ps1`，遵守 AGENTS.md 的 Purpose/Created/param、`$ErrorActionPreference = "Stop"`、中文彩色 `Write-Host` section 和仅用于数据对象的 `Write-Output`：从 `ANDROID_SDK_ROOT` 或仓库 `local.properties` 的 `sdk.dir` 解析绝对 `adb.exe`，拒绝依赖 PATH；要求全部 online adb devices 只有一个且为 emulator，API 为 35。再由 `adb emu avd name` 定位对应 AVD `.ini/config.ini`，验证 `hw.device.name=pixel_6`（或等价 Pixel 6 profile）、1080×2400/density 配置，并输出包含 `AdbPath/Serial/AvdName` 的对象。若 AVD 不存在或未启动，明确停止并报告“需用户先创建/启动 Pixel 6 API 35 AVD”，不在错误设备上继续。

- [ ] **Step 2: 写离线加载与 origin 测试**

使用 `-PwebUiEnabled=true` 启动 `MainActivity`。保持生产 WebView 请求拦截器启用，让每个非 appassets 请求收到本地 403；等待 `document.documentElement.dataset.appReady === 'true'`，并断言当前 URL 以此开头：

```text
https://appassets.androidplatform.net/assets/web/index.html#/run
```

产品 bundle 的 manifest/HTML 断言所有加载资源都位于 `/assets/web/`。对独立 test probe 的 remote request 明确断言 `HealthyWebViewClient` 返回本地 403，且 `RequestAuditSink` 记录 `BLOCKED_403`；不要把 CSP 在到达 client 前拦截的请求误称为“观察到系统网络栈”。另用 instrumentation context 的 `AssetsPathHandler` 在独立测试 WebView 加载 `bridge-frame-probe.html`，但复用生产 `HealthyWebMessageChannel`：opaque-origin sandbox iframe 中不得出现 `HealthyAgentBridge`；同源 `bridge-frame-child.html` 的消息必须到达回调并因 `isMainFrame == false` 被拒绝。该测试 WebView 不挂到产品 Activity，也不改变 release CSP。Gateway 的纯单元测试继续覆盖 `BridgeOrigin`，instrumentation 不使用生产绕过开关。

- [ ] **Step 3: 写 Bridge 往返和幂等测试**

从测试页面发送 hello 和 `run.start`，等待结果及 running 快照。重新发送同一编码命令，断言 session ID 不变，并通过 `TestAppProbe` 断言 `runtimeCreate == 1`、`foregroundStart == 1`；重复确认 stop 后断言 `repositorySave == 1`、`foregroundStop == 1`。不要仅依据 UI 文案推断幂等性。

- [ ] **Step 4: 写 reload / recreate 连续性测试**

1. 连接 mock sensor 并开始跑步，等待并断言 `distance > 0`；
2. 记录 session ID、distance 和当前 transport epoch；
3. 调用 WebView reload，等待更大的 epoch 和新的 ready；
4. 在旧页面预先安排一个 reload 后才触发的迟到 command/message，断言它保留旧 epoch/helloId 并被拒绝；同时断言 session ID 相同、distance 不下降，旧 epoch 的延迟 result 未进入新页面；
5. recreate Activity 后重复该断言；
6. 通过 fake `RunForegroundController` 断言整个过程中 `foregroundStart == 1 && foregroundStop == 0`；
7. 再次 reload，在 Starting overlay 中确认 stop，断言立即 `repositorySave == 1 && foregroundStop == 1`，不等待 handshake watchdog。

- [ ] **Step 5: 用真实 renderer 终止验证原生降级与恢复**

在 Pixel 6 API 35 模拟器上先断言 `WebViewFeature.GET_WEB_VIEW_RENDERER` 和 `WebViewFeature.WEB_VIEW_RENDERER_TERMINATE` 受支持，取得 Activity 中的真实 WebView，然后调用：

```kotlin
val renderer = WebViewCompat.getWebViewRenderProcess(webView)
assertTrue(renderer?.terminate() == true)
```

活动跑步时终止 renderer，等待 `onRenderProcessGone`，断言安全页显示相同心率以及暂停/停止操作，且操作仍通过 Coordinator 生效；空闲时重复，断言显示静态重试页。点击重试后必须创建不同 WebView identity、完成新 epoch 握手；`WebChannelAuditSink` 断言旧实例 install/remove 成对、teardown 后无 post，新实例独立 install。若模拟器 WebView 实现缺少任一 feature，测试明确失败并报告镜像/WebView 版本，不用 `assume` 跳过最终门禁。`WebHandshakeTimeoutTest` 加载 `no-hello.html`，断言真实 5 秒路径进入 fallback 且 listener/watchdog 被清理。Android Back 测试推迟到 Task 14 路由存在之后。

- [ ] **Step 6: 执行真实模拟器套件**

```powershell
$device = & .\scripts\resolve-pixel6-device.ps1
$env:ANDROID_SERIAL = $device.Serial
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PwebUiEnabled=true
.\gradlew.bat :app:connectedDebugAndroidTest -PwebUiEnabled=true
```

预期：离线加载、请求审计、握手、重复命令保护、reload 连续性和真实 renderer 降级均在 Pixel 6 模拟器通过；测试进程不崩溃，且没有真实 BLE、网络或前台服务副作用。

- [ ] **Step 7: 提交宿主集成测试**

```powershell
git add -p app/build.gradle.kts
git add -p app/src/main/java/com/healthyagent/running/HealthyAgentApp.kt app/src/main/java/com/healthyagent/running/ui/web/HealthyWebViewClient.kt
git add app/src/main/java/com/healthyagent/running/di/AppKoinBootstrap.kt app/src/main/java/com/healthyagent/running/ui/web/RequestAuditSink.kt app/src/androidTest/java/com/healthyagent/running app/src/androidTest/assets/web scripts/resolve-pixel6-device.ps1
git diff --cached --name-only
git commit -m "test: 覆盖 WebView 安全与连续性"
```

- [ ] **阶段三审查点**

执行一次范围明确的代码审查，检查 origin 过滤、Bridge 方向、listener 清理、握手超时取消、WebView 销毁、去重持久性、权限所有权，以及所有可能停止活动跑步的路径。

---

## 阶段四：三屏 React 产品界面

### Task 14：建立 HashRouter、应用外壳和基础设计 token

**Files:**

- Create: `frontend/src/app/routes.tsx`
- Create: `frontend/src/app/routes.test.tsx`
- Create: `frontend/src/app/AppLayout.tsx`
- Create: `frontend/src/app/AppNavigation.tsx`
- Create: `frontend/src/app/ErrorBoundary.tsx`
- Create: `frontend/src/features/running/RunningPage.tsx`
- Create: `frontend/src/features/history/HistoryPage.tsx`
- Create: `frontend/src/features/settings/SettingsPage.tsx`
- Create: `frontend/src/design-system/tokens.css`
- Create: `frontend/src/design-system/themes.css`
- Create: `frontend/src/design-system/global.css`
- Create: `frontend/src/design-system/primitives/Surface.module.css`
- Create: `app/src/androidTest/java/com/healthyagent/running/ui/web/WebNavigationTest.kt`
- Modify: `frontend/src/app/App.tsx`
- Modify: `frontend/src/main.tsx`

**Interfaces:**

- 产出 route `#/run`、`#/history`、`#/settings`；未知 route 重定向到 `#/run`。
- `RunningPage` 是初始 eager route；History 与 Settings 分别构建为 lazy chunk。
- 导航标签为“跑步 / 记录 / 设置”，暴露 `aria-current="page"`，并遵循 safe-area inset。

- [ ] **Step 1: 写路由失败测试**

单独导出 route 对象，让测试可使用 `createMemoryRouter` 且不耦合浏览器 hash。必须覆盖：

```tsx
it.each([
  ['/run', '跑步'],
  ['/history', '跑步记录'],
  ['/settings', '设置'],
])('renders %s', async (path, heading) => {
  render(<TestRouter initialEntry={path} />);
  expect(await screen.findByRole('heading', { name: heading })).toBeVisible();
});

it('redirects unknown paths to run', async () => {
  render(<TestRouter initialEntry="/unknown" />);
  expect(await screen.findByRole('heading', { name: '跑步' })).toBeVisible();
});
```

预期：失败，因为 routes 尚不存在。

- [ ] **Step 2: 实现最小三路由外壳**

仅在 production `App` 使用 `HashRouter`；route 定义保持可测试。用一个稳定的本地 skeleton 包住 lazy 页面。底部导航始终挂载，路由变化不重建 Bridge provider。

使用语义化 HTML（`main`、`nav`、`section`、heading），并把 viewport 设为 `viewport-fit=cover`。本任务不加入动画。

- [ ] **Step 3: 建立基础语义 token**

只定义语义变量，不写 feature 专用的十六进制颜色：

```css
:root {
  --space-1: 0.25rem;
  --space-2: 0.5rem;
  --space-3: 0.75rem;
  --space-4: 1rem;
  --space-6: 1.5rem;
  --radius-sm: 0.75rem;
  --radius-md: 1.125rem;
  --radius-lg: 1.5rem;
  --tap-min: 3rem;
  --metric-font: ui-monospace, "SFMono-Regular", Consolas, monospace;
  font-synthesis: none;
}
```

全局 CSS 使用系统字体、等宽数字、focus-visible ring、`min-height: 100dvh` 和 safe-area padding；不含远程 import。

- [ ] **Step 4: 验证路由拆包**

```powershell
Push-Location .\frontend
corepack pnpm test:unit -- routes.test.tsx
corepack pnpm build
corepack pnpm verify:bundle
Pop-Location
```

预期：三条 route 通过；Vite manifest 显示独立 history/settings chunk；初始 JS 仍在预算内。

- [ ] **Step 5: 在真实 WebView 验证 Android Back**

安装包含新路由的 bundle，在真实 WebView 依次进入 `#/run`、`#/history`、`#/settings`，分别断言 heading 与精确 URL；`RequestAuditSink` 证明 history/settings lazy chunk 都从 `/assets/web/` 加载。执行 settings → history → run 的 Android Back 链，断言顺序恢复且 `TestAppProbe` 中 runtime、repository 与 foreground 计数均未改变。再从 `#/run` 按 Back，确认交给 Activity/系统处理而不是发出 `run.stop`。

```powershell
$device = & .\scripts\resolve-pixel6-device.ps1
$env:ANDROID_SERIAL = $device.Serial
.\gradlew.bat :app:connectedDebugAndroidTest -PwebUiEnabled=true "-Pandroid.testInstrumentationRunnerArguments.class=com.healthyagent.running.ui.web.WebNavigationTest"
```

- [ ] **Step 6: 提交应用外壳**

```powershell
git add frontend/src/app frontend/src/features frontend/src/design-system frontend/src/main.tsx
git add app/src/androidTest/java/com/healthyagent/running/ui/web/WebNavigationTest.kt
git diff --cached --name-only
git commit -m "feat: 建立流萤 React 三屏外壳"
```

### Task 15：实现跑步主屏与完整命令状态

**Files:**

- Create: `frontend/src/features/running/formatMetrics.ts`
- Create: `frontend/src/features/running/formatMetrics.test.ts`
- Create: `frontend/src/features/running/useRunCommands.ts`
- Create: `frontend/src/features/running/RunningPage.module.css`
- Create: `frontend/src/features/running/RunningPage.test.tsx`
- Create: `frontend/src/features/running/components/ConnectionPill.tsx`
- Create: `frontend/src/features/running/components/HeroMetric.tsx`
- Create: `frontend/src/features/running/components/MetricGrid.tsx`
- Create: `frontend/src/features/running/components/HeartRateZone.tsx`
- Create: `frontend/src/features/running/components/CoachCard.tsx`
- Create: `frontend/src/features/running/components/RunControls.tsx`
- Modify: `frontend/src/features/running/RunningPage.tsx`

**Interfaces:**

```ts
export function formatPace(secPerKm: number | null): string;
export function formatDuration(totalSeconds: number): string;
export function formatDistance(meters: number): string;

export type RunCommandName =
  | 'device.connect'
  | 'device.disconnect'
  | 'run.start'
  | 'run.pause'
  | 'run.resume'
  | 'run.stop';

export interface RunCommandState {
  pending: ReadonlySet<RunCommandName>;
  error: string | null;
  execute(name: RunCommandName): Promise<void>;
}
```

- 只使用 `useAppSnapshot` 和 `useCommand`。
- 渲染配速、心率、步频、距离、时长、心率区间、连接状态、教练消息和跑步控件。
- `pending` 按命令名分别跟踪：同名命令 pending 时不重复发送，但 `run.stop` 必须能在 `run.pause`、`run.resume` 或其他非 stop 命令等待期间立即发送。业务值只随随后到达的 result/snapshot 改变。
- hook 为每次用户 action 分配递增 sequence；只有当前最新 action 可以写 `error`。开始 `run.stop` 时使所有更早的非 stop completion 失效，但每个命令仍在自己的 `finally` 清除 pending。这样迟到的 pause rejection 不会覆盖已经完成的 stop。

- [ ] **Step 1: 写 formatter 失败测试**

```ts
expect(formatPace(null)).toBe('--:--');
expect(formatPace(359.6)).toBe('6:00');
expect(formatDuration(0)).toBe('00:00');
expect(formatDuration(3_661)).toBe('1:01:01');
expect(formatDistance(1_234)).toBe('1.23');
```

使用不会显示 `5:60` 的舍入方式；负数和非有限输入渲染占位符。

- [ ] **Step 2: 写关键状态的组件失败测试**

必须覆盖的快照状态与操作：

- disconnected idle：启用“连接设备”，禁用 start；
- `permissions.runningSensors != 'granted'`：优先显示“授权传感器”，发送一条 `permission.request { kind: 'runningSensors' }`；授权结果只等原生 event/snapshot，不乐观改权限；
- connected idle：启用“开始跑步”；
- running：显示 pause 与 end，隐藏 start；
- paused：显示 resume 与 end；
- stale：保留最后数值，并显示“数据暂时中断”；
- 命令被拒绝：中文错误保留到下一次用户操作；
- 点击 stop：弹出确认对话框；取消不发送，确认只发送一次 `run.stop`；
- pause result 尚未返回时确认 stop：仍立即发送一次 `run.stop`，两个 pending 状态独立结束；
- pause rejection 延迟到 stop accepted/finished 之后：清除 pause pending，但不显示陈旧错误、不改变 finished 快照；
- coach message 以 ID 作为语义身份，不因无关 snapshot revision 重播。

- [ ] **Step 3: 实现无布局跳动的指标层**

主配速使用 `font-variant-numeric: tabular-nums`、固定最小字符宽度和 `aria-live="off"`；教练卡使用 polite live region；安全告警使用 assertive live region。颜色始终配合区间文字。每个控件的 `min-inline-size` 和 `min-block-size` 都为 `var(--tap-min)`。

此任务暂不使用 ReactBits，也不对每秒心率或配速做动画。

- [ ] **Step 4: 实现 command pending 与结果处理**

在 `BridgeClient.command` 前登记 `{ name, actionSequence }` pending，并在 `finally` 清除对应 name。收到 accepted 时绝不修改 Zustand 快照；rejected 只有 sequence 仍为最新时才写 error。只禁用与当前 pending 冲突的命令；其他非停止命令 pending 时，紧急停止仍须可用。

- [ ] **Step 5: 验证跑步页**

```powershell
Push-Location .\frontend
corepack pnpm test:unit -- formatMetrics.test.ts RunningPage.test.tsx
corepack pnpm typecheck
corepack pnpm build
Pop-Location
```

预期：全部状态/操作用例通过，Testing Library 测量中主指标没有布局跳动。

- [ ] **Step 6: 提交跑步主屏**

```powershell
git add frontend/src/features/running
git diff --cached --name-only
git commit -m "feat: 实现 React 跑步主屏"
```

### Task 16：实现原生分页驱动的历史页

**Files:**

- Create: `frontend/src/features/history/useHistoryPage.ts`
- Create: `frontend/src/features/history/HistoryPage.module.css`
- Create: `frontend/src/features/history/HistoryPage.test.tsx`
- Create: `frontend/src/features/history/components/RunSummaryCard.tsx`
- Create: `frontend/src/features/history/components/SplitTable.tsx`
- Modify: `frontend/src/features/history/HistoryPage.tsx`
- Modify: `frontend/src/bridge/contract.ts`
- Modify: `frontend/src/bridge/schemas.ts`

**Interfaces:**

```ts
export interface HistoryPagePayload {
  page: number;
  pageSize: number;
  totalCount: number;
  items: Array<{
    id: string;
    startTimeMillis: number;
    totalDistanceMeters: number;
    totalDurationSec: number;
    avgHeartRate: number | null;
    avgCadence: number | null;
    avgPaceSecPerKm: number | null;
    splits: Array<{
      index: number;
      durationSec: number;
      avgHeartRate: number | null;
      avgCadence: number | null;
      avgPaceSecPerKm: number;
    }>;
  }>;
}
```

- UI page size 固定为 10。
- 展开卡片 ID 属于 React 本地状态，绝不进入 AppSnapshot。
- 再次进入 route 时重新查询 Kotlin；Web 侧不建立持久数据库或缓存。

- [ ] **Step 1: 写加载、空、错误和分页失败测试**

测试必须断言初始 `history.query { page: 1, pageSize: 10 }`、skeleton、空状态文案、重试、前后页边界、页面替换和本地展开/折叠。旧请求的迟到结果不能覆盖较新的页面。

- [ ] **Step 2: 扩充结果 schema 并实现 hook**

解析 typed command promise 前，使用 Zod 校验完整 accepted payload。`useHistoryPage` 持有 `requestSequence`；只有最新序列可以提交页面状态。Abort 只在逻辑层发生——原生查询可以完成，但迟到数据被忽略。

- [ ] **Step 3: 实现可读记录卡与分段表**

使用 `<time dateTime>`、表头、本地化日期格式，以及和跑步页相同的纯指标 formatter。不加载全部分页；Task 18 前不加入动效。

- [ ] **Step 4: 验证历史页和 lazy chunk**

```powershell
Push-Location .\frontend
corepack pnpm test:unit -- HistoryPage.test.tsx
corepack pnpm build
corepack pnpm verify:bundle
Pop-Location
```

预期：分页测试通过；历史页仍是独立 route chunk。

- [ ] **Step 5: 提交历史页**

```powershell
git add frontend/src/features/history frontend/src/bridge/contract.ts frontend/src/bridge/schemas.ts
git diff --cached --name-only
git commit -m "feat: 实现分页跑步历史页"
```

### Task 17：实现设置、双主题选择与权限请求页

**Files:**

- Create: `frontend/src/features/settings/settingsForm.ts`
- Create: `frontend/src/features/settings/settingsForm.test.ts`
- Create: `frontend/src/features/settings/SettingsPage.module.css`
- Create: `frontend/src/features/settings/SettingsPage.test.tsx`
- Create: `frontend/src/features/settings/components/SettingSection.tsx`
- Create: `frontend/src/features/settings/components/SecretField.tsx`
- Create: `frontend/src/features/settings/components/SegmentedChoice.tsx`
- Modify: `frontend/src/features/settings/SettingsPage.tsx`
- Modify: `frontend/src/app/providers.tsx`

**Interfaces:**

- 表单使用脱敏快照值，只维护尚未保存的本地 draft。
- 提交时发出最小 `settings.update` patch；省略未变字段，清空目标使用显式 `null`，未触碰的密钥不发送。
- 权限按钮发送 `permission.request { kind: 'runningSensors' }`；命令 `Accepted` 只表示原生已接收请求，不能乐观地视为授权成功。页面分别展示蓝牙、身体传感器和聚合 runningSensors 状态，且只随 `event { name: 'permission.result', requestId, kind: 'runningSensors', granted }` 与后续 snapshot 改变。
- 设置页只消费与当前 pending `requestId`、`kind` 同时匹配的 `permission.result`；错误 ID、错误 kind、重复或过期事件一律忽略并写入测试审计。拒绝结果清除 pending、保留未授权状态并显示可再次请求的说明；snapshot 仍是最终真值。
- Theme provider 应用原生快照中的 `data-theme="morning|night"`，绝不在本地持久化。

- [ ] **Step 1: 写 patch builder 失败测试**

```ts
it('omits unchanged values and untouched secrets', () => {
  expect(buildSettingsPatch(snapshotSettings, draftFrom(snapshotSettings))).toEqual({});
});

it('uses null to clear a target', () => {
  const draft = { ...draftFrom(snapshotSettings), targetDistanceKm: '' };
  expect(buildSettingsPatch(snapshotSettings, draft)).toEqual({ targetDistanceKm: null });
});

it('does not retain a submitted API key', () => {
  const next = clearSecretDrafts({ ...draft, llmApiKey: 'secret-value' });
  expect(next.llmApiKey).toBe('');
});
```

- [ ] **Step 2: 写页面失败测试**

覆盖数字校验、空 patch 时禁用保存、accepted 后等待新快照、rejected 保存错误、不含密钥值的 configured badge、coach tone、五种 mock scenario、morning/night/system、五种 motion preference，以及权限请求 pending/result。权限测试必须覆盖 Accepted 后仍保持 pending、匹配的授权/拒绝事件、事件后 snapshot 对账、错误 ID/kind、重复事件和允许再次请求。

断言提交后渲染 DOM 和测试日志都不包含输入的密钥文本。

- [ ] **Step 3: 实现受控表单和主题应用**

使用可访问的 label 和描述。密钥输入始终为空起始值，configured 状态单独显示。提交 accepted 后立即清空密钥 draft，但保留非密钥 draft，直到对应原生快照到达；若新快照值不同，以原生为准并重置表单。

使用 layout effect 应用已解析主题，避免第二次绘制闪烁。表单只在原生快照确认后改变主题，不做本地持久化，也不绕过 Bridge。通过 `BridgeClient.subscribeEvent` 订阅 `permission.result`，卸载时释放订阅；事件只结束对应 pending，最终权限展示始终用最新 snapshot 对账。

- [ ] **Step 4: 验证设置页**

```powershell
Push-Location .\frontend
corepack pnpm test:unit -- settingsForm.test.ts SettingsPage.test.tsx
corepack pnpm typecheck
corepack pnpm build
corepack pnpm verify:bundle
Pop-Location
```

预期：全部设置和权限用例通过；bundle 密钥扫描通过。

- [ ] **Step 5: 提交设置页**

```powershell
git add frontend/src/features/settings frontend/src/app/providers.tsx
git diff --cached --name-only
git commit -m "feat: 实现流萤设置与权限页"
```

- [ ] **阶段四审查点**

执行一次范围明确的代码审查，检查命令/结果竞态、乐观业务状态写入、密钥生命周期、焦点顺序、停止确认、数据陈旧行为、分页竞态和 route 拆包。

---

## 阶段五：真实 ReactBits、双主题与动效预算

### Task 18：以可追溯源码 vendoring ReactBits

**Files:**

- Create: `frontend/src/vendor/reactbits/LICENSE.md`
- Create: `frontend/src/vendor/reactbits/UPSTREAM.md`
- Create: `frontend/src/vendor/reactbits/Particles/Particles.tsx`
- Create: `frontend/src/vendor/reactbits/Particles/Particles.css`
- Create: `frontend/src/vendor/reactbits/Particles/SOURCE.md`
- Create: `frontend/src/vendor/reactbits/SoftAurora/SoftAurora.tsx`
- Create: `frontend/src/vendor/reactbits/SoftAurora/SoftAurora.css`
- Create: `frontend/src/vendor/reactbits/SoftAurora/SOURCE.md`
- Create: `frontend/src/vendor/reactbits/CountUp/CountUp.tsx`
- Create: `frontend/src/vendor/reactbits/CountUp/SOURCE.md`
- Create: `frontend/src/vendor/reactbits/BlurText/BlurText.tsx`
- Create: `frontend/src/vendor/reactbits/BlurText/SOURCE.md`
- Create: `frontend/src/vendor/reactbits/BorderGlow/BorderGlow.tsx`
- Create: `frontend/src/vendor/reactbits/BorderGlow/BorderGlow.css`
- Create: `frontend/src/vendor/reactbits/BorderGlow/SOURCE.md`
- Create: `frontend/src/vendor/reactbits/FadeContent/FadeContent.tsx`
- Create: `frontend/src/vendor/reactbits/FadeContent/SOURCE.md`
- Create: `frontend/src/vendor/reactbits/ShinyText/ShinyText.tsx`
- Create: `frontend/src/vendor/reactbits/ShinyText/ShinyText.css`
- Create: `frontend/src/vendor/reactbits/ShinyText/SOURCE.md`
- Create: `frontend/scripts/verify-reactbits-provenance.mjs`
- Create: `frontend/scripts/verify-reactbits-provenance.test.mjs`
- Modify: `frontend/package.json`
- Modify: `frontend/pnpm-lock.yaml`

**Interfaces:**

- 将上游仓库 `https://github.com/DavidHDev/react-bits` 固定到 commit `1ba7ecd4e570d20392fb62c923efb00f5b440642`。
- `UPSTREAM.md` 记录 repository、commit、获取日期和上游 `LICENSE.md` 的精确位置；许可证 Git blob SHA-1 固定为 `6425315416e94469f28d0223a09f7285b2f785ab`。
- 每份 `SOURCE.md` 记录组件来源 URL、下方列出的原始 SHA-1、本地修改和必要依赖。

| Component | Upstream TSX blob SHA-1 | Upstream CSS blob SHA-1 | Runtime dependency |
|---|---|---|---|
| Particles | `e240ac6912766c874bc3d9775bba799e1df64fab` | `5ec1fa1f3b874aebc2a3dc99b277eaf666a448f4` | `ogl` |
| SoftAurora | `571ca23ffae28bb7c13fa57ad4a3ae1d235d4d91` | `8a04c9ee8fd6f9f25c890b67d39575ca9a20f320` | `ogl` |
| CountUp | `1723cfd1d55c39b303738f3bc2f03be2c10bab5d` | — | `motion/react` |
| BlurText | `a7765cd98025d3de4d17903aa574d033210e626f` | — | `motion/react` |
| BorderGlow | `413988e5f68e1fdffad1a737bd720244e8ade332` | `b4b9d93d3f9080c2779ea0aa4361f4bda31ef0d1` | React/CSS |
| FadeContent | `c00adbf9b1eaf41872c6fab2c95400c11d81fdb7` | — | `gsap`, `gsap/ScrollTrigger` |
| ShinyText | `820878e3ba43298e061d94f30bf471e976627ada` | `49b444d18f9f2f6b57ce1fe302f9957b9cf2d7ea` | React/CSS |

- [ ] **Step 1: 写 provenance verifier 失败测试**

Verifier 解析每个 `SOURCE.md`，要求 `repository`、`commit`、每个上游文件 blob SHA、每个本地文件 blob SHA 与 `localChanges`，检查固定 allowlist 和源文件存在性，并拒绝未声明的 vendor 目录。按 Git blob 公式 `sha1("blob " + byteLength + "\0" + bytes)` 实际计算每个 TSX/CSS/LICENSE 的本地 bytes；未修改文件必须等于上游 hash，允许的 lifecycle patch 必须等于 `SOURCE.md` 中实施后记录的 local hash。使用 `node:test` 覆盖合法树、缺少 SHA、文件被篡改和漏列 CSS/LICENSE。

- [ ] **Step 2: 从固定 commit 获取官方默认 TypeScript 实现**

从固定 commit 复制精确的上游组件源码和对应 CSS，并复制原样的上游 `LICENSE.md`；不得依据仓库元数据改写许可名称。本地修改前计算 Git blob SHA-1 并与表格比较；任何 hash 不符都停止并重新审计，不能静默使用新版源码。

vendor 文件只允许以下本地修改，并在应用 patch 前先验证表中 upstream bytes：

- 修正相对 import path；
- 增加 wrapper 必需的显式 exported prop type；
- `BorderGlow` 为 timer/RAF 增加 unmount/visibility cleanup；
- `Particles` 在 cleanup 中调用 `WEBGL_lose_context`（存在时）；
- 不加入产品颜色、文案、默认数量或安全策略。

在对应组件的 `SOURCE.md` 记录每一项修改。

- [ ] **Step 3: 安装固定运行时依赖**

加入以下精确版本：

```json
{
  "motion": "12.23.12",
  "ogl": "1.0.11",
  "gsap": "3.13.0"
}
```

进入 `frontend` 后运行 `corepack pnpm install` 更新 lockfile，再返回仓库根目录。除所选官方组件需要的依赖外，不添加通用动画框架。

- [ ] **Step 4: 验证来源与直接 import 禁令**

使用 TypeScript AST 解析并 resolve 静态 import、re-export 与动态 `import()` specifier：若 `src/motion/**` 之外的文件最终解析到 `src/vendor/reactbits` 即失败；不能只搜索字面路径。运行：

```powershell
node --test frontend/scripts/verify-reactbits-provenance.test.mjs
Push-Location .\frontend
corepack pnpm exec node scripts/verify-reactbits-provenance.mjs
corepack pnpm typecheck
Pop-Location
```

预期：七个组件的来源全部验证通过；没有 feature 页面直接 import vendor 代码。

- [ ] **Step 5: 提交 vendored 源码**

```powershell
git add frontend/package.json frontend/pnpm-lock.yaml frontend/src/vendor/reactbits frontend/scripts/verify-reactbits-provenance.mjs frontend/scripts/verify-reactbits-provenance.test.mjs
git diff --cached --name-only
git commit -m "feat: 引入可追溯 ReactBits 源码"
```

### Task 19：实现 Firefly Motion System 与晨萤/夜萤主题

**Files:**

- Create: `frontend/src/motion/MotionProvider.tsx`
- Create: `frontend/src/motion/motionPolicy.ts`
- Create: `frontend/src/motion/motionPolicy.test.ts`
- Create: `frontend/src/motion/FireflyBackdrop.tsx`
- Create: `frontend/src/motion/CoachReveal.tsx`
- Create: `frontend/src/motion/MetricTransition.tsx`
- Create: `frontend/src/motion/MilestoneShimmer.tsx`
- Create: `frontend/src/motion/PageReveal.tsx`
- Create: `frontend/src/motion/MotionProvider.test.tsx`
- Create: `frontend/src/motion/ReactBitsLifecycle.test.tsx`
- Modify: `frontend/src/design-system/themes.css`
- Modify: `frontend/src/features/running/components/CoachCard.tsx`
- Modify: `frontend/src/features/running/components/HeroMetric.tsx`
- Modify: `frontend/src/features/running/RunningPage.tsx`
- Modify: `frontend/src/features/history/HistoryPage.tsx`
- Modify: `frontend/src/features/settings/SettingsPage.tsx`

**Interfaces:**

```ts
export type MotionLevel = 'expressive' | 'balanced' | 'reduced' | 'off';

export interface RuntimeMotionInputs {
  nativePolicy: MotionLevel;
  prefersReducedMotion: boolean;
  documentVisible: boolean;
  emergency: boolean;
  activeRun: boolean;
}

export function resolveMotionLevel(inputs: RuntimeMotionInputs): MotionLevel;
```

- `FireflyBackdrop` chooses either `Particles` or `SoftAurora`, never both.
- `CoachReveal` 封装 `BlurText` 与单次 `BorderGlow`，只在 `sessionStorage` 记录最近播放的 coach UUID，使普通 WebView reload 不重播；不得持久化消息正文。
- `MetricTransition` 只对 summary/milestone 数值使用 `CountUp`，绝不用于逐秒配速或心率。
- `PageReveal` 延迟加载 `FadeContent`；GSAP 必须留在初始 running chunk 之外。
- `MilestoneShimmer` wraps one cycle of `ShinyText` only for split/goal events.

- [ ] **Step 1: 写优先级矩阵失败测试**

```ts
it.each([
  [{ emergency: true }, 'off'],
  [{ documentVisible: false }, 'off'],
  [{ prefersReducedMotion: true }, 'off'],
  [{ nativePolicy: 'off' }, 'off'],
  [{ activeRun: true, nativePolicy: 'expressive' }, 'reduced'],
  [{ activeRun: false, nativePolicy: 'expressive' }, 'expressive'],
])('resolves %o to %s', (overrides, expected) => {
  expect(resolveMotionLevel({ ...defaults, ...overrides })).toBe(expected);
});
```

同时测试粒子上限：expressive 24、balanced 18、reduced 12、off 0；reduced/off 的 pointer interaction 为 false。

- [ ] **Step 2: 写语义 wrapper 失败测试**

Mock vendor 组件并断言：

- off 不挂载 Particles/SoftAurora；
- 任一级别都不能同时挂载两者；
- 同一 coach ID 立即渲染，不触发第二次 BlurText 入场；使用同一 `sessionStorage` 重新挂载 provider 后也一样；
- 新 coach ID 在 450 ms 内完成 reveal；
- 活动跑步的指标更新不挂载 CountUp；
- PageReveal 仅在进入 history/settings 时动态 import FadeContent；
- 使用真实、未 mock 的 BorderGlow 在动画中途 visibility hidden/unmount 后，pending timer/RAF 为 0 且不再操作 detached element；
- 使用真实 Particles 反复 mount/unmount 后 canvas 为 0，并通过 fake OGL extension 断言每个 context 都调用一次 `WEBGL_lose_context`；
- `visibilitychange` to hidden unmounts the continuous layer.

- [ ] **Step 3: 实现双主题 token**

在两个 selector 中定义同一组语义变量：

```css
:root[data-theme='morning'] {
  color-scheme: light;
  --color-bg: #f7f4eb;
  --color-surface: rgba(255, 255, 252, 0.82);
  --color-text: #17211d;
  --color-muted: #617068;
  --color-firefly: #75d9a6;
  --color-violet: #8d82b8;
  --glow-alpha: 0.16;
}

:root[data-theme='night'] {
  color-scheme: dark;
  --color-bg: #100f17;
  --color-surface: rgba(28, 26, 39, 0.78);
  --color-text: #f3f4ed;
  --color-muted: #a7aca4;
  --color-firefly: #7ce7ad;
  --color-violet: #9e91d6;
  --glow-alpha: 0.28;
}
```

验证普通文字、弱化文字、focus ring 和主控件满足 WCAG AA 对比度。浅色主题必须使用更低 glow alpha，并保持相同组件结构。整页只能由根节点切换 morning/night，section 不得自行反色；`--color-firefly` 是唯一交互/状态强调色，`--color-violet` 只允许作为低对比环境层，不能用于 CTA、权限或跑步状态。卡片、输入与按钮遵循 Task 14 的同一圆角层级规则。

- [ ] **Step 4: 实现四级 MotionProvider**

组合原生上限、`matchMedia('(prefers-reduced-motion: reduce)')`、`document.visibilityState`、emergency 和 active-run 状态。listener 只挂载一次并正确清理。`off` 返回静态 wrapper，绝不实例化 WebGL/Canvas。

对 OGL 和 GSAP wrapper 使用 `React.lazy`；Suspense fallback 为静态且视觉完整。

- [ ] **Step 5: 接入事件动效而不改变业务行为**

- Running：一个背景、静态每秒指标、按稳定 ID 展示教练消息、连接 glow 只扫一次。
- History：首次进入使用 FadeContent，CountUp 只用于展开的 summary。
- Settings：首次进入使用 FadeContent；自身没有持续背景。
- Split/goal：按原生 event/message ID 只运行一轮 ShinyText，绝不循环。

所有时长和数量都集中在 `src/motion`，不散落到 feature 页面。

- [ ] **Step 6: 验证动效边界和 bundle**

```powershell
Push-Location .\frontend
corepack pnpm test:unit -- motionPolicy.test.ts MotionProvider.test.tsx ReactBitsLifecycle.test.tsx
corepack pnpm lint
corepack pnpm typecheck
corepack pnpm build
corepack pnpm verify:bundle
Pop-Location
```

预期：motion matrix 通过；初始 chunk 不包含 GSAP 字符串；最多一个持续层；预算通过。

- [ ] **Step 7: 提交主题与语义动效**

```powershell
git add frontend/src/motion frontend/src/design-system/themes.css frontend/src/features
git diff --cached --name-only
git commit -m "feat: 实现晨萤夜萤与低调 ReactBits 动效"
```

### Task 20：建立视觉回归、无障碍和性能验收

**Files:**

- Create: `frontend/playwright.config.ts`
- Create: `frontend/e2e/support/installBridgeMock.ts`
- Create: `frontend/e2e/routes.spec.ts`
- Create: `frontend/e2e/themes.spec.ts`
- Create: `frontend/e2e/motion.spec.ts`
- Create: `frontend/e2e/accessibility.spec.ts`
- Create: `frontend/e2e/performance.spec.ts`
- Create: `frontend/e2e/__screenshots__/morning-run.png`
- Create: `frontend/e2e/__screenshots__/night-run.png`
- Create: `frontend/e2e/__screenshots__/reduced-run.png`
- Create: `app/src/androidTest/java/com/healthyagent/running/ui/web/WebPerformanceTest.kt`
- Modify: `frontend/package.json`

**Interfaces:**

- Mock Bridge 复用生产 Zod schema 和 fixture builder，不能另造合同字段。
- Playwright 使用固定的 Pixel 6 等效 viewport、locale `zh-CN`、timezone `Asia/Singapore`、受控动画时钟和确定性粒子种子。
- 性能测试记录 visible-ready 时间、long task、持续层数量、后台 RAF 数量和触控 pending 延迟。

- [ ] **Step 1: 写 deterministic bridge mock**

添加 dev dependency `"@axe-core/playwright": "4.10.2"` 并更新冻结 lockfile。无障碍断言使用该包，并补充显式目标尺寸和焦点顺序检查。

`playwright.config.ts` 必须自带可重复启动的 preview server，使 `corepack pnpm test:e2e` 不依赖人工保留的 dev server：

```ts
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  snapshotPathTemplate: '{testDir}/__screenshots__/{arg}{ext}',
  projects: [{ name: 'chromium-pixel6', use: { browserName: 'chromium' } }],
  webServer: {
    command: 'corepack pnpm preview',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: false,
    timeout: 120_000,
  },
  use: {
    baseURL: 'http://127.0.0.1:4173',
    viewport: { width: 412, height: 915 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Singapore',
  },
});
```

package 脚本先运行 `pnpm build` 再启动 Playwright；preview 固定 `127.0.0.1:4173 --strictPort`，本地与 CI 都不复用 dev/旧 build server。单 Chromium project、单 worker 串行运行，避免性能/WebGL/截图互相争抢 CPU/GPU。截图路径模板与 Files 中的 `e2e/__screenshots__/*.png` 精确一致；基线只在固定 Windows/Chromium 环境更新，避免系统字体跨平台漂移。

Mock 初始为 disconnected/idle，处理全部九条命令、递增 revision、返回分页 fixture、确认设置但不回显密钥，并可模拟 stale data/emergency。使用 `page.addInitScript` 在应用代码前安装。

在 E2E support 安装全局 request audit：监听 `page.on('request')`，只允许 `http://127.0.0.1:4173` 同源 URL 以及 `data:`/`blob:`；任何其他 scheme/host 都记录并让用例失败。三 route、两主题、motion/GSAP lazy chunk 均在该审计下执行，作为 Task 2 静态引用检查的运行时补充。

- [ ] **Step 2: 写三路由主流程 E2E**

覆盖：

1. run: connect → start → pause → resume → confirm stop;
2. history: first page → expand split → next page → Back;
3. settings: theme/motion/tone update → permission request → native-confirmed snapshot.

每一步都等待命令结果和 revision 前进，不使用任意时长等待。

- [ ] **Step 3: 写晨萤、夜萤和 Reduced Motion 截图**

固定时间戳、指标和随机种子。在 412 × 915 CSS px 下断言截图。Reduced 模式不能出现 canvas；普通模式最多恰好一个 canvas。

`motion.spec.ts` 反复切换 emergency/off、visibility 和晨萤/夜萤并卸载背景；每轮结束断言 canvas 为 0，注入的 context-lost/RAF/timer probe 证明资源成对释放，不能只依赖 mocked vendor 单测。

- [ ] **Step 4: 写无障碍和性能断言**

无障碍测试覆盖键盘焦点可见性、heading 层级、48 px 目标、label、live region 和非颜色状态文本。性能断言：

- 初始 JS gzip ≤ 450 KiB；
- Web 静态资源总量 ≤ 2.5 MiB；
- 在指定 Pixel 6 模拟器基线上，visible shell ≤ 1.5 s；
- 触控后 pending UI ≤ 100 ms 出现；
- 10 分钟加速 mock-run trace 中不出现 ≥ 50 ms 的主线程任务；
- 后台运行 5 秒时，装饰性 RAF callback 数为 0。

- [ ] **Step 5: 运行浏览器与 Android 性能套件**

```powershell
Push-Location .\frontend
corepack pnpm exec playwright install chromium
corepack pnpm test:e2e
corepack pnpm verify:bundle
Pop-Location
$device = & .\scripts\resolve-pixel6-device.ps1
$env:ANDROID_SERIAL = $device.Serial
.\gradlew.bat :app:connectedDebugAndroidTest -PwebUiEnabled=true "-Pandroid.testInstrumentationRunnerArguments.class=com.healthyagent.running.ui.web.WebPerformanceTest"
```

预期：routes、三个视觉基线、Reduced Motion、无障碍和全部性能预算通过。

- [ ] **Step 6: 提交验收资产**

```powershell
git add frontend/package.json frontend/playwright.config.ts frontend/e2e app/src/androidTest/java/com/healthyagent/running/ui/web/WebPerformanceTest.kt
git diff --cached --name-only
git commit -m "test: 固化流萤视觉与性能验收"
```

- [ ] **阶段五审查点**

执行一次范围明确的代码审查，检查 ReactBits 来源、许可保留、仅 wrapper import、reduced-motion 优先级、WebGL 清理、无障碍、截图确定性和 bundle 拆分。

---

## 阶段六：默认切换、清理与模拟器交付

### Task 21：全量验收后删除普通 Compose UI

**Files:**

- Modify: `app/src/main/java/com/healthyagent/running/ui/AppRoot.kt`
- Modify: `app/src/main/java/com/healthyagent/running/MainActivity.kt`
- Modify: `app/src/main/java/com/healthyagent/running/di/AppModule.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/healthyagent/running/data/repository/RunRepository.kt`
- Modify: `app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorTest.kt`
- Modify: `app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorHistorySettingsTest.kt`
- Delete: `app/src/main/java/com/healthyagent/running/ui/navigation/AppNavHost.kt`
- Delete: `app/src/main/java/com/healthyagent/running/ui/running/RunningScreen.kt`
- Delete: `app/src/main/java/com/healthyagent/running/ui/running/RunningViewModel.kt`
- Delete: `app/src/main/java/com/healthyagent/running/ui/running/RunningDisplayState.kt`
- Delete: `app/src/main/java/com/healthyagent/running/ui/history/HistoryScreen.kt`
- Delete: `app/src/main/java/com/healthyagent/running/ui/history/HistoryViewModel.kt`
- Delete: `app/src/main/java/com/healthyagent/running/ui/settings/SettingsScreen.kt`
- Delete: `app/src/main/java/com/healthyagent/running/ui/settings/SettingsViewModel.kt`
- Delete: `app/src/main/java/com/healthyagent/running/ui/components/ActionButtons.kt`
- Delete: `app/src/main/java/com/healthyagent/running/ui/components/CoachMessageBar.kt`
- Delete: `app/src/main/java/com/healthyagent/running/ui/components/MetricTile.kt`
- Delete: `app/src/main/java/com/healthyagent/running/ui/components/ZoneIndicator.kt`
- Delete: `app/src/test/java/com/healthyagent/running/ui/running/RunningDisplayStateTest.kt`
- Delete: `app/src/test/java/com/healthyagent/running/ui/running/RunningViewModelAdapterTest.kt`
- Delete: `app/src/androidTest/java/com/healthyagent/running/ui/ComposeFallbackContinuityTest.kt`
- Modify: `docs/PROJECT_TRACKER.md`
- Modify: `docs/superpowers/specs/2026-08-26-reactbits-hybrid-frontend-design.md`

**Interfaces:**

- 最终 `AppRoot` 始终承载 React 或原生失败/安全 UI，不再有普通 Compose 导航路径。
- 永久保留 Compose runtime、原生安全/错误界面所需 theme primitive，以及 `NativeSafetyScreen`。
- 只有先前两个 flag 变体和三个 React 页面全部通过后才能移除 `WEB_UI_ENABLED`；在此之前不得开始本任务。

- [ ] **Step 1: 证明删除门槛已满足**

删除前运行完整测试矩阵，并把输出保存到任务日志：

```powershell
$device = & .\scripts\resolve-pixel6-device.ps1
$env:ANDROID_SERIAL = $device.Serial
Push-Location .\frontend
corepack pnpm check
corepack pnpm verify:bundle
corepack pnpm test:e2e
Pop-Location
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.ui.running.RunningViewModelAdapterTest"
.\gradlew.bat :app:assembleDebug -PwebUiEnabled=false
.\gradlew.bat :app:connectedDebugAndroidTest -PwebUiEnabled=false "-Pandroid.testInstrumentationRunnerArguments.class=com.healthyagent.running.ui.ComposeFallbackContinuityTest"
.\gradlew.bat :app:connectedDebugAndroidTest -PwebUiEnabled=true
.\gradlew.bat :app:assembleRelease -PwebUiEnabled=true
```

预期：全部通过。任一命令失败都停止本任务并保留 Compose 回退。

- [ ] **Step 2: 删除普通 Compose 页面与适配 ViewModel**

只删除已列出的文件，并移除其 Koin 注册和 import。让 `AppRoot` 在 `WebAppHost`、空闲原生错误页和活动原生安全页之间选择。保留原生权限处理和安全操作。

由于若干列出文件当前属于用户编写或未跟踪内容，删除前立即逐个执行 `git ls-files --error-unmatch <path>` 分类并检查最终 diff。任何目标仍未跟踪时，立即停止并取得用户明确确认；先把它纳入可恢复的基线提交或创建用户认可的备份，再删除。不能依赖 `git add -u` 恢复从未跟踪文件，也不能因为其他文件看似未使用就顺带删除。

- [ ] **Step 3: 收紧依赖和构建开关**

使用 `rg` 证明 Navigation Compose、ViewModel Compose、Material icons 和 Koin Compose 是否仍被引用。只移除零引用依赖。移除 `WEB_UI_ENABLED` 及其 Gradle override；release/debug 都构建本地 Web UI。保留安全 UI 所需的 WebKit、Activity Compose、Material 3 和 Compose test 依赖。

随 `HistoryViewModel` 一并删除迁移期 `RunRepository.getAllSessions()`，并同步更新 Coordinator 测试 fake；DAO 无界查询已在 Task 8 删除，分页 API 成为唯一历史读取路径。不能删除其他仍被使用的 DAO 方法。

- [ ] **Step 4: 更新文档状态**

把规格状态设为“已实施并验收”。在 `PROJECT_TRACKER.md` 记录架构结果、精确测试命令、ReactBits 上游 commit、剩余已知限制和回滚点（本删除任务之前的 commit）。

- [ ] **Step 5: 删除后重新验证**

```powershell
rg -n "AppNavHost|RunningViewModel|HistoryViewModel|SettingsViewModel|RunningDisplayState|getAllSessions" app/src
Push-Location .\frontend
corepack pnpm check
corepack pnpm verify:bundle
corepack pnpm test:e2e
Pop-Location
$device = & .\scripts\resolve-pixel6-device.ps1
$env:ANDROID_SERIAL = $device.Serial
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:assembleRelease
```

预期：`rg` 无匹配；全部测试和 release assemble 通过。

- [ ] **Step 6: 提交默认切换与清理**

```powershell
git add -u app/src/main/java/com/healthyagent/running/ui/navigation/AppNavHost.kt app/src/main/java/com/healthyagent/running/ui/running/RunningScreen.kt app/src/main/java/com/healthyagent/running/ui/running/RunningViewModel.kt app/src/main/java/com/healthyagent/running/ui/running/RunningDisplayState.kt app/src/main/java/com/healthyagent/running/ui/history/HistoryScreen.kt app/src/main/java/com/healthyagent/running/ui/history/HistoryViewModel.kt app/src/main/java/com/healthyagent/running/ui/settings/SettingsScreen.kt app/src/main/java/com/healthyagent/running/ui/settings/SettingsViewModel.kt app/src/main/java/com/healthyagent/running/ui/components/ActionButtons.kt app/src/main/java/com/healthyagent/running/ui/components/CoachMessageBar.kt app/src/main/java/com/healthyagent/running/ui/components/MetricTile.kt app/src/main/java/com/healthyagent/running/ui/components/ZoneIndicator.kt app/src/test/java/com/healthyagent/running/ui/running/RunningDisplayStateTest.kt app/src/test/java/com/healthyagent/running/ui/running/RunningViewModelAdapterTest.kt app/src/androidTest/java/com/healthyagent/running/ui/ComposeFallbackContinuityTest.kt
git add -p app/build.gradle.kts app/src/main/java/com/healthyagent/running/ui/AppRoot.kt app/src/main/java/com/healthyagent/running/MainActivity.kt app/src/main/java/com/healthyagent/running/di/AppModule.kt
git add -p app/src/main/java/com/healthyagent/running/data/repository/RunRepository.kt app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorTest.kt app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorHistorySettingsTest.kt
git add -p docs/PROJECT_TRACKER.md
git add docs/superpowers/specs/2026-08-26-reactbits-hybrid-frontend-design.md
git diff --cached --name-status
git commit -m "refactor: 默认启用 React 并保留原生安全层"
```

### Task 22：最终全链路验证并在模拟器中展示

**Files:**

- Create: `frontend/README.md`
- Create: `scripts/open-visual-review.ps1`
- Modify: `docs/PROJECT_TRACKER.md`

**Interfaces:**

- README 记录固定版本安装、浏览器 mock 预览、Android bundle 构建、Bridge 边界、来源校验和故障排查。
- 交付同时提供浏览器开发预览与安装在 Pixel 6 模拟器中的 APK；只有模拟器是 WebView 行为的最终依据。
- `open-visual-review.ps1` 只接受 resolver 返回的显式 adb 路径、设备 serial 与 APK 路径，确定性进入 mock 跑步状态、验证可访问性树、保存截图并保持 Activity 前台显示。

- [ ] **Step 1: 写运行说明并验证命令可复制**

记录：

```powershell
Push-Location .\frontend
corepack pnpm install --frozen-lockfile
corepack pnpm exec playwright install chromium
corepack pnpm dev --host 127.0.0.1
# 视觉复核结束后按 Ctrl+C，再执行下一行恢复目录。
Pop-Location
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

注明 `dev` 是前台长驻命令；浏览器模式使用本地 mock Bridge，不能验证 Android 权限、BLE、前台服务或 renderer-process 降级。

- [ ] **Step 2: 锁定唯一 Pixel 6 API 35 模拟器与实现基线**

```powershell
$implementationBase = "<执行前门禁记录的 IMPLEMENTATION_BASE>"
if ((git cat-file -t $implementationBase) -ne "commit") { throw "IMPLEMENTATION_BASE 不是有效提交" }
$device = & .\scripts\resolve-pixel6-device.ps1
$targetEmulator = $device.Serial
$adbPath = $device.AdbPath
$env:ANDROID_SERIAL = $targetEmulator
git diff --check "$implementationBase..HEAD" -- .
```

后续本任务所有 Gradle connected tests 和 adb 命令都复用该精确 serial/绝对 adb 路径；不让 Gradle 自动选择另一台设备。当前机器若仍无 AVD，这一步是明确的“需用户先创建/启动 Pixel 6 API 35 AVD”门禁，不能伪造已完成。

- [ ] **Step 3: 执行最终构建、测试与 APK 资产验证**

```powershell
Push-Location .\frontend
corepack pnpm lint
corepack pnpm format:check
corepack pnpm typecheck
corepack pnpm test:unit
corepack pnpm build
corepack pnpm verify:bundle
corepack pnpm test:e2e
Pop-Location
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:assembleRelease
$releaseApks = @(Get-ChildItem -LiteralPath .\app\build\outputs\apk\release -Filter '*.apk' -File)
if ($releaseApks.Count -ne 1) { throw "预期唯一 release APK，实际为 $($releaseApks.Count) 个" }
$apkEntries = @(& jar tf $releaseApks[0].FullName)
if ($apkEntries -notcontains 'assets/web/index.html') { throw "release APK 缺少 assets/web/index.html" }
if (-not ($apkEntries | Where-Object { $_ -like 'assets/web/assets/*' })) { throw "release APK 缺少 Vite 静态资源" }
```

预期：每条命令退出码为 0；冻结 bundle、密钥扫描、全部测试和 `jar tf` APK 资产断言通过。

- [ ] **Step 4: 用可重复脚本安装、进入 mock 跑步并截图**

`scripts/open-visual-review.ps1` 必须按 AGENTS.md 使用 UTF-8 BOM、中文 `Write-Host`、`$ErrorActionPreference = "Stop"` 和明确 section。脚本执行以下固定流程：

1. 通过 resolver 验证传入 serial 仍是 Pixel 6 API 35，验证 APK 文件存在；
2. 先执行 `install -r`，再 `pm clear com.healthyagent.running` 清除陈旧 DataStore/session，随后授予 debug 视觉流程所需运行时权限；
3. 记录 `wm size`、`wm density`、ABI 以及当前 WebView package/version；
4. 用 `am start -W -n com.healthyagent.running/.MainActivity` 启动并要求 `Status: ok`；
5. 在 15 秒 deadline 内轮询 UIAutomator XML，只有看到 React 跑步页 heading/“连接设备”才认定 app ready；
6. 根据 XML 中目标节点的 bounds 点击“连接设备”与“开始跑步”，等待 mock fixture 出现非占位心率、递增时长和大于零的距离；
7. 在晨萤主题保存 `.superpowers/visual-review/pixel6-morning-run.png`；通过设置页切换夜萤并返回同一活动 session，保存 `pixel6-night-run.png`；
8. 用 `dumpsys activity activities` 断言 `MainActivity` 为 resumed，且不 force-stop 应用。

```powershell
.\scripts\open-visual-review.ps1 -DeviceSerial $targetEmulator -AdbPath $adbPath -ApkPath .\app\build\outputs\apk\debug\app-debug.apk
```

执行后分别用 `view_image` 检查两张截图中的 mock 指标、晨萤/夜萤差异与无遮挡布局；同时让模拟器保持在夜萤活动跑步页供用户直接查看。

- [ ] **Step 5: 打开本地浏览器预览供快速视觉复核**

在可用 localhost 端口启动 Vite，保留其真实运行 session，然后在 Codex 浏览器面板打开终端实际报告的精确 URL。验证晨萤、夜萤、活动跑步 Reduced Motion 和三条 route。URL 是临时的；报告当前实时 URL，不复用早先带 token 的预览链接。

- [ ] **Step 6: 最终复核工作树和提交文档**

```powershell
git status --short --branch
git add frontend/README.md scripts/open-visual-review.ps1
git add -p docs/PROJECT_TRACKER.md
git diff --cached --check
git diff --cached --name-only
git commit -m "docs: 完成流萤 React 架构交付说明"
git diff --check "$implementationBase..HEAD" -- .
```

预期：若仍有未提交内容，只能是已确认的既有用户改动；生成 assets、node_modules、测试报告和视觉草稿均保持忽略。

- [ ] **Step 7: 完成前最终验证与交付说明**

重新运行并记录本任务的全部完成验证，然后报告：

- 已完成的架构和保留的原生安全边界；
- 精确通过的测试/构建数量与命令；
- APK 路径、活动模拟器 serial、AVD 名称与截图路径；
- 当前仍在运行的浏览器预览 URL；
- ReactBits 固定 commit 与许可位置；
- 仍需用户执行的动作或已知限制。

---

## 任务依赖顺序

```text
1 → 2 → 3 → 4
          ↓
5 → 6 → 7 → 8 → 9
              ↓
             10 → 11 → 12 → 13
                              ↓
                       14 → 15 → 16 → 17
                                      ↓
                               18 → 19 → 20
                                            ↓
                                         21 → 22
```

Task 14 的纯路由外壳可以在 Task 10 的 Kotlin Gateway 后与 Task 11 并行准备，但合并和验收必须遵守上图的合同依赖。Task 15-17 默认按当前任务串行执行；只有用户明确要求并行代理时才拆分，且每个执行者必须拥有不重叠的文件范围。Task 19 必须等三屏稳定，Task 21 必须等全部验收通过。

## 最终回归命令速查

```powershell
Push-Location .\frontend
corepack pnpm lint
corepack pnpm format:check
corepack pnpm typecheck
corepack pnpm test:unit
corepack pnpm build
corepack pnpm verify:bundle
corepack pnpm test:e2e
Pop-Location
$device = & .\scripts\resolve-pixel6-device.ps1
$env:ANDROID_SERIAL = $device.Serial
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:assembleRelease
```
