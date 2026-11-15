package com.jianshenfang.dto;

import java.util.List;

/** 旧会员资料批量导入（管理员端） */
public final class MemberImportDtos {

    private MemberImportDtos() {
    }

    /** 预览结果：只解析校验，不落库 */
    public record Preview(
            int totalRows,
            int memberCount,
            int packageCount,
            int invalidRows,
            /** 完全一致、已自动去重的行数 */
            int dedupRows,
            List<String> courseTypes,
            List<String> coachNames,
            /** Excel 里出现、但教练管理里不存在的教练名；非空时禁止导入 */
            List<String> missingCoaches,
            /** 导入时会自动新建的课程模板 */
            List<String> coursesToCreate,
            List<Item> sample,
            List<String> errors) {}

    /** 预览里的会员样例 */
    public record Item(String phone, String name, String coachName,
                       int cardCount, int packageCount, int remain) {}

    /** 实际导入结果 */
    public record Result(
            Long batchId,
            int createdMembers,
            int createdPackages,
            int skippedMembers,
            int createdCourses,
            int failedRows,
            int dedupRows,
            List<String> errors) {}
}
