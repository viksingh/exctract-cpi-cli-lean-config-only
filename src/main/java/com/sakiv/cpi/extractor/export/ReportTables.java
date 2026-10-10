package com.sakiv.cpi.extractor.export;

import com.sakiv.cpi.extractor.model.Configuration;
import com.sakiv.cpi.extractor.model.ExtractionResult;
import com.sakiv.cpi.extractor.model.IFlowAdapter;
import com.sakiv.cpi.extractor.model.IFlowContent;
import com.sakiv.cpi.extractor.model.IFlowMapping;
import com.sakiv.cpi.extractor.model.IntegrationFlow;
import com.sakiv.cpi.extractor.model.IntegrationPackage;
import com.sakiv.cpi.extractor.model.RuntimeArtifact;
import com.sakiv.cpi.extractor.model.ScriptInfo;
import com.sakiv.cpi.extractor.model.ValueMapping;
import com.sakiv.cpi.extractor.service.CpiHttpClient;
import com.sakiv.cpi.extractor.util.CpiDateUtil;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an {@link ExtractionResult} into flat tables. The Excel exporter writes one
 * sheet per table and the CSV exporter one file per table, so both formats always
 * carry the same content.
 */
public final class ReportTables {

    /**
     * @param sheetName  Excel sheet name
     * @param fileSuffix suffix for the CSV file name
     * @param headers    column headers
     * @param rows       cell values; each cell is a String or a Number
     */
    public record Table(String sheetName, String fileSuffix, String[] headers, List<Object[]> rows) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(.+?)\\}\\}");

    private ReportTables() {}

    /** File name (without extension) shared by all export formats. */
    public static String baseName(ExtractionResult result, String prefix, boolean withTimestamp) {
        if (!withTimestamp) return prefix;
        return prefix + "_" + result.getExtractedAt().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    public static List<Table> build(ExtractionResult result) {
        List<Table> tables = new ArrayList<>();
        Map<String, String> pkgNames = packageNames(result);
        List<IntegrationFlow> bundled = result.getAllFlows().stream()
                .filter(f -> f.isBundleParsed() && f.getIflowContent() != null)
                .toList();

        tables.add(packages(result));
        tables.add(flows(result));
        if (!result.getAllValueMappings().isEmpty()) tables.add(valueMappings(result));
        tables.add(configurations(result));
        if (!result.getRuntimeArtifacts().isEmpty()) tables.add(runtime(result));
        if (!bundled.isEmpty()) {
            tables.add(adapters(bundled));
            tables.add(mappings(bundled));
            tables.add(scripts(bundled));
            tables.add(eccEndpoints(bundled, pkgNames));
            tables.add(flowChains(bundled, pkgNames));
            tables.add(credentials(bundled, pkgNames));
        }
        if (!result.getApiCallLog().isEmpty()) tables.add(apiCalls(result));
        return tables;
    }

    // =========================================================================
    // Design-time artifacts
    // =========================================================================

    private static Table packages(ExtractionResult result) {
        String[] headers = {
                "Package ID", "Name", "Description", "Version", "Vendor", "Mode",
                "Created By", "Creation Date", "Modified By", "Modified Date",
                "Products", "Keywords", "# Flows", "# Value Mappings"
        };
        List<Object[]> rows = new ArrayList<>();
        for (IntegrationPackage pkg : result.getPackages()) {
            rows.add(new Object[]{
                    pkg.getId(), pkg.getName(), pkg.getDescription(), pkg.getVersion(),
                    pkg.getVendor(), pkg.getMode(), pkg.getCreatedBy(), date(pkg.getCreationDate()),
                    pkg.getModifiedBy(), date(pkg.getModifiedDate()), pkg.getProducts(), pkg.getKeywords(),
                    pkg.getIntegrationFlows().size(), pkg.getValueMappings().size()});
        }
        return new Table("Packages", "packages", headers, rows);
    }

    private static Table flows(ExtractionResult result) {
        String[] headers = {
                "Flow ID", "Name", "Description", "Package ID", "Version",
                "Sender", "Receiver", "Created By", "Created At",
                "Modified By", "Modified At", "Runtime Status",
                "Deployed Version", "Deployed By", "Deployed At", "Error Info"
        };
        List<Object[]> rows = new ArrayList<>();
        for (IntegrationFlow flow : result.getAllFlows()) {
            rows.add(new Object[]{
                    flow.getId(), flow.getName(), flow.getDescription(), flow.getPackageId(),
                    flow.getVersion(), flow.getSender(), flow.getReceiver(),
                    flow.getCreatedBy(), date(flow.getCreatedAt()),
                    flow.getModifiedBy(), date(flow.getModifiedAt()), flow.getRuntimeStatus(),
                    flow.getDeployedVersion(), flow.getDeployedBy(), date(flow.getDeployedAt()),
                    flow.getRuntimeError()});
        }
        return new Table("Integration Flows", "flows", headers, rows);
    }

    private static Table valueMappings(ExtractionResult result) {
        String[] headers = {
                "ID", "Name", "Description", "Package ID", "Version",
                "Created By", "Created At", "Modified By", "Modified At", "Runtime Status"
        };
        List<Object[]> rows = new ArrayList<>();
        for (ValueMapping vm : result.getAllValueMappings()) {
            rows.add(new Object[]{
                    vm.getId(), vm.getName(), vm.getDescription(), vm.getPackageId(), vm.getVersion(),
                    vm.getCreatedBy(), date(vm.getCreatedAt()), vm.getModifiedBy(), date(vm.getModifiedAt()),
                    vm.getRuntimeStatus()});
        }
        return new Table("Value Mappings", "valuemappings", headers, rows);
    }

    /** Externalized parameters as maintained in the design-time artifact (not the deployed values). */
    private static Table configurations(ExtractionResult result) {
        String[] headers = {"Artifact ID", "Artifact Name", "Parameter Key", "Parameter Value", "Data Type"};
        List<Object[]> rows = new ArrayList<>();
        for (IntegrationFlow flow : result.getAllFlows()) {
            for (Configuration cfg : flow.getConfigurations()) {
                rows.add(new Object[]{
                        flow.getId(), flow.getName(), cfg.getParameterKey(),
                        cfg.getParameterValue(), cfg.getDataType()});
            }
        }
        return new Table("Configurations", "configurations", headers, rows);
    }

    private static Table runtime(ExtractionResult result) {
        String[] headers = {
                "Artifact ID", "Name", "Type", "Version", "Status",
                "Deployed By", "Deployed On", "Error Information"
        };
        List<Object[]> rows = new ArrayList<>();
        for (RuntimeArtifact rt : result.getRuntimeArtifacts()) {
            rows.add(new Object[]{
                    rt.getId(), rt.getName(), rt.getType(), rt.getVersion(), rt.getStatus(),
                    rt.getDeployedBy(), date(rt.getDeployedOn()), rt.getErrorInformation()});
        }
        return new Table("Runtime Status", "runtime", headers, rows);
    }

    // =========================================================================
    // iFlow bundle content
    // =========================================================================

    private static Table adapters(List<IntegrationFlow> bundled) {
        String[] headers = {
                "Flow ID", "Flow Name", "Adapter ID", "Adapter Name",
                "Direction", "Adapter Type", "Transport Protocol", "Message Protocol", "Address"
        };
        List<Object[]> rows = new ArrayList<>();
        for (IntegrationFlow flow : bundled) {
            for (IFlowAdapter adapter : flow.getIflowContent().getAdapters()) {
                rows.add(new Object[]{
                        flow.getId(), flow.getName(), adapter.getId(), adapter.getName(),
                        adapter.getDirection(), adapter.getAdapterType(), adapter.getTransportProtocol(),
                        adapter.getMessageProtocol(), adapter.getAddress()});
            }
        }
        return new Table("iFlow Adapters", "adapters", headers, rows);
    }

    private static Table mappings(List<IntegrationFlow> bundled) {
        String[] headers = {"Flow ID", "Flow Name", "Mapping ID", "Mapping Name", "Mapping Type", "Resource ID"};
        List<Object[]> rows = new ArrayList<>();
        for (IntegrationFlow flow : bundled) {
            IFlowContent content = flow.getIflowContent();
            for (IFlowMapping mapping : content.getMappings()) {
                rows.add(new Object[]{
                        flow.getId(), flow.getName(), mapping.getId(), mapping.getName(),
                        mapping.getMappingType(), mapping.getResourceId()});
            }
            // Also list mapping files (mmap, xsl) found in the bundle
            for (String file : content.getMappingFiles()) {
                rows.add(new Object[]{flow.getId(), flow.getName(), "", file, "File", ""});
            }
        }
        return new Table("iFlow Mappings", "mappings", headers, rows);
    }

    private static Table scripts(List<IntegrationFlow> bundled) {
        String[] headers = {"Flow ID", "Flow Name", "Script File", "Language", "Script Content (Snippet)"};
        List<Object[]> rows = new ArrayList<>();
        for (IntegrationFlow flow : bundled) {
            for (ScriptInfo script : flow.getIflowContent().getScripts()) {
                rows.add(new Object[]{
                        flow.getId(), flow.getName(), script.getFileName(),
                        script.getLanguage(), script.getContentSnippet()});
            }
        }
        return new Table("iFlow Scripts", "scripts", headers, rows);
    }

    private static Table eccEndpoints(List<IntegrationFlow> bundled, Map<String, String> pkgNames) {
        String[] headers = {
                "Package", "iFlow", "Direction", "Adapter Type", "Transport Protocol",
                "Message Protocol", "Address", "Category"
        };
        List<Object[]> rows = new ArrayList<>();
        for (IntegrationFlow flow : bundled) {
            for (IFlowAdapter adapter : flow.getIflowContent().getAdapters()) {
                String type = str(adapter.getAdapterType());
                String proto = str(adapter.getTransportProtocol());
                String msgProto = str(adapter.getMessageProtocol());
                rows.add(new Object[]{
                        packageOf(flow, pkgNames), displayName(flow), adapter.getDirection(), type,
                        proto, msgProto, adapter.getAddress(), classifyEndpoint(type, proto, msgProto)});
            }
        }
        return new Table("ECC Endpoints", "ecc_endpoints", headers, rows);
    }

    /** iFlows linked through a shared JMS queue or ProcessDirect address. */
    private static Table flowChains(List<IntegrationFlow> bundled, Map<String, String> pkgNames) {
        String[] headers = {
                "Chain Type", "Queue / Address", "Sender iFlow", "Sender Package", "Sender Runtime",
                "Receiver iFlow", "Receiver Package", "Receiver Runtime"
        };
        // address -> flows writing to it (receiver adapter) / reading from it (sender adapter)
        Map<String, List<IntegrationFlow>> jmsProducers = new LinkedHashMap<>();
        Map<String, List<IntegrationFlow>> jmsConsumers = new LinkedHashMap<>();
        Map<String, List<IntegrationFlow>> pdProducers = new LinkedHashMap<>();
        Map<String, List<IntegrationFlow>> pdConsumers = new LinkedHashMap<>();

        for (IntegrationFlow flow : bundled) {
            for (IFlowAdapter adapter : flow.getIflowContent().getAdapters()) {
                String type = str(adapter.getAdapterType()).toLowerCase();
                boolean jms = type.contains("jms");
                boolean pd = type.contains("processdirect");
                if (!jms && !pd) continue;

                String address = resolveExternalizedParams(
                        resolveChainAddress(adapter, type), flow.getConfigurations());
                if (address.isEmpty()) continue;

                Map<String, List<IntegrationFlow>> target;
                if ("Receiver".equalsIgnoreCase(adapter.getDirection())) {
                    target = jms ? jmsProducers : pdProducers;
                } else if ("Sender".equalsIgnoreCase(adapter.getDirection())) {
                    target = jms ? jmsConsumers : pdConsumers;
                } else {
                    continue;
                }
                target.computeIfAbsent(address, k -> new ArrayList<>()).add(flow);
            }
        }

        List<Object[]> rows = new ArrayList<>();
        addChainRows(rows, "JMS", jmsProducers, jmsConsumers, pkgNames);
        addChainRows(rows, "ProcessDirect", pdProducers, pdConsumers, pkgNames);
        return new Table("Flow Chains", "flow_chains", headers, rows);
    }

    private static void addChainRows(List<Object[]> rows, String chainType,
                                     Map<String, List<IntegrationFlow>> producers,
                                     Map<String, List<IntegrationFlow>> consumers,
                                     Map<String, String> pkgNames) {
        for (var entry : producers.entrySet()) {
            String address = entry.getKey();
            List<IntegrationFlow> cons = consumers.getOrDefault(address, List.of());
            for (IntegrationFlow prod : entry.getValue()) {
                if (cons.isEmpty()) {
                    rows.add(new Object[]{chainType, address,
                            displayName(prod), packageOf(prod, pkgNames), runtimeOf(prod),
                            "(no consumer found)", "", ""});
                }
                for (IntegrationFlow con : cons) {
                    rows.add(new Object[]{chainType, address,
                            displayName(prod), packageOf(prod, pkgNames), runtimeOf(prod),
                            displayName(con), packageOf(con, pkgNames), runtimeOf(con)});
                }
            }
        }
        for (var entry : consumers.entrySet()) {
            if (producers.containsKey(entry.getKey())) continue;
            for (IntegrationFlow con : entry.getValue()) {
                rows.add(new Object[]{chainType, entry.getKey(),
                        "(no producer found)", "", "",
                        displayName(con), packageOf(con, pkgNames), runtimeOf(con)});
            }
        }
    }

    /** Credential / keystore / security-material references found in adapter and process properties. */
    private static Table credentials(List<IntegrationFlow> bundled, Map<String, String> pkgNames) {
        String[] headers = {
                "Package", "iFlow", "Adapter Type", "Direction",
                "Credential Name", "Credential Type", "Property Key", "Context"
        };
        List<Object[]> rows = new ArrayList<>();
        for (IntegrationFlow flow : bundled) {
            String pkg = packageOf(flow, pkgNames);
            String name = displayName(flow);
            for (IFlowAdapter adapter : flow.getIflowContent().getAdapters()) {
                String adapterType = str(adapter.getAdapterType());
                for (var entry : adapter.getProperties().entrySet()) {
                    if (isBlank(entry.getValue()) || !isCredentialProperty(entry.getKey())) continue;
                    rows.add(new Object[]{pkg, name, adapterType, str(adapter.getDirection()),
                            entry.getValue(), classifyCredentialType(entry.getKey()),
                            entry.getKey(), "Adapter: " + adapterType});
                }
            }
            for (var entry : flow.getIflowContent().getProcessProperties().entrySet()) {
                if (isBlank(entry.getValue()) || !isCredentialProperty(entry.getKey())) continue;
                rows.add(new Object[]{pkg, name, "", "",
                        entry.getValue(), classifyCredentialType(entry.getKey()),
                        entry.getKey(), "Process Property"});
            }
        }
        return new Table("Credentials", "credentials", headers, rows);
    }

    private static Table apiCalls(ExtractionResult result) {
        String[] headers = {"#", "Method", "URL / Path", "Status Code", "Duration (ms)"};
        List<Object[]> rows = new ArrayList<>();
        int seq = 1;
        for (CpiHttpClient.ApiCallRecord call : result.getApiCallLog()) {
            rows.add(new Object[]{seq++, call.method(), call.path(), call.statusCode(), call.durationMs()});
        }
        return new Table("API Calls", "apicalls", headers, rows);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Map<String, String> packageNames(ExtractionResult result) {
        Map<String, String> names = new LinkedHashMap<>();
        for (IntegrationPackage pkg : result.getPackages()) {
            names.put(pkg.getId(), pkg.getName() != null ? pkg.getName() : pkg.getId());
        }
        return names;
    }

    private static String packageOf(IntegrationFlow flow, Map<String, String> pkgNames) {
        return flow.getPackageId() == null ? "" : pkgNames.getOrDefault(flow.getPackageId(), "");
    }

    private static String displayName(IntegrationFlow flow) {
        return flow.getName() != null ? flow.getName() : str(flow.getId());
    }

    private static String runtimeOf(IntegrationFlow flow) {
        return flow.getRuntimeStatus() != null ? flow.getRuntimeStatus() : "UNKNOWN";
    }

    private static String date(String cpiDate) {
        return CpiDateUtil.formatCpiDate(cpiDate);
    }

    private static String str(String value) {
        return value != null ? value : "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final Set<String> CREDENTIAL_KEYS = Set.of(
            "credential_name", "credentialname", "credential.name",
            "private.key.alias", "privatekeyalias",
            "public.key.alias", "publickeyalias",
            "certificate.alias", "certificatealias",
            "senderauthcredential", "sender.auth.credential",
            "receiverauthcredential", "receiver.auth.credential",
            "proxyuser", "proxy.user",
            "securitymaterial", "security.material",
            "pgp.secret.keyring.alias", "pgpsecretkeyringalias",
            "pgp.public.keyring.alias", "pgppublickeyringalias"
    );

    static boolean isCredentialProperty(String key) {
        String lower = key.toLowerCase();
        if (CREDENTIAL_KEYS.contains(lower)) return true;
        return lower.contains("credential") || lower.contains("keystore")
                || lower.contains("certificate") || lower.contains("alias")
                || lower.contains("secret.key") || lower.contains("pgp");
    }

    static String classifyCredentialType(String propertyKey) {
        String lower = propertyKey.toLowerCase();
        if (lower.contains("oauth")) return "OAuth2";
        if (lower.contains("saml")) return "SAML";
        if (lower.contains("pgp")) return "PGP";
        if (lower.contains("keystore") || lower.contains("key.alias")
                || lower.contains("keyalias") || lower.contains("certificate")) return "Keystore";
        if (lower.contains("credential")) return "Credential";
        return "Security Material";
    }

    static String classifyEndpoint(String adapterType, String transportProtocol, String messageProtocol) {
        String type = adapterType.toLowerCase();
        String proto = transportProtocol.toLowerCase();
        String msgProto = messageProtocol.toLowerCase();

        if (type.contains("idoc") || msgProto.contains("idoc")) return "ECC (IDoc)";
        if (type.contains("rfc") || proto.contains("rfc")) return "ECC (RFC/BAPI)";
        if (type.contains("xi") || type.contains("soap") && msgProto.contains("xi")) return "ECC (XI/SOAP)";
        if (type.contains("as2")) return "Legacy (AS2)";
        if (type.contains("odata") || proto.contains("odata")) return "S/4 Compatible (OData)";
        if (type.contains("http") || type.contains("rest")) return "S/4 Compatible (HTTP/REST)";
        if (type.contains("soap")) return "Neutral (SOAP)";
        if (type.contains("jms")) return "Middleware (JMS)";
        if (type.contains("processdirect")) return "Internal (ProcessDirect)";
        if (type.contains("sftp") || type.contains("ftp")) return "Neutral (SFTP/FTP)";
        if (type.contains("mail") || type.contains("smtp") || type.contains("imap")) return "Neutral (Mail)";
        if (type.contains("kafka")) return "Neutral (Kafka)";
        if (type.contains("amqp")) return "Neutral (AMQP)";
        return "Other (" + adapterType + ")";
    }

    /** Queue name (JMS) or address (ProcessDirect) that links two iFlows. */
    static String resolveChainAddress(IFlowAdapter adapter, String typeLower) {
        Map<String, String> props = adapter.getProperties();
        String dir = str(adapter.getDirection());
        if (typeLower.contains("jms")) {
            for (String key : List.of(
                    "Receiver".equalsIgnoreCase(dir) ? "QueueName_outbound" : "QueueName_inbound",
                    "QueueName_outbound", "QueueName_inbound",
                    "Destination", "QueueName", "destination", "queueName")) {
                String val = props.get(key);
                if (!isBlank(val)) return val;
            }
        }
        if (typeLower.contains("processdirect")) {
            for (String key : List.of("address", "Address", "ProcessDirectAddress")) {
                String val = props.get(key);
                if (!isBlank(val)) return val;
            }
        }
        if (!isBlank(adapter.getAddress())) return adapter.getAddress();
        for (var entry : props.entrySet()) {
            String key = entry.getKey().toLowerCase();
            if ((key.contains("destination") || key.contains("queue") || key.contains("address"))
                    && !isBlank(entry.getValue())) {
                return entry.getValue();
            }
        }
        return "";
    }

    /** Resolve {{paramName}} placeholders using the flow's design-time externalized configurations. */
    static String resolveExternalizedParams(String address, List<Configuration> configs) {
        if (address == null || !address.contains("{{") || configs == null) return address;

        Map<String, String> configMap = new LinkedHashMap<>();
        for (Configuration cfg : configs) {
            if (cfg.getParameterKey() != null && cfg.getParameterValue() != null) {
                configMap.put(cfg.getParameterKey(), cfg.getParameterValue());
            }
        }

        String resolved = address;
        Matcher matcher = PLACEHOLDER.matcher(address);
        while (matcher.find()) {
            String paramName = matcher.group(1);
            String value = configMap.get(paramName);
            if (value != null && !value.isBlank()) {
                resolved = resolved.replace("{{" + paramName + "}}", value);
            }
        }
        return resolved;
    }
}
