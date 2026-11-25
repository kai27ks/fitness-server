package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.dto.ReviewDtos;
import com.jianshenfang.entity.Booking;
import com.jianshenfang.entity.Coach;
import com.jianshenfang.entity.CoachReview;
import com.jianshenfang.entity.CourseTemplate;
import com.jianshenfang.entity.Member;
import com.jianshenfang.mapper.BookingMapper;
import com.jianshenfang.mapper.CoachMapper;
import com.jianshenfang.mapper.CoachReviewMapper;
import com.jianshenfang.mapper.CourseTemplateMapper;
import com.jianshenfang.mapper.MemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * 会员对教练的五星满意度评价。
 * 规则：一次课（booking）只能评一次；只允许评价本人已上课（CHECKED_IN/COMPLETED）的预约。
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FMT_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_LIMIT = 100;

    private final CoachReviewMapper reviewMapper;
    private final BookingMapper bookingMapper;
    private final MemberMapper memberMapper;
    private final CoachMapper coachMapper;
    private final CourseTemplateMapper courseMapper;
    private final MemberService memberService;

    /** 会员提交评价，返回该教练的最新评分汇总 */
    @Transactional
    public ReviewDtos.CoachRating submit(Long userId, Long bookingId, Integer rating) {
        if (rating == null || rating < 1 || rating > 5) {
            throw new BusinessException("请选择 1~5 星满意度");
        }
        Member member = memberService.currentMember(userId);
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null) throw new BusinessException("预约记录不存在");
        if (!member.getId().equals(booking.getMemberId())) {
            throw new BusinessException("只能评价自己的课程");
        }
        if (!Booking.CHECKED_IN.equals(booking.getStatus()) && !Booking.COMPLETED.equals(booking.getStatus())) {
            throw new BusinessException("课程上完后才能评价");
        }
        CoachReview exist = reviewMapper.selectOne(new LambdaQueryWrapper<CoachReview>()
                .eq(CoachReview::getBookingId, bookingId));
        if (exist != null) throw new BusinessException("该课程已评价");

        CoachReview r = new CoachReview();
        r.setMemberId(member.getId());
        r.setCoachId(booking.getCoachId());
        r.setBookingId(bookingId);
        r.setRating(rating);
        r.setCreateTime(LocalDateTime.now());
        try {
            reviewMapper.insert(r);
        } catch (DuplicateKeyException e) {
            // uk_booking 兜底并发重复提交
            throw new BusinessException("该课程已评价");
        }
        return coachRating(booking.getCoachId());
    }

    public ReviewDtos.CoachRating coachRating(Long coachId) {
        List<CoachReview> list = reviewMapper.selectList(new LambdaQueryWrapper<CoachReview>()
                .eq(CoachReview::getCoachId, coachId));
        if (list.isEmpty()) return new ReviewDtos.CoachRating(0, 0);
        double avg = list.stream().mapToInt(x -> x.getRating() == null ? 0 : x.getRating()).average().orElse(0);
        return new ReviewDtos.CoachRating(list.size(), Math.round(avg * 10) / 10.0);
    }

    /** 教练收到的评价（教练端 / 管理端查看） */
    public ReviewDtos.CoachReviewPage coachReviews(Long coachId, Integer limit) {
        int size = limit == null ? 20 : Math.max(1, Math.min(limit, MAX_LIMIT));
        List<CoachReview> list = reviewMapper.selectList(new LambdaQueryWrapper<CoachReview>()
                .eq(CoachReview::getCoachId, coachId)
                .orderByDesc(CoachReview::getCreateTime)
                .orderByDesc(CoachReview::getId)
                .last("LIMIT " + size));
        List<ReviewDtos.ReviewItem> items = list.stream()
                .map(this::toItem)
                .sorted(Comparator.comparing(ReviewDtos.ReviewItem::createTime).reversed())
                .toList();
        return new ReviewDtos.CoachReviewPage(coachRating(coachId), items);
    }

    private ReviewDtos.ReviewItem toItem(CoachReview r) {
        Member m = memberMapper.selectById(r.getMemberId());
        Coach coach = coachMapper.selectById(r.getCoachId());
        Booking b = r.getBookingId() == null ? null : bookingMapper.selectById(r.getBookingId());
        CourseTemplate course = b == null ? null : courseMapper.selectById(b.getCourseId());
        return new ReviewDtos.ReviewItem(
                r.getId(),
                mask(m == null ? "会员" : m.getName()),
                r.getRating() == null ? 0 : r.getRating(),
                course == null ? "课程" : course.getName(),
                coach == null ? "教练" : coach.getName(),
                b == null || b.getStartTime() == null ? null : b.getStartTime().format(FMT),
                r.getCreateTime() == null ? null : r.getCreateTime().format(FMT_FULL));
    }

    /** 会员姓名脱敏：只显示姓氏，避免教练端看到完整姓名 */
    private String mask(String name) {
        if (name == null || name.isEmpty()) return "会员";
        return name.charAt(0) + "**";
    }
}
