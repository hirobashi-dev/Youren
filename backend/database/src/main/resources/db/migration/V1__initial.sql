-- Java迁移接续：外层事务由Flyway管理，其余可执行SQL与旧初始迁移一致。
-- 初始数据库迁移：由tools/generate.cjs生成，说明来源为comments.cjs。
-- 修改model.cjs、comments.cjs或constraints.sql后重新生成；已部署迁移须另建增量版本。
-- 本文件只建立结构及默认配置，业务权限、频率扣额和物理删除仍需API/worker。
-- 全部变更在同一事务提交，任一步失败不留下半套表结构。

-- 注册账号：邮箱唯一；账号限制、注销与会话版本不等同于公开资料。
CREATE TABLE "accounts" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 按产品策略正规化后的登录邮箱，禁止出现在公开DTO中。
  "email_normalized" text NOT NULL,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'active',
  -- 权限/会话失效版本，撤销后服务端重新鉴权。
  "auth_version" integer NOT NULL DEFAULT 1,
  "deletion_requested_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 email_normalized：拒绝重复绑定或重复业务记录。
  UNIQUE ("email_normalized"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "accounts_check_0" CHECK (status IN ('active','restricted','deleting','deleted'))
);

-- 内容所有权主体：游客接续后绑定账号，保留历史作者与原公开身份。
CREATE TABLE "principals" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 记录分类；允许值或关系由当前表约束确定。
  "kind" text NOT NULL,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid,
  "claimed_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "principals_check_0" CHECK (kind IN ('guest','account')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "principals_check_1" CHECK (kind <> 'account' OR account_id IS NOT NULL)
);

-- 游客管理凭证：只保存秘密摘要，撤销后不可继续管理内容。
CREATE TABLE "guest_credentials" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "principal_id" uuid NOT NULL,
  -- 高熵凭证摘要，不保存原始秘密。
  "secret_hash" text NOT NULL,
  "revoked_at" timestamptz,
  "last_used_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 secret_hash：拒绝重复绑定或重复业务记录。
  UNIQUE ("secret_hash")
);

-- 账号会话及刷新令牌轮换链：支持撤销和识别旧令牌重放。
CREATE TABLE "sessions" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  -- 刷新令牌摘要；旧令牌重放需撤销对应会话家族。
  "refresh_hash" text NOT NULL,
  -- 刷新轮换链所属家族标识。
  "family_id" uuid NOT NULL,
  -- 刷新轮换产生的后继会话记录。
  "replaced_by_id" uuid,
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz NOT NULL,
  "revoked_at" timestamptz,
  "platform" text NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 refresh_hash：拒绝重复绑定或重复业务记录。
  UNIQUE ("refresh_hash")
);

-- 邮箱验证码挑战：区分注册、登录与注销用途，限制有效期和尝试次数。
CREATE TABLE "email_challenges" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 按产品策略正规化后的登录邮箱，禁止出现在公开DTO中。
  "email_normalized" text NOT NULL,
  -- 用途隔离，不能将恋爱图片直接绑定到公开帖子。
  "purpose" text NOT NULL,
  -- 验证码的带服务器秘密摘要，避免低熵验证码被离线穷举。
  "code_hmac" text NOT NULL,
  -- 已尝试次数，用于限次及退避。
  "attempts" integer NOT NULL DEFAULT 0,
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz NOT NULL,
  "consumed_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "email_challenges_check_0" CHECK (purpose IN ('login','register','delete_account')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "email_challenges_check_1" CHECK (attempts >= 0)
);

-- 地区字典：用标准代码关联都道府县与市区町村，不保存定位轨迹。
CREATE TABLE "regions" (
  "code" text NOT NULL,
  "parent_code" text,
  "level" text NOT NULL,
  "name_ja" text NOT NULL,
  "name_zh" text NOT NULL,
  "active" boolean NOT NULL DEFAULT true,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("code"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "regions_check_0" CHECK (level IN ('prefecture','municipality'))
);

-- 兴趣字典：显示名称与排序由版本化数据维护。
CREATE TABLE "interests" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "code" text NOT NULL,
  "name_zh" text NOT NULL,
  "active" boolean NOT NULL DEFAULT true,
  "sort_order" integer NOT NULL DEFAULT 0,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 code：拒绝重复绑定或重复业务记录。
  UNIQUE ("code")
);

