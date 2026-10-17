-- 举报版本用于拒绝过期结案；既有举报从初始版本1开始。
ALTER TABLE reports
  ADD COLUMN version integer NOT NULL DEFAULT 1;

-- 审核审计保存操作者实际检查的内容版本；历史记录保持未知，新写入必须提供正版本。
ALTER TABLE moderation_actions
  ADD COLUMN expected_version integer;

ALTER TABLE reports
  ADD CONSTRAINT reports_version_check CHECK (version > 0);

ALTER TABLE moderation_actions
  ADD CONSTRAINT moderation_actions_expected_version_check
  CHECK (expected_version IS NULL OR expected_version > 0);

COMMENT ON COLUMN reports.version IS '用于举报结案的乐观锁版本；每次状态更新递增。';
COMMENT ON COLUMN moderation_actions.expected_version IS '审核员做出决定时读取的内容版本；迁移前的历史记录为NULL。';
