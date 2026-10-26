package top.hcode.hoj.edu;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.hcode.hoj.pojo.entity.problem.Problem;
import top.hcode.hoj.utils.ProblemSourceVisibility;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProblemSourceVisibilityTest {
    private Subject viewer;
    private Problem problem;
    @BeforeEach void setup() {
        viewer = mock(Subject.class); ThreadContext.bind(viewer);
        problem = new Problem().setId(7L).setTitle("Public title").setDescription("Public statement").setSource("Administrative source");
    }
    @AfterEach void cleanup() { ThreadContext.unbindSubject(); }
    @Test void studentsReceiveTheStatementWithoutSourceAndStorageIsUnchanged() {
        Problem result = ProblemSourceVisibility.forViewer(problem);
        assertNull(result.getSource()); assertEquals(problem.getId(), result.getId());
        assertEquals(problem.getDescription(), result.getDescription());
        assertEquals("Administrative source", problem.getSource());
    }
    @Test void teacherRoleAloneDoesNotGrantSourceAccess() {
        when(viewer.hasRole("teacher")).thenReturn(true);
        assertNull(ProblemSourceVisibility.forViewer(problem).getSource());
    }
    @Test void anonymousResponsesDoNotContainSource() {
        assertNull(ProblemSourceVisibility.forViewer(problem).getSource());
    }
    @Test void eachAdministrativeRoleCanReadSource() {
        for (String role : new String[]{"root","admin","problem_admin"}) {
            reset(viewer); when(viewer.hasRole(role)).thenReturn(true);
            assertEquals("Administrative source", ProblemSourceVisibility.forViewer(problem).getSource());
        }
    }
}
