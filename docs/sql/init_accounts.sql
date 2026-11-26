-- ==============================================================================
-- 初始化数据脚本（三角色 + 完整演示闭环）· 2026-11-26 重做
--
-- 覆盖：管理员 / 教练 / 会员 三种角色
--       会员买课记录（PURCHASE）、消课记录（CONSUME）、历史与未来预约、教练请假
--
-- 依赖：先执行 all_tables.sql 建表
-- 执行：mysql -u root -p fitness < docs/sql/init_accounts.sql      （生产库名 fitness）
--
-- 密码统一 123456，sys_user.password 存 BCrypt 哈希（强度 10），
-- 已用项目同一套 BCryptPasswordEncoder 的 matches("123456", hash) 验证为 true。
--
-- 幂等：可重复执行，不会重复插入、不会覆盖已有账号密码。
--   1) 有唯一键的表（sys_user.phone / member.user_id / member_package(member,course)
--      / coach_qualification(cert) / coach_leave(coach,start,end) / booking(coach,slot)
--      / coach_review.booking）用 INSERT IGNORE 兜底；
--   2) 无唯一键的表（coach 档案、member_session_log）先用 SET @x = COUNT(*) 判空再插入；
--   3) 全部按「手机号 / 名称」解析主键，不写死 id，任何库上执行都不会错位。
--
-- 账号清单：
--   管理员  13700000003  陈筱鹭  / 123456   （兼教练，可被排课、可从公众号教练入口登录）
--   教练    13700000004  王毅峰  / 123456
--   会员    13800000001  毛文坊  / 123456   （专属教练：陈筱鹭）
--   会员    13900000002  陈忠辛  / 123456   （专属教练：陈筱鹭）
--   会员    15259996010  冯原    / 123456   （专属教练：王毅峰）
--   教练档案（无登录账号）：林珊、徐正週
-- ==============================================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------------------------
-- 1. 登录账号（3 种角色）
-- ------------------------------------------------------------------------------
INSERT IGNORE INTO sys_user (phone, password, role, status) VALUES
('13700000003', '$2a$10$z2tEgHA58uE2vA5HqYItOuNBw/mQjhI/tS/DD0TwPxgeGOBBOi1mK', 'ADMIN',  1),
('13700000004', '$2a$10$jTEg6VvMmG31gJfXhTUVZuvV78wj/55tN8A3Y7FTJH.VbDezosKl6', 'COACH',  1),
('13800000001', '$2a$10$jUOHumIlUGGb.fs0RLbr9O/VBsOF/HjErVnT3pui5tcyhOmSIBFl.', 'MEMBER', 1),
('13900000002', '$2a$10$jUOHumIlUGGb.fs0RLbr9O/VBsOF/HjErVnT3pui5tcyhOmSIBFl.', 'MEMBER', 1),
('15259996010', '$2a$10$jUOHumIlUGGb.fs0RLbr9O/VBsOF/HjErVnT3pui5tcyhOmSIBFl.', 'MEMBER', 1);

-- 账号主键按手机号解析，供后面各段使用
SET @uid_admin = (SELECT id FROM sys_user WHERE phone = '13700000003' LIMIT 1);
SET @uid_coach = (SELECT id FROM sys_user WHERE phone = '13700000004' LIMIT 1);
SET @uid_m1    = (SELECT id FROM sys_user WHERE phone = '13800000001' LIMIT 1);
SET @uid_m2    = (SELECT id FROM sys_user WHERE phone = '13900000002' LIMIT 1);
SET @uid_m3    = (SELECT id FROM sys_user WHERE phone = '15259996010' LIMIT 1);

-- ------------------------------------------------------------------------------
-- 2. 教练档案（coach 无唯一键，用 @变量 判空后再插，避免重复）
-- ------------------------------------------------------------------------------
SET @c_exist = (SELECT COUNT(*) FROM coach WHERE name = '陈筱鹭');
INSERT INTO coach (user_id, name, photo, intro, status)
SELECT @uid_admin, '陈筱鹭', NULL, '6 年一对一私教经验，累计授课 8000+ 节。擅长体态矫正、产后恢复与增肌减脂，主张先建立动作模式再上强度。', 1
FROM DUAL WHERE @c_exist = 0;

