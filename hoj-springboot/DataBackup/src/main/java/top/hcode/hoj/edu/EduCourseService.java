package top.hcode.hoj.edu;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EduCourseService {
    private final JdbcTemplate db;
    private final EduClassService classes;
    public EduCourseService(JdbcTemplate db, EduClassService classes) { this.db = db; this.classes = classes; }

    static final String PUBLIC_PROBLEM = EduProgress.PUBLIC_PROBLEM;
    private static final String MEMBERSHIP = "EXISTS (SELECT 1 FROM edu_class_course access_cc JOIN edu_class access_c ON access_c.id=access_cc.class_id " +
            "JOIN edu_class_member access_m ON access_m.class_id=access_c.id WHERE access_cc.course_id=c.id AND access_c.status=0 AND access_m.uid=? AND access_m.status=0)";
    private static final String COLUMNS = "SELECT c.id,c.title,c.description,c.owner_uid AS ownerUid,u.username AS ownerUsername,c.status,c.revision," +
            "c.gmt_modified AS gmtModified,(SELECT COUNT(*) FROM edu_course_problem cp JOIN problem p ON p.id=cp.pid WHERE cp.course_id=c.id AND " + PUBLIC_PROBLEM + ") AS problemCount," +
            "(SELECT COUNT(*) FROM edu_class_course refs WHERE refs.course_id=c.id) AS referenceCount FROM edu_course c LEFT JOIN user_info u ON u.uuid=c.owner_uid ";

    private long count(String sql, Object... args) { return db.queryForObject(sql, args, Long.class); }
    private static String marks(int n) { return String.join(",", Collections.nCopies(n, "?")); }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }

    class Access {
        final String uid;
        final boolean root, teacher;
        Access() { uid = classes.actor(); root = classes.hasRole(uid, "root"); teacher = root || classes.hasRole(uid, "teacher"); }
        boolean edit(Map<String, Object> row) { return root || (teacher && uid.equals(row.get("ownerUid"))); }
        void requireTeacher() { if (!teacher) throw new EduException(403, "需要教师身份"); }
    }

    private Map<String, Object> course(long id, boolean lock) {
        List<Map<String, Object>> rows = db.queryForList("SELECT id,title,description,owner_uid AS ownerUid,status,revision FROM edu_course WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new EduException(404, "课程不存在");
        return rows.get(0);
    }
    private void requireRead(Access access, Map<String, Object> row) {
        if (access.edit(row) || (access.teacher && number(row, "status") == 1)) return;
        if (number(row, "status") != 1 || count("SELECT COUNT(*) FROM edu_course c WHERE c.id=? AND " + MEMBERSHIP, row.get("id"), access.uid) == 0)
            throw new EduException(403, "请通过所属班级访问课程");
    }
    private void requireEdit(Access access, Map<String, Object> row) { if (!access.edit(row)) throw new EduException(403, "仅课程创建者和超级管理员可编辑共享课程"); }
    private void revision(Map<String, Object> row, Long expected) {
        if (expected == null || expected != number(row, "revision")) throw new EduException(409, "课程已更新，请刷新后再编辑");
    }
    private void validate(String title, String description, int status) {
        if (title == null || title.trim().isEmpty() || title.trim().length() > 128) throw new EduException(400, "课程名称须为1至128个字符");
        if (description == null || description.length() > 10000) throw new EduException(400, "课程介绍不能超过10000个字符");
        if (status < 0 || status > 2) throw new EduException(400, "课程状态无效");
    }
    private int limit(int value) { return Math.max(1, Math.min(value, 50)); }
    private int page(int value) { if (value > 100000) throw new EduException(400, "页码过大"); return Math.max(1, value); }
    private String keyword(String value) { value = value == null ? "" : value.trim(); if (value.length() > 128) throw new EduException(400, "查询参数过长"); return "%" + value + "%"; }

    public Map<String, Object> list(int page, int limit, String keyword, String scope, boolean publishedOnly) {
        Access access = new Access(); page = page(page); limit = limit(limit);
        if (!Arrays.asList("library", "mine").contains(scope)) throw new EduException(400, "课程列表范围无效");
        String where = " WHERE c.title LIKE ? "; List<Object> args = new ArrayList<>(); args.add(keyword(keyword));
        if (access.teacher) {
            if ("mine".equals(scope)) { where += "AND c.owner_uid=? "; args.add(access.uid); }
            else if (!access.root) { where += "AND (c.owner_uid=? OR c.status=1) "; args.add(access.uid); }
        } else { where += "AND c.status=1 AND " + MEMBERSHIP; args.add(access.uid); }
        if (publishedOnly) where += " AND c.status=1 ";
        return paged(COLUMNS + where, "SELECT COUNT(*) FROM edu_course c " + where, args, page, limit, "c.id DESC", access);
    }

    private Map<String, Object> paged(String sql, String counter, List<Object> args, int page, int limit, String order, Access access) {
        long total = count(counter, args.toArray());
        List<Object> values = new ArrayList<>(args); values.add(limit); values.add((page - 1) * limit);
        List<Map<String, Object>> records = db.queryForList(sql + " ORDER BY " + order + " LIMIT ? OFFSET ?", values.toArray());
        decorate(records, access);
        EduProgress.courses(db, records, access.uid);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("records", records); result.put("total", total); return result;
    }
    private void decorate(List<Map<String, Object>> records, Access access) {
        if (records.isEmpty()) return;
        List<Object> args = records.stream().map(row -> row.get("id")).collect(Collectors.toList());
        String sql = "SELECT cc.course_id AS courseId,c.id,c.name FROM edu_class_course cc JOIN edu_class c ON c.id=cc.class_id WHERE cc.course_id IN (" + marks(args.size()) + ") ";
        if (!access.root) { sql += "AND c.status=0 AND EXISTS (SELECT 1 FROM edu_class_member m WHERE m.class_id=c.id AND m.uid=? AND m.status=0) "; args.add(access.uid); }
        Map<Long, List<Map<String, Object>>> sources = new HashMap<>();
        for (Map<String, Object> row : db.queryForList(sql + "ORDER BY c.id", args.toArray())) sources.computeIfAbsent(number(row, "courseId"), ignored -> new ArrayList<>()).add(row);
        for (Map<String, Object> row : records) {
            row.put("canEdit", access.edit(row));
            if (!access.edit(row)) row.remove("referenceCount");
            row.put("sourceClasses", sources.getOrDefault(number(row, "id"), Collections.emptyList()));
        }
    }

    public Map<String, Object> detail(long id) {
        Access access = new Access(); Map<String, Object> row = course(id, false); requireRead(access, row);
        Map<String, Object> result = db.queryForMap(COLUMNS + " WHERE c.id=?", id);
        decorate(Collections.singletonList(result), access);
        boolean edit = access.edit(row);
        String fields = "SELECT cp.pid,cp.sort_order AS sortOrder,CASE WHEN " + PUBLIC_PROBLEM + " THEN p.problem_id ELSE NULL END AS problemId," +
                "CASE WHEN " + PUBLIC_PROBLEM + " THEN p.title ELSE '题目已不可用' END AS title,CASE WHEN " + PUBLIC_PROBLEM + " THEN p.difficulty ELSE NULL END AS difficulty," +
                "CASE WHEN " + PUBLIC_PROBLEM + " THEN 1 ELSE 0 END AS available FROM edu_course_problem cp LEFT JOIN problem p ON p.id=cp.pid WHERE cp.course_id=? ";
        if (!edit) fields += "AND " + PUBLIC_PROBLEM;
        List<Map<String, Object>> problems = db.queryForList(fields + " ORDER BY cp.sort_order,cp.id", id);
        List<Object> args = new ArrayList<>(); args.add(access.uid);
        for (Map<String, Object> problem : problems) if (number(problem, "available") == 1) args.add(problem.get("pid"));
        Set<Long> completed = new HashSet<>();
        if (args.size() > 1) completed.addAll(db.queryForList("SELECT DISTINCT pid FROM judge WHERE uid=? AND status=0 AND cid=0 AND gid IS NULL AND pid IN (" + marks(args.size() - 1) + ")", Long.class, args.toArray()));
        int completedCount = 0;
        for (Map<String, Object> problem : problems) {
            boolean ac = number(problem, "available") == 1 && completed.contains(number(problem, "pid"));
            problem.put("completed", ac); if (ac) completedCount++;
        }
        result.put("problems", problems); result.put("completedCount", completedCount);
        result.put("unavailableCount", problems.stream().filter(problem -> number(problem, "available") != 1).count());
        return result;
    }

    @Transactional
    public Map<String, Object> create(String title, String description) {
        Access access = new Access(); access.requireTeacher(); validate(title, description, 0);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO edu_course(title,description,owner_uid) VALUES (?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, title.trim()); statement.setString(2, description); statement.setString(3, access.uid); return statement;
        }, key);
        return detail(Objects.requireNonNull(key.getKey()).longValue());
    }
    @Transactional
    public Map<String, Object> update(long id, String title, String description, int status, Long expectedRevision) {
        Access access = new Access(); Map<String, Object> row = course(id, true); requireEdit(access, row); revision(row, expectedRevision); validate(title, description, status);
        // A locking read sees references committed while this transaction was
        // waiting for the course lock; a snapshot COUNT could miss an attach.
        if (status != 1 && !db.queryForList("SELECT id FROM edu_class_course WHERE course_id=? FOR UPDATE", Long.class, id).isEmpty())
            throw new EduException(400, "课程仍被班级引用，请先解除全部引用再取消发布或归档");
        db.update("UPDATE edu_course SET title=?,description=?,status=?,revision=revision+1 WHERE id=?", title.trim(), description, status, id);
        return detail(id);
    }
    @Transactional
    public Map<String, Object> replaceProblems(long id, List<Long> ids, Long expectedRevision) {
        Access access = new Access(); Map<String, Object> row = course(id, true); requireEdit(access, row); revision(row, expectedRevision);
        if (ids == null || ids.size() > 500 || ids.stream().anyMatch(value -> value == null || value <= 0) || new HashSet<>(ids).size() != ids.size())
            throw new EduException(400, "课程题目须为不重复的有效题目ID，最多500题");
        if (!ids.isEmpty()) {
            List<Long> eligible = db.queryForList("SELECT p.id FROM problem p WHERE p.id IN (" + marks(ids.size()) + ") AND " + PUBLIC_PROBLEM + " ORDER BY p.id FOR UPDATE", Long.class, ids.toArray());
            if (eligible.size() != ids.size()) throw new EduException(400, "只能加入公开且非团队专属的现有题目，请移除不可用题目");
        }
        db.update("DELETE FROM edu_course_problem WHERE course_id=?", id);
        List<Object[]> rows = new ArrayList<>(); for (int index = 0; index < ids.size(); index++) rows.add(new Object[]{id, ids.get(index), index});
        if (!rows.isEmpty()) db.batchUpdate("INSERT INTO edu_course_problem(course_id,pid,sort_order) VALUES (?,?,?)", rows);
        db.update("UPDATE edu_course SET revision=revision+1 WHERE id=?", id);
        return detail(id);
    }

    public Map<String, Object> publicProblems(int page, int limit, String keyword) {
        new Access().requireTeacher(); page = page(page); limit = limit(limit); String search = keyword(keyword);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count("SELECT COUNT(*) FROM problem p WHERE " + PUBLIC_PROBLEM + " AND (p.title LIKE ? OR p.problem_id LIKE ?)", search, search));
        result.put("records", db.queryForList("SELECT p.id,p.problem_id AS problemId,p.title,p.difficulty FROM problem p WHERE " + PUBLIC_PROBLEM +
                " AND (p.title LIKE ? OR p.problem_id LIKE ?) ORDER BY p.id DESC LIMIT ? OFFSET ?", search, search, limit, (page - 1) * limit));
        return result;
    }
    public Map<String, Object> classCourses(long id, int page, int limit) {
        classes.detail(id); Access access = new Access(); page = page(page); limit = limit(limit);
        List<Object> args = Collections.singletonList(id);
        return paged(COLUMNS + " JOIN edu_class_course selected ON selected.course_id=c.id WHERE selected.class_id=? AND c.status=1",
                "SELECT COUNT(*) FROM edu_class_course selected JOIN edu_course c ON c.id=selected.course_id WHERE selected.class_id=? AND c.status=1", args,
                page, limit, "selected.sort_order,selected.id", access);
    }
    private Map<String, Object> managedClass(long id, Access access) {
        List<Map<String, Object>> rows = db.queryForList("SELECT id,status FROM edu_class WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new EduException(404, "班级不存在");
        if (!classes.canManage(access.uid, id)) throw new EduException(403, "无权管理此班级");
        return rows.get(0);
    }
    @Transactional
    public void attach(long classId, List<Long> ids) {
        Access access = new Access(); Map<String, Object> target = managedClass(classId, access);
        if (number(target, "status") != 0) throw new EduException(400, "班级已归档，请先恢复班级");
        if (ids == null || ids.isEmpty() || ids.size() > 50 || ids.stream().anyMatch(value -> value == null || value <= 0)) throw new EduException(400, "每次选择1至50门有效课程");
        // Lock in ID order even when classes select overlapping batches in a different UI order.
        for (long id : new TreeSet<>(ids)) if (number(course(id, true), "status") != 1) throw new EduException(400, "班级只能引用已发布课程");
        int order = db.queryForObject("SELECT COALESCE(MAX(sort_order),-1)+1 FROM edu_class_course WHERE class_id=?", Integer.class, classId);
        for (long id : new LinkedHashSet<>(ids)) db.update("INSERT INTO edu_class_course(class_id,course_id,sort_order,created_by) VALUES (?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE class_id=VALUES(class_id)", classId, id, order++, access.uid);
    }
    @Transactional
    public void detach(long classId, long courseId) {
        Access access = new Access(); managedClass(classId, access); course(courseId, true);
        db.update("DELETE FROM edu_class_course WHERE class_id=? AND course_id=?", classId, courseId);
    }
}
