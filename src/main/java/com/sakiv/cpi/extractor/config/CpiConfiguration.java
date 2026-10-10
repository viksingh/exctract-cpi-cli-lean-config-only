package com.sakiv.cpi.extractor.config;

import com.sakiv.cpi.extractor.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads and manages application configuration.
 *
 * Loading order (later wins):
 *   1. Built-in defaults
 *   2. External config file (optional)
 *   3. Environment variables (CPI_BASE_URL, CPI_OAUTH_CLIENT_ID, ...)
 *   4. Command-line overrides applied through {@link #set(String, String)}
 */
public class CpiConfiguration {

    private static final Log log = Log.get(CpiConfiguration.class);
    private final Properties properties = new Properties();
    private final String configSource;

    /** @param configFilePath path to a properties file, or null to use defaults + environment only */
    public CpiConfiguration(String configFilePath) {
        loadDefaults();
        if (configFilePath != null) {
            loadFromFile(configFilePath);
        }
        this.configSource = configFilePath != null ? configFilePath : "environment / defaults";
        overrideFromEnvironment();
    }

    private void loadDefaults() {
        properties.setProperty("cpi.auth.type", "oauth2");
        properties.setProperty("cpi.api.packages", "/api/v1/IntegrationPackages");
        properties.setProperty("cpi.api.runtime", "/api/v1/IntegrationRuntimeArtifacts");
        properties.setProperty("http.connect.timeout.ms", "30000");
        properties.setProperty("http.read.timeout.ms", "60000");
        properties.setProperty("http.max.retries", "3");
        properties.setProperty("http.retry.delay.ms", "2000");
        properties.setProperty("export.format", "xlsx");
        properties.setProperty("export.output.dir", "./output");
        properties.setProperty("export.filename.prefix", "cpi_artifacts");
        properties.setProperty("export.filename.timestamp", "true");
        properties.setProperty("extract.flows", "true");
        properties.setProperty("extract.valuemappings", "true");
        properties.setProperty("extract.configurations", "true");
        properties.setProperty("extract.runtime.status", "true");
        properties.setProperty("extract.bundles", "true");
        properties.setProperty("api.parallel.threads", "4");
    }

    private void loadFromFile(String filePath) {
        Path path = Path.of(filePath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Config file not found: " + filePath);
        }
        try (InputStream is = Files.newInputStream(path)) {
            properties.load(is);
            log.debug("Loaded configuration from: {}", filePath);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load config from " + filePath + ": " + e.getMessage(), e);
        }
    }

    /** Environment variables override the config file. Convention: CPI_BASE_URL maps to cpi.base.url */
    private void overrideFromEnvironment() {
        mapEnvToProperty("CPI_BASE_URL", "cpi.base.url");
        mapEnvToProperty("CPI_AUTH_TYPE", "cpi.auth.type");
        mapEnvToProperty("CPI_OAUTH_TOKEN_URL", "cpi.oauth.token.url");
        mapEnvToProperty("CPI_OAUTH_CLIENT_ID", "cpi.oauth.client.id");
        mapEnvToProperty("CPI_OAUTH_CLIENT_SECRET", "cpi.oauth.client.secret");
        mapEnvToProperty("CPI_BASIC_USERNAME", "cpi.basic.username");
        mapEnvToProperty("CPI_BASIC_PASSWORD", "cpi.basic.password");
    }

    private void mapEnvToProperty(String envVar, String propKey) {
        String value = System.getenv(envVar);
        if (value != null && !value.isBlank()) {
            properties.setProperty(propKey, value);
            log.debug("Override from env: {} -> {}", envVar, propKey);
        }
    }

    // --- Accessors ---

    /** Override a single property (used for command-line options). */
    public void set(String key, String value) {
        properties.setProperty(key, value);
    }

    public String get(String key) {
        String value = properties.getProperty(key);
        return value != null ? value.trim() : null;
    }

    public String get(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = get(key);
        return val != null ? Boolean.parseBoolean(val) : defaultValue;
    }

    // --- Convenience Accessors ---

    /** Tenant base URL without a trailing slash. */
    public String getBaseUrl() {
        String url = get("cpi.base.url");
        if (url == null) return null;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    public String getAuthType() { return get("cpi.auth.type", "oauth2").toLowerCase(); }
    public String getOAuthTokenUrl() { return get("cpi.oauth.token.url"); }
    public String getOAuthClientId() { return get("cpi.oauth.client.id"); }
    public String getOAuthClientSecret() { return get("cpi.oauth.client.secret"); }
    public String getBasicUsername() { return get("cpi.basic.username"); }
    public String getBasicPassword() { return get("cpi.basic.password"); }
    public String getExportFormat() { return get("export.format", "xlsx"); }
    public String getOutputDir() { return get("export.output.dir", "./output"); }
    public String getFilenamePrefix() { return get("export.filename.prefix", "cpi_artifacts"); }
    public String getConfigSource() { return configSource; }

    public void validate() {
        String hint = " - set it in the config file or via environment variables"
                + " (see config.properties.template).";

        if (getBaseUrl() == null || getBaseUrl().isBlank()) {
            throw new IllegalStateException("cpi.base.url must be configured" + hint);
        }
        switch (getAuthType()) {
            case "oauth2" -> {
                requireNonBlank("cpi.oauth.token.url", "OAuth token URL", hint);
                requireNonBlank("cpi.oauth.client.id", "OAuth client ID", hint);
                requireNonBlank("cpi.oauth.client.secret", "OAuth client secret", hint);
            }
            case "basic" -> {
                requireNonBlank("cpi.basic.username", "Basic auth username", hint);
                requireNonBlank("cpi.basic.password", "Basic auth password", hint);
            }
            default -> throw new IllegalStateException(
                    "Unknown cpi.auth.type '" + getAuthType() + "' - use oauth2 or basic.");
        }
    }

    private void requireNonBlank(String key, String label, String hint) {
        if (get(key) == null || get(key).isBlank()) {
            throw new IllegalStateException(label + " (" + key + ") must be configured" + hint);
        }
    }
}
