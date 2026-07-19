package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Slf4j
@Controller
@ConditionalOnProperty(prefix = "giteabot.security", name = "login-method", havingValue = "native", matchIfMissing = true)
public class SetupController {

    private final AdminService adminService;

    public SetupController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/setup")
    public String setup(Model model) {
        if (!adminService.isSetupRequired()) {
            return "redirect:/login";
        }
        return "setup";
    }

    @PostMapping("/setup")
    public String createAdmin(@RequestParam String username,
                              @RequestParam String password,
                              @RequestParam String confirmPassword,
                              RedirectAttributes redirectAttributes) {
        if (!adminService.isSetupRequired()) {
            return "redirect:/login";
        }

        if (username == null || username.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Username is required");
            return "redirect:/setup";
        }

        if (password == null || password.length() < 8) {
            redirectAttributes.addFlashAttribute("error", "Password must be at least 8 characters");
            return "redirect:/setup";
        }

        if (!password.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("error", "Passwords do not match");
            return "redirect:/setup";
        }

        adminService.createAdmin(username, password);
        log.info("Admin user '{}' created during initial setup", username);
        redirectAttributes.addFlashAttribute("success", "Admin account created successfully. Please login.");
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login(Model model) {
        if (adminService.isSetupRequired()) {
            return "redirect:/setup";
        }
        return "login";
    }
}
