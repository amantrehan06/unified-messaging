package com.messaging.web;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.messaging.channel.Channel;
import com.messaging.channel.ChannelRepository;
import com.messaging.tenant.Tenant;
import com.messaging.tenant.TenantContext;
import com.messaging.tenant.TenantRepository;

/**
 * Dev-only seed endpoint. Active only with the "local" profile.
 * Seeds a tenant, user, and connected WhatsApp channel, then returns
 * a JWT and the account ID needed for the inject-message script.
 */
@RestController
@Profile("local")
public class DevSeedController {

    private static final Logger log = LoggerFactory.getLogger(DevSeedController.class);

    private final TenantRepository tenantRepo;
    private final ChannelRepository channelRepo;
    private final JwtService jwtService;

    public DevSeedController(TenantRepository tenantRepo,
                             ChannelRepository channelRepo,
                             JwtService jwtService) {
        this.tenantRepo = tenantRepo;
        this.channelRepo = channelRepo;
        this.jwtService = jwtService;
    }

    @PostMapping("/api/dev/seed")
    @Transactional
    public SeedResponse seed() {
        Tenant tenant = tenantRepo.save(new Tenant("Dev Tenant"));
        UUID userId = UUID.randomUUID();
        String accountId = "dev-acc-" + UUID.randomUUID().toString().substring(0, 8);

        TenantContext.set(tenant.getId());
        try {
            Channel channel = new Channel(tenant.getId(), "whatsapp");
            channel.setExternalAccountId(accountId);
            channel.setStatus("connected");
            channelRepo.save(channel);
        } finally {
            TenantContext.clear();
        }

        String jwt = jwtService.mint(userId, tenant.getId(), "dev@localhost", "owner");

        log.info("Dev seed: tenantId={}, userId={}, accountId={}", tenant.getId(), userId, accountId);

        return new SeedResponse(jwt, tenant.getId().toString(), userId.toString(), accountId);
    }

    record SeedResponse(String jwt, String tenantId, String userId, String accountId) {}
}
