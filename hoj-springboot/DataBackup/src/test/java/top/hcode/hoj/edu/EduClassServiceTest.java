package top.hcode.hoj.edu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import top.hcode.hoj.utils.RedisUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EduClassServiceTest {
    private JdbcTemplate db;
    private EduClassService service;
    private Map<String, Object> row;

    @BeforeEach void setup() {
        db = mock(JdbcTemplate.class);
        service = spy(new EduClassService(db, new RedisUtils()) { @Override protected String actor() { return "teacher"; } });
        when(db.queryForObject(anyString(), any(Object[].class), eq(Long.class))).thenReturn(0L);
        doReturn(true).when(service).hasRole("teacher", "teacher");
        row = new HashMap<>(); row.put("status", 0); row.put("owner_uid", "owner");
        when(db.queryForList("SELECT * FROM edu_class WHERE id=? FOR UPDATE", 7L)).thenReturn(Collections.singletonList(row));
        doReturn(true).when(service).canManage("teacher", 7L);
    }

    @Test void studentCannotCreateClasses() {
        doReturn(false).when(service).hasRole("teacher", "teacher");
        EduException error = assertThrows(EduException.class, () -> service.create("班级", ""));
        assertEquals(403, error.getStatus()); verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void unrelatedTeacherCannotChangeMembers() {
        doReturn(false).when(service).canManage("teacher", 7L);
        assertEquals(403, assertThrows(EduException.class, () -> service.addMembers(7, Collections.singletonList("alice"), "STUDENT")).getStatus());
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void archivedClassRejectsMembershipChanges() {
        row.put("status", 1);
        assertThrows(EduException.class, () -> service.addMembers(7, Collections.singletonList("alice"), "STUDENT"));
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void invalidAccountRejectsEntireBatchBeforeWriting() {
        when(db.queryForList("SELECT uuid FROM user_info WHERE username=? AND status=0", String.class, "alice")).thenReturn(Collections.singletonList("alice-id"));
        when(db.queryForList("SELECT uuid FROM user_info WHERE username=? AND status=0", String.class, "missing")).thenReturn(Collections.emptyList());
        assertThrows(EduException.class, () -> service.addMembers(7, Arrays.asList("alice", "missing"), "STUDENT"));
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void duplicateAccountsOnlyWriteOneMembership() {
        when(db.queryForList("SELECT uuid FROM user_info WHERE username=? AND status=0", String.class, "alice")).thenReturn(Collections.singletonList("alice-id"));
        service.addMembers(7, Arrays.asList("alice", "alice"), "STUDENT");
        verify(db, times(1)).update(startsWith("INSERT INTO edu_class_member"), eq(7L), eq("alice-id"), eq("STUDENT"));
    }
    @Test void ownerCannotBeRemovedOrDemoted() {
        assertThrows(EduException.class, () -> service.removeMember(7, "owner"));
        when(db.queryForList("SELECT uuid FROM user_info WHERE username=? AND status=0", String.class, "owner-name")).thenReturn(Collections.singletonList("owner"));
        assertThrows(EduException.class, () -> service.addMembers(7, Collections.singletonList("owner-name"), "STUDENT"));
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void assigningClassTeacherRequiresGlobalTeacherAuthorization() {
        when(db.queryForList("SELECT uuid FROM user_info WHERE username=? AND status=0", String.class, "alice")).thenReturn(Collections.singletonList("alice-id"));
        assertThrows(EduException.class, () -> service.addMembers(7, Collections.singletonList("alice"), "TEACHER"));
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void teachersCannotGrantGlobalTeacherRights() {
        assertEquals(403, assertThrows(EduException.class, () -> service.grantTeacher("alice")).getStatus());
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void invalidClassDisplayNumbersDoNotChangeMetadata() {
        assertEquals(400,assertThrows(EduException.class,()->service.update(7,"班级","",0,0L)).getStatus());
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void changingAClassDisplayNumberKeepsItsPersistentId() {
        service.update(7,"班级","介绍",0,12L);
        verify(db).update("UPDATE edu_class SET name=?,description=?,status=? WHERE id=?","班级","介绍",0,7L);
        verify(db).update(startsWith("INSERT INTO edu_class_display"),eq(7L),eq(12L));
    }
}
