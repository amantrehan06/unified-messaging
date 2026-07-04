package com.messaging.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.messaging.tenant.Tenant;
import com.messaging.tenant.TenantRepository;

@RestController
public class AuthController {

    private final TenantRepository tenantRepo;

    public AuthController(TenantRepository tenantRepo) {
        this.tenantRepo = tenantRepo;
    }

    @GetMapping("/api/me")
    @Transactional(readOnly = true)
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        String tenantName = tenantRepo.findById(user.tenantId())
                .map(Tenant::getName)
                .orElse("Unknown");
        return new MeResponse(
                user.userId().toString(),
                user.email(),
                user.role(),
                user.tenantId().toString(),
                tenantName
        );
    }

    record MeResponse(String userId, String email, String role, String tenantId, String tenantName) {}
}
