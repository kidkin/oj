package top.hcode.hoj.edu;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PracticeLevelServiceTest {
    @Test void everyScoreBoundaryUsesOneSharedLevelRule() {
        PracticeLevelService service = new PracticeLevelService(null);
        List<Map<String, Object>> rules = service.rules();
        assertEquals(8, rules.size());
        for (int i = 0; i < rules.size(); i++) {
            long threshold = ((Number) rules.get(i).get("minimum")).longValue();
            assertEquals(rules.get(i).get("key"), PracticeLevelService.level(threshold).get("key"));
            if (i > 0) assertEquals(rules.get(i - 1).get("key"), PracticeLevelService.level(threshold - 1).get("key"));
            assertEquals(threshold, PracticeLevelService.level(threshold).get("score"));
        }
        assertEquals(0L, PracticeLevelService.level(-1).get("score"));
    }
    @Test void resolvesMultipleUsernamesAndUidsWithOneBoundQueryAndDistinctProblems() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        Map<String, Object> row = new HashMap<>(); row.put("uid", "u1"); row.put("username", "学生"); row.put("solvedCount", 61L); row.put("accountType", "formal");
        when(db.queryForList(anyString(), eq("u1"), eq("学生"))).thenReturn(Collections.singletonList(row));
        List<Map<String, Object>> levels = new PracticeLevelService(db).users(Arrays.asList("u1", "u1"), Collections.singletonList("学生"));
        assertEquals("expert", levels.get(0).get("key")); assertEquals(61L, levels.get(0).get("score"));
        verify(db).queryForList(contains("COUNT(DISTINCT a.pid)"), eq("u1"), eq("学生"));
    }
    @Test void trialAccountsKeepSolvedHistoryButHaveZeroGrowthAndLockedSettings() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        Map<String, Object> trial = new HashMap<>(), formal = new HashMap<>();
        trial.put("accountType", "trial"); trial.put("accountMode", "auto"); trial.put("solvedCount", 61L);
        formal.put("accountType", "formal"); formal.put("accountMode", "formal"); formal.put("solvedCount", 1L);
        when(db.queryForList(anyString(), eq("trial"), eq("formal"))).thenReturn(Arrays.asList(trial, formal));
        List<Map<String, Object>> levels = new PracticeLevelService(db).users(Arrays.asList("trial", "formal"), null);
        assertEquals(0L, levels.get(0).get("score"));
        assertEquals(61L, levels.get(0).get("solvedCount"));
        assertEquals(false, levels.get(0).get("canEditProfile"));
        assertEquals("pupil", levels.get(1).get("key"));
        assertEquals(true, levels.get(1).get("canEditProfile"));
        assertFalse(levels.get(1).containsKey("accountMode"));
    }
    @Test void emptyLookupsAndInvalidBatchesNeverHitTheDatabase() {
        JdbcTemplate db = mock(JdbcTemplate.class); PracticeLevelService service = new PracticeLevelService(db);
        assertTrue(service.users(null, null).isEmpty());
        assertThrows(EduException.class, () -> service.users(Collections.nCopies(101, "u"), null));
        assertThrows(EduException.class, () -> service.users(Arrays.asList("u", ""), null));
        assertThrows(EduException.class, () -> service.users(null, Collections.singletonList(String.join("", Collections.nCopies(101, "a")))));
        verifyNoInteractions(db);
    }
}
