package top.hcode.hoj.edu;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PracticeLevelServiceTest {
    @Test void fixedLevelsAndCompetitiveQuotasHaveExactBoundaries() {
        assertEquals("newbie", PracticeLevelService.level(1, 0, 0).get("key"));
        assertEquals("pupil", PracticeLevelService.level(2, 99, 100).get("key"));
        assertEquals("specialist", PracticeLevelService.level(60, 99, 100).get("key"));
        assertEquals("expert", PracticeLevelService.level(120, 99, 100).get("key"));
        assertEquals("expert", PracticeLevelService.level(199, 1, 100).get("key"));
        assertEquals("candidate", PracticeLevelService.level(200, 20, 100).get("key"));
        assertEquals("expert", PracticeLevelService.level(900, 21, 100).get("key"));
        assertEquals("master", PracticeLevelService.level(320, 5, 100).get("key"));
        assertEquals("candidate", PracticeLevelService.level(900, 6, 100).get("key"));
        assertEquals("grandmaster", PracticeLevelService.level(480, 1, 100).get("key"));
        assertEquals("master", PracticeLevelService.level(900, 2, 100).get("key"));
        assertEquals("master", PracticeLevelService.level(900, 1, 99).get("key"));
        assertEquals(7, new PracticeLevelService(null).rules().size());
    }

    private Map<String, Object> user(String uid, String username, String type, long solved) {
        Map<String, Object> row = new HashMap<>();
        row.put("uid", uid);
        row.put("username", username);
        row.put("accountType", type);
        row.put("accountMode", "auto");
        row.put("solvedCount", solved);
        return row;
    }

    @Test void leaderboardAndUsernameUseTheSameScoreAndColor() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        Map<String, Object> formal = user("u1", "Student", "formal", 27);
        Map<String, Object> score = new HashMap<>(formal);
        score.put("scoreUnits", 121L);
        score.put("solvedCount", 25L);
        when(db.queryForList(anyString())).thenReturn(Collections.singletonList(score));
        when(db.queryForList(anyString(), eq("u1"), eq("Student"))).thenReturn(Collections.singletonList(formal));
        PracticeLevelService service = new PracticeLevelService(db);
        Map<String, Object> level = service.users(Arrays.asList("u1", "u1"), Collections.singletonList("Student")).get(0);
        List<?> ranked = (List<?>) service.rating(1, 20, "student").get("records");
        assertEquals(60.5, level.get("score"));
        assertEquals("expert", level.get("key"));
        assertEquals(25L, level.get("solvedCount"));
        assertEquals(level.get("score"), ((Map<?, ?>) ranked.get(0)).get("score"));
        assertEquals(level.get("color"), ((Map<?, ?>) ranked.get(0)).get("color"));
        assertTrue(((List<?>) service.rating(Integer.MAX_VALUE, 100, "").get("records")).isEmpty());
        verify(db).queryForList(contains("MAX(GREATEST"));
    }

    @Test void trialAccountsKeepSolvedHistoryButHaveZeroGrowthAndLockedSettings() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        Map<String, Object> trial = user("trial", "Trial", "trial", 61);
        when(db.queryForList(anyString(), eq("trial"))).thenReturn(Collections.singletonList(trial));
        Map<String, Object> level = new PracticeLevelService(db).users(Collections.singletonList("trial"), null).get(0);
        assertEquals(0.0, level.get("score"));
        assertEquals(61L, level.get("solvedCount"));
        assertEquals(false, level.get("canEditProfile"));
        assertFalse(level.containsKey("accountMode"));
        verify(db, never()).queryForList(anyString());
    }

    @Test void emptyLookupsAndInvalidBatchesNeverHitTheDatabase() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        PracticeLevelService service = new PracticeLevelService(db);
        assertTrue(service.users(null, null).isEmpty());
        assertThrows(EduException.class, () -> service.users(Collections.nCopies(101, "u"), null));
        assertThrows(EduException.class, () -> service.users(Arrays.asList("u", ""), null));
        verifyNoInteractions(db);
    }
}
