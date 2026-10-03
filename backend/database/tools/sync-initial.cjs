// 迁移交接期从旧SQL同步V1；正式部署后冻结V1，变更只能创建V2等新版本。
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../..');
const source = path.join(root, 'packages/database/prisma/migrations/202610030001_initial/migration.sql');
const target = path.join(__dirname, '../src/main/resources/db/migration/V1__initial.sql');
const original = fs.readFileSync(source, 'utf8').replace(/\r\n/g, '\n');
if ((original.match(/^BEGIN;$/gm) || []).length !== 1 || (original.match(/^COMMIT;$/gm) || []).length !== 1) {
  throw new Error('外层事务结构不符合初始迁移，停止同步');
}
// Flyway负责外层事务，原表/函数/索引/触发器及中文注释完整保留。
const content = '-- Java迁移接续：外层事务由Flyway管理，其余可执行SQL与旧初始迁移一致。\n'
  + original.replace(/^BEGIN;\n/m, '').replace(/^COMMIT;\n?/m, '').trimEnd() + '\n';
if (process.argv.includes('--check')) {
  if (!fs.existsSync(target) || fs.readFileSync(target, 'utf8').replace(/\r\n/g, '\n') !== content) throw new Error('Flyway V1与迁移来源不一致');
  console.log('Flyway V1 SQL equivalence: passed');
} else {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, content);
}
