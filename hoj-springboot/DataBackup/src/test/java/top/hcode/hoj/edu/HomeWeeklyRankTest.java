package top.hcode.hoj.edu;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.dao.user.UserRecordEntityService;
import top.hcode.hoj.manager.oj.HomeManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import static org.mockito.Mockito.*;

class HomeWeeklyRankTest {
    private void check(String now, String start, String end) {
        HomeManager manager = new HomeManager();
        UserRecordEntityService records = mock(UserRecordEntityService.class);
        ReflectionTestUtils.setField(manager, "userRecordEntityService", records);
        // Deliberately give the clock a different zone from the site's calendar.
        ReflectionTestUtils.setField(manager, "weeklyRankClock", Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
        manager.getCurrentWeekACRank();
        verify(records).getCurrentWeekACRank(Date.from(Instant.parse(start)), Date.from(Instant.parse(end)));
    }

    @Test void mondayMidnightResetsInShanghaiEvenWhenUtcIsStillSunday() {
        check("2026-11-01T16:00:00Z", "2026-11-01T16:00:00Z", "2026-11-08T16:00:00Z");
    }

    @Test void sundayLastSecondStillBelongsToThePreviousWeek() {
        check("2026-11-01T15:59:59Z", "2026-10-25T16:00:00Z", "2026-11-01T16:00:00Z");
    }

    @Test void aWeekCanCrossTheYearBoundary() {
        check("2027-01-01T04:00:00Z", "2026-12-27T16:00:00Z", "2027-01-03T16:00:00Z");
    }
}
