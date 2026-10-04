// 使用真实子进程验证成功、失败和启动错误；后续命令不得在失败后执行。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { runSteps } = require('../tools/check.cjs');

function temporary(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'youren-check-'));
  // 测试只清理由mkdtemp创建的自身目录，不接受外部路径。
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  return directory;
}
function markerStep(file) {
  return {
    name: '写入下一步标记',
    command: process.execPath,
    args: [
      '-e',
      'require("node:fs").writeFileSync(process.argv[1], "done")',
      file,
    ],
  };
}

test('全部检查成功时执行后续步骤并返回零', (t) => {
  const marker = path.join(temporary(t), 'finished');
  assert.equal(
    runSteps([
      {
        name: '成功步骤',
        command: process.execPath,
        args: ['-e', 'process.exit(0)'],
      },
      markerStep(marker),
    ]),
    0,
  );
  assert.equal(fs.readFileSync(marker, 'utf8'), 'done');
});

test('子进程失败时保留退出码并停止后续步骤', (t) => {
  const marker = path.join(temporary(t), 'must-not-exist');
  assert.equal(
    runSteps([
      {
        name: '预期失败',
        command: process.execPath,
        args: ['-e', 'process.exit(7)'],
      },
      markerStep(marker),
    ]),
    7,
  );
  assert.equal(fs.existsSync(marker), false);
});

test('命令不存在时返回非零且不继续检查', (t) => {
  const marker = path.join(temporary(t), 'must-not-exist');
  assert.notEqual(
    runSteps([
      {
        name: '不存在的工具',
        command: path.join(path.dirname(marker), 'missing-command'),
        args: [],
      },
      markerStep(marker),
    ]),
    0,
  );
  assert.equal(fs.existsSync(marker), false);
});

test('未知检查模式被拒绝，不静默跳过必需检查', () => {
  const result = spawnSync(
    process.execPath,
    [path.join(__dirname, '../tools/check.cjs'), '--unknown'],
    { encoding: 'utf8' },
  );
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /检查模式/);
});
