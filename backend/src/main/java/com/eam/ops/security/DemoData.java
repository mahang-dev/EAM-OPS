package com.eam.ops.security;

import com.eam.ops.alert.AlertService;
import com.eam.ops.common.Db;
import com.eam.ops.inspection.InspectionService;
import com.eam.ops.workorder.WorkOrderService;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
@Order(20)
public class DemoData implements ApplicationRunner {
  private final Db db;
  private final AuthService auth;
  private final InspectionService inspections;
  private final WorkOrderService orders;
  private final AlertService alerts;

  public DemoData(
      Db db,
      AuthService auth,
      InspectionService inspections,
      WorkOrderService orders,
      AlertService alerts) {
    this.db = db;
    this.auth = auth;
    this.inspections = inspections;
    this.orders = orders;
    this.alerts = alerts;
  }

  private void as(long user) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(auth.load(user), null, List.of()));
  }

  @Override
  public void run(ApplicationArguments args) {
    if (db.count("SELECT COUNT(*) FROM ops_work_order") > 0) return;
    try {
      for (int dept = 2; dept <= 3; dept++) {
        long engineer =
            db.count(
                "SELECT id FROM sys_user WHERE department_id=? AND username=?",
                dept,
                dept == 2 ? "engineer" : "engineer2");
        long supervisor =
            db.count(
                "SELECT id FROM sys_user WHERE department_id=? AND username=?",
                dept,
                dept == 2 ? "supervisor" : "supervisor2");
        for (var p : db.list("SELECT id FROM ops_inspection_plan WHERE department_id=?", dept))
          inspections.generate(Db.num(p, "id"));
        for (var a :
            db.list(
                "SELECT id,name FROM ops_asset WHERE department_id=? ORDER BY id LIMIT 5", dept)) {
          as(engineer);
          long id =
              orders.create(
                  Map.of(
                      "asset_id",
                      a.get("id"),
                      "title",
                      "模拟·" + a.get("name") + "运行异常",
                      "description",
                      "虚构演示故障，用于验证业务流转。",
                      "priority",
                      2));
          if (Db.num(a, "id") % 3 != 0) {
            as(supervisor);
            orders.action(
                id, "assign", Map.of("version", 0, "handler_id", engineer, "detail", "模拟分派"));
            as(engineer);
            orders.action(id, "accept", Map.of("version", 1));
            if (Db.num(a, "id") % 2 == 0) {
              orders.action(id, "submit", Map.of("version", 2, "detail", "模拟处理：检查接口后恢复。"));
              as(supervisor);
              orders.action(
                  id, "approve", Map.of("version", 3, "detail", "模拟验收通过", "recovered", true));
            }
          }
        }
        as(supervisor);
        long asset = db.count("SELECT MIN(id) FROM ops_asset WHERE department_id=?", dept);
        alerts.metrics(asset, Map.of("CPU", 94, "MEMORY", 88));
      }
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
