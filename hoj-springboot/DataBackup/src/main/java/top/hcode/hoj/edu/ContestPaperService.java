package top.hcode.hoj.edu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.shiro.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hcode.hoj.common.exception.StatusForbiddenException;
import top.hcode.hoj.dao.contest.ContestEntityService;
import top.hcode.hoj.pojo.entity.contest.Contest;
import top.hcode.hoj.shiro.AccountProfile;
import top.hcode.hoj.validator.ContestValidator;
import top.hcode.hoj.validator.GroupValidator;

import java.util.*;

@Service
public class ContestPaperService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ContestEntityService contests;
    private final ContestValidator access;
    private final GroupValidator groups;
    public ContestPaperService(JdbcTemplate db,ObjectMapper json,ContestEntityService contests,ContestValidator access,GroupValidator groups) {
        this.db=db; this.json=json; this.contests=contests; this.access=access; this.groups=groups;
    }
    private AccountProfile actor() { return (AccountProfile)SecurityUtils.getSubject().getPrincipal(); }
    private boolean manager(Contest c, AccountProfile a) {
        return a!=null && (SecurityUtils.getSubject().hasRole("root") || a.getUid().equals(c.getUid())
                || (Boolean.TRUE.equals(c.getIsGroup()) && groups.isGroupRoot(a.getUid(),c.getGid())));
    }
    private Contest contest(long id) {
        Contest c=contests.getById(id);
        if(c==null) throw new EduException(404,"比赛不存在");
        return c;
    }
    private void requireManager(Contest c) {
        if(!manager(c,actor())) throw new EduException(403,"仅比赛管理员可管理试卷与成绩");
    }
    private void admission(Contest c) {
        AccountProfile a=actor();
        if(manager(c,a)) return;
        if(a==null || !Boolean.TRUE.equals(c.getVisible())) throw new EduException(403,"请登录并进入已开放的比赛");
        if(Boolean.TRUE.equals(c.getIsGroup()) && !groups.isGroupMember(a.getUid(),c.getGid())) throw new EduException(403,"仅本团队成员可参加");
        if(Boolean.TRUE.equals(c.getOpenAccountLimit()) && !access.validateAccountRule(c.getAccountLimitRule(),a.getUsername())) throw new EduException(403,"此账号不在参赛范围内");
        try { access.validateJudgeAuth(c,a.getUid()); } catch(StatusForbiddenException e) { throw new EduException(403,"请先在比赛首页验证密码"); }
    }
    private JsonNode parse(String s) {
        try { return json.readTree(s); } catch(Exception e) { throw new IllegalStateException("试卷记录格式错误",e); }
    }
    private int count(String sql,Object... args) { return db.queryForObject(sql,Integer.class,args); }
    private Map<String,Object> config(long cid) {
        List<Map<String,Object>> rows=db.queryForList("SELECT minutes,questions FROM edu_contest_paper WHERE cid=?",cid);
        return rows.isEmpty()?null:rows.get(0);
    }
    private void lockContest(long cid) {
        db.queryForObject("SELECT id FROM contest WHERE id=? FOR UPDATE",Long.class,cid);
    }
    // Keep fixed-time native contests on their original path.
    public void guard(long cid, boolean writing) throws StatusForbiddenException {
        if(cid<=0 || count("SELECT COUNT(*) FROM edu_contest_paper WHERE cid=?",cid)==0) return;
        Contest c=contest(cid);
        AccountProfile a=actor();
        if(!writing && manager(c,a)) return;
        if(a==null) throw new StatusForbiddenException("请登录并从比赛首页开始答题");
        String sql="SELECT (submitted_at IS NULL AND ends_at>NOW(3)) FROM edu_paper_attempt WHERE cid=? AND uid=?";
        List<Integer> states=db.queryForList(sql+(writing?" FOR UPDATE":""),Integer.class,cid,a.getUid());
        if(states.isEmpty()) throw new StatusForbiddenException("请先在比赛首页点击开始比赛");
        if(writing && states.get(0)==0) throw new StatusForbiddenException("已经交卷或个人答题时间已到");
    }
    public boolean paperOnly(long pid) {
        return count("SELECT COUNT(*) FROM contest_problem cp JOIN edu_contest_paper p ON p.cid=cp.cid WHERE cp.pid=?",pid)>0;
    }
    public JsonNode settings(long cid) {
        requireManager(contest(cid));
        Map<String,Object> p=config(cid);
        ObjectNode out=json.createObjectNode();
        out.put("minutes",p==null?0:(int)p.get("minutes"));
        out.set("questions",p==null?json.createArrayNode():parse((String)p.get("questions")));
        out.put("locked",count("SELECT EXISTS(SELECT 1 FROM edu_paper_attempt WHERE cid=?) OR EXISTS(SELECT 1 FROM edu_paper_retake WHERE cid=?)",cid,cid)>0);
        return out;
    }
    @Transactional
    public void configure(long cid,JsonNode data) {
        Contest c=contest(cid); requireManager(c); lockContest(cid);
        if(count("SELECT EXISTS(SELECT 1 FROM edu_paper_attempt WHERE cid=?) OR EXISTS(SELECT 1 FROM edu_paper_retake WHERE cid=?)",cid,cid)>0) throw new EduException(409,"已有学生开考，试卷与计时已锁定");
        int minutes=data.path("minutes").asInt(-1);
        if(minutes<0 || minutes>10080 || minutes*60000L>=c.getEndTime().getTime()-c.getStartTime().getTime()) throw new EduException(400,"个人时长必须短于比赛开放时段");
        JsonNode qs=data.path("questions");
        if(!qs.isArray() || qs.size()>250 || qs.toString().length()>500000) throw new EduException(400,"请上传最多 250 个题项的 JSON 数组");
        Set<String> ids=new HashSet<>();
        for(JsonNode q:qs) {
            String id=q.path("id").asText(""),type=q.path("type").asText("");
            if(!id.matches("[A-Za-z0-9_-]{1,40}") || !ids.add(id) || q.path("label").asText("").trim().isEmpty()
                    || q.path("label").asText().length()>4000 || !Arrays.asList("choice","blank","info").contains(type)) throw new EduException(400,"题项需要唯一编号、题干和有效题型");
            if(type.equals("info")) continue;
            int points=q.path("points").asInt(-1);
            JsonNode keys=q.path("keys"),options=q.path("options");
            if(points<0 || points>1000 || !keys.isArray() || keys.size()==0 || keys.size()>100) throw new EduException(400,"请填写分值和参考答案："+id);
            if(type.equals("choice") && (!options.isArray() || options.size()<2 || options.size()>26 || keys.size()!=1)) throw new EduException(400,"单选题需要 2 至 26 个选项和一个正确选项："+id);
            for(JsonNode key:keys) {
                if(!key.isTextual() || key.asText().trim().isEmpty() || key.asText().length()>1000) throw new EduException(400,"参考答案无效："+id);
                if(type.equals("choice") && (!key.asText().matches("[A-Z]") || key.asText().charAt(0)-'A'>=options.size())) throw new EduException(400,"正确选项超出范围："+id);
            }
            if(type.equals("choice")) for(JsonNode op:options) if(!op.isTextual() || op.asText().trim().isEmpty()) throw new EduException(400,"选项不可为空："+id);
        }
        if(minutes==0 && qs.size()==0) db.update("DELETE FROM edu_contest_paper WHERE cid=?",cid);
        else db.update("INSERT INTO edu_contest_paper(cid,minutes,questions) VALUES(?,?,?) ON DUPLICATE KEY UPDATE minutes=VALUES(minutes),questions=VALUES(questions)",cid,minutes,qs.toString());
    }
    private Map<String,Object> attempt(long cid,String uid,boolean lock) {
        List<Map<String,Object>> rows=db.queryForList("SELECT UNIX_TIMESTAMP(started_at)*1000 AS started,UNIX_TIMESTAMP(ends_at)*1000 AS deadline,submitted_at IS NOT NULL AS finished, answers,revision FROM edu_paper_attempt WHERE cid=? AND uid=?"+(lock?" FOR UPDATE":""),cid,uid);
        return rows.isEmpty()?null:rows.get(0);
    }
    private long now() {return db.queryForObject("SELECT UNIX_TIMESTAMP(NOW(3))*1000",Long.class);}
    private boolean closed(Map<String,Object> a,long time) {
        return number(a.get("deadline"))<=time || truth(a.get("finished"));
    }
    private long number(Object v) {return ((Number)v).longValue();}
    private boolean truth(Object v) {return v instanceof Boolean?(boolean)v:number(v)!=0;}
    private long cutoff(Contest c,int mins) {return c.getEndTime().getTime()-mins*60000L;}
    private ArrayNode publicItems(JsonNode qs) {
        ArrayNode arr=json.createArrayNode();
        for(JsonNode q:qs) {
            ObjectNode o=arr.addObject();
            for(String key:new String[]{"id","type","label","options","points"}) if(q.has(key)) o.set(key,q.get(key));
        }
        return arr;
    }
    private int writtenScore(JsonNode qs,JsonNode answers) {
        int score=0;
        for(JsonNode q:qs) if(!q.path("type").asText().equals("info")) {
            String v=answers.path(q.path("id").asText()).asText("").trim();
            for(JsonNode key:q.path("keys")) if(key.asText().trim().equals(v)) {score+=q.path("points").asInt();break;}
        }
        return score;
    }
    private int programScore(long cid,String uid) {
        return db.queryForObject("SELECT COALESCE(SUM(best),0) FROM (SELECT MAX(score) AS best FROM contest_record WHERE cid=? AND uid=? GROUP BY pid) scores",Integer.class,cid,uid);
    }
    private List<Map<String,Object>> programs(long cid,String uid) {
        return db.queryForList("SELECT cp.display_id AS id,cp.display_title AS title," +
                "IF(p.type=1,p.io_score,NULL) AS points,COALESCE(r.attempts,0) AS submissions,r.score " +
                "FROM contest_problem cp JOIN problem p ON p.id=cp.pid " +
                "LEFT JOIN (SELECT cpid,COUNT(*) AS attempts,MAX(score) AS score FROM contest_record " +
                "WHERE cid=? AND uid=? GROUP BY cpid) r ON r.cpid=cp.id " +
                "WHERE cp.cid=? ORDER BY cp.display_id",cid,uid,cid);
    }
    public ObjectNode state(long cid) {
        ObjectNode out=json.createObjectNode();
        Map<String,Object> p=config(cid);
        out.put("enabled",p!=null);
        if(p==null) return out;
        Contest c=contest(cid); admission(c);
        int mins=(int)p.get("minutes");
        long t=now();
        out.put("now",t).put("minutes",mins).put("open",c.getStartTime().getTime()).put("lastStart",cutoff(c,mins));
        String uid=actor().getUid();
        Map<String,Object> a=attempt(cid,uid,false);
        if(a==null) {
            out.put("retake",count("SELECT EXISTS(SELECT 1 FROM edu_paper_retake WHERE cid=? AND uid=?)",cid,uid)>0);
            return out;
        }
        JsonNode qs=parse((String)p.get("questions")),answers=parse((String)a.remove("answers"));
        out.set("attempt",json.valueToTree(a));out.set("questions",publicItems(qs));out.set("answers",answers);
        out.set("programs",json.valueToTree(programs(cid,uid)));
        out.put("closed",closed(a,t));
        if(closed(a,t)) out.put("writtenScore",writtenScore(qs,answers)).put("programScore",programScore(cid,uid));
        return out;
    }
    @Transactional
    public JsonNode start(long cid) {
        Contest c=contest(cid); admission(c); lockContest(cid);
        if(attempt(cid,actor().getUid(),true)!=null) return state(cid);
        Map<String,Object> p=config(cid);
        if(p==null) throw new EduException(404,"本场未启用试卷");
        int mins=(int)p.get("minutes");long t=now();
        if(t<c.getStartTime().getTime() || t>=cutoff(c,mins)) throw new EduException(403,"当前不在允许开始的时段内");
        long end=mins>0?t+mins*60000L:c.getEndTime().getTime();
        String uid=actor().getUid();
        int revision=count("SELECT COALESCE(MAX(revision)+1,0) FROM edu_paper_retake WHERE cid=? AND uid=?",cid,uid);
        db.update("INSERT INTO edu_paper_attempt(cid,uid,started_at,ends_at,answers,revision) VALUES(?,?,FROM_UNIXTIME(?/1000.0),FROM_UNIXTIME(?/1000.0),'{}',?)",cid,uid,t,end,revision);
        return state(cid);
    }
    @Transactional
    public JsonNode save(long cid,JsonNode data,boolean finish) {
        Contest c=contest(cid); admission(c);
        Map<String,Object> a=attempt(cid,actor().getUid(),true);
        if(a==null) throw new EduException(409,"答题会话已变更，请重新载入试卷");
        if(closed(a,now())) throw new EduException(403,"已经交卷或答题时间已到");
        if(data.path("revision").asInt(-1)!=number(a.get("revision"))) throw new EduException(409,"其他页面已保存，请重新载入试卷");
        JsonNode answers=data.path("answers"),qs=parse((String)config(cid).get("questions"));
        if(!answers.isObject() || answers.size()>qs.size()) throw new EduException(400,"答案格式错误");
        ObjectNode clean=json.createObjectNode();List<String> missing=new ArrayList<>();
        for(JsonNode q:qs) {
            String id=q.get("id").asText(),v=answers.path(id).asText("").trim();
            if(v.length()>1000 || (!v.isEmpty() && q.get("type").asText().equals("choice") && (!v.matches("[A-Z]") || v.charAt(0)-'A'>=q.get("options").size()))) throw new EduException(400,"答案无效："+q.get("label").asText());
            if(v.isEmpty()) missing.add(q.get("label").asText());
            else clean.put(id,v);
        }
        if(finish && !missing.isEmpty()) throw new EduException(400,"尚未作答："+String.join("、",missing));
        int changed=db.update("UPDATE edu_paper_attempt SET answers=?,revision=revision+1"+(finish?",submitted_at=NOW(3)":"")+" WHERE cid=? AND uid=? AND submitted_at IS NULL AND ends_at>NOW(3)",clean.toString(),cid,actor().getUid());
        if(changed!=1) throw new EduException(403,"个人答题时间已到");
        return state(cid);
    }
    @Transactional
    public void reset(long cid,String uid,long started) {
        Contest c=contest(cid);
        requireManager(c);
        lockContest(cid);
        Map<String,Object> p=config(cid);
        if(p==null) throw new EduException(404,"本场未启用试卷");
        if(now()>=cutoff(c,(int)p.get("minutes"))) throw new EduException(409,"最晚开考时间已过，无法重新开考");
        Map<String,Object> a=attempt(cid,uid,true);
        if(a==null || number(a.get("started"))!=started) throw new EduException(409,"该学生的答卷已变更，请刷新列表");
        // Keep judges and their source code for staff. Detach only these submissions from the official contest scores.
        db.update("INSERT INTO edu_paper_retake(cid,uid,reset_by,started_at,ends_at,submitted_at,answers,revision,program_score) " +
                "SELECT cid,uid,?,started_at,ends_at,submitted_at,answers,revision,? " +
                "FROM edu_paper_attempt WHERE cid=? AND uid=?",actor().getUid(),programScore(cid,uid),cid,uid);
        Long id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO edu_paper_retired_submission(submit_id,retake_id) " +
                "SELECT submit_id,? FROM contest_record WHERE cid=? AND uid=?",id,cid,uid);
        db.update("DELETE FROM contest_record WHERE cid=? AND uid=?",cid,uid);
        db.update("DELETE FROM edu_paper_attempt WHERE cid=? AND uid=?",cid,uid);
    }

    public List<Map<String,Object>> results(long cid) {
        requireManager(contest(cid));
        Map<String,Object> p=config(cid); if(p==null) return Collections.emptyList();
        JsonNode qs=parse((String)p.get("questions"));
        List<Map<String,Object>> rows=db.queryForList("SELECT a.uid,u.username,UNIX_TIMESTAMP(a.started_at)*1000 AS started,UNIX_TIMESTAMP(a.ends_at)*1000 AS deadline,UNIX_TIMESTAMP(a.submitted_at)*1000 AS submitted,a.answers FROM edu_paper_attempt a JOIN user_info u ON u.uuid=a.uid WHERE a.cid=? ORDER BY a.started_at",cid);
        Map<String,Map<String,Object>> students=new LinkedHashMap<>();
        for(Map<String,Object> r:rows) {
            String uid=(String)r.get("uid");
            formatResult(r,qs,programScore(cid,uid));
            students.put(uid,r);
        }
        List<Map<String,Object>> history=db.queryForList("SELECT h.id,h.uid,u.username,UNIX_TIMESTAMP(h.started_at)*1000 AS started," +
                "UNIX_TIMESTAMP(h.reset_at)*1000 AS resetAt,h.answers,h.program_score AS programScore " +
                "FROM edu_paper_retake h JOIN user_info u ON u.uuid=h.uid WHERE h.cid=? ORDER BY h.id DESC",cid);
        Map<String,List<Map<String,Object>>> past=new LinkedHashMap<>();
        for(Map<String,Object> r:history) {
            String uid=(String)r.get("uid");
            if(!students.containsKey(uid)) {
                Map<String,Object> pending=new LinkedHashMap<>();
                pending.put("uid",uid);
                pending.put("username",r.get("username"));
                students.put(uid,pending);
            }
            formatResult(r,qs,(int)number(r.get("programScore")));
            past.computeIfAbsent(uid,k->new ArrayList<>()).add(r);
        }
        for(Map.Entry<String,Map<String,Object>> student:students.entrySet()) {
            student.getValue().put("history",past.getOrDefault(student.getKey(),Collections.emptyList()));
        }
        return new ArrayList<>(students.values());
    }
    private void formatResult(Map<String,Object> row,JsonNode questions,int programScore) {
        JsonNode answers=parse((String)row.remove("answers"));
        Map<String,String> details=new LinkedHashMap<>();
        for(JsonNode q:questions) {
            details.put(q.get("label").asText(),answers.path(q.get("id").asText()).asText(""));
        }
        row.put("programScore",programScore);
        row.put("details",details);
        row.put("writtenScore",writtenScore(questions,answers));
    }
}
