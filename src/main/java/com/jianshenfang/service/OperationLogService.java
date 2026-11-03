package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.entity.OperationLog;
import com.jianshenfang.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final OperationLogMapper logMapper;

    public void log(String action, String targetType, Long targetId, String remark) {
        try {
            OperationLog log = new OperationLog();
            log.setOperatorId(AuthContext.userId());
            log.setOperatorRole(AuthContext.role());
            log.setAction(action);
            log.setTargetType(targetType);
            log.setTargetId(targetId);
            log.setRemark(remark);
            log.setCreateTime(LocalDateTime.now());
            logMapper.insert(log);
        } catch (Exception e) {
            // 日志写入失败不影响主流程
        }
    }

    public List<OperationLog> recent(int limit) {
        return logMapper.selectList(new LambdaQueryWrapper<OperationLog>()
                .orderByDesc(OperationLog::getCreateTime).last("LIMIT " + limit));
    }
}
