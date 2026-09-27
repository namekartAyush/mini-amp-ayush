package com.namekart.auction_api.security.controller;

import com.namekart.auction_api.security.model.UserRole;
import com.namekart.auction_api.security.service.JwtService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    public record TokenRequest(
            @NotBlank @Email String email,
            @NotNull UserRole role
    ) {}

    public record TokenResponse(
            String token,
            String tokenType,
            String email,
            String role,
            long expiresInHours
    ) {}

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> issueToken(@RequestBody TokenRequest request) {
        String token = jwtService.generateToken(request.email(), request.role());
        return ResponseEntity.ok(new TokenResponse(
                token,
                "Bearer",
                request.email(),
                request.role().name(),
                24
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody Map<String, String> body) {
        String email = body.getOrDefault("email", "bidder@miniamp.com");
        String roleStr = body.getOrDefault("role", "BIDDER");
        UserRole role;
        try {
            role = UserRole.valueOf(roleStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            role = UserRole.BIDDER;
        }

        String token = jwtService.generateToken(email, role);
        return ResponseEntity.ok(new TokenResponse(
                token,
                "Bearer",
                email,
                role.name(),
                24
        ));
    }
}
