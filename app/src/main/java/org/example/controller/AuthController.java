package org.example.controller;

import org.example.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@CrossOrigin("*")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil               jwtUtil;

    /**
     * POST /api/auth/login
     * Body: { "username": "admin", "password": "password123" }
     * Returns: { "token": "<jwt>" }  on success
     *          { "error": "Invalid credentials" }  on failure (HTTP 401)
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @RequestBody Map<String, String> credentials) {

        String username = credentials.getOrDefault("username", "");
        String password = credentials.getOrDefault("password", "");

        log.info("Login attempt for username: '{}'", username);

        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));

            String token = jwtUtil.generateToken(auth.getName());
            log.info("Login successful for user '{}'.", auth.getName());

            return ResponseEntity.ok(Map.of("token", token));

        } catch (BadCredentialsException e) {
            log.warn("Login failed for username '{}': invalid credentials.", username);
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid credentials"));
        } catch (Exception e) {
            log.error("Unexpected error during login for '{}': {}", username, e.getMessage(), e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Authentication error: " + e.getMessage()));
        }
    }
}
