package com.eam.ops.common;

import static com.eam.ops.common.BusinessException.require;

import java.util.*;

public final class Input {
  private Input() {}

  public static String text(Map<String, Object> m, String k, int max) {
    Object v = m.get(k);
    require(v instanceof String, 400, k + " 必填");
    String s = v.toString().trim();
    require(!s.isEmpty() && s.length() <= max, 400, k + " 不能为空且不得超过 " + max + " 字符");
    return s;
  }

  public static String optional(Map<String, Object> m, String k, int max) {
    if (m.get(k) == null) return "";
    String s = m.get(k).toString().trim();
    require(s.length() <= max, 400, k + " 过长");
    return s;
  }

  public static long id(Map<String, Object> m, String k) {
    try {
      long v = Long.parseLong(String.valueOf(m.get(k)));
      require(v > 0, 400, k + " 必须为正整数");
      return v;
    } catch (NumberFormatException e) {
      throw new BusinessException(400, k + " 必须为正整数");
    }
  }

  public static int version(Map<String, Object> m) {
    try {
      int v = Integer.parseInt(String.valueOf(m.get("version")));
      require(v >= 0, 400, "version 必填");
      return v;
    } catch (Exception e) {
      throw new BusinessException(400, "version 必填");
    }
  }

  public static boolean flag(Map<String, Object> m, String k) {
    require(m.get(k) instanceof Boolean, 400, k + " 必须为布尔值");
    return Boolean.TRUE.equals(m.get(k));
  }

  public static String choice(Map<String, Object> m, String k, String... values) {
    String s = text(m, k, 40);
    require(Arrays.asList(values).contains(s), 400, k + " 值不合法");
    return s;
  }
}
