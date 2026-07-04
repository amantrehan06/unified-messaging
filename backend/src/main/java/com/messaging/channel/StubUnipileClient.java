package com.messaging.channel;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub Unipile client for local testing without real credentials.
 * Logs every call and returns a fake provider message ID.
 * Activated automatically when unipile.dsn is not set.
 */
public class StubUnipileClient implements UnipileClient {

    private static final Logger log = LoggerFactory.getLogger(StubUnipileClient.class);

    @Override
    public String createHostedAuthLink(HostedAuthRequest request) {
        log.info("[STUB] createHostedAuthLink: type={}, providers={}, name={}",
                request.type(), request.providers(), request.name());
        return "https://stub.unipile.local/hosted-auth/" + UUID.randomUUID();
    }

    @Override
    public String sendMessage(String accountId, String to, String body) {
        String fakeId = "stub-msg-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[STUB] sendMessage: accountId={}, to={}, body='{}' -> providerMessageId={}",
                accountId, to, body, fakeId);
        return fakeId;
    }
}
