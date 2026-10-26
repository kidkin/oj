package top.hcode.hoj.edu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EduClassProgressServiceTest {
    private JdbcTemplate db;
    private EduClassService classes;
    private EduClassProgressService service;
    private Map<String,Object> klass;
    private List<Map<String,Object>> questions,students,judgements;
    private static Map<String,Object> row(Object... fields) {
        Map<String,Object> result=new HashMap<>(); for (int i=0;i<fields.length;i+=2) result.put((String)fields[i],fields[i+1]); return result;
    }
    @BeforeEach void setup() {
        questions=Arrays.asList(row("id",1L,"title","一","problemId","P1"),row("id",2L,"title","二","problemId","P2"));
        students=Collections.emptyList(); judgements=Collections.emptyList();
        db=mock(JdbcTemplate.class,invocation -> {
            if (invocation.getMethod().getName().equals("queryForObject")) return 0L;
            if (invocation.getMethod().getName().equals("queryForList")) {
                String sql=invocation.getArgument(0);
                if (sql.startsWith("SELECT p.id")) return questions;
                if (sql.startsWith("SELECT u.uuid")) return students;
                if (sql.startsWith("SELECT j.uid")) return judgements;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        }); classes=mock(EduClassService.class); service=new EduClassProgressService(db,classes);
        klass=row("id",7L,"displayId",12L,"name","测试班","canManage",true); when(classes.detail(7L)).thenReturn(klass);
    }
    @Test void classMembersWithoutManagementRightsCannotReadReportsOrPicker() {
        klass.put("canManage",false);
        assertEquals(403,assertThrows(EduException.class,()->service.generate(7L,Arrays.asList(1L,2L))).getStatus());
        assertEquals(403,assertThrows(EduException.class,()->service.problems(7L,1,10,"",null)).getStatus());
        verifyNoInteractions(db);
    }
    @Test void unrelatedTeachersCannotReadClassMembersOrSubmissions() {
        when(classes.detail(7L)).thenThrow(new EduException(403,"无权访问"));
        assertThrows(EduException.class,()->service.generate(7L,Arrays.asList(1L,2L))); verifyNoInteractions(db);
    }
    @Test void invalidSelectionsAreRejectedBeforeAnyProblemOrStudentRead() {
        List<List<Long>> inputs=Arrays.asList(null,Collections.emptyList(),Arrays.asList(1L,1L),Arrays.asList(1L,null),Collections.singletonList(-1L),Collections.nCopies(51,1L));
        for (List<Long> value:inputs) assertEquals(400,assertThrows(EduException.class,()->service.generate(7L,value)).getStatus());
        verifyNoInteractions(db);
    }
    @Test void oneHiddenQuestionPreventsTheWholeStudentReport() {
        questions=Collections.singletonList(row("id",1L));
        assertEquals(400,assertThrows(EduException.class,()->service.generate(7L,Arrays.asList(1L,2L))).getStatus());
        assertNoQuery("SELECT u.uuid");
    }
    @Test void historicalBestResultHasAStablePriority() {
        assertEquals("CORRECT",EduClassProgressService.status(true,true,true,true));
        assertEquals("PARTIAL",EduClassProgressService.status(false,true,true,true));
        assertEquals("WRONG",EduClassProgressService.status(false,false,true,true));
        assertEquals("PENDING",EduClassProgressService.status(false,false,false,true));
        assertEquals("NOT_SUBMITTED",EduClassProgressService.status(false,false,false,false));
    }
    @Test @SuppressWarnings("unchecked") void preservesSelectedColumnsAndEveryStudentIncludingNoSubmissions() {
        students=Arrays.asList(row("uid","a","username","alice","realname","同名"),row("uid","b","username","bob","realname","同名"),row("uid","c","username","carol","realname"," "));
        judgements=Arrays.asList(row("uid","a","pid",1L,"accepted",1,"partial",1,"wrong",1,"pending",1),row("uid","a","pid",2L,"partial",1,"wrong",1),row("uid","b","pid",1L,"wrong",1),row("uid","c","pid",1L,"pending",1),row("uid","outsider","pid",2L,"accepted",1));
        Map<String,Object> result=service.generate(7L,Arrays.asList(2L,1L));
        List<Map<String,Object>> students=(List<Map<String,Object>>)result.get("students");
        assertEquals(3,students.size()); assertEquals("同名（alice）",students.get(0).get("name")); assertEquals("carol",students.get(2).get("name"));
        assertEquals(Arrays.asList("PARTIAL","CORRECT"),students.get(0).get("results"));
        assertEquals(Arrays.asList("NOT_SUBMITTED","WRONG"),students.get(1).get("results"));
        assertEquals(Arrays.asList("NOT_SUBMITTED","PENDING"),students.get(2).get("results"));
        assertEquals(2L,((List<Map<String,Object>>)result.get("problems")).get(0).get("id"));
        assertFalse(result.containsKey("code"));
    }
    @Test @SuppressWarnings("unchecked") void emptyClassesProduceAnEmptyReportWithoutJudgeQueries() {
        Map<String,Object> result=service.generate(7L,Arrays.asList(1L,2L));
        assertTrue(((List<Object>)result.get("students")).isEmpty());
        assertNoQuery("SELECT j.uid");
    }
    @Test void oversizedClassesFailExplicitlyInsteadOfDroppingStudents() {
        students=Collections.nCopies(2001,row("uid","a"));
        assertEquals(400,assertThrows(EduException.class,()->service.generate(7L,Arrays.asList(1L,2L))).getStatus());
        assertNoQuery("SELECT j.uid");
    }
    @Test void pickerCannotUseAnUnrelatedCourseAsAReadBypass() {
        assertEquals(400,assertThrows(EduException.class,()->service.problems(7L,1,10,"",9L)).getStatus());
    }
    private void assertNoQuery(String prefix) {
        assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(call->call.getMethod().getName().equals("queryForList") && call.getArgument(0).toString().startsWith(prefix)));
    }
}
