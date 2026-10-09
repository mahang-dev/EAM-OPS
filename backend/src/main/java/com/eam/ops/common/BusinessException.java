package com.eam.ops.common;

public class BusinessException extends RuntimeException {
  public final int status;

  public BusinessException(int status, String message) {
    super(message);
    this.status = status;
  }

  public static void require(boolean condition, int status, String message) {
    if (!condition) throw new BusinessException(status, message);
  }
}
