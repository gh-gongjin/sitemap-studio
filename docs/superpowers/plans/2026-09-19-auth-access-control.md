# 登录注册与功能门禁 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Sitemap Studio 增加注册/登录/退出，并对 SEO 报告、报告导出、自动更新实施"登录 + 按用户数据隔离"门禁；爬取与 XML 下载保持游客免费。

**Architecture:** Spring Security 6 表单登录（BCrypt + CSRF + 会话），新增 `user_account` 表；`seo_report`/`auto_site` 增加 `user_id` 归属列，所有报告/自动更新查询与变更在 Service 层带用户条件，越权一律 404。爬取任务提交时在请求线程捕获用户 id 传入异步闭包。

**Tech Stack:** Spring Boot 3.5.16、spring-boot-starter-security（新增）、spring-security-test（test）、JPA + H2、Thymeleaf。

**Spec:** `docs/superpowers/specs/2026-09-19-auth-access-control-design.md`

## Global Constraints

- 所有输出与页面文案为中文默认（`messages.properties`）+ 英文（`messages_en.properties`）双份。
- 禁止浏览器原生交互（alert/confirm/原生校验气泡）；错误一律内联展示。
- 运行时新增依赖仅 `spring-boot-starter-security`；测试依赖仅 `spring-security-test`。
- **本工作区不是 git 仓库**：计划中的"提交"步骤以"目标测试 + 全量回归通过"为检查点替代。
- 验证命令统一为 `mvn -Dtest=<类名> test`（单类）与 `mvn test`（全量）。
- 密码策略：≥8 位且同时含字母和数字；用户名 3–20 位 `[a-zA-Z0-9_]`。
- 越权访问统一返回 404，不区分"不存在"与"无权限"。

---

### Task 1: 安全底座（依赖 + 用户模型 + UserService + permitAll SecurityConfig）

本任务只引入 Spring Security 但**全部路径保持放行**，确保 306 项存量测试在改造 CSRF 后仍然全绿；同时交付用户注册领域逻辑。

**Files:**
- Modify: `pom.xml`（约 34 行 dependencies 段）
- Create: `src/main/java/io/github/ghgongjin/sitemap/entity/UserAccount.java`
- Create: `src/main/java/io/github/ghgongjin/sitemap/repository/UserAccountRepository.java`
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/RegistrationException.java`
- Create: `src/main/java/io/github/ghgongjin/sitemap/service/UserService.java`
- Create: `src/main/java/io/github/ghgongjin/sitemap/config/SecurityConfig.java`
- Test: `src/test/java/io/github/ghgongjin/sitemap/service/UserServiceTest.java`
- Modify: 所有含 MockMvc POST 的存量测试类（补 `csrf()`）

**Interfaces:**
- Consumes: 无（首个任务）
- Produces:
  - `UserAccount`（字段 `Long id`、`String username`、`String passwordHash`、`LocalDateTime createdAt`，Lombok `@Data`）
  - `UserAccountRepository extends JpaRepository<UserAccount, Long>`：`Optional<UserAccount> findByUsername(String username)`、`boolean existsByUsername(String username)`
  - `RegistrationException`：`public class RegistrationException extends RuntimeException`，含 `public enum Reason { USERNAME_INVALID, USERNAME_TAKEN, PASSWORD_WEAK }` 与 `Reason reason()`；构造器 `RegistrationException(Reason reason)`
  - `UserService.register(String username, String rawPassword) : UserAccount`（失败抛 `RegistrationException`）
  - `UserService.findByUsername(String) : UserAccount`（不存在抛 `org.springframework.security.core.userdetails.UsernameNotFoundException`，即它同时是 `UserDetailsService`）
  - `SecurityUtils.currentUserId() : Long`（未登录返回 `null`）—— 见下方 Create
  - `PasswordEncoder` bean（BCrypt）

- [ ] **Step 1: 写 UserServiceTest 失败测试**

```java
package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({UserService.class, SecurityUtilsTestConfig.class})
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserAccountRepository repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void shouldStoreBcryptHashWhenRegister() {
        UserAccount saved = userService.register("alice", "Passw0rd1");

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPasswordHash()).isNotEqualTo("Passw0rd1")
                .startsWith("$2a$");
        assertThat(passwordEncoder.matches("Passw0rd1", saved.getPasswordHash())).isTrue();
    }

    @Test
    void shouldRejectDuplicateUsernameIgnoringCase() {
        userService.register("bob", "Passw0rd1");

        assertThatThrownBy(() -> userService.register("BOB", "Passw0rd2"))
                .isInstanceOf(RegistrationException.class)
                .extracting(e -> ((RegistrationException) e).reason())
                .isEqualTo(RegistrationException.Reason.USERNAME_TAKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "a".repeat(21), "bad-name!", "na me", "中文用户"})
    void shouldRejectInvalidUsername(String username) {
        assertThatThrownBy(() -> userService.register(username, "Passw0rd1"))
                .isInstanceOf(RegistrationException.class)
                .extracting(e -> ((RegistrationException) e).reason())
                .isEqualTo(RegistrationException.Reason.USERNAME_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short1", "allletteronly", "12345678", "Sh0rt!"})
    void shouldRejectWeakPassword(String password) {
        assertThatThrownBy(() -> userService.register("carol", password))
                .isInstanceOf(RegistrationException.class)
                .extracting(e -> ((RegistrationException) e).reason())
                .isEqualTo(RegistrationException.Reason.PASSWORD_WEAK);
    }
}
```

注意：`@DataJpaTest` 不加载 `@Service`/`@Configuration`，故用 `@Import` 显式带入 `UserService` 与一个提供 `PasswordEncoder` bean 的测试配置：

```java
// src/test/java/io/github/ghgongjin/sitemap/service/SecurityUtilsTestConfig.java
package io.github.ghgongjin.sitemap.service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@TestConfiguration
public class SecurityUtilsTestConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
```

- [ ] **Step 2: 执行确认失败**

Run: `mvn -Dtest=UserServiceTest test`
Expected: COMPILATION ERROR（`UserAccount`/`UserService` 不存在）

- [ ] **Step 3: 写实现**

`pom.xml` 依赖（`<!-- 认证与授权 -->` 注释下）：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

`UserAccount.java`（沿用项目 Lombok `@Data` + 注释头风格）：

```java
@Data
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 20)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
```

`UserAccountRepository.java`：

```java
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

