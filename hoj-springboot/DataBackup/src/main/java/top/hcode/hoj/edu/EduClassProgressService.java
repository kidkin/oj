package top.hcode.hoj.edu;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

/** Read-only, teacher-authorized snapshots; no contest records or source code are returned. */
@Service
public class EduClassProgressService {
    private final JdbcTemplate db;
    private final EduClassService classes;
    public EduClassProgressService(JdbcTemplate db, EduClassService classes) { this.db=db; this.classes=classes; }
    private String marks(int n) { return String.join(",",Collections.nCopies(n,"?")); }
    private static long number(Map<String,Object> row,String key) { Object value=row.get(key); return value instanceof Number ? ((Number)value).longValue() : 0; }
    private Map<String,Object> managed(long classId) {
        Map<String,Object> row=classes.detail(classId);
        if (!Boolean.TRUE.equals(row.get("canManage"))) throw new EduException(403,"仅本班管理教师和超级管理员可查看学习情况");
        return row;
    }
    static String status(boolean accepted,boolean partial,boolean wrong,boolean pending) {
        return accepted ? "CORRECT" : partial ? "PARTIAL" : wrong ? "WRONG" : pending ? "PENDING" : "NOT_SUBMITTED";
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> problems(long classId,int page,int limit,String keyword,Long courseId) {
        managed(classId);
        page=Math.max(1,page); limit=Math.max(1,Math.min(50,limit)); keyword=keyword==null ? "" : keyword.trim();
        if (page>100000 || keyword.length()>128) throw new EduException(400,"查询参数过长");
        String from=" FROM problem p "; List<Object> args=new ArrayList<>(); String order="p.id";
        if (courseId!=null) {
            if (courseId<=0 || db.queryForObject("SELECT COUNT(*) FROM edu_class_course cc JOIN edu_course c ON c.id=cc.course_id AND c.status=1 WHERE cc.class_id=? AND cc.course_id=?",Long.class,classId,courseId)==0)
                throw new EduException(400,"请选择本班引用的已发布课程");
            from+="JOIN edu_course_problem cp ON cp.pid=p.id AND cp.course_id=? LEFT JOIN edu_course_problem_display pd ON pd.course_id=cp.course_id AND pd.pid=cp.pid "; args.add(courseId);
            order="COALESCE(pd.display_id,cp.sort_order+1),cp.id";
        }
        String where=" WHERE "+EduProgress.PUBLIC_PROBLEM+" AND (p.title LIKE ? OR p.problem_id LIKE ?) ";
        args.add("%"+keyword+"%"); args.add("%"+keyword+"%");
        long total=db.queryForObject("SELECT COUNT(*)"+from+where,args.toArray(),Long.class);
        List<Object> values=new ArrayList<>(args); values.add(limit); values.add((page-1)*limit);
        Map<String,Object> result=new LinkedHashMap<>(); result.put("total",total);
        result.put("records",db.queryForList("SELECT p.id,p.problem_id AS problemId,p.title"+from+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",values.toArray()));
        return result;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> generate(long classId,List<Long> problemIds) {
        Map<String,Object> klass=managed(classId);
        if (problemIds==null || problemIds.isEmpty() || problemIds.size()>50 || problemIds.stream().anyMatch(id->id==null || id<=0) || new HashSet<>(problemIds).size()!=problemIds.size())
            throw new EduException(400,"请选择1至50道不重复的公开题目");
        List<Map<String,Object>> eligible=db.queryForList("SELECT p.id,p.problem_id AS problemId,p.title FROM problem p WHERE "+EduProgress.PUBLIC_PROBLEM+" AND p.id IN ("+marks(problemIds.size())+")",problemIds.toArray());
        if (eligible.size()!=problemIds.size()) throw new EduException(400,"有题目已不可公开访问，请重新选题");
        Map<Long,Map<String,Object>> byId=new HashMap<>(); for (Map<String,Object> row:eligible) byId.put(number(row,"id"),row);
        List<Map<String,Object>> selected=problemIds.stream().map(byId::get).collect(Collectors.toList());
        List<Map<String,Object>> students=db.queryForList("SELECT u.uuid AS uid,u.username,u.realname,u.number FROM edu_class_member m JOIN user_info u ON u.uuid=m.uid WHERE m.class_id=? AND m.status=0 AND m.role='STUDENT' ORDER BY CASE WHEN COALESCE(u.number,'')='' THEN 1 ELSE 0 END,u.number,u.username,u.uuid LIMIT 2001",classId);
        if (students.size()>2000) throw new EduException(400,"本次班级人数超过2000名，请分班生成进度表");
        Map<String,Map<Long,Map<String,Object>>> scores=new HashMap<>();
        if (!students.isEmpty()) {
            List<Object> args=students.stream().map(row->row.get("uid")).collect(Collectors.toList()); args.addAll(problemIds);
            String sql="SELECT j.uid,j.pid,MAX(CASE WHEN j.status=0 THEN 1 ELSE 0 END) AS accepted,"+
                    "MAX(CASE WHEN j.status=8 THEN 1 ELSE 0 END) AS partial,"+
                    "MAX(CASE WHEN j.status IN (-5,-4,-3,-2,-1,1,2,3,4) THEN 1 ELSE 0 END) AS wrong,"+
                    "MAX(CASE WHEN j.status IS NULL OR j.status NOT IN (-5,-4,-3,-2,-1,0,1,2,3,4,8) THEN 1 ELSE 0 END) AS pending "+
                    "FROM judge j WHERE j.cid=0 AND j.gid IS NULL AND j.uid IN ("+marks(students.size())+") AND j.pid IN ("+marks(problemIds.size())+") GROUP BY j.uid,j.pid";
            for (Map<String,Object> row:db.queryForList(sql,args.toArray())) scores.computeIfAbsent((String)row.get("uid"),ignored->new HashMap<>()).put(number(row,"pid"),row);
        }
        Map<String,Long> names=students.stream().collect(Collectors.groupingBy(EduClassProgressService::studentName,Collectors.counting()));
        List<Map<String,Object>> rows=new ArrayList<>(); int pendingCells=0;
        for (Map<String,Object> student:students) {
            Map<String,Object> row=new LinkedHashMap<>(); row.put("uid",student.get("uid")); row.put("username",student.get("username")); row.put("number",student.get("number"));
            String name=studentName(student); if (names.get(name)>1) name+="（"+student.get("username")+"）"; row.put("name",name);
            Map<Long,Map<String,Object>> own=scores.getOrDefault((String)student.get("uid"),Collections.emptyMap());
            List<String> results=new ArrayList<>(); int completed=0;
            for (Long pid:problemIds) {
                Map<String,Object> value=own.getOrDefault(pid,Collections.emptyMap());
                String state=status(number(value,"accepted")>0,number(value,"partial")>0,number(value,"wrong")>0,number(value,"pending")>0);
                results.add(state); if ("CORRECT".equals(state)) completed++; if (number(value,"pending")>0) pendingCells++;
            }
            row.put("results",results); row.put("completedCount",completed); rows.add(row);
        }
        Map<String,Object> result=new LinkedHashMap<>(); result.put("classId",classId); result.put("className",klass.get("name")); result.put("classDisplayId",klass.get("displayId"));
        result.put("problems",selected); result.put("students",rows); result.put("studentCount",rows.size()); result.put("problemCount",selected.size()); result.put("pendingCells",pendingCells); result.put("generatedAt",System.currentTimeMillis());
        return result;
    }
    private static String studentName(Map<String,Object> student) {
        Object name=student.get("realname"); return name!=null && !name.toString().trim().isEmpty() ? name.toString().trim() : (String)student.get("username");
    }
}
