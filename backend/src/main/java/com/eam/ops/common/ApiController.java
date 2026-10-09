package com.eam.ops.common;

import com.eam.ops.alert.AlertService;
import com.eam.ops.asset.*;
import com.eam.ops.dashboard.DashboardService;
import com.eam.ops.inspection.InspectionService;
import com.eam.ops.security.*;
import com.eam.ops.workorder.WorkOrderService;
import java.time.LocalDate;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  private final QueryService query;
  private final AssetService assets;
  private final AssetExcel excel;
  private final WorkOrderService orders;
  private final InspectionService inspections;
  private final AlertService alerts;
  private final AdminService admin;
  private final DashboardService dashboard;
  private final Access access;
  private final Db db;
  private final Environment env;

  public ApiController(
      QueryService query,
      AssetService assets,
      AssetExcel excel,
      WorkOrderService orders,
      InspectionService inspections,
      AlertService alerts,
      AdminService admin,
      DashboardService dashboard,
      Access access,
      Db db,
      Environment env) {
    this.query = query;
    this.assets = assets;
    this.excel = excel;
    this.orders = orders;
    this.inspections = inspections;
    this.alerts = alerts;
    this.admin = admin;
    this.dashboard = dashboard;
    this.access = access;
    this.db = db;
    this.env = env;
  }

  @GetMapping("/{resource}")
  Object list(
      @PathVariable String resource,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long departmentId,
      @RequestParam(required = false) Long assetId,
      @RequestParam(required = false) Boolean overdue,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return query.list(resource, page, size, q, status, departmentId, assetId, overdue, from, to);
  }

  @GetMapping("/{resource}/{id:\\d+}")
  Object detail(@PathVariable String resource, @PathVariable long id) {
    return query.detail(resource, id);
  }

  @GetMapping("/options")
  Object options() {
    return query.options();
  }

  @GetMapping("/capabilities")
  Object capabilities() {
    return Map.of("demo", Arrays.asList(env.getActiveProfiles()).contains("demo"));
  }

  @GetMapping("/dashboard")
  Object dashboard(
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) Long departmentId) {
    return dashboard.stats(
        from == null ? LocalDate.now().minusDays(29) : from,
        to == null ? LocalDate.now() : to,
        departmentId);
  }

  @PostMapping("/assets")
  Object createAsset(@RequestBody Map<String, Object> r) {
    return Map.of("id", assets.create(r));
  }

  @PutMapping("/assets/{id}")
  void editAsset(@PathVariable long id, @RequestBody Map<String, Object> r) {
    assets.edit(id, r);
  }

  @PostMapping("/assets/{id}/lifecycle")
  void lifecycle(@PathVariable long id, @RequestBody Map<String, Object> r) {
    assets.lifecycle(id, r);
  }

  @PostMapping("/assets/{id}/approvals")
  Object request(@PathVariable long id, @RequestBody Map<String, Object> r) {
    return Map.of("id", assets.request(id, r));
  }

  @PostMapping("/approvals/{id}/review")
  void review(@PathVariable long id, @RequestBody Map<String, Object> r) {
    assets.approve(id, r);
  }

  @GetMapping("/assets/export")
  ResponseEntity<byte[]> export(@RequestParam(defaultValue = "false") boolean template)
      throws Exception {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=assets.xlsx")
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(excel.export(template));
  }

  @PostMapping(value = "/assets/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  Object importAssets(
      @RequestParam MultipartFile file, @RequestParam(defaultValue = "false") boolean commit)
      throws Exception {
    return excel.importFile(file, commit);
  }

  @PostMapping("/orders")
  Object createOrder(@RequestBody Map<String, Object> r) {
    return Map.of("id", orders.create(r));
  }

  @PostMapping("/orders/{id}/{action}")
  void orderAction(
      @PathVariable long id, @PathVariable String action, @RequestBody Map<String, Object> r) {
    orders.action(id, action, r);
  }

  @PostMapping("/templates")
  Object template(@RequestBody Map<String, Object> r) {
    return Map.of("id", inspections.template(r, null));
  }

  @PutMapping("/templates/{id}")
  void templateEdit(@PathVariable long id, @RequestBody Map<String, Object> r) {
    inspections.template(r, id);
  }

  @PostMapping("/plans")
  Object plan(@RequestBody Map<String, Object> r) {
    return Map.of("id", inspections.plan(r));
  }

  @PostMapping("/plans/{id}/toggle")
  void toggle(@PathVariable long id, @RequestBody Map<String, Object> r) {
    inspections.toggle(id, r);
  }

  @PostMapping("/plans/{id}/generate")
  void generate(@PathVariable long id) {
    access.need("inspection:manage");
    query.detail("plans", id);
    inspections.generate(id);
  }

  @PostMapping("/tasks/{id}/complete")
  void complete(@PathVariable long id, @RequestBody Map<String, Object> r) {
    inspections.complete(id, r);
  }

  @PostMapping("/alerts/{id}/{action}")
  void alertAction(
      @PathVariable long id, @PathVariable String action, @RequestBody Map<String, Object> r) {
    alerts.action(id, action, r);
  }

  @PostMapping("/metrics/{id}")
  void metrics(@PathVariable long id, @RequestBody Map<String, Object> r) {
    BusinessException.require(
        Arrays.asList(env.getActiveProfiles()).contains("demo"), 404, "仅演示环境支持模拟指标");
    alerts.metrics(id, r);
  }

  @PostMapping("/notifications/{id}/read")
  void read(@PathVariable long id) {
    Db.changed(
        db.update(
            "UPDATE ops_notification SET read_status=1 WHERE id=? AND receiver_id=?",
            id,
            access.actor().id()));
  }

  @PostMapping("/users")
  Object user(@RequestBody Map<String, Object> r) {
    return Map.of("id", admin.user(r, null));
  }

  @PutMapping("/users/{id}")
  void editUser(@PathVariable long id, @RequestBody Map<String, Object> r) {
    admin.user(r, id);
  }

  @GetMapping("/roles")
  Object roles() {
    access.need("system:manage");
    return db.list("SELECT * FROM sys_role");
  }

  @PutMapping("/roles/{code}")
  void role(@PathVariable String code, @RequestBody Map<String, Object> r) {
    admin.role(code, r);
  }

  @PostMapping("/rules")
  Object rule(@RequestBody Map<String, Object> r) {
    return Map.of("id", admin.rule(r, null));
  }

  @PutMapping("/rules/{id}")
  void editRule(@PathVariable long id, @RequestBody Map<String, Object> r) {
    admin.rule(r, id);
  }

  @PostMapping("/settings/{kind}")
  Object dictionary(@PathVariable String kind, @RequestBody Map<String, Object> r) {
    return Map.of("id", admin.dictionary(kind, r, null));
  }

  @PutMapping("/settings/{kind}/{id}")
  void editDictionary(
      @PathVariable String kind, @PathVariable long id, @RequestBody Map<String, Object> r) {
    admin.dictionary(kind, r, id);
  }
}
