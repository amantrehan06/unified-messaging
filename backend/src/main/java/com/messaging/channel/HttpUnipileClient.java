package com.messaging.channel;

import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public class HttpUnipileClient implements UnipileClient {

    private final RestClient restClient;
    private final String apiUrl;

    public HttpUnipileClient(String baseUrl, String apiKey) {
        this.apiUrl = baseUrl;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(Version.HTTP_1_1).build());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-API-KEY", apiKey)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public String createHostedAuthLink(HostedAuthRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", request.type());
        body.put("api_url", apiUrl);
        body.put("expiresOn", request.expiresOn());
        body.put("name", request.name());

        if (!request.providers().isEmpty()) {
            body.put("providers", request.providers());
        }
        if (request.successRedirectUrl() != null) {
            body.put("success_redirect_url", request.successRedirectUrl());
        }
        if (request.failureRedirectUrl() != null) {
            body.put("failure_redirect_url", request.failureRedirectUrl());
        }
        if (request.notifyUrl() != null) {
            body.put("notify_url", request.notifyUrl());
        }
        if (request.reconnectAccount() != null) {
            body.put("reconnect_account", request.reconnectAccount());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri("/api/v1/hosted/accounts/link")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null || !response.containsKey("url")) {
            throw new RuntimeException("Unipile did not return a hosted auth URL");
        }
        return (String) response.get("url");
    }
}
