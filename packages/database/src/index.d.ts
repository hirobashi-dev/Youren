// 类型复用生成客户端，应用只通过工厂创建连接。
import { PrismaClient } from '../generated/client';
export function createDatabaseClient(databaseUrl: string): PrismaClient;
