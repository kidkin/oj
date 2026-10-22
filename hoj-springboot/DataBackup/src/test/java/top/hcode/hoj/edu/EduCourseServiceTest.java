package top.hcode.hoj.edu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EduCourseServiceTest {
    private JdbcTemplate db;
    private EduClassService classes;
    private EduCourseService service;
    private Map<String, Object> row;

    @BeforeEach void setup() {
        db = mock(JdbcTemplate.class); classes = mock(EduClassService.class);
        service = new EduCourseService(db, classes);
        when(classes.actor()).thenReturn("owner");
        when(classes.hasRole("owner", "teacher")).thenReturn(true);
        row = new HashMap<>(); row.put("id", 7L); row.put("ownerUid", "owner");
        row.put("status", 1); row.put("revision", 3L);
        when(db.queryForList("SELECT id,title,description,owner_uid AS ownerUid,status,revision FROM edu_course WHERE id=? FOR UPDATE", 7L))
                .thenReturn(Collections.singletonList(row));
    }
    private void noWrites() {
        verify(db, never()).update(anyString(), any(Object[].class));
        verify(db, never()).batchUpdate(anyString(), anyList());
    }
    @Test void studentsCannotCreateCoursesOrBrowseProblemPicker() {
        when(classes.hasRole("owner", "teacher")).thenReturn(false);
        assertEquals(403, assertThrows(EduException.class, () -> service.create("课程", "")).getStatus());
        assertEquals(403, assertThrows(EduException.class, () -> service.publicProblems(1, 10, "")).getStatus());
        verifyNoInteractions(db);
    }
    @Test void referencingTeachersCannotEditSomeoneElsesCourse() {
        row.put("ownerUid", "someone-else");
        assertEquals(403, assertThrows(EduException.class, () -> service.update(7, "课程", "", 1, 3L)).getStatus());
        assertEquals(403, assertThrows(EduException.class, () -> service.replaceProblems(7, Collections.singletonList(10L), 3L)).getStatus());
        noWrites();
    }
    @Test void revokingTeacherRightsBlocksTheExistingOwnerImmediately() {
        when(classes.hasRole("owner", "teacher")).thenReturn(false);
        assertEquals(403, assertThrows(EduException.class, () -> service.replaceProblems(7, Collections.emptyList(), 3L)).getStatus());
        noWrites();
    }
    @Test void staleAndMissingRevisionsCannotOverwriteNewerQuestions() {
        for (Long revision : Arrays.asList(2L, null))
            assertEquals(409, assertThrows(EduException.class, () -> service.replaceProblems(7, Collections.emptyList(), revision)).getStatus());
        assertEquals(409, assertThrows(EduException.class, () -> service.update(7, "课程", "", 1, 2L)).getStatus());
        noWrites();
    }
    @Test void duplicateAndOversizedQuestionListsNeverChangeTheOldList() {
        assertThrows(EduException.class, () -> service.replaceProblems(7, Arrays.asList(10L, 10L), 3L));
        assertThrows(EduException.class, () -> service.replaceProblems(7, Collections.nCopies(501, 10L), 3L));
        assertThrows(EduException.class, () -> service.replaceProblems(7, Arrays.asList(10L, null), 3L));
        noWrites();
    }
    @Test void oneUnavailableQuestionRejectsTheEntireReplacement() {
        when(db.queryForList(startsWith("SELECT p.id FROM problem"), eq(Long.class), any(Object[].class)))
                .thenReturn(Collections.singletonList(10L));
        assertEquals(400, assertThrows(EduException.class, () -> service.replaceProblems(7, Arrays.asList(10L, 11L), 3L)).getStatus());
        noWrites();
    }
    @Test void referencedCoursesCannotBeUnpublishedUsingASnapshotCount() {
        when(db.queryForList("SELECT id FROM edu_class_course WHERE course_id=? FOR UPDATE", Long.class, 7L))
                .thenReturn(Collections.singletonList(1L));
        assertEquals(400, assertThrows(EduException.class, () -> service.update(7, "课程", "", 0, 3L)).getStatus());
        noWrites();
    }
    @Test void invalidMetadataCannotChangePublishedCourses() {
        assertThrows(EduException.class, () -> service.update(7, " ", "", 1, 3L));
        assertThrows(EduException.class, () -> service.update(7, "课程", "", 4, 3L));
        noWrites();
    }
    @Test void aDraftInABatchPreventsAllClassReferenceWrites() {
        Map<String, Object> target = new HashMap<>(); target.put("id", 5L); target.put("status", 0);
        when(db.queryForList("SELECT id,status FROM edu_class WHERE id=? FOR UPDATE", 5L)).thenReturn(Collections.singletonList(target));
        when(classes.canManage("owner", 5L)).thenReturn(true);
        row.put("status", 0);
        assertEquals(400, assertThrows(EduException.class, () -> service.attach(5L, Arrays.asList(7L, 8L))).getStatus());
        noWrites();
    }
    @Test void unrelatedAndArchivedClassesRejectNewReferences() {
        Map<String, Object> target = new HashMap<>(); target.put("id", 5L); target.put("status", 0);
        when(db.queryForList("SELECT id,status FROM edu_class WHERE id=? FOR UPDATE", 5L)).thenReturn(Collections.singletonList(target));
        assertEquals(403, assertThrows(EduException.class, () -> service.attach(5L, Collections.singletonList(7L))).getStatus());
        when(classes.canManage("owner", 5L)).thenReturn(true); target.put("status", 1);
        assertEquals(400, assertThrows(EduException.class, () -> service.attach(5L, Collections.singletonList(7L))).getStatus());
        noWrites();
    }
    @Test void iconChangesRequireLiveOwnershipAndCurrentRevisionBeforeFileAccess() {
        row.put("ownerUid", "someone-else");
        assertEquals(403, assertThrows(EduException.class, () -> service.updateIcon(7, null, 3L, false)).getStatus());
        row.put("ownerUid", "owner");
        assertEquals(409, assertThrows(EduException.class, () -> service.updateIcon(7, null, 2L, false)).getStatus());
        when(classes.hasRole("owner", "teacher")).thenReturn(false);
        assertEquals(403, assertThrows(EduException.class, () -> service.updateIcon(7, null, 3L, true)).getStatus());
        noWrites();
    }
    @Test void customCourseNumbersStillRequireOwnershipAndCurrentRevision() {
        row.put("ownerUid","someone-else");
        assertEquals(403,assertThrows(EduException.class,()->service.update(7,"课程","",1,3L,12L)).getStatus());
        row.put("ownerUid","owner");
        assertEquals(409,assertThrows(EduException.class,()->service.update(7,"课程","",1,2L,12L)).getStatus());
        assertEquals(400,assertThrows(EduException.class,()->service.update(7,"课程","",1,3L,0L)).getStatus());
        noWrites();
    }
    @Test void invalidOrDuplicateQuestionDisplayNumbersNeverReplaceTheOldList() {
        assertEquals(400,assertThrows(EduException.class,()->service.replaceProblems(7,Arrays.asList(10L,11L),3L,Arrays.asList(2L,2L))).getStatus());
        assertEquals(400,assertThrows(EduException.class,()->service.replaceProblems(7,Arrays.asList(10L,11L),3L,Collections.singletonList(2L))).getStatus());
        noWrites();
    }
}
