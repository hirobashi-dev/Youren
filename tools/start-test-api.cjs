// 浏览器验收专用入口：目标固定为本项目测试库，不读取外部数据库URL。
const fs = require('node:fs');
const path = require('node:path');
const { spawn, execFileSync } = require('node:child_process');
const { dockerExecutable } = require('./docker.cjs');
const root = path.resolve(__dirname, '..');
const javaHome =
  process.env.YOUREN_JDK_HOME ||
  process.env.JAVA_HOME ||
  path.join(root, '../.youren-tools/jdk-21.0.12.1+1');
// 系统Java8不作为默认候选；本机工具目录存在时优先选择其独立JDK21。
const localJava = path.join(root, '../.youren-tools/jdk-21.0.12.1+1');
const selected =
  process.env.YOUREN_JDK_HOME ||
  (fs.existsSync(localJava) ? localJava : javaHome);
const java = path.join(
  selected,
  'bin',
  process.platform === 'win32' ? 'java.exe' : 'java',
);
const container = JSON.parse(
  execFileSync(
    dockerExecutable(),
    ['inspect', 'youren-stage1-postgres-test-1'],
    { encoding: 'utf8' },
  ),
)[0];
const values = Object.fromEntries(
  container.Config.Env.map((item) => {
    const separator = item.indexOf('=');
    return [item.slice(0, separator), item.slice(separator + 1)];
  }),
);
if (
  values.POSTGRES_USER !== 'youren_test' ||
  values.POSTGRES_DB !== 'youren_test'
)
  throw new Error('测试数据库身份不匹配');
const api = spawn(
  java,
  [
    '-Dfile.encoding=UTF-8',
    '-jar',
    path.join(root, 'backend/api/target/youren-api-0.1.0-SNAPSHOT.jar'),
  ],
  {
    stdio: 'inherit',
    windowsHide: true,
    env: {
      ...process.env,
      SPRING_DATASOURCE_URL: 'jdbc:postgresql://127.0.0.1:5442/youren_test',
      SPRING_DATASOURCE_USERNAME: 'youren_test',
      SPRING_DATASOURCE_PASSWORD: values.POSTGRES_PASSWORD,
      SPRING_DATA_REDIS_URL: 'redis://127.0.0.1:6382',
      SERVER_PORT: process.env.YOUREN_TEST_API_PORT ?? '3001',
    },
  },
);
api.on('error', () => {
  console.error('Java测试入口启动失败，请检查JDK及打包产物。');
  process.exitCode = 1;
});
api.on('exit', (code) => {
  process.exitCode = code ?? 1;
});
for (const signal of ['SIGINT', 'SIGTERM'])
  process.on(signal, () => api.kill(signal));