SET @c_exist = (SELECT COUNT(*) FROM coach WHERE name = '王毅峰');
INSERT INTO coach (user_id, name, photo, intro, status)
SELECT @uid_coach, '王毅峰', NULL, '拉伸与运动康复方向，注重动作模式重建与疼痛缓解，课程节奏温和。', 1
FROM DUAL WHERE @c_exist = 0;

SET @c_exist = (SELECT COUNT(*) FROM coach WHERE name = '林珊');
INSERT INTO coach (user_id, name, photo, intro, status)
SELECT NULL, '林珊', NULL, '美臀塑形与女性体态管理，强度循序渐进，兼顾趣味性。', 1
FROM DUAL WHERE @c_exist = 0;

SET @c_exist = (SELECT COUNT(*) FROM coach WHERE name = '徐正週');
INSERT INTO coach (user_id, name, photo, intro, status)
SELECT NULL, '徐正週', NULL, '力量训练与增肌专家，重视渐进超负荷与训练安全。', 1
FROM DUAL WHERE @c_exist = 0;

SET @cid_cxl = (SELECT id FROM coach WHERE name = '陈筱鹭' LIMIT 1);
SET @cid_wyf = (SELECT id FROM coach WHERE name = '王毅峰' LIMIT 1);

-- ------------------------------------------------------------------------------
-- 3. 教练资质（uk_coach_cert 兜底幂等）
-- ------------------------------------------------------------------------------
INSERT IGNORE INTO coach_qualification (coach_id, name, cert_no, issuer, expire_date)
SELECT c.id, s.qname, s.cert_no, s.issuer, s.expire_date
FROM (
    SELECT '陈筱鹭' AS coach_name, 'ACE 私人教练认证' AS qname, 'ACE-TR-82317' AS cert_no, '美国运动委员会' AS issuer, '2028-05-31' AS expire_date
    UNION ALL SELECT '陈筱鹭', 'FMS 功能性动作筛查 Level 2', 'FMS-L2-1145', 'FMS', '2029-01-31'
    UNION ALL SELECT '王毅峰', 'NASM-CPT 私人教练认证', 'NASM-88231', '美国国家运动医学院', '2027-12-31'
    UNION ALL SELECT '林珊',   'CBBA 高级私人教练', 'CBBA-2023-7741', '中国健美协会', '2027-06-30'
    UNION ALL SELECT '徐正週', 'NSCA-CSCS 体能训练专家', 'NSC-55617', '美国国家体能协会', '2028-03-31'
) s
JOIN coach c ON c.name = s.coach_name;

-- ------------------------------------------------------------------------------
-- 4. 教练请假（uk_coach_range 兜底幂等；保证与下面预约时段不冲突）
-- ------------------------------------------------------------------------------
INSERT IGNORE INTO coach_leave (coach_id, start_time, end_time, reason)
SELECT c.id, s.start_time, s.end_time, s.reason
FROM (
    SELECT '陈筱鹭' AS coach_name, '2026-10-28 09:00:00' AS start_time, '2026-10-28 12:00:00' AS end_time, '外出参加体态康复培训' AS reason
    UNION ALL SELECT '陈筱鹭', '2026-12-02 09:00:00', '2026-12-02 12:00:00', '外出参加体态康复培训'
    UNION ALL SELECT '王毅峰', '2026-12-05 09:00:00', '2026-12-05 21:00:00', '家中有事，全天请假'
) s
JOIN coach c ON c.name = s.coach_name;

-- ------------------------------------------------------------------------------
-- 5. 会员档案（member.phone 与 sys_user.phone 一致，1V1 绑定专属教练）
-- ------------------------------------------------------------------------------
INSERT IGNORE INTO member (user_id, name, phone, gender, birthday, coach_id)
SELECT u.id, '毛文坊', u.phone, 1, '1995-06-18', @cid_cxl
FROM sys_user u WHERE u.phone = '13800000001';

