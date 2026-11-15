-- ==============================================================================
-- 旧会员资料迁移（方案 2：课包补教练 + 来源批次 + 原始行归档）
-- 日期：2026-11-15
--
-- 说明：
--   1. member_package 增加 coach_id（卡→教练）、source（来源）、import_batch_id（批次）
--   2. 新增 legacy_import_batch / legacy_import_row 两张归档表
--   3. 唯一键 uk_member_course(member_id, course_id) 保持不变（导入时按会员+课种合并）
--   4. 教练必须提前在「教练管理」录入，导入按教练名称对照，不做自动创建
-- 执行顺序：先执行本脚本，再部署后端，最后在管理端跑导入
-- ==============================================================================

-- 1) 会员课包：归属教练
ALTER TABLE member_package
    ADD COLUMN coach_id BIGINT NULL COMMENT '课包归属教练（旧数据迁移用，可空）' AFTER course_id;

-- 2) 会员课包：来源与批次（用于区分历史卡、支持按批次回滚）
ALTER TABLE member_package
    ADD COLUMN source VARCHAR(20) NULL COMMENT 'NEW / LEGACY_IMPORT' AFTER status;

ALTER TABLE member_package
    ADD COLUMN import_batch_id BIGINT NULL COMMENT '旧数据迁移批次' AFTER source;

ALTER TABLE member_package
    ADD KEY idx_pkg_batch (import_batch_id);

-- 3) 导入批次
CREATE TABLE IF NOT EXISTS legacy_import_batch (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_name      VARCHAR(120) NULL,
    total_rows     INT          NOT NULL DEFAULT 0,
    member_count   INT          NOT NULL DEFAULT 0,
    package_count  INT          NOT NULL DEFAULT 0,
    skipped_count  INT          NOT NULL DEFAULT 0,
    failed_count   INT          NOT NULL DEFAULT 0,
    operator_id    BIGINT       NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '旧会员资料导入批次';

-- 4) 原始行归档（Excel 有什么存什么，失败行也留档）
CREATE TABLE IF NOT EXISTS legacy_import_row (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id       BIGINT        NOT NULL,
    row_no         INT           NOT NULL,
    phone          VARCHAR(20)   NULL,
    name           VARCHAR(30)   NULL,
    coach_name     VARCHAR(30)   NULL,
    card_name      VARCHAR(50)   NULL,
    course_type    VARCHAR(50)   NULL,
    remain         INT           NULL,
    card_no        VARCHAR(40)   NULL,
    pay_type       VARCHAR(20)   NULL,
    sales_name     VARCHAR(30)   NULL,
    source_channel VARCHAR(20)   NULL,
    remark         VARCHAR(255)  NULL,
    member_id      BIGINT        NULL COMMENT '映射到的会员',
    package_id     BIGINT        NULL COMMENT '映射到的课包',
    status         VARCHAR(20)   NOT NULL DEFAULT 'OK' COMMENT 'OK / SKIPPED / FAILED',
    err_msg        VARCHAR(255)  NULL,
    KEY idx_batch (batch_id),
    KEY idx_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '旧会员资料原始行归档';

-- ==============================================================================
-- 回滚（确认不需要迁移数据时执行，注意先备份）
-- ==============================================================================
-- DELETE FROM member_package WHERE import_batch_id = ?;   -- 按批次删课包
-- ALTER TABLE member_package DROP INDEX idx_pkg_batch;
-- ALTER TABLE member_package DROP COLUMN import_batch_id;
-- ALTER TABLE member_package DROP COLUMN source;
-- ALTER TABLE member_package DROP COLUMN coach_id;
-- DROP TABLE IF EXISTS legacy_import_row;
-- DROP TABLE IF EXISTS legacy_import_batch;
