package com.bond.transfer.webdav;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bond.transfer.entitlement.UsageService;
import com.bond.transfer.service.PersonalSpaceService;
import com.bond.transfer.service.WorkspaceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * WebDAV 的 servlet 层回归测试（task-44）。
 *
 * <p>用 MockMvc 直接喂 WebDAV 方法名（PROPFIND/OPTIONS 等），验证：</p>
 * <ul>
 *   <li>{@code /dav} 映射能被 Spring MVC 分发（自定义方法不会被 405 掉）；</li>
 *   <li>响应是原始 WebDAV 响应（207 XML / 401+WWW-Authenticate），不会被统一 JSON 包装；</li>
 *   <li>OPTIONS 的 DAV/Allow 能力声明存在（Explorer/Finder 探测前提）。</li>
 * </ul>
 *
 * <p>说明：本地没有数据库/网关，这层测试不等于真实 HTTP 链路；真实 PROPFIND 由 lead 在生产用
 * {@code curl -X PROPFIND} 验收。</p>
 */
class WebDavHttpTest {

    private final WebDavAuthService authService = mock(WebDavAuthService.class);
    private final PersonalSpaceService personalSpaceService = mock(PersonalSpaceService.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final UsageService usageService = mock(UsageService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WebDavService service = new WebDavService(authService,
                new WebDavLocks(new InMemoryWebDavLockStore()), personalSpaceService,
                workspaceService, usageService);
        // OPTIONS 由 WebDavOptionsFilter 在 servlet 层直接应答（不依赖 MVC 的 OPTIONS 分发）。
        mockMvc = MockMvcBuilders.standaloneSetup(new WebDavController(service))
                .addFilters(new WebDavOptionsFilter(authService))
                .build();
        when(authService.authenticate(any())).thenReturn(1L);
        when(authService.isEnabled()).thenReturn(true);
        when(personalSpaceService.resolveWorkspaceId(1L)).thenReturn(10L);
        when(workspaceService.listFiles(eq(10L), eq(1L), eq("/"))).thenReturn(List.of());
    }

    @Test
    void optionsAdvertisesDavCapabilities() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf("OPTIONS"), "/dav/"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.header().string("DAV", "1,2"))
                .andExpect(MockMvcResultMatchers.header().string("Allow", containsString("PROPFIND")))
                .andExpect(MockMvcResultMatchers.header().string("MS-Author-Via", "DAV"));
    }

    @Test
    void optionsWhenDisabledReturns404() throws Exception {
        org.mockito.Mockito.doThrow(new WebDavException(404, "disabled"))
                .when(authService).ensureAvailable(any());
        mockMvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf("OPTIONS"), "/dav/"))
                .andExpect(MockMvcResultMatchers.status().isNotFound());
    }

    @Test
    void optionsOverPlainHttpReturns403() throws Exception {
        org.mockito.Mockito.doThrow(new WebDavException(403, "https required"))
                .when(authService).ensureAvailable(any());
        mockMvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf("OPTIONS"), "/dav/"))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void propfindRootReturnsMultiStatusXml() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .request(HttpMethod.valueOf("PROPFIND"), "/dav/")
                        .header("Depth", "0"))
                .andExpect(MockMvcResultMatchers.status().is(207))
                .andExpect(MockMvcResultMatchers.content().contentTypeCompatibleWith("application/xml"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("<D:href>"), "207 body must be WebDAV XML");
        assertTrue(body.contains("/dav/"), "207 body must contain the mount href");
        assertFalse(body.contains("\"code\""), "WebDAV responses must not be wrapped in the JSON envelope");
    }

    @Test
    void unauthenticatedRequestKeepsRaw401WithChallenge() throws Exception {
        when(authService.authenticate(any())).thenThrow(new WebDavException(401, "unauthorized")
                .header("WWW-Authenticate", "Basic realm=\"Bond\""));
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .request(HttpMethod.valueOf("PROPFIND"), "/dav/")
                        .header("Depth", "0"))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.header().string("WWW-Authenticate", containsString("Basic")))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("\"code\":401"),
                "401 must not be rewritten into the JSON envelope");
    }
}
