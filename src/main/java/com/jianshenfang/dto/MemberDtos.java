package com.jianshenfang.dto;

public final class MemberDtos {
    private MemberDtos() {}

    public record CoachBrief(Long id, String name, String photo, String intro, String tag) {}

    public record Qualification(String name, String certNo, String issuer, String image, String expireDate) {}

    public record PackageInfo(Long id, Long courseId, String courseName, Integer remain, Integer total,
                              String expireDate, String status, String price) {}

    public record Studio(String name, String address, String hours) {}

    public record HomeResponse(String memberName, CoachBrief coach, boolean coachOnLeaveToday,
                               PackageInfo packageInfo, java.util.List<CoachBrief> coaches, Studio studio) {}

    public record MeResponse(String name, String phone, String coachName, String avatarUrl,
                             int remainSessions, int consumedSessions, int upcoming, int packageCount) {}

    /** 课包课时流水：changeSessions 正数增加、负数扣减；remainAfter 为变动后剩余（历史数据可能为空） */
    public record SessionLogVO(Long id, String type, int changeSessions, Integer remainAfter,
                               Long packageId, Long memberId, String courseName, String coachName,
                               String bookingTime, String remark, String createTime) {}
}
