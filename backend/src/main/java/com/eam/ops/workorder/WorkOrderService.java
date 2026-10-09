package com.eam.ops.workorder;

import static com.eam.ops.common.BusinessException.require;
import static com.eam.ops.common.Db.*;
import static com.eam.ops.common.Input.*;

import com.eam.ops.asset.AssetService;
import com.eam.ops.common.*;
import com.eam.ops.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderService {
  private final Db db;
  private final Access access;
  private final Events events;
  private final AssetService assets;

  public WorkOrderService(Db db, Access access, Events events, AssetService assets) {
    this.db = db;
    this.access = access;
    this.events = events;
    this.assets = assets;
  }

  @Transactional
  public long create(Map<String, Object> r) {
    access.need("order:create");
    var a = access.asset(id(r, "asset_id"), true);
    require(!a.get("status").equals("RETIRED"), 409, "报废资产不能创建工单");
    Long task = r.get("source_task_id") == null ? null : id(r, "source_task_id");
    if (task != null) {
      var t =
          db.one(
              "SELECT t.*,r.abnormal FROM ops_inspection_task t JOIN ops_inspection_record r ON"
                  + " r.task_id=t.id WHERE t.id=?",
              task);
      access.scope(num(t, "department_id"));
      if (access.actor().role().equals("ENGINEER")) access.handler(t);
      require(num(t, "asset_id") == num(a, "id") && bool(t, "abnormal"), 400, "巡检异常与资产不匹配");
      var existing = db.list("SELECT id FROM ops_work_order WHERE source_task_id=?", task);
      if (!existing.isEmpty()) return num(existing.get(0), "id");
    }
    int priority = (int) id(r, "priority");
    require(priority <= 3, 400, "优先级为 1–3");
    long order =
        db.insert(
            "INSERT INTO"
                + " ops_work_order(order_no,title,description,department_id,asset_id,source_task_id,created_by,priority,due_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?)",
            "WO-" + UUID.randomUUID().toString().substring(0, 18),
            text(r, "title", 200),
            text(r, "description", 2000),
            num(a, "department_id"),
            num(a, "id"),
            task,
            access.actor().id(),
            priority,
            java.time.LocalDateTime.now().plusHours(priority == 1 ? 4 : priority == 2 ? 24 : 72));
    db.update(
        "UPDATE ops_asset SET status='FAULT',version=version+1,updated_at=NOW() WHERE id=?",
        a.get("id"));
    assets.history(num(a, "id"), num(a, "department_id"), "FAULT", "关联故障工单 " + order);
    log(order, "", "PENDING", "create", r.toString());
    events.supervisors(
        num(a, "department_id"), "新工单待分派", text(r, "title", 200), "order-new:" + order);
    return order;
  }

  @Transactional
  public void action(long id, String action, Map<String, Object> r) {
    access.need(
        action.equals("assign")
            ? "order:assign"
            : Set.of("approve", "reject").contains(action) ? "order:verify" : "order:handle");
    var base = db.one("SELECT * FROM ops_work_order WHERE id=?", id);
    access.scope(num(base, "department_id"));
    var asset = access.asset(num(base, "asset_id"), true);
    var order = db.one("SELECT * FROM ops_work_order WHERE id=? FOR UPDATE", id);
    String old = order.get("status").toString();
    String next = OrderState.next(old, action);
    require(num(order, "version") == version(r), 409, "工单已变化，请刷新");
    Object handler = order.get("handler_id");
    String detail = optional(r, "detail", 2000);
    if (action.equals("assign")) {
      long engineer = Input.id(r, "handler_id");
      access.engineer(engineer, num(order, "department_id"));
      detail = "原处理人=" + handler + "，新处理人=" + engineer + "；" + text(r, "detail", 2000);
      handler = engineer;
    } else if (Set.of("approve", "reject").contains(action)) {
      require(
          handler != null && ((Number) handler).longValue() != access.actor().id(),
          403,
          "处理人不能自行验收");
      detail = text(r, "detail", 2000);
    } else {
      access.handler(order);
      if (!action.equals("accept")) detail = text(r, "detail", 2000);
    }
    changed(
        db.update(
            "UPDATE ops_work_order SET"
                + " status=?,handler_id=?,result=?,version=version+1,updated_at=NOW(),closed_at=?"
                + " WHERE id=? AND version=?",
            next,
            handler,
            action.equals("submit") ? detail : order.get("result"),
            next.equals("CLOSED") ? java.time.LocalDateTime.now() : null,
            id,
            version(r)));
    log(id, old, next, action, detail);
    events.audit(num(order, "department_id"), "ORDER_" + action, id, detail);
    if (action.equals("approve") && flag(r, "recovered")) {
      if (order.get("source_task_id") != null)
        db.update(
            "UPDATE ops_alert SET"
                + " status='RECOVERED',active_key=NULL,recovered_at=NOW(),version=version+1 WHERE"
                + " asset_id=? AND rule_key=? AND active_key IS NOT NULL",
            asset.get("id"),
            "inspection:" + order.get("source_task_id"));
      if (db.count(
                  "SELECT COUNT(*) FROM ops_work_order WHERE asset_id=? AND status<>'CLOSED'",
                  asset.get("id"))
              == 0
          && db.count(
                  "SELECT COUNT(*) FROM ops_alert WHERE asset_id=? AND active_key IS NOT NULL",
                  asset.get("id"))
              == 0) {
        db.update(
            "UPDATE ops_asset SET status='RUNNING',version=version+1,updated_at=NOW() WHERE id=?"
                + " AND status IN ('FAULT','MAINTENANCE')",
            asset.get("id"));
        assets.history(num(asset, "id"), num(asset, "department_id"), "RESTORE", "工单验收确认恢复 " + id);
      }
    }
    if (action.equals("submit"))
      events.supervisors(
          num(order, "department_id"),
          "工单待验收",
          order.get("title").toString(),
          "review:" + id + ":" + (version(r) + 1));
    else if (handler != null)
      events.notify(
          ((Number) handler).longValue(),
          "工单状态更新",
          order.get("title") + " → " + next,
          "order:" + id + ":" + (version(r) + 1));
  }

  private void log(long id, String before, String after, String action, String detail) {
    db.update(
        "INSERT INTO"
            + " ops_work_order_log(order_id,before_status,after_status,action,detail,operator_id)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        before,
        after,
        action,
        detail,
        access.actor().id());
  }
}
