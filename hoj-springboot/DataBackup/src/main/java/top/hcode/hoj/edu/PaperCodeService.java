package top.hcode.hoj.edu;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hcode.hoj.judge.Dispatcher;
import top.hcode.hoj.pojo.dto.PaperCodeJob;
import top.hcode.hoj.pojo.entity.contest.Contest;
import top.hcode.hoj.utils.Constants;

import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PaperCodeService {
    private static final Logger log=LoggerFactory.getLogger(PaperCodeService.class);
    private static final String ATTEMPT="cid=? AND uid=? AND attempt_key=?";
    private final JdbcTemplate db;
    private final ContestPaperService papers;
    private final Dispatcher dispatcher;
    private final ContentReview contentReview;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean();
    @Value("${hoj.judge.token:no_judge_token}")
    private String judgeToken;

    public PaperCodeService(JdbcTemplate db,ContestPaperService papers,Dispatcher dispatcher,ContentReview contentReview) {
        this.db=db;
        this.papers=papers;
        this.dispatcher=dispatcher;
        this.contentReview=contentReview;
    }
    public Map<String,Object> status(long cid,String displayId) {
        Map<String,Object> result=new LinkedHashMap<>();
        boolean enabled=papers.deferred(cid);
        result.put("enabled",enabled);
        if(!enabled) return result;
        Contest contest=papers.contest(cid);
        papers.admission(contest);
        String uid=papers.actor().getUid();
        Map<String,Object> attempt=papers.attempt(cid,uid,false);
        result.put("writable",attempt!=null && !papers.closed(attempt,papers.now()));
        result.put("rows",attempt==null?Collections.emptyList():db.queryForList(
                "SELECT s.id,s.language,s.compile_state AS status,s.diagnostics,s.created_at AS created FROM edu_paper_code s "+
                "JOIN contest_problem cp ON cp.id=s.cpid WHERE s.cid=? AND s.uid=? AND s.attempt_key=? AND cp.display_id=? ORDER BY s.id DESC LIMIT 25",
                cid,uid,attempt.get("started"),displayId));
        return result;
    }
    @Transactional
    public long submit(long cid,String displayId,JsonNode input) {
        if(!papers.deferred(cid)) throw new EduException(404,"本场未启用仅编译赛制");
        Contest contest=papers.contest(cid);
        papers.admission(contest);
        String uid=papers.actor().getUid();
        Map<String,Object> attempt=papers.attempt(cid,uid,true);
        if(attempt==null || papers.closed(attempt,papers.now())) throw new EduException(403,"请先开始答题，交卷或到时后停止提交");
        String source=input.path("code").asText("");
        String language=input.path("language").asText("");
        if(source.trim().isEmpty() || source.length()>65535) throw new EduException(400,"请填写 1 至 65535 字符的代码");
        List<Map<String,Object>> problems=db.queryForList(
                "SELECT cp.id,cp.pid FROM contest_problem cp JOIN problem p ON p.id=cp.pid "+
                "WHERE cp.cid=? AND cp.display_id=? AND p.is_remote=0 AND EXISTS "+
                "(SELECT 1 FROM problem_language pl JOIN language l ON l.id=pl.lid WHERE pl.pid=p.id AND l.name=? AND l.oj='ME')",
                cid,displayId,language);
        if(problems.size()!=1) throw new EduException(400,"当前题目或编程语言不可用");
        contentReview.privatePaperCode(source);
        Map<String,Object> problem=problems.get(0);
        int inserted=db.update("INSERT INTO edu_paper_code(cid,uid,attempt_key,pid,cpid,language,code) "+
                        "SELECT cid,uid,UNIX_TIMESTAMP(started_at)*1000,?,?,?,? FROM edu_paper_attempt "+
                        "WHERE cid=? AND uid=? AND submitted_at IS NULL AND ends_at>NOW(3)",
                problem.get("pid"),problem.get("id"),language,source,cid,uid);
        if(inserted!=1) throw new EduException(403,"个人答题时间已到");
        return db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
    }
    @Transactional
    public void grade(long cid,String uid,long started) {
        papers.requireManager(papers.contest(cid));
        requireClosed(cid,uid,started);
        if(count("SELECT COUNT(*) FROM edu_paper_grade WHERE "+ATTEMPT+" AND published_at IS NOT NULL",cid,uid,started)>0)
            throw new EduException(409,"成绩已公布");
        if(count("SELECT COUNT(*) FROM edu_paper_code WHERE "+ATTEMPT+" AND compile_state='QUEUED'",cid,uid,started)>0)
            throw new EduException(409,"还有代码正在编译，请在编译结束后评测");
        db.update("INSERT IGNORE INTO edu_paper_grade(cid,uid,attempt_key) VALUES(?,?,?)",cid,uid,started);
        db.update("UPDATE edu_paper_code s JOIN (SELECT MAX(id) AS id FROM edu_paper_code WHERE "+
                ATTEMPT+" AND compile_state='PASSED' GROUP BY cpid) chosen ON s.id=chosen.id "+
                "SET s.grade_state='QUEUED', s.score=NULL, s.lease_until=NULL WHERE s.grade_state IS NULL OR s.grade_state='ERROR'",
                cid,uid,started);
    }
    @Transactional
    public void publish(long cid,String uid,long started) {
        papers.requireManager(papers.contest(cid));
        requireClosed(cid,uid,started);
        Map<String,Object> grade=papers.deferredGrade(cid,uid,started);
        if(!"GRADED".equals(grade.get("status"))) throw new EduException(409,"请先完成评测并处理评测失败的任务");
        db.update("UPDATE edu_paper_grade SET published_at=NOW(3) WHERE "+ATTEMPT+" AND published_at IS NULL",
                cid,uid,started);
    }
    private void requireClosed(long cid,String uid,long started) {
        Map<String,Object> attempt=papers.attempt(cid,uid,true);
        if(!papers.deferred(cid) || attempt==null || ((Number)attempt.get("started")).longValue()!=started)
            throw new EduException(409,"答卷已变更，请刷新列表");
        if(!papers.closed(attempt,papers.now())) throw new EduException(409,"请等待考生交卷或到时收卷");
    }
    private int count(String sql,Object... args) {
        return db.queryForObject(sql,Integer.class,args);
    }
    @Scheduled(fixedDelay=1500, initialDelay=30000)
    public void schedule() {
        if(!busy.compareAndSet(false,true)) return;
        worker.submit(() -> {
            try { drain(); }
            finally { busy.set(false); }
        });
    }
    @PreDestroy
    public void shutdown() { worker.shutdownNow(); }
    private void drain() {
        try {
            if(count("SELECT COUNT(*) FROM edu_schema_history WHERE version='V019__paper_deferred_judging'")==0) return;
            Map<String,Object> job=next("compile_state");
            boolean grading=job==null;
            if(grading) job=next("grade_state");
            if(job==null) return;
            String stage=grading?"grade_state":"compile_state";
            long id=((Number)job.get("id")).longValue();
            String eligible=" WHERE id=? AND "+stage+"='QUEUED' AND (lease_until IS NULL OR lease_until<NOW(3))";
            if(db.update("UPDATE edu_paper_code SET lease_until=DATE_ADD(NOW(3),INTERVAL 3 MINUTE)"+eligible,id)!=1) return;
            PaperCodeJob request=new PaperCodeJob();
            request.setId(id);
            request.setPid(((Number)job.get("pid")).longValue());
            request.setUid((String)job.get("uid"));
            request.setLanguage((String)job.get("language"));
            request.setCode((String)job.get("code"));
            request.setGrading(grading);
            request.setToken(judgeToken);
            JSONObject result=dispatcher.paperCode(request);
            int status=result==null?Constants.Judge.STATUS_SYSTEM_ERROR.getStatus():result.getInt("status");
            if(grading) {
                boolean failed=status==4 || status==10;
                int score=failed?0:result.getInt("score",0);
                db.update("UPDATE edu_paper_code SET grade_state=?,score=?,lease_until=NULL WHERE id=? AND grade_state='QUEUED'",
                        failed?"ERROR":"DONE",failed?null:score,id);
            } else {
                boolean error=status==4 || status==10;
                String message=error?"暂时无法完成编译，请稍后重新提交。":result.getStr("diagnostics","");
                db.update("UPDATE edu_paper_code SET compile_state=?,diagnostics=?,lease_until=NULL WHERE id=? AND compile_state='QUEUED'",
                        error?"ERROR":status==0?"PASSED":"FAILED",message.substring(0,Math.min(4000,message.length())),id);
            }
        } catch (Exception e) {
            log.warn("处理试卷任务失败 ({})",e.getClass().getSimpleName());
        }
    }
    private Map<String,Object> next(String stage) {
        List<Map<String,Object>> rows=db.queryForList("SELECT s.id,s.uid,s.pid,s.language,s.code FROM edu_paper_code s "+
                "JOIN edu_paper_attempt a ON a.cid=s.cid AND a.uid=s.uid AND UNIX_TIMESTAMP(a.started_at)*1000=s.attempt_key "+
                "WHERE s."+stage+"='QUEUED' AND (s.lease_until IS NULL OR s.lease_until<NOW(3)) ORDER BY s.id LIMIT 1");
        return rows.isEmpty()?null:rows.get(0);
    }
}
