package top.hcode.hoj.edu;

import java.util.*;
import java.util.regex.Pattern;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicNames {
    private final JdbcTemplate db;
    private final EduClassService classes;
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{Nd}_·-]{2,24}");

    public PublicNames(JdbcTemplate db, EduClassService classes) {
        this.db = db;
        this.classes = classes;
    }
    public static boolean staff() {
        Subject viewer = SecurityUtils.getSubject();
        return viewer.isAuthenticated() && (viewer.hasRole("root") || viewer.hasRole("teacher") || viewer.hasRole("admin"));
    }
    public String mine() {
        return db.queryForObject("SELECT COALESCE(n.public_name,u.username) FROM user_info u LEFT JOIN edu_effective_display_name n ON n.uid=u.uuid WHERE u.uuid=?",String.class,classes.actor());
    }
    String validate(String uid,String input) {
        String name = input == null ? "" : input.trim();
        if (!NAME.matcher(name).matches()) throw new EduException(400,"显示名须为 2 至 24 个汉字、字母、数字或 _ · -");
        if (!BuiltinReviewDictionary.INSTANCE.find(name).isEmpty()) throw new EduException(400,"请换一个显示名");
        if (db.queryForObject("SELECT COUNT(*) FROM user_info WHERE uuid<>? AND (username=? OR realname=?)",Long.class,uid,name,name)>0)
            throw new EduException(400,"该显示名不可用");
        if (db.queryForObject("SELECT COUNT(*) FROM edu_effective_display_name WHERE uid<>? AND public_name=?",Long.class,uid,name)>0)
            throw new EduException(400,"该显示名不可用");
        return name;
    }
    void publish(String uid,String name){
        try {
            if (db.queryForObject("SELECT COUNT(*) FROM edu_display_name WHERE uid=?",Long.class,uid)>0)
                db.update("UPDATE edu_display_name SET public_name=? WHERE uid=?",name,uid);
            else db.update("INSERT INTO edu_display_name(uid,public_name) VALUES (?,?)",uid,name);
        } catch (DuplicateKeyException e) { throw new EduException(400,"该显示名不可用"); }
    }
    public Map<String,String> labels(Set<String> uids,boolean roster) {
        Map<String,String> labels=new HashMap<>();
        if (uids.isEmpty()) return labels;
        String sql = roster ? "SELECT uuid,COALESCE(NULLIF(TRIM(realname),''),username) FROM user_info WHERE uuid"
                            : "SELECT uid,public_name FROM edu_effective_display_name WHERE uid";
        db.query(sql+" IN ("+String.join(",",Collections.nCopies(uids.size(),"?"))+")",uids.toArray(),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> labels.put(rs.getString(1),rs.getString(2)));
        return labels;
    }
}
