package com.eam.ops.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
  @Bean
  SecretKeySpec key(@Value("${app.jwt-secret}") String secret) {
    if (secret.length() < 32) throw new IllegalArgumentException("JWT_SECRET 至少 32 字符");
    return new SecretKeySpec(
        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
  }

  @Bean
  JwtEncoder encoder(SecretKeySpec key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  JwtDecoder decoder(SecretKeySpec key) {
    var decoder = NimbusJwtDecoder.withSecretKey(key).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("eam-ops"));
    return decoder;
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity http, AuthService auth, JwtDecoder decoder)
      throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/v1/auth/login",
                        "/actuator/health",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (q, s, x) -> {
                      s.setStatus(401);
                      s.setContentType("application/json;charset=UTF-8");
                      s.getWriter().write("{\"message\":\"请先登录\"}");
                    }))
        .addFilterBefore(new TokenFilter(auth, decoder), UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
