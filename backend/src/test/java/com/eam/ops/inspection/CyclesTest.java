package com.eam.ops.inspection;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CyclesTest {
  @Test
  void monthEndKeepsAnchor() {
    var feb = Cycles.next(LocalDate.of(2026, 1, 31), "MONTHLY", 31);
    assertEquals(LocalDate.of(2026, 2, 28), feb);
    assertEquals(LocalDate.of(2026, 3, 31), Cycles.next(feb, "MONTHLY", 31));
  }

  @Test
  void leapYearAndYearBoundary() {
    assertEquals(LocalDate.of(2028, 2, 29), Cycles.next(LocalDate.of(2028, 1, 31), "MONTHLY", 31));
    assertEquals(LocalDate.of(2027, 1, 1), Cycles.next(LocalDate.of(2026, 12, 31), "DAILY", 31));
    assertEquals(LocalDate.of(2027, 1, 5), Cycles.next(LocalDate.of(2026, 12, 29), "WEEKLY", 29));
  }
}
