package com.eam.ops.common;

import com.eam.ops.security.Access;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

@Service
public class Events {
  private final Db db;
  private final Access access;
  private final StringRedisTemplate redis;

  public Events(Db db, Access access, StringRedisTemplate redis) {
    this.db = db;
    this.access = access;
    this.redis = redis;
  }

  public void audit(long department, String action, long target, String detail) {
    db.update(
        "INSERT INTO ops_audit_log(department_id,operator_id,action,target_id,detail)"
            + " VALUES(?,?,?,?,?)",
        department,
        access.actor().id(),
        action,
        target,
        detail);
    invalidate();
  }

  public void notify(long user, String title, String content, String key) {
    db.update(
        "INSERT INTO ops_notification(receiver_id,title,content,dedup_key) VALUES(?,?,?,?) ON"
            + " DUPLICATE KEY UPDATE dedup_key=dedup_key",
        user,
        title,
        content,
        key);
  }

  public void supervisors(long department, String title, String content, String key) {
    for (var u :
        db.list(
            "SELECT id FROM sys_user WHERE department_id=? AND role_code='SUPERVISOR' AND"
                + " enabled=1",
            department)) notify(Db.num(u, "id"), title, content, key + ":" + u.get("id"));
  }

  public void invalidate() {
    Runnable r =
        () -> {
          try {
            redis.opsForValue().increment("stats:epoch");
          } catch (Exception ignored) {
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              r.run();
            }
          });
    else r.run();
  }
}
