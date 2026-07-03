package com.messaging.channel;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.messaging.channel.UnipileClient.HostedAuthRequest;
import com.messaging.web.AuthenticatedUser;

@RestController
public class ChannelController {

    private final ChannelRepository channelRepository;
    private final UnipileClient unipileClient;
    private final String backendBaseUrl;
    private final String frontendBaseUrl;
    private final String webhookSecret;

    public ChannelController(ChannelRepository channelRepository,
                             UnipileClient unipileClient,
                             @Value("${app.backend-base-url}") String backendBaseUrl,
                             @Value("${app.frontend-base-url}") String frontendBaseUrl,
                             @Value("${unipile.webhook-secret}") String webhookSecret) {
        this.channelRepository = channelRepository;
        this.unipileClient = unipileClient;
        this.backendBaseUrl = backendBaseUrl;
        this.frontendBaseUrl = frontendBaseUrl;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/api/channels/connect")
    @Transactional
    public Map<String, String> connect(@AuthenticationPrincipal AuthenticatedUser user,
                                       @RequestBody ConnectRequest request) {
        Channel channel = new Channel(user.tenantId(), request.type());
        channelRepository.save(channel);

        String expiresOn = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        String notifyUrl = backendBaseUrl + "/api/unipile/notify?secret=" + webhookSecret;

        HostedAuthRequest authRequest = HostedAuthRequest.create(
                "create",
                List.of(request.type().toUpperCase()),
                expiresOn,
                user.tenantId().toString(),
                frontendBaseUrl + "/channels?status=success",
                frontendBaseUrl + "/channels?status=failure",
                notifyUrl
        );

        String url = unipileClient.createHostedAuthLink(authRequest);
        return Map.of("url", url);
    }

    @GetMapping("/api/channels")
    @Transactional(readOnly = true)
    public List<ChannelResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return channelRepository.findByTenantId(user.tenantId()).stream()
                .map(ChannelResponse::from)
                .toList();
    }

    @PostMapping("/api/channels/{id}/reconnect")
    @Transactional
    public Map<String, String> reconnect(@AuthenticationPrincipal AuthenticatedUser user,
                                          @PathVariable UUID id) {
        Channel channel = channelRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (!channel.getTenantId().equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!"error".equals(channel.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel is not in error state");
        }

        String expiresOn = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        String notifyUrl = backendBaseUrl + "/api/unipile/notify?secret=" + webhookSecret;

        HostedAuthRequest authRequest = HostedAuthRequest.reconnect(
                expiresOn,
                user.tenantId().toString(),
                frontendBaseUrl + "/channels?status=success",
                frontendBaseUrl + "/channels?status=failure",
                notifyUrl,
                channel.getExternalAccountId()
        );

        String url = unipileClient.createHostedAuthLink(authRequest);
        return Map.of("url", url);
    }

    record ConnectRequest(String type) {}

    record ChannelResponse(UUID id, String type, String provider, String status,
                           String externalAccountId, Instant connectedAt, Instant lastStatusAt) {
        static ChannelResponse from(Channel c) {
            return new ChannelResponse(c.getId(), c.getType(), c.getProvider(), c.getStatus(),
                    c.getExternalAccountId(), c.getConnectedAt(), c.getLastStatusAt());
        }
    }
}
