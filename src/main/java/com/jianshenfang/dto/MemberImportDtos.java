package com.jianshenfang.dto;

import java.util.List;

/** 旧会员资料批量导入（管理员端） */
public final class MemberImportDtos {

    private MemberImportDtos() {
    }

    /** 预览结果：只读不落库 */
    public record Preview(
            int totalRows,
            int memberCount,
            int packageCount,
            int invalidRows,
            List<String> courseTypes,
            List<String> coachNames,
            List<Item> sample,
            List<String> errors) {}

    /** 预览里的会员样例（写库前给管理员核对） */
    public record Item(String phone, String name, String coachName,
                       int cardCount, int packageCount, int remain) {}

    /** 实际导入结果 */
    public record Result(
            int createdMembers,
            int createdPackages,
            int skippedMembers,
            int createdCourses,
            int createdCoaches,
            int failedRows,
            List<String> errors) {}
}