INSERT IGNORE INTO member (user_id, name, phone, gender, birthday, coach_id)
SELECT u.id, '陈忠辛', u.phone, 1, '1992-02-10', @cid_cxl
FROM sys_user u WHERE u.phone = '13900000002';

INSERT IGNORE INTO member (user_id, name, phone, gender, birthday, coach_id)
SELECT u.id, '冯原', u.phone, 1, NULL, @cid_wyf
FROM sys_user u WHERE u.phone = '15259996010';

SET @mid_m1 = (SELECT id FROM member WHERE phone = '13800000001' LIMIT 1);
SET @mid_m2 = (SELECT id FROM member WHERE phone = '13900000002' LIMIT 1);
SET @mid_m3 = (SELECT id FROM member WHERE phone = '15259996010' LIMIT 1);

-- ------------------------------------------------------------------------------
-- 6. 课程模板（uk_name 兜底幂等）
-- ------------------------------------------------------------------------------
INSERT IGNORE INTO course_template (name, duration_min, default_sessions, status) VALUES
('基础私教', 60, 30, 1),
('拉伸课程', 60, 20, 1),
('美臀塑形', 60, 30, 1),
('力量增肌', 60, 30, 1);

-- ------------------------------------------------------------------------------
-- 7. 会员课包（买课记录）
--    remain_sessions 已按下面的消课记录扣好：毛文坊 30→24、陈忠辛 30→27、冯原 12→10
--    uk_member_course 兜底幂等
-- ------------------------------------------------------------------------------
INSERT IGNORE INTO member_package
    (member_id, course_id, coach_id, total_sessions, remain_sessions, total_price,
     start_date, expire_date, status, source)
SELECT m.id, ct.id, m.coach_id, 30, 24, 9600.00, '2026-10-20', '2027-03-01', 'ACTIVE', 'NEW'
FROM member m JOIN course_template ct ON ct.name = '基础私教'
WHERE m.phone = '13800000001';

INSERT IGNORE INTO member_package
    (member_id, course_id, coach_id, total_sessions, remain_sessions, total_price,
     start_date, expire_date, status, source)
SELECT m.id, ct.id, m.coach_id, 30, 27, 9600.00, '2026-09-10', '2027-01-15', 'ACTIVE', 'NEW'
FROM member m JOIN course_template ct ON ct.name = '基础私教'
WHERE m.phone = '13900000002';

INSERT IGNORE INTO member_package
    (member_id, course_id, coach_id, total_sessions, remain_sessions, total_price,
     start_date, expire_date, status, source)
SELECT m.id, ct.id, m.coach_id, 12, 10, 3600.00, '2026-11-01', '2027-05-01', 'ACTIVE', 'NEW'
FROM member m JOIN course_template ct ON ct.name = '力量增肌'
WHERE m.phone = '15259996010';

-- 课包主键（下文流水要用）
SET @pkg_m1 = (SELECT p.id FROM member_package p
               JOIN course_template ct ON ct.id = p.course_id
               WHERE p.member_id = @mid_m1 AND ct.name = '基础私教' LIMIT 1);
SET @pkg_m2 = (SELECT p.id FROM member_package p
               JOIN course_template ct ON ct.id = p.course_id
               WHERE p.member_id = @mid_m2 AND ct.name = '基础私教' LIMIT 1);
SET @pkg_m3 = (SELECT p.id FROM member_package p
               JOIN course_template ct ON ct.id = p.course_id
               WHERE p.member_id = @mid_m3 AND ct.name = '力量增肌' LIMIT 1);

-- ------------------------------------------------------------------------------
-- 8. 预约记录
--    8.1 历史已完成（列表里能看到消课来源）       8.2 未来待上课
--    8.3 已取消（会员取消 / 教练取消各一条）
--    8.1/8.2 由 uk_coach_slot(coach_id, active_start_time) 兜底；
--    8.3 的 active_start_time 为 NULL、唯一键拦不住，用 @变量 判空后再插。
-- ------------------------------------------------------------------------------
-- 8.1 历史已完成
INSERT IGNORE INTO booking
    (member_id, coach_id, course_id, package_id, start_time, end_time, status, checked_in_at)
