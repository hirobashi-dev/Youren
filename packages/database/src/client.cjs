// 必须显式提供合法连接串；不读取潜在指向外部数据库的环境默认值。
function createDatabaseClient(databaseUrl) {
  let url;
  try {
    url = new URL(databaseUrl);
  } catch {
    throw new Error('数据库URL格式不正确');
  }
  if (!['postgres:', 'postgresql:'].includes(url.protocol))
    throw new Error('数据库URL协议不正确');
  const { PrismaClient } = require('../generated/client');
  return new PrismaClient({
    datasources: { db: { url: databaseUrl } },
    log: [],
  });
}
module.exports = { createDatabaseClient };
