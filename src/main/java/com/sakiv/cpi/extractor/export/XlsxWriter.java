package com.sakiv.cpi.extractor.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Small dependency-free writer for .xlsx workbooks (Office Open XML SpreadsheetML).
 * Supports what the reports need: several sheets, string and numeric cells, a few
 * fixed cell styles, column widths, row heights, a frozen header row and auto-filter.
 */
final class XlsxWriter {

    /** Cell styles; the ordinal is the index into cellXfs in styles.xml. */
    enum Style { DEFAULT, HEADER, WRAP, ERROR, SUCCESS }

    /** Excel's hard limit for the text in one cell. */
    static final int MAX_CELL_LENGTH = 32767;

    private final List<SheetData> sheets = new ArrayList<>();
    private final Map<String, Integer> sharedStrings = new LinkedHashMap<>();
    private int stringRefs = 0;

    Sheet addSheet(String name) {
        SheetData data = new SheetData(name);
        sheets.add(data);
        data.builder = new Sheet(data);
        return data.builder;
    }

    /** Builder for one worksheet. Rows are appended top to bottom. */
    final class Sheet {
        private final SheetData data;
        private StringBuilder row;
        private int rowNum = 0;
        private int colNum = 0;

        private Sheet(SheetData data) {
            this.data = data;
        }

        /** Start the next row; {@code heightPoints <= 0} keeps the default height. */
        Sheet row(double heightPoints) {
            endRow();
            rowNum++;
            colNum = 0;
            row = new StringBuilder("<row r=\"").append(rowNum).append('"');
            if (heightPoints > 0) {
                row.append(" ht=\"").append(heightPoints).append("\" customHeight=\"1\"");
            }
            row.append('>');
            return this;
        }

        Sheet row() {
            return row(0);
        }

        Sheet text(String value, Style style) {
            String ref = cellRef(colNum++, rowNum);
            if (value == null || value.isEmpty()) {
                if (style != Style.DEFAULT) {
                    row.append("<c r=\"").append(ref).append("\" s=\"").append(style.ordinal()).append("\"/>");
                }
                return this;
            }
            int index = sharedStrings.computeIfAbsent(clean(value), k -> sharedStrings.size());
            stringRefs++;
            row.append("<c r=\"").append(ref).append('"');
            if (style != Style.DEFAULT) row.append(" s=\"").append(style.ordinal()).append('"');
            row.append(" t=\"s\"><v>").append(index).append("</v></c>");
            return this;
        }

        Sheet number(Number value) {
            String ref = cellRef(colNum++, rowNum);
            row.append("<c r=\"").append(ref).append("\"><v>").append(value).append("</v></c>");
            return this;
        }

        /** Column widths in characters, one per column starting at A. */
        Sheet columnWidths(int... chars) {
            data.widths = chars;
            return this;
        }

        /** Freeze the first row and put an auto-filter on it across {@code columns} columns. */
        Sheet headerRow(int columns) {
            data.headerColumns = columns;
            return this;
        }

        private void endRow() {
            if (row != null) {
                data.rows.append(row).append("</row>");
                row = null;
            }
        }

        private void finish() {
            endRow();
            data.lastRow = rowNum;
        }
    }

    private static final class SheetData {
        final String name;
        final StringBuilder rows = new StringBuilder();
        int[] widths = new int[0];
        int headerColumns = 0;
        int lastRow = 0;
        Sheet builder;

        SheetData(String name) {
            this.name = name;
        }
    }

