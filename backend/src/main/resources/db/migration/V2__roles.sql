INSERT INTO sys_department(id,name) VALUES(1,'平台管理'),(2,'数据中心一部'),(3,'数据中心二部');
INSERT INTO sys_role(code,name,permissions) VALUES
 ('ADMIN','系统管理员','["*"]'),
 ('ASSET','资产管理员','["asset:read","asset:write","inspection:read","order:read","alert:read","dashboard:read"]'),
 ('ENGINEER','运维工程师','["asset:read","inspection:read","inspection:execute","order:read","order:create","order:handle","alert:read","alert:write","dashboard:read"]'),
 ('SUPERVISOR','运维主管','["asset:read","asset:approve","inspection:read","inspection:manage","order:read","order:create","order:assign","order:verify","alert:read","alert:write","alert:manage","dashboard:read"]'),
 ('AUDITOR','审计人员','["audit:read"]');
INSERT INTO ops_category(name) VALUES('服务器'),('交换机'),('防火墙'),('存储设备');
