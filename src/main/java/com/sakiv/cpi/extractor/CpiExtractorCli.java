package com.sakiv.cpi.extractor;

import com.sakiv.cpi.extractor.config.CpiConfiguration;
import com.sakiv.cpi.extractor.export.CsvExporter;
import com.sakiv.cpi.extractor.export.ExcelExporter;
import com.sakiv.cpi.extractor.export.JsonExporter;
import com.sakiv.cpi.extractor.export.ReportTables;
import com.sakiv.cpi.extractor.model.ExtractionResult;
import com.sakiv.cpi.extractor.model.IntegrationPackage;
import com.sakiv.cpi.extractor.service.CpiApiService;
import com.sakiv.cpi.extractor.service.CpiHttpClient;
import com.sakiv.cpi.extractor.service.ExtractionProgressCallback;
import com.sakiv.cpi.extractor.util.Log;
import com.sakiv.cpi.extractor.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line entry point.
 *
 * stdout carries results only (written file paths, the summary, the package list);
 * all progress and diagnostics go to stderr.
 *
 * Exit codes: 0 success, 1 extraction/export failed, 2 usage or configuration error,
 * 3 completed with warnings (only with --strict).
 */
public final class CpiExtractorCli {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_WARNINGS = 3;

    private static final Set<String> FORMATS = Set.of("xlsx", "csv", "json");

    private static final String USAGE = """
            Usage: cpi-extract [options] [config.properties]

            Extracts SAP Cloud Integration packages, iFlows, value mappings, design-time
            configurations (externalized parameters), iFlow bundle content and deployment
            status, and writes them to Excel, CSV or JSON.

            Connection settings come from a properties file and/or environment variables
            (CPI_BASE_URL, CPI_AUTH_TYPE, CPI_OAUTH_TOKEN_URL, CPI_OAUTH_CLIENT_ID,
            CPI_OAUTH_CLIENT_SECRET, CPI_BASIC_USERNAME, CPI_BASIC_PASSWORD).
            Without -c, ./config.properties is used when it exists.

            Options:
              -c, --config <file>      Properties file (see config.properties.template)
              -f, --format <list>      xlsx, csv, json, all or a comma list (default: xlsx)
              -o, --output <dir>       Output directory (default: ./output)
                  --prefix <name>      Output file name prefix (default: cpi_artifacts)
                  --no-timestamp       Do not append _yyyyMMdd_HHmmss to file names
              -p, --packages <ids>     Only these package IDs (comma-separated)
                  --list-packages      Print package IDs and names, then exit
                  --no-bundles         Skip iFlow bundle download (no adapters, scripts,
                                       endpoints, flow chains or credentials) - much faster
                  --no-configs         Skip externalized configurations
                  --no-runtime         Skip deployment status
                  --no-valuemappings   Skip value mappings
                  --threads <n>        Parallel API calls (default: 4)
                  --set <key=value>    Override any property; may be repeated
                  --strict             Exit with code 3 if anything was skipped
              -q, --quiet              Only warnings and errors on stderr
              -v, --verbose            Debug logging on stderr
              -h, --help               Show this help
              -V, --version            Show version
            """;

    private CpiExtractorCli() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Options opts;
        try {
            opts = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Try 'cpi-extract --help'.");
            return EXIT_USAGE;
        }
        if (opts.help) {
            System.out.print(USAGE);
            return EXIT_OK;
        }
        if (opts.version) {
            String version = CpiExtractorCli.class.getPackage().getImplementationVersion();
            System.out.println("cpi-extract " + (version != null ? version : "dev"));
            return EXIT_OK;
        }

        Log.setLevel(opts.verbose ? Log.Level.DEBUG : opts.quiet ? Log.Level.WARN : Log.Level.INFO);
        Log log = Log.get(CpiExtractorCli.class);

        CpiConfiguration config;
        List<String> formats;
        try {
            String configFile = opts.configFile;
            if (configFile == null && Files.isRegularFile(Path.of("config.properties"))) {
                configFile = "config.properties";
            }
            config = new CpiConfiguration(configFile);
            opts.overrides.forEach(config::set);
            config.validate();
            formats = parseFormats(config.getExportFormat());
            log.info("Tenant: {} (auth: {}, config: {})",
                    config.getBaseUrl(), config.getAuthType(), config.getConfigSource());
        } catch (IllegalStateException | IllegalArgumentException e) {
            System.err.println("Configuration error: " + e.getMessage());
            return EXIT_USAGE;
        }

