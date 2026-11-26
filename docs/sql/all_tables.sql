-- ==============================================================================
-- 私教工作室管理系统 · 全量建库脚本（all_tables.sql）
-- 生成日期：2026-11-25
--
-- 用途：在**全新且空的 MySQL 库**中一次性建好所有表（结构 + 索引），
--       已包含 docs/sql/ 下所有增量脚本的最终结果（booking 生成列、legacy 导入表、
--       member_session_log、coach_review），新库不需要再执行历史 ALTER 脚本。
--
-- 执行方式：
--   mysql -u root -p -e "CREATE DATABASE jianshenfang DEFAULT CHARACTER SET utf8mb4"
--   mysql -u root -p jianshenfang < all_tables.sql
--
-- 说明：
--   1. 全部为 CREATE TABLE IF NOT EXISTS，可重复执行，不会删除数据。
--   2. 表之间没有数据库级外键，顺序不影响执行。
--   3. 项目无 Flyway/Liquibase，本脚本是「新库」的唯一入口；老库升级仍按
--      docs/sql/*.sql 里的增量脚本处理。
--   4. 本脚本只建表，不含初始化数据；初始管理员账号与演示数据见文件末尾说明。
--   5. 表清单（22 张）：
--      sys_user / member / coach / coach_qualification / coach_leave /
--      course_template / member_package / legacy_import_batch / legacy_import_row /
--      booking / notify_log / operation_log / media / media_comment /
--      moment_daily_quota / wechat_account / member_claim_code / wechat_temp_token /
--      wechat_claim_request / wechat_notify_log / member_session_log / coach_review
-- ==============================================================================

SET NAMES utf8mb4;

-- 私教工作室管理系统 · 会员端 V1 表结构（幂等，可重复执行）
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone       VARCHAR(20)  NOT NULL,
    password    VARCHAR(100) NOT NULL,
    role        VARCHAR(20)  NOT NULL COMMENT 'ADMIN/COACH/MEMBER',
    status      TINYINT      NOT NULL DEFAULT 1,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '账号';

CREATE TABLE IF NOT EXISTS member (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    name        VARCHAR(30)  NOT NULL,
    phone       VARCHAR(20)  NOT NULL,
    gender      TINYINT      NULL COMMENT '1男 2女',
    birthday    DATE         NULL,
    coach_id    BIGINT       NULL COMMENT '1V1 绑定的专属教练',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user (user_id),
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会员档案';

CREATE TABLE IF NOT EXISTS coach (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NULL COMMENT '教练登录账号（本期会员端不使用）',
    name        VARCHAR(30)  NOT NULL,
    photo       VARCHAR(255) NULL,
    intro       TEXT         NULL,
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1在职 0停用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '教练';

CREATE TABLE IF NOT EXISTS coach_qualification (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    coach_id    BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    cert_no     VARCHAR(50)  NOT NULL,
    issuer      VARCHAR(100) NULL,
    image       VARCHAR(255) NULL,
    expire_date DATE         NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_coach_cert (coach_id, cert_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '教练资质';

CREATE TABLE IF NOT EXISTS coach_leave (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    coach_id    BIGINT       NOT NULL,
    start_time  DATETIME     NOT NULL,
    end_time    DATETIME     NOT NULL,
    reason      VARCHAR(200) NULL,
    status      TINYINT      NOT NULL DEFAULT 1,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_coach_range (coach_id, start_time, end_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '教练请假';

CREATE TABLE IF NOT EXISTS course_template (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(50) NOT NULL,
    duration_min     INT         NOT NULL DEFAULT 60,
    default_sessions INT         NOT NULL DEFAULT 30,
    status           TINYINT     NOT NULL DEFAULT 1,
    create_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '课程模板（无价格、无固定排期）';

CREATE TABLE IF NOT EXISTS member_package (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id       BIGINT        NOT NULL,
    course_id       BIGINT        NOT NULL,
    coach_id        BIGINT        NULL COMMENT '课包归属教练（旧数据迁移用，可空）',
    total_sessions  INT           NOT NULL,
    remain_sessions INT           NOT NULL,
    total_price     DECIMAL(10,2) NOT NULL,
    start_date      DATE          NOT NULL,
    expire_date     DATE          NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    source          VARCHAR(20)   NULL COMMENT 'NEW / LEGACY_IMPORT',
    import_batch_id BIGINT        NULL COMMENT '旧数据迁移批次',
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_member_course (member_id, course_id),
    KEY idx_pkg_batch (import_batch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会员课包';

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

CREATE TABLE IF NOT EXISTS booking (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id    BIGINT       NOT NULL,
    coach_id     BIGINT       NOT NULL,
    course_id    BIGINT       NOT NULL,
    package_id   BIGINT       NOT NULL,
    start_time   DATETIME     NOT NULL,
    end_time     DATETIME     NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'BOOKED' COMMENT 'BOOKED/CHECKED_IN/COMPLETED/CANCELED',
    cancel_by    VARCHAR(20)  NULL,
    cancel_reason VARCHAR(200) NULL,
    canceled_at  DATETIME     NULL,
    checked_in_at DATETIME    NULL,
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active_start_time DATETIME GENERATED ALWAYS AS (CASE WHEN status = 'CANCELED' THEN NULL ELSE start_time END) STORED,
    UNIQUE KEY uk_coach_slot (coach_id, active_start_time),
    KEY idx_member_start (member_id, start_time),
    KEY idx_status_start (status, start_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '预约记录';

CREATE TABLE IF NOT EXISTS notify_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    scene       VARCHAR(30)  NOT NULL COMMENT 'BOOKING_CREATED/BOOKING_CANCELED/AUTO_CHECKIN/PACKAGE_ABNORMAL',
    receiver    VARCHAR(50)  NOT NULL COMMENT '接收方手机号',
    biz_id      BIGINT       NULL,
    content     VARCHAR(500) NOT NULL,
    status      VARCHAR(10)  NOT NULL DEFAULT 'SUCCESS',
    fail_reason VARCHAR(200) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '通知发送记录（公众号模板消息占位）';

CREATE TABLE IF NOT EXISTS operation_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_id BIGINT       NULL COMMENT '操作人 sys_user.id',
    operator_role VARCHAR(20) NULL,
    action      VARCHAR(40)  NOT NULL COMMENT 'ADJUST_SESSIONS/CANCEL_BOOKING/MANUAL_CHECKIN/REBIND_COACH/...',
    target_type VARCHAR(20)  NULL,
    target_id   BIGINT       NULL,
    remark      VARCHAR(300) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_op_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '敏感操作日志';

-- ===== 会员风采（本期仅图片） =====
CREATE TABLE IF NOT EXISTS media (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id       BIGINT       NOT NULL COMMENT '发布会员 member.id',
    author_name     VARCHAR(30)  NOT NULL COMMENT '冗余展示名',
    title           VARCHAR(200) NULL COMMENT '配文，≤200 字',
    type            VARCHAR(10)  NOT NULL DEFAULT 'IMAGE' COMMENT '本期固定 IMAGE',
    url             VARCHAR(255) NOT NULL COMMENT '大图相对路径',
    thumb_url       VARCHAR(255) NOT NULL COMMENT '缩略图相对路径',
    width           INT          NOT NULL,
    height          INT          NOT NULL,
    source_size     BIGINT       NOT NULL COMMENT '原图字节',
    output_size     BIGINT       NOT NULL COMMENT '压缩后字节',
    source_format   VARCHAR(10)  NULL COMMENT 'JPEG/PNG/WEBP',
    batch_id        VARCHAR(36)  NULL COMMENT '前端一次选图批次，便于逐张定位打回',
    upload_day      DATE         NOT NULL COMMENT 'Asia/Shanghai 自然日',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/DELETED',
    reject_reason   VARCHAR(200) NULL,
    review_by       BIGINT       NULL,
    review_at       DATETIME     NULL,
    audit_status    VARCHAR(20)  NOT NULL DEFAULT 'SKIPPED' COMMENT 'SKIPPED/PASS/REVIEW/BLOCK/ERROR',
    audit_suggestion VARCHAR(20) NULL COMMENT '腾讯云 Suggestion: Pass/Review/Block',
    audit_label     VARCHAR(50)  NULL,
    audit_score     INT          NULL,
    audit_request_id VARCHAR(64) NULL,
    audit_at        DATETIME     NULL,
    comment_count   INT          NOT NULL DEFAULT 0,
    duration        INT          NULL COMMENT '本期不用，保留 NULL',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_status_day (status, upload_day, id),
    KEY idx_member_day (member_id, upload_day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会员风采图片';

CREATE TABLE IF NOT EXISTS media_comment (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    media_id         BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL COMMENT 'sys_user.id',
    author_role      VARCHAR(20)  NOT NULL,
    author_name      VARCHAR(30)  NOT NULL COMMENT '冗余展示名',
    root_id          BIGINT       NULL COMMENT '一级评论 id；一级评论为 NULL',
    parent_id        BIGINT       NULL COMMENT '被回复的评论 id',
    reply_to_user_id BIGINT       NULL,
    reply_to_name    VARCHAR(30)  NULL,
    content          VARCHAR(200) NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/DELETED',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_media_root (media_id, root_id, id),
    KEY idx_user_time (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '风采评论/回复';

CREATE TABLE IF NOT EXISTS moment_daily_quota (
    member_id   BIGINT   NOT NULL,
    upload_day  DATE     NOT NULL,
    used_count  TINYINT  NOT NULL DEFAULT 0,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (member_id, upload_day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '风采每日额度计数';

-- ===== 微信公众号入口：身份绑定 / 认领码 / 临时票据 / 待核实申请 =====
-- 说明：全部为新增独立表，不改 sys_user / member 的既有列与唯一约束，
--       CREATE TABLE IF NOT EXISTS 对已有库可安全重复执行。

CREATE TABLE IF NOT EXISTS wechat_account (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL COMMENT 'sys_user.id（教练建档时已存在）',
    appid       VARCHAR(64)  NOT NULL,
    openid      VARCHAR(64)  NOT NULL,
    unionid     VARCHAR(64)  NULL,
    avatar_url  VARCHAR(255) NULL COMMENT '转存到本站 /upload/avatars 后的相对路径',
    nickname    VARCHAR(64)  NULL,
    subscribed  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否关注服务号（模板消息前提）',
    subscribed_at   DATETIME NULL,
    unsubscribed_at DATETIME NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user (user_id),
    UNIQUE KEY uk_appid_openid (appid, openid),
    KEY idx_unionid (unionid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '微信身份绑定';

CREATE TABLE IF NOT EXISTS member_claim_code (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id   BIGINT      NOT NULL COMMENT 'member.id',
    code        VARCHAR(16) NOT NULL COMMENT '6 位认领码，线下交付',
    expire_at   DATETIME    NOT NULL,
    fail_count  INT         NOT NULL DEFAULT 0,
    used_at     DATETIME    NULL,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_member (member_id),
    KEY idx_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会员认领码（不接短信时的线下凭证）';

CREATE TABLE IF NOT EXISTS wechat_temp_token (
    token       VARCHAR(64) NOT NULL PRIMARY KEY,
    type        VARCHAR(16) NOT NULL COMMENT 'STATE / CLAIM_TICKET',
    payload     VARCHAR(1000) NULL COMMENT 'JSON：redirect 或 openid/unionid/avatar/nickname/userId',
    expire_at   DATETIME    NOT NULL,
    used_at     DATETIME    NULL,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_type_expire (type, expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '微信授权 state 与一次性认领票据';

CREATE TABLE IF NOT EXISTS wechat_claim_request (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    appid       VARCHAR(64)  NULL,
    openid      VARCHAR(64)  NOT NULL,
    unionid     VARCHAR(64)  NULL,
    avatar_url  VARCHAR(255) NULL,
    nickname    VARCHAR(64)  NULL,
    phone_input VARCHAR(20)  NOT NULL COMMENT '会员填入但档案中不存在的手机号',
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/HANDLED/REJECTED',
    handle_note VARCHAR(200) NULL,
    handled_by  BIGINT       NULL COMMENT 'sys_user.id',
    handled_at  DATETIME     NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_status_time (status, create_time),
    KEY idx_openid (openid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '手机号未命中的待核实认领申请';

-- ===== 公众号模板消息发送记录（Q9~Q11） =====
CREATE TABLE IF NOT EXISTS wechat_notify_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    dedup_key    VARCHAR(120) NOT NULL COMMENT 'scene:bizId:openid，保证同一事件只发一次',
    scene        VARCHAR(30)  NOT NULL COMMENT 'BOOKING_CREATED/BOOKING_CANCELED/BOOKING_REMINDER',
    openid       VARCHAR(64)  NULL,
    member_id    BIGINT       NULL,
    biz_id       BIGINT       NULL COMMENT '预约 id',
    template_id  VARCHAR(64)  NULL,
    msgid        VARCHAR(64)  NULL,
    errcode      INT          NULL,
    errmsg       VARCHAR(200) NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'SUCCESS/FAILED/SKIPPED',
    retry_count  INT          NOT NULL DEFAULT 0,
    content      VARCHAR(500) NULL,
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_dedup (dedup_key),
    KEY idx_scene_time (scene, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '公众号模板消息发送与幂等记录';

-- ===== 会员课包课时流水（消费记录） =====
-- 所有改动 member_package.remain_sessions 的业务都应写一条流水：
--   CONSUME 上课签到扣减 / PURCHASE 购买或续购 / ADJUST 管理端调整 / IMPORT 旧数据导入
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

-- ===== 会员对教练的五星满意度评价 =====
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

-- 注意：项目无 Flyway/Liquibase，CREATE TABLE IF NOT EXISTS 不会给已存在的表加列。
-- 若 dev 库在加 subscribed 字段之前已初始化过 wechat_account，需手工执行一次：
-- ALTER TABLE wechat_account
--   ADD COLUMN subscribed TINYINT NOT NULL DEFAULT 0 COMMENT '是否关注服务号',
--   ADD COLUMN subscribed_at DATETIME NULL,
--   ADD COLUMN unsubscribed_at DATETIME NULL;

-- ==============================================================================
-- 九、初始化数据说明（本脚本不含 INSERT，按需选择）
-- ==============================================================================
-- 本脚本只建表。项目没有任何管理员时无法登录后台，必须先准备一个 ADMIN 账号
-- （sys_user.password 存 BCrypt 哈希，不是明文）。
--
-- 方式 A（推荐，本地 / 测试环境）：
--   1) 导入演示数据：mysql -u root -p jianshenfang < data.sql
--      data.sql 里的密码是占位符 RESET_ME；
--   2) 用 dev profile 启动一次后端，DevDemoInitializer 会把 RESET_ME 改成 123456，
--      之后可用 13700000003 / 123456 登录（管理员，兼教练）。
--
-- 方式 B（生产环境）：
--   1) 用任意 BCrypt(强度 10) 工具生成密码哈希，例如 Java：
--      new BCryptPasswordEncoder().encode("你的密码")
--      或 https://bcrypt-generator.com/ （强度选 10，$2a 前缀）；
--   2) 手工插入管理员：
--      INSERT INTO sys_user (phone, password, role, status)
--      VALUES ('13xxxxxxxxx', '<替换为BCrypt哈希>', 'ADMIN', 1);
--
-- 执行后核对（应返回 22 行）：
--   SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES
--   WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME;
-- ==============================================================================
