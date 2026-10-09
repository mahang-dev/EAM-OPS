package com.eam.ops.security;

import com.eam.ops.common.Db;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@org.springframework.core.annotation.Order(10)
public class Bootstrap implements ApplicationRunner {
  private final Db db;
  private final AuthService auth;
  private final Environment env;
  private final String demoPassword, bootstrapPassword;

  public Bootstrap(
      Db db,
      AuthService auth,
      Environment env,
      @Value("${app.demo-password}") String demo,
      @Value("${app.bootstrap-password}") String bootstrap) {
    this.db = db;
    this.auth = auth;
    this.env = env;
    this.demoPassword = demo;
    this.bootstrapPassword = bootstrap;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (db.count("SELECT COUNT(*) FROM sys_user") > 0) return;
    boolean demo = Arrays.asList(env.getActiveProfiles()).contains("demo");
    String pass = demo ? demoPassword : bootstrapPassword;
    if (pass.length() < 10)
      throw new IllegalStateException(
          "首次启动必须设置至少 10 字符的 " + (demo ? "DEMO_PASSWORD" : "BOOTSTRAP_PASSWORD"));
    String hash = auth.hashPassword(pass);
    db.update(
        "INSERT INTO sys_user(id,username,display_name,password_hash,department_id,role_code)"
            + " VALUES(1,'admin','平台管理员',?,1,'ADMIN')",
        hash);
    if (!demo) return;
    String[] names = {"asset", "engineer", "supervisor", "auditor"};
    String[] roles = {"ASSET", "ENGINEER", "SUPERVISOR", "AUDITOR"};
    String[] labels = {"资产管理员", "运维工程师", "运维主管", "审计人员"};
    for (int dept = 2; dept <= 3; dept++) {
      for (int i = 0; i < 4; i++)
        db.update(
            "INSERT INTO sys_user(username,display_name,password_hash,department_id,role_code)"
                + " VALUES(?,?,?,?,?)",
            names[i] + (dept == 2 ? "" : "2"),
            "模拟·" + labels[i] + (dept - 1),
            hash,
            dept,
            roles[i]);
      long room =
          db.insert(
              "INSERT INTO ops_location(department_id,name,location_type) VALUES(?,?,'ROOM')",
              dept,
              "模拟·" + (dept == 2 ? "昆明" : "成都") + "数据中心");
      long rack =
          db.insert(
              "INSERT INTO ops_location(department_id,parent_id,name,location_type)"
                  + " VALUES(?,?,?,'RACK')",
              dept,
              room,
              "A01 机柜");
      long owner =
          db.count("SELECT id FROM sys_user WHERE department_id=? AND role_code='ASSET'", dept);
      long engineer =
          db.count("SELECT id FROM sys_user WHERE department_id=? AND role_code='ENGINEER'", dept);
      for (int i = 1; i <= 16; i++)
        db.update(
            "INSERT INTO"
                + " ops_asset(asset_code,name,category_id,department_id,location_id,owner_id,model,ip_address,status,created_by)"
                + " VALUES(?,?,?,?,?,?,?,?,?,1)",
            "DEMO-" + dept + "-" + String.format("%03d", i),
            new String[] {"计算节点", "核心交换机", "边界防火墙", "存储阵列"}[(i - 1) % 4]
                + " "
                + String.format("%02d", i),
            (i - 1) % 4 + 1,
            dept,
            rack,
            owner,
            "DEMO-SERIES",
            "192.0.2." + ((dept - 2) * 16 + i),
            "RUNNING");
      long template =
          db.insert(
              "INSERT INTO ops_inspection_template(department_id,name,items) VALUES(?,?,'["
                  + '"'
                  + "电源与指示灯"
                  + '"'
                  + ","
                  + '"'
                  + "接口连通状态"
                  + '"'
                  + ","
                  + '"'
                  + "系统资源使用率"
                  + '"'
                  + "]')",
              dept,
              "模拟·设备日常巡检");
      long plan =
          db.insert(
              "INSERT INTO"
                  + " ops_inspection_plan(department_id,name,template_id,handler_id,period,anchor_day,next_run)"
                  + " VALUES(?,?,?,?,'DAILY',1,CURRENT_DATE)",
              dept,
              "每日基础巡检",
              template,
              engineer);
      for (var asset :
          db.list("SELECT id FROM ops_asset WHERE department_id=? ORDER BY id LIMIT 4", dept))
        db.update(
            "INSERT INTO ops_plan_asset(plan_id,asset_id) VALUES(?,?)", plan, asset.get("id"));
      for (String metric : List.of("CPU", "MEMORY", "DISK", "ONLINE"))
        db.update(
            "INSERT INTO ops_alert_rule(department_id,name,metric,threshold_value,level)"
                + " VALUES(?,?,?,?,'WARNING')",
            dept,
            metric + " 异常规则",
            metric,
            metric.equals("ONLINE") ? 0 : 85);
    }
  }
}
