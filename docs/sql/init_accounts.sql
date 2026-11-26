-- ==============================================================================
-- 初始账号与演示基础数据（密码统一 123456）
-- 生成日期：2026-11-25
--
-- 依赖：先执行 all_tables.sql 建表
-- 执行：mysql -u root -p jianshenfang < init_accounts.sql
-- 特性：幂等（INSERT IGNORE + NOT EXISTS），可重复执行，不会覆盖已有账号的密码
--      —— 如果手机号已存在，脚本会跳过，不会把现有账号改成 123456。
--
-- 密码哈希由项目同一套 BCryptPasswordEncoder(强度 10) 生成并已验证匹配 "123456"：
--   $2a$10$z2tEgHA58uE2vA5HqYItOuNBw/mQjhI/tS/DD0TwPxgeGOBBOi1mK   （管理员）
--   $2a$10$jTEg6VvMmG31gJfXhTUVZuvV78wj/55tN8A3Y7FTJH.VbDezosKl6   （教练）
--   $2a$10$jUOHumIlUGGb.fs0RLbr9O/VBsOF/HjErVnT3pui5tcyhOmSIBFl.   （会员）
--
-- 账号清单：
--   管理员  13800000001 店长     / 123456
--   管理员  13800000002 副店长   / 123456
--   教练    13800000011 李教练   / 123456
--   教练    13800000012 王教练   / 123456
--   会员    13900000001 张小明   / 123456（李教练）
--   会员    13900000002 李小红   / 123456（李教练）
--   会员    13900000003 王大壮   / 123456（王教练）
--   会员    13900000004 赵小美   / 123456（王教练）
--   会员    13900000005 陈小强   / 123456（李教练）
-- ==============================================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------------------------
-- 1. 登录账号
-- ------------------------------------------------------------------------------
INSERT IGNORE INTO sys_user (phone, password, role, status) VALUES
('13800000001', '$2a$10$z2tEgHA58uE2vA5HqYItOuNBw/mQjhI/tS/DD0TwPxgeGOBBOi1mK', 'ADMIN',  1),
('13800000002', '$2a$10$z2tEgHA58uE2vA5HqYItOuNBw/mQjhI/tS/DD0TwPxgeGOBBOi1mK', 'ADMIN',  1),
('13800000011', '$2a$10$jTEg6VvMmG31gJfXhTUVZuvV78wj/55tN8A3Y7FTJH.VbDezosKl6', 'COACH',  1),
('13800000012', '$2a$10$jTEg6VvMmG31gJfXhTUVZuvV78wj/55tN8A3Y7FTJH.VbDezosKl6', 'COACH',  1),
('13900000001', '$2a$10$jUOHumIlUGGb.fs0RLbr9O/VBsOF/HjErVnT3pui5tcyhOmSIBFl.', 'MEMBER', 1),
('13900000002', '$2a$10$jUOHumIlUGGb.fs0RLbr9O/VBsOF/HjErVnT3pui5tcyhOmSIBFl.', 'MEMBER', 1),
('13900000003', '$2a$10$jUOHumIlUGGb.fs0RLbr9O/VBsOF/HjErVnT3pui5tcyhOmSIBFl.', 'MEMBER', 1),
('13900000004', '$2a$10$jUOHumIlUGGb.fs0RLbr9O/VBsOF/HjErVnT3pui5tcyhOmSIBFl.', 'MEMBER', 1),
('13900000005', '$2a$10$jUOHumIlUGGb.fs0RLbr9O/VBsOF/HjErVnT3pui5tcyhOmSIBFl.', 'MEMBER', 1);

-- ------------------------------------------------------------------------------
-- 2. 教练档案（coach.user_id 指向刚建/已有的教练账号）
-- ------------------------------------------------------------------------------
INSERT INTO coach (user_id, name, status)
SELECT u.id, '李教练', 1 FROM sys_user u
WHERE u.phone = '13800000011'
  AND NOT EXISTS (SELECT 1 FROM coach c WHERE c.user_id = u.id);

INSERT INTO coach (user_id, name, status)
SELECT u.id, '王教练', 1 FROM sys_user u
WHERE u.phone = '13800000012'
  AND NOT EXISTS (SELECT 1 FROM coach c WHERE c.user_id = u.id);

-- ------------------------------------------------------------------------------
-- 3. 会员档案（member.phone 与 sys_user.phone 保持一致，专属教练按上文绑定）
-- ------------------------------------------------------------------------------
INSERT INTO member (user_id, name, phone, gender, coach_id)
SELECT u.id, '张小明', u.phone, 1,
       (SELECT c.id FROM coach c JOIN sys_user cu ON cu.id = c.user_id WHERE cu.phone = '13800000011' LIMIT 1)
