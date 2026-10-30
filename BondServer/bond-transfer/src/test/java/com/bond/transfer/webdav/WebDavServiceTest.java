package com.bond.transfer.webdav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bond.common.dto.WorkspaceFileDTO;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class WebDavServiceTest {

    private static final long USER_ID = 1L;
    private static final long WORKSPACE_ID = 10L;

    @Mock
    private WebDavAuthService authService;
    @Mock
    private PersonalSpaceService personalSpaceService;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private UsageService usageService;

    private final InMemoryWebDavLockStore lockStore = new InMemoryWebDavLockStore();
    private final WebDavLocks locks = new WebDavLocks(lockStore);
    private WebDavService service;

    @BeforeEach
    void setUp() {
        service = new WebDavService(authService, locks, personalSpaceService, workspaceService, usageService);
        lenient().when(authService.authenticate(any())).thenReturn(USER_ID);
        lenient().when(personalSpaceService.resolveWorkspaceId(USER_ID)).thenReturn(WORKSPACE_ID);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private static WorkspaceFile file(long id, String name, long size) {
        WorkspaceFile file = new WorkspaceFile();
        file.setId(id);
        file.setWorkspaceId(WORKSPACE_ID);
        file.setFileName(name);
        file.setParentPath("/");
        file.setFileSize(size);
        file.setIsDirectory(false);
        file.setContentType("text/plain");
        file.setFileHash("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
        file.setCreatedAt(LocalDateTime.of(2026, 10, 23, 0, 0));
        return file;
    }

    private static WorkspaceFileDTO child(String name, boolean directory, long size) {
        WorkspaceFileDTO dto = new WorkspaceFileDTO();
        dto.setId(99L);
        dto.setWorkspaceId(WORKSPACE_ID);
        dto.setFileName(name);
        dto.setIsDirectory(directory);
        dto.setFileSize(size);
        dto.setContentType("text/plain");
        dto.setCreatedAt(1_700_000_000_000L);
        return dto;
    }

    // ------------------------------------------------------------ OPTIONS

    @Test
    void optionsAdvertisesDavCapabilities() {
        WebDavResponse response = service.handle(request("OPTIONS", "/dav/"));

        assertEquals(200, response.getStatus());
        assertEquals("1,2", response.getHeaders().get("DAV"));
        assertTrue(response.getHeaders().get("Allow").contains("PROPFIND"));
        verify(authService).ensureAvailable(any());
        verify(authService, never()).authenticate(any());
    }

    // ------------------------------------------------------------ PROPFIND

    @Test
    void propFindRootDepthZeroReturnsOnlyMount() {
        MockHttpServletRequest request = request("PROPFIND", "/dav/");
        request.addHeader("Depth", "0");

        WebDavResponse response = service.handle(request);

        assertEquals(207, response.getStatus());
        String xml = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(xml.contains("<D:href>/dav/</D:href>"), xml);
        verify(workspaceService, never()).listFiles(anyLong(), anyLong(), anyString());
    }

    @Test
    void propFindDepthOneListsChildren() {
        when(workspaceService.listFiles(WORKSPACE_ID, USER_ID, "/"))
                .thenReturn(List.of(child("docs", true, 0L), child("a.txt", false, 3L)));

        WebDavResponse response = service.handle(request("PROPFIND", "/dav/"));

        assertEquals(207, response.getStatus());
        String xml = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(xml.contains("<D:href>/dav/docs/</D:href>"), xml);
        assertTrue(xml.contains("<D:href>/dav/a.txt</D:href>"), xml);
        assertTrue(xml.contains("<D:collection/>"), xml);
    }

    @Test
    void propFindMissingEntryIs404() {
        WebDavException error = assertThrows(WebDavException.class,
                () -> service.handle(request("PROPFIND", "/dav/missing.txt")));
        assertEquals(404, error.getStatus());
    }

    @Test
    void propFindDepthInfinityIs403() {
        MockHttpServletRequest request = request("PROPFIND", "/dav/");
        request.addHeader("Depth", "infinity");

        WebDavException error = assertThrows(WebDavException.class, () -> service.handle(request));
        assertEquals(403, error.getStatus());
    }

    // ------------------------------------------------------------ GET / HEAD

    @Test
    void getReturnsStreamWithHeaders() throws Exception {
        WorkspaceFile file = file(5L, "a.txt", 10L);
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "a.txt")).thenReturn(file);
        when(workspaceService.downloadFile(WORKSPACE_ID, USER_ID, 5L))
                .thenReturn(new WorkspaceService.DownloadResult(new ByteArrayInputStream(new byte[10]), 10L));

        WebDavResponse response = service.handle(request("GET", "/dav/a.txt"));

        assertEquals(200, response.getStatus());
        assertEquals("10", response.getHeaders().get("Content-Length"));
        assertEquals("\"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08\"",
                response.getHeaders().get("ETag"));
        assertEquals("bytes", response.getHeaders().get("Accept-Ranges"));
        assertNotNull(response.getStream());
    }

    @Test
    void headReturnsHeadersWithoutStream() throws Exception {
        WorkspaceFile file = file(5L, "a.txt", 10L);
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "a.txt")).thenReturn(file);

        WebDavResponse response = service.handle(request("HEAD", "/dav/a.txt"));

        assertEquals(200, response.getStatus());
        assertNull(response.getStream());
        verify(workspaceService, never()).downloadFile(anyLong(), anyLong(), anyLong());
    }

    @Test
    void getWithRangeReturns206() throws Exception {
        WorkspaceFile file = file(5L, "a.txt", 10L);
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "a.txt")).thenReturn(file);
        when(workspaceService.openFileContentStream(any(), anyLong(), anyLong()))
                .thenReturn(new ByteArrayInputStream(new byte[5]));
        lenient().when(usageService.meterAndThrottleDownload(eq(USER_ID), any(InputStream.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        MockHttpServletRequest request = request("GET", "/dav/a.txt");
        request.addHeader("Range", "bytes=0-4");
        WebDavResponse response = service.handle(request);

        assertEquals(206, response.getStatus());
        assertEquals("bytes 0-4/10", response.getHeaders().get("Content-Range"));
        assertEquals("5", response.getHeaders().get("Content-Length"));
    }

    // ------------------------------------------------------------ PUT

    @Test
    void putWithoutContentLengthIs411() {
        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("PUT");
        when(request.getRequestURI()).thenReturn("/dav/x.txt");
        when(request.getContextPath()).thenReturn("");
        when(request.getContentLengthLong()).thenReturn(-1L);

        WebDavException error = assertThrows(WebDavException.class, () -> service.handle(request));
        assertEquals(411, error.getStatus());
    }

    @Test
    void putCreatesFileWith201() throws Exception {
        byte[] body = "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockHttpServletRequest request = request("PUT", "/dav/new.txt");
        request.setContent(body);
        request.setContentType("text/plain");
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "new.txt")).thenReturn(null);
        when(workspaceService.uploadStream(eq(WORKSPACE_ID), eq(USER_ID), eq("/"), eq("new.txt"),
                any(InputStream.class), eq(3L), eq("text/plain")))
                .thenReturn(child("new.txt", false, 3L));

        WebDavResponse response = service.handle(request);

        assertEquals(201, response.getStatus());
        verify(workspaceService).uploadStream(eq(WORKSPACE_ID), eq(USER_ID), eq("/"), eq("new.txt"),
                any(InputStream.class), eq(3L), eq("text/plain"));
    }

    @Test
    void putOverwritesExistingFileWith204() throws Exception {
        MockHttpServletRequest request = request("PUT", "/dav/a.txt");
        request.setContent("abcd".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "a.txt")).thenReturn(file(5L, "a.txt", 3L));
        when(workspaceService.uploadStream(eq(WORKSPACE_ID), eq(USER_ID), eq("/"), eq("a.txt"),
                any(InputStream.class), eq(4L), anyString()))
                .thenReturn(child("a.txt", false, 4L));

        WebDavResponse response = service.handle(request);

        assertEquals(204, response.getStatus());
        verify(workspaceService).deleteFile(WORKSPACE_ID, USER_ID, 5L);
    }

    @Test
    void putQuotaExhaustedMapsTo507() throws Exception {
        MockHttpServletRequest request = request("PUT", "/dav/big.bin");
        request.setContent(new byte[]{1, 2, 3});
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "big.bin")).thenReturn(null);
        doThrow(new BusinessException(413, "空间不足，当前配额无法容纳该文件"))
                .when(workspaceService).uploadStream(anyLong(), anyLong(), anyString(), anyString(),
                        any(InputStream.class), anyLong(), anyString());

        WebDavException error = assertThrows(WebDavException.class, () -> service.handle(request));
        assertEquals(507, error.getStatus());
    }

    @Test
    void putPlanLimitMapsTo413() throws Exception {
        MockHttpServletRequest request = request("PUT", "/dav/huge.bin");
        request.setContent(new byte[]{1, 2, 3});
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "huge.bin")).thenReturn(null);
        doThrow(new BusinessException(400, "file exceeds plan max limit(2GB)"))
                .when(workspaceService).uploadStream(anyLong(), anyLong(), anyString(), anyString(),
                        any(InputStream.class), anyLong(), anyString());

        WebDavException error = assertThrows(WebDavException.class, () -> service.handle(request));
        assertEquals(413, error.getStatus());
    }

    // ------------------------------------------------------------ MKCOL / DELETE

    @Test
    void mkcolCreatesCollection() {
        when(workspaceService.createDirectory(WORKSPACE_ID, USER_ID, "/", "docs"))
                .thenReturn(child("docs", true, 0L));

        WebDavResponse response = service.handle(request("MKCOL", "/dav/docs"));

        assertEquals(201, response.getStatus());
    }

    @Test
    void mkcolMissingParentIs409() {
        WebDavException error = assertThrows(WebDavException.class,
                () -> service.handle(request("MKCOL", "/dav/missing/docs")));
        assertEquals(409, error.getStatus());
        verify(workspaceService, never()).createDirectory(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void deleteSoftDeletesEntry() {
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "a.txt")).thenReturn(file(5L, "a.txt", 3L));

        WebDavResponse response = service.handle(request("DELETE", "/dav/a.txt"));

        assertEquals(204, response.getStatus());
        verify(workspaceService).deleteFile(WORKSPACE_ID, USER_ID, 5L);
    }

    // ------------------------------------------------------------ MOVE / COPY

    @Test
    void moveToAnotherMountIs403() {
        MockHttpServletRequest request = request("MOVE", "/dav/a.txt");
        request.addHeader("Destination", "http://127.0.0.1:8082/dav/workspaces/9/b.txt");

        WebDavException error = assertThrows(WebDavException.class, () -> service.handle(request));
        assertEquals(403, error.getStatus());
    }

    @Test
    void moveRenamesEntryWith201() {
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "a.txt")).thenReturn(file(5L, "a.txt", 3L));
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "b.txt")).thenReturn(null);

        MockHttpServletRequest request = request("MOVE", "/dav/a.txt");
        request.addHeader("Destination", "/dav/b.txt");
        WebDavResponse response = service.handle(request);

        assertEquals(201, response.getStatus());
        verify(workspaceService).moveEntry(WORKSPACE_ID, USER_ID, 5L, "/", "b.txt");
    }

    @Test
    void copyWithOverwriteFalseAndExistingTargetIs412() {
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "a.txt")).thenReturn(file(5L, "a.txt", 3L));
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "b.txt")).thenReturn(file(6L, "b.txt", 1L));

        MockHttpServletRequest request = request("COPY", "/dav/a.txt");
        request.addHeader("Destination", "/dav/b.txt");
        request.addHeader("Overwrite", "F");
        WebDavException error = assertThrows(WebDavException.class, () -> service.handle(request));

        assertEquals(412, error.getStatus());
        verify(workspaceService, never()).copyEntry(anyLong(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void copyCreatesWith201() {
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "a.txt")).thenReturn(file(5L, "a.txt", 3L));
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "b.txt")).thenReturn(null);

        MockHttpServletRequest request = request("COPY", "/dav/a.txt");
        request.addHeader("Destination", "/dav/b.txt");
        WebDavResponse response = service.handle(request);

        assertEquals(201, response.getStatus());
        verify(workspaceService).copyEntry(WORKSPACE_ID, USER_ID, 5L, "/", "b.txt");
    }

    // ------------------------------------------------------------ LOCK / UNLOCK

    @Test
    void lockReturnsToken() {
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "a.txt")).thenReturn(file(5L, "a.txt", 3L));

        WebDavResponse response = service.handle(request("LOCK", "/dav/a.txt"));

        assertEquals(200, response.getStatus());
        assertNotNull(response.getHeaders().get("Lock-Token"));
        assertTrue(response.getHeaders().get("Lock-Token").startsWith("<opaquelocktoken:"));
        assertEquals("Second-1800", response.getHeaders().get("Timeout"));
        assertTrue(locks.hasLock("10:/a.txt"));
    }

    @Test
    void lockHonorsRequestedTimeout() {
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "a.txt")).thenReturn(file(5L, "a.txt", 3L));
        MockHttpServletRequest request = request("LOCK", "/dav/a.txt");
        request.addHeader("Timeout", "Second-600");

        WebDavResponse response = service.handle(request);

        assertEquals(200, response.getStatus());
        assertEquals("Second-600", response.getHeaders().get("Timeout"));
    }

    @Test
    void secondLockOnSameResourceIs423() {
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "a.txt")).thenReturn(file(5L, "a.txt", 3L));
        locks.lock("10:/a.txt", "", "0", 1800);

        WebDavException error = assertThrows(WebDavException.class,
                () -> service.handle(request("LOCK", "/dav/a.txt")));
        assertEquals(423, error.getStatus());
    }

    @Test
    void putOnLockedFileWithoutTokenIs423() throws Exception {
        locks.lock("10:/a.txt", "", "0", 1800);
        MockHttpServletRequest request = request("PUT", "/dav/a.txt");
        request.setContent("abcd".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        WebDavException error = assertThrows(WebDavException.class, () -> service.handle(request));

        assertEquals(423, error.getStatus());
        verify(workspaceService, never()).uploadStream(anyLong(), anyLong(), anyString(), anyString(),
                any(InputStream.class), anyLong(), anyString());
    }

    @Test
    void putOnLockedFileWithMatchingIfTokenSucceeds() throws Exception {
        when(workspaceService.findActiveEntry(WORKSPACE_ID, "/", "a.txt")).thenReturn(file(5L, "a.txt", 3L));
        when(workspaceService.uploadStream(eq(WORKSPACE_ID), eq(USER_ID), eq("/"), eq("a.txt"),
                any(InputStream.class), eq(4L), anyString()))
                .thenReturn(child("a.txt", false, 4L));
        WebDavLocks.LockInfo lock = locks.lock("10:/a.txt", "", "0", 1800);
        MockHttpServletRequest request = request("PUT", "/dav/a.txt");
        request.setContent("abcd".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.addHeader("If", "(<" + lock.token() + ">)");

        WebDavResponse response = service.handle(request);

        assertEquals(204, response.getStatus());
    }

    @Test
    void propFindIncludesLockDiscoveryForLockedChild() {
        when(workspaceService.listFiles(WORKSPACE_ID, USER_ID, "/"))
                .thenReturn(List.of(child("a.txt", false, 3L)));
        locks.lock("10:/a.txt", "", "0", 1800);

        WebDavResponse response = service.handle(request("PROPFIND", "/dav/"));

        assertEquals(207, response.getStatus());
        String xml = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(xml.contains("<D:lockdiscovery>"), xml);
        assertTrue(xml.contains("opaquelocktoken:"), xml);
    }

    @Test
    void unlockWithMatchingTokenIs204() {
        WebDavLocks.LockInfo lock = locks.lock("10:/a.txt", "", "0", 1800);
        MockHttpServletRequest request = request("UNLOCK", "/dav/a.txt");
        request.addHeader("Lock-Token", "<" + lock.token() + ">");

        WebDavResponse response = service.handle(request);

        assertEquals(204, response.getStatus());
        assertEquals(false, locks.hasLock("10:/a.txt"));
    }

    @Test
    void unlockWithWrongTokenIs409() {
        locks.lock("10:/a.txt", "", "0", 1800);
        MockHttpServletRequest request = request("UNLOCK", "/dav/a.txt");
        request.addHeader("Lock-Token", "<opaquelocktoken:wrong>");

        WebDavException error = assertThrows(WebDavException.class, () -> service.handle(request));
        assertEquals(409, error.getStatus());
    }

    // ------------------------------------------------------------ disabled switch

    @Test
    void disabledSwitchReturns404ForAllMethods() {
        doThrow(new WebDavException(404, "WebDAV is disabled"))
                .when(authService).ensureAvailable(any());

        WebDavException error = assertThrows(WebDavException.class,
                () -> service.handle(request("PROPFIND", "/dav/")));
        assertEquals(404, error.getStatus());
    }
}