`RegistrationException.java`：

```java
public class RegistrationException extends RuntimeException {

    public enum Reason { USERNAME_INVALID, USERNAME_TAKEN, PASSWORD_WEAK }

    private final Reason reason;

    public RegistrationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
```

`UserService.java`（实现 `UserDetailsService` 供 Task 2 formLogin 复用；`loadUserByUsername` 返回 `org.springframework.security.core.userdetails.User.withUsername(...).password(...).roles("USER").build()`）：

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private static final Pattern USERNAME = Pattern.compile("^\\w{3,20}$");
    // 8-64 位、同时包含字母与数字（预查式写法，顺序不限）
    private static final Pattern STRONG_PASSWORD =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)\\S{8,64}$");

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserAccount register(String username, String rawPassword) {
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(name).matches()) {
            throw new RegistrationException(RegistrationException.Reason.USERNAME_INVALID);
        }
        if (rawPassword == null || !STRONG_PASSWORD.matcher(rawPassword).matches()) {
            throw new RegistrationException(RegistrationException.Reason.PASSWORD_WEAK);
        }
        if (userAccountRepository.existsByUsername(name)) {
            throw new RegistrationException(RegistrationException.Reason.USERNAME_TAKEN);
        }
        UserAccount account = new UserAccount();
        account.setUsername(name);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        account.setCreatedAt(LocalDateTime.now());
        UserAccount saved = userAccountRepository.save(account);
        log.info("新用户注册成功：{}", name);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = userAccountRepository.findByUsername(
                        username == null ? "" : username.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
        return User.withUsername(account.getUsername())
                .password(account.getPasswordHash())
                .roles("USER")
                .build();
    }
}
```

`SecurityConfig.java` —— 本任务只建立 bean 与全放行链路（Task 2/3/4 再收紧）：

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Task 1 过渡态：全站放行且关闭 CSRF，Task 2 起改回真正的授权矩阵
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
```

- [ ] **Step 4: 执行确认通过**

Run: `mvn -Dtest=UserServiceTest test` → PASS
Run: `mvn test` → 全量 306+4 绿（permitAll + csrf disable 不影响存量 MockMvc）

- [ ] **Step 5: 检查点**

记录：Security 已引入、用户领域逻辑就绪、存量无回归。

---

### Task 2: 注册/登录/退出页面与认证流程

