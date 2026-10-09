package com.eam.ops.security;

import static com.eam.ops.common.BusinessException.require;
import static com.eam.ops.common.Db.*;

import com.eam.ops.common.*;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("access")
public class Access {
  private final Db db;

  public Access(Db db) {
    this.db = db;
  }

  public Actor actor() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !(a.getPrincipal() instanceof Actor)) throw new BusinessException(401, "请先登录");
    return (Actor) a.getPrincipal();
  }

  public boolean has(String permission) {
    return actor().can(permission);
  }

  public Actor need(String permission) {
    var a = actor();
    require(a.can(permission), 403, "没有此操作权限");
    return a;
  }

  public void scope(long department) {
    require(actor().admin() || actor().departmentId() == department, 403, "不能访问其他部门数据");
  }

  public String filter(String alias) {
    return actor().admin() ? "1=1" : alias + "department_id=" + actor().departmentId();
  }

  public void handler(Map<String, Object> row) {
    require(
        actor().admin() || row.get("handler_id") != null && num(row, "handler_id") == actor().id(),
        403,
        "只能处理分派给自己的任务");
  }

  public Map<String, Object> asset(long id, boolean lock) {
    var r = db.one("SELECT * FROM ops_asset WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    scope(num(r, "department_id"));
    return r;
  }

  public void engineer(long id, long department) {
    require(
        db.count(
                "SELECT COUNT(*) FROM sys_user WHERE id=? AND department_id=? AND"
                    + " role_code='ENGINEER' AND enabled=1",
                id,
                department)
            == 1,
        400,
        "请选择本部门有效的运维工程师");
  }

  public void location(long id, long department) {
    require(
        db.count(
                "SELECT COUNT(*) FROM ops_location WHERE id=? AND department_id=? AND enabled=1",
                id,
                department)
            == 1,
        400,
        "位置必须属于资产部门且有效");
  }

  public void owner(long id, long department) {
    require(
        db.count(
                "SELECT COUNT(*) FROM sys_user WHERE id=? AND department_id=? AND enabled=1",
                id,
                department)
            == 1,
        400,
        "责任人必须属于资产部门且有效");
  }
}
