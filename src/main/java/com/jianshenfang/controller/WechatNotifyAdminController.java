package com.jianshenfang.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.auth.AuthUtil;
import com.jianshenfang.common.Result;
import com.jianshenfang.dto.WechatDtos;
import com.jianshenfang.entity.WechatNotifyLog;
import com.jianshenfang.mapper.WechatNotifyLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 前台对账用：查看模板消息发送结果（成功/跳过/失败） */
@RestController
@RequestMapping("/api/admin/wechat/notify-logs")
@RequiredArgsConstructor
public class WechatNotifyAdminController {

    private final WechatNotifyLogMapper notifyLogMapper;

    @GetMapping
    public Result<List<WechatDtos.NotifyLogVO>> list(@RequestParam(required = false) String scene,
                                                     @RequestParam(defaultValue = "50") int limit) {
        AuthUtil.requireStaff();
        List<WechatNotifyLog> rows = notifyLogMapper.selectList(new LambdaQueryWrapper<WechatNotifyLog>()
                .eq(scene != null && !scene.isBlank(), WechatNotifyLog::getScene, scene)
                .orderByDesc(WechatNotifyLog::getCreateTime)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
        return Result.ok(rows.stream().map(r -> new WechatDtos.NotifyLogVO(
                r.getId(), r.getScene(), r.getOpenid(), r.getStatus(),
                r.getErrcode(), r.getErrmsg(), r.getContent(), r.getCreateTime())).toList());
    }
}
