package top.hcode.hoj.edu;

import org.apache.shiro.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import top.hcode.hoj.common.exception.StatusFailException;
import top.hcode.hoj.shiro.AccountProfile;
import org.springframework.stereotype.Service;
import java.util.*;

/** A user's permanent practice level is shared by all name displays. */
@Service
public class PracticeLevelService {
    private final JdbcTemplate db;
    public PracticeLevelService(JdbcTemplate db) { this.db = db; }
    public static final String PROFILE_LOCKED = "正式账号达到绿色等级（1 分）后可修改资料";
    private static final long GREEN = 1;
    private static final long[] MINIMUMS = {0, GREEN, 30, 60, 100, 160, 240, 400};
    private static final String[] KEYS = {"newbie", "pupil", "specialist", "expert", "candidate", "master", "grandmaster", "legend"};
    private static final String[] TITLES = {"初学", "起步", "进阶", "熟练", "优秀", "精英", "卓越", "传奇"};
    private static final String[] COLORS = {"#777777", "#008000", "#008b8b", "#164dcc", "#a020c0", "#c77700", "#e02020", "#b50920"};

    public static Map<String, Object> level(long solved) {
        long score = Math.max(0, solved); int index = 0;
        while (index + 1 < MINIMUMS.length && score >= MINIMUMS[index + 1]) index++;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("solvedCount", score); row.put("score", score); row.put("key", KEYS[index]);
        row.put("title", TITLES[index]); row.put("color", COLORS[index]);
        row.put("minimum", MINIMUMS[index]); row.put("nextMinimum", index + 1 < MINIMUMS.length ? MINIMUMS[index + 1] : null);
        return row;
    }
    public List<Map<String, Object>> rules() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long minimum : MINIMUMS) rows.add(level(minimum));
        return rows;
    }
    private List<String> clean(List<String> values, int maximumLength) {
        if (values == null) return Collections.emptyList();
        if (values.size() > 100) throw new EduException(400, "每次最多查询100位用户");
        Set<String> selected = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.trim().isEmpty() || value.length() > maximumLength)
                throw new EduException(400, "用户标识无效");
            selected.add(value.trim());
        }
        return new ArrayList<>(selected);
    }
    public List<Map<String, Object>> users(List<String> requestedUids, List<String> requestedNames) {
        return lookup(requestedUids, requestedNames, false);
    }

    public List<Map<String, Object>> adminUsers(List<String> uids) {
        return lookup(uids, null, true);
    }

    private List<Map<String, Object>> lookup(List<String> requestedUids, List<String> requestedNames, boolean admin) {
        List<String> uids = clean(requestedUids, 32), names = clean(requestedNames, 100);
        if (uids.size() + names.size() > 100) throw new EduException(400, "每次最多查询100位用户");
        if (uids.isEmpty() && names.isEmpty()) return Collections.emptyList();
        List<String> filters = new ArrayList<>(); List<Object> args = new ArrayList<>();
        if (!uids.isEmpty()) { filters.add("u.uuid IN (" + String.join(",", Collections.nCopies(uids.size(), "?")) + ")"); args.addAll(uids); }
        if (!names.isEmpty()) { filters.add("u.username IN (" + String.join(",", Collections.nCopies(names.size(), "?")) + ")"); args.addAll(names); }
        // Match the all-time ACM ranking: accepted problems, deduplicated by stable problem ID.
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT u.uuid AS uid,u.username,COALESCE(o.mode,'auto') AS accountMode," +
                "COALESCE(o.mode, CASE WHEN EXISTS (SELECT 1 FROM edu_class_member m " +
                "JOIN edu_growth_class gc ON gc.class_id=m.class_id JOIN edu_class c ON c.id=m.class_id " +
                "WHERE m.uid=u.uuid AND m.role='STUDENT' AND m.status=0 AND c.status=0) " +
                "OR EXISTS (SELECT 1 FROM user_role ur JOIN role r ON r.id=ur.role_id " +
                "WHERE ur.uid=u.uuid AND r.status=0 AND r.role IN ('root','admin','problem_admin','teacher')) " +
                "THEN 'formal' ELSE 'trial' END) AS accountType," +
                "(SELECT COUNT(DISTINCT a.pid) FROM user_acproblem a WHERE a.uid=u.uuid) AS solvedCount " +
                "FROM user_info u LEFT JOIN edu_growth_account o ON o.uid=u.uuid WHERE " +
                (admin ? "" : "u.status=0 AND ") + "(" + String.join(" OR ", filters) + ")", args.toArray());
        for (Map<String, Object> row : rows) {
            long solved = ((Number) row.get("solvedCount")).longValue();
            long score = "formal".equals(row.get("accountType")) ? solved : 0;
            row.putAll(level(score));
            row.put("solvedCount", solved);
            row.put("canEditProfile", score >= GREEN);
            if (!admin) row.remove("accountMode");
        }
        return rows;
    }

    public Map<String, Object> currentProfileAccess() {
        Object principal = SecurityUtils.getSubject().getPrincipal();
        if (!(principal instanceof AccountProfile)) return Collections.emptyMap();
        List<Map<String, Object>> rows = users(Collections.singletonList(((AccountProfile) principal).getUid()), null);
        return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }

    public boolean canEditCurrentProfile() {
        return Boolean.TRUE.equals(currentProfileAccess().get("canEditProfile"));
    }

    public static void validateAccountMode(String mode) throws StatusFailException {
        if (mode != null && !Arrays.asList("auto", "trial", "formal").contains(mode)) {
            throw new StatusFailException("成长账号类型无效");
        }
    }

    public void setAccountMode(String uid, String mode) {
        if ("auto".equals(mode)) {
            db.update("DELETE FROM edu_growth_account WHERE uid=?", uid);
        } else {
            db.update("INSERT INTO edu_growth_account(uid,mode) VALUES (?,?) ON DUPLICATE KEY UPDATE mode=VALUES(mode)", uid, mode);
        }
    }
}
