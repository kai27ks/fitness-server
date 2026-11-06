package com.jianshenfang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jianshenfang.entity.Media;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface MediaMapper extends BaseMapper<Media> {

    @Update("UPDATE media SET comment_count = comment_count + 1 WHERE id = #{id}")
    int incrCommentCount(@Param("id") Long id);

    @Update("UPDATE media SET comment_count = GREATEST(comment_count - 1, 0) WHERE id = #{id}")
    int decrCommentCount(@Param("id") Long id);
}
