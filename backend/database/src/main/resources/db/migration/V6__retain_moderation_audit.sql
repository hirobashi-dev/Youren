-- 审核审计没有已确认的自动清理期限，因此不能随举报或内容到期自动删除。
ALTER TABLE moderation_actions
  ALTER COLUMN expires_at DROP NOT NULL;

COMMENT ON COLUMN moderation_actions.expires_at IS
  '历史字段为兼容保留；第三阶段审计记录默认长期保留，不设置自动到期。';
