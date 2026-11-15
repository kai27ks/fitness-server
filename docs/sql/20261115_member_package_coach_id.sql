-- ==============================================================================
-- 旧会员资料迁移导入：给会员课包补「归属教练」
-- 日期：2026-11-15
--
-- 背景：旧的「会员绑卡」Excel 里每张卡都带教练（卡名形如「王卢磊基础私教」），
--       现表 member_package 只有 course_id，没有教练字段，迁移会丢失卡→教练的归属。
-- 说明：唯一键 uk_member_course(member_id, course_id) 保持不变，迁移时按
--       「会员 + 课种」合并课时，因此不会产生冲突，也不影响现有增购逻辑。
-- ==============================================================================

ALTER TABLE member_package
    ADD COLUMN coach_id BIGINT NULL COMMENT '课包归属教练（旧数据迁移用，可空）' AFTER course_id;

-- 若需按教练筛课包，可加索引（数据量小，可选）
-- ALTER TABLE member_package ADD INDEX idx_pkg_coach (coach_id);

-- 回滚（确认无需该字段时执行）：
-- ALTER TABLE member_package DROP COLUMN coach_id;
