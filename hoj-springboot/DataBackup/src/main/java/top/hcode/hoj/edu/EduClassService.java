package top.hcode.hoj.edu;

import org.apache.shiro.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.shiro.ShiroConstant;
import top.hcode.hoj.utils.RedisUtils;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

@Service
public class EduClassService {
    private final JdbcTemplate db;
    private final RedisUtils redis;
    public EduClassService(JdbcTemplate db, RedisUtils redis) { this.db = db; this.redis = redis; }

    protected String actor() {
        AccountProfile profile = (AccountProfile) SecurityUtils.getSubject().getPrincipal();
        if (profile == null || count("SELECT COUNT(*) FROM user_info WHERE uuid=? AND status=0", profile.getUid()) != 1)
            throw new EduException(401, "请先使用有效账号登录");
        return profile.getUid();
    }
    private long count(String sql, Object... args) { return db.queryForObject(sql, args, Long.class); }
    boolean hasRole(String uid, String role) {
        return count("SELECT COUNT(*) FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.uid=? AND r.role=? AND r.status=0", uid, role) > 0;
    }
    private boolean root(String uid) { return hasRole(uid, "root"); }
    private boolean teacher(String uid) { return root(uid) || hasRole(uid, "teacher"); }
    private void requireRoot(String uid) { if (!root(uid)) throw new EduException(403, "仅超级管理员可管理教师授权"); }
    private void requireTeacher(String uid) { if (!teacher(uid)) throw new EduException(403, "需要教师身份"); }

    public Map<String, Object> me() {
        String uid = actor(); Map<String, Object> result = new LinkedHashMap<>();
        result.put("uid", uid); result.put("isRoot", root(uid)); result.put("isTeacher", teacher(uid));
        return result;
    }

    private static final String CLASS_COLUMNS = "SELECT c.id,c.name,c.description,c.status,c.owner_uid AS ownerUid," +
            "u.username AS ownerUsername,c.gmt_create AS gmtCreate,c.gmt_modified AS gmtModified," +
            "(SELECT COUNT(*) FROM edu_class_member m JOIN user_info enrolled ON enrolled.uuid=m.uid WHERE m.class_id=c.id AND m.status=0 AND m.role='STUDENT') AS studentCount " +
            "FROM edu_class c LEFT JOIN user_info u ON u.uuid=c.owner_uid ";

    public Map<String, Object> classes(int page, int limit, String keyword) {
        String uid = actor(); page = Math.max(page, 1); limit = Math.max(1, Math.min(limit, 50));
        keyword = keyword == null ? "" : keyword.trim();
        if (keyword.length() > 128 || page > 100000) throw new EduException(400, "查询参数过长");
        String where = " WHERE c.name LIKE ? "; List<Object> params = new ArrayList<>(); params.add("%" + keyword + "%");
        if (!root(uid)) {
            where += "AND EXISTS (SELECT 1 FROM edu_class_member m WHERE m.class_id=c.id AND m.uid=? AND m.status=0 AND (c.status=0 OR (m.role='TEACHER' AND ?=1))) ";
            params.add(uid); params.add(teacher(uid) ? 1 : 0);
        }
        long total = count("SELECT COUNT(*) FROM edu_class c " + where, params.toArray());
        List<Object> paged = new ArrayList<>(params); paged.add(limit); paged.add((page - 1) * limit);
        List<Map<String, Object>> records = db.queryForList(CLASS_COLUMNS + where + "ORDER BY c.id DESC LIMIT ? OFFSET ?", paged.toArray());
        for (Map<String, Object> row : records) row.put("canManage", canManage(uid, ((Number) row.get("id")).longValue()));
        Map<String, Object> result = new LinkedHashMap<>(); result.put("records", records); result.put("total", total); return result;
    }

    private Map<String, Object> classRow(long id, boolean lock) {
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM edu_class WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw new EduException(404, "班级不存在");
        return rows.get(0);
    }
    private boolean member(String uid, long id) {
        return count("SELECT COUNT(*) FROM edu_class_member WHERE class_id=? AND uid=? AND status=0", id, uid) > 0;
    }
    boolean canManage(String uid, long id) {
        return root(uid) || (teacher(uid) && count("SELECT COUNT(*) FROM edu_class_member WHERE class_id=? AND uid=? AND role='TEACHER' AND status=0", id, uid) > 0);
    }
    private void requireManager(String uid, long id) {
        if (!canManage(uid, id)) throw new EduException(403, "无权管理此班级");
    }
    private void requireActive(Map<String, Object> row) {
        if (((Number) row.get("status")).intValue() != 0) throw new EduException(400, "班级已归档，请先恢复班级");
    }

    public Map<String, Object> detail(long id) {
        String uid = actor(); Map<String, Object> row = classRow(id, false);
        boolean manage = canManage(uid, id);
        if (!manage && (!member(uid, id) || ((Number) row.get("status")).intValue() != 0)) throw new EduException(403, "无权访问此班级");
        Map<String, Object> result = db.queryForMap(CLASS_COLUMNS + "WHERE c.id=?", id); result.put("canManage", manage); return result;
    }

