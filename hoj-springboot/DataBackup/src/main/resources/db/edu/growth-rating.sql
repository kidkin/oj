WITH formal_accounts AS (
    SELECT u.uuid AS uid, u.username
    FROM user_info u
    LEFT JOIN edu_growth_account o ON o.uid = u.uuid
    WHERE u.status = 0 AND /*account_type*/ = 'formal'
), weekly_problems AS (
    SELECT tp.pid, MIN(COALESCE(w.published_at, t.gmt_create)) AS published_at
    FROM training_problem tp
    JOIN training t ON t.id = tp.tid
    LEFT JOIN edu_weekly_release w ON w.training_id = t.id
    WHERE t.status = 1 AND t.is_group = 0 AND t.auth = 'Public'
      AND COALESCE(w.published_at, t.gmt_create) <= CURRENT_TIMESTAMP
    GROUP BY tp.pid
), assigned_problems AS (
    SELECT DISTINCT m.uid, cp.pid
    FROM edu_class_member m
    JOIN formal_accounts f ON f.uid = m.uid
    JOIN edu_class c ON c.id = m.class_id AND c.status = 0
    JOIN edu_class_course cc ON cc.class_id = m.class_id
    JOIN edu_course course ON course.id = cc.course_id AND course.status = 1
    JOIN edu_course_problem cp ON cp.course_id = course.id
    WHERE m.status = 0 AND m.role = 'STUDENT'
), accepted_problems AS (
    SELECT j.uid, j.pid, p.difficulty, MAX(GREATEST(
        /*normal*/,
        CASE WHEN a.pid IS NOT NULL THEN /*course*/ ELSE /*normal*/ END,
        CASE WHEN w.published_at <= j.submit_time THEN /*weekly*/ ELSE /*normal*/ END,
        CASE WHEN j.submit_time >= c.start_time AND j.submit_time < c.end_time THEN /*contest*/ ELSE /*normal*/ END
    )) AS multiplier
    FROM judge j
    JOIN formal_accounts f ON f.uid = j.uid
    JOIN problem p ON p.id = j.pid
    LEFT JOIN assigned_problems a ON a.uid = j.uid AND a.pid = j.pid
    LEFT JOIN weekly_problems w ON w.pid = j.pid
    LEFT JOIN contest c ON c.id = j.cid AND c.visible = 1 AND c.end_time <= CURRENT_TIMESTAMP
    WHERE j.status = 0
      AND NOT EXISTS (
          SELECT 1 FROM edu_growth_invalid_submission flag
          WHERE flag.submit_id = j.submit_id AND flag.restored_at IS NULL
      )
      AND (
        (COALESCE(j.cid, 0) = 0 AND p.auth = 1)
        OR (c.id IS NOT NULL AND EXISTS (
            SELECT 1 FROM contest_problem cp WHERE cp.cid = c.id AND cp.pid = j.pid
        ))
    )
    GROUP BY j.uid, j.pid, p.difficulty
), scores AS (
    SELECT uid, COUNT(*) AS solved_count, SUM((/*base_points*/) * multiplier) AS score_units
    FROM accepted_problems
    GROUP BY uid
)
SELECT f.uid, f.username, COALESCE(s.solved_count, 0) AS solvedCount, COALESCE(s.score_units, 0) AS scoreUnits
FROM formal_accounts f
LEFT JOIN scores s ON s.uid = f.uid
ORDER BY scoreUnits DESC, solvedCount DESC, f.uid
