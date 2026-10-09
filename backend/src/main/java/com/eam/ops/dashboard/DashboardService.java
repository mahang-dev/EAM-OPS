package com.eam.ops.dashboard;

import com.eam.ops.common.*;
import com.eam.ops.security.Access;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
  private final Db db;
  private final Access access;
  private final StringRedisTemplate redis;
  private final ObjectMapper json;

  public DashboardService(Db db, Access access, StringRedisTemplate redis, ObjectMapper json) {
    this.db = db;
    this.access = access;
    this.redis = redis;
    this.json = json;
  }

  public Object stats(LocalDate from, LocalDate to, Long department) {
    access.need("dashboard:read");
    BusinessException.require(
        !to.isBefore(from) && !from.plusYears(3).isBefore(to), 400, "日期范围不合法或超过三年");
    String scope = access.filter("");
    if (department != null) {
      access.scope(department);
      scope += " AND department_id=" + department;
    }
    String epoch = "0";
    try {
      epoch = String.valueOf(redis.opsForValue().get("stats:epoch"));
    } catch (Exception ignored) {
    }
    String key = "stats:" + epoch + ":" + access.actor().id() + ":" + scope + ":" + from + ":" + to;
    try {
      String value = redis.opsForValue().get(key);
      if (value != null) return json.readValue(value, Map.class);
    } catch (Exception ignored) {
    }
    var result = new LinkedHashMap<String, Object>();
    String orders = " FROM ops_work_order WHERE " + scope + " AND created_at>=? AND created_at<?";
    String tasks =
        " FROM ops_inspection_task WHERE "
            + scope
            + (access.actor().role().equals("ENGINEER")
                ? " AND handler_id=" + access.actor().id()
                : "")
            + " AND cycle_date>=? AND cycle_date<?";
    Object[] dates = {from, to.plusDays(1)};
    result.put(
        "assets",
        db.count("SELECT COUNT(*) FROM ops_asset WHERE " + scope + " AND status<>'RETIRED'"));
    result.put(
        "faults",
        db.count("SELECT COUNT(*) FROM ops_asset WHERE " + scope + " AND status='FAULT'"));
    result.put("orders", db.count("SELECT COUNT(*)" + orders, dates));
    result.put("openOrders", db.count("SELECT COUNT(*)" + orders + " AND status<>'CLOSED'", dates));
    result.put(
        "overdueOrders",
        db.count("SELECT COUNT(*)" + orders + " AND status<>'CLOSED' AND due_at<NOW()", dates));
    result.put("tasks", db.count("SELECT COUNT(*)" + tasks, dates));
    result.put(
        "completedTasks", db.count("SELECT COUNT(*)" + tasks + " AND status='COMPLETED'", dates));
    result.put(
        "overdueTasks",
        db.count("SELECT COUNT(*)" + tasks + " AND status<>'COMPLETED' AND due_at<NOW()", dates));
    result.put(
        "averageHours",
        db.one(
                "SELECT COALESCE(ROUND(AVG(TIMESTAMPDIFF(SECOND,created_at,closed_at))/3600,2),0)"
                    + " AS value"
                    + orders
                    + " AND status='CLOSED'",
                dates)
            .get("value"));
    result.put(
        "assetStatus",
        db.list(
            "SELECT status AS name,COUNT(*) AS value FROM ops_asset WHERE "
                + scope
                + " GROUP BY status"));
    result.put(
        "categories",
        db.list(
            "SELECT c.name,COUNT(*) AS value FROM ops_asset a JOIN ops_category c ON"
                + " c.id=a.category_id WHERE "
                + scope
                + " GROUP BY c.id,c.name"));
    result.put(
        "orderStatus",
        db.list("SELECT status AS name,COUNT(*) AS value" + orders + " GROUP BY status", dates));
    result.put(
        "trend",
        db.list(
            "SELECT DATE(created_at) AS name,COUNT(*) AS value"
                + orders
                + " GROUP BY DATE(created_at) ORDER BY name",
            dates));
    result.put(
        "alerts",
        db.list(
            "SELECT * FROM ops_alert WHERE "
                + scope
                + " AND active_key IS NOT NULL ORDER BY id DESC LIMIT 5"));
    try {
      redis.opsForValue().set(key, json.writeValueAsString(result), Duration.ofSeconds(30));
    } catch (Exception ignored) {
    }
    return result;
  }
}
