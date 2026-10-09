package com.eam.ops.common;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(BusinessException.class)
  ResponseEntity<?> business(BusinessException e) {
    return response(e.status, e.getMessage());
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<?> denied() {
    return response(403, "没有此操作权限");
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return response(
        400,
        e instanceof MethodArgumentNotValidException v
            ? v.getBindingResult().getAllErrors().get(0).getDefaultMessage()
            : "参数不合法，请检查输入");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict() {
    return response(409, "编号重复或关联数据不满足约束");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    org.slf4j.LoggerFactory.getLogger(Errors.class).error("Request failed", e);
    return response(503, "服务暂不可用，请稍后重试");
  }

  private ResponseEntity<?> response(int status, String message) {
    return ResponseEntity.status(status)
        .body(Map.of("message", message, "requestId", String.valueOf(MDC.get("requestId"))));
  }
}
