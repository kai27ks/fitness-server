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
    total_sessions  INT           NOT NULL,
    remain_sessions INT           NOT NULL,
    total_price     DECIMAL(10,2) NOT NULL,
    start_date      DATE          NOT NULL,
    expire_date     DATE          NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_member_course (member_id, course_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会员课包';

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
    UNIQUE KEY uk_coach_slot (coach_id, start_time),
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
