// 固定运行时是可复现安装的前提；错误版本不能静默继续。
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
function checkRuntime(version) {
  assert.equal(
    version,
    '22.23.3',
    '请使用 .node-version 指定的 Node 22.23.3。',
  );
}
function checkEngineering() {
  checkRuntime(process.versions.node);
  const root = path.resolve(__dirname, '..');
  const pkg = JSON.parse(
    fs.readFileSync(path.join(root, 'package.json'), 'utf8'),
  );
  assert.deepEqual(pkg.workspaces, ['apps/*', 'packages/*']);
  const lock = JSON.parse(
    fs.readFileSync(path.join(root, 'package-lock.json'), 'utf8'),
  );
  for (const name of ['database', 'contracts'])
    assert.ok(lock.packages[`packages/${name}`]);
  console.log('工程运行时、工作区与固定安装检查通过。');
}
if (require.main === module) checkEngineering();
module.exports = { checkRuntime, checkEngineering };
