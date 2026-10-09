package com.eam.ops.asset;

import static com.eam.ops.common.BusinessException.require;
import static com.eam.ops.common.Db.*;
import static com.eam.ops.common.Input.*;

import com.eam.ops.common.*;
import com.eam.ops.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetService {
  private final Db db;
  private final Access access;
  private final Events events;
  private final AssetMapper mapper;

  public AssetService(Db db, Access access, Events events, AssetMapper mapper) {
    this.db = db;
    this.access = access;
    this.events = events;
    this.mapper = mapper;
  }

  public long validate(Map<String, Object> r) {
    var actor = access.need("asset:write");
    long department = actor.admin() ? id(r, "department_id") : actor.departmentId();
    access.scope(department);
    text(r, "asset_code", 64);
    text(r, "name", 100);
    optional(r, "model", 100);
    String ip = optional(r, "ip_address", 45);
    require(ip.isEmpty() || ip.matches("[0-9a-fA-F:.]+"), 400, "IP 地址格式不合法");
    require(
        db.count("SELECT COUNT(*) FROM ops_category WHERE id=? AND enabled=1", id(r, "category_id"))
            == 1,
        400,
        "设备分类无效");
    access.location(id(r, "location_id"), department);
    access.owner(id(r, "owner_id"), department);
    return department;
  }

  @Transactional
  public long create(Map<String, Object> r) {
    long department = validate(r);
    Asset a = new Asset();
    a.assetCode = text(r, "asset_code", 64);
    a.name = text(r, "name", 100);
    a.categoryId = id(r, "category_id");
    a.departmentId = department;
    a.locationId = id(r, "location_id");
    a.ownerId = id(r, "owner_id");
    a.model = optional(r, "model", 100);
    a.ipAddress = optional(r, "ip_address", 45);
    a.status = "STOCK";
    a.version = 0;
    a.createdBy = access.actor().id();
    mapper.insert(a);
    history(a.id, department, "CREATE", "登记资产 " + a.assetCode);
    return a.id;
  }

  @Transactional
  public void edit(long id, Map<String, Object> r) {
    access.need("asset:write");
    var a = access.asset(id, true);
    require(!a.get("status").equals("RETIRED"), 409, "已报废资产不可修改");
    long department = num(a, "department_id");
    access.owner(Input.id(r, "owner_id"), department);
    changed(
        db.update(
            "UPDATE ops_asset SET"
                + " name=?,model=?,ip_address=?,owner_id=?,version=version+1,updated_at=NOW() WHERE"
                + " id=? AND version=?",
            text(r, "name", 100),
            optional(r, "model", 100),
            optional(r, "ip_address", 45),
            Input.id(r, "owner_id"),
            id,
            version(r)));
    history(id, department, "EDIT", "修改前：" + a + "；修改后：" + r);
  }

  @Transactional
  public void lifecycle(long id, Map<String, Object> r) {
    access.need("asset:write");
    var a = access.asset(id, true);
    String action = choice(r, "action", "ISSUE", "MAINTAIN");
    String status = a.get("status").toString();
    require(
        action.equals("ISSUE")
            ? status.equals("STOCK")
            : Set.of("RUNNING", "FAULT").contains(status),
        409,
        "当前状态不支持此操作");
    changed(
        db.update(
            "UPDATE ops_asset SET status=?,version=version+1,updated_at=NOW() WHERE id=? AND"
                + " version=?",
            action.equals("ISSUE") ? "RUNNING" : "MAINTENANCE",
            id,
            version(r)));
    history(id, num(a, "department_id"), action, text(r, "reason", 1000));
  }

  @Transactional
  public long request(long id, Map<String, Object> r) {
    access.need("asset:write");
    var a = access.asset(id, true);
    require(!a.get("status").equals("RETIRED"), 409, "设备已报废");
    require(num(a, "version") == version(r), 409, "资产版本已变化");
    String action = choice(r, "action", "MOVE", "RETIRE");
    Long target = action.equals("MOVE") ? Input.id(r, "target_location_id") : null;
    if (target != null) access.location(target, num(a, "department_id"));
    long request =
        db.insert(
            "INSERT INTO"
                + " ops_asset_approval(asset_id,department_id,action,target_location_id,asset_version,applicant_id,reason)"
                + " VALUES(?,?,?,?,?,?,?)",
            id,
            num(a, "department_id"),
            action,
            target,
            version(r),
            access.actor().id(),
            text(r, "reason", 1000));
    events.audit(num(a, "department_id"), "ASSET_REQUEST", request, r.toString());
    events.supervisors(
        num(a, "department_id"), "资产审批待处理", a.get("name").toString(), "approval:" + request);
    return request;
  }

  @Transactional
  public void approve(long requestId, Map<String, Object> r) {
    access.need("asset:approve");
    var request = db.one("SELECT * FROM ops_asset_approval WHERE id=?", requestId);
    access.scope(num(request, "department_id"));
    var a = access.asset(num(request, "asset_id"), true);
    request = db.one("SELECT * FROM ops_asset_approval WHERE id=? FOR UPDATE", requestId);
    require(num(request, "applicant_id") != access.actor().id(), 403, "不能审批自己的申请");
    require(request.get("status").equals("PENDING"), 409, "申请已处理");
    boolean accept = flag(r, "accept");
    if (accept) {
      require(num(a, "version") == num(request, "asset_version"), 409, "申请后资产已变化，请重新申请");
      require(!a.get("status").equals("RETIRED"), 409, "设备已报废");
      if (request.get("action").equals("RETIRE")) {
        require(
            db.count(
                        "SELECT COUNT(*) FROM ops_work_order WHERE asset_id=? AND status<>'CLOSED'",
                        a.get("id"))
                    == 0
                && db.count(
                        "SELECT COUNT(*) FROM ops_inspection_task WHERE asset_id=? AND"
                            + " status<>'COMPLETED'",
                        a.get("id"))
                    == 0,
            409,
            "存在未完成工单或巡检任务");
        db.update(
            "UPDATE ops_asset SET status='RETIRED',version=version+1,updated_at=NOW() WHERE id=?",
            a.get("id"));
      } else {
        access.location(num(request, "target_location_id"), num(a, "department_id"));
        db.update(
            "UPDATE ops_asset SET location_id=?,version=version+1,updated_at=NOW() WHERE id=?",
            request.get("target_location_id"),
            a.get("id"));
      }
      history(
          num(a, "id"),
          num(a, "department_id"),
          request.get("action").toString(),
          "审批通过；原位置="
              + a.get("location_id")
              + "；目标位置="
              + request.get("target_location_id")
              + "；"
              + request.get("reason"));
    }
    changed(
        db.update(
            "UPDATE ops_asset_approval SET status=?,reviewer_id=?,version=version+1 WHERE id=? AND"
                + " version=?",
            accept ? "APPROVED" : "REJECTED",
            access.actor().id(),
            requestId,
            version(r)));
    events.audit(num(a, "department_id"), "ASSET_APPROVAL", requestId, r.toString());
    events.notify(
        num(request, "applicant_id"),
        "资产审批已处理",
        accept ? "通过" : "驳回",
        "approval-result:" + requestId);
  }

  public void history(long id, long department, String action, String detail) {
    db.update(
        "INSERT INTO ops_asset_change(asset_id,department_id,action,detail,operator_id)"
            + " VALUES(?,?,?,?,?)",
        id,
        department,
        action,
        detail,
        access.actor().id());
    events.audit(department, "ASSET_" + action, id, detail);
  }
}