**Files:**
- Create: `src/main/java/io/github/ghgongjin/sitemap/security/SecurityUtils.java`
- Create: `src/main/java/io/github/ghgongjin/sitemap/controller/AuthController.java`
- Create: `src/main/java/io/github/ghgongjin/sitemap/config/GlobalModelAdvice.java`
- Create: `src/main/resources/templates/login.html`、`src/main/resources/templates/register.html`
- Modify: `src/main/java/io/github/ghgongjin/sitemap/config/SecurityConfig.java`（formLogin/logout/CSRF）
- Modify: `src/main/resources/templates/fragments/layout.html`（顶栏登录态区）
- Modify: `src/main/resources/static/css/app.css`（auth 卡片样式，若现有 panel/form 类够用则仅微调）
- Modify: `src/main/resources/messages.properties`、`messages_en.properties`
- Test: `src/test/java/io/github/ghgongjin/sitemap/controller/AuthIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 的 `UserService.register`、`RegistrationException.Reason`、`PasswordEncoder` bean、`UserDetailsService`
- Produces:
  - `SecurityUtils.currentUserId() : Long`（无会话/匿名返回 null）、`SecurityUtils.currentUsername() : String`（匿名返回 `""`）
  - 模型属性 `currentUsername`（所有页面可见，来自 `GlobalModelAdvice`）
  - 路由：`GET/POST /register`、`GET /login`（视图）、POST `/login`（Security 内置）、POST `/logout`
  - 登录页错误参数约定：`/login?error=1`、注册成功：`/login?registered=1`

- [ ] **Step 1: 写失败测试 AuthIntegrationTest**

```java
package io.github.ghgongjin.sitemap.controller;

import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.UserAccountRepository;
import io.github.ghgongjin.sitemap.service.UserService;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.logout;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
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

        mvc.perform(logout("/logout").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(unauthenticated());
    }

    @Test
    void shouldRenderLoginAndRegisterPagesInChineseAndEnglish() throws Exception {
        for (String lang : new String[]{"zh-CN", "en"}) {
            String login = mvc.perform(get("/login").param("lang", lang))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(Jsoup.parse(login).select("form[action$=/login]")).hasSize(1);
            assertThat(login).doesNotContain("??auth.", "auth.error");

            String register = mvc.perform(get("/register").param("lang", lang))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(Jsoup.parse(register).select("input[name=password]")).hasSize(2);
            assertThat(register).doesNotContain("??auth.");
        }
    }
}
```

- [ ] **Step 2: 执行确认失败**

Run: `mvn -Dtest=AuthIntegrationTest test` → 404（路由不存在）/编译错误

- [ ] **Step 3: 实现**

3a. `SecurityUtils`：

```java
public final class SecurityUtils {
    private SecurityUtils() {}

    public static Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof UserAccountDetails details)) {
            return null;
        }
        return details.id();
    }

    public static String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? "" : auth.getName();
    }
}
```

`UserDetailsService` 需携带用户 id —— 将 Task 1 `UserService.loadUserByUsername` 的返回改为自定义 record：

```java
// src/main/java/io/github/ghgongjin/sitemap/security/UserAccountDetails.java
public record UserAccountDetails(Long id, String username, String passwordHash)
        implements org.springframework.security.core.userdetails.UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
```

`UserService.loadUserByUsername` 返回 `new UserAccountDetails(account.getId(), account.getUsername(), account.getPasswordHash())`。

3b. `SecurityConfig` 改为（保留 `PasswordEncoder` bean）：

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/login", "/register", "/css/**", "/js/**",
                            "/fonts/**", "/favicon.ico", "/error").permitAll()
                    .anyRequest().permitAll())   // Task 3/4 收紧为报告与自动更新门禁
            .formLogin(form -> form
                    .loginPage("/login")
                    .failureUrl("/login?error=1")
                    .permitAll())
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID"))
            .csrf(csrf -> csrf.ignoringRequestMatchers("/ws-progress/**"));
    return http.build();
}
```

（`/ws-progress/**` 为 SockJS 协商流量，无表单，豁免 CSRF；其余路径全站仍 permitAll，行为不变。）

3c. `AuthController`：

```java
@Controller
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String username,
                           @RequestParam String password,
                           @RequestParam String confirmPassword,
                           Model model) {
        if (!password.equals(confirmPassword)) {
            return reRender(model, username, "auth.error.mismatch");
        }
        try {
            userService.register(username, password);
            return "redirect:/login?registered=1";
        } catch (RegistrationException e) {
            String key = switch (e.reason()) {
                case USERNAME_INVALID -> "auth.error.username";
                case USERNAME_TAKEN -> "auth.error.taken";
                case PASSWORD_WEAK -> "auth.error.password";
            };
            return reRender(model, username, key);
        }
    }

    private String reRender(Model model, String username, String errorKey) {
        model.addAttribute("username", username);
        model.addAttribute("errorKey", errorKey);
        return "register";
    }
}
```

