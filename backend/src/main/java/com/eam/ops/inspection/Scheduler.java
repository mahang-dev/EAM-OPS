package com.eam.ops.inspection;

import com.eam.ops.common.Db;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.scheduling", havingValue = "true", matchIfMissing = true)
public class Scheduler {
  private final Db db;
  private final InspectionService service;

  public Scheduler(Db db, InspectionService service) {
    this.db = db;
    this.service = service;
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 10000)
  public void tick() {
    for (var p :
        db.list("SELECT id FROM ops_inspection_plan WHERE enabled=1 AND next_run<=CURRENT_DATE"))
      try {
        service.generate(Db.num(p, "id"));
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(Scheduler.class)
            .error("Plan {} failed; retry next tick", p.get("id"), e);
      }
    service.overdue();
  }
}
