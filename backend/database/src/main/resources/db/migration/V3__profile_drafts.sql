-- 普通公开快照必须有明确批准标记；历史没有审核证据的行默认待审，安全地拒绝公开读取。
ALTER TABLE profiles ADD COLUMN moderation_status text NOT NULL DEFAULT 'pending';
ALTER TABLE profiles ADD CONSTRAINT profiles_moderation_status_check CHECK (moderation_status IN ('pending','approved','rejected'));

-- 只保留本人最新编辑快照，独立于profiles已批准公开内容；修改草稿绝不覆盖公开旧值。
-- moderation_revisions强制expires_at，但普通资料草稿期限尚未批准，因此不虚构清理期限。
-- fields只由资料服务规范化为昵称、简介、地区和兴趣ID；不存秘密、恋爱资料或图片URL。
CREATE TABLE profile_drafts (
  account_id uuid PRIMARY KEY REFERENCES accounts(id) ON DELETE RESTRICT,
  fields jsonb NOT NULL CHECK (jsonb_typeof(fields)='object'),
  version integer NOT NULL CHECK (version > 0),
  moderation_status text NOT NULL DEFAULT 'pending' CHECK (moderation_status IN ('pending','approved','rejected')),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