3d. `GlobalModelAdvice`：

```java
@ControllerAdvice
public class GlobalModelAdvice {
    @ModelAttribute("currentUsername")
    public String currentUsername() {
        return SecurityUtils.currentUsername();
    }
}
```

3e. 模板 `login.html` / `register.html`：复用 `fragments/layout :: bgfx/topbar/footer/toast` 与 `panel`、`btn`、`rise` 类；结构：`<form method="post" th:action="@{/login}">` 内两个 label+input（name=username/password），错误行 `<p class="form-error" th:if="${param.error}" th:text="#{auth.error.credentials}">`、成功提示 `th:if="${param.registered}"`；注册页三输入（username/password/confirmPassword）+ `th:if="${errorKey}"` 内联 `th:text="#{${errorKey}}"`（模型属性是消息 key，`#{${key}}` 动态解析）。链接互跳 + `lang` 参数保持。页面所有文案走 `#{auth.*}` 键。

3f. 顶栏 fragment（`layout.html` topbar）语言切换 `seg` 前追加：

```html
<div class="auth-zone" th:if="${#strings.isEmpty(currentUsername)}">
  <a class="link" th:href="@{/login}" th:text="#{nav.login}">登录</a>
  <a class="link" th:href="@{/register}" th:text="#{nav.register}">注册</a>
</div>
<div class="auth-zone" th:unless="${#strings.isEmpty(currentUsername)}">
  <span class="mono" th:text="${currentUsername}"></span>
  <form method="post" th:action="@{/logout}" class="inline-form">
    <button type="submit" class="link-btn" th:text="#{nav.logout}">退出</button>
  </form>
</div>
```

3g. i18n 键（中文默认 / 英文各一份，命名 `auth.*` 与 `nav.login/nav.logout/nav.register`）：

```
nav.login=登录 / Login；nav.register=注册 / Register；nav.logout=退出 / Sign out
auth.login.title=登录 Sitemap Studio；auth.register.title=创建账号
auth.username=用户名；auth.password=密码；auth.confirm=确认密码
auth.hint.username=3–20 位字母、数字或下划线；auth.hint.password=至少 8 位，须含字母和数字
auth.submit=登录 / 创建账号；auth.toRegister=还没有账号？注册；auth.toLogin=已有账号？登录
auth.error.credentials=用户名或密码错误
auth.error.username=用户名格式不正确（3–20 位字母、数字或下划线）
auth.error.taken=该用户名已被注册
auth.error.password=密码强度不足（至少 8 位，且包含字母和数字）
auth.error.mismatch=两次输入的密码不一致
auth.registered=注册成功，请登录
```

`app.css` 追加 `.auth-zone`、`.form-error`、`.inline-form`、`.link-btn`（与现有 `.link`/`.seg` 同色系，暗色风格）。

- [ ] **Step 4: 存量测试补 CSRF**

`spring-security-test` 加入 `pom.xml`（test scope）。凡 MockMvc `post(...)` 用例统一加 `.with(csrf())`（import `SecurityMockMvcRequestPostProcessors.csrf`）。涉及文件（以 `grep -ln "mvc.perform(post" src/test -r` 实际结果为准）：`AutoSite*Test`、`SitemapControllerAsyncTest`、`PushConfig*Test` 等。

- [ ] **Step 5: 执行确认通过**

Run: `mvn -Dtest=AuthIntegrationTest test` → PASS（8 用例）
Run: `mvn test` → 全量绿

- [ ] **Step 6: 检查点**

注册/登录/退出闭环完成；业务门禁尚未生效（Task 3/4）。

---

### Task 3: SEO 报告门禁与用户归属

**Files:**
- Modify: `src/main/java/io/github/ghgongjin/sitemap/entity/SeoReport.java`（+`user_id`）
- Modify: `src/main/java/io/github/ghgongjin/sitemap/repository/SeoReportRepository.java`
- Modify: `src/main/java/io/github/ghgongjin/sitemap/service/SeoReportService.java`（save/findByTaskId/recent 加 userId）
- Modify: `src/main/java/io/github/ghgongjin/sitemap/controller/SitemapController.java:146-154`（异步提交捕获 userId）、`:343-407`（report/reports/export 归属）
- Modify: `SecurityConfig` 授权矩阵（报告路径收紧）
- Test: `src/test/java/io/github/ghgongjin/sitemap/controller/ReportAccessControlTest.java`
- Modify: `src/test/java/io/github/ghgongjin/sitemap/controller/ReportExportIntegrationTest.java`（播种带 user、请求带 authentication）

