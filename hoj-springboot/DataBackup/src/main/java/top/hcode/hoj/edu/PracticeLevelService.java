package top.hcode.hoj.edu;

import org.apache.shiro.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import top.hcode.hoj.common.exception.StatusFailException;
import top.hcode.hoj.shiro.AccountProfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Shared growth score and color for the leaderboard, profiles and usernames. */
@Service
public class PracticeLevelService {
    public static final String PROFILE_LOCKED = "正式账号达到绿色等级（1 分）后可修改资料";
    private static final long GREEN = 1;
    private static final long[] MINIMUMS = {0, GREEN, 30, 60, 100, 160, 240};
    private static final int[] TOP_PERCENT = {0, 0, 0, 0, 20, 5, 1};
    private static final String[] KEYS = {"newbie", "pupil", "specialist", "expert", "candidate", "master", "grandmaster"};
    private static final String[] TITLES = {"初学", "起步", "进阶", "熟练", "优秀", "精英", "卓越"};
    private static final String[] COLORS = {"#777777", "#008000", "#008b8b", "#164dcc", "#a020c0", "#c77700", "#e02020"};
    private static final int[] BASE_POINTS = {1, 2, 3, 5, 8, 13, 21, 34};
    // Scores are stored in half-point units during calculation, so ties compare exactly.
    private static final int NORMAL = 2, WEEKLY = 4, CONTEST = 3, COURSE = 3;
    private static final long REFRESH_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final String ACCOUNT_TYPE =
            "COALESCE(o.mode, CASE WHEN EXISTS (SELECT 1 FROM edu_class_member m " +
            "JOIN edu_growth_class gc ON gc.class_id=m.class_id JOIN edu_class c ON c.id=m.class_id " +
            "WHERE m.uid=u.uuid AND m.role='STUDENT' AND m.status=0 AND c.status=0) " +
            "OR EXISTS (SELECT 1 FROM user_role ur JOIN role r ON r.id=ur.role_id " +
            "WHERE ur.uid=u.uuid AND r.status=0 AND r.role IN ('root','admin','problem_admin','teacher')) " +
            "THEN 'formal' ELSE 'trial' END)";
    private static final String RATING_QUERY = ratingQuery();

    private final JdbcTemplate db;
    private volatile Snapshot cached;

    public PracticeLevelService(JdbcTemplate db) { this.db = db; }

    private static String ratingQuery() {
        StringBuilder points = new StringBuilder("CASE difficulty");
        for (int level = 0; level < BASE_POINTS.length; level++) {
            points.append(" WHEN ").append(level).append(" THEN ").append(BASE_POINTS[level]);
        }
        points.append(" ELSE ").append(BASE_POINTS[0]).append(" END");
        try (InputStream source = PracticeLevelService.class.getResourceAsStream("/db/edu/growth-rating.sql")) {
            if (source == null) throw new IllegalStateException("Growth rating SQL is missing");
            return StreamUtils.copyToString(source, StandardCharsets.UTF_8)
                    .replace("/*account_type*/", ACCOUNT_TYPE)
                    .replace("/*base_points*/", points.toString())
                    .replace("/*normal*/", Integer.toString(NORMAL))
                    .replace("/*weekly*/", Integer.toString(WEEKLY))
                    .replace("/*contest*/", Integer.toString(CONTEST))
                    .replace("/*course*/", Integer.toString(COURSE));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load growth rating SQL", error);
        }
    }

