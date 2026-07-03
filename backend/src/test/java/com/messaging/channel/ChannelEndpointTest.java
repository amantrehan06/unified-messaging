package com.messaging.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.messaging.tenant.Tenant;
import com.messaging.tenant.TenantContext;
import com.messaging.tenant.TenantRepository;
import com.messaging.web.JwtService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @MockitoBean
    private UnipileClient unipileClient;

    @Value("${unipile.webhook-secret}")
    private String webhookSecret;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenantRepository.save(new Tenant("test-tenant"));
        tenantId = tenant.getId();
    }

    @Test
    void connectChannel_returnsHostedAuthUrl() throws Exception {
        when(unipileClient.createHostedAuthLink(any()))
                .thenReturn("https://account.unipile.com/test-link");

        UUID userId = UUID.randomUUID();
        String token = jwtService.mint(userId, tenantId, "test@example.com", "owner");

        mockMvc.perform(post("/api/channels/connect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"whatsapp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://account.unipile.com/test-link"));
    }

    @Test
    void connectChannel_requiresAuth() throws Exception {
        // Spring OAuth2 redirects unauthenticated requests to the login page (302)
        mockMvc.perform(post("/api/channels/connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"whatsapp\"}"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void notifyCallback_validSecret_returns200() throws Exception {
        mockMvc.perform(post("/api/unipile/notify")
                        .param("secret", webhookSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "CREATION_SUCCESS", "account_id": "acc1", "name": "%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    @Test
    void notifyCallback_invalidSecret_returns401() throws Exception {
        mockMvc.perform(post("/api/unipile/notify")
                        .param("secret", "wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "CREATION_SUCCESS", "account_id": "acc1", "name": "%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void notifyCallback_missingSecret_returns400() throws Exception {
        mockMvc.perform(post("/api/unipile/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "CREATION_SUCCESS", "account_id": "acc1", "name": "tenant"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void accountStatusWebhook_invalidSecret_returns401() throws Exception {
        mockMvc.perform(post("/api/unipile/account-status")
                        .param("secret", "forged-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"AccountStatus": {"account_id": "acc1", "account_type": "WHATSAPP", "message": "OK"}}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accountStatusWebhook_validSecret_returns200() throws Exception {
        mockMvc.perform(post("/api/unipile/account-status")
                        .param("secret", webhookSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"AccountStatus": {"account_id": "unknown-acc", "account_type": "WHATSAPP", "message": "OK"}}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void listChannels_requiresAuth() throws Exception {
        mockMvc.perform(get("/api/channels"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void webhookEndpoints_doNotRequireJwt() throws Exception {
        mockMvc.perform(post("/api/unipile/notify")
                        .param("secret", webhookSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "UNKNOWN", "account_id": "x", "name": "%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    @Test
    void forgedNotifyCallback_writesNothing() throws Exception {
        // Seed a pending channel for a real tenant
        try {
            TenantContext.set(tenantId);
            Channel pending = new Channel(tenantId, "whatsapp");
            channelRepository.save(pending);
            UUID channelId = pending.getId();

            // Forged callback: valid payload but wrong secret
            mockMvc.perform(post("/api/unipile/notify")
                            .param("secret", "forged-secret")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "CREATION_SUCCESS", "account_id": "attacker-acc", "name": "%s"}
                                    """.formatted(tenantId)))
                    .andExpect(status().isUnauthorized());

            // Channel must still be pending - no DB mutation happened
            Optional<Channel> after = channelRepository.findById(channelId);
            assertThat(after).isPresent();
            assertThat(after.get().getStatus()).isEqualTo("pending");
            assertThat(after.get().getExternalAccountId()).isNull();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void forgedStatusWebhook_writesNothing() throws Exception {
        // Seed a connected channel
        try {
            TenantContext.set(tenantId);
            Channel connected = new Channel(tenantId, "whatsapp");
            connected.setExternalAccountId("real-acc-for-forge-test");
            connected.setStatus("connected");
            channelRepository.save(connected);
            UUID channelId = connected.getId();

            // Forged status webhook: valid payload but wrong secret
            mockMvc.perform(post("/api/unipile/account-status")
                            .param("secret", "forged-secret")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"AccountStatus": {"account_id": "real-acc-for-forge-test", "account_type": "WHATSAPP", "message": "ERROR"}}
                                    """))
                    .andExpect(status().isUnauthorized());

            // Channel must still be connected - no status change
            Optional<Channel> after = channelRepository.findById(channelId);
            assertThat(after).isPresent();
            assertThat(after.get().getStatus()).isEqualTo("connected");
        } finally {
            TenantContext.clear();
        }
    }
}
