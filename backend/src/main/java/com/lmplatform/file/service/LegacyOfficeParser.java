package com.lmplatform.file.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;

/** Extracts text from binary Office formats without requiring LibreOffice in production images. */
@Component
public class LegacyOfficeParser {

    private final ObjectMapper objectMapper;

    public LegacyOfficeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean supports(String filename) {
        String extension = extension(filename);
        return extension.equals("doc") || extension.equals("xls") || extension.equals("ppt");
    }

    public JsonNode parse(byte[] content, String filename, String mediaType) throws IOException {
        String extension = extension(filename);
        return switch (extension) {
            case "doc" -> parsed(mediaType, parseDoc(content), null, "apache-poi-hwpf");
            case "xls" -> parseXls(content, mediaType);
            case "ppt" -> parsed(mediaType, parsePpt(content), null, "apache-poi-hslf");
            default -> throw new IllegalArgumentException("Unsupported legacy Office format: " + extension);
        };
    }

    private String parseDoc(byte[] content) throws IOException {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content));
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private JsonNode parseXls(byte[] content, String mediaType) throws IOException {
        try (Workbook workbook = new HSSFWorkbook(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            ArrayNode tables = objectMapper.createArrayNode();
            StringBuilder text = new StringBuilder();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                if (!text.isEmpty()) text.append("\n\n");
                text.append("[").append(sheet.getSheetName()).append("]\n");
                ArrayNode table = objectMapper.createArrayNode();
                for (Row row : sheet) {
                    ArrayNode tableRow = objectMapper.createArrayNode();
                    List<String> values = new ArrayList<>();
                    short lastCell = row.getLastCellNum();
                    for (int cellIndex = 0; cellIndex < Math.max(lastCell, 0); cellIndex++) {
                        String value = formatter.formatCellValue(row.getCell(cellIndex));
                        tableRow.add(value);
                        values.add(value);
                    }
                    table.add(tableRow);
                    text.append(String.join("\t", values)).append("\n");
                }
                tables.add(table);
            }
            return parsed(mediaType, text.toString().strip(), tables, "apache-poi-hssf");
        }
    }

    private String parsePpt(byte[] content) throws IOException {
        try (HSLFSlideShow slideshow = new HSLFSlideShow(new ByteArrayInputStream(content))) {
            StringBuilder text = new StringBuilder();
            for (HSLFSlide slide : slideshow.getSlides()) {
                if (!text.isEmpty()) text.append("\n\n");
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        String shapeText = textShape.getText();
                        if (shapeText != null && !shapeText.isBlank()) {
                            text.append(shapeText.strip()).append("\n");
                        }
                    }
                }
            }
            return text.toString().strip();
        }
    }

    private ObjectNode parsed(String mediaType, String text, ArrayNode tables, String parser) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("media_type", mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType);
        result.put("text", text == null ? "" : text);
        result.set("tables", tables == null ? objectMapper.createArrayNode() : tables);
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("parser", parser);
        result.set("metadata", metadata);
        return result;
    }

    private String extension(String filename) {
        String name = filename == null ? "" : Path.of(filename).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