    private void validateClass(String name, String description, int status) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 128) throw new EduException(400, "班级名称须为1至128个字符");
        if (description == null || description.length() > 10000) throw new EduException(400, "班级介绍不能超过10000个字符");
        if (status != 0 && status != 1) throw new EduException(400, "班级状态无效");
    }
    @Transactional
    public Map<String, Object> create(String name, String description) {
        String uid = actor(); requireTeacher(uid); validateClass(name, description, 0);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO edu_class(name,description,owner_uid) VALUES (?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name.trim()); statement.setString(2, description); statement.setString(3, uid); return statement;
        }, key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        db.update("INSERT INTO edu_class_member(class_id,uid,role) VALUES (?,?,'TEACHER')", id, uid);
        return detail(id);
    }
    @Transactional
    public void update(long id, String name, String description, int status) {
        String uid = actor(); classRow(id, true); requireManager(uid, id); validateClass(name, description, status);
        db.update("UPDATE edu_class SET name=?,description=?,status=? WHERE id=?", name.trim(), description, status, id);
    }

    public Map<String, Object> members(long id, int page, int limit) {
        String uid = actor(); classRow(id, false); requireManager(uid, id);
        page = Math.max(1, page); limit = Math.max(1, Math.min(limit, 100));
        if (page > 100000) throw new EduException(400, "页码过大");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count("SELECT COUNT(*) FROM edu_class_member m JOIN user_info u ON u.uuid=m.uid WHERE m.class_id=? AND m.status=0", id));
        result.put("records", db.queryForList("SELECT m.uid,m.role,u.username,u.nickname,u.realname,u.number,u.status AS accountStatus,m.gmt_create AS gmtCreate " +
                "FROM edu_class_member m JOIN user_info u ON u.uuid=m.uid WHERE m.class_id=? AND m.status=0 ORDER BY m.role,m.id LIMIT ? OFFSET ?", id, limit, (page-1)*limit));
        return result;
    }
    @Transactional
    public void addMembers(long id, List<String> usernames, String role) {
        String uid = actor(); Map<String, Object> row = classRow(id, true); requireManager(uid, id); requireActive(row);
        if (!Arrays.asList("STUDENT", "TEACHER").contains(role)) throw new EduException(400, "成员角色无效");
        if (usernames == null || usernames.isEmpty() || usernames.size() > 100) throw new EduException(400, "每次添加1至100个已有账号");
        Set<String> users = new LinkedHashSet<>();
        for (String username : usernames) {
            if (username == null || username.trim().isEmpty() || username.length() > 100) throw new EduException(400, "账号无效");
            List<String> matches = db.queryForList("SELECT uuid FROM user_info WHERE username=? AND status=0", String.class, username.trim());
            if (matches.isEmpty()) throw new EduException(400, "账号不存在或已停用：" + username.trim());
            String target = matches.get(0);
            if (target.equals(row.get("owner_uid")) && !"TEACHER".equals(role)) throw new EduException(400, "不能将班级创建者改为学生");
            if ("TEACHER".equals(role) && !teacher(target)) throw new EduException(400, "该账号尚未获得教师授权：" + username.trim());
            users.add(target);
        }
        for (String target : users) db.update("INSERT INTO edu_class_member(class_id,uid,role,status) VALUES (?,?,?,0) " +
                "ON DUPLICATE KEY UPDATE role=VALUES(role),status=0", id, target, role);
    }
    @Transactional
    public void removeMember(long id, String target) {
        String uid = actor(); Map<String, Object> row = classRow(id, true); requireManager(uid, id); requireActive(row);
        if (Objects.equals(row.get("owner_uid"), target)) throw new EduException(400, "不能移除班级创建者");
        db.update("UPDATE edu_class_member SET status=1 WHERE class_id=? AND uid=?", id, target);
    }

    public List<Map<String, Object>> teachers() {
        requireRoot(actor());
        return db.queryForList("SELECT DISTINCT u.uuid AS uid,u.username,u.nickname,u.status AS accountStatus FROM user_info u " +
                "JOIN user_role ur ON ur.uid=u.uuid JOIN role r ON r.id=ur.role_id WHERE r.role='teacher' AND r.status=0 ORDER BY u.username");
    }
    @Transactional
    public void grantTeacher(String username) {
        requireRoot(actor());
        if (username == null || username.trim().isEmpty() || username.length() > 100) throw new EduException(400, "请填写已有账号");
        List<String> users = db.queryForList("SELECT uuid FROM user_info WHERE username=? AND status=0 FOR UPDATE", String.class, username.trim());
        if (users.isEmpty()) throw new EduException(400, "账号不存在或已停用");
        String uid = users.get(0);
        Long roleId = db.queryForObject("SELECT id FROM role WHERE role='teacher' AND status=0", Long.class);
        List<Long> grants = db.queryForList("SELECT id FROM user_role WHERE uid=? AND role_id=? FOR UPDATE", Long.class, uid, roleId);
        if (grants.isEmpty()) db.update("INSERT INTO user_role(uid,role_id) VALUES (?,?)", uid, roleId);
        clearRoleCache(uid);
    }
    @Transactional
    public void revokeTeacher(String target) {
        requireRoot(actor());
        db.queryForList("SELECT uuid FROM user_info WHERE uuid=? FOR UPDATE", String.class, target);
        db.update("DELETE ur FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.uid=? AND r.role='teacher'", target);
        clearRoleCache(target);
    }

    private void clearRoleCache(String uid) {
        // Education authorization queries the database on every request, so a
        // stale Shiro cache can never retain teacher authority in this module.
        Runnable clear = () -> redis.del(ShiroConstant.SHIRO_AUTHORIZATION_CACHE + uid);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { clear.run(); }
            });
        } else clear.run();
    }
}