SELECT m.id, p.coach_id, p.course_id, p.id, CAST(s.st AS DATETIME),
       TIMESTAMPADD(MINUTE, ct.duration_min, CAST(s.st AS DATETIME)), 'COMPLETED', CAST(s.st AS DATETIME)
FROM (
              SELECT '13800000001' AS phone, '基础私教' AS course, '2026-11-02 14:00:00' AS st
    UNION ALL SELECT '13800000001', '基础私教', '2026-11-05 14:00:00'
    UNION ALL SELECT '13800000001', '基础私教', '2026-11-09 14:00:00'
    UNION ALL SELECT '13800000001', '基础私教', '2026-11-12 14:00:00'
    UNION ALL SELECT '13800000001', '基础私教', '2026-11-16 14:00:00'
    UNION ALL SELECT '13800000001', '基础私教', '2026-11-19 14:00:00'
    UNION ALL SELECT '13900000002', '基础私教', '2026-11-03 09:30:00'
    UNION ALL SELECT '13900000002', '基础私教', '2026-11-06 09:30:00'
    UNION ALL SELECT '13900000002', '基础私教', '2026-11-10 09:30:00'
    UNION ALL SELECT '15259996010', '力量增肌', '2026-11-07 19:00:00'
    UNION ALL SELECT '15259996010', '力量增肌', '2026-11-14 19:00:00'
) s
JOIN member m ON m.phone = s.phone
JOIN member_package p ON p.member_id = m.id
JOIN course_template ct ON ct.id = p.course_id AND ct.name = s.course
ORDER BY s.st;

-- 8.2 未来待上课（工作台「今日上课 / 我的预约」有内容可看）
INSERT IGNORE INTO booking
    (member_id, coach_id, course_id, package_id, start_time, end_time, status)
SELECT m.id, p.coach_id, p.course_id, p.id, CAST(s.st AS DATETIME),
       TIMESTAMPADD(MINUTE, ct.duration_min, CAST(s.st AS DATETIME)), 'BOOKED'
FROM (
              SELECT '13800000001' AS phone, '基础私教' AS course, '2026-12-01 14:00:00' AS st
    UNION ALL SELECT '13900000002', '基础私教', '2026-12-01 15:00:00'
) s
JOIN member m ON m.phone = s.phone
JOIN member_package p ON p.member_id = m.id
JOIN course_template ct ON ct.id = p.course_id AND ct.name = s.course
ORDER BY s.st;

-- 8.3 已取消：会员取消（临时有事）
SET @course_m1 = (SELECT course_id FROM member_package WHERE id = @pkg_m1);
SET @b_cancel  = (SELECT COUNT(*) FROM booking WHERE member_id = @mid_m1 AND start_time = '2026-11-23 14:00:00');
INSERT INTO booking
    (member_id, coach_id, course_id, package_id, start_time, end_time, status,
     cancel_by, cancel_reason, canceled_at)
SELECT @mid_m1, @cid_cxl, @course_m1, @pkg_m1,
       '2026-11-23 14:00:00', '2026-11-23 15:00:00', 'CANCELED',
       'MEMBER', '临时有事，改天再约', '2026-11-22 20:15:00'
FROM DUAL WHERE @b_cancel = 0;

-- 8.3 已取消：教练取消（外出培训）
SET @course_m2 = (SELECT course_id FROM member_package WHERE id = @pkg_m2);
SET @b_cancel  = (SELECT COUNT(*) FROM booking WHERE member_id = @mid_m2 AND start_time = '2026-11-20 09:30:00');
INSERT INTO booking
    (member_id, coach_id, course_id, package_id, start_time, end_time, status,
     cancel_by, cancel_reason, canceled_at)
SELECT @mid_m2, @cid_cxl, @course_m2, @pkg_m2,
       '2026-11-20 09:30:00', '2026-11-20 10:30:00', 'CANCELED',
       'COACH', '教练外出培训，已与会员沟通改期', '2026-11-18 09:00:00'
