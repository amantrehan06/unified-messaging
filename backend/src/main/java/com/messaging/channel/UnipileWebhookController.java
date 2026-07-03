package com.messaging.channel;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.messaging.tenant.TenantContext;

@RestController
public class UnipileWebhookController {

    private static final Logger log = LoggerFactory.getLogger(UnipileWebhookController.class);

    private final ChannelService channelService;
    private final String webhookSecret;

    public UnipileWebhookController(ChannelService channelService,
                                     @Value("${unipile.webhook-secret}") String webhookSecret) {
        this.channelService = channelService;
        this.webhookSecret = webhookSecret;
    }

    /**
     * Hosted auth callback - Unipile calls this server-to-server when a user
     * completes the hosted auth flow.
     */
    @PostMapping("/api/unipile/notify")
    public ResponseEntity<Void> notify(@RequestParam("secret") String secret,
                                        @RequestBody NotifyPayload payload) {
        if (!webhookSecret.equals(secret)) {
            log.warn("Rejected notify callback: invalid secret");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UUID tenantId;
        try {
            tenantId = UUID.fromString(payload.name());
        } catch (IllegalArgumentException e) {
            log.warn("Rejected notify callback: invalid tenant id in name field: {}", payload.name());
            return ResponseEntity.badRequest().build();
        }

        try {
            TenantContext.set(tenantId);
            channelService.handleNotify(tenantId, payload.status(), payload.accountId());
        } finally {
            TenantContext.clear();
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Account status webhook - Unipile calls this when an account's status changes.
     */
    @PostMapping("/api/unipile/account-status")
    public ResponseEntity<Void> accountStatus(@RequestParam("secret") String secret,
                                               @RequestBody Map<String, Map<String, String>> body) {
        if (!webhookSecret.equals(secret)) {
            log.warn("Rejected account-status webhook: invalid secret");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, String> status = body.get("AccountStatus");
        if (status == null) {
            return ResponseEntity.badRequest().build();
        }

        String accountId = status.get("account_id");
        String message = status.get("message");

        UUID tenantId = channelService.lookupTenantByAccountId(accountId);
        if (tenantId == null) {
            log.warn("No channel found for accountId={}", accountId);
            return ResponseEntity.ok().build();
        }

        try {
            TenantContext.set(tenantId);
            channelService.handleAccountStatus(accountId, message);
        } finally {
            TenantContext.clear();
        }

        return ResponseEntity.ok().build();
    }

    record NotifyPayload(
            String status,
            @JsonProperty("account_id") String accountId,
            String name
    ) {}
}
