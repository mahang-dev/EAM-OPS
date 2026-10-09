package com.eam.ops.security;

import static com.eam.ops.common.BusinessException.require;
import static com.eam.ops.common.Db.*;

import com.eam.ops.common.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final Db db;
  private final StringRedisTemplate redis;
  private final JwtEncoder encoder;
  private final ObjectMapper json;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

  public String hashPassword(String password) {
    return passwords.encode(password);
  }

  public AuthService(Db db, StringRedisTemplate redis, JwtEncoder encoder, ObjectMapper json) {
    this.db = db;
    this.redis = redis;
    this.encoder = encoder;
    this.json = json;
  }

  public Map<String, Object> login(String username, String password) {
    require(username.length() <= 64 && password.length() <= 100, 400, "输入过长");
    String throttle = "login:" + username;
    Long attempts = redis.opsForValue().increment(throttle);
    if (attempts != null && attempts == 1) redis.expire(throttle, Duration.ofMinutes(10));
    require(attempts != null && attempts <= 20, 429, "尝试次数过多，请十分钟后重试");
    var users =
        db.list(
            "SELECT u.* FROM sys_user u JOIN sys_department d ON d.id=u.department_id WHERE"
                + " u.username=? AND u.enabled=1 AND d.enabled=1",
            username);
    require(
        !users.isEmpty()
            && passwords.matches(password, users.get(0).get("password_hash").toString()),
        401,
        "用户名或密码错误");
    var u = users.get(0);
    String sid = UUID.randomUUID().toString();
    Instant now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer("eam-ops")
            .subject(u.get("id").toString())
            .id(sid)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(7200))
            .claim("ver", num(u, "auth_version"))
            .build();
    String token =
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    redis.opsForValue().set("session:" + sid, u.get("id").toString(), Duration.ofHours(2));
    redis.delete(throttle);
    return Map.of("token", token, "user", load(num(u, "id")));
  }

  public Actor authenticate(Jwt jwt) {
    long id = Long.parseLong(jwt.getSubject());
    require(
        jwt.getSubject().equals(redis.opsForValue().get("session:" + jwt.getId())), 401, "会话已过期");
    var u =
        db.one(
            "SELECT u.* FROM sys_user u JOIN sys_department d ON d.id=u.department_id WHERE u.id=?"
                + " AND u.enabled=1 AND d.enabled=1",
            id);
    require(
        num(u, "auth_version") == ((Number) jwt.getClaim("ver")).longValue(), 401, "权限已变更，请重新登录");
    return load(id);
  }

  public Actor load(long id) {
    var u =
        db.one(
            "SELECT u.*,r.permissions FROM sys_user u JOIN sys_role r ON r.code=u.role_code WHERE"
                + " u.id=?",
            id);
    try {
      return new Actor(
          id,
          num(u, "department_id"),
          u.get("username").toString(),
          u.get("display_name").toString(),
          u.get("role_code").toString(),
          json.readValue(u.get("permissions").toString(), new TypeReference<Set<String>>() {}));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void logout(String token, JwtDecoder decoder) {
    redis.delete("session:" + decoder.decode(token).getId());
  }

  @Transactional
  public void changePassword(long id, String oldPassword, String password) {
    require(password.length() >= 10 && password.length() <= 72, 400, "新密码需要 10–72 个字符");
    var u = db.one("SELECT * FROM sys_user WHERE id=? FOR UPDATE", id);
    require(passwords.matches(oldPassword, u.get("password_hash").toString()), 400, "原密码错误");
    db.update(
        "UPDATE sys_user SET password_hash=?,auth_version=auth_version+1 WHERE id=?",
        passwords.encode(password),
        id);
  }
}