FROM DUAL WHERE @b_cancel = 0;

-- ------------------------------------------------------------------------------
-- 9. 课时流水（买课 + 消课）
--    9.1 PURCHASE：每张课包一条，change=+总节数
--    9.2 CONSUME：按上面的 COMPLETED 预约逐条生成，change=-1，
--        remain_after = 总节数 - 到该节课为止的第几节，与课包 remain 对得上
--    两张表都先 SET @x = COUNT(*) 判空，重复执行不会叠加
-- ------------------------------------------------------------------------------
-- 9.1 买课记录
SET @has = (SELECT COUNT(*) FROM member_session_log WHERE package_id = @pkg_m1 AND type = 'PURCHASE');
INSERT INTO member_session_log
    (member_id, package_id, type, change_sessions, remain_after, remark, operator_id, create_time)
SELECT @mid_m1, @pkg_m1, 'PURCHASE', p.total_sessions, p.total_sessions,
       CONCAT('购买「基础私教」', p.total_sessions, ' 节，金额 ', p.total_price, ' 元'),
       @uid_admin, '2026-10-20 10:00:00'
FROM member_package p WHERE p.id = @pkg_m1 AND @has = 0;

SET @has = (SELECT COUNT(*) FROM member_session_log WHERE package_id = @pkg_m2 AND type = 'PURCHASE');
INSERT INTO member_session_log
    (member_id, package_id, type, change_sessions, remain_after, remark, operator_id, create_time)
SELECT @mid_m2, @pkg_m2, 'PURCHASE', p.total_sessions, p.total_sessions,
       CONCAT('购买「基础私教」', p.total_sessions, ' 节，金额 ', p.total_price, ' 元'),
       @uid_admin, '2026-09-10 16:30:00'
FROM member_package p WHERE p.id = @pkg_m2 AND @has = 0;

SET @has = (SELECT COUNT(*) FROM member_session_log WHERE package_id = @pkg_m3 AND type = 'PURCHASE');
INSERT INTO member_session_log
    (member_id, package_id, type, change_sessions, remain_after, remark, operator_id, create_time)
SELECT @mid_m3, @pkg_m3, 'PURCHASE', p.total_sessions, p.total_sessions,
       CONCAT('购买「力量增肌」', p.total_sessions, ' 节，金额 ', p.total_price, ' 元'),
       @uid_admin, '2026-11-01 19:30:00'
FROM member_package p WHERE p.id = @pkg_m3 AND @has = 0;

-- 9.2 消课记录（签到自动扣课，operator_id 留空）
SET @has = (SELECT COUNT(*) FROM member_session_log WHERE package_id = @pkg_m1 AND type = 'CONSUME');
INSERT INTO member_session_log
    (member_id, package_id, booking_id, course_id, coach_id, type,
     change_sessions, remain_after, remark, create_time)
SELECT b.member_id, b.package_id, b.id, b.course_id, b.coach_id, 'CONSUME', -1,
       p.total_sessions - (SELECT COUNT(*) FROM booking b2
                           WHERE b2.package_id = b.package_id AND b2.status = 'COMPLETED'
                             AND b2.start_time <= b.start_time),
       '上课签到自动扣课', b.start_time
FROM booking b JOIN member_package p ON p.id = b.package_id
WHERE b.package_id = @pkg_m1 AND b.status = 'COMPLETED' AND @has = 0
ORDER BY b.start_time;

SET @has = (SELECT COUNT(*) FROM member_session_log WHERE package_id = @pkg_m2 AND type = 'CONSUME');
INSERT INTO member_session_log
    (member_id, package_id, booking_id, course_id, coach_id, type,
     change_sessions, remain_after, remark, create_time)
SELECT b.member_id, b.package_id, b.id, b.course_id, b.coach_id, 'CONSUME', -1,
       p.total_sessions - (SELECT COUNT(*) FROM booking b2
                           WHERE b2.package_id = b.package_id AND b2.status = 'COMPLETED'
                             AND b2.start_time <= b.start_time),
       '上课签到自动扣课', b.start_time