-- 图片资源：记录所有者、用途、上传状态及物理对象清理信息，原图不直接公开。
CREATE TABLE "media_assets" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 图片管理主体，绑定时校验当前账号或游客凭证所有权。
  "owner_principal_id" uuid NOT NULL,
  -- 用途隔离，不能将恋爱图片直接绑定到公开帖子。
  "purpose" text NOT NULL,
  -- 受控存储对象键，不允许客户端指定覆盖他人对象。
  "object_key" text NOT NULL,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'initiated',
  -- 静态JPEG/PNG/WebP；实际文件类型还需解码核对。
  "mime" text NOT NULL,
  -- 实际文件字节数，上限5,000,000。
  "size_bytes" bigint NOT NULL,
  -- 解码宽度；与高度组合限制总像素。
  "width" integer,
  -- 解码高度；不能仅相信客户端声明。
  "height" integer,
  "checksum" text,
  -- 临时上传授权过期时间，不等于已公开内容保留期限。
  "upload_expires_at" timestamptz NOT NULL,
  -- 未关联或失败资源的短期清理时点。
  "cleanup_after" timestamptz,
  "deleted_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 object_key：拒绝重复绑定或重复业务记录。
  UNIQUE ("object_key"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "media_assets_check_0" CHECK (purpose IN ('board','profile','dating','event')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "media_assets_check_1" CHECK (status IN ('initiated','uploaded','processing','approved','rejected','deleting','deleted')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "media_assets_check_2" CHECK (size_bytes > 0 AND size_bytes <= 5000000),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "media_assets_check_3" CHECK (mime IN ('image/jpeg','image/png','image/webp')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "media_assets_check_4" CHECK ((width IS NULL AND height IS NULL) OR (width > 0 AND height > 0 AND width::bigint * height <= 20000000))
);

-- 普通公开资料：不混入恋爱偏好与年龄核验状态。
CREATE TABLE "profiles" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  "nickname" text NOT NULL,
  "bio" text NOT NULL DEFAULT '',
  "avatar_media_id" uuid,
  -- 标准地区代码外键，不存持续定位。
  "region_code" text,
  "visibility" text NOT NULL DEFAULT 'public',
  -- 乐观锁/资料版本，用于拒绝旧版本覆盖。
  "version" integer NOT NULL DEFAULT 1,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 account_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("account_id"),
  -- 唯一组合 avatar_media_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("avatar_media_id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "profiles_check_0" CHECK (visibility IN ('public','hidden'))
);

-- 用户隐私及通知设置：陌生私聊默认开放，锁屏正文默认关闭。
CREATE TABLE "account_settings" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  -- 是否允许新陌生人联系；关闭不终止已有成功联系的会话。
  "allow_stranger_dm" boolean NOT NULL DEFAULT true,
  "push_enabled" boolean NOT NULL DEFAULT true,
  "lockscreen_preview" boolean NOT NULL DEFAULT false,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 account_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("account_id")
);

-- 普通资料与兴趣的多对多关系，复合主键避免重复绑定。
CREATE TABLE "profile_interests" (
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  "interest_id" uuid NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("account_id","interest_id")
);

-- 推送安装绑定：切换账号需撤销旧绑定，令牌加密存储且不回显。
CREATE TABLE "push_devices" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  "installation_id" uuid NOT NULL,
  -- 推送令牌加密值，读取接口不回显。
  "token_ciphertext" text NOT NULL,
  -- 令牌查找及唯一性摘要。
  "token_hash" text NOT NULL,
  "platform" text NOT NULL,
  "revoked_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 installation_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("installation_id"),
  -- 唯一组合 token_hash：拒绝重复绑定或重复业务记录。
  UNIQUE ("token_hash"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "push_devices_check_0" CHECK (platform IN ('ios','android'))
);

-- 用户接受条款和隐私政策的版本及时间记录。
CREATE TABLE "consent_records" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  "terms_version" text NOT NULL,
  "privacy_version" text NOT NULL,
  -- 条款或接任者确认的时间。
  "accepted_at" timestamptz NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id")
);

-- 留言板帖子：待审与公开分开；首次公开起保留一个日历年。
CREATE TABLE "posts" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 稳定作者主体，游客注册接续不重写历史外键。
  "author_principal_id" uuid NOT NULL,
  "display_mode" text NOT NULL DEFAULT 'guest',
  "scope" text NOT NULL DEFAULT 'public',
  "category" text NOT NULL DEFAULT 'general',
  -- 公开标题；字符数按Unicode可见字符在服务层校验。
  "title" text NOT NULL,
  -- 纯文本正文；可见字符限额在内容服务校验。
  "body" text NOT NULL,
  -- 标准地区代码外键，不存持续定位。
  "region_code" text,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'pending',
  -- 首次公开时间；待审为NULL，后续编辑不得重置。
  "published_at" timestamptz,
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz,
  "deleted_at" timestamptz,
  -- 乐观锁/资料版本，用于拒绝旧版本覆盖。
  "version" integer NOT NULL DEFAULT 1,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "posts_check_0" CHECK (status IN ('pending','visible','rejected','hidden','deleted','expired')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "posts_check_1" CHECK (published_at IS NULL OR expires_at > published_at),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "posts_check_2" CHECK (status <> 'visible' OR (published_at IS NOT NULL AND expires_at IS NOT NULL)),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "posts_check_3" CHECK (display_mode IN ('guest','account')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "posts_check_4" CHECK (scope IN ('public','dating')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "posts_check_5" CHECK (category IN ('interest','city','general'))
);

-- 留言板回复：只允许同帖引用，独立计算公开时间与到期时间。
CREATE TABLE "comments" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "post_id" uuid NOT NULL,
  -- 稳定作者主体，游客注册接续不重写历史外键。
  "author_principal_id" uuid NOT NULL,
  "display_mode" text NOT NULL DEFAULT 'guest',
  "reply_to_id" uuid,
  -- 纯文本正文；可见字符限额在内容服务校验。
  "body" text NOT NULL DEFAULT '',
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'pending',
  -- 首次公开时间；待审为NULL，后续编辑不得重置。
  "published_at" timestamptz,
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz,
  "deleted_at" timestamptz,
  -- 乐观锁/资料版本，用于拒绝旧版本覆盖。
  "version" integer NOT NULL DEFAULT 1,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 post_id,id：拒绝重复绑定或重复业务记录。
  UNIQUE ("post_id","id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "comments_check_0" CHECK (status IN ('pending','visible','rejected','hidden','deleted','expired')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "comments_check_1" CHECK (published_at IS NULL OR expires_at > published_at),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "comments_check_2" CHECK (status <> 'visible' OR (published_at IS NOT NULL AND expires_at IS NOT NULL)),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "comments_check_3" CHECK (display_mode IN ('guest','account')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "comments_check_4" CHECK (reply_to_id IS NULL OR reply_to_id <> id)
);

-- 帖子兴趣分类关系，避免把筛选标签写入非结构化正文。
CREATE TABLE "post_interests" (
  "post_id" uuid NOT NULL,
  "interest_id" uuid NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("post_id","interest_id")
);

-- 帖子图片及显示顺序：位置0至8限制最多九张，资源跨对象绑定另由触发器保护。
CREATE TABLE "post_media" (
  "post_id" uuid NOT NULL,
  "media_id" uuid NOT NULL,
  -- 从0开始的展示顺序；唯一性及范围同时限制附件数量。
  "position" integer NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("post_id","media_id"),
  -- 唯一组合 media_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("media_id"),
  -- 唯一组合 post_id,position：拒绝重复绑定或重复业务记录。
  UNIQUE ("post_id","position"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "post_media_check_0" CHECK (position >= 0 AND position < 9)
);

-- 回复图片及显示顺序：位置0至2限制最多三张。
CREATE TABLE "comment_media" (
  "comment_id" uuid NOT NULL,
  "media_id" uuid NOT NULL,
  -- 从0开始的展示顺序；唯一性及范围同时限制附件数量。
  "position" integer NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("comment_id","media_id"),
  -- 唯一组合 media_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("media_id"),
  -- 唯一组合 comment_id,position：拒绝重复绑定或重复业务记录。
  UNIQUE ("comment_id","position"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "comment_media_check_0" CHECK (position >= 0 AND position < 3)
);

-- 图片展示版与缩略图：独立对象键，便于级联清理。
CREATE TABLE "media_variants" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "media_id" uuid NOT NULL,
  -- 记录分类；允许值或关系由当前表约束确定。
  "kind" text NOT NULL,
  -- 受控存储对象键，不允许客户端指定覆盖他人对象。
  "object_key" text NOT NULL,
  -- 实际文件字节数，上限5,000,000。
  "size_bytes" bigint NOT NULL,
  -- 解码宽度；与高度组合限制总像素。
  "width" integer NOT NULL,
  -- 解码高度；不能仅相信客户端声明。
  "height" integer NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 media_id,kind：拒绝重复绑定或重复业务记录。
  UNIQUE ("media_id","kind"),
  -- 唯一组合 object_key：拒绝重复绑定或重复业务记录。
  UNIQUE ("object_key"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "media_variants_check_0" CHECK (kind IN ('display','thumbnail')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "media_variants_check_1" CHECK (size_bytes > 0),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "media_variants_check_2" CHECK (width > 0 AND height > 0 AND GREATEST(width,height) <= 1920)
);

-- 群资料及人数：普通群默认100人，解散使用只读状态保留授权历史。
CREATE TABLE "groups" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 原始创建者，不能由客户端代指定。
  "creator_id" uuid NOT NULL,
  -- 当前群主，需与有效owner成员记录一致。
  "owner_id" uuid NOT NULL,
  -- 业务类型，具体组合与资格由类型约束及服务层校验。
  "type" text NOT NULL DEFAULT 'ordinary',
  "name" text NOT NULL,
  "description" text NOT NULL DEFAULT '',
  "rules" text NOT NULL DEFAULT '',
  -- 标准地区代码外键，不存持续定位。
  "region_code" text,
  "interest_id" uuid,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'active',
  -- 人数容量；活动名额与普通群人数采用各自业务规则。
  "capacity" integer DEFAULT 100,
  -- 当前有效群成员数，同事务更新并在提交时核对。
  "active_count" integer NOT NULL DEFAULT 0,
  -- 乐观锁/资料版本，用于拒绝旧版本覆盖。
  "version" integer NOT NULL DEFAULT 1,
  -- 群解散时点，停止加入与发送但保留授权只读历史。
  "dissolved_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "groups_check_0" CHECK (type IN ('ordinary','event','dating')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "groups_check_1" CHECK (status IN ('active','readonly','closed')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "groups_check_2" CHECK (active_count >= 0 AND (capacity IS NULL OR capacity >= active_count)),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "groups_check_3" CHECK (type <> 'ordinary' OR (capacity IS NOT NULL AND capacity > 0)),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "groups_check_4" CHECK (dissolved_at IS NULL OR status <> 'active')
);

-- 当前群成员资格：群主、普通成员、退出与封禁；不设入群审批状态。
CREATE TABLE "group_members" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 所属群；群访问必须结合有效成员和读取区间。
  "group_id" uuid NOT NULL,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  -- 身份角色，业务服务限制角色变更权限。
  "role" text NOT NULL DEFAULT 'member',
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'active',
  -- 用户自己的免打扰偏好，不改变读取资格。
  "muted" boolean NOT NULL DEFAULT false,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 group_id,account_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("group_id","account_id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "group_members_check_0" CHECK (role IN ('owner','member')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "group_members_check_1" CHECK (status IN ('active','left','banned'))
);

-- 每次加入的消息读取区间：重新加入不能自动读取退出期间历史。
CREATE TABLE "membership_periods" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "group_member_id" uuid NOT NULL,
  -- 本次加入可读的起始消息序号。
  "start_sequence" bigint NOT NULL,
  -- 退出时关闭区间；NULL表示当前开放加入区间。
  "end_sequence" bigint,
  "joined_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 退出群或已开始活动的时间。
  "left_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "membership_periods_check_0" CHECK (start_sequence >= 0),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "membership_periods_check_1" CHECK (end_sequence IS NULL OR end_sequence >= start_sequence),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "membership_periods_check_2" CHECK ((end_sequence IS NULL) = (left_at IS NULL))
);

-- 双向恋爱匹配：账号对规范排序并唯一，关闭恋爱不影响普通私聊。
CREATE TABLE "matches" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 按UUID排序的账号对较小一方。
  "account_low_id" uuid NOT NULL,
  -- 按UUID排序的账号对较大一方，禁止自会话。
  "account_high_id" uuid NOT NULL,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'active',
  "matched_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "ended_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 account_low_id,account_high_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("account_low_id","account_high_id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "matches_check_0" CHECK (account_low_id < account_high_id),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "matches_check_1" CHECK (status IN ('active','ended'))
);

-- 聊天会话：群、普通私聊和恋爱私聊分别校验字段组合及唯一性。
CREATE TABLE "conversations" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 业务类型，具体组合与资格由类型约束及服务层校验。
  "type" text NOT NULL,
  -- 所属群；群访问必须结合有效成员和读取区间。
  "group_id" uuid,
  -- 恋爱匹配来源，必须与恋爱会话账号对一致。
  "match_id" uuid,
  -- 按UUID排序的账号对较小一方。
  "account_low_id" uuid,
  -- 按UUID排序的账号对较大一方，禁止自会话。
  "account_high_id" uuid,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'active',
  -- 服务端分配消息序号的会话计数器。
  "last_sequence" bigint NOT NULL DEFAULT 0,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 group_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("group_id"),
  -- 唯一组合 match_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("match_id"),
  -- 唯一组合 type,account_low_id,account_high_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("type","account_low_id","account_high_id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "conversations_check_0" CHECK (type IN ('group','direct','dating')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "conversations_check_1" CHECK (status IN ('active','readonly','closed')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "conversations_check_2" CHECK (last_sequence >= 0),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "conversations_check_3" CHECK ((type='group' AND group_id IS NOT NULL AND match_id IS NULL AND account_low_id IS NULL AND account_high_id IS NULL) OR (type='direct' AND group_id IS NULL AND match_id IS NULL AND account_low_id < account_high_id) OR (type='dating' AND group_id IS NULL AND match_id IS NOT NULL AND account_low_id < account_high_id)),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "conversations_check_4" CHECK (type='group' OR (account_low_id IS NOT NULL AND account_high_id IS NOT NULL))
);

-- 两人会话参与者及个人设置；群聊读取群成员资格而不是此表。
CREATE TABLE "conversation_participants" (
  -- 所属会话，服务层按群/普通/恋爱分别鉴权。
  "conversation_id" uuid NOT NULL,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  -- 用户自己的免打扰偏好，不改变读取资格。
  "muted" boolean NOT NULL DEFAULT false,
  "archived_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("conversation_id","account_id")
);

-- 文字消息：客户端ID去重，序号保证补取排序，原发送时间起保留一年。
CREATE TABLE "messages" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 所属会话，服务层按群/普通/恋爱分别鉴权。
  "conversation_id" uuid NOT NULL,
  "sender_id" uuid NOT NULL,
  -- 客户端固定重试ID；UUIDv7版本及离线时间窗由服务层校验。
  "client_message_id" uuid NOT NULL,
  -- 会话内单调序号，通过API按字符串返回避免精度丢失。
  "sequence" bigint NOT NULL,
  -- 纯文本正文；可见字符限额在内容服务校验。
  "body" text NOT NULL,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'visible',
  -- 消息原始发送时间，决定一年保留起算点。
  "sent_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 sender_id,client_message_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("sender_id","client_message_id"),
  -- 唯一组合 conversation_id,sequence：拒绝重复绑定或重复业务记录。
  UNIQUE ("conversation_id","sequence"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "messages_check_0" CHECK (status IN ('visible','deleted','expired')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "messages_check_1" CHECK (sequence > 0),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "messages_check_2" CHECK (expires_at > sent_at)
);

-- 每个账号的会话已读位置：单调前进及可读范围仍由服务层验证。
CREATE TABLE "read_cursors" (
  -- 所属会话，服务层按群/普通/恋爱分别鉴权。
  "conversation_id" uuid NOT NULL,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  -- 已读高水位，不代表获得之前消息的读取权。
  "last_read_sequence" bigint NOT NULL DEFAULT 0,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("conversation_id","account_id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "read_cursors_check_0" CHECK (last_read_sequence >= 0)
);

-- 消息重试收据：只留摘要和结果元信息，不复制到期正文。
CREATE TABLE "message_request_receipts" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "sender_id" uuid NOT NULL,
  -- 客户端固定重试ID；UUIDv7版本及离线时间窗由服务层校验。
  "client_message_id" uuid NOT NULL,
  -- 所属会话，服务层按群/普通/恋爱分别鉴权。
  "conversation_id" uuid NOT NULL,
  -- 规范化请求摘要，同键不同内容应拒绝。
  "request_hash" text NOT NULL,
  "message_id" uuid,
  -- 会话内单调序号，通过API按字符串返回避免精度丢失。
  "sequence" bigint NOT NULL,
  "result_state" text NOT NULL DEFAULT 'saved',
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 sender_id,client_message_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("sender_id","client_message_id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "message_request_receipts_check_0" CHECK (result_state IN ('saved','expired'))
);

-- 18岁自我声明：不允许写verified状态，撤销后按一年保留处理。
CREATE TABLE "age_declarations" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'self_declared',
  "statement_version" text NOT NULL,
  "declared_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 年龄声明撤销时点，撤销记录从此保留一年。
  "withdrawn_at" timestamptz,
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 account_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("account_id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "age_declarations_check_0" CHECK (status IN ('self_declared','withdrawn')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "age_declarations_check_1" CHECK ((status='self_declared' AND withdrawn_at IS NULL AND expires_at IS NULL) OR (status='withdrawn' AND withdrawn_at IS NOT NULL AND expires_at IS NOT NULL))
);

-- 独立恋爱资料与展示意愿：开启及发现资格由统一权限服务校验。
CREATE TABLE "dating_profiles" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  -- 主动开启恋爱意愿，不是年龄认证状态。
  "enabled" boolean NOT NULL DEFAULT false,
  "intro" text NOT NULL DEFAULT '',
  -- 标准地区代码外键，不存持续定位。
  "region_code" text,
  "preferences" jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'pending',
  -- 乐观锁/资料版本，用于拒绝旧版本覆盖。
  "version" integer NOT NULL DEFAULT 1,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 account_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("account_id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "dating_profiles_check_0" CHECK (status IN ('pending','visible','rejected','hidden'))
);

-- 恋爱资料独立兴趣关系，不自动继承普通公开资料。
CREATE TABLE "dating_profile_interests" (
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  "interest_id" uuid NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("account_id","interest_id")
);

-- 恋爱照片：位置0至5限制六张，读取时需恋爱资格和资源授权。
CREATE TABLE "dating_photos" (
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  "media_id" uuid NOT NULL,
  -- 从0开始的展示顺序；唯一性及范围同时限制附件数量。
  "position" integer NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("account_id","media_id"),
  -- 唯一组合 media_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("media_id"),
  -- 唯一组合 account_id,position：拒绝重复绑定或重复业务记录。
  UNIQUE ("account_id","position"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "dating_photos_check_0" CHECK (position >= 0 AND position < 6)
);

-- 有方向的喜欢与跳过：禁止自己对自己操作，反向喜欢触发匹配事务。
CREATE TABLE "likes" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "from_account_id" uuid NOT NULL,
  "to_account_id" uuid NOT NULL,
  "decision" text NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 from_account_id,to_account_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("from_account_id","to_account_id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "likes_check_0" CHECK (from_account_id <> to_account_id),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "likes_check_1" CHECK (decision IN ('like','skip'))
);

-- 活动公开资料、时间、名额和生命周期；详细集合信息使用独立表。
CREATE TABLE "events" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 原始创建者，不能由客户端代指定。
  "creator_id" uuid NOT NULL,
  -- 所属群；群访问必须结合有效成员和读取区间。
  "group_id" uuid,
  "cover_media_id" uuid,
  -- 公开标题；字符数按Unicode可见字符在服务层校验。
  "title" text NOT NULL,
  "description" text NOT NULL,
  -- 标准地区代码外键，不存持续定位。
  "region_code" text NOT NULL,
  -- 活动开始时间，取消报名与退出逻辑以此为边界。
  "starts_at" timestamptz NOT NULL,
  -- 计划结束时间，须晚于开始。
  "ends_at" timestamptz NOT NULL,
  -- 报名截止时间，不能晚于活动开始。
  "registration_deadline" timestamptz NOT NULL,
  -- 人数容量；活动名额与普通群人数采用各自业务规则。
  "capacity" integer NOT NULL,
  -- 占用的活动报名名额；开始后退出不减此数。
  "confirmed_count" integer NOT NULL DEFAULT 0,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'draft',
  "cancellation_policy" text NOT NULL,
  "cancel_reason" text,
  -- 取消操作生效时间。
  "cancelled_at" timestamptz,
  -- 提前结束的实际时点，相关记录从此起算保留。
  "actual_ended_at" timestamptz,
  -- 乐观锁/资料版本，用于拒绝旧版本覆盖。
  "version" integer NOT NULL DEFAULT 1,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 group_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("group_id"),
  -- 唯一组合 cover_media_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("cover_media_id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "events_check_0" CHECK (capacity > 0 AND confirmed_count >= 0 AND confirmed_count <= capacity),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "events_check_1" CHECK (registration_deadline <= starts_at AND starts_at < ends_at),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "events_check_2" CHECK (status IN ('draft','pending','open','ended','cancelled','hidden')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "events_check_3" CHECK (status <> 'cancelled' OR (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL))
);

-- 活动集合信息：只供发布者及有资格的报名者读取。
CREATE TABLE "event_private_details" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "event_id" uuid NOT NULL,
  "meeting_instructions" text NOT NULL,
  -- 乐观锁/资料版本，用于拒绝旧版本覆盖。
  "version" integer NOT NULL DEFAULT 1,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 event_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("event_id")
);

-- 活动与兴趣的分类关系，支持兴趣筛选。
CREATE TABLE "event_interests" (
  "event_id" uuid NOT NULL,
  "interest_id" uuid NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("event_id","interest_id")
);

-- 报名状态：开始前取消释放名额，开始后left退出不释放历史报名名额。
CREATE TABLE "event_registrations" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "event_id" uuid NOT NULL,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'confirmed',
  -- 报名时确认的活动规则版本。
  "accepted_event_version" integer NOT NULL,
  "registered_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 取消操作生效时间。
  "cancelled_at" timestamptz,
  -- 退出群或已开始活动的时间。
  "left_at" timestamptz,
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 event_id,account_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("event_id","account_id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "event_registrations_check_0" CHECK (status IN ('confirmed','cancelled','left'))
);

-- 活动变更版本及字段级摘要，用于确认修改与通知报名者。
CREATE TABLE "event_revisions" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "event_id" uuid NOT NULL,
  -- 乐观锁/资料版本，用于拒绝旧版本覆盖。
  "version" integer NOT NULL,
  "changed_fields" jsonb NOT NULL,
  -- 操作理由，不记录令牌或无关隐私。
  "reason" text NOT NULL,
  "actor_id" uuid NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 event_id,version：拒绝重复绑定或重复业务记录。
  UNIQUE ("event_id","version")
);

-- 账号屏蔽关系：有方向记录，私聊服务必须检查双方方向。
CREATE TABLE "blocks" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "blocker_id" uuid NOT NULL,
  "blocked_id" uuid NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 blocker_id,blocked_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("blocker_id","blocked_id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "blocks_check_0" CHECK (blocker_id <> blocked_id)
);

-- 独立管理员身份、角色与MFA配置，不复用手机端普通账号。
CREATE TABLE "admin_accounts" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "login_name" text NOT NULL,
  -- 管理员密码安全摘要，禁止明文存储。
  "password_hash" text NOT NULL,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'active',
  -- 身份角色，业务服务限制角色变更权限。
  "role" text NOT NULL,
  -- MFA秘密的加密存储，不进入普通日志。
  "mfa_secret_ciphertext" text NOT NULL,
  -- 权限/会话失效版本，撤销后服务端重新鉴权。
  "auth_version" integer NOT NULL DEFAULT 1,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 login_name：拒绝重复绑定或重复业务记录。
  UNIQUE ("login_name"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "admin_accounts_check_0" CHECK (status IN ('active','disabled')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "admin_accounts_check_1" CHECK (role IN ('reviewer','operator','auditor'))
);

-- 已通过MFA的管理会话，支持过期与主动撤销。
CREATE TABLE "admin_sessions" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "admin_id" uuid NOT NULL,
  -- 令牌查找及唯一性摘要。
  "token_hash" text NOT NULL,
  "mfa_verified_at" timestamptz NOT NULL,
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz NOT NULL,
  "revoked_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 token_hash：拒绝重复绑定或重复业务记录。
  UNIQUE ("token_hash")
);

-- 举报：只能绑定一个实际目标，处理时检查举报者访问资格。
CREATE TABLE "reports" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "reporter_principal_id" uuid NOT NULL,
  "target_type" text NOT NULL,
  "post_id" uuid,
  "comment_id" uuid,
  "message_id" uuid,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid,
  -- 所属群；群访问必须结合有效成员和读取区间。
  "group_id" uuid,
  "event_id" uuid,
  "reason_code" text NOT NULL,
  -- 举报补充说明，按必要范围向审核者开放。
  "detail" text NOT NULL DEFAULT '',
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'open',
  "resolved_at" timestamptz,
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "reports_check_0" CHECK (num_nonnulls(post_id,comment_id,message_id,account_id,group_id,event_id)=1 AND ((target_type='post' AND post_id IS NOT NULL) OR (target_type='comment' AND comment_id IS NOT NULL) OR (target_type='message' AND message_id IS NOT NULL) OR (target_type='account' AND account_id IS NOT NULL) OR (target_type='group' AND group_id IS NOT NULL) OR (target_type='event' AND event_id IS NOT NULL))),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "reports_check_1" CHECK (status IN ('open','reviewing','resolved'))
);

-- 管理员处理流水：记录对象、理由和操作者，不复制聊天正文。
CREATE TABLE "moderation_actions" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "report_id" uuid,
  "admin_id" uuid NOT NULL,
  "target_type" text NOT NULL,
  "post_id" uuid,
  "comment_id" uuid,
  "message_id" uuid,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid,
  -- 所属群；群访问必须结合有效成员和读取区间。
  "group_id" uuid,
  "event_id" uuid,
  "action" text NOT NULL,
  -- 操作理由，不记录令牌或无关隐私。
  "reason" text NOT NULL,
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "moderation_actions_check_0" CHECK (num_nonnulls(post_id,comment_id,message_id,account_id,group_id,event_id)=1 AND ((target_type='post' AND post_id IS NOT NULL) OR (target_type='comment' AND comment_id IS NOT NULL) OR (target_type='message' AND message_id IS NOT NULL) OR (target_type='account' AND account_id IS NOT NULL) OR (target_type='group' AND group_id IS NOT NULL) OR (target_type='event' AND event_id IS NOT NULL)))
);

-- 通知跳转线索：目标读取重新鉴权，不将缓存参数当作访问授权。
CREATE TABLE "notifications" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  -- 业务类型，具体组合与资格由类型约束及服务层校验。
  "type" text NOT NULL,
  "target_type" text NOT NULL,
  "target_id" uuid NOT NULL,
  -- 通知参数不得泄漏私有正文或过期引用。
  "params" jsonb NOT NULL DEFAULT '{}'::jsonb,
  "read_at" timestamptz,
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id")
);

-- 可靠异步任务：与业务事务一起写入，用租约和幂等消费者处理外部调用。
CREATE TABLE "outbox" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "event_type" text NOT NULL,
  "aggregate_type" text NOT NULL,
  "aggregate_id" uuid NOT NULL,
  -- 任务参数只保存必要对象ID与版本，避免永久复制正文。
  "payload" jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'pending',
  -- 已尝试次数，用于限次及退避。
  "attempts" integer NOT NULL DEFAULT 0,
  -- 下次可领取任务时刻。
  "available_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 任务领取租约到期，可在崩溃后重新领取。
  "lease_until" timestamptz,
  "last_error_code" text,
  -- 任务唯一去重键，重复请求不产生重复业务效果。
  "dedupe_key" text NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 dedupe_key：拒绝重复绑定或重复业务记录。
  UNIQUE ("dedupe_key"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "outbox_check_0" CHECK (status IN ('pending','processing','succeeded','dead')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "outbox_check_1" CHECK (attempts >= 0)
);

-- 消费者已处理事件键：防止供应商回调或广播重复消费。
CREATE TABLE "processed_events" (
  "consumer" text NOT NULL,
  "event_key" text NOT NULL,
  "processed_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("consumer","event_key")
);

-- 请求幂等记录：绑定主体、路由、键和请求摘要，不缓存秘密令牌。
CREATE TABLE "idempotency_records" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "actor_key" text NOT NULL,
  "method" text NOT NULL,
  "route_key" text NOT NULL,
  "key" text NOT NULL,
  -- 规范化请求摘要，同键不同内容应拒绝。
  "request_hash" text NOT NULL,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL,
  "resource_type" text,
  "resource_id" uuid,
  "response_code" integer,
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 actor_key,method,route_key,key：拒绝重复绑定或重复业务记录。
  UNIQUE ("actor_key","method","route_key","key"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "idempotency_records_check_0" CHECK (status IN ('pending','completed'))
);

-- 可重试资源清理任务：完成之前保留删除所需元信息。
CREATE TABLE "deletion_jobs" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "target_type" text NOT NULL,
  "target_id" uuid NOT NULL,
  -- 操作理由，不记录令牌或无关隐私。
  "reason" text NOT NULL,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'pending',
  "requested_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "completed_at" timestamptz,
  "retry_after" timestamptz,
  -- 任务唯一去重键，重复请求不产生重复业务效果。
  "dedupe_key" text NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 dedupe_key：拒绝重复绑定或重复业务记录。
  UNIQUE ("dedupe_key"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "deletion_jobs_check_0" CHECK (status IN ('pending','processing','completed','failed'))
);

-- 删除账本：备份恢复后重放删除，不含正文与邮箱。
CREATE TABLE "deletion_ledger" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "target_type" text NOT NULL,
  "target_id" uuid NOT NULL,
  "deleted_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "purge_reason" text NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 target_type,target_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("target_type","target_id")
);

-- 待审核内容版本：只存允许字段的短期快照，不作永久聊天备份。
CREATE TABLE "moderation_revisions" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "target_type" text NOT NULL,
  "target_id" uuid NOT NULL,
  -- 乐观锁/资料版本，用于拒绝旧版本覆盖。
  "version" integer NOT NULL,
  "fields" jsonb NOT NULL,
  -- 到期时间；读取必须过滤到期记录，后台再进行物理清理。
  "expires_at" timestamptz NOT NULL,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 target_type,target_id,version：拒绝重复绑定或重复业务记录。
  UNIQUE ("target_type","target_id","version")
);

-- 普通私聊首次联系计数：删除消息或创建空会话不能重置未回复额度。
CREATE TABLE "direct_contact_states" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 按UUID排序的账号对较小一方。
  "account_low_id" uuid NOT NULL,
  -- 按UUID排序的账号对较大一方，禁止自会话。
  "account_high_id" uuid NOT NULL,
  "low_sent_count" integer NOT NULL DEFAULT 0,
  "high_sent_count" integer NOT NULL DEFAULT 0,
  "low_first_sent_at" timestamptz,
  "high_first_sent_at" timestamptz,
  "low_first_replied_at" timestamptz,
  "high_first_replied_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 account_low_id,account_high_id：拒绝重复绑定或重复业务记录。
  UNIQUE ("account_low_id","account_high_id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "direct_contact_states_check_0" CHECK (account_low_id < account_high_id),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "direct_contact_states_check_1" CHECK (low_sent_count >= 0 AND high_sent_count >= 0)
);

-- 东京自然日额度：每日新联系和建群计数，重复请求不重复扣次数。
CREATE TABLE "daily_usage" (
  -- 关联注册账号；公开接口只返回允许的资料。
  "account_id" uuid NOT NULL,
  -- 东京时区自然日，不使用客户端日期作为扣额依据。
  "day_jst" date NOT NULL,
  -- 记录分类；允许值或关系由当前表约束确定。
  "kind" text NOT NULL,
  -- 当日已使用次数；去重后与业务操作原子更新。
  "used_count" integer NOT NULL DEFAULT 0,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("account_id","day_jst","kind"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "daily_usage_check_0" CHECK (kind IN ('new_dm_target','group_create')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "daily_usage_check_1" CHECK (used_count >= 0)
);

-- 群主自愿转让请求：接任者接受后才由事务变更群主。
CREATE TABLE "group_owner_transfers" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 所属群；群访问必须结合有效成员和读取区间。
  "group_id" uuid NOT NULL,
  "from_account_id" uuid NOT NULL,
  "to_account_id" uuid NOT NULL,
  -- 当前生命周期状态，允许值由下方CHECK约束限制。
  "status" text NOT NULL DEFAULT 'pending',
  -- 条款或接任者确认的时间。
  "accepted_at" timestamptz,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "group_owner_transfers_check_0" CHECK (status IN ('pending','accepted','cancelled')),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "group_owner_transfers_check_1" CHECK (from_account_id <> to_account_id),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "group_owner_transfers_check_2" CHECK (status <> 'accepted' OR accepted_at IS NOT NULL)
);

-- 运营配置：初始群及私聊配额，后续修改记录版本和管理员。
CREATE TABLE "policy_configs" (
  -- 内部唯一标识，不能仅凭ID获得对象访问权。
  "id" uuid NOT NULL DEFAULT gen_random_uuid(),
  -- 记录创建时间，统一存储UTC。
  "created_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最近修改时间，由写入服务显式维护。
  "updated_at" timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "key" text NOT NULL,
  "value" jsonb NOT NULL,
  -- 乐观锁/资料版本，用于拒绝旧版本覆盖。
  "version" integer NOT NULL DEFAULT 1,
  "updated_by_admin_id" uuid,
  -- 主键：唯一定位记录；连接表使用组合键避免重复关系。
  PRIMARY KEY ("id"),
  -- 唯一组合 key：拒绝重复绑定或重复业务记录。
  UNIQUE ("key"),
  -- 检查约束：限制允许状态、合法字段组合或数值边界。
  CONSTRAINT "policy_configs_check_0" CHECK (version > 0)
);

-- 外键及关联索引：RESTRICT防止绕过资源清理；索引支持关联查询及引用检查。
ALTER TABLE "principals" ADD CONSTRAINT "principals_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "principals_account_id_idx" ON "principals"("account_id");
ALTER TABLE "guest_credentials" ADD CONSTRAINT "guest_credentials_principal_id_fkey" FOREIGN KEY ("principal_id") REFERENCES "principals"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "guest_credentials_principal_id_idx" ON "guest_credentials"("principal_id");
ALTER TABLE "sessions" ADD CONSTRAINT "sessions_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "sessions_account_id_idx" ON "sessions"("account_id");
ALTER TABLE "sessions" ADD CONSTRAINT "sessions_replaced_by_id_fkey" FOREIGN KEY ("replaced_by_id") REFERENCES "sessions"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "sessions_replaced_by_id_idx" ON "sessions"("replaced_by_id");
ALTER TABLE "regions" ADD CONSTRAINT "regions_parent_code_fkey" FOREIGN KEY ("parent_code") REFERENCES "regions"("code") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "regions_parent_code_idx" ON "regions"("parent_code");
ALTER TABLE "media_assets" ADD CONSTRAINT "media_assets_owner_principal_id_fkey" FOREIGN KEY ("owner_principal_id") REFERENCES "principals"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "media_assets_owner_principal_id_idx" ON "media_assets"("owner_principal_id");
ALTER TABLE "profiles" ADD CONSTRAINT "profiles_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "profiles_account_id_idx" ON "profiles"("account_id");
ALTER TABLE "profiles" ADD CONSTRAINT "profiles_avatar_media_id_fkey" FOREIGN KEY ("avatar_media_id") REFERENCES "media_assets"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "profiles_avatar_media_id_idx" ON "profiles"("avatar_media_id");
ALTER TABLE "profiles" ADD CONSTRAINT "profiles_region_code_fkey" FOREIGN KEY ("region_code") REFERENCES "regions"("code") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "profiles_region_code_idx" ON "profiles"("region_code");
ALTER TABLE "account_settings" ADD CONSTRAINT "account_settings_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "account_settings_account_id_idx" ON "account_settings"("account_id");
ALTER TABLE "profile_interests" ADD CONSTRAINT "profile_interests_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "profile_interests_account_id_idx" ON "profile_interests"("account_id");
ALTER TABLE "profile_interests" ADD CONSTRAINT "profile_interests_interest_id_fkey" FOREIGN KEY ("interest_id") REFERENCES "interests"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "profile_interests_interest_id_idx" ON "profile_interests"("interest_id");
ALTER TABLE "push_devices" ADD CONSTRAINT "push_devices_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "push_devices_account_id_idx" ON "push_devices"("account_id");
ALTER TABLE "consent_records" ADD CONSTRAINT "consent_records_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "consent_records_account_id_idx" ON "consent_records"("account_id");
ALTER TABLE "posts" ADD CONSTRAINT "posts_author_principal_id_fkey" FOREIGN KEY ("author_principal_id") REFERENCES "principals"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "posts_author_principal_id_idx" ON "posts"("author_principal_id");
ALTER TABLE "posts" ADD CONSTRAINT "posts_region_code_fkey" FOREIGN KEY ("region_code") REFERENCES "regions"("code") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "posts_region_code_idx" ON "posts"("region_code");
ALTER TABLE "comments" ADD CONSTRAINT "comments_post_id_fkey" FOREIGN KEY ("post_id") REFERENCES "posts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "comments_post_id_idx" ON "comments"("post_id");
ALTER TABLE "comments" ADD CONSTRAINT "comments_author_principal_id_fkey" FOREIGN KEY ("author_principal_id") REFERENCES "principals"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "comments_author_principal_id_idx" ON "comments"("author_principal_id");
ALTER TABLE "comments" ADD CONSTRAINT "comments_reply_to_id_fkey" FOREIGN KEY ("reply_to_id") REFERENCES "comments"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "comments_reply_to_id_idx" ON "comments"("reply_to_id");
ALTER TABLE "post_interests" ADD CONSTRAINT "post_interests_post_id_fkey" FOREIGN KEY ("post_id") REFERENCES "posts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "post_interests_post_id_idx" ON "post_interests"("post_id");
ALTER TABLE "post_interests" ADD CONSTRAINT "post_interests_interest_id_fkey" FOREIGN KEY ("interest_id") REFERENCES "interests"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "post_interests_interest_id_idx" ON "post_interests"("interest_id");
ALTER TABLE "post_media" ADD CONSTRAINT "post_media_post_id_fkey" FOREIGN KEY ("post_id") REFERENCES "posts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "post_media_post_id_idx" ON "post_media"("post_id");
ALTER TABLE "post_media" ADD CONSTRAINT "post_media_media_id_fkey" FOREIGN KEY ("media_id") REFERENCES "media_assets"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "post_media_media_id_idx" ON "post_media"("media_id");
ALTER TABLE "comment_media" ADD CONSTRAINT "comment_media_comment_id_fkey" FOREIGN KEY ("comment_id") REFERENCES "comments"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "comment_media_comment_id_idx" ON "comment_media"("comment_id");
ALTER TABLE "comment_media" ADD CONSTRAINT "comment_media_media_id_fkey" FOREIGN KEY ("media_id") REFERENCES "media_assets"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "comment_media_media_id_idx" ON "comment_media"("media_id");
ALTER TABLE "media_variants" ADD CONSTRAINT "media_variants_media_id_fkey" FOREIGN KEY ("media_id") REFERENCES "media_assets"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "media_variants_media_id_idx" ON "media_variants"("media_id");
ALTER TABLE "groups" ADD CONSTRAINT "groups_creator_id_fkey" FOREIGN KEY ("creator_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "groups_creator_id_idx" ON "groups"("creator_id");
ALTER TABLE "groups" ADD CONSTRAINT "groups_owner_id_fkey" FOREIGN KEY ("owner_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "groups_owner_id_idx" ON "groups"("owner_id");
ALTER TABLE "groups" ADD CONSTRAINT "groups_region_code_fkey" FOREIGN KEY ("region_code") REFERENCES "regions"("code") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "groups_region_code_idx" ON "groups"("region_code");
ALTER TABLE "groups" ADD CONSTRAINT "groups_interest_id_fkey" FOREIGN KEY ("interest_id") REFERENCES "interests"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "groups_interest_id_idx" ON "groups"("interest_id");
ALTER TABLE "group_members" ADD CONSTRAINT "group_members_group_id_fkey" FOREIGN KEY ("group_id") REFERENCES "groups"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "group_members_group_id_idx" ON "group_members"("group_id");
ALTER TABLE "group_members" ADD CONSTRAINT "group_members_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "group_members_account_id_idx" ON "group_members"("account_id");
ALTER TABLE "membership_periods" ADD CONSTRAINT "membership_periods_group_member_id_fkey" FOREIGN KEY ("group_member_id") REFERENCES "group_members"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "membership_periods_group_member_id_idx" ON "membership_periods"("group_member_id");
ALTER TABLE "matches" ADD CONSTRAINT "matches_account_low_id_fkey" FOREIGN KEY ("account_low_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "matches_account_low_id_idx" ON "matches"("account_low_id");
ALTER TABLE "matches" ADD CONSTRAINT "matches_account_high_id_fkey" FOREIGN KEY ("account_high_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "matches_account_high_id_idx" ON "matches"("account_high_id");
ALTER TABLE "conversations" ADD CONSTRAINT "conversations_group_id_fkey" FOREIGN KEY ("group_id") REFERENCES "groups"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "conversations_group_id_idx" ON "conversations"("group_id");
ALTER TABLE "conversations" ADD CONSTRAINT "conversations_match_id_fkey" FOREIGN KEY ("match_id") REFERENCES "matches"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "conversations_match_id_idx" ON "conversations"("match_id");
ALTER TABLE "conversations" ADD CONSTRAINT "conversations_account_low_id_fkey" FOREIGN KEY ("account_low_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "conversations_account_low_id_idx" ON "conversations"("account_low_id");
ALTER TABLE "conversations" ADD CONSTRAINT "conversations_account_high_id_fkey" FOREIGN KEY ("account_high_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "conversations_account_high_id_idx" ON "conversations"("account_high_id");
ALTER TABLE "conversation_participants" ADD CONSTRAINT "conversation_participants_conversation_id_fkey" FOREIGN KEY ("conversation_id") REFERENCES "conversations"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "conversation_participants_conversation_id_idx" ON "conversation_participants"("conversation_id");
ALTER TABLE "conversation_participants" ADD CONSTRAINT "conversation_participants_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "conversation_participants_account_id_idx" ON "conversation_participants"("account_id");
ALTER TABLE "messages" ADD CONSTRAINT "messages_conversation_id_fkey" FOREIGN KEY ("conversation_id") REFERENCES "conversations"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "messages_conversation_id_idx" ON "messages"("conversation_id");
ALTER TABLE "messages" ADD CONSTRAINT "messages_sender_id_fkey" FOREIGN KEY ("sender_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "messages_sender_id_idx" ON "messages"("sender_id");
ALTER TABLE "read_cursors" ADD CONSTRAINT "read_cursors_conversation_id_fkey" FOREIGN KEY ("conversation_id") REFERENCES "conversations"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "read_cursors_conversation_id_idx" ON "read_cursors"("conversation_id");
ALTER TABLE "read_cursors" ADD CONSTRAINT "read_cursors_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "read_cursors_account_id_idx" ON "read_cursors"("account_id");
ALTER TABLE "message_request_receipts" ADD CONSTRAINT "message_request_receipts_sender_id_fkey" FOREIGN KEY ("sender_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "message_request_receipts_sender_id_idx" ON "message_request_receipts"("sender_id");
ALTER TABLE "message_request_receipts" ADD CONSTRAINT "message_request_receipts_conversation_id_fkey" FOREIGN KEY ("conversation_id") REFERENCES "conversations"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "message_request_receipts_conversation_id_idx" ON "message_request_receipts"("conversation_id");
ALTER TABLE "message_request_receipts" ADD CONSTRAINT "message_request_receipts_message_id_fkey" FOREIGN KEY ("message_id") REFERENCES "messages"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "message_request_receipts_message_id_idx" ON "message_request_receipts"("message_id");
ALTER TABLE "age_declarations" ADD CONSTRAINT "age_declarations_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "age_declarations_account_id_idx" ON "age_declarations"("account_id");
ALTER TABLE "dating_profiles" ADD CONSTRAINT "dating_profiles_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "dating_profiles_account_id_idx" ON "dating_profiles"("account_id");
ALTER TABLE "dating_profiles" ADD CONSTRAINT "dating_profiles_region_code_fkey" FOREIGN KEY ("region_code") REFERENCES "regions"("code") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "dating_profiles_region_code_idx" ON "dating_profiles"("region_code");
ALTER TABLE "dating_profile_interests" ADD CONSTRAINT "dating_profile_interests_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "dating_profiles"("account_id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "dating_profile_interests_account_id_idx" ON "dating_profile_interests"("account_id");
ALTER TABLE "dating_profile_interests" ADD CONSTRAINT "dating_profile_interests_interest_id_fkey" FOREIGN KEY ("interest_id") REFERENCES "interests"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "dating_profile_interests_interest_id_idx" ON "dating_profile_interests"("interest_id");
ALTER TABLE "dating_photos" ADD CONSTRAINT "dating_photos_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "dating_profiles"("account_id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "dating_photos_account_id_idx" ON "dating_photos"("account_id");
ALTER TABLE "dating_photos" ADD CONSTRAINT "dating_photos_media_id_fkey" FOREIGN KEY ("media_id") REFERENCES "media_assets"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "dating_photos_media_id_idx" ON "dating_photos"("media_id");
ALTER TABLE "likes" ADD CONSTRAINT "likes_from_account_id_fkey" FOREIGN KEY ("from_account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "likes_from_account_id_idx" ON "likes"("from_account_id");
ALTER TABLE "likes" ADD CONSTRAINT "likes_to_account_id_fkey" FOREIGN KEY ("to_account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "likes_to_account_id_idx" ON "likes"("to_account_id");
ALTER TABLE "events" ADD CONSTRAINT "events_creator_id_fkey" FOREIGN KEY ("creator_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "events_creator_id_idx" ON "events"("creator_id");
ALTER TABLE "events" ADD CONSTRAINT "events_group_id_fkey" FOREIGN KEY ("group_id") REFERENCES "groups"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "events_group_id_idx" ON "events"("group_id");
ALTER TABLE "events" ADD CONSTRAINT "events_cover_media_id_fkey" FOREIGN KEY ("cover_media_id") REFERENCES "media_assets"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "events_cover_media_id_idx" ON "events"("cover_media_id");
ALTER TABLE "events" ADD CONSTRAINT "events_region_code_fkey" FOREIGN KEY ("region_code") REFERENCES "regions"("code") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "events_region_code_idx" ON "events"("region_code");
ALTER TABLE "event_private_details" ADD CONSTRAINT "event_private_details_event_id_fkey" FOREIGN KEY ("event_id") REFERENCES "events"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "event_private_details_event_id_idx" ON "event_private_details"("event_id");
ALTER TABLE "event_interests" ADD CONSTRAINT "event_interests_event_id_fkey" FOREIGN KEY ("event_id") REFERENCES "events"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "event_interests_event_id_idx" ON "event_interests"("event_id");
ALTER TABLE "event_interests" ADD CONSTRAINT "event_interests_interest_id_fkey" FOREIGN KEY ("interest_id") REFERENCES "interests"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "event_interests_interest_id_idx" ON "event_interests"("interest_id");
ALTER TABLE "event_registrations" ADD CONSTRAINT "event_registrations_event_id_fkey" FOREIGN KEY ("event_id") REFERENCES "events"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "event_registrations_event_id_idx" ON "event_registrations"("event_id");
ALTER TABLE "event_registrations" ADD CONSTRAINT "event_registrations_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "event_registrations_account_id_idx" ON "event_registrations"("account_id");
ALTER TABLE "event_revisions" ADD CONSTRAINT "event_revisions_event_id_fkey" FOREIGN KEY ("event_id") REFERENCES "events"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "event_revisions_event_id_idx" ON "event_revisions"("event_id");
ALTER TABLE "event_revisions" ADD CONSTRAINT "event_revisions_actor_id_fkey" FOREIGN KEY ("actor_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "event_revisions_actor_id_idx" ON "event_revisions"("actor_id");
ALTER TABLE "blocks" ADD CONSTRAINT "blocks_blocker_id_fkey" FOREIGN KEY ("blocker_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "blocks_blocker_id_idx" ON "blocks"("blocker_id");
ALTER TABLE "blocks" ADD CONSTRAINT "blocks_blocked_id_fkey" FOREIGN KEY ("blocked_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "blocks_blocked_id_idx" ON "blocks"("blocked_id");
ALTER TABLE "admin_sessions" ADD CONSTRAINT "admin_sessions_admin_id_fkey" FOREIGN KEY ("admin_id") REFERENCES "admin_accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "admin_sessions_admin_id_idx" ON "admin_sessions"("admin_id");
ALTER TABLE "reports" ADD CONSTRAINT "reports_reporter_principal_id_fkey" FOREIGN KEY ("reporter_principal_id") REFERENCES "principals"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "reports_reporter_principal_id_idx" ON "reports"("reporter_principal_id");
ALTER TABLE "reports" ADD CONSTRAINT "reports_post_id_fkey" FOREIGN KEY ("post_id") REFERENCES "posts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "reports_post_id_idx" ON "reports"("post_id");
ALTER TABLE "reports" ADD CONSTRAINT "reports_comment_id_fkey" FOREIGN KEY ("comment_id") REFERENCES "comments"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "reports_comment_id_idx" ON "reports"("comment_id");
ALTER TABLE "reports" ADD CONSTRAINT "reports_message_id_fkey" FOREIGN KEY ("message_id") REFERENCES "messages"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "reports_message_id_idx" ON "reports"("message_id");
ALTER TABLE "reports" ADD CONSTRAINT "reports_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "reports_account_id_idx" ON "reports"("account_id");
ALTER TABLE "reports" ADD CONSTRAINT "reports_group_id_fkey" FOREIGN KEY ("group_id") REFERENCES "groups"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "reports_group_id_idx" ON "reports"("group_id");
ALTER TABLE "reports" ADD CONSTRAINT "reports_event_id_fkey" FOREIGN KEY ("event_id") REFERENCES "events"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "reports_event_id_idx" ON "reports"("event_id");
ALTER TABLE "moderation_actions" ADD CONSTRAINT "moderation_actions_report_id_fkey" FOREIGN KEY ("report_id") REFERENCES "reports"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "moderation_actions_report_id_idx" ON "moderation_actions"("report_id");
ALTER TABLE "moderation_actions" ADD CONSTRAINT "moderation_actions_admin_id_fkey" FOREIGN KEY ("admin_id") REFERENCES "admin_accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "moderation_actions_admin_id_idx" ON "moderation_actions"("admin_id");
ALTER TABLE "moderation_actions" ADD CONSTRAINT "moderation_actions_post_id_fkey" FOREIGN KEY ("post_id") REFERENCES "posts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "moderation_actions_post_id_idx" ON "moderation_actions"("post_id");
ALTER TABLE "moderation_actions" ADD CONSTRAINT "moderation_actions_comment_id_fkey" FOREIGN KEY ("comment_id") REFERENCES "comments"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "moderation_actions_comment_id_idx" ON "moderation_actions"("comment_id");
ALTER TABLE "moderation_actions" ADD CONSTRAINT "moderation_actions_message_id_fkey" FOREIGN KEY ("message_id") REFERENCES "messages"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "moderation_actions_message_id_idx" ON "moderation_actions"("message_id");
ALTER TABLE "moderation_actions" ADD CONSTRAINT "moderation_actions_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "moderation_actions_account_id_idx" ON "moderation_actions"("account_id");
ALTER TABLE "moderation_actions" ADD CONSTRAINT "moderation_actions_group_id_fkey" FOREIGN KEY ("group_id") REFERENCES "groups"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "moderation_actions_group_id_idx" ON "moderation_actions"("group_id");
ALTER TABLE "moderation_actions" ADD CONSTRAINT "moderation_actions_event_id_fkey" FOREIGN KEY ("event_id") REFERENCES "events"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "moderation_actions_event_id_idx" ON "moderation_actions"("event_id");
ALTER TABLE "notifications" ADD CONSTRAINT "notifications_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "notifications_account_id_idx" ON "notifications"("account_id");
ALTER TABLE "direct_contact_states" ADD CONSTRAINT "direct_contact_states_account_low_id_fkey" FOREIGN KEY ("account_low_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "direct_contact_states_account_low_id_idx" ON "direct_contact_states"("account_low_id");
ALTER TABLE "direct_contact_states" ADD CONSTRAINT "direct_contact_states_account_high_id_fkey" FOREIGN KEY ("account_high_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "direct_contact_states_account_high_id_idx" ON "direct_contact_states"("account_high_id");
ALTER TABLE "daily_usage" ADD CONSTRAINT "daily_usage_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "daily_usage_account_id_idx" ON "daily_usage"("account_id");
ALTER TABLE "group_owner_transfers" ADD CONSTRAINT "group_owner_transfers_group_id_fkey" FOREIGN KEY ("group_id") REFERENCES "groups"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "group_owner_transfers_group_id_idx" ON "group_owner_transfers"("group_id");
ALTER TABLE "group_owner_transfers" ADD CONSTRAINT "group_owner_transfers_from_account_id_fkey" FOREIGN KEY ("from_account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "group_owner_transfers_from_account_id_idx" ON "group_owner_transfers"("from_account_id");
ALTER TABLE "group_owner_transfers" ADD CONSTRAINT "group_owner_transfers_to_account_id_fkey" FOREIGN KEY ("to_account_id") REFERENCES "accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "group_owner_transfers_to_account_id_idx" ON "group_owner_transfers"("to_account_id");
ALTER TABLE "policy_configs" ADD CONSTRAINT "policy_configs_updated_by_admin_id_fkey" FOREIGN KEY ("updated_by_admin_id") REFERENCES "admin_accounts"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "policy_configs_updated_by_admin_id_idx" ON "policy_configs"("updated_by_admin_id");

-- PostgreSQL 专用约束；不要只用 prisma db push（会遗漏本文件）。
-- 部分唯一索引：只限制当前有效身份、群主、开放加入区间和待处理转让，不影响历史记录。
CREATE UNIQUE INDEX principals_account_identity ON principals(account_id) WHERE kind='account';
CREATE UNIQUE INDEX group_single_owner ON group_members(group_id) WHERE role='owner' AND status='active';
CREATE UNIQUE INDEX membership_single_open_period ON membership_periods(group_member_id) WHERE end_sequence IS NULL;
CREATE UNIQUE INDEX group_single_pending_transfer ON group_owner_transfers(group_id) WHERE status='pending';
-- 列表与任务索引：匹配筛选/排序条件；expires_at索引供分批清理，不替代查询时鉴权与到期过滤。
CREATE INDEX posts_public_feed ON posts(scope,status,published_at DESC,id DESC);
CREATE INDEX posts_region_feed ON posts(region_code,status,published_at DESC,id DESC);
CREATE INDEX comments_thread ON comments(post_id,status,published_at,id);
CREATE INDEX events_region_time ON events(status,region_code,starts_at,id);
CREATE INDEX notifications_account_time ON notifications(account_id,created_at DESC,id DESC);
CREATE INDEX outbox_ready ON outbox(status,available_at,id);
CREATE INDEX deletion_jobs_ready ON deletion_jobs(status,retry_after,id);
CREATE INDEX likes_reverse ON likes(to_account_id,from_account_id);
CREATE INDEX messages_expiry ON messages(expires_at,id);
CREATE INDEX posts_expiry ON posts(expires_at,id);
CREATE INDEX comments_expiry ON comments(expires_at,id);

-- UTC 日历年，2月29日自动夹到次年2月最后一天，不采用365天。
CREATE FUNCTION calendar_year_after(ts timestamptz) RETURNS timestamptz
LANGUAGE sql IMMUTABLE STRICT AS $$ SELECT ((ts AT TIME ZONE 'UTC') + INTERVAL '1 year') AT TIME ZONE 'UTC' $$;
CREATE FUNCTION content_retention() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- 已公开内容的起算点不可改变，恢复隐藏或编辑也不能续期。
 IF TG_OP='UPDATE' AND OLD.published_at IS NOT NULL THEN
  IF NEW.published_at IS DISTINCT FROM OLD.published_at THEN RAISE EXCEPTION 'published_at is immutable' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.status='visible' AND NEW.published_at IS NULL THEN NEW.published_at=CURRENT_TIMESTAMP; END IF;
 NEW.expires_at=calendar_year_after(NEW.published_at);
 RETURN NEW;
END $$;
CREATE TRIGGER posts_retention BEFORE INSERT OR UPDATE ON posts FOR EACH ROW EXECUTE FUNCTION content_retention();
CREATE TRIGGER comments_retention BEFORE INSERT OR UPDATE ON comments FOR EACH ROW EXECUTE FUNCTION content_retention();
CREATE FUNCTION message_retention() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- 强制用原发送时间重算到期，拒绝改时间延长消息寿命。
 IF TG_OP='UPDATE' AND NEW.sent_at IS DISTINCT FROM OLD.sent_at THEN RAISE EXCEPTION 'sent_at is immutable' USING ERRCODE='23514'; END IF;
 NEW.expires_at=calendar_year_after(NEW.sent_at); RETURN NEW;
END $$;
CREATE TRIGGER messages_retention BEFORE INSERT OR UPDATE ON messages FOR EACH ROW EXECUTE FUNCTION message_retention();
CREATE FUNCTION declaration_retention() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- 有效自我声明不设自动失效；撤销后只保留一年的声明记录。
 IF NEW.status='withdrawn' THEN NEW.expires_at=calendar_year_after(NEW.withdrawn_at); ELSE NEW.expires_at=NULL; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER declaration_retention BEFORE INSERT OR UPDATE ON age_declarations FOR EACH ROW EXECUTE FUNCTION declaration_retention();

CREATE FUNCTION comment_same_post() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- 同帖引用检查与普通外键互补，不能用有效评论ID引用另一个帖子的内容。
 IF NEW.reply_to_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM comments WHERE id=NEW.reply_to_id AND post_id=NEW.post_id) THEN
  RAISE EXCEPTION 'reply must belong to same post' USING ERRCODE='23514';
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER comment_same_post BEFORE INSERT OR UPDATE ON comments FOR EACH ROW EXECUTE FUNCTION comment_same_post();

-- 应用同事务创建group/owner/conversation，延迟到提交时核对；不在中间状态误报。
CREATE FUNCTION group_integrity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE gid uuid; g groups%ROWTYPE; actual_count integer;
BEGIN
 IF TG_TABLE_NAME='groups' THEN gid=COALESCE(NEW.id,OLD.id); ELSE gid=COALESCE(NEW.group_id,OLD.group_id); END IF;
 SELECT * INTO g FROM groups WHERE id=gid FOR UPDATE;
 IF NOT FOUND THEN RETURN NULL; END IF;
 IF NOT EXISTS(SELECT 1 FROM group_members WHERE group_id=gid AND account_id=g.owner_id AND role='owner' AND status='active') THEN
  RAISE EXCEPTION 'group owner must have active owner membership' USING ERRCODE='23514';
 END IF;
 SELECT count(*) INTO actual_count FROM group_members WHERE group_id=gid AND status='active';
 IF g.active_count<>actual_count THEN RAISE EXCEPTION 'group active_count mismatch' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM conversations WHERE group_id=gid AND type='group') THEN RAISE EXCEPTION 'group requires conversation' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER group_integrity AFTER INSERT OR UPDATE ON groups DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION group_integrity();
CREATE CONSTRAINT TRIGGER member_integrity AFTER INSERT OR UPDATE OR DELETE ON group_members DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION group_integrity();
CREATE CONSTRAINT TRIGGER conversation_group_integrity AFTER INSERT OR UPDATE OR DELETE ON conversations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION group_integrity();

CREATE FUNCTION immutable_membership_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- 转移成员身份会破坏原加入区间；换群应退出后创建另一群的成员记录。
 IF NEW.group_id<>OLD.group_id OR NEW.account_id<>OLD.account_id THEN RAISE EXCEPTION 'membership identity is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_membership_identity BEFORE UPDATE ON group_members FOR EACH ROW EXECUTE FUNCTION immutable_membership_identity();

CREATE FUNCTION conversation_integrity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- 恋爱会话必须属于指定匹配的同一账号对，普通会话不依赖匹配。
 IF NEW.type='dating' AND NOT EXISTS(SELECT 1 FROM matches WHERE id=NEW.match_id AND account_low_id=NEW.account_low_id AND account_high_id=NEW.account_high_id) THEN
  RAISE EXCEPTION 'dating pair differs from match' USING ERRCODE='23514';
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER conversation_integrity BEFORE INSERT OR UPDATE ON conversations FOR EACH ROW EXECUTE FUNCTION conversation_integrity();
CREATE FUNCTION participant_integrity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- 两人会话只能登记账号对中的成员，群聊使用group_members鉴权。
 IF NOT EXISTS(SELECT 1 FROM conversations WHERE id=NEW.conversation_id AND type IN ('direct','dating') AND NEW.account_id IN (account_low_id,account_high_id)) THEN
  RAISE EXCEPTION 'participant not in conversation pair' USING ERRCODE='23514';
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER participant_integrity BEFORE INSERT OR UPDATE ON conversation_participants FOR EACH ROW EXECUTE FUNCTION participant_integrity();

-- 单资源不得跨头像、帖子、回复、恋爱照片、活动封面复用；锁资源串行化绑定。
CREATE FUNCTION media_binding_integrity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE mid uuid; binding_target text; asset media_assets%ROWTYPE; duplicates integer; expected_purpose text; principal uuid; account uuid;
BEGIN
 mid=(to_jsonb(NEW)->>TG_ARGV[0])::uuid; IF mid IS NULL THEN RETURN NEW; END IF;
 binding_target=to_jsonb(NEW)->>TG_ARGV[1]; expected_purpose=TG_ARGV[2];
 SELECT * INTO asset FROM media_assets WHERE id=mid FOR UPDATE;
 IF NOT FOUND THEN RETURN NEW; END IF; -- FK将在随后拒绝
 IF asset.purpose<>expected_purpose OR asset.status IN ('initiated','rejected','deleting','deleted') THEN
  RAISE EXCEPTION 'media purpose/status incompatible' USING ERRCODE='23514';
 END IF;
 IF TG_TABLE_NAME='post_media' THEN SELECT author_principal_id INTO principal FROM posts WHERE id=NEW.post_id;
 ELSIF TG_TABLE_NAME='comment_media' THEN SELECT author_principal_id INTO principal FROM comments WHERE id=NEW.comment_id;
 ELSIF TG_TABLE_NAME='profiles' THEN account=NEW.account_id;
 ELSIF TG_TABLE_NAME='dating_photos' THEN account=NEW.account_id;
 ELSIF TG_TABLE_NAME='events' THEN account=NEW.creator_id;
 END IF;
 IF principal IS NOT NULL THEN
  IF asset.owner_principal_id<>principal AND NOT EXISTS(SELECT 1 FROM principals a JOIN principals b ON a.account_id=b.account_id WHERE a.id=principal AND b.id=asset.owner_principal_id AND a.account_id IS NOT NULL) THEN
   RAISE EXCEPTION 'media owner mismatch' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM principals WHERE id=asset.owner_principal_id AND account_id=account) THEN RAISE EXCEPTION 'media owner mismatch' USING ERRCODE='23514'; END IF;
 END IF;
 SELECT count(*) INTO duplicates FROM (
  SELECT 'post_media' AS location,post_id::text AS target FROM post_media WHERE media_id=mid
  UNION ALL SELECT 'comment_media',comment_id::text FROM comment_media WHERE media_id=mid
  UNION ALL SELECT 'profiles',id::text FROM profiles WHERE avatar_media_id=mid
  UNION ALL SELECT 'dating_photos',account_id::text FROM dating_photos WHERE media_id=mid
  UNION ALL SELECT 'events',id::text FROM events WHERE cover_media_id=mid
 ) bindings WHERE NOT(location=TG_TABLE_NAME AND bindings.target=binding_target);
 IF duplicates>0 THEN RAISE EXCEPTION 'media already bound' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER post_media_binding BEFORE INSERT OR UPDATE ON post_media FOR EACH ROW EXECUTE FUNCTION media_binding_integrity('media_id','post_id','board');
CREATE TRIGGER comment_media_binding BEFORE INSERT OR UPDATE ON comment_media FOR EACH ROW EXECUTE FUNCTION media_binding_integrity('media_id','comment_id','board');
CREATE TRIGGER avatar_media_binding BEFORE INSERT OR UPDATE ON profiles FOR EACH ROW EXECUTE FUNCTION media_binding_integrity('avatar_media_id','id','profile');
CREATE TRIGGER dating_media_binding BEFORE INSERT OR UPDATE ON dating_photos FOR EACH ROW EXECUTE FUNCTION media_binding_integrity('media_id','account_id','dating');
CREATE TRIGGER event_media_binding BEFORE INSERT OR UPDATE ON events FOR EACH ROW EXECUTE FUNCTION media_binding_integrity('cover_media_id','id','event');

-- 已确认的运营初始配额；业务服务在去重后同事务计数，每日按东京自然日。
INSERT INTO policy_configs(key,value) VALUES
 ('ordinary_group_capacity','100'),('max_owned_ordinary_groups','5'),('daily_group_creations','2'),
 ('unanswered_direct_message_limit','3'),('daily_new_direct_targets','10'),('daily_timezone','"Asia/Tokyo"');
-- 频率、群拥有数、资格及read cursor必须由授权服务事务校验；schema不提供绕过身份的业务入口。
