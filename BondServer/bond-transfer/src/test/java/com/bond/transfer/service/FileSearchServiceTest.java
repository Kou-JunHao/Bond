package com.bond.transfer.service;

import com.bond.common.dto.FileSearchItem;
import com.bond.common.dto.FileSearchResult;
import com.bond.common.entity.WorkspaceFile;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * task-117：文件搜索服务契约测试（无容器、无真实 DB）。
 */
@ExtendWith(MockitoExtension.class)
class FileSearchServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long PERSONAL_ID = 101L;

    @Mock
    private WorkspaceFileMapper fileMapper;
    @Mock
    private WorkspaceMemberMapper memberMapper;
    @Mock
    private PersonalSpaceService personalSpaceService;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private com.bond.transfer.acl.WorkspaceAclService workspaceAclService;

    @InjectMocks
    private FileSearchService service;

    private void stubPersonal() {
        when(personalSpaceService.resolveWorkspaceId(USER_ID)).thenReturn(PERSONAL_ID);
        when(memberMapper.selectList(any())).thenReturn(List.of());
    }

    private static WorkspaceFile file(long id, String workspaceName, String name, String parentPath,
                                      boolean directory, OffsetDateTime updatedAt) {
        WorkspaceFile f = new WorkspaceFile();
        f.setId(id);
        f.setWorkspaceId(workspaceName == null ? null : 1L);
        f.setFileName(name);
        f.setParentPath(parentPath);
        f.setIsDirectory(directory);
        f.setFileSize(directory ? 0L : 12L);
        f.setContentType(directory ? "httpd/unix-directory" : "text/plain");
        f.setUpdatedAt(updatedAt);
        return f;
    }

    @Test
    @DisplayName("q 为空/空白 → 400")
    void rejectsBlankQuery() {
        assertThrows(BusinessException.class, () -> service.search(USER_ID, null, null, null, null));
        assertThrows(BusinessException.class, () -> service.search(USER_ID, "   ", null, null, null));
    }

    @Test
    @DisplayName("q 超过 100 字符 → 400")
    void rejectsTooLongQuery() {
        String longQuery = "x".repeat(101);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.search(USER_ID, longQuery, null, null, null));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("关键词走参数绑定：% _ \\\\ 被转义成 ILIKE 字面量")
    void escapesLikeWildcards() {
        stubPersonal();
        when(fileMapper.countSearch(anyList(), anyString())).thenReturn(0L);
        when(fileMapper.search(anyList(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.search(USER_ID, "100%_off\\x", null, null, null);

        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        verify(fileMapper).search(anyList(), pattern.capture(), prefix.capture(), any(), any(), anyInt());
        assertEquals("%100\\%\\_off\\\\x%", pattern.getValue());
        assertEquals("100\\%\\_off\\\\x%", prefix.getValue());
    }

    @Test
    @DisplayName("大小写原样传给 DB（ILIKE 负责不敏感），不做 Java 侧 lower-case")
    void keepsQueryCase() {
        stubPersonal();
        when(fileMapper.countSearch(anyList(), anyString())).thenReturn(0L);
        when(fileMapper.search(anyList(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.search(USER_ID, "RePorT", null, null, null);

        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(fileMapper).search(anyList(), pattern.capture(), anyString(), any(), any(), anyInt());
        assertEquals("%RePorT%", pattern.getValue());
    }

    @Test
    @DisplayName("不传 workspaceId：搜索范围 = 个人空间 + 我是成员的工作区（去重）")
    void scopesToPersonalAndMemberships() {
        when(personalSpaceService.resolveWorkspaceId(USER_ID)).thenReturn(PERSONAL_ID);
        WorkspaceMember m1 = new WorkspaceMember();
        m1.setWorkspaceId(202L);
        WorkspaceMember m2 = new WorkspaceMember();
        m2.setWorkspaceId(PERSONAL_ID);
        when(memberMapper.selectList(any())).thenReturn(List.of(m1, m2));
        when(fileMapper.countSearch(anyList(), anyString())).thenReturn(0L);
        when(fileMapper.search(anyList(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.search(USER_ID, "abc", null, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(fileMapper).search(ids.capture(), anyString(), anyString(), any(), any(), anyInt());
        assertEquals(List.of(PERSONAL_ID, 202L), ids.getValue());
    }

    @Test
    @DisplayName("task-183B：restricted 成员的工作区从搜索范围中排除（不泄露未授权文件）")
    void excludesRestrictedWorkspaces() {
        when(personalSpaceService.resolveWorkspaceId(USER_ID)).thenReturn(PERSONAL_ID);
        WorkspaceMember restricted = new WorkspaceMember();
        restricted.setWorkspaceId(202L);
        WorkspaceMember normal = new WorkspaceMember();
        normal.setWorkspaceId(303L);
        when(memberMapper.selectList(any())).thenReturn(List.of(restricted, normal));
        when(workspaceAclService.restrictedWorkspaceIds(eq(USER_ID), anyCollection()))
                .thenReturn(java.util.Set.of(202L));
        when(fileMapper.countSearch(anyList(), anyString())).thenReturn(0L);
        when(fileMapper.search(anyList(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.search(USER_ID, "abc", null, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(fileMapper).search(ids.capture(), anyString(), anyString(), any(), any(), anyInt());
        assertEquals(List.of(PERSONAL_ID, 303L), ids.getValue());
    }

    @Test
    @DisplayName("task-183B：显式搜索受限工作区 → 返回空结果（不做 SQL 查询）")
    void restrictedWorkspaceSearchReturnsEmpty() {
        when(workspaceAclService.isRestricted(5L, USER_ID)).thenReturn(true);

        FileSearchResult result = service.search(USER_ID, "abc", 5L, null, null);

        assertTrue(result.items().isEmpty());
        assertEquals(0L, result.total());
        verify(workspaceService).ensureMember(5L, USER_ID);
        verify(fileMapper, never()).search(anyList(), anyString(), anyString(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("带 workspaceId：只搜该空间并校验成员；非成员抛 403 且不查库")
    void memberScopeRequiresMembership() {
        when(fileMapper.countSearch(anyList(), anyString())).thenReturn(0L);
        when(fileMapper.search(anyList(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.search(USER_ID, "abc", 5L, null, null);

        verify(workspaceService).ensureMember(5L, USER_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(fileMapper).search(ids.capture(), anyString(), anyString(), any(), any(), anyInt());
        assertEquals(List.of(5L), ids.getValue());

        // 非成员：ensureMember 抛 403 → 不查库
        reset(fileMapper, workspaceService);
        doThrow(new BusinessException(403, "不是该工作区成员"))
                .when(workspaceService).ensureMember(6L, USER_ID);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.search(USER_ID, "abc", 6L, null, null));
        assertEquals(403, ex.getCode());
        verifyNoInteractions(fileMapper);
    }

    @Test
    @DisplayName("目录也返回，size 可为 0，isDirectory=true")
    void includesDirectories() {
        stubPersonal();
        OffsetDateTime t = OffsetDateTime.of(2026, 10, 24, 12, 0, 0, 0, ZoneOffset.UTC);
        WorkspaceFile dir = file(11L, "p", "docs", "/a", true, t);
        when(fileMapper.countSearch(anyList(), anyString())).thenReturn(1L);
        when(fileMapper.search(anyList(), anyString(), anyString(), any(), any(), anyInt())).thenReturn(List.of(dir));

        FileSearchResult result = service.search(USER_ID, "doc", null, null, null);

        assertEquals(1, result.items().size());
        FileSearchItem item = result.items().get(0);
        assertTrue(item.isDirectory());
        assertEquals("docs", item.name());
        assertEquals("/a/docs", item.path());
        assertEquals("httpd/unix-directory", item.contentType());
        assertEquals(t.toInstant().toEpochMilli(), item.updatedAtMillis());
        assertEquals(1L, result.total());
    }

    @Test
    @DisplayName("名称前缀优先由 SQL 决定；服务只透传顺序（不重排）")
    void keepsSqlOrdering() {
        stubPersonal();
        OffsetDateTime t1 = OffsetDateTime.of(2026, 10, 24, 12, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime t2 = t1.minusMinutes(5);
        WorkspaceFile prefixHit = file(12L, "p", "report-q3.pdf", "/", false, t2);
        WorkspaceFile suffixHit = file(13L, "p", "old-report.txt", "/", false, t1);
        when(fileMapper.countSearch(anyList(), anyString())).thenReturn(2L);
        when(fileMapper.search(anyList(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(prefixHit, suffixHit));

        FileSearchResult result = service.search(USER_ID, "report", null, null, null);

        assertEquals(List.of("report-q3.pdf", "old-report.txt"),
                result.items().stream().map(FileSearchItem::name).toList());
    }

    @Test
    @DisplayName("limit：0/负数回默认 50；超 100 截到 100；fetch=limit+1")
    void clampsLimit() {
        stubPersonal();
        when(fileMapper.countSearch(anyList(), anyString())).thenReturn(0L);
        when(fileMapper.search(anyList(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.search(USER_ID, "a", null, 0, null);
        service.search(USER_ID, "a", null, 500, null);
        service.search(USER_ID, "a", null, 10, null);

        ArgumentCaptor<Integer> fetch = ArgumentCaptor.forClass(Integer.class);
        verify(fileMapper, times(3)).search(anyList(), anyString(), anyString(), any(), any(), fetch.capture());
        assertEquals(List.of(51, 101, 11), fetch.getAllValues());
    }

    @Test
    @DisplayName("keyset 分页：返回 nextCursor 并能在下一页传回 (updatedAt,id)")
    void keysetPagination() {
        stubPersonal();
        OffsetDateTime t3 = OffsetDateTime.of(2026, 10, 24, 12, 0, 0, 0, ZoneOffset.UTC);
        WorkspaceFile first = file(21L, "p", "a.txt", "/", false, t3);
        WorkspaceFile second = file(22L, "p", "b.txt", "/", false, t3.minusMinutes(1));
        WorkspaceFile third = file(23L, "p", "c.txt", "/", false, t3.minusMinutes(2));
        when(fileMapper.countSearch(anyList(), anyString())).thenReturn(3L);
        when(fileMapper.search(anyList(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(first, second, third));

        FileSearchResult firstPage = service.search(USER_ID, "x", null, 2, null);
        assertTrue(firstPage.hasMore());
        assertEquals(2, firstPage.items().size());
        assertNotNull(firstPage.nextCursor());
        assertEquals(3L, firstPage.total());

        String cursor = firstPage.nextCursor();
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        assertEquals(second.getUpdatedAt().toString() + "|22", decoded);

        service.search(USER_ID, "x", null, 2, cursor);

        ArgumentCaptor<OffsetDateTime> time = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<Long> id = ArgumentCaptor.forClass(Long.class);
        verify(fileMapper, times(2)).search(anyList(), anyString(), anyString(), time.capture(), id.capture(), anyInt());
        assertNull(time.getAllValues().get(0));
        assertEquals(second.getUpdatedAt(), time.getAllValues().get(1));
        assertEquals(22L, id.getAllValues().get(1));
    }

    @Test
    @DisplayName("非法 cursor → 400")
    void rejectsInvalidCursor() {
        stubPersonal();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.search(USER_ID, "abc", null, null, "not-base64!!"));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("软删过滤/ILIKE/keyset 直接写在 Mapper SQL 里（防回退成内存过滤）")
    void mapperSqlKeepsSoftDeleteFilterAndBoundParams() throws Exception {
        Select search = WorkspaceFileMapper.class
                .getMethod("search", List.class, String.class, String.class, OffsetDateTime.class, Long.class, int.class)
                .getAnnotation(Select.class);
        String sql = String.join("\n", search.value());
        assertTrue(sql.contains("deleted_at IS NULL"));
        assertTrue(sql.contains("ILIKE #{pattern}"));
        assertTrue(sql.contains("updated_at"));
        assertTrue(sql.contains("#{workspaceIds}") || sql.contains("<foreach"));
        assertTrue(sql.contains("LIMIT #{fetch}"));
        assertFalse(sql.toLowerCase().contains("offset"));
    }
}
