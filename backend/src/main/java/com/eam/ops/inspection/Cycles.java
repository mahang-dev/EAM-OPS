package com.eam.ops.inspection;

import java.time.LocalDate;

public final class Cycles {
  private Cycles() {}

  public static LocalDate next(LocalDate date, String period, int anchor) {
    return switch (period) {
      case "DAILY" -> date.plusDays(1);
      case "WEEKLY" -> date.plusWeeks(1);
      case "MONTHLY" -> {
        var m = date.plusMonths(1);
        yield m.withDayOfMonth(Math.min(anchor, m.lengthOfMonth()));
      }
      default -> throw new IllegalArgumentException("周期不合法");
    };
  }
}
