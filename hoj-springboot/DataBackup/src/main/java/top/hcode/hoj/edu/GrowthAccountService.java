package top.hcode.hoj.edu;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.hcode.hoj.pojo.vo.JudgeVO;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class GrowthAccountService {
    private final JdbcTemplate db;
    private final PracticeLevelService levels;

    public GrowthAccountService(JdbcTemplate db, PracticeLevelService levels) {
        this.db = db;
        this.levels = levels;
    }

    public Set<Long> invalidSubmissions(List<Long> submitIds) {
        Set<Long> flagged = new HashSet<>();
        for (int start = 0; start < submitIds.size(); start += 200) {
            List<Long> batch = submitIds.subList(start, Math.min(start + 200, submitIds.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            flagged.addAll(db.queryForList("SELECT submit_id FROM edu_growth_invalid_submission " +
                    "WHERE restored_at IS NULL AND submit_id IN (" + placeholders + ")", Long.class, batch.toArray()));
        }
        return flagged;
    }

    public void annotateSubmissions(List<JudgeVO> records) {
        List<Long> ids = records.stream().map(JudgeVO::getSubmitId).filter(Objects::nonNull).collect(Collectors.toList());
        Set<Long> flagged = invalidSubmissions(ids);
        for (JudgeVO row : records) row.setGrowthInvalid(flagged.contains(row.getSubmitId()));
    }

    @Transactional
    public void review(long submitId, boolean invalid, String reason, String reviewer) {
        String note = reason == null ? "" : reason.trim();
        if (note.length() > 255) throw new EduException(400, "备注最多255个字符");
        List<Long> ids = db.queryForList("SELECT submit_id FROM judge WHERE submit_id=? FOR UPDATE", Long.class, submitId);
        if (ids.isEmpty()) throw new EduException(404, "提交不存在");
        if (invalid) {
            db.update("INSERT INTO edu_growth_invalid_submission(submit_id,invalidated_by,reason) VALUES(?,?,?) " +
                    "ON DUPLICATE KEY UPDATE invalidated_by=VALUES(invalidated_by),reason=VALUES(reason)," +
                    "invalidated_at=CURRENT_TIMESTAMP(3),restored_by=NULL,restored_at=NULL", submitId, reviewer, note);
        } else {
            db.update("UPDATE edu_growth_invalid_submission SET restored_by=?,restored_at=CURRENT_TIMESTAMP(3) " +
                    "WHERE submit_id=? AND restored_at IS NULL", reviewer, submitId);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override public void afterCommit() { levels.invalidateRating(); }
        });
    }

    public Map<String, Object> wallet(String uid) {
        List<Map<String, Object>> users = levels.adminUsers(Collections.singletonList(uid));
        if (users.isEmpty()) throw new EduException(404, "账号不存在");
        Map<String, Object> user = users.get(0);
        long earned = Math.round(((Number) user.get("score")).doubleValue() * 2);
        Map<String, Object> result = balances(earned, spent(uid));
        result.put("username", user.get("username"));
        return result;
    }

    private long spent(String uid) {
        return db.queryForObject("SELECT COALESCE(SUM(units),0) FROM edu_growth_wallet_debit WHERE uid=?", Long.class, uid);
    }

    private Map<String, Object> balances(long earned, long spent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("earned", earned / 2.0);
        result.put("spent", spent / 2.0);
        result.put("balance", Math.max(0, earned - spent) / 2.0);
        return result;
    }

    /** For future server-side uses. Callers must supply a stable operation ID for retries. */
    @Transactional
    public Map<String, Object> debit(String uid, String operationId, long units) {
        if (units <= 0 || operationId == null || !operationId.matches("[A-Za-z0-9_.:-]{1,80}")) {
            throw new EduException(400, "无效的积分扣减请求");
        }
        List<String> owners = db.queryForList("SELECT uuid FROM user_info WHERE uuid=? FOR UPDATE", String.class, uid);
        if (owners.isEmpty()) throw new EduException(404, "账号不存在");
        List<Long> prior = db.queryForList("SELECT units FROM edu_growth_wallet_debit WHERE uid=? AND operation_id=?", Long.class, uid, operationId);
        long earned = levels.currentEarnedUnits(uid), used = spent(uid);
        if (!prior.isEmpty()) {
            if (prior.get(0) != units) throw new EduException(409, "该扣减编号已用于其他金额");
            return balances(earned, used);
        }
        if (earned - used < units) throw new EduException(409, "可用积分不足");
        db.update("INSERT INTO edu_growth_wallet_debit(uid,operation_id,units) VALUES(?,?,?)", uid, operationId, units);
        return balances(earned, used + units);
    }
}
