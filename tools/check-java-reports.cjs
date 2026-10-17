// 报告由Maven clean verify生成；缺失、零测试、跳过与统计漂移均不能假绿。
const fs = require('node:fs');
const path = require('node:path');
const { XMLParser, XMLValidator } = require('fast-xml-parser');
function parseReport(xml) {
  if (XMLValidator.validate(xml) !== true || xml.includes('<!DOCTYPE'))
    throw new Error('报告XML无效');
  const suite = new XMLParser({
    ignoreAttributes: false,
    processEntities: false,
  }).parse(xml).testsuite;
  if (!suite) throw new Error('报告缺少testsuite');
  const counts = {};
  for (const name of ['tests', 'errors', 'failures', 'skipped']) {
    const value = suite[`@_${name}`];
    if (!/^\d+$/.test(String(value))) throw new Error('报告统计无效');
    counts[name] = Number(value);
  }
  if (!counts.tests || counts.errors || counts.failures || counts.skipped)
    throw new Error('测试报告未通过或未执行用例');
  const cases = suite.testcase ? [suite.testcase].flat() : [];
  if (
    cases.length !== counts.tests ||
    cases.some((item) =>
      ['error', 'failure', 'skipped'].some((key) => Object.hasOwn(item, key)),
    )
  )
    throw new Error('报告用例与统计不符或未通过');
  return counts.tests;
}
function verifyReports(backendRoot = path.resolve(__dirname, '../backend')) {
  let total = 0;
  // 基线是各模块最低实际数量；允许未来新增测试，但不能减少基础验收覆盖。
  for (const [module, kind, minimum] of [
    ['shared', 'surefire', 7],
    ['database', 'failsafe', 11],
    ['api', 'surefire', 6],
    ['api', 'failsafe', 1],
    ['worker', 'surefire', 3],
    ['worker', 'failsafe', 2],
  ]) {
    const directory = path.join(
      backendRoot,
      module,
      'target',
      `${kind}-reports`,
    );
    if (!fs.existsSync(directory)) throw new Error(`缺少${module}/${kind}报告`);
    const files = fs
      .readdirSync(directory)
      .filter((name) => /^TEST-.+\.xml$/.test(name));
    const count = files.reduce(
      (sum, name) =>
        sum + parseReport(fs.readFileSync(path.join(directory, name), 'utf8')),
      0,
    );
    if (count < minimum)
      throw new Error(`${module}/${kind}报告不足${minimum}项`);
    total += count;
  }
  return total;
}
if (require.main === module) {
  try {
    console.log(`Java报告检查通过：${verifyReports()}项，无失败、错误或跳过。`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
module.exports = { parseReport, verifyReports };
