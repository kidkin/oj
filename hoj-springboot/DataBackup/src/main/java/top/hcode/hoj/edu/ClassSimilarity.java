package top.hcode.hoj.edu;

import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassSimilarity {
    private final JdbcTemplate db;
    private final ContentReview access;
    private final EduClassService classes;
    private final Semaphore capacity=new Semaphore(1);
    public ClassSimilarity(JdbcTemplate db,ContentReview access,EduClassService classes){ this.db=db; this.access=access; this.classes=classes; }
    private static String language(String family){
        switch (family) {
            case "cpp": return "(LOWER(j.language) LIKE '%c++%' OR LOWER(j.language) IN ('c','c with o2','c99','c11','c17','c89'))";
            case "python": return "(LOWER(j.language) LIKE '%python%' OR LOWER(j.language) LIKE '%pypy%')";
            case "java": return "LOWER(j.language) LIKE 'java%' AND LOWER(j.language) NOT LIKE 'javascript%'";
            default: throw new EduException(400,"请选择支持的语言");
        }
    }
    private static Map<String,Object> identity(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:Arrays.asList("submitId","username","realname","submitted","language")) result.put(key,row.get(key));
        return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> compare(long classId,long pid,String family,boolean ac,int threshold){
        access.manager(classId);
        if(db.queryForObject("SELECT COUNT(*) FROM problem p WHERE p.id=? AND "+EduProgress.PUBLIC_PROBLEM,Long.class,pid)!=1)
            throw new EduException(404,"请选择公开题目");
        if(threshold<50 || threshold>100) throw new EduException(400,"相似度范围为50至100");
        String filter=language(family);
        if(!capacity.tryAcquire()) throw new EduException(429,"有教师正在查重，请稍后重试");
        try {
            List<Map<String,Object>> rows=db.queryForList("SELECT j.submit_id AS submitId,j.uid,u.username,u.realname,j.language,j.submit_time AS submitted,j.code "
                    +"FROM (SELECT MAX(j.submit_id) sid FROM edu_class_member m JOIN judge j ON j.uid=m.uid "
                    +"WHERE m.class_id=? AND m.role='STUDENT' AND m.status=0 AND j.pid=? AND j.cid=0 AND j.gid IS NULL AND "+filter
                    +(ac?" AND j.status=0":"")+" GROUP BY j.uid LIMIT 201) latest JOIN judge j ON j.submit_id=latest.sid "
                    +"JOIN user_info u ON u.uuid=j.uid ORDER BY j.submit_id",classId,pid);
            if(rows.size()>200) throw new EduException(400,"本题参评人数超过200，请按小班检查");
            return grade(rows,pid,family,threshold,null);
        } finally { capacity.release(); }
    }

    private Map<String,Object> grade(List<Map<String,Object>> rows,long pid,String family,int threshold,Long anchor){
            String filter=language(family);
            long size=rows.stream().mapToLong(row -> row.get("code")==null ? 0 : ((String)row.get("code")).length()).sum();
            if(size>850000) throw new EduException(400,"本次代码总量过大，请按小班或语言检查");
            Set<Long> templateKeys=new HashSet<>();
            List<String> templates=db.queryForList("SELECT t.code FROM code_template t JOIN language l ON l.id=t.lid "
                    +"WHERE t.pid=? AND t.status=1 AND "+filter.replace("j.language","l.name"),String.class,pid);
            for(String code:templates) if(code!=null) templateKeys.addAll(new SolutionFingerprint(code,"python".equals(family)).keys());
            List<SolutionFingerprint> prints=new ArrayList<>();
            Map<Long,List<Integer>> index=new HashMap<>();
            List<Map<String,Object>> pairs=new ArrayList<>();
            int shortCount=0,totalPairs=0;
            for(int i=0;i<rows.size();i++){
                String code=(String)rows.get(i).remove("code");
                SolutionFingerprint current=new SolutionFingerprint(code==null?"":code,"python".equals(family));
                current.keys().removeAll(templateKeys);
                prints.add(current);
                if(!current.substantial()){shortCount++; continue;}
                int[] shared=new int[i];
                for(Long key:current.keys()){
                    List<Integer> previous=index.computeIfAbsent(key,k->new ArrayList<>());
                    for(int j:previous) shared[j]++;
                    if(anchor==null || ((Number)rows.get(i).get("submitId")).longValue()==anchor) previous.add(i);
                }
                for(int j=0;j<i;j++){
                    if(shared[j]<20 || rows.get(i).get("uid").equals(rows.get(j).get("uid"))) continue;
                    if(anchor!=null && ((Number)rows.get(j).get("submitId")).longValue()!=anchor &&
                            ((Number)rows.get(i).get("submitId")).longValue()!=anchor) continue;
                    int score=SolutionFingerprint.percent(shared[j],current.keys().size(),prints.get(j).keys().size());
                    if(score<threshold) continue;
                    totalPairs++;
                    Map<String,Object> pair=new LinkedHashMap<>();
                    pair.put("score",score); pair.put("a",j); pair.put("b",i);
                    pairs.add(pair);
                }
            }
            pairs.sort(Comparator.comparingInt(p->-(int)p.get("score")));
            List<Map<String,Object>> best=new ArrayList<>(pairs.subList(0,Math.min(50,pairs.size())));
            for(Map<String,Object> pair:best){
                int a=(int)pair.remove("a"), b=(int)pair.remove("b");
                pair.put("left",identity(rows.get(a))); pair.put("right",identity(rows.get(b)));
                pair.put("blocks",prints.get(a).evidence(prints.get(b)));
            }
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("pairs",best); result.put("totalPairs",totalPairs);
            result.put("submissions",rows.size()); result.put("accounts",rows.stream().map(r -> r.get("uid")).distinct().count()); result.put("tooShort",shortCount); return result;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> submissions(long pid,Long anchor,String family,int days,int threshold,long contestId,Long groupId) {
        String actor=classes.actor();
        boolean root=classes.hasRole(actor,"root");
        if(!root && !classes.hasRole(actor,"teacher")) throw new EduException(403,"仅教师可查重");
        if(days<0 || days>3650 || threshold<50 || threshold>100) throw new EduException(400,"时间或相似度超出允许范围");
        if(contestId!=0 && !root && db.queryForObject("SELECT COUNT(*) FROM contest WHERE id=? AND uid=?",Long.class,contestId,actor)==0)
            throw new EduException(403,"仅比赛负责人可检查本场比赛");
        if(groupId!=null && !root && db.queryForObject("SELECT COUNT(*) FROM group_member WHERE gid=? AND uid=? AND auth IN (4,5)",Long.class,groupId,actor)==0)
            throw new EduException(403,"仅团队管理员可检查团队提交");
        if(contestId==0 && groupId==null && !root && db.queryForObject("SELECT COUNT(*) FROM problem p WHERE id=? AND "+EduProgress.PUBLIC_PROBLEM,Long.class,pid)==0)
            throw new EduException(404,"题目不可用");
        String from=" FROM judge j JOIN user_info u ON u.uuid=j.uid WHERE j.pid=? AND j.cid=? AND j.gid "+(groupId==null?"IS NULL":"=?");
        List<Object> scope=new ArrayList<>(Arrays.asList(pid,contestId));
        if(groupId!=null) scope.add(groupId);
        if(!root) {
            from+=" AND EXISTS (SELECT 1 FROM edu_class_member s JOIN edu_class_member t ON t.class_id=s.class_id "
                    +"JOIN edu_class c ON c.id=s.class_id WHERE s.uid=j.uid AND s.role='STUDENT' AND s.status=0 "
                    +"AND t.uid=? AND t.role='TEACHER' AND t.status=0 AND c.status=0)";
            scope.add(actor);
        }
        if(!capacity.tryAcquire()) throw new EduException(429,"有教师正在查重，请稍后重试");
        try {
            String columns="SELECT j.submit_id AS submitId,j.uid,u.username,u.realname,j.language,j.submit_time AS submitted,CHAR_LENGTH(j.code) AS chars";
            List<Map<String,Object>> rows=new ArrayList<>();
            List<Object> args=new ArrayList<>(scope);
            if(anchor!=null) {
                args.add(anchor);
                rows.addAll(db.queryForList(columns+from+" AND j.submit_id=?",args.toArray()));
                if(rows.isEmpty()) throw new EduException(403,"该提交不在可查重范围内");
                String lang=String.valueOf(rows.get(0).get("language")).toLowerCase(Locale.ROOT);
                if(lang.contains("python") || lang.contains("pypy")) family="python";
                else if(lang.startsWith("java") && !lang.startsWith("javascript")) family="java";
                else if(lang.contains("c++") || lang.matches("c( with o2|99|11|17|89)?")) family="cpp";
                else throw new EduException(400,"暂支持 C/C++、Python 和 Java");
            }
            args=new ArrayList<>(scope);
            String where=from+" AND "+language(family)+" AND j.code IS NOT NULL";
            if(days>0) {where+=" AND j.submit_time>=DATE_SUB(NOW(), INTERVAL ? DAY)"; args.add(days);}
            if(anchor!=null) {where+=" AND j.uid<>?"; args.add(rows.get(0).get("uid"));}
            rows.addAll(db.queryForList(columns+where+" ORDER BY j.submit_id DESC LIMIT 351",args.toArray()));
            long chars=rows.stream().filter(r->r.get("chars")!=null).mapToLong(r->((Number)r.get("chars")).longValue()).sum();
            if(rows.size()>350 || chars>850000) throw new EduException(400,"范围内提交太多，请缩短对比天数");
            if(!rows.isEmpty()){
                Object[] ids=rows.stream().map(r->r.get("submitId")).toArray();
                Map<Long,String> code=new HashMap<>();
                db.query("SELECT submit_id,code FROM judge WHERE submit_id IN ("+String.join(",",Collections.nCopies(ids.length,"?"))+")",
                    ids,(org.springframework.jdbc.core.RowCallbackHandler)rs->code.put(rs.getLong(1),rs.getString(2)));
                for(Map<String,Object> row:rows) row.put("code",code.get(((Number)row.get("submitId")).longValue()));
            }
            return grade(rows,pid,family,threshold,anchor);
        } finally { capacity.release(); }
    }
}
