# SAP CPI Artifact Extractor – CLI

Command-line, lite version of [extract-cpi-ui](https://github.com/viksingh/extract-cpi-ui).
It extracts design-time content and deployment status from an SAP Cloud Integration
tenant through the OData API and writes Excel, CSV or JSON.

No desktop UI, no message processing log extraction, no API Management.

## What it extracts

| Data | API | Notes |
|---|---|---|
| Packages | `IntegrationPackages` | |
| iFlows, value mappings | `IntegrationPackages('…')/IntegrationDesigntimeArtifacts`, `…/ValueMappingDesigntimeArtifacts` | |
| Configurations | `IntegrationDesigntimeArtifacts(Id,Version)/Configurations` | **Design-time** externalized parameters, not the deployed values |
| iFlow bundle content | `IntegrationDesigntimeArtifacts(Id,Version)/$value` | Adapters, mappings, scripts; skip with `--no-bundles` |
| Deployment status | `IntegrationRuntimeArtifacts` | Status, deployed version, deployed by/on |

Derived from the bundles: ECC Endpoints (adapter classification), Flow Chains
(iFlows linked by a JMS queue or ProcessDirect address, with `{{param}}` placeholders
resolved from the design-time configurations) and Credentials (credential, keystore
and alias references).

## Download

A ready-built jar is attached to each [release](../../releases). It needs Java 17 or later.

## Build

Requires Java 17+ and Maven.

```bash
mvn package
# -> target/cpi-extract.jar (single runnable jar, about 4 MB)
```

To publish a release, set the version in `pom.xml`, then either push a matching
tag (`git tag v1.0.1 && git push origin v1.0.1`) or run the Release workflow from
the Actions tab. It builds the jar and attaches it to a release for that version.

## Run

```bash
cp config.properties.template config.properties   # fill in tenant URL and credentials
java -jar target/cpi-extract.jar -c config.properties
```

Credentials can come from environment variables instead of a file:

```bash
export CPI_BASE_URL=https://<tenant>.it-cpi018.cfapps.eu10.hana.ondemand.com
export CPI_OAUTH_TOKEN_URL=https://<subdomain>.authentication.eu10.hana.ondemand.com/oauth/token
export CPI_OAUTH_CLIENT_ID=...
export CPI_OAUTH_CLIENT_SECRET=...
java -jar target/cpi-extract.jar -f csv -o ./out
```

Examples:

```bash
# Which packages are there?
java -jar cpi-extract.jar -c config.properties --list-packages

# Two packages only, Excel + JSON
java -jar cpi-extract.jar -c config.properties -p OrderToCash,Finance -f xlsx,json

# Fast run: configurations and status only, stable file names for diffing between runs
java -jar cpi-extract.jar -c config.properties --no-bundles -f csv --no-timestamp -o ./snapshot

# Behind a proxy
java -Dhttps.proxyHost=proxy.corp -Dhttps.proxyPort=8080 -jar cpi-extract.jar -c config.properties
```

## Options

```
-c, --config <file>      Properties file (see config.properties.template)
-f, --format <list>      xlsx, csv, json, all or a comma list (default: xlsx)
-o, --output <dir>       Output directory (default: ./output)
    --prefix <name>      Output file name prefix (default: cpi_artifacts)
    --no-timestamp       Do not append _yyyyMMdd_HHmmss to file names
-p, --packages <ids>     Only these package IDs (comma-separated)
    --list-packages      Print package IDs and names, then exit
    --no-bundles         Skip iFlow bundle download - much faster
    --no-configs         Skip externalized configurations
    --no-runtime         Skip deployment status
    --no-valuemappings   Skip value mappings
    --threads <n>        Parallel API calls (default: 4)
    --set <key=value>    Override any property; may be repeated
    --strict             Exit with code 3 if anything was skipped
-q, --quiet              Only warnings and errors on stderr
-v, --verbose            Debug logging on stderr
-h, --help               Show help
-V, --version            Show version
```

Precedence: command line > environment variables > config file > defaults.
Without `-c`, `./config.properties` is used when it exists.

## Output

stdout carries only results (the summary and the paths of the files written; with `-q`
just the paths), so the tool can be used in scripts. Progress and warnings go to stderr.

| Format | Files |
|---|---|
| `xlsx` | One workbook: Summary, Packages, Integration Flows, Value Mappings, Configurations, Runtime Status, iFlow Adapters, iFlow Mappings, iFlow Scripts, ECC Endpoints, Flow Chains, Credentials, API Calls |
| `csv` | One UTF-8 file per sheet above (except Summary): `<prefix>_packages.csv`, `_flows`, `_valuemappings`, `_configurations`, `_runtime`, `_adapters`, `_mappings`, `_scripts`, `_ecc_endpoints`, `_flow_chains`, `_credentials`, `_apicalls` |
| `json` | The full object graph in one file, including raw iFlow XML and script sources |

Exit codes: `0` success, `1` extraction or export failed, `2` usage or configuration
error, `3` finished but some items were skipped (only with `--strict`). Skipped items
(a package, bundle or configuration call that failed) are listed on stderr and on the
Summary sheet.

## Differences from the UI version

- Removed: JavaFX UI, connection profiles, snapshots, date filter, message processing
  logs (and the iFlow Usage sheet and "Last Used" columns built from them), API
  Management, dependency graph / unique interfaces sheets.
- Dependencies are down to Apache HttpClient and Jackson. Excel and CSV files are
  written by small classes in this project (`XlsxWriter`, `CsvExporter`) instead of
  Apache POI and Commons CSV, and logging is a built-in stderr logger.
- Excel and CSV are generated from the same tables (`ReportTables`), so both formats
  have the same columns.
- JSON export only reformats real date fields. All-digit values such as a port or a
  client number are written unchanged.
