package com.eam.ops.alert;

import static com.eam.ops.common.BusinessException.require;
import static com.eam.ops.common.Db.*;
import static com.eam.ops.common.Input.*;

import com.eam.ops.common.*;
import com.eam.ops.security.Access;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertService {
  private final Db db;
  private final Access access;
  private final Events events;

  public AlertService(Db db, Access access, Events events) {
    this.db = db;
    this.access = access;
    this.events = events;
  }

  // Caller holds the asset row lock. All fault-producing paths share this lock order.
  public void raise(
      Map<String, Object> asset,
      String key,
      String source,
      String title,
      String level,
      String value) {
    long assetId = num(asset, "id");
    String active = assetId + ":" + key;
    var old = db.list("SELECT id FROM ops_alert WHERE active_key=?", active);
    if (old.isEmpty()) {
      long id =
          db.insert(
              "INSERT INTO"
                  + " ops_alert(department_id,asset_id,rule_key,source,title,level,active_key,metric_value)"
                  + " VALUES(?,?,?,?,?,?,?,?)",
              num(asset, "department_id"),
              assetId,
              key,
              source,
              title,
              level,
              active,
              value);
      events.supervisors(num(asset, "department_id"), "新设备告警", title, "alert:" + id);
    } else
      db.update(
          "UPDATE ops_alert SET"
              + " occurrences=occurrences+1,metric_value=?,last_seen_at=NOW(),version=version+1"
              + " WHERE active_key=?",
          value,
          active);
    db.update(
        "UPDATE ops_asset SET status='FAULT',version=version+1,updated_at=NOW() WHERE id=? AND"
            + " status<>'RETIRED'",
        assetId);
    events.invalidate();
  }

  @Transactional
  public void action(long id, String action, Map<String, Object> r) {
    access.need("alert:write");
    var base = db.one("SELECT * FROM ops_alert WHERE id=?", id);
    access.scope(num(base, "department_id"));
    access.asset(num(base, "asset_id"), true);
    require(Set.of("acknowledge", "recover").contains(action), 400, "未知告警操作");
    if (action.equals("acknowledge"))
      changed(
          db.update(
              "UPDATE ops_alert SET status='ACKNOWLEDGED',version=version+1 WHERE id=? AND"
                  + " version=? AND status='OPEN'",
              id,
              version(r)));
    else
      changed(
          db.update(
              "UPDATE ops_alert SET"
                  + " status='RECOVERED',active_key=NULL,recovered_at=NOW(),version=version+1 WHERE"
                  + " id=? AND version=? AND active_key IS NOT NULL",
              id,
              version(r)));
    events.audit(num(base, "department_id"), "ALERT_" + action, id, text(r, "detail", 1000));
  }

  @Transactional
  public void metrics(long assetId, Map<String, Object> r) {
    access.need("alert:manage");
    var asset = access.asset(assetId, true);
    require(!asset.get("status").equals("RETIRED"), 409, "设备已报废");
    for (String k : r.keySet())
      require(Set.of("CPU", "MEMORY", "DISK", "ONLINE").contains(k), 400, "未知指标");
    for (var rule :
        db.list(
            "SELECT * FROM ops_alert_rule WHERE department_id=? AND enabled=1",
            asset.get("department_id"))) {
      String metric = rule.get("metric").toString();
      if (!r.containsKey(metric)) continue;
      require(r.get(metric) instanceof Number, 400, "指标必须是数字");
      double value = ((Number) r.get(metric)).doubleValue();
      require(
          Double.isFinite(value)
              && value >= 0
              && value <= 100
              && (!metric.equals("ONLINE") || value == 0 || value == 1),
          400,
          "指标范围不合法");
      double threshold = ((Number) rule.get("threshold_value")).doubleValue();
      boolean abnormal = metric.equals("ONLINE") ? value == 0 : value > threshold;
      String key = "rule:" + rule.get("id");
      if (abnormal)
        raise(
            asset,
            key,
            "METRIC",
            rule.get("name").toString(),
            rule.get("level").toString(),
            String.valueOf(value));
      else
        db.update(
            "UPDATE ops_alert SET"
                + " status='RECOVERED',active_key=NULL,recovered_at=NOW(),version=version+1 WHERE"
                + " active_key=?",
            assetId + ":" + key);
    }
    events.audit(num(asset, "department_id"), "METRIC_REPORT", assetId, r.toString());
  }
}
