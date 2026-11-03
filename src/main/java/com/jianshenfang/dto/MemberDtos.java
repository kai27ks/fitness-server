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

    public record MeResponse(String name, String phone, String coachName,
                             int remainSessions, int consumedSessions, int upcoming, int packageCount) {}
}
