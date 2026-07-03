package com.messaging.channel;

import java.util.List;

/**
 * Boundary interface for Unipile API interactions.
 * Real implementation calls Unipile REST API; tests use WireMock behind this boundary.
 */
public interface UnipileClient {

    /**
     * Creates a hosted auth link for account connection.
     *
     * @param request the link generation parameters
     * @return the hosted auth URL the user should be redirected to
     */
    String createHostedAuthLink(HostedAuthRequest request);

    record HostedAuthRequest(
            String type,
            List<String> providers,
            String expiresOn,
            String name,
            String successRedirectUrl,
            String failureRedirectUrl,
            String notifyUrl,
            String reconnectAccount
    ) {
        public static HostedAuthRequest create(String type, List<String> providers,
                                                String expiresOn, String name,
                                                String successRedirectUrl, String failureRedirectUrl,
                                                String notifyUrl) {
            return new HostedAuthRequest(type, providers, expiresOn, name,
                    successRedirectUrl, failureRedirectUrl, notifyUrl, null);
        }

        public static HostedAuthRequest reconnect(String expiresOn, String name,
                                                    String successRedirectUrl, String failureRedirectUrl,
                                                    String notifyUrl, String reconnectAccount) {
            return new HostedAuthRequest("reconnect", List.of(), expiresOn, name,
                    successRedirectUrl, failureRedirectUrl, notifyUrl, reconnectAccount);
        }
    }
}
