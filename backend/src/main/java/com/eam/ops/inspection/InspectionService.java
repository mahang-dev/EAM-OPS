package com.eam.ops.inspection;

import static com.eam.ops.common.BusinessException.require;
import static com.eam.ops.common.Db.*;
import static com.eam.ops.common.Input.*;

import com.eam.ops.alert.AlertService;
import com.eam.ops.common.*;
import com.eam.ops.security.Access;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InspectionService {
  private final Db db;
  private final Access access;
  private final Events events;
  private final AlertService alerts;
  private final ObjectMapper json;

  public InspectionService(
      Db db, Access access, Events events, AlertService alerts, ObjectMapper json) {
    this.db = db;
    this.access = access;
    this.events = events;
    this.alerts = alerts;
    this.json = json;
  }

  public String json(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  @Transactional
  public long template(Map<String, Object> r, Long id) {
    access.need("inspection:manage");
    long department =
        access.actor().admin() ? Input.id(r, "department_id") : access.actor().departmentId();
    access.scope(department);
    require(
        r.get("items") instanceof List<?> list && !list.isEmpty() && list.size() <= 50,
        400,
        "检查项数量为 1–50");
    var items = (List<?>) r.get("items");
    require(
        items.stream().allMatch(x -> x instanceof String s && !s.isBlank() && s.length() <= 120)
            && new HashSet<>(items).size() == items.size(),
        400,
        "检查项必须是不重复的非空文字");
    if (id == null)
      id =
          db.insert(
              "INSERT INTO ops_inspection_template(department_id,name,items) VALUES(?,?,?)",
              department,
              text(r, "name", 100),
              json(items));
    else {
      var t = db.one("SELECT * FROM ops_inspection_template WHERE id=?", id);
      access.scope(num(t, "department_id"));
      changed(
          db.update(
              "UPDATE ops_inspection_template SET name=?,items=?,version=version+1 WHERE id=? AND"
                  + " version=?",
              text(r, "name", 100),
              json(items),
              id,
              version(r)));
    }
    events.audit(department, "INSPECTION_TEMPLATE", id, r.toString());
    return id;
  }

  @Transactional
  public long plan(Map<String, Object> r) {
    access.need("inspection:manage");
    var template = db.one("SELECT * FROM ops_inspection_template WHERE id=?", id(r, "template_id"));
    long dept = num(template, "department_id");
    access.scope(dept);
    access.engineer(id(r, "handler_id"), dept);
    LocalDate start = LocalDate.parse(text(r, "next_run", 10));
    require(!start.isBefore(LocalDate.now().minusYears(1)), 400, "开始日期不得早于一年前");
    require(
        r.get("asset_ids") instanceof List<?> l && !l.isEmpty() && l.size() <= 200,
        400,
        "请选择 1–200 个资产");
    var assetIds =
        ((List<?>) r.get("asset_ids"))
            .stream().map(x -> Long.parseLong(x.toString())).distinct().sorted().toList();
    for (long assetId : assetIds) {
      var a = access.asset(assetId, true);
      require(
          num(a, "department_id") == dept && !a.get("status").equals("RETIRED"),
          400,
          "资产必须属于模板部门且未报废");
    }
    long plan =
        db.insert(
            "INSERT INTO"
                + " ops_inspection_plan(department_id,name,template_id,handler_id,period,anchor_day,next_run)"
                + " VALUES(?,?,?,?,?,?,?)",
            dept,
            text(r, "name", 100),
            id(r, "template_id"),
            id(r, "handler_id"),
            choice(r, "period", "DAILY", "WEEKLY", "MONTHLY"),
            start.getDayOfMonth(),
            start);
    for (long assetId : assetIds)
      db.update("INSERT INTO ops_plan_asset(plan_id,asset_id) VALUES(?,?)", plan, assetId);
    events.audit(dept, "INSPECTION_PLAN", plan, r.toString());
    return plan;
  }

  @Transactional
  public void toggle(long id, Map<String, Object> r) {
    access.need("inspection:manage");
    var plan = db.one("SELECT * FROM ops_inspection_plan WHERE id=?", id);
    access.scope(num(plan, "department_id"));
    changed(
        db.update(
            "UPDATE ops_inspection_plan SET enabled=?,version=version+1 WHERE id=? AND version=?",
            flag(r, "enabled"),
            id,
            version(r)));
    events.audit(num(plan, "department_id"), "PLAN_TOGGLE", id, r.toString());
  }

  @Transactional
  public void generate(long planId) {
    var p =
        db.one(
            "SELECT p.*,t.items FROM ops_inspection_plan p JOIN ops_inspection_template t ON"
                + " t.id=p.template_id WHERE p.id=? FOR UPDATE",
            planId);
    if (!bool(p, "enabled")) return;
    LocalDate date = LocalDate.parse(p.get("next_run").toString());
    int generated = 0;
    while (!date.isAfter(LocalDate.now()) && generated++ < 400) {
      var due = Cycles.next(date, p.get("period").toString(), (int) num(p, "anchor_day"));
      for (var link :
          db.list(
              "SELECT asset_id FROM ops_plan_asset WHERE plan_id=? ORDER BY asset_id", planId)) {
        var asset = db.one("SELECT * FROM ops_asset WHERE id=? FOR UPDATE", link.get("asset_id"));
        if (asset.get("status").equals("RETIRED")) continue;
        db.update(
            "INSERT INTO"
                + " ops_inspection_task(department_id,plan_id,asset_id,handler_id,cycle_date,due_at,snapshot)"
                + " VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
            p.get("department_id"),
            planId,
            asset.get("id"),
            p.get("handler_id"),
            date,
            due.atStartOfDay(),
            p.get("items").toString());
        events.notify(
            num(p, "handler_id"),
            "巡检任务已生成",
            p.get("name") + " / " + asset.get("name"),
            "task:" + planId + ":" + asset.get("id") + ":" + date);
      }
      date = due;
    }
    db.update(
        "UPDATE ops_inspection_plan SET next_run=?,version=version+1 WHERE id=?", date, planId);
    events.invalidate();
  }

  @Transactional
  public void complete(long id, Map<String, Object> r) {
    access.need("inspection:execute");
    var base = db.one("SELECT * FROM ops_inspection_task WHERE id=?", id);
    access.scope(num(base, "department_id"));
    access.handler(base);
    var asset = access.asset(num(base, "asset_id"), true);
    var task = db.one("SELECT * FROM ops_inspection_task WHERE id=? FOR UPDATE", id);
    require(task.get("status").equals("PENDING"), 409, "任务已完成");
    require(r.get("results") instanceof Map<?, ?>, 400, "请填写检查结果");
    var results = (Map<?, ?>) r.get("results");
    List<?> items;
    try {
      items = json.readValue(task.get("snapshot").toString(), List.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    require(
        results.size() == items.size()
            && items.stream().allMatch(i -> Set.of("NORMAL", "ABNORMAL").contains(results.get(i))),
        400,
        "请完整填写每个检查项");
    boolean abnormal = results.containsValue("ABNORMAL");
    String description = abnormal ? text(r, "description", 2000) : optional(r, "description", 2000);
    changed(
        db.update(
            "UPDATE ops_inspection_task SET"
                + " status='COMPLETED',completed_at=NOW(),overdue=(due_at<NOW()),version=version+1"
                + " WHERE id=? AND version=?",
            id,
            version(r)));
    db.update(
        "INSERT INTO ops_inspection_record(task_id,results,abnormal,description,operator_id)"
            + " VALUES(?,?,?,?,?)",
        id,
        json(results),
        abnormal,
        description,
        access.actor().id());
    if (abnormal)
      alerts.raise(
          asset,
          "inspection:" + id,
          "INSPECTION",
          "巡检异常：" + asset.get("name"),
          "WARNING",
          description.substring(0, Math.min(description.length(), 100)));
    events.audit(num(task, "department_id"), "INSPECTION_COMPLETE", id, json(results));
  }

  @Transactional
  public void overdue() {
    db.update("UPDATE ops_inspection_task SET overdue=1 WHERE status='PENDING' AND due_at<NOW()");
    db.update("UPDATE ops_work_order SET overdue=1 WHERE status<>'CLOSED' AND due_at<NOW()");
    for (var t : db.list("SELECT * FROM ops_inspection_task WHERE status='PENDING' AND overdue=1"))
      events.notify(
          num(t, "handler_id"), "巡检已逾期", "任务 #" + t.get("id"), "task-overdue:" + t.get("id"));
    for (var o : db.list("SELECT * FROM ops_work_order WHERE status<>'CLOSED' AND overdue=1")) {
      events.supervisors(
          num(o, "department_id"),
          "工单已超时",
          o.get("title").toString(),
          "order-overdue:" + o.get("id"));
      if (o.get("handler_id") != null)
        events.notify(
            num(o, "handler_id"),
            "工单已超时",
            o.get("title").toString(),
            "handler-overdue:" + o.get("id"));
    }
    events.invalidate();
  }
}
