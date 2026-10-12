-- 管理员登录挑战短时保存并单次消费，避免MFA完成请求重放或依赖单机内存状态。
CREATE TABLE admin_login_challenges (
  id uuid PRIMARY KEY,
  admin_id uuid NOT NULL REFERENCES admin_accounts(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0)
);

CREATE INDEX admin_login_challenges_expiry_idx ON admin_login_challenges(expires_at);
COMMENT ON TABLE admin_login_challenges IS '仅保存短时管理员MFA挑战，不保存口令、验证码或MFA明文密钥。';
