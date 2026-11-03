package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.AdminDtos;
import com.jianshenfang.entity.CourseTemplate;
import com.jianshenfang.mapper.CourseTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseTemplateMapper courseMapper;

    public List<CourseTemplate> list() {
        return courseMapper.selectList(new LambdaQueryWrapper<CourseTemplate>().orderByAsc(CourseTemplate::getId));
    }

    public CourseTemplate create(AdminDtos.CourseCreate req) {
        CourseTemplate exist = courseMapper.selectOne(new LambdaQueryWrapper<CourseTemplate>().eq(CourseTemplate::getName, req.name()));
        if (exist != null) {
            throw new BusinessException("课程名称已存在");
        }
        CourseTemplate c = new CourseTemplate();
        c.setName(req.name());
        c.setDurationMin(req.durationMin() == null || req.durationMin() < 1 ? 60 : req.durationMin());
        c.setDefaultSessions(req.defaultSessions() == null ? 30 : req.defaultSessions());
        c.setStatus(1);
        courseMapper.insert(c);
        return c;
    }

    public void update(Long id, AdminDtos.CourseUpdate req) {
        CourseTemplate c = courseMapper.selectById(id);
        if (c == null) {
            throw new BusinessException("课程不存在");
        }
        if (req.name() != null) c.setName(req.name());
        if (req.durationMin() != null) c.setDurationMin(req.durationMin());
        if (req.defaultSessions() != null) c.setDefaultSessions(req.defaultSessions());
        if (req.status() != null) c.setStatus(req.status());
        courseMapper.updateById(c);
    }

    public void toggle(Long id) {
        CourseTemplate c = courseMapper.selectById(id);
        if (c == null) {
            throw new BusinessException("课程不存在");
        }
        c.setStatus(c.getStatus() == 1 ? 0 : 1);
        courseMapper.updateById(c);
    }
}
