package com.messaging.channel;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

@Service
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    private final ChannelRepository channelRepository;
    private final EntityManager entityManager;

    public ChannelService(ChannelRepository channelRepository, EntityManager entityManager) {
        this.channelRepository = channelRepository;
        this.entityManager = entityManager;
    }

    /**
     * Handle a notify callback from Unipile hosted auth.
     * Must be called with TenantContext already set.
     */
    @Transactional
    public void handleNotify(UUID tenantId, String status, String accountId) {
        if (!"CREATION_SUCCESS".equals(status) && !"RECONNECTED".equals(status)) {
            log.warn("Unhandled notify status: {}", status);
            return;
        }

        channelRepository.findByExternalAccountId(accountId)
                .or(() -> channelRepository.findByTenantId(tenantId).stream()
                        .filter(c -> "pending".equals(c.getStatus()))
                        .findFirst())
                .ifPresentOrElse(channel -> {
                    channel.setExternalAccountId(accountId);
                    channel.setStatus("connected");
                    channel.setConnectedAt(Instant.now());
                    channel.setLastStatusAt(Instant.now());
                    channelRepository.save(channel);
                    log.info("Channel connected: tenant={}, accountId={}", tenantId, accountId);
                }, () -> log.warn("No pending channel found for tenant={}", tenantId));
    }

    /**
     * Handle an account status webhook from Unipile.
     * Must be called with TenantContext already set.
     */
    @Transactional
    public void handleAccountStatus(String accountId, String unipileMessage) {
        channelRepository.findByExternalAccountId(accountId).ifPresentOrElse(channel -> {
            String newStatus = mapUnipileStatus(unipileMessage);
            channel.setStatus(newStatus);
            channel.setLastStatusAt(Instant.now());
            channelRepository.save(channel);
            log.info("Channel status updated: accountId={}, status={}", accountId, newStatus);
        }, () -> log.warn("No channel found for accountId={}", accountId));
    }

    /**
     * Look up the tenant that owns a given external account ID.
     * Uses a SECURITY DEFINER function to bypass RLS.
     */
    @Transactional(readOnly = true)
    public UUID lookupTenantByAccountId(String accountId) {
        try {
            Object result = entityManager
                    .createNativeQuery("SELECT channel_tenant_by_account(:accountId)")
                    .setParameter("accountId", accountId)
                    .getSingleResult();
            return result != null ? (UUID) result : null;
        } catch (NoResultException e) {
            return null;
        }
    }

    private String mapUnipileStatus(String unipileMessage) {
        return switch (unipileMessage) {
            case "OK", "CREATION_SUCCESS", "RECONNECTED", "SYNC_SUCCESS" -> "connected";
            case "CREDENTIALS", "ERROR", "STOPPED" -> "error";
            case "CONNECTING" -> "pending";
            case "DELETED" -> "disabled";
            default -> "error";
        };
    }
}