    static Map<String, Object> level(long units, int rank, int formalCount) {
        long scoreUnits = Math.max(0, units);
        int index = 0;
        for (int i = 1; i < MINIMUMS.length; i++) {
            long seats = (long) formalCount * TOP_PERCENT[i] / 100;
            if (scoreUnits >= MINIMUMS[i] * 2 && (TOP_PERCENT[i] == 0 || (rank > 0 && rank <= seats))) index = i;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", scoreUnits / 2.0);
        result.put("key", KEYS[index]);
        result.put("title", TITLES[index]);
        result.put("color", COLORS[index]);
        result.put("minimum", MINIMUMS[index]);
        result.put("nextMinimum", index + 1 < MINIMUMS.length ? MINIMUMS[index + 1] : null);
        result.put("rank", rank > 0 ? rank : null);
        return result;
    }

    public List<Map<String, Object>> rules() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < MINIMUMS.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", KEYS[i]);
            row.put("title", TITLES[i]);
            row.put("color", COLORS[i]);
            row.put("minimum", MINIMUMS[i]);
            row.put("topPercent", TOP_PERCENT[i]);
            rows.add(row);
        }
        return rows;
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static final class Snapshot {
        final long createdAt = System.nanoTime();
        final int formalCount;
        final List<Map<String, Object>> ranked = new ArrayList<>();
        final Map<String, Map<String, Object>> byUid = new HashMap<>();

        Snapshot(List<Map<String, Object>> rows) {
            formalCount = rows.size();
            for (Map<String, Object> row : rows) {
                long units = number(row, "scoreUnits");
                Map<String, Object> record = level(units, units > 0 ? ranked.size() + 1 : 0, formalCount);
                record.put("uid", row.get("uid"));
                record.put("username", row.get("username"));
                record.put("solvedCount", number(row, "solvedCount"));
                byUid.put((String) row.get("uid"), record);
                if (units > 0) ranked.add(record);
            }
        }
    }

    private synchronized Snapshot refresh() {
        Snapshot current = cached;
        if (current == null || System.nanoTime() - current.createdAt >= REFRESH_NANOS) {
            current = new Snapshot(db.queryForList(RATING_QUERY));
            cached = current;
        }
        return current;
    }

    private Snapshot snapshot() {
        Snapshot current = cached;
        return current != null && System.nanoTime() - current.createdAt < REFRESH_NANOS ? current : refresh();
    }

    synchronized void invalidateRating() {
        cached = null;
    }

    long currentEarnedUnits(String uid) {
        for (Map<String, Object> row : db.queryForList(RATING_QUERY)) {
            if (uid.equals(row.get("uid"))) return number(row, "scoreUnits");
        }
        return 0;
    }

    public Map<String, Object> rating(int page, int requestedLimit, String keyword) {
        Snapshot current = snapshot();
        String search = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (search.length() > 100) throw new EduException(400, "用户名搜索最多100个字符");
        List<Map<String, Object>> matches = current.ranked;
        if (!search.isEmpty()) {
            matches = new ArrayList<>();
            for (Map<String, Object> record : current.ranked) {
                if (record.get("username").toString().toLowerCase(Locale.ROOT).contains(search)) matches.add(record);
            }
        }
        int limit = Math.max(1, Math.min(100, requestedLimit));
        int offset = (int) Math.min(matches.size(), (Math.max(1L, page) - 1) * limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", matches.subList(offset, Math.min(matches.size(), offset + limit)));
        result.put("total", matches.size());
        result.put("formalCount", current.formalCount);
        result.put("rules", rules());
        result.put("basePoints", BASE_POINTS.clone());
        Map<String, Double> multipliers = new LinkedHashMap<>();
        multipliers.put("normal", NORMAL / 2.0);
        multipliers.put("course", COURSE / 2.0);
        multipliers.put("weekly", WEEKLY / 2.0);
        multipliers.put("contest", CONTEST / 2.0);
        result.put("multipliers", multipliers);
        return result;
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
        List<String> filters = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (!uids.isEmpty()) {
            filters.add("u.uuid IN (" + String.join(",", Collections.nCopies(uids.size(), "?")) + ")");
            args.addAll(uids);
        }
        if (!names.isEmpty()) {
            filters.add("u.username IN (" + String.join(",", Collections.nCopies(names.size(), "?")) + ")");
            args.addAll(names);
        }
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT u.uuid AS uid,u.username,COALESCE(o.mode,'auto') AS accountMode," +
                ACCOUNT_TYPE + " AS accountType," +
                "(SELECT COUNT(DISTINCT a.pid) FROM user_acproblem a WHERE a.uid=u.uuid) AS solvedCount " +
                "FROM user_info u LEFT JOIN edu_growth_account o ON o.uid=u.uuid WHERE " +
                (admin ? "" : "u.status=0 AND ") + "(" + String.join(" OR ", filters) + ")", args.toArray());
        Snapshot current = null;
        for (Map<String, Object> row : rows) {
            Map<String, Object> score = null;
            if ("formal".equals(row.get("accountType"))) {
                if (current == null) current = snapshot();
                score = current.byUid.get(row.get("uid"));
            }
            row.putAll(score == null ? level(0, 0, 0) : score);
            row.put("canEditProfile", number(row, "score") >= GREEN);
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
        invalidateRating();
    }
}
