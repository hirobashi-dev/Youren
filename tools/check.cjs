// 统一检查只执行实际命令；任何失败或启动异常立即停止，保留非零退出码。
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { dockerExecutable } = require('./docker.cjs');
const root = path.resolve(__dirname, '..');
function runSteps(steps) {
  for (const step of steps) {
    console.log(`\n检查：${step.name}`);
    const result = spawnSync(step.command, step.args, {
      cwd: step.cwd ?? root,
      stdio: 'inherit',
      windowsHide: true,
      env: process.env,
    });
    if (result.error || result.signal || result.status !== 0) {
      console.error(`检查失败：${step.name}`);
      return result.status || 1;
    }
  }
  return 0;
}

function stepsFor(mode) {
  // 从npm调用时复用其CLI与当前固定Node，避免Windows npm.cmd切回系统旧Node。
  const npmCli = process.env.npm_execpath;
  if (!npmCli || !fs.existsSync(npmCli))
    throw new Error('请使用npm run check及其分组命令。');
  const nodeStep = (name, args) => ({ name, command: process.execPath, args });
  const npmStep = (name, args) => nodeStep(name, [npmCli, ...args]);
  const frontend = [
    npmStep('工程版本与工作区', ['run', 'check:engineering']),
    npmStep('工程、编排及报告边界测试', ['run', 'test:engineering']),
    npmStep('ESLint', ['run', 'lint']),
    npmStep('Prettier', ['run', 'format:check']),
    npmStep('管理后台类型', ['run', 'typecheck', '-w', '@youren/admin']),
    npmStep('手机类型', ['run', 'typecheck', '-w', '@youren/mobile']),
    npmStep('历史数据库规范校验', [
      'run',
      'validate',
      '-w',
      '@youren/database',
    ]),
    npmStep('历史数据库生成一致性', [
      'run',
      'check:generated',
      '-w',
      '@youren/database',
    ]),
    npmStep('合同生成一致性', [
      'run',
      'check:generated',
      '-w',
      '@youren/contracts',
    ]),
    npmStep('历史数据库约束回归', ['test', '-w', '@youren/database']),
    npmStep('OpenAPI合同', ['test', '-w', '@youren/contracts']),
    npmStep('管理后台组件与客户端', ['test', '-w', '@youren/admin']),
    npmStep('手机组件与客户端', [
      'test',
      '-w',
      '@youren/mobile',
      '--',
      '--runInBand',
    ]),
    npmStep('管理后台构建', ['run', 'build', '-w', '@youren/admin']),
    npmStep('Expo依赖兼容', [
      'exec',
      '-w',
      '@youren/mobile',
      '--',
      'expo',
      'install',
      '--check',
    ]),
    npmStep('iOS/Android JS导出', ['run', 'build:js', '-w', '@youren/mobile']),
  ];
  const backend = [
    {
      name: 'Docker必需环境',
      command: dockerExecutable(),
      args: ['version', '--format', '{{.Server.Version}}'],
    },
    nodeStep('Flyway V1来源一致性', [
      'backend/database/tools/sync-initial.cjs',
      '--check',
    ]),
    process.platform === 'win32'
      ? {
          name: 'Java clean verify',
          command: process.env.PWSH_BIN ?? 'pwsh.exe',
          args: [
            '-NoProfile',
            '-File',
            path.join(root, 'backend/build.ps1'),
            'clean',
            'verify',
          ],
        }
      : {
          name: 'Java clean verify',
          command: 'bash',
          args: [
            'backend/mvnw',
            '-B',
            '-ntp',
            '-f',
            'backend/pom.xml',
            'clean',
            'verify',
          ],
        },
    nodeStep('JUnit实际数量与结果', ['tools/check-java-reports.cjs']),
  ];
  const browser = [
    npmStep('后台模拟浏览器', ['run', 'test:e2e', '-w', '@youren/admin']),
    npmStep('后台真实Java故障恢复', [
      'run',
      'test:e2e:java',
      '-w',
      '@youren/admin',
    ]),
  ];
  return mode === 'all'
    ? [...frontend, ...backend, ...browser]
    : { frontend, backend, browser }[mode];
}

if (require.main === module) {
  try {
    const args = process.argv.slice(2);
    if (
      args.length > 1 ||
      (args.length &&
        !['--frontend', '--backend', '--browser'].includes(args[0]))
    )
      throw new Error(
        '检查模式仅支持--frontend、--backend、--browser，默认完整检查。',
      );
    process.exitCode = runSteps(stepsFor(args[0]?.slice(2) ?? 'all'));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
module.exports = { runSteps };
