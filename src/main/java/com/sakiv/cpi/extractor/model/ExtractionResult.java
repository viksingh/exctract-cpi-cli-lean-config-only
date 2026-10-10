package com.sakiv.cpi.extractor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sakiv.cpi.extractor.service.CpiHttpClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Container holding all extracted CPI artifact data.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtractionResult {

    private LocalDateTime extractedAt;
    private String tenantUrl;

    private List<IntegrationPackage> packages = new ArrayList<>();
    private List<IntegrationFlow> allFlows = new ArrayList<>();
    private List<ValueMapping> allValueMappings = new ArrayList<>();
    private List<RuntimeArtifact> runtimeArtifacts = new ArrayList<>();
    private List<CpiHttpClient.ApiCallRecord> apiCallLog = new ArrayList<>();
    private final List<String> warnings = java.util.Collections.synchronizedList(new ArrayList<>());

    private int totalPackages;
    private int totalFlows;
    private int totalValueMappings;
    private int deployedArtifacts;
    private int errorArtifacts;

    /** No-arg constructor for Jackson deserialization. */
    public ExtractionResult() {
        this.extractedAt = LocalDateTime.now();
    }

    public ExtractionResult(String tenantUrl) {
        this.extractedAt = LocalDateTime.now();
        this.tenantUrl = tenantUrl;
    }

    public void computeSummary() {
        totalPackages = packages.size();
        totalFlows = allFlows.size();
        totalValueMappings = allValueMappings.size();
        deployedArtifacts = (int) runtimeArtifacts.stream()
                .filter(r -> "STARTED".equalsIgnoreCase(r.getStatus()))
                .count();
        errorArtifacts = (int) runtimeArtifacts.stream()
                .filter(r -> "ERROR".equalsIgnoreCase(r.getStatus()))
                .count();
    }

    @JsonIgnore
    public String getSummary() {
        computeSummary();
        long configs = allFlows.stream().mapToLong(f -> f.getConfigurations().size()).sum();
        long bundles = allFlows.stream().filter(IntegrationFlow::isBundleParsed).count();
        return String.format("""
                ================================================
                SAP CPI Artifact Extraction Summary
                ================================================
                Tenant:               %s
                Extracted At:         %s
                ------------------------------------------------
                Integration Packages: %d
                Integration Flows:    %d
                Value Mappings:       %d
                Configuration Params: %d
                iFlow Bundles Parsed: %d
                Runtime Artifacts:    %d
                  - STARTED:          %d
                  - ERROR:            %d
                ================================================""",
                tenantUrl, extractedAt.withNano(0),
                totalPackages, totalFlows, totalValueMappings, configs, bundles,
                runtimeArtifacts.size(), deployedArtifacts, errorArtifacts);
    }

    // Getters and Setters

    public LocalDateTime getExtractedAt() { return extractedAt; }
    public void setExtractedAt(LocalDateTime extractedAt) { this.extractedAt = extractedAt; }

    public String getTenantUrl() { return tenantUrl; }
    public void setTenantUrl(String tenantUrl) { this.tenantUrl = tenantUrl; }

    public List<IntegrationPackage> getPackages() { return packages; }
    public void setPackages(List<IntegrationPackage> packages) { this.packages = packages; }

    public List<IntegrationFlow> getAllFlows() { return allFlows; }
    public void setAllFlows(List<IntegrationFlow> allFlows) { this.allFlows = allFlows; }

    public List<ValueMapping> getAllValueMappings() { return allValueMappings; }
    public void setAllValueMappings(List<ValueMapping> allValueMappings) { this.allValueMappings = allValueMappings; }

    public List<RuntimeArtifact> getRuntimeArtifacts() { return runtimeArtifacts; }
    public void setRuntimeArtifacts(List<RuntimeArtifact> runtimeArtifacts) { this.runtimeArtifacts = runtimeArtifacts; }

    public List<CpiHttpClient.ApiCallRecord> getApiCallLog() { return apiCallLog; }
    public void setApiCallLog(List<CpiHttpClient.ApiCallRecord> apiCallLog) { this.apiCallLog = apiCallLog; }

    /** Non-fatal problems hit during extraction (a failed package, bundle, ...). */
    public List<String> getWarnings() { return warnings; }
    public void addWarning(String warning) { warnings.add(warning); }

    public int getTotalPackages() { return totalPackages; }
    public int getTotalFlows() { return totalFlows; }
    public int getTotalValueMappings() { return totalValueMappings; }
    public int getDeployedArtifacts() { return deployedArtifacts; }
    public int getErrorArtifacts() { return errorArtifacts; }
}
