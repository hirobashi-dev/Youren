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
