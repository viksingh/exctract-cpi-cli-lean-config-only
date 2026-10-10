package com.sakiv.cpi.extractor.export;

import com.sakiv.cpi.extractor.export.ReportTables.Table;
import com.sakiv.cpi.extractor.model.ExtractionResult;
import com.sakiv.cpi.extractor.util.Log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports CPI extraction results to CSV (RFC 4180, UTF-8): one file per {@link ReportTables} table.
 */
public class CsvExporter {

    private static final Log log = Log.get(CsvExporter.class);

    /** @return absolute paths of the files written */
    public List<String> export(ExtractionResult result, String outputDir, String baseName) throws IOException {
        Path outputPath = Path.of(outputDir);
        Files.createDirectories(outputPath);

        List<String> written = new ArrayList<>();
        for (Table table : ReportTables.build(result)) {
            Path filePath = outputPath.resolve(baseName + "_" + table.fileSuffix() + ".csv");
            try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
                writeRecord(writer, table.headers());
                for (Object[] row : table.rows()) {
                    writeRecord(writer, row);
                }
            }
            written.add(filePath.toAbsolutePath().toString());
        }

        log.debug("CSV files exported to: {}", outputPath.toAbsolutePath());
        return written;
    }

    private static void writeRecord(BufferedWriter writer, Object[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            writer.write(escape(values[i]));
        }
        writer.write("\r\n");
    }

    /** Quote a field when it contains a comma, quote, line break or leading/trailing space. */
    static String escape(Object value) {
        if (value == null) return "";
        String text = value.toString();
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                || (!text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(text.length() - 1) == ' '));
        return quote ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }
}
