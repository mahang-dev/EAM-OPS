CREATE TABLE sys_department (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL UNIQUE, enabled BOOLEAN NOT NULL DEFAULT TRUE);
CREATE TABLE sys_role (code VARCHAR(32) PRIMARY KEY, name VARCHAR(60) NOT NULL, permissions JSON NOT NULL);
CREATE TABLE sys_user (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(64) NOT NULL UNIQUE, display_name VARCHAR(100) NOT NULL,
 password_hash VARCHAR(100) NOT NULL, department_id BIGINT NOT NULL, role_code VARCHAR(32) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, auth_version INT NOT NULL DEFAULT 0, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (department_id) REFERENCES sys_department(id), FOREIGN KEY (role_code) REFERENCES sys_role(code)
);
CREATE TABLE ops_category (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(80) NOT NULL UNIQUE, enabled BOOLEAN NOT NULL DEFAULT TRUE);
CREATE TABLE ops_location (id BIGINT PRIMARY KEY AUTO_INCREMENT, department_id BIGINT NOT NULL, parent_id BIGINT, name VARCHAR(100) NOT NULL, location_type VARCHAR(16) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, FOREIGN KEY (department_id) REFERENCES sys_department(id), FOREIGN KEY (parent_id) REFERENCES ops_location(id));
CREATE TABLE ops_asset (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, asset_code VARCHAR(64) NOT NULL UNIQUE, name VARCHAR(100) NOT NULL,
 category_id BIGINT NOT NULL, department_id BIGINT NOT NULL, location_id BIGINT NOT NULL, owner_id BIGINT NOT NULL,
 model VARCHAR(100) NOT NULL DEFAULT '', ip_address VARCHAR(45) NOT NULL DEFAULT '', status VARCHAR(24) NOT NULL DEFAULT 'STOCK',
 version INT NOT NULL DEFAULT 0, created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (category_id) REFERENCES ops_category(id), FOREIGN KEY (department_id) REFERENCES sys_department(id),
 FOREIGN KEY (location_id) REFERENCES ops_location(id), FOREIGN KEY (owner_id) REFERENCES sys_user(id),
 INDEX idx_asset_scope (department_id,status,category_id)
);
CREATE TABLE ops_asset_change (id BIGINT PRIMARY KEY AUTO_INCREMENT, asset_id BIGINT NOT NULL, department_id BIGINT NOT NULL, action VARCHAR(32) NOT NULL, detail TEXT NOT NULL, operator_id BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (asset_id) REFERENCES ops_asset(id));
CREATE TABLE ops_asset_approval (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, asset_id BIGINT NOT NULL, department_id BIGINT NOT NULL, action VARCHAR(16) NOT NULL, target_location_id BIGINT,
 asset_version INT NOT NULL, applicant_id BIGINT NOT NULL, reviewer_id BIGINT, reason VARCHAR(1000) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', version INT NOT NULL DEFAULT 0, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (asset_id) REFERENCES ops_asset(id), FOREIGN KEY (target_location_id) REFERENCES ops_location(id)
);
CREATE TABLE ops_inspection_template (id BIGINT PRIMARY KEY AUTO_INCREMENT, department_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, items JSON NOT NULL, version INT NOT NULL DEFAULT 0);
CREATE TABLE ops_inspection_plan (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, department_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, template_id BIGINT NOT NULL,
 handler_id BIGINT NOT NULL, period VARCHAR(16) NOT NULL, anchor_day INT NOT NULL, next_run DATE NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, version INT NOT NULL DEFAULT 0,
 FOREIGN KEY (template_id) REFERENCES ops_inspection_template(id), FOREIGN KEY (handler_id) REFERENCES sys_user(id)
);
CREATE TABLE ops_plan_asset (plan_id BIGINT NOT NULL, asset_id BIGINT NOT NULL, PRIMARY KEY(plan_id,asset_id), FOREIGN KEY(plan_id) REFERENCES ops_inspection_plan(id), FOREIGN KEY(asset_id) REFERENCES ops_asset(id));
CREATE TABLE ops_inspection_task (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, department_id BIGINT NOT NULL, plan_id BIGINT NOT NULL, asset_id BIGINT NOT NULL,
 handler_id BIGINT NOT NULL, cycle_date DATE NOT NULL, due_at DATETIME NOT NULL, snapshot JSON NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING', overdue BOOLEAN NOT NULL DEFAULT FALSE, version INT NOT NULL DEFAULT 0,
 completed_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_task_cycle(plan_id,asset_id,cycle_date), FOREIGN KEY(plan_id) REFERENCES ops_inspection_plan(id), FOREIGN KEY(asset_id) REFERENCES ops_asset(id),
 INDEX idx_task_scope(department_id,status,due_at)
);
CREATE TABLE ops_inspection_record (id BIGINT PRIMARY KEY AUTO_INCREMENT, task_id BIGINT NOT NULL UNIQUE, results JSON NOT NULL, abnormal BOOLEAN NOT NULL, description VARCHAR(2000) NOT NULL, operator_id BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(task_id) REFERENCES ops_inspection_task(id));
CREATE TABLE ops_work_order (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_no VARCHAR(64) NOT NULL UNIQUE, title VARCHAR(200) NOT NULL, description VARCHAR(2000) NOT NULL,
 department_id BIGINT NOT NULL, asset_id BIGINT NOT NULL, source_task_id BIGINT UNIQUE, handler_id BIGINT, created_by BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING', priority INT NOT NULL DEFAULT 2, result VARCHAR(2000) NOT NULL DEFAULT '',
 version INT NOT NULL DEFAULT 0, due_at DATETIME NOT NULL, overdue BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, closed_at DATETIME,
 FOREIGN KEY(asset_id) REFERENCES ops_asset(id), FOREIGN KEY(source_task_id) REFERENCES ops_inspection_task(id), FOREIGN KEY(handler_id) REFERENCES sys_user(id),
 INDEX idx_order_scope(department_id,status,created_at), INDEX idx_order_handler(handler_id,status), INDEX idx_order_asset(asset_id,status)
);
CREATE TABLE ops_work_order_log (id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, before_status VARCHAR(24) NOT NULL, after_status VARCHAR(24) NOT NULL, action VARCHAR(32) NOT NULL, detail TEXT NOT NULL, operator_id BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(order_id) REFERENCES ops_work_order(id));
CREATE TABLE ops_alert_rule (id BIGINT PRIMARY KEY AUTO_INCREMENT, department_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, metric VARCHAR(24) NOT NULL, threshold_value DOUBLE NOT NULL, level VARCHAR(16) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, version INT NOT NULL DEFAULT 0);
CREATE TABLE ops_alert (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, department_id BIGINT NOT NULL, asset_id BIGINT NOT NULL, rule_key VARCHAR(80) NOT NULL, source VARCHAR(24) NOT NULL,
 title VARCHAR(200) NOT NULL, level VARCHAR(16) NOT NULL, status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
 active_key VARCHAR(160) UNIQUE, occurrences INT NOT NULL DEFAULT 1, metric_value VARCHAR(100) NOT NULL DEFAULT '', version INT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, recovered_at DATETIME,
 FOREIGN KEY(asset_id) REFERENCES ops_asset(id), INDEX idx_alert_scope(department_id,status)
);
CREATE TABLE ops_notification (id BIGINT PRIMARY KEY AUTO_INCREMENT, receiver_id BIGINT NOT NULL, title VARCHAR(200) NOT NULL, content VARCHAR(2000) NOT NULL, read_status BOOLEAN NOT NULL DEFAULT FALSE, dedup_key VARCHAR(160) UNIQUE, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(receiver_id) REFERENCES sys_user(id));
CREATE TABLE ops_audit_log (id BIGINT PRIMARY KEY AUTO_INCREMENT, department_id BIGINT NOT NULL, operator_id BIGINT NOT NULL, action VARCHAR(64) NOT NULL, target_id BIGINT NOT NULL, detail TEXT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, INDEX idx_audit_scope(department_id,created_at));
