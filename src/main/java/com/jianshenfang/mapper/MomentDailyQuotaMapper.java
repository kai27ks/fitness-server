package com.jianshenfang.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 每日额度：靠主键行锁 + 单条原子 SQL 保证并发安全，不使用 Redis。
 * consume 与「落库」放在同一个 @Transactional 方法里，超额抛异常即整体回滚。
 */
public interface MomentDailyQuotaMapper {

    @Insert("INSERT INTO moment_daily_quota(member_id, upload_day, used_count) VALUES (#{memberId}, #{day}, 1) " +
            "ON DUPLICATE KEY UPDATE used_count = used_count + 1")
    void consume(@Param("memberId") Long memberId, @Param("day") String day);

    @Select("SELECT used_count FROM moment_daily_quota WHERE member_id = #{memberId} AND upload_day = #{day}")
    Integer used(@Param("memberId") Long memberId, @Param("day") String day);
}
