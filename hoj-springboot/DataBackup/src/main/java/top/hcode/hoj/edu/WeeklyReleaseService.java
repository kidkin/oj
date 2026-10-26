package top.hcode.hoj.edu;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import top.hcode.hoj.common.exception.StatusFailException;
import java.util.Date;
import java.util.List;

/** Publication is independent of later edits. Legacy training keeps its creation date. */
@Service
public class WeeklyReleaseService {
    private final JdbcTemplate db;
    public WeeklyReleaseService(JdbcTemplate db) { this.db = db; }

    public Date resolve(Long id, Date fallback) {
        List<Date> dates = db.query("SELECT published_at FROM edu_weekly_release WHERE training_id=?",
                (rs, row) -> rs.getTimestamp(1), id);
        return dates.isEmpty() ? fallback : dates.get(0);
    }

    public Date validate(Date requested, Date fallback) throws StatusFailException {
        Date value = requested == null ? (fallback == null ? new Date() : fallback) : requested;
        if (value.getTime() < 946656000000L || value.getTime() > System.currentTimeMillis() + 60000L)
            throw new StatusFailException("发布时间须在2000年以后，且不能晚于当前时间");
        return value;
    }

    public void save(Long id, Date date) {
        db.update("INSERT INTO edu_weekly_release(training_id,published_at) VALUES (?,?) " +
                "ON DUPLICATE KEY UPDATE published_at=VALUES(published_at)", id, date);
    }
}
