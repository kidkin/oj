package top.hcode.hoj.edu;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import top.hcode.hoj.common.exception.StatusFailException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeeklyReleaseServiceTest {
    @Test void aNewPublicationDefaultsToNow() throws Exception {
        long before = System.currentTimeMillis();
        Date result = new WeeklyReleaseService(null).validate(null, null);
        assertTrue(result.getTime() >= before && result.getTime() <= System.currentTimeMillis());
    }
    @Test void editingKeepsTheExistingPublicationDate() throws Exception {
        Date old = new Date(1600000000000L);
        assertEquals(old, new WeeklyReleaseService(null).validate(null, old));
        Date requested = new Date(1700000000000L);
        assertEquals(requested, new WeeklyReleaseService(null).validate(requested, old));
    }
    @Test void futureAndInvalidDatesAreRejected() {
        WeeklyReleaseService service = new WeeklyReleaseService(null);
        assertThrows(StatusFailException.class, () -> service.validate(new Date(0), null));
        assertThrows(StatusFailException.class, () -> service.validate(new Date(System.currentTimeMillis()+3600000), null));
    }
    @Test void legacyTrainingUsesCreationDateUntilExplicitlySet() {
        List<Date> dates = new ArrayList<>();
        JdbcTemplate db = mock(JdbcTemplate.class, call -> {
            if (call.getMethod().getName().equals("query")) return dates;
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        WeeklyReleaseService service = new WeeklyReleaseService(db);
        Date created = new Date(1600000000000L), published = new Date(1700000000000L);
        assertEquals(created, service.resolve(10L, created));
        dates.add(published);
        assertEquals(published, service.resolve(10L, created));
    }
}
