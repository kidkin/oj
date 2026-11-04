package top.hcode.hoj.edu;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** A user's permanent practice level is shared by all name displays. */
@Service
public class PracticeLevelService {
    private final JdbcTemplate db;
    public PracticeLevelService(JdbcTemplate db) { this.db = db; }
    private static final long[] MINIMUMS = {0, 10, 30, 60, 100, 160, 240, 400};
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
        List<String> uids = clean(requestedUids, 32), names = clean(requestedNames, 100);
        if (uids.size() + names.size() > 100) throw new EduException(400, "每次最多查询100位用户");
        if (uids.isEmpty() && names.isEmpty()) return Collections.emptyList();
        List<String> filters = new ArrayList<>(); List<Object> args = new ArrayList<>();
        if (!uids.isEmpty()) { filters.add("u.uuid IN (" + String.join(",", Collections.nCopies(uids.size(), "?")) + ")"); args.addAll(uids); }
        if (!names.isEmpty()) { filters.add("u.username IN (" + String.join(",", Collections.nCopies(names.size(), "?")) + ")"); args.addAll(names); }
        // Match the all-time ACM ranking: accepted problems, deduplicated by stable problem ID.
        List<Map<String, Object>> rows = db.queryForList("SELECT u.uuid AS uid,u.username,COUNT(DISTINCT a.pid) AS solvedCount FROM user_info u " +
                "LEFT JOIN user_acproblem a ON a.uid=u.uuid WHERE u.status=0 AND (" + String.join(" OR ", filters) + ") GROUP BY u.uuid,u.username", args.toArray());
        for (Map<String, Object> row : rows) row.putAll(level(((Number) row.get("solvedCount")).longValue()));
        return rows;
    }
}
