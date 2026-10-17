// 构造受控JUnit报告，验证零测试、失败、跳过与缺少报告不能成为假绿。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {
  parseReport,
  verifyReports,
} = require('../tools/check-java-reports.cjs');

function report(tests, attributes = '') {
  return `<testsuite tests="${tests}" errors="0" failures="0" skipped="0" ${attributes}>${Array.from({ length: tests }, (_, index) => `<testcase name="case-${index}"/>`).join('')}</testsuite>`;
}

test('成功报告按实际用例数统计', () => {
  assert.equal(parseReport(report(3)), 3);
});
test('失败、错误或跳过任何用例均拒绝验收', () => {
  for (const attribute of ['failures', 'errors', 'skipped']) {
    assert.throws(
      () =>
        parseReport(report(1).replace(`${attribute}="0"`, `${attribute}="1"`)),
      /未通过/,
    );
  }
});
test('零测试、损坏XML和统计不符均拒绝验收', () => {
  for (const xml of [
    report(0),
    '<testsuite',
    report(2).replace('tests="2"', 'tests="3"'),
  ]) {
    assert.throws(() => parseReport(xml));
  }
});
test('缺少任一必需模块报告拒绝验收', (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'youren-reports-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  assert.throws(() => verifyReports(directory), /报告/);
});
test('完整模块报告统计30项，删除集成报告后必须失败', (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'youren-reports-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  for (const [module, kind, count] of [
    ['shared', 'surefire', 7],
    ['database', 'failsafe', 11],
    ['api', 'surefire', 6],
    ['api', 'failsafe', 1],
    ['worker', 'surefire', 3],
    ['worker', 'failsafe', 2],
  ]) {
    const location = path.join(directory, module, 'target', `${kind}-reports`);
    fs.mkdirSync(location, { recursive: true });
    fs.writeFileSync(path.join(location, 'TEST-example.xml'), report(count));
  }
  assert.equal(verifyReports(directory), 30);
  fs.unlinkSync(
    path.join(directory, 'api/target/failsafe-reports/TEST-example.xml'),
  );
  assert.throws(() => verifyReports(directory), /报告/);
});
