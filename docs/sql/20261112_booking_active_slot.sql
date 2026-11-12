-- 已有 MySQL 8 数据库执行一次；保留取消历史，允许取消后重新预约同一教练的原时段。
-- 旧的 uk_coach_slot(coach_id,start_time) 包含已取消预约，会把取消后的空闲时段也判为重复。
-- 新库已在 schema.sql 中使用此结构，无需执行本文件。
ALTER TABLE booking
    ADD COLUMN active_start_time DATETIME
        GENERATED ALWAYS AS (CASE WHEN status = 'CANCELED' THEN NULL ELSE start_time END) STORED,
    DROP INDEX uk_coach_slot,
    ADD UNIQUE KEY uk_coach_slot (coach_id, active_start_time);
