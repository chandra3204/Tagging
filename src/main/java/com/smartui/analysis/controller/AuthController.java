package com.smartui.analysis.controller;

import com.smartui.analysis.model.User;
import com.smartui.analysis.repository.UserRepository;
import com.smartui.analysis.service.FileLoggerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final FileLoggerService fileLoggerService;

    public AuthController(UserRepository userRepository, FileLoggerService fileLoggerService) {
        this.userRepository = userRepository;
        this.fileLoggerService = fileLoggerService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String email = credentials.get("email");
        String password = credentials.get("password");

        if (email == null || password == null) {
            Map<String, String> response = new HashMap<>();
            response.put("error", "Email and password are required.");
            fileLoggerService.logWarn("AUTH", email, "Login attempt with missing credentials", null);
            return ResponseEntity.badRequest().body(response);
        }

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isPresent() && userOpt.get().getPassword().equals(password)) {
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Welcome Back");
            response.put("email", email);
            // Simulate a session token
            response.put("token", "session_token_" + email.hashCode());
            fileLoggerService.logInfo("AUTH", email, "Login successful");
            return ResponseEntity.ok(response);
        }

        Map<String, String> response = new HashMap<>();
        response.put("error", "Invalid email or password.");
        fileLoggerService.logWarn("AUTH", email, "Login failed - invalid credentials", null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }
}
