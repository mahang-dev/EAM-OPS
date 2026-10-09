package com.eam.ops.security;

import static com.eam.ops.common.BusinessException.require;
import static com.eam.ops.common.Db.*;
import static com.eam.ops.common.Input.*;

import com.eam.ops.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
  private final Db db;
  private final Access access;
  private final AuthService auth;
  private final Events events;
  private final ObjectMapper json;

  public AdminService(Db db, Access access, AuthService auth, Events events, ObjectMapper json) {
    this.db = db;
    this.access = access;
    this.auth = auth;
    this.events = events;
    this.json = json;
  }

  @Transactional
  public long user(Map<String, Object> r, Long id) {
    access.need("system:manage");
    String role = choice(r, "role_code", "ADMIN", "ASSET", "ENGINEER", "SUPERVISOR", "AUDITOR");
    long dept = Input.id(r, "department_id");
    require(
        db.count("SELECT COUNT(*) FROM sys_department WHERE id=? AND enabled=1", dept) == 1,
        400,
        "部门无效");
    if (id == null) {
      String password = text(r, "password", 72);
      require(password.length() >= 10, 400, "密码至少 10 字符");
      id =
          db.insert(
              "INSERT INTO sys_user(username,display_name,password_hash,department_id,role_code)"
                  + " VALUES(?,?,?,?,?)",
              text(r, "username", 64),
              text(r, "display_name", 100),
              auth.hashPassword(password),
              dept,
              role);
    } else {
      var old = db.one("SELECT * FROM sys_user WHERE id=? FOR UPDATE", id);
      require(id != access.actor().id(), 409, "请通过个人设置修改自己，不能停用或重分配当前管理员");
      if (num(old, "department_id") != dept
          || !role.equals(old.get("role_code"))
          || !flag(r, "enabled"))
        require(
            db.count(
                        "SELECT COUNT(*) FROM ops_work_order WHERE handler_id=? AND"
                            + " status<>'CLOSED'",
                        id)
                    == 0
                && db.count(
                        "SELECT COUNT(*) FROM ops_inspection_task WHERE handler_id=? AND"
                            + " status<>'COMPLETED'",
                        id)
                    == 0
                && db.count(
                        "SELECT COUNT(*) FROM ops_inspection_plan WHERE handler_id=? AND enabled=1",
                        id)
                    == 0
                && db.count(
                        "SELECT COUNT(*) FROM ops_asset WHERE owner_id=? AND status<>'RETIRED'", id)
                    == 0,
            409,
            "用户仍关联资产、工单或巡检，请先转交或停用计划");
      db.update(
          "UPDATE sys_user SET"
              + " display_name=?,department_id=?,role_code=?,enabled=?,auth_version=auth_version+1"
              + " WHERE id=?",
          text(r, "display_name", 100),
          dept,
          role,
          flag(r, "enabled"),
          id);
    }
    events.audit(dept, "USER_UPDATE", id, "用户与权限配置变更");
    return id;
  }

  @Transactional
  public void role(String code, Map<String, Object> r) {
    access.need("system:manage");
    require(!code.equals("ADMIN"), 400, "系统管理员权限不可修改");
    var role = db.one("SELECT * FROM sys_role WHERE code=?", code);
    require(r.get("permissions") instanceof List<?>, 400, "权限必须为列表");
    Set<String> allowed =
        Set.of(
            "asset:read",
            "asset:write",
            "asset:approve",
            "inspection:read",
            "inspection:execute",
            "inspection:manage",
            "order:read",
            "order:create",
            "order:handle",
            "order:assign",
            "order:verify",
            "alert:read",
            "alert:write",
            "alert:manage",
            "dashboard:read",
            "audit:read");
    var ps = (List<?>) r.get("permissions");
    require(ps.stream().allMatch(allowed::contains), 400, "包含未知权限");
    require(
        !code.equals("AUDITOR") || ps.stream().allMatch("audit:read"::equals), 400, "审计角色只能读取日志");
    try {
      db.update(
          "UPDATE sys_role SET permissions=? WHERE code=?", json.writeValueAsString(ps), code);
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException(e);
    }
    db.update("UPDATE sys_user SET auth_version=auth_version+1 WHERE role_code=?", code);
    events.audit(access.actor().departmentId(), "ROLE_UPDATE", 0, "角色 " + code);
  }

  @Transactional
  public long dictionary(String kind, Map<String, Object> r, Long id) {
    access.need(
        kind.equals("locations") || kind.equals("categories") ? "asset:write" : "system:manage");
    String name = text(r, "name", 100);
    long dept =
        access.actor().admin() ? Input.id(r, "department_id") : access.actor().departmentId();
    if (kind.equals("locations")) {
      String type = choice(r, "location_type", "ROOM", "RACK");
      Long parent = type.equals("RACK") ? Input.id(r, "parent_id") : null;
      if (parent != null) {
        access.location(parent, dept);
        require(
            db.count(
                    "SELECT COUNT(*) FROM ops_location WHERE id=? AND location_type='ROOM'", parent)
                == 1,
            400,
            "机柜上级必须为机房");
      }
      if (id == null)
        id =
            db.insert(
                "INSERT INTO ops_location(department_id,parent_id,name,location_type)"
                    + " VALUES(?,?,?,?)",
                dept,
                parent,
                name,
                type);
      else {
        var old = db.one("SELECT * FROM ops_location WHERE id=? FOR UPDATE", id);
        access.scope(num(old, "department_id"));
        require(
            num(old, "department_id") == dept
                && old.get("location_type").equals(type)
                && Objects.equals(old.get("parent_id"), parent),
            400,
            "已有位置只允许重命名或停用");
        boolean enabled = flag(r, "enabled");
        if (!enabled)
          require(
              db.count(
                          "SELECT COUNT(*) FROM ops_asset WHERE location_id=? AND"
                              + " status<>'RETIRED'",
                          id)
                      == 0
                  && db.count(
                          "SELECT COUNT(*) FROM ops_location WHERE parent_id=? AND enabled=1", id)
                      == 0,
              409,
              "位置仍被使用");
        db.update("UPDATE ops_location SET name=?,enabled=? WHERE id=?", name, enabled, id);
      }
    } else if (kind.equals("categories")) {
      if (id == null) id = db.insert("INSERT INTO ops_category(name) VALUES(?)", name);
      else {
        if (!flag(r, "enabled"))
          require(
              db.count(
                      "SELECT COUNT(*) FROM ops_asset WHERE category_id=? AND status<>'RETIRED'",
                      id)
                  == 0,
              409,
              "分类仍被使用");
        changed(
            db.update(
                "UPDATE ops_category SET name=?,enabled=? WHERE id=?",
                name,
                flag(r, "enabled"),
                id));
      }
    } else if (kind.equals("departments")) {
      if (id == null) id = db.insert("INSERT INTO sys_department(name) VALUES(?)", name);
      else {
        if (!flag(r, "enabled"))
          require(
              db.count("SELECT COUNT(*) FROM sys_user WHERE department_id=? AND enabled=1", id)
                  == 0,
              409,
              "部门仍有有效用户");
        changed(
            db.update(
                "UPDATE sys_department SET name=?,enabled=? WHERE id=?",
                name,
                flag(r, "enabled"),
                id));
      }
    } else throw new BusinessException(400, "未知字典");
    events.audit(dept, "DICTIONARY_UPDATE", id, kind + ":" + name);
    return id;
  }

  @Transactional
  public long rule(Map<String, Object> r, Long id) {
    access.need("alert:manage");
    long dept =
        access.actor().admin() ? Input.id(r, "department_id") : access.actor().departmentId();
    access.scope(dept);
    String metric = choice(r, "metric", "CPU", "MEMORY", "DISK", "ONLINE"),
        level = choice(r, "level", "INFO", "WARNING", "CRITICAL");
    double threshold = Double.parseDouble(String.valueOf(r.get("threshold_value")));
    require(Double.isFinite(threshold) && threshold >= 0 && threshold <= 100, 400, "阈值为 0–100");
    if (id == null)
      id =
          db.insert(
              "INSERT INTO ops_alert_rule(department_id,name,metric,threshold_value,level)"
                  + " VALUES(?,?,?,?,?)",
              dept,
              text(r, "name", 100),
              metric,
              threshold,
              level);
    else {
      var old = db.one("SELECT * FROM ops_alert_rule WHERE id=?", id);
      access.scope(num(old, "department_id"));
      changed(
          db.update(
              "UPDATE ops_alert_rule SET"
                  + " name=?,metric=?,threshold_value=?,level=?,enabled=?,version=version+1 WHERE"
                  + " id=? AND version=?",
              text(r, "name", 100),
              metric,
              threshold,
              level,
              flag(r, "enabled"),
              id,
              version(r)));
    }
    events.audit(dept, "RULE_UPDATE", id, "规则配置变更");
    return id;
  }
}
