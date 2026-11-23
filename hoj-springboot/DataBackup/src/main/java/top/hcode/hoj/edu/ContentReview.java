package top.hcode.hoj.edu;

import org.jsoup.Jsoup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.hcode.hoj.pojo.entity.judge.Judge;
import top.hcode.hoj.pojo.dto.ProblemDTO;
import top.hcode.hoj.pojo.entity.problem.Problem;
import top.hcode.hoj.pojo.entity.problem.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class ContentReview {
    private final JdbcTemplate db;
    private final EduClassService classes;
    private volatile Rules cached;
    private static class Rules {
        final long revision;
        final ReviewMatcher matcher;
        Rules(long revision,String text) {
            this.revision=revision;
            this.matcher=new ReviewMatcher(Arrays.asList(text.split("\\n")));
        }
    }
    public ContentReview(JdbcTemplate db, EduClassService classes) { this.db=db; this.classes=classes; }

    private static Map<String,String> matches(Rules rules,String text) {
        Map<String,String> result=BuiltinReviewDictionary.INSTANCE.find(text);
        result.putAll(rules.matcher.find(text));
        return result;
    }
    private Rules rules() {
        Map<String,Object> row=currentRules();
        long revision=((Number)row.get("revision")).longValue();
        Rules rules=cached;
        if (rules==null || rules.revision!=revision) {
            rules=new Rules(revision,(String)row.get("words"));
            cached=rules;
        }
        return rules;
    }
    private Map<String,Object> currentRules() {
        return db.queryForMap("SELECT revision,words FROM edu_review_rules ORDER BY revision DESC LIMIT 1");
    }
    private String requireRoot() {
        String actor=classes.actor();
        if (!classes.hasRole(actor,"root")) throw new EduException(403,"仅超级管理员可执行此操作");
        return actor;
    }
    public Map<String,Object> settings() {
        requireRoot();
        Map<String,Object> result=new LinkedHashMap<>(currentRules());
        result.put("categories",BuiltinReviewDictionary.INSTANCE.categories());
        return result;
    }
    public Map<String,Object> dictionary(String category,String keyword,int page){
        requireRoot(); return BuiltinReviewDictionary.INSTANCE.page(category,keyword,page);
    }
    @Transactional
    public void configure(long expected,String text) {
        String actor=requireRoot();
        if (text==null || text.length()>12000) throw new EduException(400,"词表最多12000个字符");
        Set<String> words=new LinkedHashSet<>();
        for (String line:text.split("\\r?\\n")) {
            String word=ReviewMatcher.normalize(line.trim());
            if (word.isEmpty()) continue;
            if (word.length()<2 || word.length()>64) throw new EduException(400,"每个关键词须为2至64个字符");
            words.add(word);
        }
        if (words.size()>500) throw new EduException(400,"每版最多500个词");
        long latest=db.queryForObject("SELECT revision FROM edu_review_rules ORDER BY revision DESC LIMIT 1 FOR UPDATE",Long.class);
        if (expected!=latest) throw new EduException(409,"词表已更新，请重新加载");
        db.update("INSERT INTO edu_review_rules(revision,words,editor) VALUES(?,?,?)",latest+1,String.join("\n",words),actor);
    }
    private static String hash(String... parts) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for (String s:parts) {
                byte[] bytes=String.valueOf(s).getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return String.format("%064x",new java.math.BigInteger(1,digest.digest()));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String record(Rules rules,String key,String kind,String uid,Long submit,String subject,Map<String,String> matches) {
        List<String> excerpts=new ArrayList<>();
        matches.forEach((term,s)->excerpts.add(term+"　"+s));
        db.update("INSERT INTO edu_content_review(fingerprint,kind,uid,submit_id,subject,hits,excerpt,revision) "
                +"VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE fingerprint=VALUES(fingerprint)",
                key,kind,uid,submit,subject.substring(0,Math.min(subject.length(),250)),String.join("、",matches.keySet()),
                String.join("\n",excerpts),rules.revision);
        return db.queryForObject("SELECT decision FROM edu_content_review WHERE fingerprint=?",String.class,key);
    }
    public void privatePaperCode(String code) {
        if (!matches(rules(), code).isEmpty()) throw new EduException(400, "代码包含审核词条，请检查后重新提交");
    }
    public boolean code(Judge j) {
        Rules rules=rules();
        Map<String,String> matches=matches(rules,j.getCode());
        if (matches.isEmpty()) return true;
        // Submitted source is immutable. Preserve a teacher's decision for that submission and rule version.
        String key=hash("code",String.valueOf(j.getSubmitId()),String.valueOf(rules.revision));
        return "ALLOW".equals(record(rules,key,"CODE",j.getUid(),j.getSubmitId(),"提交 #"+j.getSubmitId(),matches));
    }
    public boolean canShare(Judge j) {
        boolean allowed=code(j);
        return allowed && db.queryForObject("SELECT COUNT(*) FROM edu_content_review WHERE submit_id=? AND decision<>'ALLOW'",
                Long.class,j.getSubmitId())==0;
    }
    // Commit the review ticket even when its caller rolls back the pending publication.
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public String problem(ProblemDTO dto, boolean edit) {
        Rules rules=rules();
        Problem p=dto.getProblem();
        List<String> fields=new ArrayList<>(Arrays.asList(p.getTitle(),p.getDescription(),p.getInput(),p.getOutput(),
                p.getExamples(),p.getHint(),p.getSource(),p.getProblemId(),p.getAuthor()));
        if (dto.getTags()!=null) for (Tag tag:dto.getTags()) fields.add(tag.getName());
        Map<String,String> found=new LinkedHashMap<>();
        if (dto.getCodeTemplates()!=null) dto.getCodeTemplates().forEach(t->fields.add(t.getCode()));
        for (String field:fields) if (field!=null && found.size()<20) {
            found.putAll(matches(rules,field));
            if (field.contains("<") || field.contains("&")) found.putAll(matches(rules,Jsoup.parse(field).text()));
        }
        if (found.isEmpty()) return null;
        String actor=classes.actor();
        fields.add(actor); fields.add(String.valueOf(rules.revision)); fields.add(edit?"edit":"add");
        fields.add(String.valueOf(p.getId())); fields.add(String.valueOf(p.getGid()));
        String result=record(rules,hash(fields.toArray(new String[0])),"PROBLEM",actor,null,String.valueOf(p.getTitle()),found);
        if ("ALLOW".equals(result)) return null;
        return "DENY".equals(result)?"该版题面未通过审核，请修改后再提交":"题面已送审。通过后请重新保存或导入同一版题面。";
    }
    private static final String SCOPE=" r.kind='CODE' AND EXISTS (SELECT 1 FROM edu_class_member m JOIN judge j "
            +"ON j.uid=m.uid AND j.submit_id=r.submit_id WHERE m.class_id=? AND m.role='STUDENT' AND m.status=0 "
            +"AND j.cid=0 AND j.gid IS NULL AND EXISTS(SELECT 1 FROM problem p WHERE p.id=j.pid AND "+EduProgress.PUBLIC_PROBLEM+"))";
    void manager(long classId) {
        if (!classes.canManage(classes.actor(),classId)) throw new EduException(403,"仅管理本班的教师可查看");
    }
    public List<Map<String,Object>> queue(Long classId,String state,long before) {
        if (!Arrays.asList("PENDING","ALLOW","DENY").contains(state)) throw new EduException(400,"无效的审核状态");
        List<Object> args=new ArrayList<>(); args.add(state); args.add(before<=0?Long.MAX_VALUE:before);
        String scope="";
        if (classId==null) requireRoot();
        else { manager(classId); scope=" AND "+SCOPE; args.add(classId); }
        return db.queryForList("SELECT r.id,r.kind,r.subject,r.hits,r.excerpt,r.decision,r.revision,r.created_at AS createdAt,"
                +"u.username,reviewer.username AS reviewedBy FROM edu_content_review r LEFT JOIN user_info u ON u.uuid=r.uid "
                +"LEFT JOIN user_info reviewer ON reviewer.uuid=r.reviewer WHERE r.decision=? AND r.id<?"+scope+" ORDER BY r.id DESC LIMIT 51",args.toArray());
    }
    @Transactional
    public void decide(Long classId,long id,String decision) {
        if (!Arrays.asList("ALLOW","DENY").contains(decision)) throw new EduException(400,"无效的审核操作");
        String actor=classes.actor();
        String scope=""; List<Object> args=new ArrayList<>(Arrays.asList(decision,actor,id));
        if (classId==null) requireRoot();
        else { manager(classId); scope=" AND "+SCOPE; args.add(classId); }
        if (db.update("UPDATE edu_content_review r SET decision=?,reviewer=?,reviewed_at=NOW() WHERE id=? AND decision='PENDING'"+scope,args.toArray())!=1)
            throw new EduException(409,"记录已处理或不在你的审核范围");
    }
}