        try (CpiHttpClient httpClient = new CpiHttpClient(config)) {
            CpiApiService service = new CpiApiService(config, httpClient);

            if (opts.listPackages) {
                for (IntegrationPackage pkg : service.getIntegrationPackages()) {
                    System.out.println(pkg.getId() + "\t" + (pkg.getName() != null ? pkg.getName() : ""));
                }
                return EXIT_OK;
            }

            long start = System.currentTimeMillis();
            ExtractionResult result = service.extractAll(progressLogger(log));
            result.setApiCallLog(httpClient.getApiCallLog());

            String outputDir = config.getOutputDir();
            String baseName = ReportTables.baseName(result, config.getFilenamePrefix(),
                    config.getBoolean("export.filename.timestamp", true));
            List<String> written = new ArrayList<>();
            for (String format : formats) {
                switch (format) {
                    case "xlsx" -> written.add(new ExcelExporter().export(result, outputDir, baseName));
                    case "csv" -> written.addAll(new CsvExporter().export(result, outputDir, baseName));
                    case "json" -> written.add(new JsonExporter().export(result, outputDir, baseName));
                    default -> throw new IllegalStateException("Unhandled format: " + format);
                }
            }

            if (!opts.quiet) {
                System.out.println(result.getSummary());
            }
            written.forEach(System.out::println);

            int warnings = result.getWarnings().size();
            log.info("Done in {}s - {} API calls, {} file(s) written{}",
                    (System.currentTimeMillis() - start) / 1000, result.getApiCallLog().size(),
                    written.size(), warnings > 0 ? ", " + warnings + " warning(s)" : "");
            if (warnings > 0) {
                log.warn("{} item(s) could not be extracted:", warnings);
                result.getWarnings().forEach(w -> log.warn("  {}", w));
                if (opts.strict) return EXIT_WARNINGS;
            }
            return EXIT_OK;
        } catch (Exception e) {
            log.error("Extraction failed: {}", e.getMessage());
            log.debug("Stack trace", e);
            return EXIT_FAILED;
        }
    }

    /** Logs a progress line whenever another 10% is reached. */
    private static ExtractionProgressCallback progressLogger(Log log) {
        int[] lastStep = {-1};
        return (phase, progress) -> {
            int step = (int) (progress * 10);
            synchronized (lastStep) {
                if (step <= lastStep[0]) return;
                lastStep[0] = step;
            }
            log.info("[{}%] {}", String.format("%3d", (int) (progress * 100)), phase);
        };
    }

    static List<String> parseFormats(String value) {
        Set<String> formats = new LinkedHashSet<>();
        for (String part : value.toLowerCase().split(",")) {
            String format = part.trim();
            if (format.isEmpty()) continue;
            if (format.equals("all")) {
                formats.addAll(List.of("xlsx", "csv", "json"));
            } else if (format.equals("excel")) {
                formats.add("xlsx");
            } else if (FORMATS.contains(format)) {
                formats.add(format);
            } else {
                throw new IllegalArgumentException(
                        "Unknown export format '" + format + "' - use xlsx, csv, json or all.");
            }
        }
        if (formats.isEmpty()) {
            throw new IllegalArgumentException("No export format given - use xlsx, csv, json or all.");
        }
        return new ArrayList<>(formats);
    }

    /** Parsed command line. Options that map to a property are collected in {@link #overrides}. */
    static final class Options {
        String configFile;
        boolean help, version, quiet, verbose, strict, listPackages;
        final Map<String, String> overrides = new LinkedHashMap<>();

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                String inlineValue = null;
                if (arg.startsWith("--") && arg.contains("=")) {
                    inlineValue = arg.substring(arg.indexOf('=') + 1);
                    arg = arg.substring(0, arg.indexOf('='));
                }
                switch (arg) {
                    case "-h", "--help" -> o.help = true;
                    case "-V", "--version" -> o.version = true;
                    case "-q", "--quiet" -> o.quiet = true;
                    case "-v", "--verbose" -> o.verbose = true;
                    case "--strict" -> o.strict = true;
                    case "--list-packages" -> o.listPackages = true;
                    case "--no-timestamp" -> o.overrides.put("export.filename.timestamp", "false");
                    case "--no-bundles" -> o.overrides.put("extract.bundles", "false");
                    case "--no-configs" -> o.overrides.put("extract.configurations", "false");
                    case "--no-runtime" -> o.overrides.put("extract.runtime.status", "false");
                    case "--no-valuemappings" -> o.overrides.put("extract.valuemappings", "false");
                    case "-c", "--config", "-f", "--format", "-o", "--output", "--prefix",
                         "-p", "--packages", "--threads", "--set" -> {
                        String value = inlineValue;
                        if (value == null) {
                            if (i + 1 >= args.length) {
                                throw new IllegalArgumentException("Option " + arg + " needs a value.");
                            }
                            value = args[++i];
                        }
                        inlineValue = null;
                        o.applyValue(arg, value);
                    }
                    default -> {
                        if (arg.startsWith("-")) {
                            throw new IllegalArgumentException("Unknown option: " + arg);
                        }
                        if (o.configFile != null) {
                            throw new IllegalArgumentException("More than one config file given: " + arg);
                        }
                        o.configFile = arg;
                    }
                }
                if (inlineValue != null) {
                    throw new IllegalArgumentException("Option " + arg + " does not take a value.");
                }
            }
            if (o.quiet && o.verbose) {
                throw new IllegalArgumentException("Use either --quiet or --verbose, not both.");
            }
            return o;
        }

        private void applyValue(String option, String value) {
            switch (option) {
                case "-c", "--config" -> {
                    if (configFile != null) {
                        throw new IllegalArgumentException("More than one config file given: " + value);
                    }
                    configFile = value;
                }
                case "-f", "--format" -> {
                    parseFormats(value); // fail fast on typos
                    overrides.put("export.format", value);
                }
                case "-o", "--output" -> overrides.put("export.output.dir", value);
                case "--prefix" -> overrides.put("export.filename.prefix", value);
                case "-p", "--packages" -> overrides.put("extract.package.ids", value);
                case "--threads" -> {
                    try {
                        if (Integer.parseInt(value.trim()) < 1) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--threads needs a positive number, got: " + value);
                    }
                    overrides.put("api.parallel.threads", value.trim());
                }
                case "--set" -> {
                    int eq = value.indexOf('=');
                    if (eq < 1) {
                        throw new IllegalArgumentException("--set needs key=value, got: " + value);
                    }
                    overrides.put(value.substring(0, eq).trim(), value.substring(eq + 1));
                }
                default -> throw new IllegalStateException("Unhandled option: " + option);
            }
        }
    }
}
