// 非数据库协议和缺失配置必须在创建客户端前拒绝。
const test = require('node:test'),
  assert = require('node:assert/strict');
const { createDatabaseClient } = require('../src/client.cjs');
test('工厂拒绝隐式或非法连接串', () => {
  assert.throws(() => createDatabaseClient(undefined));
  assert.throws(() => createDatabaseClient('https://example.test/db'));
});
