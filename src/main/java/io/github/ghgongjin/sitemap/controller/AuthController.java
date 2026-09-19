package io.github.ghgongjin.sitemap.controller;

import io.github.ghgongjin.sitemap.service.RegistrationException;
import io.github.ghgongjin.sitemap.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * @ClassName AuthController
 * @Description 登录/注册页面路由与注册表单处理：注册失败按原因映射本地化消息 key，
 *              回显到注册页内联展示（禁止浏览器原生弹窗）
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
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
