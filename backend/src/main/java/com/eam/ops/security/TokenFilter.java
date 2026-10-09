package com.eam.ops.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.filter.OncePerRequestFilter;

class TokenFilter extends OncePerRequestFilter {
  private final AuthService auth;
  private final JwtDecoder decoder;

  TokenFilter(AuthService auth, JwtDecoder decoder) {
    this.auth = auth;
    this.decoder = decoder;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    String header = req.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      try {
        var actor = auth.authenticate(decoder.decode(header.substring(7)));
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
      } catch (Exception e) {
        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"会话失效或认证服务暂不可用，请重新登录\"}");
        return;
      }
    }
    chain.doFilter(req, res);
  }
}
