package top.hcode.hoj.edu;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PracticeLevelServiceTest {
    @Test void fixedLevelsAndCompetitiveQuotasHaveExactBoundaries() {
        assertEquals("bronze", PracticeLevelService.level(1, 1, 100).get("key"));
        assertEquals("silver", PracticeLevelService.level(2, 99, 100).get("key"));
        assertEquals("gold", PracticeLevelService.level(200, 99, 100).get("key"));
        assertEquals("platinum", PracticeLevelService.level(500, 99, 100).get("key"));
        assertEquals("emerald", PracticeLevelService.level(900, 99, 100).get("key"));
        assertEquals("emerald", PracticeLevelService.level(1299, 1, 100).get("key"));
        assertEquals("challenger", PracticeLevelService.level(1300, 1, 100).get("key"));
        assertEquals("challenger", PracticeLevelService.level(1300, 3, 100).get("key"));
        assertEquals("master", PracticeLevelService.level(1300, 4, 100).get("key"));
        assertEquals("master", PracticeLevelService.level(1300, 8, 100).get("key"));
        assertEquals("diamond", PracticeLevelService.level(1300, 9, 100).get("key"));
        assertEquals("diamond", PracticeLevelService.level(1300, 0, 100).get("key"));
        assertEquals("challenger", PracticeLevelService.level(1300, 1, 1).get("key"));
        List<Map<String,Object>> rules = new PracticeLevelService(null).rules();
        assertEquals(8, rules.size());
        assertEquals(5, rules.get(6).get("seats"));
        assertEquals(3, rules.get(7).get("seats"));
        assertEquals(rules.get(5).get("minimum"), rules.get(6).get("minimum"));
        assertEquals(rules.get(5).get("minimum"), rules.get(7).get("minimum"));
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
        score.put("scoreUnits", 501L);
        score.put("solvedCount", 25L);
        when(db.queryForList(anyString())).thenReturn(Collections.singletonList(score));
        when(db.queryForList("SELECT uid,public_name FROM edu_display_name")).thenReturn(Collections.emptyList());
        when(db.queryForList(anyString(), eq("u1"), eq("Student"))).thenReturn(Collections.singletonList(formal));
        PracticeLevelService service = new PracticeLevelService(db);
        Map<String, Object> level = service.users(Arrays.asList("u1", "u1"), Collections.singletonList("Student")).get(0);
        List<?> ranked = (List<?>) service.rating(1, 20, "student").get("records");
        assertEquals(250.5, level.get("score"));
        assertEquals("platinum", level.get("key"));
        assertEquals(25L, level.get("solvedCount"));
        assertEquals(level.get("score"), ((Map<?, ?>) ranked.get(0)).get("score"));
        assertEquals(level.get("color"), ((Map<?, ?>) ranked.get(0)).get("color"));
        assertTrue(((List<?>) service.rating(Integer.MAX_VALUE, 100, "").get("records")).isEmpty());
        verify(db).queryForList(contains("MAX(GREATEST"));
    }

    @Test void staffKeepsPrivateEarningsButHasNoRankOrSeat() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        Map<String, Object> staff = user("staff", "Teacher", "formal", 30);
        staff.put("staff", 1);
        staff.put("scoreUnits", 1200L);
        Map<String, Object> student = user("student", "Student", "formal", 20);
        student.put("scoreUnits", 800L);
        when(db.queryForList(anyString())).thenReturn(Arrays.asList(staff, student));
        when(db.queryForList(anyString(), eq("staff"))).thenAnswer(call -> Collections.singletonList(new HashMap<>(staff)));
        PracticeLevelService service = new PracticeLevelService(db);
        Map<String, Object> board = service.rating(1, 20, "");
        assertEquals(1, board.get("formalCount"));
        assertEquals(1, board.get("rankedCount"));
        Map<?, ?> first = (Map<?, ?>) ((List<?>) board.get("records")).get(0);
        assertEquals("student", first.get("uid"));
        assertEquals(1, first.get("rank"));
        Map<String, Object> publicStaff = service.users(Collections.singletonList("staff"), null).get(0);
        assertEquals("#000000", publicStaff.get("color"));
        assertNull(publicStaff.get("rank"));
        assertEquals(0.0, publicStaff.get("score"));
        assertEquals(600.0, service.adminUsers(Collections.singletonList("staff")).get(0).get("score"));
    }

    @Test void trialScoresAffectOverallStandingsButNotFormalTiersOrWallet() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        Map<String, Object> trial = user("trial", "Trial", "trial", 35);
        trial.put("scoreUnits", 2200L);
        trial.put("school", "测试学校");
        Map<String, Object> formal = user("formal", "Formal", "formal", 30);
        formal.put("scoreUnits", 1800L);
        when(db.queryForList(anyString())).thenReturn(Arrays.asList(trial, formal));
        PracticeLevelService service = new PracticeLevelService(db);

        Map<String, Object> board = service.rating(1, 3, "");
        List<?> records = (List<?>) board.get("records");
        Map<?, ?> first = (Map<?, ?>) records.get(0);
        Map<?, ?> second = (Map<?, ?>) records.get(1);
        assertEquals(1, board.get("formalCount"));
        assertEquals(2, board.get("rankedCount"));
        assertEquals("trial", first.get("accountType"));
        assertEquals("测试学校", first.get("school"));
        assertEquals("", first.get("title"));
        assertEquals(1100.0, first.get("rankingScore"));
        assertEquals(0.0, first.get("score"));
        assertEquals(0, service.currentEarnedUnits("trial"));
        assertEquals(2, second.get("rank"));
        assertEquals("challenger", second.get("key"));
        assertEquals(1800, service.currentEarnedUnits("formal"));
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

    @Test void privateWalletDebitKeepsEarnedGrowthAndDoesNotChargeARetry() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        PracticeLevelService levels = mock(PracticeLevelService.class);
        GrowthAccountService service = new GrowthAccountService(db, levels);
        when(db.queryForList(contains("FOR UPDATE"), eq(String.class), eq("u1"))).thenReturn(Collections.singletonList("u1"));
        when(db.queryForList(contains("operation_id=?"), eq(Long.class), eq("u1"), eq("use:1")))
                .thenReturn(Collections.emptyList(), Collections.singletonList(3L));
        when(db.queryForObject(contains("SUM(units)"), eq(Long.class), eq("u1"))).thenReturn(0L, 3L);
        when(levels.currentEarnedUnits("u1")).thenReturn(10L);
        Map<String, Object> first = service.debit("u1", "use:1", 3);
        assertEquals(5.0, first.get("earned"));
        assertEquals(3.5, first.get("balance"));
        assertEquals(first, service.debit("u1", "use:1", 3));
        verify(db, times(1)).update(contains("INSERT INTO edu_growth_wallet_debit"), eq("u1"), eq("use:1"), eq(3L));
    }
}
