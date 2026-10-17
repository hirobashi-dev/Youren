package jp.youren.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import jp.youren.api.auth.AuthFailure;
import jp.youren.api.http.ApiExceptionHandler.ApiError;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 管理路由仅识别管理员会话；MFA前置端点公开，其他路径缺少独立令牌即拒绝。
@Component
public class AdminTokenFilter extends OncePerRequestFilter {
  private final AdminAuthService authentication;
  private final ObjectMapper json;

  public AdminTokenFilter(AdminAuthService authentication, ObjectMapper json) {
    this.authentication = authentication;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (!path.startsWith("/v1/admin/")
        || path.equals("/v1/admin/auth/login")
        || path.equals("/v1/admin/auth/mfa")) {
      chain.doFilter(request, response);
      return;
    }
    String header = request.getHeader("Authorization");
    try {
      if (header == null || !header.startsWith("Bearer ") || header.length() > 1031)
        throw AuthFailure.unauthorized();
      request.setAttribute(
          AdminPrincipal.ATTRIBUTE, authentication.requireAccess(header.substring(7)));
    } catch (AuthFailure error) {
      response.setStatus(error.status());
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      response.setHeader("Cache-Control", "no-store");
      json.writeValue(
          response.getWriter(),
          new ApiError(error.code(), "管理身份不可用", UUID.randomUUID().toString()));
      return;
    } catch (RuntimeException error) {
      response.setStatus(500);
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      response.setHeader("Cache-Control", "no-store");
      json.writeValue(
          response.getWriter(),
          new ApiError("INTERNAL_ERROR", "服务暂时不可用", UUID.randomUUID().toString()));
      return;
    }
    chain.doFilter(request, response);
  }
}
