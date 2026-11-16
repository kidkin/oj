package top.hcode.hoj.edu;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Account profile schools for the users displayed on a leaderboard page. */
public final class RankingSchools {
    private RankingSchools() {}

    public static Map<String, String> find(JdbcTemplate db, Collection<String> userIds) {
        Set<String> ids = new LinkedHashSet<>(userIds);
        ids.remove(null);
        if (ids.isEmpty()) return Collections.emptyMap();

        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT uuid AS uid,school FROM user_info WHERE uuid IN (" + placeholders + ") AND school IS NOT NULL";
        Map<String, String> schools = new LinkedHashMap<>();
        for (Map<String, Object> row : db.queryForList(sql, ids.toArray())) {
            String school = StringUtils.trimWhitespace((String) row.get("school"));
            if (StringUtils.hasText(school)) schools.put((String) row.get("uid"), school);
        }
        return schools;
    }
}
