package com.jianshenfang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jianshenfang.entity.Booking;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface BookingMapper extends BaseMapper<Booking> {

    /**
     * 到点自动签到：CAS 状态流转，幂等防重复扣课
     */
    @Update("UPDATE booking SET status = 'CHECKED_IN', checked_in_at = NOW() WHERE id = #{id} AND status = 'BOOKED'")
    int casCheckIn(@Param("id") Long id);

    /**
     * 课程结束：已签到 -> 已完成
     */
    @Update("UPDATE booking SET status = 'COMPLETED' WHERE id = #{id} AND status = 'CHECKED_IN' AND end_time <= NOW()")
    int casComplete(@Param("id") Long id);

    /**
     * 取消预约：CAS 保证状态合法
     */
    @Update("UPDATE booking SET status = 'CANCELED', cancel_by = #{by}, cancel_reason = #{reason}, canceled_at = NOW() " +
            "WHERE id = #{id} AND status = 'BOOKED'")
    int casCancel(@Param("id") Long id, @Param("by") String by, @Param("reason") String reason);
}
