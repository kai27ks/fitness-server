-- 演示数据（INSERT IGNORE 幂等）。密码占位 RESET_ME，由 DevDemoInitializer 启动时改为 123456
-- 账号：13800000001/13900000002 会员；13700000003 管理员（陈筱鹭，兼教练）；13700000004 教练（王毅峰）
INSERT IGNORE INTO sys_user (id, phone, password, role, status) VALUES
 (1, '13800000001', 'RESET_ME', 'MEMBER', 1),
 (2, '13900000002', 'RESET_ME', 'MEMBER', 1),
 (3, '13700000003', 'RESET_ME', 'ADMIN', 1),
 (4, '13700000004', 'RESET_ME', 'COACH', 1);

INSERT IGNORE INTO coach (id, name, photo, intro, status) VALUES
 (1, '陈筱鹭', NULL, '6 年一对一私教经验，累计授课 8000+ 节。擅长体态矫正、产后恢复与增肌减脂，主张先建立动作模式再上强度，带训风格细致耐心。', 1),
 (2, '王毅峰', NULL, '拉伸与运动康复方向，注重动作模式重建与疼痛缓解，课程节奏温和。', 1),
 (3, '林珊',   NULL, '美臀塑形与女性体态管理，强度循序渐进，兼顾趣味性。', 1),
 (4, '徐正週', NULL, '力量训练与增肌专家，重视渐进超负荷与训练安全。', 1);

-- 教练登录账号关联（陈筱鹭管理员账号 3 / 王毅峰教练账号 4）
UPDATE coach SET user_id = 3 WHERE id = 1 AND user_id IS NULL;
UPDATE coach SET user_id = 4 WHERE id = 2 AND user_id IS NULL;

INSERT IGNORE INTO course_template (id, name, duration_min, default_sessions, status) VALUES
 (1, '基础私教', 60, 30, 1),
 (2, '拉伸课程', 60, 20, 1),
 (3, '美臀塑形', 60, 30, 1),
 (4, '力量增肌', 60, 30, 1);

INSERT IGNORE INTO member (id, user_id, name, phone, gender, birthday, coach_id) VALUES
 (1, 1, '毛文坊', '13800000001', 1, '1995-06-18', 1),
 (2, 2, '陈忠辛', '13900000002', 1, '1992-02-10', 1);

INSERT IGNORE INTO coach_qualification (coach_id, name, cert_no, issuer, expire_date) VALUES
 (1, 'ACE 私人教练认证', 'ACE-TR-82317', '美国运动委员会', '2028-05-31'),
 (1, 'FMS 功能性动作筛查 Level 2', 'FMS-L2-1145', 'FMS', '2029-01-31'),
 (2, 'NASM-CPT 私人教练认证', 'NASM-88231', '美国国家运动医学院', '2027-12-31'),
 (3, 'CBBA 高级私人教练', 'CBBA-2023-7741', '中国健美协会', '2027-06-30'),
 (4, 'NSCA-CSCS 体能训练专家', 'NSC-55617', '美国国家体能协会', '2028-03-31');

INSERT IGNORE INTO member_package (member_id, course_id, total_sessions, remain_sessions, total_price, start_date, expire_date, status) VALUES
 (1, 1, 30, 18, 9600.00, '2026-10-20', '2027-03-01', 'ACTIVE'),
 (1, 2, 20, 0, 5600.00, '2026-08-01', '2026-12-31', 'ACTIVE'),
 (2, 1, 30, 26, 9600.00, '2026-09-10', '2027-01-15', 'ACTIVE');

-- 固定未来日期的示例预约/请假（避免重启重复插入）
INSERT IGNORE INTO booking (member_id, coach_id, course_id, package_id, start_time, end_time, status) VALUES
 (1, 1, 1, 1, '2026-12-01 14:00:00', '2026-12-01 15:00:00', 'BOOKED'),
 (2, 1, 1, 3, '2026-12-01 15:00:00', '2026-12-01 16:00:00', 'BOOKED');

INSERT IGNORE INTO coach_leave (coach_id, start_time, end_time, reason) VALUES
 (1, '2026-12-02 09:00:00', '2026-12-02 12:00:00', '外出培训');
