-- 2026-11-22 会员对教练的五星满意度评价
-- 项目无 Flyway/Liquibase，需手工在目标库执行本脚本（幂等，可重复执行）

CREATE TABLE IF NOT EXISTS coach_review (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id   BIGINT  NOT NULL COMMENT 'member.id',
    coach_id    BIGINT  NOT NULL COMMENT 'coach.id',
    booking_id  BIGINT  NOT NULL COMMENT 'booking.id，一次课只能评一次',
    rating      TINYINT NOT NULL COMMENT '满意度 1~5 星',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_booking (booking_id),
    KEY idx_coach_time (coach_id, create_time),
    KEY idx_member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会员对教练的满意度评价';

-- 执行后核对（只读）
-- 1) 每个教练的评分汇总
-- SELECT c.id, c.name, COUNT(r.id) AS review_count, ROUND(AVG(r.rating), 1) AS avg_rating
-- FROM coach c LEFT JOIN coach_review r ON r.coach_id = c.id
-- GROUP BY c.id, c.name ORDER BY avg_rating DESC;
--
-- 2) 同一预约不应出现两条评价（唯一键已兜底，正常情况下无结果）
-- SELECT booking_id, COUNT(*) FROM coach_review GROUP BY booking_id HAVING COUNT(*) > 1;

-- 回滚
-- DROP TABLE IF EXISTS coach_review;