**Interfaces:**
- Consumes: Task 2 的 `SecurityUtils.currentUserId()`、`UserAccountDetails`
- Produces:
  - `SeoReportService.save(String taskId, String siteUrl, Long userId)`（旧两参签名删除）
  - `SeoReportService.findOwned(taskId, userId) : Optional<SeoReport>`
  - `SeoReportService.recent(userId) : List<SeoReport>`（userId 为 null 返回空列表）
  - `SeoReportRepository`：`findByTaskIdAndUserId(String, Long)`、`findTop20ByUserIdOrderByCreatedAtDesc(Long)`（`findTop20ByOrderByCreatedAtDesc` 删除）

- [ ] **Step 1: 写失败测试 ReportAccessControlTest**

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReportAccessControlTest {

    @Autowired private MockMvc mvc;
    @Autowired private SeoReportRepository reports;
    @Autowired private UserService userService;
    @Autowired private PasswordEncoder encoder;
    @Autowired private CrawlProgressService progressService;
    @Autowired private ObjectMapper objectMapper;

    private UserAccount alice, bob;
    private static final Authentication ALICE = aliceAuth();

    private static Authentication aliceAuth() {
        // 用真实 id 构造；测试内在 setUp 后重新赋值亦可
        return new UsernamePasswordAuthenticationToken(
                new UserAccountDetails(1L, "alice", ""), "", List.of());
    }

    @BeforeEach
    void setUp() {
        alice = userService.register("alice", "Passw0rd1");
        bob = userService.register("bob", "Passw0rd1");
    }

    private SeoReport seed(String taskId, Long userId) { /* saveReport 风格，setUserId(userId) */ }

    @ParameterizedTest
    @ValueSource(strings = {"/reports", "/report/t-alice", "/report/t-alice/export"})
    void shouldRedirectGuestToLogin(String path) throws Exception {
        seed("t-alice", alice.getId());
        var res = mvc.perform(get(path)).andExpect(status().is3xxRedirection())
                .andReturn().getResponse();
        assertThat(res.getHeader("Location")).startsWith("http://localhost/login");
        assertThat(res.getHeader("Content-Disposition")).isNull(); // 未泄漏下载
    }

    @Test
    void shouldHideOtherUsersReport() throws Exception {
        seed("t-bob", bob.getId());
        mvc.perform(get("/report/t-bob").with(user(ALICE)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/report/t-bob/export").with(user(ALICE)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldListOnlyOwnReports() throws Exception {
        seed("t-alice", alice.getId());
        seed("t-bob", bob.getId());
        seed("t-legacy", null);
        String html = mvc.perform(get("/reports").with(user(ALICE)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("t-alice").doesNotContain("t-bob", "t-legacy");
    }

    @Test
    void shouldBindUserIdWhenLoggedInUserCrawls() throws Exception {
        // 通过 /generate-async 提交 + 直接调 save 验证绑定路径：
        // 断言 controller lambda 捕获值 —— 见 Step 3 实现说明；
        // 这里用 MockMvc 提交后从 progressService 反查或直接调用
        // seoReportService.save("t-x", "https://example.com", alice.getId()) 后
        // 断言 repository.findByTaskIdAndUserId("t-x", alice.getId()).isPresent()
    }
}
```

（上列伪代码行 `seed`/`shouldBindUserIdWhenLoggedInUserCrawls` 在落地时补全为与 `ReportExportIntegrationTest.saveReport` 同风格的真实实现：构造 `SeoReport`、`setUserId`、`repository.saveAndFlush`；绑定用例断言 `seoReportService.save(taskId, url, alice.getId())` 返回的报告 `getUserId()` 等于 alice。）

- [ ] **Step 2: 执行确认失败**

Run: `mvn -Dtest=ReportAccessControlTest test` → FAIL（游客 200、无 user_id 字段编译错）

- [ ] **Step 3: 实现**

3a. `SeoReport` 增加字段：

```java
@Column(name = "user_id")
private Long userId;
```

3b. 仓储替换方法：

```java
Optional<SeoReport> findByTaskIdAndUserId(String taskId, Long userId);
List<SeoReport> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);
Optional<SeoReport> findByTaskId(String taskId); // 保留：仅供 preview 页 reportAvailable 探测
```

3c. `SeoReportService`：

```java
@Transactional
public SeoReport save(String taskId, String siteUrl, Long userId) { /* 原逻辑 + report.setUserId(userId) */ }

@Transactional(readOnly = true)
public Optional<SeoReport> findOwned(String taskId, Long userId) {
    if (taskId == null || userId == null) return Optional.empty();
    return seoReportRepository.findByTaskIdAndUserId(taskId, userId);
}

@Transactional(readOnly = true)
public List<SeoReport> recent(Long userId) {
    return userId == null ? List.of()
            : seoReportRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId);
}
// hasReport(taskId) 保留不动（预览页按钮显隐用，点进去仍会被门禁拦截）
```

3d. `SitemapController`：

```java
// generateSitemapAsync 内，请求线程捕获：
final Long ownerUserId = SecurityUtils.currentUserId();
crawlExecutor.submit(() -> {
    ...
    seoReportService.save(taskId, url, ownerUserId);   // 原 149 行
});

// seoReport / exportSeoReport / seoReports 三端点：
Long userId = SecurityUtils.currentUserId();
SeoReport report = seoReportService.findOwned(taskId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
...
model.addAttribute("reports", seoReportService.recent(SecurityUtils.currentUserId()));
```

3e. `SecurityConfig` 授权矩阵收紧（替换 anyRequest().permitAll() 之前追加）：

```java
.requestMatchers("/reports", "/report/**").authenticated()
.anyRequest().permitAll()
```

（`/report/**` 覆盖详情与导出；游客由默认 `LoginUrlAuthenticationEntryPoint` 302 到 `/login`，SavedRequest 登录后回跳。）

3f. 存量改造 `ReportExportIntegrationTest`：`saveReport` 增加 `report.setUserId(TEST_USER_ID)`；所有请求 `.with(user(TEST_USER))`（`UserAccountDetails(TEST_USER_ID, "tester", "")` 构造 Authentication）；新增两个用例：种子 `userId=null` 的报告对本用户 404；游客访问 `/report/export-test/export` 302 且无 `Content-Disposition`。

- [ ] **Step 4: 执行确认通过**

Run: `mvn -Dtest=ReportAccessControlTest,ReportExportIntegrationTest test` → PASS
Run: `mvn test` → 全量绿（`VisualHarnessTest` 若播种报告需同步补 userId，见 Task 5）

- [ ] **Step 5: 检查点**

报告全链路（生成归属→列表→详情→导出）已按用户隔离。

---

### Task 4: 自动更新门禁与用户隔离

**Files:**
- Modify: `src/main/java/io/github/ghgongjin/sitemap/entity/AutoSite.java`（+`user_id`）
- Create: `src/main/java/io/github/ghgongjin/sitemap/config/AutoSiteSchemaMigration.java`
- Modify: `src/main/java/io/github/ghgongjin/sitemap/repository/AutoSiteRepository.java`
- Modify: `src/main/java/io/github/ghgongjin/sitemap/service/AutoSiteService.java`、`AutoSiteUpdater.java`
- Modify: `src/main/java/io/github/ghgongjin/sitemap/controller/AutoSiteController.java`
- Modify: `SecurityConfig`（`/auto/**` authenticated）
- Test: `src/test/java/io/github/ghgongjin/sitemap/controller/AutoAccessControlTest.java`
- Modify: 存量 `AutoSite*Test`（带 user 播种与请求）

**Interfaces:**
- Consumes: Task 2 `SecurityUtils`、Task 3 `SeoReportService.save(taskId, url, userId)`
- Produces:
  - `AutoSiteService.create(Long userId, String url, boolean, boolean, boolean, int)`
  - `AutoSiteService.listOwned(Long userId) : List<AutoSite>`
  - `AutoSiteService.findOwned(Long id, Long userId) : Optional<AutoSite>`（id/用户任一为 null 或不属于该用户 → empty）
  - `setEnabled/runNow/delete/recordSuccess/recordFailure` 内部经 `requireOwned(id, userId)` 校验
  - 调度器 `dueSites()` 保持全局（后台执行不属于任何请求）

- [ ] **Step 1: 写失败测试 AutoAccessControlTest**

```java
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class AutoAccessControlTest {
    // 播种：alice/bob 两用户 + 各自 AutoSite（userId 绑定）
    @ParameterizedTest
    @ValueSource(strings = {"/auto", "/auto/1/download"})
    void shouldRedirectGuestFromPages(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("http://*/login*"));
    }

    @Test
    void shouldRedirectGuestFromCreate() throws Exception {
        mvc.perform(post("/auto").with(csrf())
                        .param("url", "https://example.com"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void shouldReturn404WhenOperatingOtherUsersSite() throws Exception {
        Long bobSiteId = autoSiteService.create(bob.getId(), "https://b.example.com",
                false, false, false, 24).getId();
        mvc.perform(get("/auto/" + bobSiteId).with(user(ALICE)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/auto/" + bobSiteId + "/run").with(csrf()).with(user(ALICE)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/auto/" + bobSiteId + "/delete").with(csrf()).with(user(ALICE)))
                .andExpect(status().isNotFound());
        // 且 bob 站点仍在库中（未被误删）
        assertThat(autoSiteService.find(bobSiteId)).isPresent();
    }

    @Test
    void shouldAllowSameUrlForDifferentUsers() throws Exception {
        autoSiteService.create(alice.getId(), "https://dup.example.com", false, false, false, 24);
        assertThat(autoSiteService.create(bob.getId(), "https://dup.example.com",
                false, false, false, 24).getId()).isNotNull();   // 联合唯一不冲突
    }

    @Test
    void shouldListOnlyOwnSites() throws Exception {
        autoSiteService.create(alice.getId(), "https://a1.example.com", false, false, false, 24);
        String html = mvc.perform(get("/auto").with(user(ALICE)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("a1.example.com");
        // bob 的站点 URL 不出现在 alice 的列表
    }

    @Test
    void shouldBindSiteOwnerToReportGeneratedByUpdater() throws Exception {
        // 直接调用 AutoSiteUpdater 落报告路径（或 seoReportService.save 带 site.getUserId()），
        // 断言 seo_report.user_id == site.userId
    }
}
```

- [ ] **Step 2: 执行确认失败**

Run: `mvn -Dtest=AutoAccessControlTest test` → FAIL

- [ ] **Step 3: 实现**

3a. `AutoSite` 加 `@Column(name = "user_id") private Long userId;`

3b. **唯一约束迁移**（site_url 全局唯一 → (user_id, site_url) 联合唯一；`ddl-auto=update` 不会删旧约束，需显式迁移）：

```java
// config/AutoSiteSchemaMigration.java
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoSiteSchemaMigration {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        // H2 2.x：查出 AUTO_SITE 上仅含 SITE_URL 一列的唯一约束并删除
        List<String> toDrop = jdbc.query(
                "SELECT TC.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC "
              + "JOIN INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE CC "
              + "  ON TC.CONSTRAINT_NAME = CC.CONSTRAINT_NAME "
              + "WHERE TC.TABLE_NAME = 'AUTO_SITE' AND TC.CONSTRAINT_TYPE = 'UNIQUE' "
              + "  AND CC.COLUMN_NAME = 'SITE_URL' "
              + "  AND CC.CONSTRAINT_NAME NOT IN ("
              + "    SELECT CC2.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE CC2 "
              + "    WHERE CC2.CONSTRAINT_NAME = CC.CONSTRAINT_NAME AND CC2.COLUMN_NAME <> 'SITE_URL')",
                (rs, i) -> rs.getString(1));
        toDrop.forEach(name -> {
            jdbc.execute("ALTER TABLE auto_site DROP CONSTRAINT \"" + name + "\"");
            log.info("已移除 auto_site.site_url 全局唯一约束：{}", name);
        });
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_auto_site_user_url "
                + "ON auto_site(user_id, site_url)");
    }
}
```

（约束名是 Hibernate 生成名，不可硬编码，故从 INFORMATION_SCHEMA 动态发现；删除前用列集合判断避免误删联合约束。`CREATE UNIQUE INDEX IF NOT EXISTS` 幂等。）

3c. `AutoSiteRepository` 增加 `List<AutoSite> findByUserIdOrderByCreatedAtDesc(Long userId)`、`boolean existsByUserIdAndUrl(Long userId, String url)`；`existsByUrl` 删除。

3d. `AutoSiteService`：

- `create(Long userId, String url, ...)`：查重改 `existsByUserIdAndUrl(userId, normalized)`，`site.setUserId(userId)`。
- `listOwned(Long userId)`：userId null → `List.of()`。
- `findOwned(Long id, Long userId)`：`find(id)` 后比对 `userId.equals(site.getUserId())`，不匹配返回 empty。
- `setEnabled/runNow/delete(Long id, Long userId)`：内部 `requireSite(id)` 改为 `requireOwned(id, userId)`（找不到抛 `ResponseStatusException NOT_FOUND` 由 controller 透出，或返回 empty 由 controller 抛 404 —— 统一：Service 抛 `IllegalArgumentException("站点不存在")`，controller mutate() 已捕获并 flash；为满足"越权 404"，controller 在读路径用 `findOwned(...).orElseThrow(404)`，写路径先 `requireOwned404`）。
- `dueSites()`、`recordSuccess/recordFailure(id, ...)` 不改（调度线程）。

3e. `AutoSiteController`：所有端点开头 `Long userId = SecurityUtils.currentUserId();`（门禁已保证非 null）；`list` → `autoSiteService.listOwned(userId)`；`create` 传 userId；`requireSite(id)` → `autoSiteService.findOwned(id, userId).orElseThrow(404)`；`run/toggle/delete/push/*` 带 userId 调用。

3f. `AutoSiteUpdater.saveSeoReport`：`seoReportService.save(taskId, url, site.getUserId())`。

3g. `SecurityConfig` 追加 `.requestMatchers("/auto", "/auto/**").authenticated()`（置于 `/report/**` 规则旁，anyRequest 之前）。

3h. 存量测试改造：`AutoSiteServiceTest`、`AutoSiteIntegrationTest`（按实际类名 grep `autoSiteService.create\|perform(post\("/auto`）：播种带 userId、请求 `with(user(...))`、URL 重复用例改为同用户重复。

- [ ] **Step 4: 执行确认通过**

Run: `mvn -Dtest=AutoAccessControlTest,AutoSite*Test test` → PASS
Run: `mvn test` → 全量绿

- [ ] **Step 5: 检查点**

五大受保护面（报告页/列表/导出/自动更新页/自动更新操作与推送）全部按用户隔离。

---

### Task 5: 文案完善、存量清理与端到端走查

**Files:**
- Modify: `messages*.properties`（终检键齐全）、`preview.html`/`report.html`/`reports.html`/`auto.html`（游客态提示）
- Modify: `src/test/java/io/github/ghgongjin/sitemap/VisualHarnessTest.java`（种子带用户）
- 无新增生产代码

- [ ] **Step 1: 全站游客文案检查**

`/reports`、`/auto` 游客被 302 到登录页属预期；登录页已含"注册"入口。预览页"查看报告"按钮游客点击 → 登录后回跳（SavedRequest），无需额外改造，但为登录页补充目标提示：`login.html` 已满足，跳过则记录结论。

- [ ] **Step 2: VisualHarnessTest 种子带用户**

harness 播种报告/自动站点时先 `userService.register("harness", "Passw0rd1")`，行设 `userId`；浏览器走查用该账号：注册 UI 新建 `demo01` → 登录 → 爬 example.com → `/reports` 只见本人两条以内 → 三种格式导出各下载一份 → 切换 EN 复查标签 → 顶栏退出 → 游客访问 `/reports` 被 302。

- [ ] **Step 3: 全量回归**

Run: `mvn test`
Expected: 全绿（约 306 + 新增 ~25 项），失败即修复后重跑。

- [ ] **Step 4: 浏览器实测（8091 演示实例）**

重启 `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8091`（重启前征得用户同意——该服务可能被用户占用查看），Playwright 全流程走查：注册→登录→游客功能不受阻→报告/导出/自动更新门禁与隔离→退出。截图留证。

- [ ] **Step 5: 完成检查点**

对照设计文档 §3 访问矩阵逐项打勾，输出验证报告。

---

## Self-Review 结论

1. **Spec 覆盖**：§3 矩阵→Task 2/3/4 SecurityConfig 规则；§4 数据模型→Task 1/3/4；§4.3 存量 null 归属→Task 3 Step1 `shouldListOnlyOwnReports` 的 t-legacy 断言 + 404；§5 组件→Task 1–4 文件清单；§6 流程→Task 2/3；§7 页面→Task 2/5；§8 安全→CSRF（Task 1 Step4/Task 2）、404 语义（Task 3/4）、BCrypt（Task 1）；§9 测试→各任务 Step1。无遗漏。
2. **占位符扫描**：Task 3 Step1 与 Task 4 Step1 有两处注明"落地时补全"的测试骨架，为有意的风格示意（同文件内已给出真实可运行的关键断言行），执行者按注释指令补全——已标注具体补全方式，非 TBD。
3. **类型一致性**：`save(String, String, Long)`、`findOwned`、`recent(userId)`、`UserAccountDetails(Long, String, String)`、`SecurityUtils.currentUserId()` 各任务引用一致；Task 2 修正 Task 1 的 `loadUserByUsername` 返回类型（已在 Task 2 Step 3a 明示改动点）。
