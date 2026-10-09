package com.eam.ops.common;

import com.eam.ops.security.Access;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class QueryService {
  public record Resource(String table, String permission, String search, boolean scoped) {}

  private static final Map<String, Resource> RESOURCES =
      Map.ofEntries(
          Map.entry("assets", new Resource("ops_asset", "asset:read", "name", true)),
              Map.entry(
                  "approvals", new Resource("ops_asset_approval", "asset:read", "reason", true)),
          Map.entry(
                  "templates",
                  new Resource("ops_inspection_template", "inspection:read", "name", true)),
              Map.entry(
                  "plans", new Resource("ops_inspection_plan", "inspection:read", "name", true)),
          Map.entry("tasks", new Resource("ops_inspection_task", "inspection:read", "id", true)),
              Map.entry("orders", new Resource("ops_work_order", "order:read", "title", true)),
          Map.entry("alerts", new Resource("ops_alert", "alert:read", "title", true)),
              Map.entry("rules", new Resource("ops_alert_rule", "alert:manage", "name", true)),
          Map.entry("audit", new Resource("ops_audit_log", "audit:read", "action", true)),
              Map.entry("locations", new Resource("ops_location", "asset:read", "name", true)),
          Map.entry("users", new Resource("sys_user", "system:manage", "username", true)),
              Map.entry(
                  "departments", new Resource("sys_department", "system:manage", "name", false)),
          Map.entry("categories", new Resource("ops_category", "asset:read", "name", false)),
              Map.entry("notifications", new Resource("ops_notification", "", "title", false)));
  private final Db db;
  private final Access access;
  private final org.springframework.data.redis.core.StringRedisTemplate redis;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public QueryService(
      Db db,
      Access access,
      org.springframework.data.redis.core.StringRedisTemplate redis,
      com.fasterxml.jackson.databind.ObjectMapper json) {
    this.db = db;
    this.access = access;
    this.redis = redis;
    this.json = json;
  }

  private Object categories() {
    String key = "categories:0";
    try {
      key = "categories:" + redis.opsForValue().get("stats:epoch");
      String cached = redis.opsForValue().get(key);
      if (cached != null) return json.readValue(cached, List.class);
    } catch (Exception ignored) {
    }
    var rows = db.list("SELECT * FROM ops_category WHERE enabled=1");
    try {
      redis.opsForValue().set(key, json.writeValueAsString(rows), java.time.Duration.ofMinutes(5));
    } catch (Exception ignored) {
    }
    return rows;
  }

  private Resource resource(String key) {
    var r = RESOURCES.get(key);
    if (r == null) throw new BusinessException(404, "资源不存在");
    if (!r.permission.isEmpty()) access.need(r.permission);
    else access.actor();
    return r;
  }

  private String where(Resource r, String key) {
    String w = r.scoped ? access.filter("") : "1=1";
    if (key.equals("notifications")) w += " AND receiver_id=" + access.actor().id();
    if (key.equals("tasks") && access.actor().role().equals("ENGINEER"))
      w += " AND handler_id=" + access.actor().id();
    return w;
  }

  public Map<String, Object> list(
      String key,
      int page,
      int size,
      String q,
      String status,
      Long departmentId,
      Long assetId,
      Boolean overdue,
      String from,
      String to) {
    var r = resource(key);
    page = Math.max(1, page);
    size = Math.max(1, Math.min(100, size));
    String w = where(r, key);
    var params = new ArrayList<Object>();
    if (q != null && !q.isBlank()) {
      w += " AND " + r.search + " LIKE ?";
      params.add("%" + q + "%");
    }
    if (status != null
        && !status.isBlank()
        && Set.of("assets", "approvals", "tasks", "orders", "alerts").contains(key)) {
      w += " AND status=?";
      params.add(status);
    }
    if (departmentId != null && r.scoped) {
      access.scope(departmentId);
      w += " AND department_id=?";
      params.add(departmentId);
    }
    if (assetId != null && Set.of("orders", "tasks", "alerts", "approvals").contains(key)) {
      w += " AND asset_id=?";
      params.add(assetId);
    }
    if (overdue != null && Set.of("orders", "tasks").contains(key)) {
      w += " AND overdue=?";
      params.add(overdue);
    }
    if (Set.of("orders", "tasks").contains(key)) {
      String date = key.equals("tasks") ? "cycle_date" : "created_at";
      if (from != null && !from.isBlank()) {
        w += " AND " + date + ">=?";
        params.add(java.time.LocalDate.parse(from));
      }
      if (to != null && !to.isBlank()) {
        w += " AND " + date + "<?";
        params.add(java.time.LocalDate.parse(to).plusDays(1));
      }
    }
    long total = db.count("SELECT COUNT(*) FROM " + r.table + " WHERE " + w, params.toArray());
    params.add(size);
    params.add((page - 1) * size);
    String cols =
        key.equals("users")
            ? "id,username,display_name,department_id,role_code,enabled,created_at"
            : "*";
    return Map.of(
        "items",
        db.list(
            "SELECT "
                + cols
                + " FROM "
                + r.table
                + " WHERE "
                + w
                + " ORDER BY id DESC LIMIT ? OFFSET ?",
            params.toArray()),
        "total",
        total,
        "page",
        page,
        "size",
        size);
  }

  public Map<String, Object> detail(String key, long id) {
    var r = resource(key);
    BusinessException.require(!key.equals("users"), 403, "用户详情请使用用户列表");
    var row = db.one("SELECT * FROM " + r.table + " WHERE id=? AND " + where(r, key), id);
    if (key.equals("assets"))
      row.put(
          "history",
          db.list(
              "SELECT c.*,u.display_name AS operator_name FROM ops_asset_change c LEFT JOIN"
                  + " sys_user u ON u.id=c.operator_id WHERE asset_id=? ORDER BY c.id DESC",
              id));
    if (key.equals("orders"))
      row.put(
          "history",
          db.list(
              "SELECT l.*,u.display_name AS operator_name FROM ops_work_order_log l LEFT JOIN"
                  + " sys_user u ON u.id=l.operator_id WHERE order_id=? ORDER BY l.id DESC",
              id));
    if (key.equals("tasks"))
      row.put("records", db.list("SELECT * FROM ops_inspection_record WHERE task_id=?", id));
    return row;
  }

  public Object options() {
    var a = access.actor();
    BusinessException.require(!a.role().equals("AUDITOR"), 403, "审计角色仅可访问审计日志");
    return Map.of(
        "departments",
        db.list(
            "SELECT id,name,enabled FROM sys_department WHERE "
                + (a.admin() ? "1=1" : "id=" + a.departmentId())),
        "users",
        db.list(
            "SELECT id,display_name,department_id,role_code,enabled FROM sys_user WHERE enabled=1"
                + " AND "
                + access.filter("")),
        "locations",
        db.list("SELECT * FROM ops_location WHERE enabled=1 AND " + access.filter("")),
        "categories",
        categories(),
        "assets",
        db.list(
            "SELECT id,name,asset_code,department_id FROM ops_asset WHERE status<>'RETIRED' AND "
                + access.filter("")),
        "templates",
        db.list(
            "SELECT id,name,department_id FROM ops_inspection_template WHERE "
                + access.filter("")));
  }
}
