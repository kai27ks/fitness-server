package com.jianshenfang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jianshenfang.common.BusinessException;
import com.jianshenfang.common.XlsxReader;
import com.jianshenfang.dto.MemberImportDtos;
import com.jianshenfang.entity.Coach;
import com.jianshenfang.entity.CourseTemplate;
import com.jianshenfang.entity.Member;
import com.jianshenfang.entity.MemberPackage;
import com.jianshenfang.entity.SysUser;
import com.jianshenfang.mapper.CoachMapper;
import com.jianshenfang.mapper.CourseTemplateMapper;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 旧会员资料批量导入：Excel（手机号/姓名/教练/卡名/剩余）→ 会员档案 + 课包。
 *
 * <p>规则（已与管理员确认）：
 * <ol>
 *   <li>课程模板按「课种」建（去掉教练前缀后的名字），课包记录归属教练；</li>
 *   <li>同一会员同一课种合并为一条课包，剩余相加（唯一键仍为 member_id+course_id，不冲突）；</li>
 *   <li>总课时 = 剩余，金额 = 0，有效期 = 导入日 + 365 天；</li>
 *   <li>会员默认密码 123456，导入后生成认领码供公众号认领；</li>
 *   <li>手机号已存在的会员整条跳过，不覆盖现有档案。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberImportService {

    private static final String DEFAULT_PASSWORD = "123456";
    private static final int DEFAULT_SESSIONS = 30;
    private static final int DEFAULT_DURATION_MIN = 60;
    private static final int EXPIRE_DAYS = 365;
    private static final int MAX_ERRORS = 30;

    private final MemberMapper memberMapper;
    private final SysUserMapper userMapper;
    private final CoachMapper coachMapper;
    private final CourseTemplateMapper courseMapper;
    private final MemberPackageMapper packageMapper;
    private final WechatAdminService wechatAdminService;
    private final OperationLogService logService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /* ==================== 预览 ==================== */

    public MemberImportDtos.Preview preview(MultipartFile file) {
        Parsed parsed = parse(file);
        List<MemberPlan> plans = plan(parsed.rows());

        List<String> courseTypes = plans.stream().flatMap(p -> p.pkgs().stream())
                .map(PkgPlan::courseType).distinct().sorted().toList();
        List<String> coachNames = plans.stream().flatMap(p -> p.pkgs().stream())
                .map(PkgPlan::coachName).filter(Objects::nonNull).distinct().sorted().toList();
        int packageCount = plans.stream().mapToInt(p -> p.pkgs().size()).sum();

        List<MemberImportDtos.Item> sample = plans.stream().limit(30).map(p -> new MemberImportDtos.Item(
                p.phone(), p.name(), p.pkgs().get(0).coachName(),
                p.pkgs().stream().mapToInt(PkgPlan::cardCount).sum(),
                p.pkgs().size(),
                p.pkgs().stream().mapToInt(PkgPlan::remain).sum())).toList();

        return new MemberImportDtos.Preview(parsed.totalRows(), plans.size(), packageCount,
                parsed.invalidRows(), courseTypes, coachNames, sample, parsed.errors());
    }

    /* ==================== 正式导入 ==================== */

    @Transactional
    public MemberImportDtos.Result commit(MultipartFile file) {
        Parsed parsed = parse(file);
        List<MemberPlan> plans = plan(parsed.rows());

        Map<String, Long> coachCache = new LinkedHashMap<>();
        coachMapper.selectList(new LambdaQueryWrapper<Coach>().orderByAsc(Coach::getId))
                .forEach(c -> coachCache.putIfAbsent(c.getName(), c.getId()));
        Map<String, Long> courseCache = new LinkedHashMap<>();
        courseMapper.selectList(null).forEach(c -> courseCache.putIfAbsent(c.getName(), c.getId()));

        int createdMembers = 0, createdPackages = 0, skipped = 0, newCourses = 0, newCoaches = 0;
        List<String> errors = new ArrayList<>(parsed.errors());
        LocalDate today = LocalDate.now();

        for (MemberPlan plan : plans) {
            if (memberMapper.selectCount(new LambdaQueryWrapper<Member>()
                    .eq(Member::getPhone, plan.phone())) > 0) {
                skipped++;
                continue;
            }
            try {
                // 1. 登录账号
                SysUser user = new SysUser();
                user.setPhone(plan.phone());
                user.setPassword(encoder.encode(DEFAULT_PASSWORD));
                user.setRole("MEMBER");
                user.setStatus(1);
                userMapper.insert(user);

                // 2. 课包：先落课程模板与教练，再取该会员的主教练
                Long mainCoachId = null;
                int mainRemain = -1;
                List<MemberPackage> pkgs = new ArrayList<>();
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
                    Long coachId = null;
                    if (p.coachName() != null && !p.coachName().isBlank()) {
                        coachId = coachCache.get(p.coachName());
                        if (coachId == null) {
                            Coach c = new Coach();
                            c.setName(p.coachName());
                            c.setStatus(1);
                            coachMapper.insert(c);
                            coachId = c.getId();
                            coachCache.put(p.coachName(), coachId);
                            newCoaches++;
                        }
                    }
                    if (p.remain() > mainRemain) {
                        mainRemain = p.remain();
                        mainCoachId = coachId;
                    }
                    MemberPackage pkg = new MemberPackage();
                    pkg.setMemberId(0L); // 建会员后回填
                    pkg.setCourseId(courseId);
                    pkg.setCoachId(coachId);
                    pkg.setTotalSessions(p.remain());
                    pkg.setRemainSessions(p.remain());
                    pkg.setTotalPrice(BigDecimal.ZERO);
                    pkg.setStartDate(today);
                    pkg.setExpireDate(today.plusDays(EXPIRE_DAYS));
                    pkg.setStatus("ACTIVE");
                    pkgs.add(pkg);
                }

                // 3. 会员档案
                Member m = new Member();
                m.setUserId(user.getId());
                m.setName(plan.name());
                m.setPhone(plan.phone());
                m.setCoachId(mainCoachId);
                memberMapper.insert(m);

                for (MemberPackage pkg : pkgs) {
                    pkg.setMemberId(m.getId());
                    packageMapper.insert(pkg);
                }
                createdMembers++;
                createdPackages += pkgs.size();

                // 4. 线下认领码（会员从公众号认领后再绑 openid）
                wechatAdminService.resetClaimCode(m.getId());
            } catch (Exception e) {
                log.warn("导入会员失败 phone={}", mask(plan.phone()), e);
                if (errors.size() < MAX_ERRORS) {
                    errors.add("手机号 " + mask(plan.phone()) + "：" + e.getMessage());
                }
            }
        }

        String detail = String.format("批量导入旧会员：成功 %d 人 / %d 个课包，跳过已存在 %d 人，新建课程 %d、教练 %d",
                createdMembers, createdPackages, skipped, newCourses, newCoaches);
        logService.log("IMPORT_MEMBER", "MEMBER", null, detail);
        log.info(detail);

        return new MemberImportDtos.Result(createdMembers, createdPackages, skipped,
                newCourses, newCoaches, parsed.invalidRows(), errors);
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
        if (iPhone < 0 || iName < 0 || iCard < 0) {
            throw new BusinessException("表头缺少「手机号 / 姓名 / 卡名」列，请使用原始导出模板");
        }

        // 已知教练名（长的优先，避免「林珊」把「林珊珊」截断）
        List<String> knownCoaches = coachMapper.selectList(null).stream()
                .map(Coach::getName).filter(Objects::nonNull).filter(n -> !n.isBlank())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();

        List<Row> valid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int invalidRows = 0;
        int total = 0;

        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            String phone = cell(r, iPhone);
            String name = cell(r, iName);
            String coachName = cell(r, iCoach);
            String cardName = cell(r, iCard);
            String remainRaw = cell(r, iRemain);
            if (phone.isEmpty() && name.isEmpty() && cardName.isEmpty()) continue; // 空行
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

            if (rowErr != null) {
                invalidRows++;
                if (errors.size() < MAX_ERRORS) {
                    errors.add("第 " + (i + 1) + " 行（" + (phone.isEmpty() ? "无手机号" : mask(phone)) + "）：" + rowErr);
                }
                continue;
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

            valid.add(new Row(i + 1, phone, name, coach, cardName, courseType, remain));
        }

        if (valid.isEmpty()) {
            throw new BusinessException("没有可导入的有效数据（" + invalidRows + " 行校验失败）");
        }
        return new Parsed(valid, total, invalidRows, errors);
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
    private List<MemberPlan> plan(List<Row> rows) {
        Map<String, List<Row>> byPhone = new LinkedHashMap<>();
        for (Row r : rows) {
            byPhone.computeIfAbsent(r.phone(), k -> new ArrayList<>()).add(r);
        }
        List<MemberPlan> plans = new ArrayList<>(byPhone.size());
        for (Map.Entry<String, List<Row>> e : byPhone.entrySet()) {
            List<Row> list = e.getValue();
            Map<String, List<Row>> byType = new LinkedHashMap<>();
            for (Row r : list) {
                byType.computeIfAbsent(r.courseType(), k -> new ArrayList<>()).add(r);
            }
            List<PkgPlan> pkgs = new ArrayList<>(byType.size());
            for (Map.Entry<String, List<Row>> te : byType.entrySet()) {
                List<Row> cs = te.getValue();
                int remain = cs.stream().mapToInt(Row::remain).sum();
                String coach = cs.stream()
                        .filter(x -> x.coachName() != null)
                        .max(Comparator.comparingInt(Row::remain))
                        .map(Row::coachName).orElse(null);
                pkgs.add(new PkgPlan(te.getKey(), coach, remain, cs.size()));
            }
            plans.add(new MemberPlan(e.getKey(), list.get(0).name(), pkgs));
        }
        return plans;
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /* ==================== 内部结构 ==================== */

    private record Row(int rowNo, String phone, String name, String coachName,
                       String cardName, String courseType, int remain) {}

    private record PkgPlan(String courseType, String coachName, int remain, int cardCount) {}

    private record MemberPlan(String phone, String name, List<PkgPlan> pkgs) {}

    private record Parsed(List<Row> rows, int totalRows, int invalidRows, List<String> errors) {}
}
