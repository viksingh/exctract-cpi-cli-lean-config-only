package com.sakiv.cpi.extractor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakiv.cpi.extractor.config.CpiConfiguration;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import com.sakiv.cpi.extractor.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * HTTP client for SAP CPI OData API calls.
 * Supports OAuth2 Client Credentials and Basic Authentication.
 */
public class CpiHttpClient implements Closeable {

    private static final Log log = Log.get(CpiHttpClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final CpiConfiguration config;
    private final CloseableHttpClient httpClient;
    private final int maxRetries;
    private final long retryDelayMs;

    // OAuth2 token cache
    private String accessToken;
    private Instant tokenExpiry;

    // API call log (written from the extraction worker threads)
    private final List<ApiCallRecord> apiCallLog = Collections.synchronizedList(new ArrayList<>());

    public record ApiCallRecord(String method, String path, int statusCode, long durationMs) {}

    public CpiHttpClient(CpiConfiguration config) {
        this.config = config;
        this.maxRetries = Math.max(1, config.getInt("http.max.retries", 3));
        this.retryDelayMs = config.getInt("http.retry.delay.ms", 2000);
        int threads = Math.max(1, config.getInt("api.parallel.threads", 4));

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(config.getInt("http.connect.timeout.ms", 30000))
                .setSocketTimeout(config.getInt("http.read.timeout.ms", 60000))
                .build();

        // useSystemProperties: honours -Dhttps.proxyHost / -Dhttps.proxyPort etc.
        // Connection limits match the worker pool so parallel calls are not queued
        // behind HttpClient's default of 2 connections per host.
        this.httpClient = HttpClients.custom()
                .useSystemProperties()
                .setDefaultRequestConfig(requestConfig)
                .setMaxConnPerRoute(threads)
                .setMaxConnTotal(threads * 2)
                .build();
    }

    /**
     * Execute GET request against the CPI OData API and return the body as a string.
     *
     * @param urlOrPath either a relative API path or a full URL (for pagination)
     */
    public String get(String urlOrPath) throws IOException {
        return new String(execute(urlOrPath, "application/json"), StandardCharsets.UTF_8);
    }

    /**
     * Execute GET request and return the raw response body.
     * Used for downloading iFlow ZIP bundles via the $value endpoint.
     */
    public byte[] getBytes(String urlOrPath) throws IOException {
        return execute(urlOrPath, "application/zip, application/octet-stream, */*");
    }

    private byte[] execute(String urlOrPath, String accept) throws IOException {
        String fullUrl = urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")
                ? urlOrPath
                : config.getBaseUrl() + urlOrPath;
        log.debug("GET {}", fullUrl);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            long startTime = System.currentTimeMillis();
            try {
                HttpGet request = new HttpGet(fullUrl);
                request.setHeader(HttpHeaders.ACCEPT, accept);
                request.setHeader(HttpHeaders.AUTHORIZATION, getAuthHeader());

                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int statusCode = response.getStatusLine().getStatusCode();
                    HttpEntity entity = response.getEntity();
                    byte[] body = entity != null ? EntityUtils.toByteArray(entity) : new byte[0];
                    long durationMs = System.currentTimeMillis() - startTime;

                    apiCallLog.add(new ApiCallRecord("GET", urlOrPath, statusCode, durationMs));

                    if (statusCode == 200) {
                        return body;
                    } else if (statusCode == 401 && attempt < maxRetries) {
                        log.warn("Got 401, refreshing token and retrying (attempt {}/{})", attempt, maxRetries);
                        invalidateToken();
                    } else if (statusCode == 429 && attempt < maxRetries) {
                        log.warn("Rate limited (429), waiting before retry (attempt {}/{})", attempt, maxRetries);
                        Thread.sleep(retryDelayMs * attempt);
                    } else if (statusCode >= 500 && attempt < maxRetries) {
                        log.warn("Server error {}, retrying (attempt {}/{})", statusCode, attempt, maxRetries);
                        Thread.sleep(retryDelayMs);
                    } else {
                        throw new IOException(String.format("HTTP %d from %s: %s", statusCode, urlOrPath,
                                abbreviate(new String(body, StandardCharsets.UTF_8))));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Request interrupted", e);
            }
        }
        throw new IOException("Max retries exceeded for: " + urlOrPath);
    }

    private static String abbreviate(String body) {
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "..." : oneLine;
    }

    /** Get the authorization header based on the configured auth type. */
    private String getAuthHeader() throws IOException {
        return switch (config.getAuthType()) {
            case "oauth2" -> "Bearer " + getOAuth2Token();
            case "basic" -> "Basic " + Base64.getEncoder().encodeToString(
                    (config.getBasicUsername() + ":" + config.getBasicPassword())
                            .getBytes(StandardCharsets.UTF_8));
            default -> throw new IllegalStateException("Unknown auth type: " + config.getAuthType());
        };
    }

    /** Obtain or reuse an OAuth2 access token via Client Credentials grant. */
    private synchronized String getOAuth2Token() throws IOException {
        if (accessToken != null && tokenExpiry != null && Instant.now().isBefore(tokenExpiry)) {
            return accessToken;
        }

        log.info("Requesting OAuth2 access token from {}", config.getOAuthTokenUrl());

        HttpPost tokenRequest = new HttpPost(config.getOAuthTokenUrl());
        tokenRequest.setHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");

        // Client credentials in Authorization header
        String credentials = config.getOAuthClientId() + ":" + config.getOAuthClientSecret();
        tokenRequest.setHeader(HttpHeaders.AUTHORIZATION,
                "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));

        tokenRequest.setEntity(new StringEntity("grant_type=client_credentials"));

        long startTime = System.currentTimeMillis();
        try (CloseableHttpResponse response = httpClient.execute(tokenRequest)) {
            int statusCode = response.getStatusLine().getStatusCode();
            long durationMs = System.currentTimeMillis() - startTime;
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            apiCallLog.add(new ApiCallRecord("POST", config.getOAuthTokenUrl(), statusCode, durationMs));

            if (statusCode != 200) {
                throw new IOException("OAuth2 token request failed with HTTP " + statusCode + ": " + abbreviate(body));
            }

            JsonNode json = mapper.readTree(body);
            if (json == null || !json.hasNonNull("access_token")) {
                throw new IOException("OAuth2 token response did not contain an access_token");
            }
            accessToken = json.get("access_token").asText();
            int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt() : 3600;
            // Refresh 60 seconds before actual expiry
            tokenExpiry = Instant.now().plusSeconds(Math.max(expiresIn - 60, 30));

            log.debug("OAuth2 token obtained, expires in {} seconds", expiresIn);
            return accessToken;
        }
    }

    private synchronized void invalidateToken() {
        accessToken = null;
        tokenExpiry = null;
    }

    /** Snapshot of all API calls made so far. */
    public List<ApiCallRecord> getApiCallLog() {
        synchronized (apiCallLog) {
            return new ArrayList<>(apiCallLog);
        }
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }
}
