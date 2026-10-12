package jp.youren.worker;

import java.io.IOException;

// worker只删除受控对象键；本机与未来S3实现必须保持相同的分区边界和幂等语义。
public interface WorkerMediaStorage {
  void delete(String area, String objectKey) throws IOException;
}
