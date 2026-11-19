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
    private final Semaphore capacity=new Semaphore(1);
    public ClassSimilarity(JdbcTemplate db,ContentReview access){ this.db=db; this.access=access; }
    private static String language(String family){
        switch (family) {
            case "cpp": return "(LOWER(j.language) LIKE '%c++%' OR LOWER(j.language) IN ('c','c99','c11','c17','c89'))";
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
            List<Map<String,Object>> rows=db.queryForList("SELECT j.submit_id AS submitId,u.username,u.realname,j.language,j.submit_time AS submitted,j.code "
                    +"FROM (SELECT MAX(j.submit_id) sid FROM edu_class_member m JOIN judge j ON j.uid=m.uid "
                    +"WHERE m.class_id=? AND m.role='STUDENT' AND m.status=0 AND j.pid=? AND j.cid=0 AND j.gid IS NULL AND "+filter
                    +(ac?" AND j.status=0":"")+" GROUP BY j.uid LIMIT 201) latest JOIN judge j ON j.submit_id=latest.sid "
                    +"JOIN user_info u ON u.uuid=j.uid ORDER BY j.submit_id",classId,pid);
            if(rows.size()>200) throw new EduException(400,"本题参评人数超过200，请按小班检查");
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
                    previous.add(i);
                }
                for(int j=0;j<i;j++){
                    if(shared[j]<20) continue;
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
            result.put("accounts",rows.size()); result.put("tooShort",shortCount); return result;
        } finally { capacity.release(); }
    }
}
