package jp.youren.api.auth;

import java.util.*;
import jp.youren.api.security.TokenSecrets;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

// Lua一次检查全部窗口后统一计数；拒绝时不能消耗部分窗口，Redis故障不能绕过配额。
@Component
public class EmailRateLimiter {
  private final StringRedisTemplate redis;
  private final AuthSettings settings;

  public EmailRateLimiter(StringRedisTemplate redis, AuthSettings settings) {
    this.redis = redis;
    this.settings = settings;
  }

  private static final DefaultRedisScript<Long> SCRIPT =
      new DefaultRedisScript<>(
          """
    local retry = 0
    for i=1,#KEYS do
      local count=tonumber(redis.call('GET',KEYS[i]) or '0')
      if count>=tonumber(ARGV[(i-1)*2+1]) then
        retry=math.max(retry,redis.call('TTL',KEYS[i]),1)
      end
    end
    if retry>0 then return retry end
    for i=1,#KEYS do
      local count=redis.call('INCR',KEYS[i])
      if count==1 then redis.call('EXPIRE',KEYS[i],ARGV[(i-1)*2+2]) end
    end
    return 0
    """,
          Long.class);

  public void acquire(String email, String ip) {
    // 同一Redis实例执行原子脚本；键中不保留原始邮箱或IP。
    String e = "youren:auth:rate:e:" + TokenSecrets.digest(email);
    String p = "youren:auth:rate:i:" + TokenSecrets.digest(ip);
    List<String> keys = List.of(e + ":cooldown", e + ":hour", e + ":day", p + ":hour", p + ":day");
    Long wait;
    try {
      wait =
          redis.execute(
              SCRIPT,
              keys,
              "1",
              Long.toString(settings.cooldown()),
              Integer.toString(settings.emailHourly()),
              "3600",
              Integer.toString(settings.emailDaily()),
              "86400",
              Integer.toString(settings.ipHourly()),
              "3600",
              Integer.toString(settings.ipDaily()),
              "86400");
    } catch (RuntimeException ex) {
      throw AuthFailure.unavailable();
    }
    if (wait == null) throw AuthFailure.unavailable();
    if (wait > 0) throw new AuthFailure(429, "RATE_LIMITED", wait);
  }
}
