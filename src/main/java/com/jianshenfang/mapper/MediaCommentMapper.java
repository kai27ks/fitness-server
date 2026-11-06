package com.jianshenfang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jianshenfang.entity.MediaComment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface MediaCommentMapper extends BaseMapper<MediaComment> {

    /** 删除图片时，其下评论一并软删（保留楼中楼结构） */
    @Update("UPDATE media_comment SET status = 'DELETED', content = '该评论已删除' WHERE media_id = #{mediaId}")
    int softDeleteByMedia(@Param("mediaId") Long mediaId);
}
