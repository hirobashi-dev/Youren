// 工程门槛必须拒绝缺失工作区、浮动依赖和不可复现安装。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
test('根安装版本与既有公共包保持一致', () => {
  const pkg = JSON.parse(
    fs.readFileSync(path.join(root, 'package.json'), 'utf8'),
  );
  assert.deepEqual(pkg.workspaces, ['apps/*', 'packages/*']);
  assert.equal(pkg.engines.node, '22.23.3');
  assert.equal(
    fs.readFileSync(path.join(root, '.node-version'), 'utf8').trim(),
    '22.23.3',
  );
  for (const name of ['database', 'contracts']) {
    const child = JSON.parse(
      fs.readFileSync(
        path.join(root, 'packages', name, 'package.json'),
        'utf8',
      ),
    );
    assert.equal(child.name, `@youren/${name}`);
    assert.ok(child.scripts.test);
  }
});
test('工程检查工具提供实质检查且拒绝错误的运行时', () => {
  const { checkRuntime } = require('../tools/check-engineering.cjs');
  assert.doesNotThrow(() => checkRuntime('22.23.3'));
  assert.throws(() => checkRuntime('20.12.1'), /22.23.3/);
});
