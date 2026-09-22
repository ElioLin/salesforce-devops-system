-- ==========================================================================================
--  Salesforce DevOps 平台 - 数据库完整初始化脚本
-- ==========================================================================================
--  项目名称 : Salesforce DevOps (基于 RuoYi-Vue 二次开发)
--  脚本说明 : 包含 RuoYi 基础框架表 + Salesforce 部署/数据比对业务表 + 菜单/字典/角色seed数据
--  数据库   : MySQL 5.7+ / 8.0+
--  字符集   : utf8mb4
--
--  【使用方式】
--    mysql -u root -p < sf_devops_init.sql
--    或在客户端中直接执行本脚本
--
--  【重要提示】
--    1. 本脚本为全新初始化脚本，会 DROP 并重建所有表，请勿在生产库直接执行！
--    2. 所有示例数据（部门/用户/邮箱/手机号）均为脱敏化名，请按实际组织架构替换。
--    3. 默认租户编号为 000000，多租户隔离依赖 sys_dept / sys_user / sys_role 的 tenant_id 字段。
--    4. 默认账号：admin / admin123
--
--  二次开发说明：本项目基于 RuoYi-Vue (https://gitee.com/y_project/RuoYi-Vue) 构建，
--  遵循 MIT 开源协议，保留原框架的版权与许可声明。
-- ==========================================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- 1、部门表
-- ----------------------------
drop table if exists sys_dept;
create table sys_dept (
  dept_id           bigint(20)      not null auto_increment    comment '部门id',
  parent_id         bigint(20)      default 0                  comment '父部门id',
  ancestors         varchar(50)     default ''                 comment '祖级列表',
  dept_name         varchar(30)     default ''                 comment '部门名称',
  order_num         int(4)          default 0                  comment '显示顺序',
  leader            varchar(20)     default null               comment '负责人',
  phone             varchar(11)     default null               comment '联系电话',
  email             varchar(50)     default null               comment '邮箱',
  status            char(1)         default '0'                comment '部门状态（0正常 1停用）',
  del_flag          char(1)         default '0'                comment '删除标志（0代表存在 2代表删除）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time 	    datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  tenant_id         varchar(20)     default '000000'           comment '租户编号',
  primary key (dept_id)
) engine=innodb auto_increment=200 comment = '部门表';

-- ----------------------------
-- 初始化-部门表数据（示例数据，人员均为化名）
-- ----------------------------
insert into sys_dept values(100,  0,   '0',          '示例集团',   0, '张伟', '13800000000', 'zhangwei@example.com', '0', '0', 'admin', sysdate(), '', null, '000000');
insert into sys_dept values(101,  100, '0,100',      '上海总部',   1, '张伟', '13800000000', 'zhangwei@example.com', '0', '0', 'admin', sysdate(), '', null, '000000');
insert into sys_dept values(102,  100, '0,100',      '北京分公司', 2, '李娜', '13800000001', 'lina@example.com',     '0', '0', 'admin', sysdate(), '', null, '000000');
insert into sys_dept values(103,  101, '0,100,101',  '研发部门',   1, '李娜', '13800000001', 'lina@example.com',     '0', '0', 'admin', sysdate(), '', null, '000000');
insert into sys_dept values(104,  101, '0,100,101',  '市场部门',   2, '王强', '13800000002', 'wangqiang@example.com','0', '0', 'admin', sysdate(), '', null, '000000');
insert into sys_dept values(105,  101, '0,100,101',  '测试部门',   3, '刘洋', '13800000003', 'liuyang@example.com',  '0', '0', 'admin', sysdate(), '', null, '000000');
insert into sys_dept values(106,  101, '0,100,101',  '财务部门',   4, '陈静', '13800000004', 'chenjing@example.com', '0', '0', 'admin', sysdate(), '', null, '000000');
insert into sys_dept values(107,  101, '0,100,101',  '运维部门',   5, '赵磊', '13800000005', 'zhaolei@example.com',  '0', '0', 'admin', sysdate(), '', null, '000000');
insert into sys_dept values(108,  102, '0,100,102',  '市场部门',   1, '孙丽', '13800000006', 'sunli@example.com',    '0', '0', 'admin', sysdate(), '', null, '000000');
insert into sys_dept values(109,  102, '0,100,102',  '财务部门',   2, '周敏', '13800000007', 'zhoumin@example.com',  '0', '0', 'admin', sysdate(), '', null, '000000');


-- ----------------------------
-- 2、用户信息表
-- ----------------------------
drop table if exists sys_user;
create table sys_user (
  user_id           bigint(20)      not null auto_increment    comment '用户ID',
  dept_id           bigint(20)      default null               comment '部门ID',
  user_name         varchar(30)     not null                   comment '用户账号',
  nick_name         varchar(30)     not null                   comment '用户昵称',
  user_type         varchar(2)      default '00'               comment '用户类型（00系统用户）',
  email             varchar(50)     default ''                 comment '用户邮箱',
  phonenumber       varchar(11)     default ''                 comment '手机号码',
  sex               char(1)         default '0'                comment '用户性别（0男 1女 2未知）',
  avatar            varchar(100)    default ''                 comment '头像地址',
  password          varchar(100)    default ''                 comment '密码',
  status            char(1)         default '0'                comment '账号状态（0正常 1停用）',
  del_flag          char(1)         default '0'                comment '删除标志（0代表存在 2代表删除）',
  login_ip          varchar(128)    default ''                 comment '最后登录IP',
  login_date        datetime                                   comment '最后登录时间',
  pwd_update_date   datetime                                   comment '密码最后更新时间',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  tenant_id         varchar(20)     default '000000'           comment '租户编号',
  primary key (user_id)
) engine=innodb auto_increment=100 comment = '用户信息表';

-- ----------------------------
-- 初始化-用户信息表数据（默认密码 admin123，上线后请立即修改）
-- ----------------------------
insert into sys_user values(1,  103, 'admin', '张伟', '00', 'zhangwei@example.com', '13800000000', '1', '', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '0', '0', '127.0.0.1', sysdate(), sysdate(), 'admin', sysdate(), '', null, '系统管理员', '000000');
insert into sys_user values(2,  105, 'sfdev', '李娜', '00', 'lina@example.com',     '13800000001', '1', '', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '0', '0', '127.0.0.1', sysdate(), sysdate(), 'admin', sysdate(), '', null, 'Salesforce开发工程师', '000000');


-- ----------------------------
-- 3、岗位信息表
-- ----------------------------
drop table if exists sys_post;
create table sys_post
(
  post_id       bigint(20)      not null auto_increment    comment '岗位ID',
  post_code     varchar(64)     not null                   comment '岗位编码',
  post_name     varchar(50)     not null                   comment '岗位名称',
  post_sort     int(4)          not null                   comment '显示顺序',
  status        char(1)         not null                   comment '状态（0正常 1停用）',
  create_by     varchar(64)     default ''                 comment '创建者',
  create_time   datetime                                   comment '创建时间',
  update_by     varchar(64)     default ''			       comment '更新者',
  update_time   datetime                                   comment '更新时间',
  remark        varchar(500)    default null               comment '备注',
  primary key (post_id)
) engine=innodb comment = '岗位信息表';

-- ----------------------------
-- 初始化-岗位信息表数据
-- ----------------------------
insert into sys_post values(1, 'ceo',  '董事长',    1, '0', 'admin', sysdate(), '', null, '');
insert into sys_post values(2, 'se',   '项目经理',  2, '0', 'admin', sysdate(), '', null, '');
insert into sys_post values(3, 'hr',   '人力资源',  3, '0', 'admin', sysdate(), '', null, '');
insert into sys_post values(4, 'user', '普通员工',  4, '0', 'admin', sysdate(), '', null, '');


-- ----------------------------
-- 4、角色信息表
-- ----------------------------
drop table if exists sys_role;
create table sys_role (
  role_id              bigint(20)      not null auto_increment    comment '角色ID',
  role_name            varchar(30)     not null                   comment '角色名称',
  role_key             varchar(100)    not null                   comment '角色权限字符串',
  role_sort            int(4)          not null                   comment '显示顺序',
  data_scope           char(1)         default '1'                comment '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）',
  menu_check_strictly  tinyint(1)      default 1                  comment '菜单树选择项是否关联显示',
  dept_check_strictly  tinyint(1)      default 1                  comment '部门树选择项是否关联显示',
  status               char(1)         not null                   comment '角色状态（0正常 1停用）',
  del_flag             char(1)         default '0'                comment '删除标志（0代表存在 2代表删除）',
  create_by            varchar(64)     default ''                 comment '创建者',
  create_time          datetime                                   comment '创建时间',
  update_by            varchar(64)     default ''                 comment '更新者',
  update_time          datetime                                   comment '更新时间',
  remark               varchar(500)    default null               comment '备注',
  tenant_id            varchar(20)     default '000000'           comment '租户编号',
  primary key (role_id)
) engine=innodb auto_increment=100 comment = '角色信息表';

-- ----------------------------
-- 初始化-角色信息表数据
-- ----------------------------
insert into sys_role values('1', '超级管理员',  'admin',  1, 1, 1, 1, '0', '0', 'admin', sysdate(), '', null, '超级管理员', '000000');
insert into sys_role values('2', '普通角色',    'common', 2, 2, 1, 1, '0', '0', 'admin', sysdate(), '', null, '普通角色',   '000000');
insert into sys_role values('3', 'Salesforce运维', 'sfops', 3, 1, 1, 1, '0', '0', 'admin', sysdate(), '', null, 'Salesforce部署与数据比对运维角色', '000000');


-- ----------------------------
-- 5、菜单权限表
-- ----------------------------
drop table if exists sys_menu;
create table sys_menu (
  menu_id           bigint(20)      not null auto_increment    comment '菜单ID',
  menu_name         varchar(50)     not null                   comment '菜单名称',
  parent_id         bigint(20)      default 0                  comment '父菜单ID',
  order_num         int(4)          default 0                  comment '显示顺序',
  path              varchar(200)    default ''                 comment '路由地址',
  component         varchar(255)    default null               comment '组件路径',
  query             varchar(255)    default null               comment '路由参数',
  route_name        varchar(50)     default ''                 comment '路由名称',
  is_frame          int(1)          default 1                  comment '是否为外链（0是 1否）',
  is_cache          int(1)          default 0                  comment '是否缓存（0缓存 1不缓存）',
  menu_type         char(1)         default ''                 comment '菜单类型（M目录 C菜单 F按钮）',
  visible           char(1)         default 0                  comment '菜单状态（0显示 1隐藏）',
  status            char(1)         default 0                  comment '菜单状态（0正常 1停用）',
  perms             varchar(100)    default null               comment '权限标识',
  icon              varchar(100)    default '#'                comment '菜单图标',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default ''                 comment '备注',
  primary key (menu_id)
) engine=innodb auto_increment=2000 comment = '菜单权限表';

-- ----------------------------
-- 初始化-菜单信息表数据
-- ----------------------------
-- 一级菜单
insert into sys_menu values('1', '系统管理', '0', '1', 'system',           null, '', '', 1, 0, 'M', '0', '0', '', 'system',   'admin', sysdate(), '', null, '系统管理目录');
insert into sys_menu values('2', '系统监控', '0', '2', 'monitor',          null, '', '', 1, 0, 'M', '0', '0', '', 'monitor',  'admin', sysdate(), '', null, '系统监控目录');
insert into sys_menu values('3', '系统工具', '0', '3', 'tool',             null, '', '', 1, 0, 'M', '0', '0', '', 'tool',     'admin', sysdate(), '', null, '系统工具目录');
insert into sys_menu values('4', '若依官网', '0', '4', 'http://ruoyi.vip', null, '', '', 0, 0, 'M', '0', '0', '', 'guide',    'admin', sysdate(), '', null, '若依官网地址');
-- 二级菜单
insert into sys_menu values('100',  '用户管理', '1',   '1', 'user',       'system/user/index',        '', '', 1, 0, 'C', '0', '0', 'system:user:list',        'user',          'admin', sysdate(), '', null, '用户管理菜单');
insert into sys_menu values('101',  '角色管理', '1',   '2', 'role',       'system/role/index',        '', '', 1, 0, 'C', '0', '0', 'system:role:list',        'peoples',       'admin', sysdate(), '', null, '角色管理菜单');
insert into sys_menu values('102',  '菜单管理', '1',   '3', 'menu',       'system/menu/index',        '', '', 1, 0, 'C', '0', '0', 'system:menu:list',        'tree-table',    'admin', sysdate(), '', null, '菜单管理菜单');
insert into sys_menu values('103',  '部门管理', '1',   '4', 'dept',       'system/dept/index',        '', '', 1, 0, 'C', '0', '0', 'system:dept:list',        'tree',          'admin', sysdate(), '', null, '部门管理菜单');
insert into sys_menu values('104',  '岗位管理', '1',   '5', 'post',       'system/post/index',        '', '', 1, 0, 'C', '0', '0', 'system:post:list',        'post',          'admin', sysdate(), '', null, '岗位管理菜单');
insert into sys_menu values('105',  '字典管理', '1',   '6', 'dict',       'system/dict/index',        '', '', 1, 0, 'C', '0', '0', 'system:dict:list',        'dict',          'admin', sysdate(), '', null, '字典管理菜单');
insert into sys_menu values('106',  '参数设置', '1',   '7', 'config',     'system/config/index',      '', '', 1, 0, 'C', '0', '0', 'system:config:list',      'edit',          'admin', sysdate(), '', null, '参数设置菜单');
insert into sys_menu values('107',  '通知公告', '1',   '8', 'notice',     'system/notice/index',      '', '', 1, 0, 'C', '0', '0', 'system:notice:list',      'message',       'admin', sysdate(), '', null, '通知公告菜单');
insert into sys_menu values('108',  '日志管理', '1',   '9', 'log',        '',                         '', '', 1, 0, 'M', '0', '0', '',                        'log',           'admin', sysdate(), '', null, '日志管理菜单');
insert into sys_menu values('109',  '在线用户', '2',   '1', 'online',     'monitor/online/index',     '', '', 1, 0, 'C', '0', '0', 'monitor:online:list',     'online',        'admin', sysdate(), '', null, '在线用户菜单');
insert into sys_menu values('110',  '定时任务', '2',   '2', 'job',        'monitor/job/index',        '', '', 1, 0, 'C', '0', '0', 'monitor:job:list',        'job',           'admin', sysdate(), '', null, '定时任务菜单');
insert into sys_menu values('111',  '数据监控', '2',   '3', 'druid',      'monitor/druid/index',      '', '', 1, 0, 'C', '0', '0', 'monitor:druid:list',      'druid',         'admin', sysdate(), '', null, '数据监控菜单');
insert into sys_menu values('112',  '服务监控', '2',   '4', 'server',     'monitor/server/index',     '', '', 1, 0, 'C', '0', '0', 'monitor:server:list',     'server',        'admin', sysdate(), '', null, '服务监控菜单');
insert into sys_menu values('113',  '缓存监控', '2',   '5', 'cache',      'monitor/cache/index',      '', '', 1, 0, 'C', '0', '0', 'monitor:cache:list',      'redis',         'admin', sysdate(), '', null, '缓存监控菜单');
insert into sys_menu values('114',  '缓存列表', '2',   '6', 'cacheList',  'monitor/cache/list',       '', '', 1, 0, 'C', '0', '0', 'monitor:cache:list',      'redis-list',    'admin', sysdate(), '', null, '缓存列表菜单');
insert into sys_menu values('115',  '表单构建', '3',   '1', 'build',      'tool/build/index',         '', '', 1, 0, 'C', '0', '0', 'tool:build:list',         'build',         'admin', sysdate(), '', null, '表单构建菜单');
insert into sys_menu values('116',  '代码生成', '3',   '2', 'gen',        'tool/gen/index',           '', '', 1, 0, 'C', '0', '0', 'tool:gen:list',           'code',          'admin', sysdate(), '', null, '代码生成菜单');
insert into sys_menu values('117',  '系统接口', '3',   '3', 'swagger',    'tool/swagger/index',       '', '', 1, 0, 'C', '0', '0', 'tool:swagger:list',       'swagger',       'admin', sysdate(), '', null, '系统接口菜单');
-- 三级菜单
insert into sys_menu values('500',  '操作日志', '108', '1', 'operlog',    'monitor/operlog/index',    '', '', 1, 0, 'C', '0', '0', 'monitor:operlog:list',    'form',          'admin', sysdate(), '', null, '操作日志菜单');
insert into sys_menu values('501',  '登录日志', '108', '2', 'logininfor', 'monitor/logininfor/index', '', '', 1, 0, 'C', '0', '0', 'monitor:logininfor:list', 'logininfor',    'admin', sysdate(), '', null, '登录日志菜单');
-- 用户管理按钮
insert into sys_menu values('1000', '用户查询', '100', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1001', '用户新增', '100', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1002', '用户修改', '100', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1003', '用户删除', '100', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:remove',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1004', '用户导出', '100', '5',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:export',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1005', '用户导入', '100', '6',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:import',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1006', '重置密码', '100', '7',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:resetPwd',       '#', 'admin', sysdate(), '', null, '');
-- 角色管理按钮
insert into sys_menu values('1007', '角色查询', '101', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1008', '角色新增', '101', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1009', '角色修改', '101', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1010', '角色删除', '101', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:remove',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1011', '角色导出', '101', '5',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:export',         '#', 'admin', sysdate(), '', null, '');
-- 菜单管理按钮
insert into sys_menu values('1012', '菜单查询', '102', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1013', '菜单新增', '102', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1014', '菜单修改', '102', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1015', '菜单删除', '102', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:remove',         '#', 'admin', sysdate(), '', null, '');
-- 部门管理按钮
insert into sys_menu values('1016', '部门查询', '103', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:dept:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1017', '部门新增', '103', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:dept:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1018', '部门修改', '103', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:dept:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1019', '部门删除', '103', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:dept:remove',         '#', 'admin', sysdate(), '', null, '');
-- 岗位管理按钮
insert into sys_menu values('1020', '岗位查询', '104', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1021', '岗位新增', '104', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1022', '岗位修改', '104', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1023', '岗位删除', '104', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:remove',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1024', '岗位导出', '104', '5',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:export',         '#', 'admin', sysdate(), '', null, '');
-- 字典管理按钮
insert into sys_menu values('1025', '字典查询', '105', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1026', '字典新增', '105', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1027', '字典修改', '105', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1028', '字典删除', '105', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:remove',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1029', '字典导出', '105', '5', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:export',         '#', 'admin', sysdate(), '', null, '');
-- 参数设置按钮
insert into sys_menu values('1030', '参数查询', '106', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:query',        '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1031', '参数新增', '106', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:add',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1032', '参数修改', '106', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:edit',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1033', '参数删除', '106', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:remove',       '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1034', '参数导出', '106', '5', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:export',       '#', 'admin', sysdate(), '', null, '');
-- 通知公告按钮
insert into sys_menu values('1035', '公告查询', '107', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:notice:query',        '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1036', '公告新增', '107', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:notice:add',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1037', '公告修改', '107', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:notice:edit',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1038', '公告删除', '107', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:notice:remove',       '#', 'admin', sysdate(), '', null, '');
-- 操作日志按钮
insert into sys_menu values('1039', '操作查询', '500', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:operlog:query',      '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1040', '操作删除', '500', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:operlog:remove',     '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1041', '日志导出', '500', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:operlog:export',     '#', 'admin', sysdate(), '', null, '');
-- 登录日志按钮
insert into sys_menu values('1042', '登录查询', '501', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:query',   '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1043', '登录删除', '501', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:remove',  '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1044', '日志导出', '501', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:export',  '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1045', '账户解锁', '501', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:unlock',  '#', 'admin', sysdate(), '', null, '');
-- 在线用户按钮
insert into sys_menu values('1046', '在线查询', '109', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:online:query',       '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1047', '批量强退', '109', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:online:batchLogout', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1048', '单条强退', '109', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:online:forceLogout', '#', 'admin', sysdate(), '', null, '');
-- 定时任务按钮
insert into sys_menu values('1049', '任务查询', '110', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1050', '任务新增', '110', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1051', '任务修改', '110', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1052', '任务删除', '110', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:remove',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1053', '状态修改', '110', '5', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:changeStatus',   '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1054', '任务导出', '110', '6', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:export',         '#', 'admin', sysdate(), '', null, '');
-- 代码生成按钮
insert into sys_menu values('1055', '生成查询', '116', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:query',             '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1056', '生成修改', '116', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:edit',              '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1057', '生成删除', '116', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:remove',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1058', '导入代码', '116', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:import',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1059', '预览代码', '116', '5', '#', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:preview',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1060', '生成代码', '116', '6', '#', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:code',              '#', 'admin', sysdate(), '', null, '');

-- ----------------------------
-- 初始化-Salesforce DevOps 业务菜单数据
-- ----------------------------
-- 一级目录
insert into sys_menu values('2000', 'Salesforce', '0', '2', 'salesforce', null, '', '', 1, 0, 'M', '0', '0', '', 'server', 'admin', sysdate(), '', null, 'Salesforce DevOps 业务目录');
-- 二级菜单
insert into sys_menu values('2001', '环境管理', '2000', '1', 'org',          'salesforce/org/index',             '', '', 1, 0, 'C', '0', '0', 'salesforce:org:list',         'server',     'admin', sysdate(), '', null, 'Salesforce环境(Org)管理菜单');
insert into sys_menu values('2002', '部署管理', '2000', '2', 'deployment',   'salesforce/deployment/index',      '', '', 1, 0, 'C', '0', '0', 'salesforce:deployment:list',  'code',       'admin', sysdate(), '', null, '元数据部署包管理菜单');
insert into sys_menu values('2003', '数据比对', '2000', '3', 'reconcile',    'salesforce/reconcile/index',       '', '', 1, 0, 'C', '0', '0', 'salesforce:reconcile:list',   'tree-table', 'admin', sysdate(), '', null, '迁移后数据质量比对菜单');
insert into sys_menu values('2004', '部署审计', '2000', '4', 'audit',        'salesforce/audit/index',           '', '', 1, 0, 'C', '0', '0', 'salesforce:audit:list',       'log',        'admin', sysdate(), '', null, '部署历史与备份审计菜单');
insert into sys_menu values('2005', 'Git配置',  '2000', '5', 'gitConfig',    'salesforce/gitConfig/index',       '', '', 1, 0, 'C', '0', '0', 'salesforce:git:list',         'github',     'admin', sysdate(), '', null, 'GitOps 仓库凭证配置菜单');
-- 隐藏路由（由列表页跳转进入，不在侧边栏显示）
insert into sys_menu values('2006', '部署详情', '2000', '6', 'deploymentDetail', 'salesforce/deployment/detail', '', '', 1, 0, 'C', '1', '0', 'salesforce:deployment:query', 'edit',    'admin', sysdate(), '', null, '部署包详情页(隐藏路由)');
insert into sys_menu values('2007', '比对监控', '2000', '7', 'jobMonitor',       'salesforce/reconcile/monitor', '', '', 1, 0, 'C', '1', '0', 'salesforce:reconcile:query',  'monitor', 'admin', sysdate(), '', null, '数据比对任务实时监控页(隐藏路由)');

-- 环境管理按钮
insert into sys_menu values('2010', '环境查询', '2001', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:org:query',        '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2011', '环境新增', '2001', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:org:add',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2012', '环境修改', '2001', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:org:edit',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2013', '环境删除', '2001', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:org:remove',       '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2014', '连通测试', '2001', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:org:test',         '#', 'admin', sysdate(), '', null, '测试 Org 授权连通性');
-- 部署管理按钮
insert into sys_menu values('2020', '部署查询', '2002', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:deployment:query',  '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2021', '部署新增', '2002', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:deployment:add',    '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2022', '部署修改', '2002', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:deployment:edit',   '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2023', '部署删除', '2002', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:deployment:remove', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2024', '校验部署', '2002', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:deployment:validate', '#', 'admin', sysdate(), '', null, '仅校验不落库(CheckOnly)');
insert into sys_menu values('2025', '执行部署', '2002', '6', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:deployment:deploy', '#', 'admin', sysdate(), '', null, '正式执行部署');
insert into sys_menu values('2026', '回滚部署', '2002', '7', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:deployment:rollback', '#', 'admin', sysdate(), '', null, '基于历史备份包回滚');
-- 数据比对按钮
insert into sys_menu values('2030', '任务查询', '2003', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:reconcile:query',  '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2031', '任务新增', '2003', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:reconcile:add',    '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2032', '任务修改', '2003', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:reconcile:edit',   '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2033', '任务删除', '2003', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:reconcile:remove', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2034', '执行比对', '2003', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:reconcile:run',    '#', 'admin', sysdate(), '', null, '触发数据比对任务');
insert into sys_menu values('2035', '结果下载', '2003', '6', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:reconcile:export', '#', 'admin', sysdate(), '', null, '下载比对差异明细');
-- 部署审计按钮
insert into sys_menu values('2040', '审计查询', '2004', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:audit:query',   '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2041', '记录删除', '2004', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:audit:remove',  '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2042', '备份包下载', '2004', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:audit:download', '#', 'admin', sysdate(), '', null, '下载部署前备份包');
-- Git配置按钮
insert into sys_menu values('2050', '配置查询', '2005', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:git:query',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2051', '配置保存', '2005', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:git:edit',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2052', '配置删除', '2005', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:git:remove',        '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2053', '连通测试', '2005', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:git:test',          '#', 'admin', sysdate(), '', null, '测试 Git 仓库连通性');
insert into sys_menu values('2054', '拉取分支', '2005', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'salesforce:git:branches',      '#', 'admin', sysdate(), '', null, '获取远程分支列表');


-- ----------------------------
-- 6、用户和角色关联表  用户N-1角色
-- ----------------------------
drop table if exists sys_user_role;
create table sys_user_role (
  user_id   bigint(20) not null comment '用户ID',
  role_id   bigint(20) not null comment '角色ID',
  primary key(user_id, role_id)
) engine=innodb comment = '用户和角色关联表';

-- ----------------------------
-- 初始化-用户和角色关联表数据
-- ----------------------------
insert into sys_user_role values ('1', '1');
insert into sys_user_role values ('2', '2');


-- ----------------------------
-- 7、角色和菜单关联表  角色1-N菜单
-- ----------------------------
drop table if exists sys_role_menu;
create table sys_role_menu (
  role_id   bigint(20) not null comment '角色ID',
  menu_id   bigint(20) not null comment '菜单ID',
  primary key(role_id, menu_id)
) engine=innodb comment = '角色和菜单关联表';

-- ----------------------------
-- 初始化-角色和菜单关联表数据
-- ----------------------------
insert into sys_role_menu values ('2', '1');
insert into sys_role_menu values ('2', '2');
insert into sys_role_menu values ('2', '3');
insert into sys_role_menu values ('2', '4');
insert into sys_role_menu values ('2', '100');
insert into sys_role_menu values ('2', '101');
insert into sys_role_menu values ('2', '102');
insert into sys_role_menu values ('2', '103');
insert into sys_role_menu values ('2', '104');
insert into sys_role_menu values ('2', '105');
insert into sys_role_menu values ('2', '106');
insert into sys_role_menu values ('2', '107');
insert into sys_role_menu values ('2', '108');
insert into sys_role_menu values ('2', '109');
insert into sys_role_menu values ('2', '110');
insert into sys_role_menu values ('2', '111');
insert into sys_role_menu values ('2', '112');
insert into sys_role_menu values ('2', '113');
insert into sys_role_menu values ('2', '114');
insert into sys_role_menu values ('2', '115');
insert into sys_role_menu values ('2', '116');
insert into sys_role_menu values ('2', '117');
insert into sys_role_menu values ('2', '500');
insert into sys_role_menu values ('2', '501');
insert into sys_role_menu values ('2', '1000');
insert into sys_role_menu values ('2', '1001');
insert into sys_role_menu values ('2', '1002');
insert into sys_role_menu values ('2', '1003');
insert into sys_role_menu values ('2', '1004');
insert into sys_role_menu values ('2', '1005');
insert into sys_role_menu values ('2', '1006');
insert into sys_role_menu values ('2', '1007');
insert into sys_role_menu values ('2', '1008');
insert into sys_role_menu values ('2', '1009');
insert into sys_role_menu values ('2', '1010');
insert into sys_role_menu values ('2', '1011');
insert into sys_role_menu values ('2', '1012');
insert into sys_role_menu values ('2', '1013');
insert into sys_role_menu values ('2', '1014');
insert into sys_role_menu values ('2', '1015');
insert into sys_role_menu values ('2', '1016');
insert into sys_role_menu values ('2', '1017');
insert into sys_role_menu values ('2', '1018');
insert into sys_role_menu values ('2', '1019');
insert into sys_role_menu values ('2', '1020');
insert into sys_role_menu values ('2', '1021');
insert into sys_role_menu values ('2', '1022');
insert into sys_role_menu values ('2', '1023');
insert into sys_role_menu values ('2', '1024');
insert into sys_role_menu values ('2', '1025');
insert into sys_role_menu values ('2', '1026');
insert into sys_role_menu values ('2', '1027');
insert into sys_role_menu values ('2', '1028');
insert into sys_role_menu values ('2', '1029');
insert into sys_role_menu values ('2', '1030');
insert into sys_role_menu values ('2', '1031');
insert into sys_role_menu values ('2', '1032');
insert into sys_role_menu values ('2', '1033');
insert into sys_role_menu values ('2', '1034');
insert into sys_role_menu values ('2', '1035');
insert into sys_role_menu values ('2', '1036');
insert into sys_role_menu values ('2', '1037');
insert into sys_role_menu values ('2', '1038');
insert into sys_role_menu values ('2', '1039');
insert into sys_role_menu values ('2', '1040');
insert into sys_role_menu values ('2', '1041');
insert into sys_role_menu values ('2', '1042');
insert into sys_role_menu values ('2', '1043');
insert into sys_role_menu values ('2', '1044');
insert into sys_role_menu values ('2', '1045');
insert into sys_role_menu values ('2', '1046');
insert into sys_role_menu values ('2', '1047');
insert into sys_role_menu values ('2', '1048');
insert into sys_role_menu values ('2', '1049');
insert into sys_role_menu values ('2', '1050');
insert into sys_role_menu values ('2', '1051');
insert into sys_role_menu values ('2', '1052');
insert into sys_role_menu values ('2', '1053');
insert into sys_role_menu values ('2', '1054');
insert into sys_role_menu values ('2', '1055');
insert into sys_role_menu values ('2', '1056');
insert into sys_role_menu values ('2', '1057');
insert into sys_role_menu values ('2', '1058');
insert into sys_role_menu values ('2', '1059');
insert into sys_role_menu values ('2', '1060');

-- Salesforce DevOps 菜单授权（角色2-普通角色，角色3-Salesforce运维）
insert into sys_role_menu values ('2', '2000');
insert into sys_role_menu values ('2', '2001');
insert into sys_role_menu values ('2', '2002');
insert into sys_role_menu values ('2', '2003');
insert into sys_role_menu values ('2', '2004');
insert into sys_role_menu values ('2', '2005');
insert into sys_role_menu values ('2', '2006');
insert into sys_role_menu values ('2', '2007');
insert into sys_role_menu values ('2', '2010');
insert into sys_role_menu values ('2', '2011');
insert into sys_role_menu values ('2', '2012');
insert into sys_role_menu values ('2', '2013');
insert into sys_role_menu values ('2', '2014');
insert into sys_role_menu values ('2', '2020');
insert into sys_role_menu values ('2', '2021');
insert into sys_role_menu values ('2', '2022');
insert into sys_role_menu values ('2', '2023');
insert into sys_role_menu values ('2', '2024');
insert into sys_role_menu values ('2', '2025');
insert into sys_role_menu values ('2', '2026');
insert into sys_role_menu values ('2', '2030');
insert into sys_role_menu values ('2', '2031');
insert into sys_role_menu values ('2', '2032');
insert into sys_role_menu values ('2', '2033');
insert into sys_role_menu values ('2', '2034');
insert into sys_role_menu values ('2', '2035');
insert into sys_role_menu values ('2', '2040');
insert into sys_role_menu values ('2', '2041');
insert into sys_role_menu values ('2', '2042');
insert into sys_role_menu values ('2', '2050');
insert into sys_role_menu values ('2', '2051');
insert into sys_role_menu values ('2', '2052');
insert into sys_role_menu values ('2', '2053');
insert into sys_role_menu values ('2', '2054');
insert into sys_role_menu values ('3', '2000');
insert into sys_role_menu values ('3', '2001');
insert into sys_role_menu values ('3', '2002');
insert into sys_role_menu values ('3', '2003');
insert into sys_role_menu values ('3', '2004');
insert into sys_role_menu values ('3', '2005');
insert into sys_role_menu values ('3', '2006');
insert into sys_role_menu values ('3', '2007');
insert into sys_role_menu values ('3', '2010');
insert into sys_role_menu values ('3', '2011');
insert into sys_role_menu values ('3', '2012');
insert into sys_role_menu values ('3', '2013');
insert into sys_role_menu values ('3', '2014');
insert into sys_role_menu values ('3', '2020');
insert into sys_role_menu values ('3', '2021');
insert into sys_role_menu values ('3', '2022');
insert into sys_role_menu values ('3', '2023');
insert into sys_role_menu values ('3', '2024');
insert into sys_role_menu values ('3', '2025');
insert into sys_role_menu values ('3', '2026');
insert into sys_role_menu values ('3', '2030');
insert into sys_role_menu values ('3', '2031');
insert into sys_role_menu values ('3', '2032');
insert into sys_role_menu values ('3', '2033');
insert into sys_role_menu values ('3', '2034');
insert into sys_role_menu values ('3', '2035');
insert into sys_role_menu values ('3', '2040');
insert into sys_role_menu values ('3', '2041');
insert into sys_role_menu values ('3', '2042');
insert into sys_role_menu values ('3', '2050');
insert into sys_role_menu values ('3', '2051');
insert into sys_role_menu values ('3', '2052');
insert into sys_role_menu values ('3', '2053');
insert into sys_role_menu values ('3', '2054');

-- ----------------------------
-- 8、角色和部门关联表  角色1-N部门
-- ----------------------------
drop table if exists sys_role_dept;
create table sys_role_dept (
  role_id   bigint(20) not null comment '角色ID',
  dept_id   bigint(20) not null comment '部门ID',
  primary key(role_id, dept_id)
) engine=innodb comment = '角色和部门关联表';

-- ----------------------------
-- 初始化-角色和部门关联表数据
-- ----------------------------
insert into sys_role_dept values ('2', '100');
insert into sys_role_dept values ('2', '101');
insert into sys_role_dept values ('2', '105');


-- ----------------------------
-- 9、用户与岗位关联表  用户1-N岗位
-- ----------------------------
drop table if exists sys_user_post;
create table sys_user_post
(
  user_id   bigint(20) not null comment '用户ID',
  post_id   bigint(20) not null comment '岗位ID',
  primary key (user_id, post_id)
) engine=innodb comment = '用户与岗位关联表';

-- ----------------------------
-- 初始化-用户与岗位关联表数据
-- ----------------------------
insert into sys_user_post values ('1', '1');
insert into sys_user_post values ('2', '2');


-- ----------------------------
-- 10、操作日志记录
-- ----------------------------
drop table if exists sys_oper_log;
create table sys_oper_log (
  oper_id           bigint(20)      not null auto_increment    comment '日志主键',
  title             varchar(50)     default ''                 comment '模块标题',
  business_type     int(2)          default 0                  comment '业务类型（0其它 1新增 2修改 3删除）',
  method            varchar(200)    default ''                 comment '方法名称',
  request_method    varchar(10)     default ''                 comment '请求方式',
  operator_type     int(1)          default 0                  comment '操作类别（0其它 1后台用户 2手机端用户）',
  oper_name         varchar(50)     default ''                 comment '操作人员',
  dept_name         varchar(50)     default ''                 comment '部门名称',
  oper_url          varchar(255)    default ''                 comment '请求URL',
  oper_ip           varchar(128)    default ''                 comment '主机地址',
  oper_location     varchar(255)    default ''                 comment '操作地点',
  oper_param        varchar(2000)   default ''                 comment '请求参数',
  json_result       varchar(2000)   default ''                 comment '返回参数',
  status            int(1)          default 0                  comment '操作状态（0正常 1异常）',
  error_msg         varchar(2000)   default ''                 comment '错误消息',
  oper_time         datetime                                   comment '操作时间',
  cost_time         bigint(20)      default 0                  comment '消耗时间',
  primary key (oper_id),
  key idx_sys_oper_log_bt (business_type),
  key idx_sys_oper_log_s  (status),
  key idx_sys_oper_log_ot (oper_time)
) engine=innodb auto_increment=100 comment = '操作日志记录';


-- ----------------------------
-- 11、字典类型表
-- ----------------------------
drop table if exists sys_dict_type;
create table sys_dict_type
(
  dict_id          bigint(20)      not null auto_increment    comment '字典主键',
  dict_name        varchar(100)    default ''                 comment '字典名称',
  dict_type        varchar(100)    default ''                 comment '字典类型',
  status           char(1)         default '0'                comment '状态（0正常 1停用）',
  create_by        varchar(64)     default ''                 comment '创建者',
  create_time      datetime                                   comment '创建时间',
  update_by        varchar(64)     default ''                 comment '更新者',
  update_time      datetime                                   comment '更新时间',
  remark           varchar(500)    default null               comment '备注',
  primary key (dict_id),
  unique (dict_type)
) engine=innodb auto_increment=100 comment = '字典类型表';

insert into sys_dict_type values(1,  '用户性别', 'sys_user_sex',        '0', 'admin', sysdate(), '', null, '用户性别列表');
insert into sys_dict_type values(2,  '菜单状态', 'sys_show_hide',       '0', 'admin', sysdate(), '', null, '菜单状态列表');
insert into sys_dict_type values(3,  '系统开关', 'sys_normal_disable',  '0', 'admin', sysdate(), '', null, '系统开关列表');
insert into sys_dict_type values(4,  '任务状态', 'sys_job_status',      '0', 'admin', sysdate(), '', null, '任务状态列表');
insert into sys_dict_type values(5,  '任务分组', 'sys_job_group',       '0', 'admin', sysdate(), '', null, '任务分组列表');
insert into sys_dict_type values(6,  '系统是否', 'sys_yes_no',          '0', 'admin', sysdate(), '', null, '系统是否列表');
insert into sys_dict_type values(7,  '通知类型', 'sys_notice_type',     '0', 'admin', sysdate(), '', null, '通知类型列表');
insert into sys_dict_type values(8,  '通知状态', 'sys_notice_status',   '0', 'admin', sysdate(), '', null, '通知状态列表');
insert into sys_dict_type values(9,  '操作类型', 'sys_oper_type',       '0', 'admin', sysdate(), '', null, '操作类型列表');
insert into sys_dict_type values(10, '系统状态', 'sys_common_status',   '0', 'admin', sysdate(), '', null, '登录状态列表');
-- Salesforce DevOps 业务字典
insert into sys_dict_type values(11, 'Salesforce元数据类型', 'sys_salesforce_metadata_type',  '0', 'admin', sysdate(), '', null, 'Salesforce元数据类型列表');
insert into sys_dict_type values(12, 'Salesforce部署状态',   'sys_salesforce_deploy_status',  '0', 'admin', sysdate(), '', null, 'Salesforce部署包状态列表');
insert into sys_dict_type values(13, '部署包类型',           'sf_deploy_type',                '0', 'admin', sysdate(), '', null, '部署包业务类型列表');
insert into sys_dict_type values(14, '需求责任人',           'sf_demand_personnel',           '0', 'admin', sysdate(), '', null, '需求责任人列表（示例数据，请替换为实际人员）');


-- ----------------------------
-- 12、字典数据表
-- ----------------------------
drop table if exists sys_dict_data;
create table sys_dict_data
(
  dict_code        bigint(20)      not null auto_increment    comment '字典编码',
  dict_sort        int(4)          default 0                  comment '字典排序',
  dict_label       varchar(100)    default ''                 comment '字典标签',
  dict_value       varchar(100)    default ''                 comment '字典键值',
  dict_type        varchar(100)    default ''                 comment '字典类型',
  css_class        varchar(100)    default null               comment '样式属性（其他样式扩展）',
  list_class       varchar(100)    default null               comment '表格回显样式',
  is_default       char(1)         default 'N'                comment '是否默认（Y是 N否）',
  status           char(1)         default '0'                comment '状态（0正常 1停用）',
  create_by        varchar(64)     default ''                 comment '创建者',
  create_time      datetime                                   comment '创建时间',
  update_by        varchar(64)     default ''                 comment '更新者',
  update_time      datetime                                   comment '更新时间',
  remark           varchar(500)    default null               comment '备注',
  primary key (dict_code)
) engine=innodb auto_increment=100 comment = '字典数据表';

insert into sys_dict_data values(1,  1,  '男',       '0',       'sys_user_sex',        '',   '',        'Y', '0', 'admin', sysdate(), '', null, '性别男');
insert into sys_dict_data values(2,  2,  '女',       '1',       'sys_user_sex',        '',   '',        'N', '0', 'admin', sysdate(), '', null, '性别女');
insert into sys_dict_data values(3,  3,  '未知',     '2',       'sys_user_sex',        '',   '',        'N', '0', 'admin', sysdate(), '', null, '性别未知');
insert into sys_dict_data values(4,  1,  '显示',     '0',       'sys_show_hide',       '',   'primary', 'Y', '0', 'admin', sysdate(), '', null, '显示菜单');
insert into sys_dict_data values(5,  2,  '隐藏',     '1',       'sys_show_hide',       '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '隐藏菜单');
insert into sys_dict_data values(6,  1,  '正常',     '0',       'sys_normal_disable',  '',   'primary', 'Y', '0', 'admin', sysdate(), '', null, '正常状态');
insert into sys_dict_data values(7,  2,  '停用',     '1',       'sys_normal_disable',  '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '停用状态');
insert into sys_dict_data values(8,  1,  '正常',     '0',       'sys_job_status',      '',   'primary', 'Y', '0', 'admin', sysdate(), '', null, '正常状态');
insert into sys_dict_data values(9,  2,  '暂停',     '1',       'sys_job_status',      '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '停用状态');
insert into sys_dict_data values(10, 1,  '默认',     'DEFAULT', 'sys_job_group',       '',   '',        'Y', '0', 'admin', sysdate(), '', null, '默认分组');
insert into sys_dict_data values(11, 2,  '系统',     'SYSTEM',  'sys_job_group',       '',   '',        'N', '0', 'admin', sysdate(), '', null, '系统分组');
insert into sys_dict_data values(12, 1,  '是',       'Y',       'sys_yes_no',          '',   'primary', 'Y', '0', 'admin', sysdate(), '', null, '系统默认是');
insert into sys_dict_data values(13, 2,  '否',       'N',       'sys_yes_no',          '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '系统默认否');
insert into sys_dict_data values(14, 1,  '通知',     '1',       'sys_notice_type',     '',   'warning', 'Y', '0', 'admin', sysdate(), '', null, '通知');
insert into sys_dict_data values(15, 2,  '公告',     '2',       'sys_notice_type',     '',   'success', 'N', '0', 'admin', sysdate(), '', null, '公告');
insert into sys_dict_data values(16, 1,  '正常',     '0',       'sys_notice_status',   '',   'primary', 'Y', '0', 'admin', sysdate(), '', null, '正常状态');
insert into sys_dict_data values(17, 2,  '关闭',     '1',       'sys_notice_status',   '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '关闭状态');
insert into sys_dict_data values(18, 99, '其他',     '0',       'sys_oper_type',       '',   'info',    'N', '0', 'admin', sysdate(), '', null, '其他操作');
insert into sys_dict_data values(19, 1,  '新增',     '1',       'sys_oper_type',       '',   'info',    'N', '0', 'admin', sysdate(), '', null, '新增操作');
insert into sys_dict_data values(20, 2,  '修改',     '2',       'sys_oper_type',       '',   'info',    'N', '0', 'admin', sysdate(), '', null, '修改操作');
insert into sys_dict_data values(21, 3,  '删除',     '3',       'sys_oper_type',       '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '删除操作');
insert into sys_dict_data values(22, 4,  '授权',     '4',       'sys_oper_type',       '',   'primary', 'N', '0', 'admin', sysdate(), '', null, '授权操作');
insert into sys_dict_data values(23, 5,  '导出',     '5',       'sys_oper_type',       '',   'warning', 'N', '0', 'admin', sysdate(), '', null, '导出操作');
insert into sys_dict_data values(24, 6,  '导入',     '6',       'sys_oper_type',       '',   'warning', 'N', '0', 'admin', sysdate(), '', null, '导入操作');
insert into sys_dict_data values(25, 7,  '强退',     '7',       'sys_oper_type',       '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '强退操作');
insert into sys_dict_data values(26, 8,  '生成代码', '8',       'sys_oper_type',       '',   'warning', 'N', '0', 'admin', sysdate(), '', null, '生成操作');
insert into sys_dict_data values(27, 9,  '清空数据', '9',       'sys_oper_type',       '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '清空操作');
insert into sys_dict_data values(28, 1,  '成功',     '0',       'sys_common_status',   '',   'primary', 'N', '0', 'admin', sysdate(), '', null, '正常状态');
insert into sys_dict_data values(29, 2,  '失败',     '1',       'sys_common_status',   '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '停用状态');

-- ----------------------------
-- Salesforce DevOps 业务字典数据
-- ----------------------------
-- 元数据类型 (sys_salesforce_metadata_type)
insert into sys_dict_data values(100, 1,  'Apex类',            'ApexClass',                'sys_salesforce_metadata_type', '', 'primary', 'Y', '0', 'admin', sysdate(), '', null, 'Apex 类');
insert into sys_dict_data values(101, 2,  'Apex触发器',        'ApexTrigger',              'sys_salesforce_metadata_type', '', 'primary', 'N', '0', 'admin', sysdate(), '', null, 'Apex 触发器');
insert into sys_dict_data values(102, 3,  'Visualforce页面',   'ApexPage',                 'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, 'Visualforce 页面');
insert into sys_dict_data values(103, 4,  'Visualforce组件',   'ApexComponent',            'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, 'Visualforce 组件');
insert into sys_dict_data values(104, 5,  'LWC组件',           'LightningComponentBundle', 'sys_salesforce_metadata_type', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, 'Lightning Web Component 组件包');
insert into sys_dict_data values(105, 6,  'Aura组件',          'AuraDefinitionBundle',     'sys_salesforce_metadata_type', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, 'Aura 组件包');
insert into sys_dict_data values(106, 7,  '自定义对象',        'CustomObject',             'sys_salesforce_metadata_type', '', 'success', 'N', '0', 'admin', sysdate(), '', null, '自定义对象及字段');
insert into sys_dict_data values(107, 8,  '自定义字段',        'CustomField',              'sys_salesforce_metadata_type', '', 'success', 'N', '0', 'admin', sysdate(), '', null, '自定义字段');
insert into sys_dict_data values(108, 9,  '自定义元数据',      'CustomMetadata',           'sys_salesforce_metadata_type', '', 'success', 'N', '0', 'admin', sysdate(), '', null, 'Custom Metadata Type 记录');
insert into sys_dict_data values(109, 10, 'Flow流程',          'Flow',                     'sys_salesforce_metadata_type', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, 'Flow 流程定义');
insert into sys_dict_data values(110, 11, '页面布局',          'Layout',                   'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, 'Page Layout 页面布局');
insert into sys_dict_data values(111, 12, '动态表单',          'FlexiPage',                'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, 'Lightning 动态表单');
insert into sys_dict_data values(112, 13, '权限集',            'PermissionSet',            'sys_salesforce_metadata_type', '', 'danger',  'N', '0', 'admin', sysdate(), '', null, '权限集');
insert into sys_dict_data values(113, 14, '简档',              'Profile',                  'sys_salesforce_metadata_type', '', 'danger',  'N', '0', 'admin', sysdate(), '', null, 'Profile 简档');
insert into sys_dict_data values(114, 15, '校验规则',          'ValidationRule',           'sys_salesforce_metadata_type', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, '对象校验规则');
insert into sys_dict_data values(115, 16, '工作流规则',        'WorkflowRule',             'sys_salesforce_metadata_type', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, '工作流规则');
insert into sys_dict_data values(116, 17, '静态资源',          'StaticResource',           'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '静态资源');
insert into sys_dict_data values(117, 18, '自定义选项卡',      'CustomTab',                'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '自定义选项卡');
insert into sys_dict_data values(118, 19, '自定义应用程序',    'CustomApplication',        'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '自定义应用程序');
insert into sys_dict_data values(119, 20, '邮件模板',          'EmailTemplate',            'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '邮件模板');
insert into sys_dict_data values(120, 21, '报表',              'Report',                   'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '报表');
insert into sys_dict_data values(121, 22, '仪表板',            'Dashboard',                'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '仪表板');
insert into sys_dict_data values(122, 23, '快速操作',          'QuickAction',              'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '快速操作');
insert into sys_dict_data values(123, 24, '自定义标签',        'CustomLabels',             'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '自定义标签');
insert into sys_dict_data values(124, 25, '翻译',              'Translations',             'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '翻译工作台');
insert into sys_dict_data values(125, 26, '远程站点设置',      'RemoteSiteSetting',        'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '远程站点设置');
insert into sys_dict_data values(126, 27, '命名凭证',          'NamedCredential',          'sys_salesforce_metadata_type', '', 'danger',  'N', '0', 'admin', sysdate(), '', null, '命名凭证');
insert into sys_dict_data values(127, 28, '自定义权限',        'CustomPermission',         'sys_salesforce_metadata_type', '', 'danger',  'N', '0', 'admin', sysdate(), '', null, '自定义权限');
insert into sys_dict_data values(128, 29, '全局值集',          'GlobalValueSet',           'sys_salesforce_metadata_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '全局选项列表值集');

-- 部署状态 (sys_salesforce_deploy_status)
insert into sys_dict_data values(140, 1, '草稿',     'Draft',      'sys_salesforce_deploy_status', '', 'info',    'Y', '0', 'admin', sysdate(), '', null, '部署包已创建，尚未校验');
insert into sys_dict_data values(141, 2, '校验中',   'Validating', 'sys_salesforce_deploy_status', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, '正在执行 CheckOnly 校验');
insert into sys_dict_data values(142, 3, '校验通过', 'Validated',  'sys_salesforce_deploy_status', '', 'success', 'N', '0', 'admin', sysdate(), '', null, '校验通过，可执行部署');
insert into sys_dict_data values(143, 4, '排队中',   'Queued',     'sys_salesforce_deploy_status', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, '已提交 Salesforce 异步队列');
insert into sys_dict_data values(144, 5, '部署中',   'Deploying',  'sys_salesforce_deploy_status', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, '正在向目标环境部署');
insert into sys_dict_data values(145, 6, '处理中',   'Processing', 'sys_salesforce_deploy_status', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, 'Salesforce 服务端处理中');
insert into sys_dict_data values(146, 7, '部署成功', 'Succeeded',  'sys_salesforce_deploy_status', '', 'success', 'N', '0', 'admin', sysdate(), '', null, '部署执行成功');
insert into sys_dict_data values(147, 8, '部署失败', 'Failed',     'sys_salesforce_deploy_status', '', 'danger',  'N', '0', 'admin', sysdate(), '', null, '部署执行失败，详见错误信息');
insert into sys_dict_data values(148, 9, '已取消',   'Cancelled',  'sys_salesforce_deploy_status', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '部署被取消');
insert into sys_dict_data values(149, 10,'无变更',   'NoChange',   'sys_salesforce_deploy_status', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '目标环境已是最新，无需部署');

-- 部署包类型 (sf_deploy_type)
insert into sys_dict_data values(160, 1, '需求迭代', 'Feature',  'sf_deploy_type', '', 'primary', 'Y', '0', 'admin', sysdate(), '', null, '常规需求迭代发布');
insert into sys_dict_data values(161, 2, '缺陷修复', 'Bugfix',   'sf_deploy_type', '', 'warning', 'N', '0', 'admin', sysdate(), '', null, '缺陷修复发布');
insert into sys_dict_data values(162, 3, '紧急修复', 'Hotfix',   'sf_deploy_type', '', 'danger',  'N', '0', 'admin', sysdate(), '', null, '生产环境紧急修复');
insert into sys_dict_data values(163, 4, '配置变更', 'Config',   'sf_deploy_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '系统配置类变更');
insert into sys_dict_data values(164, 5, '数据修复', 'DataFix',  'sf_deploy_type', '', 'info',    'N', '0', 'admin', sysdate(), '', null, '数据订正类变更');
insert into sys_dict_data values(165, 6, '初始化',   'Init',     'sf_deploy_type', '', 'success', 'N', '0', 'admin', sysdate(), '', null, '环境初始化元数据');

-- 需求责任人 (sf_demand_personnel)  —— 示例化名，请替换为实际人员
insert into sys_dict_data values(180, 1, '张伟', 'zhangwei', 'sf_demand_personnel', '', 'default', 'Y', '0', 'admin', sysdate(), '', null, '示例责任人');
insert into sys_dict_data values(181, 2, '李娜', 'lina',     'sf_demand_personnel', '', 'default', 'N', '0', 'admin', sysdate(), '', null, '示例责任人');
insert into sys_dict_data values(182, 3, '王强', 'wangqiang','sf_demand_personnel', '', 'default', 'N', '0', 'admin', sysdate(), '', null, '示例责任人');
insert into sys_dict_data values(183, 4, '刘洋', 'liuyang',  'sf_demand_personnel', '', 'default', 'N', '0', 'admin', sysdate(), '', null, '示例责任人');
insert into sys_dict_data values(184, 5, '陈静', 'chenjing', 'sf_demand_personnel', '', 'default', 'N', '0', 'admin', sysdate(), '', null, '示例责任人');
insert into sys_dict_data values(185, 6, '赵磊', 'zhaolei',  'sf_demand_personnel', '', 'default', 'N', '0', 'admin', sysdate(), '', null, '示例责任人');
insert into sys_dict_data values(186, 7, '孙丽', 'sunli',    'sf_demand_personnel', '', 'default', 'N', '0', 'admin', sysdate(), '', null, '示例责任人');
insert into sys_dict_data values(187, 8, '周敏', 'zhoumin',  'sf_demand_personnel', '', 'default', 'N', '0', 'admin', sysdate(), '', null, '示例责任人');


-- ----------------------------
-- 13、参数配置表
-- ----------------------------
drop table if exists sys_config;
create table sys_config (
  config_id         int(5)          not null auto_increment    comment '参数主键',
  config_name       varchar(100)    default ''                 comment '参数名称',
  config_key        varchar(100)    default ''                 comment '参数键名',
  config_value      varchar(500)    default ''                 comment '参数键值',
  config_type       char(1)         default 'N'                comment '系统内置（Y是 N否）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (config_id)
) engine=innodb auto_increment=100 comment = '参数配置表';

insert into sys_config values(1, '主框架页-默认皮肤样式名称',     'sys.index.skinName',               'skin-blue',     'Y', 'admin', sysdate(), '', null, '蓝色 skin-blue、绿色 skin-green、紫色 skin-purple、红色 skin-red、黄色 skin-yellow' );
insert into sys_config values(2, '用户管理-账号初始密码',         'sys.user.initPassword',            '123456',        'Y', 'admin', sysdate(), '', null, '初始化密码 123456' );
insert into sys_config values(3, '主框架页-侧边栏主题',           'sys.index.sideTheme',              'theme-dark',    'Y', 'admin', sysdate(), '', null, '深色主题theme-dark，浅色主题theme-light' );
insert into sys_config values(4, '账号自助-验证码开关',           'sys.account.captchaEnabled',       'true',          'Y', 'admin', sysdate(), '', null, '是否开启验证码功能（true开启，false关闭）');
insert into sys_config values(5, '账号自助-是否开启用户注册功能', 'sys.account.registerUser',         'false',         'Y', 'admin', sysdate(), '', null, '是否开启注册用户功能（true开启，false关闭）');
insert into sys_config values(6, '用户登录-黑名单列表',           'sys.login.blackIPList',            '',              'Y', 'admin', sysdate(), '', null, '设置登录IP黑名单限制，多个匹配项以;分隔，支持匹配（*通配、网段）');
insert into sys_config values(7, '用户管理-初始密码修改策略',     'sys.account.initPasswordModify',   '1',             'Y', 'admin', sysdate(), '', null, '0：初始密码修改策略关闭，没有任何提示，1：提醒用户，如果未修改初始密码，则在登录时就会提醒修改密码对话框');
insert into sys_config values(8, '用户管理-账号密码更新周期',     'sys.account.passwordValidateDays', '0',             'Y', 'admin', sysdate(), '', null, '密码更新周期（填写数字，数据初始化值为0不限制，若修改必须为大于0小于365的正整数），如果超过这个周期登录系统时，则在登录时就会提醒修改密码对话框');


-- ----------------------------
-- 14、系统访问记录
-- ----------------------------
drop table if exists sys_logininfor;
create table sys_logininfor (
  info_id        bigint(20)     not null auto_increment   comment '访问ID',
  user_name      varchar(50)    default ''                comment '用户账号',
  ipaddr         varchar(128)   default ''                comment '登录IP地址',
  login_location varchar(255)   default ''                comment '登录地点',
  browser        varchar(50)    default ''                comment '浏览器类型',
  os             varchar(50)    default ''                comment '操作系统',
  status         char(1)        default '0'               comment '登录状态（0成功 1失败）',
  msg            varchar(255)   default ''                comment '提示消息',
  login_time     datetime                                 comment '访问时间',
  primary key (info_id),
  key idx_sys_logininfor_s  (status),
  key idx_sys_logininfor_lt (login_time)
) engine=innodb auto_increment=100 comment = '系统访问记录';


-- ----------------------------
-- 15、定时任务调度表
-- ----------------------------
drop table if exists sys_job;
create table sys_job (
  job_id              bigint(20)    not null auto_increment    comment '任务ID',
  job_name            varchar(64)   default ''                 comment '任务名称',
  job_group           varchar(64)   default 'DEFAULT'          comment '任务组名',
  invoke_target       varchar(500)  not null                   comment '调用目标字符串',
  cron_expression     varchar(255)  default ''                 comment 'cron执行表达式',
  misfire_policy      varchar(20)   default '3'                comment '计划执行错误策略（1立即执行 2执行一次 3放弃执行）',
  concurrent          char(1)       default '1'                comment '是否并发执行（0允许 1禁止）',
  status              char(1)       default '0'                comment '状态（0正常 1暂停）',
  create_by           varchar(64)   default ''                 comment '创建者',
  create_time         datetime                                 comment '创建时间',
  update_by           varchar(64)   default ''                 comment '更新者',
  update_time         datetime                                 comment '更新时间',
  remark              varchar(500)  default ''                 comment '备注信息',
  primary key (job_id, job_name, job_group)
) engine=innodb auto_increment=100 comment = '定时任务调度表';

insert into sys_job values(1, '系统默认（无参）', 'DEFAULT', 'ryTask.ryNoParams',        '0/10 * * * * ?', '3', '1', '1', 'admin', sysdate(), '', null, '');
insert into sys_job values(2, '系统默认（有参）', 'DEFAULT', 'ryTask.ryParams(\'ry\')',  '0/15 * * * * ?', '3', '1', '1', 'admin', sysdate(), '', null, '');
insert into sys_job values(3, '系统默认（多参）', 'DEFAULT', 'ryTask.ryMultipleParams(\'ry\', true, 2000L, 316.50D, 100)',  '0/20 * * * * ?', '3', '1', '1', 'admin', sysdate(), '', null, '');


-- ----------------------------
-- 16、定时任务调度日志表
-- ----------------------------
drop table if exists sys_job_log;
create table sys_job_log (
  job_log_id          bigint(20)     not null auto_increment    comment '任务日志ID',
  job_name            varchar(64)    not null                   comment '任务名称',
  job_group           varchar(64)    not null                   comment '任务组名',
  invoke_target       varchar(500)   not null                   comment '调用目标字符串',
  job_message         varchar(500)                              comment '日志信息',
  status              char(1)        default '0'                comment '执行状态（0正常 1失败）',
  exception_info      varchar(2000)  default ''                 comment '异常信息',
  create_time         datetime                                  comment '创建时间',
  primary key (job_log_id)
) engine=innodb comment = '定时任务调度日志表';


-- ----------------------------
-- 17、通知公告表
-- ----------------------------
drop table if exists sys_notice;
create table sys_notice (
  notice_id         int(4)          not null auto_increment    comment '公告ID',
  notice_title      varchar(50)     not null                   comment '公告标题',
  notice_type       char(1)         not null                   comment '公告类型（1通知 2公告）',
  notice_content    longblob        default null               comment '公告内容',
  status            char(1)         default '0'                comment '公告状态（0正常 1关闭）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(255)    default null               comment '备注',
  primary key (notice_id)
) engine=innodb auto_increment=10 comment = '通知公告表';

-- ----------------------------
-- 初始化-公告信息表数据
-- ----------------------------
insert into sys_notice values('1', '温馨提醒：2018-07-01 若依新版本发布啦', '2', '新版本内容', '0', 'admin', sysdate(), '', null, '管理员');
insert into sys_notice values('2', '维护通知：2018-07-01 若依系统凌晨维护', '1', '维护内容',   '0', 'admin', sysdate(), '', null, '管理员');


-- ----------------------------
-- 18、代码生成业务表
-- ----------------------------
drop table if exists gen_table;
create table gen_table (
  table_id          bigint(20)      not null auto_increment    comment '编号',
  table_name        varchar(200)    default ''                 comment '表名称',
  table_comment     varchar(500)    default ''                 comment '表描述',
  sub_table_name    varchar(64)     default null               comment '关联子表的表名',
  sub_table_fk_name varchar(64)     default null               comment '子表关联的外键名',
  class_name        varchar(100)    default ''                 comment '实体类名称',
  tpl_category      varchar(200)    default 'crud'             comment '使用的模板（crud单表操作 tree树表操作）',
  tpl_web_type      varchar(30)     default ''                 comment '前端模板类型（element-ui模版 element-plus模版）',
  package_name      varchar(100)                               comment '生成包路径',
  module_name       varchar(30)                                comment '生成模块名',
  business_name     varchar(30)                                comment '生成业务名',
  function_name     varchar(50)                                comment '生成功能名',
  function_author   varchar(50)                                comment '生成功能作者',
  gen_type          char(1)         default '0'                comment '生成代码方式（0zip压缩包 1自定义路径）',
  gen_path          varchar(200)    default '/'                comment '生成路径（不填默认项目路径）',
  options           varchar(1000)                              comment '其它生成选项',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time 	    datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (table_id)
) engine=innodb auto_increment=1 comment = '代码生成业务表';


-- ----------------------------
-- 19、代码生成业务表字段
-- ----------------------------
drop table if exists gen_table_column;
create table gen_table_column (
  column_id         bigint(20)      not null auto_increment    comment '编号',
  table_id          bigint(20)                                 comment '归属表编号',
  column_name       varchar(200)                               comment '列名称',
  column_comment    varchar(500)                               comment '列描述',
  column_type       varchar(100)                               comment '列类型',
  java_type         varchar(500)                               comment 'JAVA类型',
  java_field        varchar(200)                               comment 'JAVA字段名',
  is_pk             char(1)                                    comment '是否主键（1是）',
  is_increment      char(1)                                    comment '是否自增（1是）',
  is_required       char(1)                                    comment '是否必填（1是）',
  is_insert         char(1)                                    comment '是否为插入字段（1是）',
  is_edit           char(1)                                    comment '是否编辑字段（1是）',
  is_list           char(1)                                    comment '是否列表字段（1是）',
  is_query          char(1)                                    comment '是否查询字段（1是）',
  query_type        varchar(200)    default 'EQ'               comment '查询方式（等于、不等于、大于、小于、范围）',
  html_type         varchar(200)                               comment '显示类型（文本框、文本域、下拉框、复选框、单选框、日期控件）',
  dict_type         varchar(200)    default ''                 comment '字典类型',
  sort              int                                        comment '排序',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time 	    datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  primary key (column_id)
) engine=innodb auto_increment=1 comment = '代码生成业务表字段';



-- ==========================================================================================
--  Salesforce DevOps 业务表
-- ==========================================================================================
--  说明：所有 sf_ 前缀表均参与多租户隔离（MyBatis-Plus TenantLineInnerInterceptor 自动追加
--        tenant_id 条件）。新增数据时 tenant_id 由 TenantAutoFillInterceptor 自动填充。
--  约定：del_flag（0存在 2删除）；create_by/create_time/update_by/update_time 为审计字段。
-- ==========================================================================================

-- ----------------------------
-- 20、Salesforce 环境（Org）表
-- ----------------------------
drop table if exists sf_org;
create table sf_org (
  id              bigint          not null auto_increment    comment '主键',
  name            varchar(50)     default null               comment '环境别名(如: 开发环境)',
  org_type        varchar(20)     default 'Sandbox'          comment '环境类型(Production/Sandbox)',
  org_id          varchar(18)     default null               comment 'Salesforce Org ID (15/18位)',
  username        varchar(100)    default null               comment '登录用户名',
  instance_url    varchar(200)    default null               comment '实例地址(https://xxx.my.salesforce.com)',
  access_token    text                                       comment '短期访问令牌',
  refresh_token   varchar(500)    default null               comment '长期刷新令牌(关键，请加密存储)',
  client_id       varchar(200)    default null               comment 'Connected App Consumer Key',
  client_secret   varchar(200)    default null               comment 'Connected App Consumer Secret',
  custom_domain   varchar(100)    default null               comment 'My Domain 自定义域名',
  user_id         bigint          default null               comment '归属用户ID',
  dept_id         bigint          default null               comment '归属部门ID',
  tenant_id       varchar(20)     default '000000'           comment '租户编号',
  create_by       varchar(64)     default ''                 comment '创建者',
  create_time     datetime                                   comment '创建时间',
  update_by       varchar(64)     default ''                 comment '更新者',
  update_time     datetime                                   comment '更新时间',
  remark          varchar(500)    default null               comment '备注',
  primary key (id),
  key idx_sf_org_tenant (tenant_id)
) engine=innodb auto_increment=100 default charset=utf8mb4 collate=utf8mb4_general_ci comment='Salesforce环境管理表';

-- ----------------------------
-- 21、部署包主表
-- ----------------------------
drop table if exists sf_deployment;
create table sf_deployment (
  id                bigint          not null auto_increment    comment '主键',
  title             varchar(200)    default ''                 comment '部署包标题',
  source_org_id     bigint          default null               comment '源环境ID',
  target_org_id     bigint          default null               comment '目标环境ID',
  status            varchar(50)     default 'Draft'            comment '状态(Draft/Validating/Validated/Queued/Deploying/Processing/Succeeded/Failed/Cancelled/NoChange)',
  test_level        varchar(50)     default 'NoTestRun'        comment '测试级别(NoTestRun/RunLocalTests/RunSpecifiedTests)',
  specified_tests   text                                       comment '指定测试类(逗号分隔)',
  deploy_type       varchar(50)     default null               comment '部署包类型(字典 sf_deploy_type)',
  demand_no         varchar(100)    default null               comment '需求单号',
  demand_personnel  varchar(100)    default null               comment '需求责任人(字典 sf_demand_personnel)',
  description       varchar(500)    default ''                 comment '备注描述',
  sync_git          tinyint(1)      default 0                  comment '是否同步至Git(1是 0否)',
  auto_merge        tinyint(1)      default 0                  comment '部署成功后是否自动合并至主分支(1是 0否)',
  target_branch     varchar(100)    default null               comment 'GitOps 目标分支',
  user_id           bigint          default null               comment '创建用户ID',
  dept_id           bigint          default null               comment '创建部门ID',
  tenant_id         varchar(20)     default '000000'           comment '租户编号',
  del_flag          char(1)         default '0'                comment '删除标志(0存在 2删除)',
  last_async_id     varchar(50)     default null               comment 'Salesforce异步处理ID',
  error_msg         varchar(10000)  default null               comment '最后一次部署错误信息',
  version           bigint          default 0                  comment '乐观锁版本号',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  primary key (id),
  key idx_sf_deploy_tenant (tenant_id),
  key idx_sf_deploy_status (status)
) engine=innodb auto_increment=100 default charset=utf8mb4 collate=utf8mb4_general_ci comment='Salesforce部署包主表';

-- ----------------------------
-- 22、部署包明细表
-- ----------------------------
drop table if exists sf_deployment_item;
create table sf_deployment_item (
  id                    bigint          not null auto_increment    comment '主键',
  deployment_id         bigint          not null                   comment '部署主表ID',
  metadata_type         varchar(100)    default ''                 comment '元数据类型(ApexClass等)',
  member_name           varchar(200)    default ''                 comment '元数据名称',
  action                varchar(20)     default 'Add'              comment '动作(Add/Delete)',
  diff_status           varchar(20)     default ''                 comment '比对状态(New/Changed/Same/Invalid)',
  last_check_time       datetime                                   comment '最后比对时间',
  last_modified_date    datetime                                   comment '源环境最后修改时间',
  last_modified_by_name varchar(100)    default null               comment '源环境最后修改人',
  tenant_id             varchar(20)     default '000000'           comment '租户编号',
  create_time           datetime                                   comment '创建时间',
  primary key (id),
  key idx_sf_item_deploy (deployment_id),
  key idx_sf_item_tenant (tenant_id)
) engine=innodb auto_increment=100 default charset=utf8mb4 collate=utf8mb4_general_ci comment='Salesforce部署包明细表';

-- ----------------------------
-- 23、部署历史记录表
-- ----------------------------
drop table if exists sf_deployment_history;
create table sf_deployment_history (
  id                bigint          not null auto_increment    comment '主键',
  deployment_id     bigint          default null               comment '关联的部署包ID',
  org_id            bigint          default null               comment '目标环境ID',
  type              varchar(30)     default null               comment '类型(Validate仅验证/Deploy完整部署/Quick快速部署/Rollback回滚)',
  status            varchar(30)     default null               comment '状态(Processing/Succeeded/Failed/Canceled)',
  backup_path       varchar(500)    default null               comment '备份文件存储路径(仅Deploy/Rollback有值)',
  deploy_async_id   varchar(50)     default null               comment 'Salesforce 异步任务ID',
  start_time        datetime                                   comment '开始时间',
  end_time          datetime                                   comment '结束时间',
  error_msg         varchar(5000)   default null               comment '错误信息',
  git_commit_hash   varchar(64)     default null               comment 'Git 提交哈希',
  git_sync_status   varchar(30)     default null               comment 'Git 同步状态(Success/Failed/NoChange)',
  git_sync_log      varchar(5000)   default null               comment 'Git 同步日志',
  tenant_id         varchar(20)     default '000000'           comment '租户编号',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (id),
  key idx_sf_hist_deploy (deployment_id),
  key idx_sf_hist_tenant (tenant_id)
) engine=innodb auto_increment=100 default charset=utf8mb4 collate=utf8mb4_general_ci comment='Salesforce部署历史记录表';

-- ----------------------------
-- 24、部署历史明细表（Diff 快照）
-- ----------------------------
drop table if exists sf_deployment_history_detail;
create table sf_deployment_history_detail (
  id              bigint          not null auto_increment    comment '主键',
  history_id      bigint          default null               comment '关联的历史记录ID',
  metadata_type   varchar(100)    default null               comment '元数据类型(ApexClass)',
  member_name     varchar(200)    default null               comment '元数据名称(MyController)',
  action          varchar(20)     default null               comment '动作(Add/Delete/Modify)',
  diff_content    longtext                                   comment 'Diff 文本内容(Git 风格展示)',
  tenant_id       varchar(20)     default '000000'           comment '租户编号',
  primary key (id),
  key idx_sf_hist_detail (history_id)
) engine=innodb auto_increment=100 default charset=utf8mb4 collate=utf8mb4_general_ci comment='Salesforce部署历史明细表';

-- ----------------------------
-- 25、Git 仓库配置表（凭证 AES 加密存储）
-- ----------------------------
drop table if exists sf_git_config;
create table sf_git_config (
  id              bigint          not null auto_increment    comment '主键',
  tenant_id       varchar(20)     default '000000'           comment '租户编号',
  name            varchar(100)    default null               comment '配置名称',
  repo_url        varchar(500)    default null               comment 'Git 仓库地址',
  auth_type       varchar(30)     default 'TOKEN'            comment '鉴权方式(TOKEN/SSH)',
  credentials     varchar(1000)   default null               comment '访问凭证(AES 加密后的密文)',
  is_active       tinyint(1)      default 1                  comment '是否启用(1启用 0停用)',
  create_by       varchar(64)     default ''                 comment '创建者',
  create_time     datetime                                   comment '创建时间',
  update_by       varchar(64)     default ''                 comment '更新者',
  update_time     datetime                                   comment '更新时间',
  primary key (id),
  key idx_sf_git_tenant (tenant_id)
) engine=innodb auto_increment=100 default charset=utf8mb4 collate=utf8mb4_general_ci comment='Git仓库配置表';

-- ----------------------------
-- 26、数据比对任务表
-- ----------------------------
drop table if exists sf_data_job;
create table sf_data_job (
  id                bigint          not null auto_increment    comment '主键',
  job_name          varchar(200)    default null               comment '任务名称',
  source_org_id     bigint          default null               comment '源环境ID',
  target_org_id     bigint          default null               comment '目标环境ID',
  status            varchar(30)     default 'IDLE'             comment '任务状态(IDLE/RUNNING/PAUSED/FINISHED)',
  concurrent_limit  int             default 3                  comment '并发比对对象数上限',
  data_end_time     datetime                                   comment '数据截止时间(仅比对该时间点之前的数据)',
  tenant_id         varchar(20)     default '000000'           comment '租户编号',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (id),
  key idx_sf_job_tenant (tenant_id)
) engine=innodb auto_increment=100 default charset=utf8mb4 collate=utf8mb4_general_ci comment='数据比对任务表';

-- ----------------------------
-- 27、数据比对对象配置表
-- ----------------------------
drop table if exists sf_data_obj_config;
create table sf_data_obj_config (
  id                  bigint          not null auto_increment    comment '主键',
  job_id              bigint          not null                   comment '所属任务ID',
  object_name         varchar(100)    default null               comment 'Salesforce 对象 API 名称',
  source_key_field    varchar(100)    default null               comment '源端主键字段',
  target_key_field    varchar(100)    default null               comment '目标端主键字段',
  excluded_fields     text                                       comment '排除比对的字段(逗号分隔)',
  field_mapping_json  text                                       comment '字段映射关系(JSON)',
  sync_filter_logic   varchar(2000)   default null               comment 'SOQL 过滤条件(WHERE 子句)',
  is_active           char(1)         default '0'                comment '是否启用(0启用 1停用)',
  last_check_time     datetime                                   comment '最后比对时间',
  tenant_id           varchar(20)     default '000000'           comment '租户编号',
  create_time         datetime                                   comment '创建时间',
  primary key (id),
  key idx_sf_objcfg_job (job_id),
  key idx_sf_objcfg_tenant (tenant_id)
) engine=innodb auto_increment=100 default charset=utf8mb4 collate=utf8mb4_general_ci comment='数据比对对象配置表';

-- ----------------------------
-- 28、数据比对执行日志表（任务级）
-- ----------------------------
drop table if exists sf_data_run_log;
create table sf_data_run_log (
  id                    bigint          not null auto_increment    comment '主键',
  job_id                bigint          not null                   comment '任务ID',
  run_batch_no          varchar(50)     default null               comment '执行批次号',
  status                varchar(30)     default null               comment '执行状态(RUNNING/FINISHED/FAILED)',
  start_time            datetime                                   comment '开始时间',
  end_time              datetime                                   comment '结束时间',
  total_source_rows     int             default 0                  comment '源端总行数',
  total_target_rows     int             default 0                  comment '目标端总行数',
  diff_row_count        int             default 0                  comment '差异行数',
  missing_target_count  int             default 0                  comment '目标端缺失行数',
  result_file_path      varchar(500)    default null               comment '比对结果文件路径',
  error_msg             varchar(5000)   default null               comment '错误信息',
  tenant_id             varchar(20)     default '000000'           comment '租户编号',
  primary key (id),
  key idx_sf_runlog_job (job_id),
  key idx_sf_runlog_tenant (tenant_id)
) engine=innodb auto_increment=100 default charset=utf8mb4 collate=utf8mb4_general_ci comment='数据比对执行日志表';

-- ----------------------------
-- 29、数据比对执行日志表（对象级）
-- ----------------------------
drop table if exists sf_data_run_obj_log;
create table sf_data_run_obj_log (
  id                          bigint          not null auto_increment    comment '主键',
  run_log_id                  bigint          not null                   comment '父级日志ID',
  job_id                      bigint          default null               comment '任务ID',
  obj_config_id               bigint          default null               comment '对象配置ID',
  object_name                 varchar(100)    default null               comment '对象 API 名称',
  status                      varchar(30)     default null               comment '状态(WAITING/RUNNING/FINISHED/FAILED)',
  progress                    int             default 0                  comment '进度百分比(0-100)',
  total_source                int             default 0                  comment '源端行数',
  total_target                int             default 0                  comment '目标端行数',
  diff_count                  int             default 0                  comment '差异行数',
  ignored_post_cutoff_count   int             default 0                  comment '因超过数据截止时间被忽略的行数',
  result_file_path            varchar(500)    default null               comment '结果文件路径',
  error_msg                   varchar(5000)   default null               comment '错误信息',
  cost_time                   bigint          default 0                  comment '耗时(毫秒)',
  tenant_id                   varchar(20)     default '000000'           comment '租户编号',
  create_time                 datetime                                   comment '创建时间',
  update_time                 datetime                                   comment '更新时间',
  primary key (id),
  key idx_sf_objlog_run (run_log_id),
  key idx_sf_objlog_job (job_id),
  key idx_sf_objlog_tenant (tenant_id)
) engine=innodb auto_increment=100 default charset=utf8mb4 collate=utf8mb4_general_ci comment='数据比对执行日志表(对象级)';



-- ==========================================================================================
--  Quartz 定时任务调度表（ruoyi-quartz 模块依赖，缺少会导致应用启动失败）
-- ==========================================================================================

DROP TABLE IF EXISTS QRTZ_FIRED_TRIGGERS;
DROP TABLE IF EXISTS QRTZ_PAUSED_TRIGGER_GRPS;
DROP TABLE IF EXISTS QRTZ_SCHEDULER_STATE;
DROP TABLE IF EXISTS QRTZ_LOCKS;
DROP TABLE IF EXISTS QRTZ_SIMPLE_TRIGGERS;
DROP TABLE IF EXISTS QRTZ_SIMPROP_TRIGGERS;
DROP TABLE IF EXISTS QRTZ_CRON_TRIGGERS;
DROP TABLE IF EXISTS QRTZ_BLOB_TRIGGERS;
DROP TABLE IF EXISTS QRTZ_TRIGGERS;
DROP TABLE IF EXISTS QRTZ_JOB_DETAILS;
DROP TABLE IF EXISTS QRTZ_CALENDARS;

-- ----------------------------
-- 1、存储每一个已配置的 jobDetail 的详细信息
-- ----------------------------
create table QRTZ_JOB_DETAILS (
    sched_name           varchar(120)    not null            comment '调度名称',
    job_name             varchar(200)    not null            comment '任务名称',
    job_group            varchar(200)    not null            comment '任务组名',
    description          varchar(250)    null                comment '相关介绍',
    job_class_name       varchar(250)    not null            comment '执行任务类名称',
    is_durable           varchar(1)      not null            comment '是否持久化',
    is_nonconcurrent     varchar(1)      not null            comment '是否并发',
    is_update_data       varchar(1)      not null            comment '是否更新数据',
    requests_recovery    varchar(1)      not null            comment '是否接受恢复执行',
    job_data             blob            null                comment '存放持久化job对象',
    primary key (sched_name, job_name, job_group)
) engine=innodb comment = '任务详细信息表';

-- ----------------------------A
-- 2、 存储已配置的 Trigger 的信息
-- ----------------------------
create table QRTZ_TRIGGERS (
    sched_name           varchar(120)    not null            comment '调度名称',
    trigger_name         varchar(200)    not null            comment '触发器的名字',
    trigger_group        varchar(200)    not null            comment '触发器所属组的名字',
    job_name             varchar(200)    not null            comment 'qrtz_job_details表job_name的外键',
    job_group            varchar(200)    not null            comment 'qrtz_job_details表job_group的外键',
    description          varchar(250)    null                comment '相关介绍',
    next_fire_time       bigint(13)      null                comment '上一次触发时间（毫秒）',
    prev_fire_time       bigint(13)      null                comment '下一次触发时间（默认为-1表示不触发）',
    priority             integer         null                comment '优先级',
    trigger_state        varchar(16)     not null            comment '触发器状态',
    trigger_type         varchar(8)      not null            comment '触发器的类型',
    start_time           bigint(13)      not null            comment '开始时间',
    end_time             bigint(13)      null                comment '结束时间',
    calendar_name        varchar(200)    null                comment '日程表名称',
    misfire_instr        smallint(2)     null                comment '补偿执行的策略',
    job_data             blob            null                comment '存放持久化job对象',
    primary key (sched_name, trigger_name, trigger_group),
    foreign key (sched_name, job_name, job_group) references QRTZ_JOB_DETAILS(sched_name, job_name, job_group)
) engine=innodb comment = '触发器详细信息表';

-- ----------------------------
-- 3、 存储简单的 Trigger，包括重复次数，间隔，以及已触发的次数
-- ----------------------------
create table QRTZ_SIMPLE_TRIGGERS (
    sched_name           varchar(120)    not null            comment '调度名称',
    trigger_name         varchar(200)    not null            comment 'qrtz_triggers表trigger_name的外键',
    trigger_group        varchar(200)    not null            comment 'qrtz_triggers表trigger_group的外键',
    repeat_count         bigint(7)       not null            comment '重复的次数统计',
    repeat_interval      bigint(12)      not null            comment '重复的间隔时间',
    times_triggered      bigint(10)      not null            comment '已经触发的次数',
    primary key (sched_name, trigger_name, trigger_group),
    foreign key (sched_name, trigger_name, trigger_group) references QRTZ_TRIGGERS(sched_name, trigger_name, trigger_group)
) engine=innodb comment = '简单触发器的信息表';

-- ----------------------------
-- 4、 存储 Cron Trigger，包括 Cron 表达式和时区信息
-- ----------------------------
create table QRTZ_CRON_TRIGGERS (
    sched_name           varchar(120)    not null            comment '调度名称',
    trigger_name         varchar(200)    not null            comment 'qrtz_triggers表trigger_name的外键',
    trigger_group        varchar(200)    not null            comment 'qrtz_triggers表trigger_group的外键',
    cron_expression      varchar(200)    not null            comment 'cron表达式',
    time_zone_id         varchar(80)                         comment '时区',
    primary key (sched_name, trigger_name, trigger_group),
    foreign key (sched_name, trigger_name, trigger_group) references QRTZ_TRIGGERS(sched_name, trigger_name, trigger_group)
) engine=innodb comment = 'Cron类型的触发器表';

-- ----------------------------
-- 5、 Trigger 作为 Blob 类型存储(用于 Quartz 用户用 JDBC 创建他们自己定制的 Trigger 类型，JobStore 并不知道如何存储实例的时候)
-- ----------------------------
create table QRTZ_BLOB_TRIGGERS (
    sched_name           varchar(120)    not null            comment '调度名称',
    trigger_name         varchar(200)    not null            comment 'qrtz_triggers表trigger_name的外键',
    trigger_group        varchar(200)    not null            comment 'qrtz_triggers表trigger_group的外键',
    blob_data            blob            null                comment '存放持久化Trigger对象',
    primary key (sched_name, trigger_name, trigger_group),
    foreign key (sched_name, trigger_name, trigger_group) references QRTZ_TRIGGERS(sched_name, trigger_name, trigger_group)
) engine=innodb comment = 'Blob类型的触发器表';

-- ----------------------------
-- 6、 以 Blob 类型存储存放日历信息， quartz可配置一个日历来指定一个时间范围
-- ----------------------------
create table QRTZ_CALENDARS (
    sched_name           varchar(120)    not null            comment '调度名称',
    calendar_name        varchar(200)    not null            comment '日历名称',
    calendar             blob            not null            comment '存放持久化calendar对象',
    primary key (sched_name, calendar_name)
) engine=innodb comment = '日历信息表';

-- ----------------------------
-- 7、 存储已暂停的 Trigger 组的信息
-- ----------------------------
create table QRTZ_PAUSED_TRIGGER_GRPS (
    sched_name           varchar(120)    not null            comment '调度名称',
    trigger_group        varchar(200)    not null            comment 'qrtz_triggers表trigger_group的外键',
    primary key (sched_name, trigger_group)
) engine=innodb comment = '暂停的触发器表';

-- ----------------------------
-- 8、 存储与已触发的 Trigger 相关的状态信息，以及相联 Job 的执行信息
-- ----------------------------
create table QRTZ_FIRED_TRIGGERS (
    sched_name           varchar(120)    not null            comment '调度名称',
    entry_id             varchar(95)     not null            comment '调度器实例id',
    trigger_name         varchar(200)    not null            comment 'qrtz_triggers表trigger_name的外键',
    trigger_group        varchar(200)    not null            comment 'qrtz_triggers表trigger_group的外键',
    instance_name        varchar(200)    not null            comment '调度器实例名',
    fired_time           bigint(13)      not null            comment '触发的时间',
    sched_time           bigint(13)      not null            comment '定时器制定的时间',
    priority             integer         not null            comment '优先级',
    state                varchar(16)     not null            comment '状态',
    job_name             varchar(200)    null                comment '任务名称',
    job_group            varchar(200)    null                comment '任务组名',
    is_nonconcurrent     varchar(1)      null                comment '是否并发',
    requests_recovery    varchar(1)      null                comment '是否接受恢复执行',
    primary key (sched_name, entry_id)
) engine=innodb comment = '已触发的触发器表';

-- ----------------------------
-- 9、 存储少量的有关 Scheduler 的状态信息，假如是用于集群中，可以看到其他的 Scheduler 实例
-- ----------------------------
create table QRTZ_SCHEDULER_STATE (
    sched_name           varchar(120)    not null            comment '调度名称',
    instance_name        varchar(200)    not null            comment '实例名称',
    last_checkin_time    bigint(13)      not null            comment '上次检查时间',
    checkin_interval     bigint(13)      not null            comment '检查间隔时间',
    primary key (sched_name, instance_name)
) engine=innodb comment = '调度器状态表';

-- ----------------------------
-- 10、 存储程序的悲观锁的信息(假如使用了悲观锁)
-- ----------------------------
create table QRTZ_LOCKS (
    sched_name           varchar(120)    not null            comment '调度名称',
    lock_name            varchar(40)     not null            comment '悲观锁名称',
    primary key (sched_name, lock_name)
) engine=innodb comment = '存储的悲观锁信息表';

-- ----------------------------
-- 11、 Quartz集群实现同步机制的行锁表
-- ----------------------------
create table QRTZ_SIMPROP_TRIGGERS (
    sched_name           varchar(120)    not null            comment '调度名称',
    trigger_name         varchar(200)    not null            comment 'qrtz_triggers表trigger_name的外键',
    trigger_group        varchar(200)    not null            comment 'qrtz_triggers表trigger_group的外键',
    str_prop_1           varchar(512)    null                comment 'String类型的trigger的第一个参数',
    str_prop_2           varchar(512)    null                comment 'String类型的trigger的第二个参数',
    str_prop_3           varchar(512)    null                comment 'String类型的trigger的第三个参数',
    int_prop_1           int             null                comment 'int类型的trigger的第一个参数',
    int_prop_2           int             null                comment 'int类型的trigger的第二个参数',
    long_prop_1          bigint          null                comment 'long类型的trigger的第一个参数',
    long_prop_2          bigint          null                comment 'long类型的trigger的第二个参数',
    dec_prop_1           numeric(13,4)   null                comment 'decimal类型的trigger的第一个参数',
    dec_prop_2           numeric(13,4)   null                comment 'decimal类型的trigger的第二个参数',
    bool_prop_1          varchar(1)      null                comment 'Boolean类型的trigger的第一个参数',
    bool_prop_2          varchar(1)      null                comment 'Boolean类型的trigger的第二个参数',
    primary key (sched_name, trigger_name, trigger_group),
    foreign key (sched_name, trigger_name, trigger_group) references QRTZ_TRIGGERS(sched_name, trigger_name, trigger_group)
) engine=innodb comment = '同步机制的行锁表';

commit;
SET FOREIGN_KEY_CHECKS = 1;

-- ==========================================================================================
--  初始化脚本结束
--
--  默认账号：admin / admin123   （超级管理员，生产环境请立即修改）
--  演示账号：sfdev / admin123   （普通角色，已授权 Salesforce 运维菜单）
--
--  执行完成后请检查：
--    1. ruoyi-admin/src/main/resources/application-druid.yml  数据库连接
--    2. ruoyi-admin/src/main/resources/application.yml          Salesforce Connected App 凭证
--    3. Redis 连接配置（spring.redis.*）
-- ==========================================================================================
