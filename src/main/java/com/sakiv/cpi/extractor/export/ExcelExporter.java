package com.sakiv.cpi.extractor.export;

import com.sakiv.cpi.extractor.export.ReportTables.Table;
import com.sakiv.cpi.extractor.export.XlsxWriter.Style;
import com.sakiv.cpi.extractor.model.ExtractionResult;
import com.sakiv.cpi.extractor.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/**
 * Exports CPI extraction results to one Excel (.xlsx) workbook:
 * a Summary sheet followed by one sheet per {@link ReportTables} table.
 */
public class ExcelExporter {

    private static final Log log = Log.get(ExcelExporter.class);
    private static final int MAX_COLUMN_CHARS = 60;
    private static final String SNIPPET_HEADER = "Script Content (Snippet)";

    public String export(ExtractionResult result, String outputDir, String baseName) throws IOException {
        Path outputPath = Path.of(outputDir);
        Files.createDirectories(outputPath);
        Path filePath = outputPath.resolve(baseName + ".xlsx");

        XlsxWriter workbook = new XlsxWriter();
        writeSummary(workbook, result);
        for (Table table : ReportTables.build(result)) {
            writeTable(workbook, table);
        }
        workbook.write(filePath);

        log.debug("Excel report exported to: {}", filePath.toAbsolutePath());
        return filePath.toAbsolutePath().toString();
    }

    private void writeSummary(XlsxWriter workbook, ExtractionResult result) {
        XlsxWriter.Sheet sheet = workbook.addSheet("Summary").columnWidths(30, 70);

        long started = result.getRuntimeArtifacts().stream()
                .filter(r -> "STARTED".equalsIgnoreCase(r.getStatus())).count();
        long errors = result.getRuntimeArtifacts().stream()
                .filter(r -> "ERROR".equalsIgnoreCase(r.getStatus())).count();
        long configs = result.getAllFlows().stream().mapToLong(f -> f.getConfigurations().size()).sum();

        sheet.row().text("SAP CPI Artifact Extraction Summary", Style.HEADER).text("", Style.HEADER);
        sheet.row();
        summaryRow(sheet, "Tenant URL", result.getTenantUrl());
        summaryRow(sheet, "Extracted At",
                result.getExtractedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        sheet.row();
        summaryRow(sheet, "Integration Packages", String.valueOf(result.getPackages().size()));
        summaryRow(sheet, "Integration Flows", String.valueOf(result.getAllFlows().size()));
        summaryRow(sheet, "Value Mappings", String.valueOf(result.getAllValueMappings().size()));
        summaryRow(sheet, "Configuration Parameters", String.valueOf(configs));
        summaryRow(sheet, "Runtime Artifacts", String.valueOf(result.getRuntimeArtifacts().size()));
        summaryRow(sheet, "  - STARTED", String.valueOf(started));
        summaryRow(sheet, "  - ERROR", String.valueOf(errors));
        if (!result.getWarnings().isEmpty()) {
            sheet.row();
            summaryRow(sheet, "Extraction Warnings", String.valueOf(result.getWarnings().size()));
            for (String warning : result.getWarnings()) {
                summaryRow(sheet, "", warning);
            }
        }
    }

    private void summaryRow(XlsxWriter.Sheet sheet, String label, String value) {
        sheet.row().text(label, Style.DEFAULT).text(value, Style.DEFAULT);
    }

    private void writeTable(XlsxWriter workbook, Table table) {
        String[] headers = table.headers();
        int[] widths = new int[headers.length];
        XlsxWriter.Sheet sheet = workbook.addSheet(table.sheetName()).headerRow(headers.length);

        sheet.row();
        for (int c = 0; c < headers.length; c++) {
            sheet.text(headers[c], Style.HEADER);
            widths[c] = headers[c].length() + 3; // room for the filter button
        }

        for (Object[] values : table.rows()) {
            boolean hasSnippet = false;
            for (int c = 0; c < headers.length && !hasSnippet; c++) {
                hasSnippet = SNIPPET_HEADER.equals(headers[c]);
            }
            sheet.row(hasSnippet ? 60 : 0);

            for (int c = 0; c < headers.length; c++) {
                Object value = c < values.length ? values[c] : null;
                if (value instanceof Number number) {
                    sheet.number(number);
                    widths[c] = Math.max(widths[c], number.toString().length());
                    continue;
                }
                String text = value != null ? value.toString() : "";
                widths[c] = Math.max(widths[c], longestLine(text));

                Style style = Style.DEFAULT;
                if (SNIPPET_HEADER.equals(headers[c])) {
                    style = Style.WRAP;
                } else if (headers[c].endsWith("Status")) {
                    if ("ERROR".equalsIgnoreCase(text)) style = Style.ERROR;
                    else if ("STARTED".equalsIgnoreCase(text)) style = Style.SUCCESS;
                }
                sheet.text(text, style);
            }
        }

        for (int c = 0; c < headers.length; c++) {
            widths[c] = SNIPPET_HEADER.equals(headers[c]) ? 80 : Math.min(widths[c], MAX_COLUMN_CHARS) + 2;
        }
        sheet.columnWidths(widths);
    }

    private static int longestLine(String text) {
        int max = 0;
        int lineStart = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                max = Math.max(max, i - lineStart);
                lineStart = i + 1;
            }
        }
        return max;
    }
}
