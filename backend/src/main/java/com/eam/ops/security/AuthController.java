package com.eam.ops.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;
  private final Access access;
  private final JwtDecoder decoder;

  public AuthController(AuthService auth, Access access, JwtDecoder decoder) {
    this.auth = auth;
    this.access = access;
    this.decoder = decoder;
  }

  public record Login(@NotBlank String username, @NotBlank String password) {}

  public record Password(@NotBlank String oldPassword, @NotBlank String password) {}

  @PostMapping("/login")
  Object login(@Valid @RequestBody Login r) {
    return auth.login(r.username(), r.password());
  }

  @GetMapping("/me")
  Object me() {
    return access.actor();
  }

  @PostMapping("/logout")
  void logout(@RequestHeader("Authorization") String token) {
    auth.logout(token.substring(7), decoder);
  }

  @PostMapping("/password")
  void password(@Valid @RequestBody Password r) {
    auth.changePassword(access.actor().id(), r.oldPassword(), r.password());
  }
}
