package com.eam.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.eam.ops.alert.*;
import com.eam.ops.asset.*;
import com.eam.ops.common.*;
import com.eam.ops.inspection.*;
import com.eam.ops.security.*;
import com.eam.ops.workorder.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.scheduling=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "EAM_MYSQL_IT", matches = "true")
class BusinessIntegrationTest {
  @Autowired Db db;
  @Autowired AssetService assets;
  @Autowired AssetExcel excel;
  @Autowired WorkOrderService orders;
  @Autowired InspectionService inspections;
  @Autowired AlertService alerts;
  @Autowired QueryService query;
  @Autowired AuthService auth;
  @Autowired MockMvc mvc;
  @Autowired com.eam.ops.dashboard.DashboardService dashboard;
  @MockitoSpyBean Events events;
  long dept, location, asset, engineer, supervisor, assetUser;
  Actor boss, worker, manager;

  void actor(Actor a) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(a, null, List.of()));
  }

  long user(String role) {
    return db.insert(
        "INSERT INTO sys_user(username,display_name,password_hash,department_id,role_code)"
            + " VALUES(?,?,?,?,?)",
        UUID.randomUUID().toString(),
        "测试用户",
        auth.hashPassword("Testing!12345"),
        dept,
        role);
  }

  @BeforeEach
  void setup() {
    reset(events);
    dept = db.insert("INSERT INTO sys_department(name) VALUES(?)", "IT-" + UUID.randomUUID());
    engineer = user("ENGINEER");
    supervisor = user("SUPERVISOR");
    assetUser = user("ASSET");
    worker = auth.load(engineer);
    boss = auth.load(supervisor);
    manager = auth.load(assetUser);
    location =
        db.insert(
            "INSERT INTO ops_location(department_id,name,location_type) VALUES(?,'测试机房','ROOM')",
            dept);
    actor(manager);
    asset =
        assets.create(
            Map.of(
                "asset_code",
                UUID.randomUUID().toString(),
                "name",
                "测试设备",
                "category_id",
                1,
                "location_id",
                location,
                "owner_id",
                assetUser));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    reset(events);
  }

  long create() {
    actor(worker);
    return orders.create(
        Map.of("asset_id", asset, "priority", 2, "title", "集成测试故障", "description", "模拟异常"));
  }

  Map<String, Object> order(long id) {
    return db.one("SELECT * FROM ops_work_order WHERE id=?", id);
  }

  int version(long id) {
    return ((Number) order(id).get("version")).intValue();
  }

  void assign(long id) {
    actor(boss);
    orders.action(
        id, "assign", Map.of("handler_id", engineer, "version", version(id), "detail", "分派测试"));
  }

  void process(long id) {
    actor(worker);
    orders.action(id, "accept", Map.of("version", version(id)));
    orders.action(id, "submit", Map.of("version", version(id), "detail", "处理完毕"));
  }

  @Test
  void concurrencyHasExactlyOneWinner() throws Exception {
    long id = create();
    assign(id);
    int v = version(id);
    var pool = Executors.newFixedThreadPool(8);
    var start = new CountDownLatch(1);
    var success = new AtomicInteger();
    var conflict = new AtomicInteger();
    var futures = new ArrayList<Future<?>>();
    for (int i = 0; i < 8; i++)
      futures.add(
          pool.submit(
              () -> {
                actor(worker);
                try {
                  start.await();
                  orders.action(id, "accept", Map.of("version", v));
                  success.incrementAndGet();
                } catch (BusinessException e) {
                  assertEquals(409, e.status);
                  conflict.incrementAndGet();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  SecurityContextHolder.clearContext();
                }
              }));
    start.countDown();
    for (var f : futures) f.get(20, TimeUnit.SECONDS);
    pool.shutdown();
    assertEquals(1, success.get());
    assertEquals(7, conflict.get());
    assertEquals(
        1,
        db.count(
            "SELECT COUNT(*) FROM ops_work_order_log WHERE order_id=? AND action='accept'", id));
  }

  @Test
  void rollbackIncludesOrderAssetLogsAndNotification() {
    long id = create();
    assign(id);
    process(id);
    actor(boss);
    long logs = db.count("SELECT COUNT(*) FROM ops_work_order_log WHERE order_id=?", id);
    doThrow(new RuntimeException("Injected notification failure"))
        .when(events)
        .notify(eq(engineer), anyString(), anyString(), anyString());
    assertThrows(
        RuntimeException.class,
        () ->
            orders.action(
                id,
                "approve",
                Map.of("version", version(id), "detail", "验收正常", "recovered", true)));
    assertEquals("WAITING_REVIEW", order(id).get("status"));
    assertEquals("FAULT", db.one("SELECT status FROM ops_asset WHERE id=?", asset).get("status"));
    assertEquals(logs, db.count("SELECT COUNT(*) FROM ops_work_order_log WHERE order_id=?", id));
    assertEquals(
        0,
        db.count(
            "SELECT COUNT(*) FROM ops_asset_change WHERE asset_id=? AND action='RESTORE'", asset));
  }

  @Test
  void anotherFaultPreventsAssetRecovery() {
    long first = create(), second = create();
    assign(first);
    process(first);
    actor(boss);
    orders.action(
        first, "approve", Map.of("version", version(first), "detail", "已修复该故障", "recovered", true));
    assertEquals("FAULT", db.one("SELECT status FROM ops_asset WHERE id=?", asset).get("status"));
    assign(second);
    process(second);
    actor(boss);
    orders.action(
        second,
        "approve",
        Map.of("version", version(second), "detail", "已修复全部故障", "recovered", true));
    assertEquals("RUNNING", db.one("SELECT status FROM ops_asset WHERE id=?", asset).get("status"));
  }

  @Test
  void dataScopeAndOwnerChecks() {
    long id = create();
    assign(id);
    actor(new Actor(999, dept + 100, "other", "other", "ENGINEER", worker.permissions()));
    assertThrows(
        BusinessException.class, () -> orders.action(id, "accept", Map.of("version", version(id))));
    assertEquals(
        0L, query.list("orders", 1, 20, null, null, null, null, null, null, null).get("total"));
    actor(new Actor(998, dept, "same", "same", "ENGINEER", worker.permissions()));
    assertThrows(
        BusinessException.class, () -> orders.action(id, "accept", Map.of("version", version(id))));
    actor(worker);
    assertThrows(
        BusinessException.class,
        () ->
            orders.action(
                id, "approve", Map.of("version", version(id), "detail", "越权", "recovered", true)));
  }

  @Test
  void selfReviewForbiddenEvenWithVerifyPermission() {
    long id = create();
    assign(id);
    process(id);
    actor(new Actor(engineer, dept, "x", "x", "ENGINEER", Set.of("order:verify")));
    var e =
        assertThrows(
            BusinessException.class,
            () ->
                orders.action(
                    id,
                    "approve",
                    Map.of("version", version(id), "detail", "自行验收", "recovered", true)));
    assertEquals(403, e.status);
  }

  @Test
  void tasksIdempotentSnapshotsAndAbnormalOrder() {
    actor(boss);
    long template =
        inspections.template(Map.of("name", "测试模板", "items", List.of("电源", "接口")), null);
    long plan =
        inspections.plan(
            Map.of(
                "template_id",
                template,
                "handler_id",
                engineer,
                "name",
                "测试计划",
                "period",
                "DAILY",
                "next_run",
                java.time.LocalDate.now().minusDays(2).toString(),
                "asset_ids",
                List.of(asset)));
    inspections.generate(plan);
    assertEquals(3, db.count("SELECT COUNT(*) FROM ops_inspection_task WHERE plan_id=?", plan));
    db.update(
        "UPDATE ops_inspection_plan SET next_run=CURRENT_DATE-INTERVAL 2 DAY WHERE id=?", plan);
    inspections.generate(plan);
    assertEquals(3, db.count("SELECT COUNT(*) FROM ops_inspection_task WHERE plan_id=?", plan));
    inspections.template(Map.of("name", "新模板", "items", List.of("新检查项"), "version", 0), template);
    var task =
        db.one("SELECT * FROM ops_inspection_task WHERE plan_id=? ORDER BY id LIMIT 1", plan);
    assertTrue(task.get("snapshot").toString().contains("电源"));
    actor(worker);
    long tid = Db.num(task, "id");
    inspections.complete(
        tid,
        Map.of(
            "version",
            0,
            "results",
            Map.of("电源", "NORMAL", "接口", "ABNORMAL"),
            "description",
            "接口故障"));
    var request =
        Map.<String, Object>of(
            "asset_id",
            asset,
            "source_task_id",
            tid,
            "priority",
            1,
            "title",
            "接口异常",
            "description",
            "巡检发现");
    long first = orders.create(request);
    assertEquals(first, orders.create(request));
    assertEquals(
        1,
        db.count(
            "SELECT COUNT(*) FROM ops_alert WHERE asset_id=? AND active_key IS NOT NULL", asset));
  }

  @Test
  void alertDedupAndRecoveryAreDistinct() {
    actor(boss);
    long rule =
        db.insert(
            "INSERT INTO ops_alert_rule(department_id,name,metric,threshold_value,level)"
                + " VALUES(?,'CPU','CPU',80,'WARNING')",
            dept);
    alerts.metrics(asset, Map.of("CPU", 95));
    alerts.metrics(asset, Map.of("CPU", 96));
    var alert = db.one("SELECT * FROM ops_alert WHERE asset_id=?", asset);
    assertEquals(2, Db.num(alert, "occurrences"));
    alerts.action(Db.num(alert, "id"), "acknowledge", Map.of("version", 1, "detail", "已确认"));
    assertNotNull(db.one("SELECT * FROM ops_alert WHERE id=?", alert.get("id")).get("active_key"));
    alerts.metrics(asset, Map.of("CPU", 20));
    alerts.metrics(asset, Map.of("CPU", 95));
    assertEquals(2, db.count("SELECT COUNT(*) FROM ops_alert WHERE asset_id=?", asset));
  }

  @Test
  void approvalStaleAndUnfinishedWorkBlocked() {
    actor(manager);
    long request =
        assets.request(asset, Map.of("version", 0, "action", "RETIRE", "reason", "测试报废"));
    long id = create();
    actor(boss);
    assertThrows(
        BusinessException.class,
        () -> assets.approve(request, Map.of("version", 0, "accept", true)));
    actor(manager);
    long current = Db.num(db.one("SELECT version FROM ops_asset WHERE id=?", asset), "version");
    long pending =
        assets.request(asset, Map.of("version", current, "action", "RETIRE", "reason", "测试报废"));
    actor(boss);
    assertThrows(
        BusinessException.class,
        () -> assets.approve(pending, Map.of("version", 0, "accept", true)));
    assertEquals(
        "PENDING",
        db.one("SELECT status FROM ops_asset_approval WHERE id=?", pending).get("status"));
  }

  @Test
  void unauthorizedHttpAndSessionRevocation() throws Exception {
    SecurityContextHolder.clearContext();
    mvc.perform(get("/api/v1/assets").with(anonymous())).andExpect(status().isUnauthorized());
    String username =
        db.one("SELECT username FROM sys_user WHERE id=?", engineer).get("username").toString();
    String token = auth.login(username, "Testing!12345").get("token").toString();
    mvc.perform(get("/api/v1/assets").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    db.update("UPDATE sys_user SET auth_version=auth_version+1 WHERE id=?", engineer);
    mvc.perform(get("/api/v1/assets").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void migrationApprovalWritesHistory() {
    actor(manager);
    long target =
        db.insert(
            "INSERT INTO ops_location(department_id,name,location_type) VALUES(?,'目标机房','ROOM')",
            dept);
    long request =
        assets.request(
            asset,
            Map.of("version", 0, "action", "MOVE", "target_location_id", target, "reason", "计划迁移"));
    actor(new Actor(assetUser, dept, "x", "x", "ASSET", Set.of("asset:approve")));
    assertThrows(
        BusinessException.class,
        () -> assets.approve(request, Map.of("version", 0, "accept", true)));
    actor(boss);
    assets.approve(request, Map.of("version", 0, "accept", true));
    assertEquals(
        target,
        Db.num(db.one("SELECT location_id FROM ops_asset WHERE id=?", asset), "location_id"));
    assertEquals(
        1,
        db.count(
            "SELECT COUNT(*) FROM ops_asset_change WHERE asset_id=? AND action='MOVE'", asset));
    assertThrows(
        BusinessException.class,
        () -> assets.approve(request, Map.of("version", 0, "accept", true)));
  }

  @Test
  void excelRejectsEntireBatchAndReportsRow() throws Exception {
    actor(manager);
    byte[] template = excel.export(true);
    byte[] data;
    String code = "IMPORT-" + UUID.randomUUID();
    try (var wb =
            new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(template));
        var out = new java.io.ByteArrayOutputStream()) {
      for (int n = 1; n <= 2; n++) {
        var row = wb.getSheetAt(0).createRow(n);
        String[] cells = {
          code,
          "导入设备",
          "1",
          String.valueOf(dept),
          String.valueOf(location),
          String.valueOf(assetUser),
          "TEST",
          "192.0.2.1"
        };
        for (int i = 0; i < cells.length; i++) row.createCell(i).setCellValue(cells[i]);
      }
      wb.write(out);
      data = out.toByteArray();
    }
    var result =
        (Map<?, ?>)
            excel.importFile(
                new org.springframework.mock.web.MockMultipartFile(
                    "file",
                    "assets.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    data),
                true);
    assertEquals(false, result.get("valid"));
    assertEquals(3, ((Map<?, ?>) ((List<?>) result.get("errors")).get(0)).get("row"));
    assertEquals(0, db.count("SELECT COUNT(*) FROM ops_asset WHERE asset_code=?", code));
  }

  @Test
  void excelValidImportAndExport() throws Exception {
    actor(manager);
    String code = "IMPORT-" + UUID.randomUUID();
    byte[] data;
    try (var wb =
            new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(excel.export(true)));
        var out = new java.io.ByteArrayOutputStream()) {
      var row = wb.getSheetAt(0).createRow(1);
      String[] cells = {
        code,
        "导入设备",
        "1",
        String.valueOf(dept),
        String.valueOf(location),
        String.valueOf(assetUser),
        "TEST",
        "192.0.2.2"
      };
      for (int i = 0; i < cells.length; i++) row.createCell(i).setCellValue(cells[i]);
      wb.write(out);
      data = out.toByteArray();
    }
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "file",
            "assets.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            data);
    assertEquals(false, ((Map<?, ?>) excel.importFile(file, false)).get("committed"));
    assertEquals(0, db.count("SELECT COUNT(*) FROM ops_asset WHERE asset_code=?", code));
    assertEquals(true, ((Map<?, ?>) excel.importFile(file, true)).get("committed"));
    try (var wb =
        new org.apache.poi.xssf.usermodel.XSSFWorkbook(
            new java.io.ByteArrayInputStream(excel.export(false)))) {
      assertEquals(2, wb.getSheetAt(0).getLastRowNum());
    }
  }

  @Test
  void overdueAndStatisticsUseAssignedTaskScope() {
    actor(boss);
    long other = user("ENGINEER");
    long template = inspections.template(Map.of("name", "统计模板", "items", List.of("电源")), null);
    for (long owner : List.of(engineer, other)) {
      long plan =
          inspections.plan(
              Map.of(
                  "template_id",
                  template,
                  "handler_id",
                  owner,
                  "name",
                  "统计计划",
                  "period",
                  "DAILY",
                  "next_run",
                  java.time.LocalDate.now().minusDays(1).toString(),
                  "asset_ids",
                  List.of(asset)));
      inspections.generate(plan);
    }
    inspections.overdue();
    long before = db.count("SELECT COUNT(*) FROM ops_notification WHERE receiver_id=?", engineer);
    inspections.overdue();
    assertEquals(
        before, db.count("SELECT COUNT(*) FROM ops_notification WHERE receiver_id=?", engineer));
    actor(worker);
    var stats =
        (Map<?, ?>)
            dashboard.stats(
                java.time.LocalDate.now().minusDays(1), java.time.LocalDate.now(), null);
    assertEquals(2L, ((Number) stats.get("tasks")).longValue());
    assertEquals(1L, ((Number) stats.get("overdueTasks")).longValue());
  }
}
