package top.hcode.hoj.edu;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** School class names, restricted to active student memberships of the displayed users. */
public final class RankingSchools {
    private RankingSchools() {}

    public static Map<String, List<String>> find(JdbcTemplate db, Collection<String> userIds) {
        Set<String> ids = new LinkedHashSet<>(userIds);
        ids.remove(null);
        if (ids.isEmpty()) return Collections.emptyMap();

        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT m.uid,c.name AS school FROM edu_class_member m " +
                "JOIN edu_class c ON c.id=m.class_id " +
                "WHERE m.uid IN (" + placeholders + ") AND m.role='STUDENT' AND m.status=0 AND c.status=0 " +
                // The original school import and the subsequent roster sync use these descriptions.
                "AND (c.description LIKE '按原账号或导入标记中的学校归类%' " +
                "OR c.description LIKE '%课表名单 · 校内班') ORDER BY c.id";
        Map<String, Set<String>> names = new LinkedHashMap<>();
        for (Map<String, Object> row : db.queryForList(sql, ids.toArray())) {
            names.computeIfAbsent((String) row.get("uid"), id -> new LinkedHashSet<>())
                    .add((String) row.get("school"));
        }
        Map<String, List<String>> schools = new LinkedHashMap<>();
        names.forEach((uid, values) -> schools.put(uid, new ArrayList<>(values)));
        return schools;
    }
}