FROM booking b JOIN member_package p ON p.id = b.package_id
WHERE b.package_id = @pkg_m2 AND b.status = 'COMPLETED' AND @has = 0
ORDER BY b.start_time;

SET @has = (SELECT COUNT(*) FROM member_session_log WHERE package_id = @pkg_m3 AND type = 'CONSUME');
INSERT INTO member_session_log
    (member_id, package_id, booking_id, course_id, coach_id, type,
     change_sessions, remain_after, remark, create_time)
SELECT b.member_id, b.package_id, b.id, b.course_id, b.coach_id, 'CONSUME', -1,
       p.total_sessions - (SELECT COUNT(*) FROM booking b2
                           WHERE b2.package_id = b.package_id AND b2.status = 'COMPLETED'
                             AND b2.start_time <= b.start_time),
       '上课签到自动扣课', b.start_time
FROM booking b JOIN member_package p ON p.id = b.package_id
WHERE b.package_id = @pkg_m3 AND b.status = 'COMPLETED' AND @has = 0
ORDER BY b.start_time;

-- ------------------------------------------------------------------------------
-- 10. 会员评价教练（uk_booking 兜底幂等，取最近一节课各一条）
-- ------------------------------------------------------------------------------
INSERT IGNORE INTO coach_review (member_id, coach_id, booking_id, rating)
SELECT b.member_id, b.coach_id, b.id, 5
FROM booking b
WHERE b.package_id = @pkg_m1 AND b.status = 'COMPLETED'
ORDER BY b.start_time DESC LIMIT 1;

INSERT IGNORE INTO coach_review (member_id, coach_id, booking_id, rating)
SELECT b.member_id, b.coach_id, b.id, 4
FROM booking b
WHERE b.package_id = @pkg_m3 AND b.status = 'COMPLETED'
ORDER BY b.start_time DESC LIMIT 1;

-- ==============================================================================
-- 11. 执行后核对
-- ==============================================================================
-- 11.1 账号 + 档案（应 5 行：1 管理员 + 1 教练账号 + 3 会员）
SELECT u.id, u.phone, u.role, u.status,
       COALESCE(m.name, c.name, '-') AS profile_name,
       mc.name AS bound_coach
FROM sys_user u
LEFT JOIN member m ON m.user_id = u.id
LEFT JOIN coach c ON c.user_id = u.id
LEFT JOIN coach mc ON mc.id = m.coach_id
WHERE u.phone IN ('13700000003','13700000004','13800000001','13900000002','15259996010')
ORDER BY FIELD(u.role, 'ADMIN', 'COACH', 'MEMBER'), u.phone;

-- 11.2 课包与流水对账：purchase_cnt 应为 1，consume_cnt > 0，
--      calc_remain = total - consume_cnt 应等于 remain_sessions
SELECT m.name AS member_name, ct.name AS course_name,
       p.total_sessions, p.remain_sessions,
       p.total_sessions - p.remain_sessions AS used,
       SUM(l.type = 'PURCHASE') AS purchase_cnt,
       SUM(l.type = 'CONSUME')  AS consume_cnt,
       SUM(l.change_sessions)   AS net_change
FROM member_package p
JOIN member m ON m.id = p.member_id
JOIN course_template ct ON ct.id = p.course_id
LEFT JOIN member_session_log l ON l.package_id = p.id
GROUP BY p.id, m.name, ct.name, p.total_sessions, p.remain_sessions
ORDER BY p.id;

-- 11.3 预约分布（COMPLETED 11 条 / BOOKED 2 条 / CANCELED 2 条）
SELECT status, COUNT(*) AS cnt, MIN(start_time) AS first_time, MAX(start_time) AS last_time
FROM booking GROUP BY status;

-- 11.4 教练请假
SELECT c.name AS coach_name, l.start_time, l.end_time, l.reason
FROM coach_leave l JOIN coach c ON c.id = l.coach_id
ORDER BY l.start_time;
