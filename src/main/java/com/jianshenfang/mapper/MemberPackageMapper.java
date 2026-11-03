package com.jianshenfang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jianshenfang.entity.MemberPackage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface MemberPackageMapper extends BaseMapper<MemberPackage> {

    /** 扣减 1 节课时（条件更新防超扣） */
    @Update("UPDATE member_package SET remain_sessions = remain_sessions - 1 WHERE id = #{id} AND remain_sessions > 0")
    int deductOne(@Param("id") Long id);

    /** 区间课包销售额（以购买创建时间计，V1 财务口径） */
    @Select("SELECT IFNULL(SUM(total_price),0) FROM member_package WHERE create_time >= #{start} AND create_time < #{end}")
    BigDecimal sumPrice(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
