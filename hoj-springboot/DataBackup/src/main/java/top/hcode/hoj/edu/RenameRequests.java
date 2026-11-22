package top.hcode.hoj.edu;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RenameRequests {
    private static final int COST = 50;
    private final JdbcTemplate db;
    private final PublicNames names;
    private final EduClassService classes;
    private final GrowthAccountService wallet;
    public RenameRequests(JdbcTemplate db,PublicNames names,EduClassService classes,GrowthAccountService wallet){
        this.db=db; this.names=names; this.classes=classes; this.wallet=wallet;
    }
    private boolean staff(String uid){
        return classes.hasRole(uid,"root") || classes.hasRole(uid,"teacher") ||
               classes.hasRole(uid,"admin") || classes.hasRole(uid,"problem_admin");
    }
    private String lockAccount(String uid){
        return db.queryForObject("SELECT username FROM user_info WHERE uuid=? FOR UPDATE",String.class,uid);
    }
    public Map<String,Object> status(){
        String uid=classes.actor();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("current",names.mine());
        boolean direct=staff(uid);
        result.put("direct",direct);
        result.put("cost",COST);
        if(!direct){
            result.put("balance",Math.max(0,wallet.availableUnits(uid))/2.0);
            List<Map<String,Object>> rows=db.queryForList("SELECT public_name AS requestedName,state FROM edu_rename_request WHERE uid=? ORDER BY id DESC LIMIT 1",uid);
            if(!rows.isEmpty()) result.put("latest",rows.get(0));
        }
        return result;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public String apply(String input){
        String uid=classes.actor();
        lockAccount(uid);
        String name=names.validate(uid,input);
        if(staff(uid)) { names.publish(uid,name); return name; }
        List<String> pending=db.queryForList("SELECT public_name FROM edu_rename_request WHERE uid=? AND state='PENDING'",String.class,uid);
        if(!pending.isEmpty()){
            if(pending.get(0).equals(name)) return name;
            throw new EduException(409,"已有改名申请等待审核");
        }
        if(name.equals(names.mine())) throw new EduException(400,"请输入新的显示名");
        if(wallet.availableUnits(uid)<2L*COST) throw new EduException(409,"改名需要 50 可用积分");
        db.update("INSERT INTO edu_rename_request(uid,public_name) VALUES (?,?)",uid,name);
        return name;
    }
    private void requireReviewer(String reviewer,Long classId){
        if(classId==null ? !classes.hasRole(reviewer,"root") : !classes.canManage(reviewer,classId))
            throw new EduException(403,"无权审核此班学生");
    }
    private static final String MEMBER="EXISTS (SELECT 1 FROM edu_class_member m WHERE m.uid=r.uid AND m.role='STUDENT' AND m.status=0 AND m.class_id=?)";
    public List<Map<String,Object>> queue(Long classId,long after){
        requireReviewer(classes.actor(),classId);
        String sql="SELECT r.id,u.username,u.realname,COALESCE(n.public_name,u.username) AS currentName,r.public_name AS requestedName,r.requested_at AS requestedAt "
                +"FROM edu_rename_request r JOIN user_info u ON u.uuid=r.uid LEFT JOIN edu_display_name n ON n.uid=r.uid WHERE r.state='PENDING' AND r.id>? ";
        List<Object> args=new ArrayList<>(); args.add(after);
        if(classId!=null){sql+=" AND "+MEMBER; args.add(classId);}
        return db.queryForList(sql+" ORDER BY r.id LIMIT 51",args.toArray());
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void decide(long id,Long classId,boolean approve){
        String reviewer=classes.actor();
        requireReviewer(reviewer,classId);
        String sql="SELECT r.uid,r.public_name,r.state FROM edu_rename_request r WHERE r.id=?";
        Object[] args={id};
        if(classId!=null){sql+=" AND "+MEMBER; args=new Object[]{id,classId};}
        List<Map<String,Object>> rows=db.queryForList(sql+" FOR UPDATE",args);
        if(rows.isEmpty()) throw new EduException(404,"申请不在审核范围内");
        Map<String,Object> item=rows.get(0);
        String next=approve?"APPROVED":"REJECTED";
        if(next.equals(item.get("state"))) return;
        if(!"PENDING".equals(item.get("state"))) throw new EduException(409,"申请已处理");
        String uid=(String)item.get("uid");
        if(uid.equals(reviewer)) throw new EduException(403,"请由其他教师审核");
        if(approve){
            lockAccount(uid);
            String name=names.validate(uid,(String)item.get("public_name"));
            wallet.debit(uid,"rename:"+id,2L*COST);
            names.publish(uid,name);
        }
        db.update("UPDATE edu_rename_request SET state=?,reviewer=?,reviewed_at=NOW() WHERE id=?",next,reviewer,id);
    }
}
