package com.messaging.web;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.messaging.tenant.AppUser;
import com.messaging.tenant.Tenant;
import com.messaging.tenant.TenantRepository;
import com.messaging.tenant.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;

@Component
public class OAuthSuccessHandler implements AuthenticationSuccessHandler {

    private static final String AUTH_PROVIDER = "google";
    private static final String FRONTEND_URL = "http://localhost:5173";

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final JwtService jwtService;

    public OAuthSuccessHandler(UserRepository userRepository,
                               TenantRepository tenantRepository,
                               JwtService jwtService) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.jwtService = jwtService;
    }

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        String googleSub = oauth2User.getAttribute("sub");
        String email = oauth2User.getAttribute("email");

        AppUser user = userRepository
                .findByAuthProviderAndExternalAuthId(AUTH_PROVIDER, googleSub)
                .orElseGet(() -> createUserAndTenant(email, googleSub));

        String token = jwtService.mint(
                user.getId(), user.getTenant().getId(), user.getEmail(), user.getRole());

        String redirectUrl = FRONTEND_URL + "/auth/callback?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        response.sendRedirect(redirectUrl);
    }

    private AppUser createUserAndTenant(String email, String googleSub) {
        String tenantName = email.substring(0, email.indexOf('@'));
        Tenant tenant = tenantRepository.save(new Tenant(tenantName));
        return userRepository.save(new AppUser(tenant, email, AUTH_PROVIDER, googleSub));
    }
}
