package com.bond.transfer.service;

import com.bond.common.entity.Workspace;
import com.bond.common.entity.WorkspaceMember;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.entitlement.EntitlementResolver;
import com.bond.transfer.entitlement.Entitlements;
import com.bond.transfer.entitlement.PlanLimits;
import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.mapper.UserMapper;
import com.bond.transfer.mapper.WorkspaceFileMapper;
import com.bond.transfer.mapper.WorkspaceInvitationMapper;
import com.bond.transfer.mapper.WorkspaceMapper;
import com.bond.transfer.mapper.WorkspaceMemberMapper;
import com.bond.transfer.storage.ObjectStorage;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task-25 A/B：配额原子性 + 工作区分片上传参数校验。
 *
 * <p>并发用例用 AtomicLong 模拟 PG 的 {@code UPDATE ... WHERE used_size + ? <= max_size}
 * 单语句语义：读-改-写窗口内其它线程的写入必须让后到者失败。</p>
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceQuotaAtomicTest {

    private static final long WORKSPACE_ID = 500L;
    private static final long USER_ID = 7L;

    @Mock
    private WorkspaceMapper workspaceMapper;

    @Mock
    private WorkspaceMemberMapper memberMapper;

    @Mock
    private WorkspaceFileMapper fileMapper;

    @Mock
    private WorkspaceInvitationMapper invitationMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ObjectStorage objectStorage;

    @Mock
    private EntitlementResolver entitlementResolver;

    @Mock
    private UsageService usageService;

    @Mock
    private ConfigService configService;

    @InjectMocks
    private WorkspaceService workspaceService;

    private static Entitlements planWith(long maxFileSize) {
        return new Entitlements("free", new PlanLimits(
                10L * 1024 * 1024 * 1024,
                100L,
                maxFileSize,
                2, 1, 3,
                0L, 7, 3, 30, 5, 30), null);
    }

    private Workspace workspace(long maxSize) {
        Workspace ws = new Workspace();
        ws.setId(WORKSPACE_ID);
        ws.setOwnerId(USER_ID);
        ws.setName("team");
        ws.setType("team");
        ws.setMaxSize(maxSize);
        ws.setUsedSize(0L);
        return ws;
    }

    private void stubMembership() {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(WORKSPACE_ID);
        member.setUserId(USER_ID);
        member.setRole(1);
        when(memberMapper.selectOne(any())).thenReturn(member);
        when(fileMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("并发两次上传同额度：原子占位下恰好只成功一次，另一次 413")
    void concurrentUploadsOnlyOneSucceeds() throws Exception {
        long quota = 100L;
        long fileSize = 60L;
        when(workspaceMapper.selectById(WORKSPACE_ID)).thenReturn(workspace(quota));
        when(entitlementResolver.resolve(USER_ID)).thenReturn(planWith(1024L));
        stubMembership();

        // 模拟 PG 原子 UPDATE：compareAndSet 循环等价于单语句的互斥判定
        AtomicLong used = new AtomicLong(0);
        when(workspaceMapper.reserveUsedSize(eq(WORKSPACE_ID), anyLong())).thenAnswer(invocation -> {
            long delta = invocation.getArgument(1);
            while (true) {
                long current = used.get();
                if (current + delta > quota) {
                    return 0;
                }
                if (used.compareAndSet(current, current + delta)) {
                    return 1;
                }
            }
        });
        // 让成功者的对象写入停留一会，确保两次上传的占位窗口重叠
        doAnswer(invocation -> {
            Thread.sleep(150L);
            return null;
        }).when(objectStorage).putObject(anyString(), any(InputStream.class), anyLong(), any());

        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = pool.submit(() -> upload(fileSize, start, done));
            Future<Throwable> second = pool.submit(() -> upload(fileSize, start, done));
            Throwable e1 = first.get(10, TimeUnit.SECONDS);
            Throwable e2 = second.get(10, TimeUnit.SECONDS);
            assertTrue(done.await(10, TimeUnit.SECONDS));

            int failures = (e1 == null ? 0 : 1) + (e2 == null ? 0 : 1);
            assertEquals(1, failures, "并发上传必须恰好失败一次");
            Throwable failure = e1 != null ? e1 : e2;
            assertTrue(failure instanceof BusinessException);
            assertEquals(413, ((BusinessException) failure).getCode());
            assertEquals(fileSize, used.get(), "最多只能占住一次空间");
            verify(objectStorage, times(1)).putObject(anyString(), any(InputStream.class), anyLong(), any());
        } finally {
            pool.shutdownNow();
        }
    }

    private Throwable upload(long fileSize, CyclicBarrier start, CountDownLatch done) {
        try {
            start.await(5, TimeUnit.SECONDS);
            MemoryMultipartFile file = new MemoryMultipartFile(
                    "file", "a.bin", "application/octet-stream", new byte[(int) fileSize]);
            workspaceService.uploadFile(WORKSPACE_ID, USER_ID, "/", file, null);
            return null;
        } catch (Throwable t) {
            return t;
        } finally {
            done.countDown();
        }
    }

    @Test
    @DisplayName("顺序上传占用后超额：第二次请求 413 且 used_size 不突破上限")
    void sequentialOverQuotaRejectedWith413() throws Exception {
        when(workspaceMapper.selectById(WORKSPACE_ID)).thenReturn(workspace(100L));
        when(entitlementResolver.resolve(USER_ID)).thenReturn(planWith(1024L));
        stubMembership();
        AtomicLong used = new AtomicLong(0);
        when(workspaceMapper.reserveUsedSize(eq(WORKSPACE_ID), anyLong())).thenAnswer(invocation -> {
            long delta = invocation.getArgument(1);
            long current = used.get();
            if (current + delta > 100L) {
                return 0;
            }
            used.set(current + delta);
            return 1;
        });

        workspaceService.uploadFile(WORKSPACE_ID, USER_ID, "/",
                new MemoryMultipartFile("file", "a.bin", "application/octet-stream", new byte[60]), null);

        BusinessException ex = assertThrows(BusinessException.class, () -> workspaceService.uploadFile(
                WORKSPACE_ID, USER_ID, "/",
                new MemoryMultipartFile("file", "b.bin", "application/octet-stream", new byte[60]), null));

        assertEquals(413, ex.getCode());
        assertEquals(60L, used.get());
    }

    @Test
    @DisplayName("占位 SQL 必须是单条条件 UPDATE（并发安全），不是先查后改")
    void reserveSqlIsSingleConditionalUpdate() throws Exception {
        Method method = WorkspaceMapper.class.getMethod("reserveUsedSize", Long.class, long.class);
        Update update = method.getAnnotation(Update.class);
        assertNotNull(update, "reserveUsedSize 必须是 @Update 单语句");
        String sql = String.join(" ", update.value()).replaceAll("\\s+", " ");
        assertTrue(sql.contains("used_size + #{delta} <= max_size"), "SQL 必须带原子上限条件: " + sql);
        assertFalse(sql.toLowerCase().contains("select"), "不允许在 SQL 内先查后改: " + sql);
    }

    @Test
    @DisplayName("init 阶段快速拒绝：文件大小非正 / 分片过大 / 分片数过多")
    void rejectsInvalidChunkParameters() {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(WORKSPACE_ID);
        member.setUserId(USER_ID);
        when(memberMapper.selectOne(any())).thenReturn(member);

        BusinessException zero = assertThrows(BusinessException.class,
                () -> workspaceService.initChunkUpload(WORKSPACE_ID, USER_ID, "a.bin", 0L,
                        "/", null, "application/octet-stream", 5, null));
        assertEquals(400, zero.getCode());

        // 分片大小上限：配置默认 64MB，这里桩成 1024 以便用小值验证
        when(configService.getLong("transfer.max_chunk_size", 64L * 1024 * 1024)).thenReturn(1024L);
        when(configService.getLong("transfer.max_chunks", 200_000L)).thenReturn(5L);
        BusinessException tooBig = assertThrows(BusinessException.class,
                () -> workspaceService.initChunkUpload(WORKSPACE_ID, USER_ID, "a.bin", 100L,
                        "/", null, "application/octet-stream", 2048, null));
        assertEquals(400, tooBig.getCode());

        BusinessException tooMany = assertThrows(BusinessException.class,
                () -> workspaceService.initChunkUpload(WORKSPACE_ID, USER_ID, "a.bin", 100L,
                        "/", null, "application/octet-stream", 10, null));
        assertEquals(400, tooMany.getCode());
    }

    @Test
    @DisplayName("声明大小超过档位 max_file_size：init 直接拒绝")
    void rejectsDeclaredSizeAbovePlanLimit() {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(WORKSPACE_ID);
        member.setUserId(USER_ID);
        when(memberMapper.selectOne(any())).thenReturn(member);
        when(configService.getLong("transfer.max_chunk_size", 64L * 1024 * 1024)).thenReturn(64L * 1024 * 1024);
        when(configService.getLong("transfer.max_chunks", 200_000L)).thenReturn(200_000L);
        when(entitlementResolver.resolve(USER_ID)).thenReturn(planWith(1024L));

        BusinessException abovePlan = assertThrows(BusinessException.class,
                () -> workspaceService.initChunkUpload(WORKSPACE_ID, USER_ID, "a.bin", 2048L,
                        "/", null, "application/octet-stream", 512, null));
        assertEquals(400, abovePlan.getCode());
    }

    @Test
    @DisplayName("初始化后字节数不符：complete 拒绝并清理会话（不可继续使用）")
    void completeCleansUpWhenBytesMismatch() throws Exception {
        when(workspaceMapper.selectById(WORKSPACE_ID)).thenReturn(workspace(100L));
        when(entitlementResolver.resolve(USER_ID)).thenReturn(planWith(1024L));
        stubMembership();
        when(configService.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));

        String uploadId = workspaceService.initChunkUpload(WORKSPACE_ID, USER_ID, "a.bin", 10L,
                "/", null, "application/octet-stream", 5, null);
        assertNotNull(uploadId);
        workspaceService.uploadChunk(WORKSPACE_ID, USER_ID, uploadId, 0,
                new java.io.ByteArrayInputStream(new byte[5]), 5L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workspaceService.completeChunkUpload(WORKSPACE_ID, USER_ID, uploadId));
        assertEquals(400, ex.getCode());

        BusinessException gone = assertThrows(BusinessException.class,
                () -> workspaceService.uploadChunk(WORKSPACE_ID, USER_ID, uploadId, 1,
                        new java.io.ByteArrayInputStream(new byte[5]), 5L));
        assertEquals(404, gone.getCode());
        verify(objectStorage, never()).putObject(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    /** 测试用内存 MultipartFile（bond-transfer 测试类路径没有 spring-test 的 MockMultipartFile）。 */
    private static final class MemoryMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] bytes;

        private MemoryMultipartFile(String name, String originalFilename, String contentType, byte[] bytes) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.bytes = bytes;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), bytes);
        }
    }
}
