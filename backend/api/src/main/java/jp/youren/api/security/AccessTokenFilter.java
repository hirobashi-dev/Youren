package jp.youren.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import jp.youren.api.auth.*;
import jp.youren.api.http.ApiExceptionHandler.ApiError;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 本人路由必须有正向认证结果；可选公开路由使用服务端身份属性，匿名读取不获得本人权限。
@Component
public class AccessTokenFilter extends OncePerRequestFilter {
  private final SessionService sessions;
  private final ObjectMapper json;
  private static final Set<String> ANONYMOUS =
      Set.of(
          "/v1/auth/email-code",
          "/v1/auth/register",
          "/v1/auth/login",
          "/v1/auth/refresh",
          "/v1/auth/policy",
          "/v1/guest-sessions");

  public AccessTokenFilter(SessionService sessions, ObjectMapper json) {
    this.sessions = sessions;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (!path.startsWith("/v1/") || ANONYMOUS.contains(path)) {
      chain.doFilter(request, response);
      return;
    }
    boolean required =
        path.equals("/v1/me") || path.startsWith("/v1/me/") || path.equals("/v1/auth/logout");
    String header = request.getHeader("Authorization");
    try {
      if (header != null) {
        if (!header.startsWith("Bearer ") || header.length() > 1031)
          throw AuthFailure.unauthorized();
        request.setAttribute(
            CurrentPrincipal.ATTRIBUTE, sessions.requireAccess(header.substring(7)));
      } else if (required) throw AuthFailure.unauthorized();
    } catch (AuthFailure failure) {
      writeError(response, failure.status(), failure.code());
      return;
    } catch (org.springframework.dao.DataAccessException failure) {
      writeError(response, 503, "TEMPORARILY_UNAVAILABLE");
      return;
    } catch (RuntimeException failure) {
      // 过滤器在MVC异常处理之外，程序错误仍返回安全500而不能泄漏底层异常。
      writeError(response, 500, "INTERNAL_ERROR");
      return;
    }
    chain.doFilter(request, response);
  }

  private void writeError(HttpServletResponse response, int status, String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    json.writeValue(
        response.getWriter(), new ApiError(code, "身份凭证不可用", UUID.randomUUID().toString()));
  }
}
