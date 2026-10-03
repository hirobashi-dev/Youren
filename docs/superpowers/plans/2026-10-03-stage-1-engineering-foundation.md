# 第1阶段：工程基础实施计划

> 执行人员：按 executing-plans 技能逐任务实施，使用下方复选框记录进度。本次仅编写计划，不启动开发，不自动安排并行代理。

**目标：** 建立可复现、可测试的手机端、API、管理后台及worker工程，完成隔离数据库连接和两端开发构建验收。

**架构：** npm workspaces组织模块化单体；API与worker独立启动，共用数据库访问包。手机端和后台只通过HTTP访问API，不直接访问数据库；工程健康检查独立于业务接口。

**技术组合：** TypeScript、NestJS、React Native/Expo、React/Vite、PostgreSQL/Prisma、Redis；ESLint、Prettier、后端Jest/Supertest、后台Vitest/Testing Library/Playwright、手机端jest-expo/React Native Testing Library。现有数据库与合同的Node测试保留。

**依据：** [八阶段路线](2026-10-03-phased-development-acceptance.md)、[数据库/API详细设计](../specs/2026-10-03-social-app-database-api-design.md)、[合同说明](../../../packages/contracts/README.md)。

## 范围与全局要求

- 本阶段实现启动、配置、日志、健康检查、构建及测试基础；账号、帖子、聊天、报名、恋爱、审核业务属于后续阶段。
- 当前已有数据库54模型、迁移、隔离PostgreSQL测试和OpenAPI92操作；没有根package.json或apps工程。保留既有迁移语义和Prisma6.19.0版本，不为搭骨架重写数据库。
- 运行环境采用Node22系列且至少22.12，实施时核对Expo等全部依赖要求并固定兼容的具体补丁版本到`.node-version`与CI；不使用浮动latest。Nest当前文档说明22系列下限为22.12：[官方要求](https://docs.nestjs.com/first-steps)。
- iOS与Android同时作为验收目标。Windows不运行本地Xcode；iOS采用EAS云构建及设备验收路径，或在具备macOS环境时验收模拟器。物理iOS设备EAS开发构建需要Apple开发者资格：[官方环境说明](https://docs.expo.dev/get-started/set-up-your-environment/?device=physical&mode=development-build&platform=ios)。
- 新增/修改代码和测试加入中文注释；JSON通过相邻README解释；UTC服务时间，中文界面。每个任务测试通过后审查差分并本地提交一次。
- 下面标为“拟新增”的命令，必须先在对应package.json定义才可执行；它们不是当前已有命令。禁止使用`--if-present`把缺失检查当通过。

## 文件布局与职责

以下为拟新增文件；标注“修改”的文件已存在。

| 路径 | 职责 |
|---|---|
| `package.json`、`package-lock.json`、`.node-version`、`tsconfig.base.json` | 根workspaces、固定安装与类型基线 |
| `eslint.config.mjs`、`.prettierignore`、`.prettierrc.json`、`README.md` | 统一风格、排除生成文件、启动说明 |
| `tools/check-engineering.cjs`、`tests/engineering.test.cjs` | 工程文件、脚本与版本一致性检查 |
| `infra/compose.yaml`、`infra/.env.example`、`infra/README.md` | 隔离开发/测试PostgreSQL与Redis；配置说明 |
| `packages/runtime/src/config.ts`、`logger.ts`、`index.ts`、`tests/runtime.spec.ts` | API/worker服务端配置校验和日志脱敏，不导入手机端 |
| `packages/database/src/client.ts`、`index.ts`、`tsconfig.json`、`tests/client.test.cjs` | 导出固定版本Prisma Client工厂与生命周期 |
| `apps/api/src/main.ts`、`app.module.ts`、`health/health.controller.ts`、`health/health.service.ts` | Nest启动与存活/就绪检查 |
| `apps/api/test/health.e2e-spec.ts`、`test/readiness.integration-spec.ts` | HTTP与真实依赖健康测试 |
| `apps/worker/src/main.ts`、`bootstrap.ts`、`tests/bootstrap.spec.ts` | 无业务任务的worker启动、关闭与依赖失败检查 |
| `apps/admin/index.html`、`src/main.tsx`、`src/App.tsx`、`src/api/health.ts` | 后台中文工程状态页和API连接 |
| `apps/admin/src/App.test.tsx`、`tests/smoke.spec.ts` | 组件状态与浏览器操作检查 |
| `apps/mobile/App.tsx`、`src/screens/FoundationScreen.tsx`、`src/api/health.ts`、`src/theme.ts` | 中文手机状态页、API连接、既有绿色/粉色主题 |
| `apps/mobile/tests/FoundationScreen.test.tsx`、`app.config.ts`、`eas.json` | 手机组件测试、平台标识与开发构建配置 |
| `.github/workflows/ci.yml`、`tools/check.cjs`、`docs/development/stage-1-acceptance.md` | 自动检查编排与实际验收证据 |

每个新应用/包同时新增自己的`package.json`、`tsconfig.json`、测试配置与中文`README.md`。修改现有`packages/database/package.json`、`packages/contracts/package.json`及根`.gitignore`；现有包的生成文件、schema及测试默认不改。

## 任务1：依赖管理与工程检查

**产出接口：** 根workspaces包含`apps/*`、`packages/*`，应用名称依次为`@youren/api`、`@youren/worker`、`@youren/admin`、`@youren/mobile`；公共服务包为`@youren/runtime`，既有包名称保持不变。

- [ ] 先新增`tests/engineering.test.cjs`，断言四应用、两既有包和runtime有必需脚本及Node版本声明；运行`node --test tests/engineering.test.cjs`，确认缺少工程时失败。

```js
// 验证必需检查存在，防止空脚本或缺失脚本被视为通过。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const root = JSON.parse(fs.readFileSync('package.json', 'utf8'));
assert.deepEqual(root.workspaces, ['apps/*', 'packages/*']);
assert.ok(root.scripts.check);
assert.ok(root.scripts['test:engineering']);
```

- [ ] 添加根workspaces、类型/格式配置及新包清单；保留既有固定依赖版本。根lock成为唯一安装依据；用npm生成根lock，确认数据库/合同测试通过后移除两包旧lock，不手工编辑依赖解析。格式化排除既有生成JSON/SQL和设计图册。
- [ ] 定义根`test:engineering`、`lint`、`typecheck`、`format:check`；新包定义实际非空测试脚本。完整`check`在任务7接入，任务1不能提前声称全仓检查通过。
- [ ] 执行`npm ci`、`npm run test:engineering`，以及既有数据库/合同回归命令；确认固定安装成功且生成文件无漂移。
- [ ] 更新中文README与实际版本，检查`.gitignore`排除秘密、node_modules及构建产物，审查后提交`Add workspace and engineering checks`。

## 任务2：环境配置、隔离依赖与日志

**消费/产出：** `loadConfig(env: NodeJS.ProcessEnv): RuntimeConfig`返回`databaseUrl`、`redisUrl`、`port`、`logLevel`；`redactLog(value: unknown): unknown`只返回安全副本。DB工厂`createDatabaseClient(databaseUrl: string): PrismaClient`显式传入URL，不自动读取外部数据库配置。

- [ ] 在runtime测试写配置缺失、URL格式、合法端口和日志递归脱敏场景，运行`npm test -w @youren/runtime -- --runInBand`确认失败。

```ts
// 缺少数据库配置必须启动失败；令牌、邮箱和密码不进入日志。
expect(() => loadConfig({})).toThrow();
expect(redactLog({ authorization: 'Bearer secret', nested: { password: 'secret' } }))
  .toEqual({ authorization: '[REDACTED]', nested: { password: '[REDACTED]' } });
```

- [ ] 实现配置校验、中文错误与日志脱敏；禁止回显连接串、HTTP正文、cookie、游客凭证、验证码、邮箱及嵌套秘密；任意字符串消息采用允许字段规则，不能只靠键名过滤。
- [ ] 编写compose：开发与测试使用不同数据库、用户、端口和项目卷；端口仅绑定loopback。示例密码只用于本地，真实值保存在忽略的环境文件；Redis做PING检查。新增Prisma工厂与释放测试，不改迁移。
- [ ] 从根目录执行`docker compose --env-file infra/.env -f infra/compose.yaml config --quiet`及`docker compose --env-file infra/.env -f infra/compose.yaml -p youren-stage1 up -d --wait`，确认依赖就绪；`.env`按示例在本机创建，不提交。
- [ ] Docker daemon若仍不可用，可继续现有隔离PostgreSQL测试，但Redis/compose验收保留未通过；不得连接用户已有数据库或停用其他项目容器。完成runtime测试和DB工厂测试后提交`Add isolated runtime configuration and logging`。

## 任务3：API与数据库就绪检查

**接口：** `GET /health/live`成功返回`{status:'ok'}`；`GET /health/ready`依赖就绪返回200，否则503，正文不包含连接串或异常堆栈。工程路径不增加业务OpenAPI功能，健康就绪不能宣称全部业务已运行。

- [ ] 新增Supertest健康用例，运行`npm run test:e2e -w @youren/api`确认控制器缺失时失败。

```ts
// 存活与依赖就绪独立；数据库不可用时不得返回假成功。
await request(app.getHttpServer()).get('/health/live').expect(200, { status: 'ok' });
await request(app.getHttpServer()).get('/health/ready').expect(503);
```

- [ ] 实现Nest应用、配置注入、Prisma连接、Redis客户端、超时检查、受限CORS和关闭钩子。health只暴露安全状态；测试注入失败依赖，正常集成使用隔离数据库。
- [ ] 定义`dev`、`build`、`typecheck`、`test`、`test:e2e`、`test:integration`脚本；执行三类测试。测试集成入口拒绝非测试数据库名/未经显式允许的目标，执行迁移后验证真实`SELECT 1`及Redis PING。
- [ ] 启动`npm run dev -w @youren/api`，调用`Invoke-RestMethod http://127.0.0.1:3000/health/ready`；在测试环境模拟依赖中断，断言503、有限超时、脱敏日志及恢复200。
- [ ] 构建并检查退出时释放连接，记录结果后提交`Add API health and database readiness`。

## 任务4：worker生命周期

**接口：** `startWorker(config: RuntimeConfig): Promise<{stop(): Promise<void>}>`连接数据库/Redis，启动成功只记录脱敏就绪事件；目前不消费审核、推送或删除业务任务。

- [ ] 编写连接失败不报ready、`stop()`重复调用安全、关闭时释放两依赖测试，执行`npm test -w @youren/worker -- --runInBand`确认失败。

```ts
// 关闭可重复调用，不能遗留数据库连接或定时器。
const worker = await startWorker(testConfig);
await worker.stop();
await expect(worker.stop()).resolves.toBeUndefined();
```

- [ ] 实现入口、超时、启动失败非零退出码、SIGINT/SIGTERM关闭；为Windows测试直接调用stop，另外运行实际启动/关闭烟雾测试。
- [ ] 执行worker单元/集成测试、类型检查、build；用`npm run dev -w @youren/worker`确认只启动骨架，无业务副作用。
- [ ] 记录连接释放与失败证据后提交`Add worker startup and shutdown lifecycle`。

## 任务5：管理后台骨架

**接口：** `getReadiness(signal?: AbortSignal): Promise<'ready' | 'unavailable'>`调用health；界面提供“连接成功”“连接失败”“重试”，不返回数据库/管理凭证。

- [ ] 编写成功、503、网络超时和重试组件测试，运行`npm test -w @youren/admin -- --run`确认界面缺失时失败。

```tsx
// 服务不可用时显示中文错误，可重试且不把错误当连接成功。
render(<App />);
expect(await screen.findByText('连接失败')).toBeInTheDocument();
await userEvent.click(screen.getByRole('button', { name: '重试' }));
expect(await screen.findByText('连接成功')).toBeInTheDocument();
```

- [ ] 实现React/Vite状态页、环境化API地址与fetch超时；使用MSW在组件测试返回503→200，不增加登录/审核功能。
- [ ] 运行`npm run test:e2e -w @youren/admin`：Playwright实际打开页面并检查三种状态、窄屏文字和重试操作；构建执行`npm run build -w @youren/admin`。
- [ ] 保存页面截图和结果至验收记录，提交`Add admin application foundation`。

## 任务6：手机端骨架与两端开发构建

**接口：** 手机端独立`getReadiness`与后台同样的返回约定；`FoundationScreen`展示中文名称、状态和重试。`EXPO_PUBLIC_API_BASE_URL`只放公开API地址，不放秘密；真机使用可达的局域网API地址，不能使用手机的localhost。

- [ ] 编写手机连接失败、成功、超时和点击重试测试；运行`npm test -w @youren/mobile -- --runInBand`确认缺少页面时失败。

```tsx
// 手机重试按钮能够恢复连接状态；测试fetch先失败再成功。
render(<FoundationScreen />);
expect(await screen.findByText('连接失败')).toBeTruthy();
fireEvent.press(screen.getByText('重试'));
expect(await screen.findByText('连接成功')).toBeTruthy();
```

- [ ] 创建Expo/React Native项目，按所选SDK配套安装依赖与jest-expo；固定版本及开发标识，接入绿色主色/粉色恋爱辅助主题，不开始业务页面。
- [ ] 定义`start`、`android`（expo run:android）、`build:js`（expo export --platform all）、`typecheck`与`test`；`eas.json` development使用开发客户端/internal，另设development-simulator供macOS验收。EAS CLI固定为mobile开发依赖。
- [ ] 执行手机组件测试、`npm run build:js -w @youren/mobile`、`npm exec -w @youren/mobile -- expo install --check`；JS导出不等于原生构建通过。
- [ ] Android：Windows具备SDK/JDK与设备后执行`npm run android -w @youren/mobile`；iOS：进入`apps/mobile`执行`npx eas build --platform ios --profile development`，构建并安装到已登记真机。云端上传/账户操作在实际执行前按已有授权及必要审批处理，不在本次计划中执行。
- [ ] 两端各实测启动、重载、API成功/不可用/恢复、中文和安全区显示；记录OS、设备、SDK、构建ID、截图与操作结果。缺账号或设备时此项保持未验收；Expo Go预览不代替开发构建。[Expo开发构建说明](https://docs.expo.dev/develop/development-builds/introduction/)。
- [ ] 组件和两端证据齐全后提交`Add mobile foundation and development build setup`；若只能完成配置，提交消息及记录明确未完成平台验收。

## 任务7：自动检查、干净安装与阶段验收

**接口：** 根`npm run check`通过`tools/check.cjs`按依赖顺序执行各检查；任一步失败立即非零退出，不忽略缺失脚本。`test:integration`连接明确隔离环境；云构建与人工设备检查单独记录，不伪装为普通CI自动通过。

- [ ] 编写工具编排测试：注入失败命令，断言退出非零且停止后续检查；使用`node --test tests/engineering.test.cjs`执行RED→GREEN。

```js
// 自动检查必须传播子命令失败，不允许假绿。
const result = spawnSync(process.execPath, ['tools/check.cjs', '--self-test-failure']);
assert.notEqual(result.status, 0);
```

- [ ] `check`依次执行工程检查、lint、格式、类型、所有单元/HTTP测试、数据库validate/生成一致性、合同生成一致性、API/worker/admin构建、手机JS导出。根命令输出每项执行结果。
- [ ] CI固定Node/npm，使用`npm ci`；独立job建立测试PostgreSQL/Redis服务，运行真实集成及后台浏览器测试。业务依赖环境不与开发卷混用，不自动运行上传或发布。
- [ ] 在干净检出环境执行完整命令矩阵，按README复现；确认没有依赖本机未记录的全局包。用户目录PostgreSQL fallback不得当CI默认依赖。
- [ ] 在`docs/development/stage-1-acceptance.md`记录命令、退出码、环境、平台构建、截图及未实施原因；完成下方清单后更新路线中第1阶段状态。
- [ ] 审查中文注释、秘密、lock和差分，提交`Add CI checks and foundation acceptance evidence`。

## 命令矩阵

均从仓库根执行（标注mobile目录的iOS命令除外）；根及新应用命令在任务中创建。

| 命令 | 定义状态与用途 |
|---|---|
| `npm ci` | 拟新增根安装依据；任务1生成root lock后可用 |
| `npm run test:engineering` | 拟新增；工程/检查失败传播测试 |
| `npm run check` | 拟新增；任务7全自动静态、测试和构建门槛 |
| `npm run test:integration` | 拟新增；API/worker真实DB与Redis测试 |
| `npm run test:e2e -w @youren/api` | 拟新增；HTTP测试 |
| `npm run test:e2e -w @youren/admin` | 拟新增；浏览器页面操作 |
| `npm test -w @youren/mobile -- --runInBand` | 拟新增；手机组件测试 |
| `npm run android -w @youren/mobile` | 拟新增；Android原生构建/安装 |
| `npm run validate -w @youren/database` | 既有包脚本；workspaces接入后根可调用 |
| `npm test -w @youren/database` | 既有包脚本；当前10项约束回归 |
| `npm run test:postgres -w @youren/database` | 既有隔离本机PostgreSQL测试；无PG二进制时如实记录，不代替CI服务测试 |
| `npm test -w @youren/contracts` | 既有包脚本；当前9项合同回归 |
| `git diff --check` | 既有；每任务完成前检查 |

## 阶段1验收清单

- [ ] 全新检出按README固定安装、检查、启动成功。
- [ ] apps和共用包依赖边界清晰，无手机端导入服务端秘密/Prisma。
- [ ] 实际数据库迁移两次无重复副作用；54表与Prisma读写回归通过。
- [ ] API存活/就绪正常；数据库或Redis不可用返回503并可恢复。
- [ ] worker启动失败可检测，重复关闭安全，无遗留连接或任务副作用。
- [ ] 日志脱敏测试、格式、类型、合同及数据库生成一致性通过。
- [ ] 后台实际渲染，失败/恢复/重试操作通过。
- [ ] Android开发构建安装并连接API，截图及设备证据完整。
- [ ] iOS开发构建安装并连接API，截图及设备证据完整。
- [ ] 自动检查对真实错误返回非零；CI集成与浏览器测试通过。
- [ ] 开发/测试数据隔离；秘密与构建产物未提交。
- [ ] 每任务测试及本地提交记录完整，未执行项明确；必要验收缺失时第1阶段保持未完成。

本计划验收通过后进入阶段2。当前只完成计划文件的内容、引用和一致性检查，以上开发命令尚未执行，不宣称第1阶段已完成。
