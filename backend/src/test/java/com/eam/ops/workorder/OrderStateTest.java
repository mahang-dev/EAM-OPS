package com.eam.ops.workorder;

import static org.junit.jupiter.api.Assertions.*;

import com.eam.ops.common.BusinessException;
import org.junit.jupiter.api.Test;

class OrderStateTest {
  @Test
  void completeLifecycle() {
    assertEquals("ASSIGNED", OrderState.next("PENDING", "assign"));
    assertEquals("IN_PROGRESS", OrderState.next("ASSIGNED", "accept"));
    assertEquals("WAITING_REVIEW", OrderState.next("IN_PROGRESS", "submit"));
    assertEquals("CLOSED", OrderState.next("WAITING_REVIEW", "approve"));
  }

  @Test
  void rejectsShortcutsAndRepeatedAccept() {
    assertThrows(BusinessException.class, () -> OrderState.next("PENDING", "approve"));
    assertThrows(BusinessException.class, () -> OrderState.next("IN_PROGRESS", "accept"));
    assertThrows(BusinessException.class, () -> OrderState.next("CLOSED", "assign"));
  }

  @Test
  void rejectReturnsToWork() {
    assertEquals("IN_PROGRESS", OrderState.next("WAITING_REVIEW", "reject"));
    assertEquals("ASSIGNED", OrderState.next("IN_PROGRESS", "assign"));
  }
}
