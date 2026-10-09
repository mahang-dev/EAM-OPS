package com.eam.ops.security;

import java.util.Set;

public record Actor(
    long id,
    long departmentId,
    String username,
    String displayName,
    String role,
    Set<String> permissions) {
  public boolean admin() {
    return role.equals("ADMIN");
  }

  public boolean can(String permission) {
    return permissions.contains("*") || permissions.contains(permission);
  }
}
