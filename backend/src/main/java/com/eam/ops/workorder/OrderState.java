package com.eam.ops.workorder;

import com.eam.ops.common.BusinessException;
import java.util.Set;

public final class OrderState {
  private OrderState() {}

  public static String next(String status, String action) {
    return switch (action) {
      case "assign" -> {
        BusinessException.require(
            Set.of("PENDING", "ASSIGNED", "IN_PROGRESS").contains(status), 409, "当前状态不可派单");
        yield "ASSIGNED";
      }
      case "accept" -> {
        BusinessException.require(status.equals("ASSIGNED"), 409, "当前状态不可接单");
        yield "IN_PROGRESS";
      }
      case "submit" -> {
        BusinessException.require(status.equals("IN_PROGRESS"), 409, "当前状态不可提交");
        yield "WAITING_REVIEW";
      }
      case "approve" -> {
        BusinessException.require(status.equals("WAITING_REVIEW"), 409, "当前状态不可验收");
        yield "CLOSED";
      }
      case "reject" -> {
        BusinessException.require(status.equals("WAITING_REVIEW"), 409, "当前状态不可驳回");
        yield "IN_PROGRESS";
      }
      case "record" -> {
        BusinessException.require(status.equals("IN_PROGRESS"), 409, "当前状态不可添加处理记录");
        yield status;
      }
      default -> throw new BusinessException(400, "未知工单操作");
    };
  }
}
