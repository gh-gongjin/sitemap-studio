package io.github.ghgongjin.sitemap.controller;

import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.UserAccountRepository;
import io.github.ghgongjin.sitemap.service.UserService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserService userService;
    @Autowired private UserAccountRepository users;

    @Test
    void shouldRegisterAndCreateBcryptUser() throws Exception {
        mvc.perform(post("/register").with(csrf())
                        .param("username", "dave")
                        .param("password", "Passw0rd1")
                        .param("confirmPassword", "Passw0rd1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?registered=1"));

        UserAccount saved = users.findByUsername("dave").orElseThrow();
        assertThat(saved.getPasswordHash()).startsWith("{bcrypt}$2a$").isNotEqualTo("Passw0rd1");
    }

    @ParameterizedTest
    @CsvSource({
            "ab,Passw0rd1,Passw0rd1,auth.error.username",
            "dave,short1,short1,auth.error.password",
            "dave,Passw0rd1,Passw0rd2,auth.error.mismatch",
    })
    void shouldReRenderRegisterWithLocalizedError(String u, String p, String c, String errorKey)
            throws Exception {
        mvc.perform(post("/register").with(csrf())
                        .param("username", u).param("password", p).param("confirmPassword", c))
                .andExpect(status().isOk())
                .andExpect(model().attribute("errorKey", errorKey))
                .andExpect(model().attribute("username", u));
    }

    @Test
    void shouldRejectRegisterWithoutCsrf() throws Exception {
        mvc.perform(post("/register")
                        .param("username", "eve").param("password", "Passw0rd1")
                        .param("confirmPassword", "Passw0rd1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAuthenticateWhenPasswordMatches() throws Exception {
        userService.register("frank", "Passw0rd1");

        mvc.perform(formLogin("/login").user("frank").password("Passw0rd1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(authenticated().withUsername("frank").withRoles("USER"));
    }

    @Test
    void shouldRedirectWithErrorWhenPasswordWrong() throws Exception {
        userService.register("grace", "Passw0rd1");

        mvc.perform(formLogin("/login").user("grace").password("WrongPass1"))
                .andExpect(redirectedUrl("/login?error=1"))
                .andExpect(unauthenticated());
    }

    @Test
    void shouldLogoutAndInvalidateContext() throws Exception {
        userService.register("henry", "Passw0rd1");
        var session = mvc.perform(formLogin("/login").user("henry").password("Passw0rd1"))
                .andReturn().getRequest().getSession();

        mvc.perform(post("/logout").with(csrf())
                        .session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(unauthenticated());
    }

    @Test
    void shouldBounceAuthenticatedUserFromLoginAndRegisterPages() throws Exception {
        // Given: 账号已登录
        userService.register("ivy", "Passw0rd1");
        var session = mvc.perform(formLogin("/login").user("ivy").password("Passw0rd1"))
                .andReturn().getRequest().getSession();

        // When & Then: 矩阵约定「已登录访问登录/注册页则 302 回首页」，不再向本人展示登录表单
        for (String path : new String[]{"/login", "/register"}) {
            mvc.perform(get(path).session((org.springframework.mock.web.MockHttpSession) session))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/"));
        }

        // And: 已登录用户提交注册表单同样回首页，不得再新建账号
        mvc.perform(post("/register").with(csrf()).session((org.springframework.mock.web.MockHttpSession) session)
                        .param("username", "mallory").param("password", "Passw0rd1")
                        .param("confirmPassword", "Passw0rd1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        assertThat(users.findByUsername("mallory")).isEmpty();
    }

    @Test
    void shouldRenderLoginAndRegisterPagesInChineseAndEnglish() throws Exception {
        for (String lang : new String[]{"zh-CN", "en"}) {
            String login = mvc.perform(get("/login").param("lang", lang))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(Jsoup.parse(login).select("form[action$=/login]")).hasSize(1);
            assertThat(login).doesNotContain("??auth.", "auth.error");
            // CSRF 收紧后真实浏览器提交依赖 Thymeleaf 注入的隐藏 token，缺失即 403
            assertThat(login).contains("name=\"_csrf\"");

            String register = mvc.perform(get("/register").param("lang", lang))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(Jsoup.parse(register).select("input[type=password]")).hasSize(2);
            assertThat(register).doesNotContain("??auth.");
            assertThat(register).contains("name=\"_csrf\"");
        }
    }

    @Test
    void shouldRenderBilingualLoginHintsWhenErrorAndRegisteredParamsPresent() throws Exception {
        // Given / When / Then: ?error=1 与 ?registered=1 的提示经真实 MessageSource 解析，中英各自命中
        assertThat(loginHint("error", "zh-CN", ".form-error")).isEqualTo("用户名或密码错误");
        assertThat(loginHint("error", "en", ".form-error")).isEqualTo("Incorrect username or password");
        assertThat(loginHint("registered", "zh-CN", ".form-ok")).isEqualTo("注册成功，请登录");
        assertThat(loginHint("registered", "en", ".form-ok")).isEqualTo("Registered successfully, please login");

        // And: 两个参数都不带时不渲染任何提示，避免模板默认文案常驻页面
        String plain = mvc.perform(get("/login").param("lang", "zh-CN"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(Jsoup.parse(plain).select(".form-error, .form-ok")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/about", "/help", "/preview", "/download", "/task/missing", "/api/task/missing/progress",
            "/api/task/missing/result", "/ws-progress/info", "/css/app.css", "/js/app.js",
            "/fonts/none.woff2", "/favicon.ico"
    })
    void shouldKeepPublicPathsOpenToGuests(String path) throws Exception {
        // Given / When: 游客直连游客免费链路（业务侧 400/404 属正常，关键看安全层放行与否）
        MockHttpServletResponse response = mvc.perform(get(path)).andReturn().getResponse();

        // Then: 绝不能被门禁送到登录页，否则首页"免费 · 免注册"定位即被破坏
        assertThat(bouncedToLogin(response)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/generate", "/generate-async"})
    void shouldKeepGuestCrawlSubmissionOpen(String path) throws Exception {
        // Given: 非法地址让爬取快速失败，只验证安全层是否放行游客 POST
        MockHttpServletResponse response = mvc.perform(post(path).with(csrf())
                        .param("url", "not-a-url"))
                .andReturn().getResponse();

        // Then: 失败后回首页/进度页，而不是回登录页
        assertThat(bouncedToLogin(response)).isFalse();
    }

    @Test
    void shouldResumeSavedRequestAfterLoginWhenGuestWasBounced() throws Exception {
        // Given: 同一会话内游客访问门禁页被 302 到登录页，原始目标由 RequestCache 暂存进该会话
        userService.register("kate", "Passw0rd1");
        MockHttpSession session = new MockHttpSession();
        String target = "/report/t-kate";
        assertThat(bouncedToLogin(mvc.perform(get(target).session(session)).andReturn().getResponse()))
                .isTrue();

        // When & Then: 该会话登录成功后回跳原始目标页，而不是固定首页
        var result = mvc.perform(post("/login").with(csrf()).session(session)
                        .param("username", "kate").param("password", "Passw0rd1"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        // SavedRequest 处理器给出原始绝对地址，并按惯例追加 ?continue 作为缓存分隔标记
        assertThat(result.getResponse().getRedirectedUrl()).endsWith("/report/t-kate?continue");
    }

    /**
     * 判定响应是否为「302 到登录页」，即游客被访问门禁拦截。
     */
    private boolean bouncedToLogin(MockHttpServletResponse response) {
        int status = response.getStatus();
        String location = response.getHeader("Location");
        return status >= 300 && status < 400 && location != null && location.contains("/login");
    }

    private String loginHint(String flag, String lang, String selector) throws Exception {
        String html = mvc.perform(get("/login").param("lang", lang).param(flag, "1"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        // key 未解析时 Thymeleaf 会渲染成 ??auth.xxx??，出现即视为失败
        assertThat(html).doesNotContain("??auth.");
        Element hint = Jsoup.parse(html).selectFirst(selector);
        assertThat(hint).isNotNull();
        return hint.text();
    }
}
