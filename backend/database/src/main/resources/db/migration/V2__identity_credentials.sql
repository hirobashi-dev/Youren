-- 游客旧凭证从原创建时间起算30天，不因升级重新延长有效期。
ALTER TABLE guest_credentials ADD COLUMN expires_at timestamptz;
UPDATE guest_credentials SET expires_at = created_at + INTERVAL '30 days';
ALTER TABLE guest_credentials ALTER COLUMN expires_at SET NOT NULL;
ALTER TABLE guest_credentials ADD CONSTRAINT guest_credentials_expiry_check CHECK (expires_at > created_at);
CREATE INDEX guest_credentials_expiry_idx ON guest_credentials(expires_at,id);

-- 访问和刷新秘密分别保存摘要；旧会话没有访问秘密，必须撤销而非生成伪造摘要。
ALTER TABLE sessions ADD COLUMN access_hash text;
ALTER TABLE sessions ADD COLUMN access_expires_at timestamptz;
UPDATE sessions SET revoked_at = COALESCE(revoked_at,CURRENT_TIMESTAMP),updated_at = CURRENT_TIMESTAMP;
ALTER TABLE sessions ADD CONSTRAINT sessions_access_pair_check CHECK (
  (access_hash IS NULL AND access_expires_at IS NULL) OR
  (access_hash IS NOT NULL AND access_expires_at IS NOT NULL AND access_expires_at <= expires_at)
);
-- 保留缺少访问秘密的已撤销历史，但禁止创建可用的旧格式会话。
ALTER TABLE sessions ADD CONSTRAINT sessions_active_access_check CHECK (revoked_at IS NOT NULL OR access_hash IS NOT NULL);
CREATE UNIQUE INDEX sessions_access_hash_unique ON sessions(access_hash) WHERE access_hash IS NOT NULL;
-- 会话族撤销按family_id检索，保留历史摘要以识别刷新重放。
CREATE INDEX sessions_family_id_idx ON sessions(family_id);
