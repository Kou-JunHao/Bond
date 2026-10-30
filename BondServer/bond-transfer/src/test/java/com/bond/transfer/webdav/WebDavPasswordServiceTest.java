package com.bond.transfer.webdav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bond.common.dto.WebDavPasswordCreatedDTO;
import com.bond.common.dto.WebDavPasswordDTO;
import com.bond.common.entity.WebDavPassword;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.WebDavPasswordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class WebDavPasswordServiceTest {

    @Mock
    private WebDavPasswordMapper mapper;

    @Mock
    private WebDavAuthService authService;

    private WebDavPasswordService service;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        service = new WebDavPasswordService(mapper, authService);
    }

    @Test
    void createReturnsOneTimePlaintextAndStoresOnlyBcrypt() {
        when(mapper.selectCount(any())).thenReturn(0L);

        WebDavPasswordCreatedDTO created = service.create(7L, "  Windows 笔记本  ");

        assertEquals("Windows 笔记本", created.getName());
        assertNotNull(created.getId());
        assertNotNull(created.getPassword());
        // 24 位 Bond-XXXX-XXXX-XXXX-XXXX（base32，去掉易混的 0/O/1/I）
        assertEquals(WebDavPasswordService.PLAINTEXT_LENGTH, created.getPassword().length());
        assertEquals(24, created.getPassword().length());
        assertTrue(created.getPassword().matches(
                "Bond-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}"));
        assertFalse(created.getPassword().matches(".*[0O1I].*"));

        ArgumentCaptor<WebDavPassword> captor = ArgumentCaptor.forClass(WebDavPassword.class);
        verify(mapper).insert(captor.capture());
        WebDavPassword row = captor.getValue();
        assertEquals(7L, row.getUserId());
        assertEquals("Windows 笔记本", row.getName());
        assertNotEquals(created.getPassword(), row.getPasswordHash());
        assertTrue(row.getPasswordHash().startsWith("$2"));
        assertTrue(encoder.matches(created.getPassword(), row.getPasswordHash()));
        assertNull(row.getRevokedAt());
    }

    @Test
    void listNeverExposesHash() throws Exception {
        WebDavPassword row = new WebDavPassword();
        row.setId(11L);
        row.setUserId(7L);
        row.setName("laptop");
        row.setPasswordHash(encoder.encode("top-secret-hash"));
        row.setCreatedAt(OffsetDateTime.now());
        row.setLastUsedAt(OffsetDateTime.now());
        when(mapper.selectList(any())).thenReturn(List.of(row));

        List<WebDavPasswordDTO> list = service.list(7L);

        assertEquals(1, list.size());
        assertEquals("laptop", list.get(0).getName());
        assertNotNull(list.get(0).getCreatedAt());
        assertNotNull(list.get(0).getLastUsedAt());
        assertFalse(list.get(0).isRevoked());
        String json = new ObjectMapper().writeValueAsString(list);
        assertFalse(json.contains("top-secret-hash"));
        assertFalse(json.toLowerCase().contains("passwordhash"));
    }

    @Test
    void revokeMarksRowAndEvictsAuthCache() {
        WebDavPassword row = new WebDavPassword();
        row.setId(11L);
        row.setUserId(7L);
        row.setName("laptop");
        when(mapper.selectById(11L)).thenReturn(row);
        when(mapper.updateById(any(WebDavPassword.class))).thenReturn(1);

        service.revoke(7L, 11L);

        ArgumentCaptor<WebDavPassword> captor = ArgumentCaptor.forClass(WebDavPassword.class);
        verify(mapper).updateById(captor.capture());
        assertNotNull(captor.getValue().getRevokedAt());
        verify(authService).invalidateUser(7L);
    }

    @Test
    void revokeIsIdempotentAndStillInvalidatesCache() {
        WebDavPassword row = new WebDavPassword();
        row.setId(11L);
        row.setUserId(7L);
        row.setRevokedAt(OffsetDateTime.now().minusDays(1));
        when(mapper.selectById(11L)).thenReturn(row);

        service.revoke(7L, 11L);

        verify(mapper, never()).updateById(any(WebDavPassword.class));
        verify(authService).invalidateUser(7L);
    }

    @Test
    void revokeOtherUsersPasswordIs404() {
        WebDavPassword row = new WebDavPassword();
        row.setId(11L);
        row.setUserId(8L);
        when(mapper.selectById(11L)).thenReturn(row);

        BusinessException error = assertThrows(BusinessException.class, () -> service.revoke(7L, 11L));
        assertEquals(404, error.getCode());
        verify(authService, never()).invalidateUser(any());
    }

    @Test
    void createRejectsBlankOrOverlongName() {
        BusinessException blank = assertThrows(BusinessException.class, () -> service.create(7L, "  "));
        assertEquals(400, blank.getCode());

        String tooLong = "x".repeat(WebDavPasswordService.NAME_MAX_LENGTH + 1);
        BusinessException overlong = assertThrows(BusinessException.class, () -> service.create(7L, tooLong));
        assertEquals(400, overlong.getCode());
    }

    @Test
    void createStopsAtActivePasswordLimit() {
        when(mapper.selectCount(any())).thenReturn((long) WebDavPasswordService.MAX_ACTIVE_PER_USER);

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(7L, "one-too-many"));
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("上限"));
    }

    @Test
    void elevenActivePasswordsAreRejected() {
        // 用户在已有 10 个的基础上再建第 11 个（生产语义）：计数 >= 10 直接 400
        when(mapper.selectCount(any())).thenReturn(11L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(7L, "eleventh"));
        assertEquals(400, error.getCode());
    }
}
