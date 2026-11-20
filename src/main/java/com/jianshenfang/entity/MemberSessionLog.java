package com.jianshenfang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 会员课包课时流水：购买 / 消课 / 管理端调整 / 旧数据导入，每笔变动可追溯 */
@Data
@TableName("member_session_log")
public class MemberSessionLog {

    /** 上课签到扣减 */
    public static final String CONSUME = "CONSUME";
    /** 购买或续购课包 */
    public static final String PURCHASE = "PURCHASE";
    /** 管理端手工调整课时 */
    public static final String ADJUST = "ADJUST";
    /** 旧会员数据导入 */
    public static final String IMPORT = "IMPORT";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long memberId;
    private Long packageId;
    /** 签到扣课时关联的预约 */
    private Long bookingId;
    private Long courseId;
    private Long coachId;
    /** CONSUME / PURCHASE / ADJUST / IMPORT */
    private String type;
    /** 正数为增加，负数为扣减 */
    private Integer changeSessions;
    /** 变动后剩余课时；历史回填数据可能为空 */
    private Integer remainAfter;
    private String remark;
    /** 操作人 sys_user.id，会员自助场景为空 */
    private Long operatorId;
    private LocalDateTime createTime;
}