FROM sys_user u
WHERE u.phone = '13900000001'
  AND NOT EXISTS (SELECT 1 FROM member m WHERE m.user_id = u.id OR m.phone = u.phone);

INSERT INTO member (user_id, name, phone, gender, coach_id)
SELECT u.id, '李小红', u.phone, 2,
       (SELECT c.id FROM coach c JOIN sys_user cu ON cu.id = c.user_id WHERE cu.phone = '13800000011' LIMIT 1)
FROM sys_user u
WHERE u.phone = '13900000002'
  AND NOT EXISTS (SELECT 1 FROM member m WHERE m.user_id = u.id OR m.phone = u.phone);

INSERT INTO member (user_id, name, phone, gender, coach_id)
SELECT u.id, '王大壮', u.phone, 1,
       (SELECT c.id FROM coach c JOIN sys_user cu ON cu.id = c.user_id WHERE cu.phone = '13800000012' LIMIT 1)
FROM sys_user u
WHERE u.phone = '13900000003'
  AND NOT EXISTS (SELECT 1 FROM member m WHERE m.user_id = u.id OR m.phone = u.phone);

INSERT INTO member (user_id, name, phone, gender, coach_id)
SELECT u.id, '赵小美', u.phone, 2,
       (SELECT c.id FROM coach c JOIN sys_user cu ON cu.id = c.user_id WHERE cu.phone = '13800000012' LIMIT 1)
FROM sys_user u
WHERE u.phone = '13900000004'
  AND NOT EXISTS (SELECT 1 FROM member m WHERE m.user_id = u.id OR m.phone = u.phone);

INSERT INTO member (user_id, name, phone, gender, coach_id)
SELECT u.id, '陈小强', u.phone, 1,
       (SELECT c.id FROM coach c JOIN sys_user cu ON cu.id = c.user_id WHERE cu.phone = '13800000011' LIMIT 1)
FROM sys_user u
WHERE u.phone = '13900000005'
  AND NOT EXISTS (SELECT 1 FROM member m WHERE m.user_id = u.id OR m.phone = u.phone);

-- ------------------------------------------------------------------------------
-- 4. 可选：管理员兼任教练（需要管理员也能被排课 / 从公众号教练入口进工作台时）
--    默认不执行，需要时去掉注释。
-- ------------------------------------------------------------------------------
-- INSERT INTO coach (user_id, name, status)
-- SELECT u.id, '店长', 1 FROM sys_user u
-- WHERE u.phone = '13800000001'
--   AND NOT EXISTS (SELECT 1 FROM coach c WHERE c.user_id = u.id);

-- ------------------------------------------------------------------------------
-- 5. 可选：基础课程 + 每个会员一个 30 节课包（没有课包无法预约，建议一起执行）
-- ------------------------------------------------------------------------------
INSERT IGNORE INTO course_template (name, duration_min, default_sessions, status) VALUES
('增肌训练', 60, 30, 1),
('减脂塑形', 60, 30, 1),
('体态康复', 60, 30, 1);

-- 男会员 → 增肌训练，女会员 → 减脂塑形；30 节 / 3000 元 / 有效期一年
INSERT IGNORE INTO member_package
    (member_id, course_id, coach_id, total_sessions, remain_sessions, total_price,
     start_date, expire_date, status, source)
SELECT m.id, ct.id, m.coach_id, 30, 30, 3000.00,
       CURDATE(), DATE_ADD(CURDATE(), INTERVAL 1 YEAR), 'ACTIVE', 'NEW'
FROM member m
JOIN sys_user u ON u.id = m.user_id
JOIN course_template ct ON ct.name = CASE WHEN m.gender = 2 THEN '减脂塑形' ELSE '增肌训练' END
WHERE u.phone IN ('13900000001', '13900000002', '13900000003', '13900000004', '13900000005');

-- ------------------------------------------------------------------------------
-- 6. 执行后核对
-- ------------------------------------------------------------------------------
-- 账号与档案一览（role / 档案名 / 教练）
SELECT u.id, u.phone, u.role, u.status,
       COALESCE(m.name, c.name, '-') AS profile_name,
       mc.name AS coach_name
FROM sys_user u
LEFT JOIN member m ON m.user_id = u.id
LEFT JOIN coach c ON c.user_id = u.id
LEFT JOIN coach mc ON mc.id = m.coach_id
WHERE u.phone IN ('13800000001','13800000002','13800000011','13800000012',
                  '13900000001','13900000002','13900000003','13900000004','13900000005')
ORDER BY u.role DESC, u.phone;

-- 课包一览
SELECT m.name AS member_name, ct.name AS course_name, p.remain_sessions, p.expire_date
FROM member_package p
JOIN member m ON m.id = p.member_id
JOIN course_template ct ON ct.id = p.course_id
ORDER BY m.id;
