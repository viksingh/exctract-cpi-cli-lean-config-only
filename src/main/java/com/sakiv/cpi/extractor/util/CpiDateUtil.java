package com.sakiv.cpi.extractor.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Formats the date strings returned by the SAP CPI OData API.
 */
public final class CpiDateUtil {

    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CpiDateUtil() {}

    /**
     * Format a SAP CPI OData date string to {@code yyyy-MM-dd HH:mm:ss} (local time zone).
     * Handles {@code /Date(epochMs)/}, {@code /Date(epochMs+HHmm)/}, plain epoch-millisecond
     * strings and ISO-8601 date-times. Returns an empty string for null/blank input and the
     * original string if it cannot be parsed.
     */
    public static String formatCpiDate(String cpiDate) {
        if (cpiDate == null || cpiDate.isBlank()) return "";
        try {
            long epoch;
            if (cpiDate.contains("/Date(")) {
                epoch = extractEpochMs(cpiDate);
            } else if (cpiDate.matches("\\d+")) {
                epoch = Long.parseLong(cpiDate);
            } else {
                // ISO-8601 datetime (e.g. "2025-02-21T02:04:31.165") — reformat consistently
                return cpiDate.length() > 10 ? cpiDate.substring(0, 19).replace('T', ' ') : cpiDate;
            }
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneId.systemDefault()).format(OUT);
        } catch (Exception e) {
            return cpiDate;
        }
    }

    /**
     * Extract epoch milliseconds from an OData {@code /Date(...)/} string, ignoring an
     * optional timezone offset suffix (a leading '-' is the sign of a negative epoch).
     */
    private static long extractEpochMs(String cpiDate) {
        int start = cpiDate.indexOf('(') + 1;
        int end = cpiDate.indexOf(')', start);
        if (end < 0) end = cpiDate.length();
        String inner = cpiDate.substring(start, end);

        int tzSep = inner.indexOf('+');
        if (tzSep < 0) {
            int tzMinus = inner.lastIndexOf('-');
            if (tzMinus > 0) tzSep = tzMinus;
        }
        String epochPart = tzSep >= 0 ? inner.substring(0, tzSep) : inner;
        return Long.parseLong(epochPart.trim());
    }
}