    void write(Path file) throws IOException {
        for (SheetData sheet : sheets) {
            sheet.builder.finish(); // close open rows so every sheet knows its last row
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            entry(zip, "[Content_Types].xml", contentTypes());
            entry(zip, "_rels/.rels", XML
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            entry(zip, "xl/workbook.xml", workbook());
            entry(zip, "xl/_rels/workbook.xml.rels", workbookRels());
            entry(zip, "xl/styles.xml", STYLES);
            for (int i = 0; i < sheets.size(); i++) {
                entry(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", worksheet(sheets.get(i), i == 0));
            }
            // Written last: only now are all shared strings known.
            entry(zip, "xl/sharedStrings.xml", sharedStringsXml());
        }
    }

    // =========================================================================
    // Package parts
    // =========================================================================

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /** cellXfs order must match {@link Style}. */
    private static final String STYLES = XML
            + "<styleSheet xmlns=\"" + NS_MAIN + "\">"
            + "<fonts count=\"4\">"
            + "<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "<font><b/><sz val=\"11\"/><color rgb=\"FFC00000\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "<font><b/><sz val=\"11\"/><color rgb=\"FF008000\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "</fonts>"
            + "<fills count=\"3\">"
            + "<fill><patternFill patternType=\"none\"/></fill>"
            + "<fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1F3864\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "</fills>"
            + "<borders count=\"2\">"
            + "<border><left/><right/><top/><bottom/><diagonal/></border>"
            + "<border><left/><right/><top/><bottom style=\"thin\"><color auto=\"1\"/></bottom><diagonal/></border>"
            + "</borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"5\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "</cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";

    private String contentTypes() {
        StringBuilder sb = new StringBuilder(XML)
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
                .append("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>");
        for (int i = 1; i <= sheets.size(); i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return sb.append("</Types>").toString();
    }

    private String workbook() {
        StringBuilder sb = new StringBuilder(XML)
                .append("<workbook xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(NS_REL).append("\">")
                .append("<bookViews><workbookView/></bookViews><sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<sheet name=\"").append(escape(sheets.get(i).name)).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        sb.append("</sheets>");

        // Excel keeps a hidden defined name for every auto-filter range.
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < sheets.size(); i++) {
            SheetData sheet = sheets.get(i);
            if (sheet.headerColumns > 0) {
                names.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i)
                        .append("\" hidden=\"1\">'").append(escape(sheet.name.replace("'", "''"))).append("'!")
                        .append(absoluteRange(sheet)).append("</definedName>");
            }
        }
        if (names.length() > 0) sb.append("<definedNames>").append(names).append("</definedNames>");
        return sb.append("</workbook>").toString();
    }

    private String workbookRels() {
        StringBuilder sb = new StringBuilder(XML)
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        int id = 1;
        for (; id <= sheets.size(); id++) {
            sb.append("<Relationship Id=\"rId").append(id)
                    .append("\" Type=\"").append(NS_REL).append("/worksheet\" Target=\"worksheets/sheet")
                    .append(id).append(".xml\"/>");
        }
        sb.append("<Relationship Id=\"rId").append(id++).append("\" Type=\"").append(NS_REL)
                .append("/styles\" Target=\"styles.xml\"/>");
        sb.append("<Relationship Id=\"rId").append(id).append("\" Type=\"").append(NS_REL)
                .append("/sharedStrings\" Target=\"sharedStrings.xml\"/>");
        return sb.append("</Relationships>").toString();
    }

    /** Element order follows the schema: sheetViews, cols, sheetData, autoFilter. */
    private String worksheet(SheetData sheet, boolean first) {
        StringBuilder sb = new StringBuilder(XML)
                .append("<worksheet xmlns=\"").append(NS_MAIN).append("\">")
                .append("<sheetViews><sheetView workbookViewId=\"0\"").append(first ? " tabSelected=\"1\"" : "").append('>');
        if (sheet.headerColumns > 0) {
            sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                    .append("<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/>");
        }
        sb.append("</sheetView></sheetViews>");
        if (sheet.widths.length > 0) {
            sb.append("<cols>");
            for (int i = 0; i < sheet.widths.length; i++) {
                sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                        .append("\" width=\"").append(Math.max(1, Math.min(sheet.widths[i], 255)))
                        .append("\" customWidth=\"1\"/>");
            }
            sb.append("</cols>");
        }
        sb.append("<sheetData>").append(sheet.rows).append("</sheetData>");
        if (sheet.headerColumns > 0) {
            sb.append("<autoFilter ref=\"A1:").append(cellRef(sheet.headerColumns - 1, Math.max(sheet.lastRow, 1)))
                    .append("\"/>");
        }
        return sb.append("</worksheet>").toString();
    }

    private static String absoluteRange(SheetData sheet) {
        return "$A$1:$" + columnName(sheet.headerColumns - 1) + "$" + Math.max(sheet.lastRow, 1);
    }

    private String sharedStringsXml() {
        StringBuilder sb = new StringBuilder(XML)
                .append("<sst xmlns=\"").append(NS_MAIN).append("\" count=\"").append(stringRefs)
                .append("\" uniqueCount=\"").append(sharedStrings.size()).append("\">");
        for (String s : sharedStrings.keySet()) {
            sb.append("<si><t xml:space=\"preserve\">").append(escape(s)).append("</t></si>");
        }
        return sb.append("</sst>").toString();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String cellRef(int col, int row) {
        return columnName(col) + row;
    }

    static String columnName(int col) {
        StringBuilder sb = new StringBuilder();
        for (int n = col; n >= 0; n = n / 26 - 1) {
            sb.insert(0, (char) ('A' + n % 26));
        }
        return sb.toString();
    }

    /** Normalise line breaks, drop characters XML 1.0 cannot carry, enforce Excel's cell limit. */
    static String clean(String value) {
        StringBuilder sb = new StringBuilder(Math.min(value.length(), MAX_CELL_LENGTH));
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\r') {
                if (i < value.length() && value.charAt(i) == '\n') continue; // CRLF -> LF
                cp = '\n';
            }
            boolean valid = cp == '\t' || cp == '\n'
                    || (cp >= 0x20 && cp <= 0xD7FF)
                    || (cp >= 0xE000 && cp <= 0xFFFD)
                    || cp >= 0x10000;
            if (!valid) continue;
            if (sb.length() + Character.charCount(cp) > MAX_CELL_LENGTH - 3) {
                return sb.append("...").toString();
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    static String escape(String value) {
        StringBuilder sb = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String replacement = switch (c) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                default -> null;
            };
            if (replacement != null) {
                if (sb == null) sb = new StringBuilder(value.length() + 16).append(value, 0, i);
                sb.append(replacement);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : value;
    }
}
