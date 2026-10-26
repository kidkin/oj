package top.hcode.hoj.edu;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import top.hcode.hoj.common.exception.StatusAccessDeniedException;
import top.hcode.hoj.common.exception.StatusFailException;
import top.hcode.hoj.config.NacosSwitchConfig;
import top.hcode.hoj.config.WebConfig;
import top.hcode.hoj.dao.user.*;
import top.hcode.hoj.manager.admin.user.AdminUserManager;
import top.hcode.hoj.manager.msg.AdminNoticeManager;
import top.hcode.hoj.manager.oj.PassportManager;
import top.hcode.hoj.pojo.dto.AdminCreateUserDTO;
import top.hcode.hoj.pojo.dto.RegisterDTO;
import top.hcode.hoj.pojo.entity.user.*;
import top.hcode.hoj.service.admin.user.impl.AdminUserServiceImpl;
import javax.validation.Validation;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountProvisioningTest {
    private AdminUserManager manager;
    private UserInfoEntityService users;
    private UserRoleEntityService roles;
    private UserRecordEntityService records;
    private AdminNoticeManager notices;

    @BeforeEach void setup() {
        manager = new AdminUserManager();
        users = mock(UserInfoEntityService.class); roles = mock(UserRoleEntityService.class);
        records = mock(UserRecordEntityService.class); notices = mock(AdminNoticeManager.class);
        ReflectionTestUtils.setField(manager, "userInfoEntityService", users);
        ReflectionTestUtils.setField(manager, "userRoleEntityService", roles);
        ReflectionTestUtils.setField(manager, "userRecordEntityService", records);
        ReflectionTestUtils.setField(manager, "adminNoticeManager", notices);
        when(users.save(any(UserInfo.class))).thenReturn(true);
        when(roles.save(any(UserRole.class))).thenReturn(true);
        when(records.save(any(UserRecord.class))).thenReturn(true);
    }

    private AdminCreateUserDTO account() {
        AdminCreateUserDTO dto = new AdminCreateUserDTO();
        dto.setUsername("student_2026"); dto.setPassword("Account123!"); dto.setRealname("测试学生");
        return dto;
    }

    @Test void legacySettingsCannotEnablePublicRegistrationOrItsEmailEndpoint() throws Exception {
        WebConfig config = new WebConfig(); config.setRegister(true);
        assertFalse(config.getRegister());
        assertTrue(new ObjectMapper().writeValueAsString(config).contains("\"register\":false"));
        NacosSwitchConfig settings = mock(NacosSwitchConfig.class);
        when(settings.getWebConfig()).thenReturn(config);
        PassportManager passport = new PassportManager();
        ReflectionTestUtils.setField(passport, "nacosSwitchConfig", settings);
        assertThrows(StatusAccessDeniedException.class, () -> passport.register(new RegisterDTO()));
        assertThrows(StatusAccessDeniedException.class, () -> passport.getRegisterCode("student@example.com"));
    }

    @Test void createsAnOrdinaryAccountWithTheChosenPasswordAndStatistics() throws Exception {
        AdminCreateUserDTO dto = account(); manager.createUser(dto);
        ArgumentCaptor<UserInfo> saved = ArgumentCaptor.forClass(UserInfo.class);
        ArgumentCaptor<UserRole> role = ArgumentCaptor.forClass(UserRole.class);
        ArgumentCaptor<UserRecord> record = ArgumentCaptor.forClass(UserRecord.class);
        verify(users).save(saved.capture()); verify(roles).save(role.capture()); verify(records).save(record.capture());
        UserInfo user = saved.getValue();
        assertEquals(dto.getUsername(), user.getUsername()); assertEquals(dto.getRealname(), user.getRealname());
        assertEquals(SecureUtil.md5(dto.getPassword()), user.getPassword()); assertNull(user.getEmail());
        assertEquals(1002L, role.getValue().getRoleId());
        assertEquals(user.getUuid(), role.getValue().getUid()); assertEquals(user.getUuid(), record.getValue().getUid());
        assertFalse(dto.toString().contains(dto.getPassword()));
    }

    @Test void existingUserIsNeverOverwritten() {
        when(users.count(any())).thenReturn(1);
        assertThrows(StatusFailException.class, () -> manager.createUser(account()));
        verify(users, never()).save(any(UserInfo.class)); verifyNoInteractions(roles, records, notices);
    }

    @Test void rejectsPasswordsWhichTheLoginFormWouldTrim() {
        for (String password : Arrays.asList(" Account123!", "Account123! ", "\tAccount123!")) {
            AdminCreateUserDTO dto = account(); dto.setPassword(password);
            assertThrows(StatusFailException.class, () -> manager.createUser(dto));
        }
        verify(users, never()).save(any(UserInfo.class));
    }

    @Test void incompleteAccountCreationFailsWithoutSendingNotices() {
        when(records.save(any(UserRecord.class))).thenReturn(false);
        assertThrows(StatusFailException.class, () -> manager.createUser(account()));
        verifyNoInteractions(notices);
    }

    @Test void validatesAccountInputBeforeDatabaseWrites() {
        try (javax.validation.ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            javax.validation.Validator validator = factory.getValidator();
            assertTrue(validator.validate(account()).isEmpty());
            for (String username : Arrays.asList("", "a b", "a@b", "a\u00a0b", "123456789012345678901")) {
                AdminCreateUserDTO dto = account(); dto.setUsername(username);
                assertFalse(validator.validate(dto).isEmpty(), username);
            }
            for (String password : Arrays.asList("", "12345", "123456789012345678901")) {
                AdminCreateUserDTO dto = account(); dto.setPassword(password);
                assertFalse(validator.validate(dto).isEmpty());
            }
            AdminCreateUserDTO dto = account(); dto.setRealname(new String(new char[51]).replace('\0', '名'));
            assertFalse(validator.validate(dto).isEmpty());
        }
    }

    @Test void concurrentDuplicateInsertReturnsAFriendlyErrorWithoutDatabaseDetails() throws Exception {
        AdminUserManager duplicate = mock(AdminUserManager.class);
        doThrow(new DuplicateKeyException("private SQL details")).when(duplicate).createUser(any());
        AdminUserServiceImpl service = new AdminUserServiceImpl();
        ReflectionTestUtils.setField(service, "adminUserManager", duplicate);
        assertNotEquals(200, service.createUser(account()).getStatus());
        assertEquals("用户名已存在，请更换用户名。", service.createUser(account()).getMsg());
        assertNull(service.createUser(account()).getData());
    }
}
