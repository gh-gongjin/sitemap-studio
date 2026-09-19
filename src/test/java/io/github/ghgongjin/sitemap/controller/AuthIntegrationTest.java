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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
}
