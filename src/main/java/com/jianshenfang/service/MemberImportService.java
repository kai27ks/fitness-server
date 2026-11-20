package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.auth.AuthContext;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.common.XlsxReader;
import com.jianshenfang.dto.MemberImportDtos;
import com.jianshenfang.entity.Coach;
import com.jianshenfang.entity.CourseTemplate;
import com.jianshenfang.entity.LegacyImportBatch;
import com.jianshenfang.entity.LegacyImportRow;
import com.jianshenfang.entity.Member;
import com.jianshenfang.entity.MemberPackage;
import com.jianshenfang.entity.MemberSessionLog;
import com.jianshenfang.entity.SysUser;
import com.jianshenfang.mapper.CoachMapper;
import com.jianshenfang.mapper.CourseTemplateMapper;
import com.jianshenfang.mapper.LegacyImportBatchMapper;
import com.jianshenfang.mapper.LegacyImportRowMapper;
import com.jianshenfang.mapper.MemberMapper;
import com.jianshenfang.mapper.MemberPackageMapper;
import com.jianshenfang.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 旧会员资料批量导入：Excel（手机号/姓名/教练/卡名/剩余…）→ 会员档案 + 课包 + 原始行归档。
 *
 * <p>规则：
 * <ol>
 *   <li><b>教练必须先录入</b>：按名称对照 coach 表，对不上则该行失败，预览阶段即给出缺失清单并禁止提交；</li>
 *   <li>课程模板按「课种」自动建（去掉教练前缀后的名字），课包记录归属教练；</li>
 *   <li>同一会员同一课种合并为一条课包，剩余相加（唯一键 member_id+course_id 不冲突）；</li>
 *   <li>总课时 = 剩余，金额 = 0，有效期 = 导入日 + 365 天，source = LEGACY_IMPORT；</li>
 *   <li>会员默认密码 123456，导入后生成认领码；手机号已存在的会员整条跳过；</li>
 *   <li>每次导入写一条 legacy_import_batch，Excel 的每一行写 legacy_import_row（含失败/跳过原因）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberImportService {

    private static final String DEFAULT_PASSWORD = "123456";
    private static final String SOURCE_LEGACY = "LEGACY_IMPORT";
    private static final int DEFAULT_SESSIONS = 30;
    private static final int DEFAULT_DURATION_MIN = 60;
    private static final int EXPIRE_DAYS = 365;
    private static final int MAX_ERRORS = 30;

    private final MemberMapper memberMapper;
    private final SysUserMapper userMapper;
    private final CoachMapper coachMapper;
    private final CourseTemplateMapper courseMapper;
    private final MemberPackageMapper packageMapper;
    private final LegacyImportBatchMapper batchMapper;
    private final LegacyImportRowMapper importRowMapper;
    private final WechatAdminService wechatAdminService;
    private final OperationLogService logService;
    private final SessionLogService sessionLogService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /* ==================== 预览 ==================== */

    public MemberImportDtos.Preview preview(MultipartFile file) {
        Parsed parsed = parse(file);
        List<MemberPlan> plans = plan(parsed.validRows());

        List<String> courseTypes = plans.stream().flatMap(p -> p.pkgs().stream())
                .map(PkgPlan::courseType).distinct().sorted().toList();
        List<String> coachNames = plans.stream().flatMap(p -> p.pkgs().stream())
                .map(PkgPlan::coachName).filter(Objects::nonNull).distinct().sorted().toList();
        Set<String> existingCourses = new LinkedHashSet<>();
        courseMapper.selectList(null).forEach(c -> existingCourses.add(c.getName()));
        List<String> toCreate = courseTypes.stream().filter(n -> !existingCourses.contains(n)).toList();

        int packageCount = plans.stream().mapToInt(p -> p.pkgs().size()).sum();
        List<MemberImportDtos.Item> sample = plans.stream().limit(30).map(p -> new MemberImportDtos.Item(
                p.phone(), p.name(), p.pkgs().get(0).coachName(),
                p.pkgs().stream().mapToInt(PkgPlan::cardCount).sum(),
                p.pkgs().size(),
                p.pkgs().stream().mapToInt(PkgPlan::remain).sum())).toList();

        return new MemberImportDtos.Preview(parsed.totalRows(), plans.size(), packageCount,
                parsed.invalidCount(), parsed.dedupCount(), courseTypes, coachNames,
                parsed.missingCoaches(), toCreate, sample, parsed.errors());
    }

    /* ==================== 正式导入 ==================== */

    @Transactional
    public MemberImportDtos.Result commit(MultipartFile file) {
        Parsed parsed = parse(file);
        if (!parsed.missingCoaches().isEmpty()) {
            throw new BusinessException("以下教练尚未录入「教练管理」，请先录入后再导入："
                    + String.join("、", parsed.missingCoaches()));
        }
        List<SourceRow> validRows = parsed.validRows();
        if (validRows.isEmpty()) throw new BusinessException("没有可导入的有效数据");

        LegacyImportBatch batch = new LegacyImportBatch();
        batch.setFileName(file.getOriginalFilename());
        batch.setTotalRows(parsed.totalRows());
        batch.setMemberCount(0);
        batch.setPackageCount(0);
        batch.setSkippedCount(0);
        batch.setFailedCount(parsed.invalidCount());
        batch.setOperatorId(AuthContext.userId());
        batchMapper.insert(batch);
        Long batchId = batch.getId();

        Map<String, Long> coachCache = new LinkedHashMap<>();
        coachMapper.selectList(new LambdaQueryWrapper<Coach>().orderByAsc(Coach::getId))
                .forEach(c -> coachCache.putIfAbsent(c.getName(), c.getId()));
        Map<String, Long> courseCache = new LinkedHashMap<>();
        courseMapper.selectList(null).forEach(c -> courseCache.putIfAbsent(c.getName(), c.getId()));

        int createdMembers = 0, createdPackages = 0, skipped = 0, newCourses = 0;
        List<String> errors = new ArrayList<>(parsed.errors());
        LocalDate today = LocalDate.now();
        List<MemberPlan> plans = plan(validRows);

        for (MemberPlan plan : plans) {
            List<SourceRow> rows = validRows.stream().filter(r -> r.phone().equals(plan.phone())).toList();

            boolean exists = memberMapper.selectCount(new LambdaQueryWrapper<Member>()
                    .eq(Member::getPhone, plan.phone())) > 0
                    || userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getPhone, plan.phone())) > 0;
            if (exists) {
                skipped++;
                rows.forEach(r -> archive(batchId, r, null, null, "SKIPPED", "手机号已存在，跳过"));
                continue;
            }

            try {
                SysUser user = new SysUser();
                user.setPhone(plan.phone());
                user.setPassword(encoder.encode(DEFAULT_PASSWORD));
                user.setRole("MEMBER");
                user.setStatus(1);
                userMapper.insert(user);

                Long mainCoachId = null;
                int mainRemain = -1;
                List<MemberPackage> pkgs = new ArrayList<>();
                Map<String, Long> typeToPackage = new LinkedHashMap<>();

                for (PkgPlan p : plan.pkgs()) {
                    Long courseId = courseCache.get(p.courseType());
                    if (courseId == null) {
                        CourseTemplate t = new CourseTemplate();
                        t.setName(p.courseType());
                        t.setDurationMin(DEFAULT_DURATION_MIN);
                        t.setDefaultSessions(DEFAULT_SESSIONS);
                        t.setStatus(1);
                        courseMapper.insert(t);
                        courseId = t.getId();
                        courseCache.put(p.courseType(), courseId);
                        newCourses++;
                    }
                    Long coachId = p.coachName() == null ? null : coachCache.get(p.coachName());
                    if (p.remain() > mainRemain) {
                        mainRemain = p.remain();
                        mainCoachId = coachId;
                    }
                    MemberPackage pkg = new MemberPackage();
                    pkg.setMemberId(0L);
                    pkg.setCourseId(courseId);
                    pkg.setCoachId(coachId);
                    pkg.setTotalSessions(p.remain());
                    pkg.setRemainSessions(p.remain());
                    pkg.setTotalPrice(BigDecimal.ZERO);
                    pkg.setStartDate(today);
                    pkg.setExpireDate(today.plusDays(EXPIRE_DAYS));
                    pkg.setStatus("ACTIVE");
                    pkg.setSource(SOURCE_LEGACY);
                    pkg.setImportBatchId(batchId);
                    pkgs.add(pkg);
                    }

                Member m = new Member();
                m.setUserId(user.getId());
                m.setName(plan.name());
                m.setPhone(plan.phone());
                m.setCoachId(mainCoachId);
                memberMapper.insert(m);

                for (MemberPackage pkg : pkgs) {
                    pkg.setMemberId(m.getId());
                    packageMapper.insert(pkg);
                    sessionLogService.record(m.getId(), pkg.getId(), null, pkg.getCourseId(), pkg.getCoachId(),
                            MemberSessionLog.IMPORT, pkg.getTotalSessions(), pkg.getRemainSessions(),
                            "旧数据导入课包（批次 " + batchId + "）");
                }
                // 按课种回填课包 id，供原始行归档（pkgs 与 plan.pkgs() 顺序一致）
                for (int i = 0; i < plan.pkgs().size(); i++) {
                    typeToPackage.put(plan.pkgs().get(i).courseType(), pkgs.get(i).getId());
                }

                createdMembers++;
                createdPackages += pkgs.size();

                for (SourceRow r : rows) {
                    Long pid = typeToPackage.get(r.courseType());
                    archive(batchId, r, m.getId(), pid, "OK", null);
                }

                wechatAdminService.resetClaimCode(m.getId());
            } catch (Exception e) {
                log.warn("导入会员失败 phone={}", mask(plan.phone()), e);
                if (errors.size() < MAX_ERRORS) {
                    errors.add("手机号 " + mask(plan.phone()) + "：" + e.getMessage());
                }
                rows.forEach(r -> archive(batchId, r, null, null, "FAILED", e.getMessage()));
            }
        }

        // 校验不通过的行也归档，便于事后核对
        parsed.invalidRows().forEach(r -> archive(batchId, r, null, null, "FAILED", r.err()));
        // 完全一致的重复行归档（未参与导入）
        parsed.dedupRows().forEach(r -> archive(batchId, r, null, null, "DEDUP", "与前面的行完全相同，已去重"));

        batch.setMemberCount(createdMembers);
        batch.setPackageCount(createdPackages);
        batch.setSkippedCount(skipped);
        batch.setFailedCount(parsed.invalidCount());
        batchMapper.updateById(batch);

        String detail = String.format("旧会员导入 #%d：会员 %d 人 / 课包 %d 条，跳过 %d 人，失败 %d 行，新建课程 %d",
                batchId, createdMembers, createdPackages, skipped, parsed.invalidCount(), newCourses);
        logService.log("IMPORT_MEMBER", "MEMBER", null, detail);
        log.info(detail);

        return new MemberImportDtos.Result(batchId, createdMembers, createdPackages, skipped,
                newCourses, parsed.invalidCount(), parsed.dedupCount(), errors);
    }

    private void archive(Long batchId, SourceRow r, Long memberId, Long packageId, String status, String err) {
        LegacyImportRow row = new LegacyImportRow();
        row.setBatchId(batchId);
        row.setRowNo(r.rowNo());
        row.setPhone(r.phone());
        row.setName(r.name());
        row.setCoachName(r.coachName());
        row.setCardName(r.cardName());
        row.setCourseType(r.courseType());
        row.setRemain(r.remain());
        row.setCardNo(r.cardNo());
        row.setPayType(r.payType());
        row.setSalesName(r.salesName());
        row.setSourceChannel(r.channel());
        row.setRemark(r.remark());
        row.setMemberId(memberId);
        row.setPackageId(packageId);
        row.setStatus(status);
        row.setErrMsg(err == null ? null : cut(err, 250));
        importRowMapper.insert(row);
    }

    /* ==================== 解析 ==================== */

    private Parsed parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("请选择要导入的 Excel 文件");
        List<List<String>> rows;
        try (InputStream in = file.getInputStream()) {
            rows = XlsxReader.readFirstSheet(in);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("读取 Excel 失败，请重新导出后再试");
        }
        if (rows.size() < 2) throw new BusinessException("Excel 里没有数据行");

        List<String> header = rows.get(0);
        int iPhone = indexOf(header, "手机号");
        int iName = indexOf(header, "姓名");
        int iCoach = indexOf(header, "教练");
        int iCard = indexOf(header, "卡名");
        int iRemain = indexOf(header, "剩余");
        int iCardNo = indexOf(header, "卡号");
        int iPay = indexOf(header, "支付方式");
        int iSales = indexOf(header, "销售");
        int iSource = indexOf(header, "来源");
        int iRemark = indexOf(header, "备注");
        if (iPhone < 0 || iName < 0 || iCard < 0) {
            throw new BusinessException("表头缺少「手机号 / 姓名 / 卡名」列，请使用原始导出模板");
        }

        // 已录入的教练（长的优先，避免「林珊」把「林珊珊」截断）
        Map<String, Long> coachIds = new LinkedHashMap<>();
        coachMapper.selectList(null).stream()
                .filter(c -> c.getName() != null && !c.getName().isBlank())
                .sorted(Comparator.comparingInt((Coach c) -> c.getName().length()).reversed())
                .forEach(c -> coachIds.putIfAbsent(c.getName(), c.getId()));
        List<String> knownCoaches = new ArrayList<>(coachIds.keySet());

        List<SourceRow> valid = new ArrayList<>();
        List<SourceRow> invalid = new ArrayList<>();
        List<SourceRow> dedup = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> missingCoaches = new LinkedHashSet<>();
        // 完全一致的重复行只保留第一条（Excel 里所有字段都一样，视为重复录入）
        Set<String> fingerprints = new LinkedHashSet<>();
        int total = 0;

        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            String phone = cell(r, iPhone);
            String name = cell(r, iName);
            String coachName = cell(r, iCoach);
            String cardName = cell(r, iCard);
            String remainRaw = cell(r, iRemain);
            String cardNo = cell(r, iCardNo);
            String payType = cell(r, iPay);
            String sales = cell(r, iSales);
            String channel = cell(r, iSource);
            String remark = cell(r, iRemark);
            if (phone.isEmpty() && name.isEmpty() && cardName.isEmpty()) continue;
            total++;

            String rowErr = null;
            if (!phone.matches("^1\\d{10}$")) rowErr = "手机号格式不正确";
            else if (name.isEmpty()) rowErr = "姓名为空";
            else if (cardName.isEmpty()) rowErr = "卡名为空";

            int remain = 0;
            if (rowErr == null) {
                if (remainRaw.isEmpty()) {
                    rowErr = "剩余课时为空";
                } else {
                    try {
                        remain = Integer.parseInt(remainRaw.trim());
                        if (remain < 0) rowErr = "剩余课时为负数";
                    } catch (NumberFormatException e) {
                        rowErr = "剩余课时不是整数";
                    }
                }
            }

            // 去掉卡名里的教练前缀，得到课种；教练列为空时用前缀反推
            String courseType = cardName;
            String coach = coachName.isEmpty() ? null : coachName;
            if (coach != null && cardName.startsWith(coach)) {
                courseType = cardName.substring(coach.length()).trim();
            } else {
                for (String c : knownCoaches) {
                    if (cardName.startsWith(c)) {
                        courseType = cardName.substring(c.length()).trim();
                        if (coach == null) coach = c;
                        break;
                    }
                }
            }
            if (courseType.isEmpty()) courseType = cardName;

            if (rowErr == null) {
                if (coach == null) {
                    rowErr = "教练为空且卡名无法识别教练";
                } else if (!coachIds.containsKey(coach)) {
                    rowErr = "教练「" + coach + "」尚未录入教练管理";
                    missingCoaches.add(coach);
                }
            }

            SourceRow row = new SourceRow(i + 1, phone, name, coach, cardName, courseType,
                    remain, cardNo, payType, sales, channel, remark, rowErr);
            if (rowErr == null) {
                String fp = String.join("\u0001", phone, name, String.valueOf(coach), cardName, courseType,
                        String.valueOf(remain), cardNo, payType, sales, channel, remark);
                if (fingerprints.add(fp)) {
                    valid.add(row);
                } else {
                    dedup.add(row);
                }
            } else {
                invalid.add(row);
                if (errors.size() < MAX_ERRORS) {
                    errors.add("第 " + (i + 1) + " 行（" + (phone.isEmpty() ? "无手机号" : mask(phone)) + "）：" + rowErr);
                }
            }
        }

        return new Parsed(total, valid, invalid, dedup, errors, new ArrayList<>(missingCoaches));
    }

    private int indexOf(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (name.equals(header.get(i).trim())) return i;
        }
        return -1;
    }

    private String cell(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size()) return "";
        String v = row.get(idx);
        return v == null ? "" : v.trim();
    }

    /* ==================== 归并 ==================== */

    /** 按会员 + 课种合并：同一人同一课种只留一条课包，剩余相加 */
    private List<MemberPlan> plan(List<SourceRow> rows) {
        Map<String, List<SourceRow>> byPhone = new LinkedHashMap<>();
        for (SourceRow r : rows) {
            byPhone.computeIfAbsent(r.phone(), k -> new ArrayList<>()).add(r);
        }
        List<MemberPlan> plans = new ArrayList<>(byPhone.size());
        for (Map.Entry<String, List<SourceRow>> e : byPhone.entrySet()) {
            Map<String, List<SourceRow>> byType = new LinkedHashMap<>();
            for (SourceRow r : e.getValue()) {
                byType.computeIfAbsent(r.courseType(), k -> new ArrayList<>()).add(r);
            }
            List<PkgPlan> pkgs = new ArrayList<>(byType.size());
            for (Map.Entry<String, List<SourceRow>> te : byType.entrySet()) {
                List<SourceRow> cs = te.getValue();
                int remain = cs.stream().mapToInt(SourceRow::remain).sum();
                String coach = cs.stream()
                        .filter(x -> x.coachName() != null)
                        .max(Comparator.comparingInt(SourceRow::remain))
                        .map(SourceRow::coachName).orElse(null);
                pkgs.add(new PkgPlan(te.getKey(), coach, remain, cs.size()));
            }
            plans.add(new MemberPlan(e.getKey(), e.getValue().get(0).name(), pkgs));
        }
        return plans;
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /* ==================== 内部结构 ==================== */

    private record SourceRow(int rowNo, String phone, String name, String coachName,
                             String cardName, String courseType, int remain,
                             String cardNo, String payType, String salesName,
                             String channel, String remark, String err) {}

    private record PkgPlan(String courseType, String coachName, int remain, int cardCount) {}

    private record MemberPlan(String phone, String name, List<PkgPlan> pkgs) {}

    private record Parsed(int totalRows, List<SourceRow> validRows, List<SourceRow> invalidRows,
                          List<SourceRow> dedupRows, List<String> errors, List<String> missingCoaches) {

        int invalidCount() {
            return invalidRows.size();
        }

        int dedupCount() {
            return dedupRows.size();
        }
    }
}
