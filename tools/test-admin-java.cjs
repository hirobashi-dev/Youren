// 仅启动本项目测试服务；退出后停止本次新启动的服务，不删除任何卷。
const path = require('node:path');
const { spawnSync, execFileSync } = require('node:child_process');
const net = require('node:net');
const { dockerExecutable } = require('./docker.cjs');
const root = path.resolve(__dirname, '..');
const docker = dockerExecutable();
const compose = [
  'compose',
  '--env-file',
  'infra/.env',
  '-f',
  'infra/compose.yaml',
  '-p',
  'youren-stage1',
];
const previous = execFileSync(
  docker,
  [...compose, 'ps', '--services', '--status', 'running'],
  { cwd: root, encoding: 'utf8' },
)
  .trim()
  .split(/\r?\n/);
const services = ['postgres-test', 'redis-test'];
const created = services.filter((service) => !previous.includes(service));
// 分配独立本机端口，不复用或停止端口3000已有服务。
function availablePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
  });
}
async function main() {
  const apiPort = String(await availablePort());
  let exitCode = 1;
  try {
    const started = spawnSync(
      docker,
      [...compose, 'up', '-d', '--wait', ...services],
      { cwd: root, stdio: 'inherit', windowsHide: true },
    );
    if (started.status !== 0) throw new Error('测试依赖启动失败');
    const result = spawnSync(
      process.execPath,
      [
        path.join(root, 'node_modules/@playwright/test/cli.js'),
        'test',
        '-c',
        'playwright.integration.config.ts',
      ],
      {
        cwd: path.join(root, 'apps/admin'),
        stdio: 'inherit',
        windowsHide: true,
        env: {
          ...process.env,
          YOUREN_TEST_API_PORT: apiPort,
          VITE_API_BASE_URL: `http://127.0.0.1:${apiPort}`,
        },
      },
    );
    exitCode = result.status ?? 1;
  } finally {
    if (created.length) {
      const stopped = spawnSync(docker, [...compose, 'stop', ...created], {
        cwd: root,
        stdio: 'inherit',
        windowsHide: true,
      });
      if (stopped.status !== 0) exitCode = 1;
    }
    process.exitCode = exitCode;
  }
}
main().catch(() => {
  console.error('Java后台联调失败，请检查测试依赖及端口。');
  process.exitCode = 1;
});
