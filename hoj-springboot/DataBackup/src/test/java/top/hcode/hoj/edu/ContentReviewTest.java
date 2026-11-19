package top.hcode.hoj.edu;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import top.hcode.hoj.pojo.dto.ProblemDTO;
import top.hcode.hoj.pojo.entity.problem.Problem;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentReviewTest {
    @Test void completeTermsAndFallbacks() {
        ReviewMatcher matcher=new ReviewMatcher(Arrays.asList("共产党","中国共产党","ＡＢＣ"));
        assertEquals(3,matcher.find("中国共产党 abc").size());
        assertTrue(matcher.find("一共产生几组数据").isEmpty());
    }
    @Test void classCodeAndQueueRequireManagement() {
        JdbcTemplate db=mock(JdbcTemplate.class);
        EduClassService classes=mock(EduClassService.class);
        when(classes.actor()).thenReturn("student");
        ContentReview permissions=new ContentReview(db,classes);
        assertThrows(EduException.class,()->permissions.queue(17L,"PENDING",0));
        assertThrows(EduException.class,permissions::settings);
        assertThrows(EduException.class,()->new ClassSimilarity(db,permissions).compare(17,1,"cpp",true,75));
        verifyNoInteractions(db);
    }
    @Test void snippetsSurviveRenamingAndLayout() {
        String code="def process(data):\n answer = []\n for item in data:\n  if item % 2 == 0:\n   answer.append(item * 3 + 1)\n  else:\n   answer.append(item // 2)\n return sorted(answer)\nprint(process(list(range(9))))";
        SolutionFingerprint a=new SolutionFingerprint(code,true);
        SolutionFingerprint b=new SolutionFingerprint("# comment\n"+code.replace("data","values").replace("answer","out")+" # done",true);
        assertTrue(a.substantial()); assertEquals(a.keys(),b.keys());
        assertEquals(100,SolutionFingerprint.percent(a.keys().size(),a.keys().size(),b.keys().size()));
        assertFalse(a.evidence(b).isEmpty());
        assertFalse(new SolutionFingerprint("print(int(input()) + int(input()))",true).substantial());
        b.keys().removeAll(a.keys()); assertFalse(b.substantial());
    }
    @Test void ordinaryProblemSkipsReviewAndPoliticalMarkupCreatesTicket() {
        JdbcTemplate db=mock(JdbcTemplate.class);
        EduClassService classes=mock(EduClassService.class);
        when(classes.actor()).thenReturn("author");
        Map<String,Object> rules=new HashMap<>(); rules.put("revision",1L); rules.put("words","共产党");
        when(db.queryForMap(anyString())).thenReturn(rules);
        ProblemDTO form=new ProblemDTO().setProblem(new Problem().setTitle("一共产生多少个组合"));
        ContentReview review=new ContentReview(db,classes);
        assertNull(review.problem(form,false));
        form.getProblem().setDescription("<b>共产党</b>");
        assertNotNull(review.problem(form,true));
        verify(classes,times(1)).actor();
    }
}
