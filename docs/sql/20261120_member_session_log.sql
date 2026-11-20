-- 2026-11-20 会员课包课时流水（消费记录）
-- 项目无 Flyway/Liquibase，需手工在目标库执行本脚本（幂等，可重复执行）

CREATE TABLE IF NOT EXISTS member_session_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id       BIGINT       NOT NULL COMMENT 'member.id',
    package_id      BIGINT       NULL COMMENT 'member_package.id',
    booking_id      BIGINT       NULL COMMENT 'booking.id（签到扣课关联）',
    course_id       BIGINT       NULL COMMENT 'course_template.id',
    coach_id        BIGINT       NULL,
    type            VARCHAR(20)  NOT NULL COMMENT 'CONSUME/PURCHASE/ADJUST/IMPORT',
    change_sessions INT          NOT NULL COMMENT '正数增加，负数扣减',
    remain_after    INT          NULL COMMENT '变动后剩余课时，历史回填可能为空',
    remark          VARCHAR(200) NULL,
    operator_id     BIGINT       NULL COMMENT '操作人 sys_user.id，会员自助场景为空',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_member_time (member_id, create_time),
    KEY idx_package (package_id),
    KEY idx_booking (booking_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会员课包课时流水';

-- ===== 可选：历史消课回填 =====
-- 上线前的已签到/已完成预约没有流水，可按下面语句补 CONSUME 记录。
-- remain_after 留空（历史无法还原当时的剩余课时），页面会显示「—」。
-- 重复执行安全：靠 booking_id + type 去重。
INSERT INTO member_session_log
    (member_id, package_id, booking_id, course_id, coach_id, type, change_sessions, remain_after, remark, create_time)
SELECT b.member_id, b.package_id, b.id, b.course_id, b.coach_id,
       'CONSUME', -1, NULL, '历史数据回填（上线前已消课）',
       IFNULL(b.checked_in_at, b.start_time)
FROM booking b
WHERE b.status IN ('CHECKED_IN', 'COMPLETED')
  AND b.package_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM member_session_log l
      WHERE l.booking_id = b.id AND l.type = 'CONSUME'
  );

-- 回填后核对：同一预约不应出现两条 CONSUME
-- SELECT booking_id, COUNT(*) FROM member_session_log
-- WHERE booking_id IS NOT NULL GROUP BY booking_id HAVING COUNT(*) > 1;
