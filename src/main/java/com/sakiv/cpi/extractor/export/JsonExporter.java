package com.sakiv.cpi.extractor.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sakiv.cpi.extractor.model.ExtractionResult;
import com.sakiv.cpi.extractor.util.CpiDateUtil;
import com.sakiv.cpi.extractor.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Exports CPI extraction results to a single JSON file (the full object graph,
 * including raw iFlow XML and script sources).
 */
public class JsonExporter {

    private static final Log log = Log.get(JsonExporter.class);

    /** JSON properties that hold CPI dates ({@code /Date(...)/} or epoch milliseconds). */
    private static final Set<String> DATE_PROPERTIES = Set.of(
            "CreatedAt", "ModifiedAt", "CreationDate", "ModifiedDate", "DeployedOn", "deployedAt");

    public String export(ExtractionResult result, String outputDir, String baseName) throws IOException {
        Path outputPath = Path.of(outputDir);
        Files.createDirectories(outputPath);
        Path filePath = outputPath.resolve(baseName + ".json");

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        SimpleModule dateModule = new SimpleModule();
        dateModule.addSerializer(String.class, new CpiStringSerializer(false));
        mapper.registerModule(dateModule);

        mapper.writeValue(filePath.toFile(), result);

        log.debug("JSON report exported to: {}", filePath.toAbsolutePath());
        return filePath.toAbsolutePath().toString();
    }

    /**
     * Makes CPI dates human-readable, but only for the known date properties. Every other
     * string is written untouched, so all-digit values such as a port, a client number or
     * a numeric parameter value are never mistaken for an epoch timestamp.
     */
    private static final class CpiStringSerializer extends JsonSerializer<String> implements ContextualSerializer {

        private final boolean date;

        private CpiStringSerializer(boolean date) {
            this.date = date;
        }

        @Override
        public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) {
            boolean isDate = property != null && DATE_PROPERTIES.contains(property.getName());
            return isDate == date ? this : new CpiStringSerializer(isDate);
        }

        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(date ? CpiDateUtil.formatCpiDate(value) : value);
        }
    }
}
