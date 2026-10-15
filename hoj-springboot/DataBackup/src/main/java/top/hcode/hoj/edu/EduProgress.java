package top.hcode.hoj.edu;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

/** Enrich only already-authorized rows, in one batch, with the requesting user's progress. */
final class EduProgress {
    static final String PUBLIC_PROBLEM = "p.auth=1 AND (p.is_group=0 OR p.is_group IS NULL) AND p.gid IS NULL";
    private EduProgress() {}

    static void classes(JdbcTemplate db, List<Map<String, Object>> records, String uid) { enrich(db, records, uid, true); }
    static void courses(JdbcTemplate db, List<Map<String, Object>> records, String uid) { enrich(db, records, uid, false); }

    private static void enrich(JdbcTemplate db, List<Map<String, Object>> records, String uid, boolean classes) {
        if (records.isEmpty()) return;
        List<Object> args = new ArrayList<>(); args.add(uid);
        args.addAll(records.stream().map(row -> row.get("id")).collect(Collectors.toList()));
        String marks = String.join(",", Collections.nCopies(records.size(), "?"));
        String completed = " LEFT JOIN (SELECT DISTINCT pid FROM judge WHERE uid=? AND status=0 AND cid=0 AND gid IS NULL) ac ON ac.pid=p.id ";
        String sql = classes
                ? "SELECT cc.class_id AS itemId,COUNT(DISTINCT c.id) AS courseCount,COUNT(DISTINCT p.id) AS problemCount,COUNT(DISTINCT ac.pid) AS completedCount " +
                  "FROM edu_class_course cc JOIN edu_course c ON c.id=cc.course_id AND c.status=1 " +
                  "LEFT JOIN edu_course_problem cp ON cp.course_id=c.id LEFT JOIN problem p ON p.id=cp.pid AND " + PUBLIC_PROBLEM +
                  completed + "WHERE cc.class_id IN (" + marks + ") GROUP BY cc.class_id"
                : "SELECT cp.course_id AS itemId,COUNT(DISTINCT p.id) AS problemCount,COUNT(DISTINCT ac.pid) AS completedCount " +
                  "FROM edu_course_problem cp JOIN problem p ON p.id=cp.pid AND " + PUBLIC_PROBLEM +
                  completed + "WHERE cp.course_id IN (" + marks + ") GROUP BY cp.course_id";
        Map<Long, Map<String, Object>> totals = new HashMap<>();
        for (Map<String, Object> row : db.queryForList(sql, args.toArray())) totals.put(((Number) row.get("itemId")).longValue(), row);
        for (Map<String, Object> row : records) {
            Map<String, Object> progress = totals.get(((Number) row.get("id")).longValue());
            row.put("problemCount", progress == null ? 0L : progress.get("problemCount"));
            row.put("completedCount", progress == null ? 0L : progress.get("completedCount"));
            if (classes) row.put("courseCount", progress == null ? 0L : progress.get("courseCount"));
        }
    }
}
