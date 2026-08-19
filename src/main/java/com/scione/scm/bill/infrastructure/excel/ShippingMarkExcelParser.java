package com.scione.scm.bill.infrastructure.excel;

import com.scione.scm.bill.application.port.ShippingMarkImportParser;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import org.apache.poi.hssf.usermodel.HSSFClientAnchor;
import org.apache.poi.hssf.usermodel.HSSFPicture;
import org.apache.poi.hssf.usermodel.HSSFShape;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 通过 POI 解析采购订单 Excel，包括表格内容、WPS DISPIMG 图片和普通浮动图片。
 */
@Component
public class ShippingMarkExcelParser implements ShippingMarkImportParser {

    private static final String PRODUCT_INFO_SHEET = "产品信息";
    private static final List<String> REQUIRED_HEADERS = List.of("采购单号", "SKU", "品名", "图片");
    private static final int MAX_DATA_ROWS = 50;
    private static final int MAX_WPS_ZIP_ENTRIES = 200;
    private static final long MAX_WPS_ZIP_ENTRY_BYTES = 5L * 1024 * 1024;
    private static final long MAX_WPS_ZIP_TOTAL_BYTES = 30L * 1024 * 1024;
    private static final Pattern DISP_IMG_PATTERN = Pattern.compile("DISPIMG\\(\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);
    private final DataFormatter dataFormatter = new DataFormatter();

    @Override
    public ParsedImport parse(ImportDocument source) {
        String fileName = source == null ? null : source.fileName();
        if (source == null || source.content() == null || source.content().length == 0
                || fileName == null || !isExcel(fileName)) {
            throw new BusinessException(ResultCode.IMPORT_FILE_UNSUPPORTED);
        }
        try {
            byte[] content = source.content();
            List<RawProductRow> rawRows = readProductInfoRows(content);
            Map<String, ImageData> wpsImages = isXlsx(fileName) ? extractWpsImages(content) : Map.of();
            return attachImages(content, fileName, rawRows, wpsImages);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.IMPORT_FILE_INVALID);
        }
    }

    private List<RawProductRow> readProductInfoRows(byte[] content) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = requiredProductInfoSheet(workbook);
            Header header = findHeader(sheet);
            Map<String, RawProductRow> rows = new LinkedHashMap<>();
            int dataRowCount = 0;
            for (int rowIndex = header.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                String purchaseOrderNo = cellText(row, header.purchaseOrderColumn());
                String skuCode = cellText(row, header.skuColumn());
                String skuName = cellText(row, header.skuNameColumn());
                if (isEmptyProductRow(purchaseOrderNo, skuCode, skuName)) {
                    continue;
                }
                if (++dataRowCount > MAX_DATA_ROWS) {
                    throw new BusinessException(ResultCode.IMPORT_FILE_TOO_MANY_ROWS);
                }
                String deduplicationKey = rowKey(purchaseOrderNo, skuCode);
                if (purchaseOrderNo.isBlank() || skuCode.isBlank()) {
                    deduplicationKey += "\u0000" + rowIndex;
                }
                rows.putIfAbsent(deduplicationKey,
                        new RawProductRow(rowIndex, purchaseOrderNo, skuCode, skuName));
            }
            return List.copyOf(rows.values());
        }
    }

    private ParsedImport attachImages(byte[] content, String fileName, List<RawProductRow> rawRows,
                                      Map<String, ImageData> wpsImages) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = requiredProductInfoSheet(workbook);
            Header header = findHeader(sheet);
            Map<Integer, ImageData> floatingImages = extractFloatingImages(sheet);
            List<ParsedDetail> details = new ArrayList<>(rawRows.size());
            for (RawProductRow rawRow : rawRows) {
                details.add(new ParsedDetail(
                        rawRow.purchaseOrderNo(), rawRow.skuCode(), rawRow.skuName(),
                        imageForRow(sheet.getRow(rawRow.rowIndex()), rawRow.rowIndex(),
                                header.imageColumn(), floatingImages, wpsImages)));
            }
            if (details.isEmpty()) {
                throw excelValidationException("Excel未解析到有效数据行");
            }
            return new ParsedImport(fileName, List.copyOf(details));
        }
    }

    private Sheet requiredProductInfoSheet(Workbook workbook) {
        Sheet sheet = workbook.getSheet(PRODUCT_INFO_SHEET);
        if (sheet == null) {
            throw excelValidationException("Excel缺少“产品信息”工作表");
        }
        return sheet;
    }

    private Header findHeader(Sheet sheet) {
        List<String> closestMissingHeaders = REQUIRED_HEADERS;
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Header header = parseHeader(row.getLastCellNum(), column -> cellText(row, column), rowIndex);
            if (header != null) {
                return header;
            }
            List<String> missingHeaders = missingRequiredHeaders(
                    row.getLastCellNum(), column -> cellText(row, column));
            if (missingHeaders.size() < closestMissingHeaders.size()) {
                closestMissingHeaders = missingHeaders;
            }
        }
        throw excelValidationException("Excel缺少必填表头：" + String.join("、", closestMissingHeaders));
    }

    private static Header parseHeader(int columnCount, CellValueAccessor cellValueAccessor, int rowIndex) {
        Integer purchaseOrderColumn = null;
        Integer skuColumn = null;
        Integer skuNameColumn = null;
        Integer imageColumn = null;
        for (int column = 0; column < Math.max(columnCount, 0); column++) {
            String title = normalizeHeader(cellValueAccessor.valueAt(column));
            if ("采购单号".equals(title)) {
                purchaseOrderColumn = column;
            } else if ("SKU".equals(title)) {
                skuColumn = column;
            } else if ("品名".equals(title)) {
                skuNameColumn = column;
            } else if ("图片".equals(title)) {
                imageColumn = column;
            }
        }
        if (purchaseOrderColumn == null || skuColumn == null || skuNameColumn == null || imageColumn == null) {
            return null;
        }
        return new Header(rowIndex, purchaseOrderColumn, skuColumn, skuNameColumn, imageColumn);
    }

    private static List<String> missingRequiredHeaders(int columnCount, CellValueAccessor cellValueAccessor) {
        Set<String> headers = new HashSet<>();
        for (int column = 0; column < Math.max(columnCount, 0); column++) {
            headers.add(normalizeHeader(cellValueAccessor.valueAt(column)));
        }
        return REQUIRED_HEADERS.stream()
                .filter(requiredHeader -> !headers.contains(normalizeHeader(requiredHeader)))
                .toList();
    }

    private static BusinessException excelValidationException(String message) {
        return new BusinessException(ResultCode.IMPORT_FILE_INVALID) {
            @Override
            public String getMessage() {
                return message;
            }
        };
    }

    private Map<Integer, ImageData> extractFloatingImages(Sheet sheet) {
        Map<Integer, ImageData> result = new HashMap<>();
        if (sheet instanceof XSSFSheet xssfSheet) {
            XSSFDrawing drawing = xssfSheet.getDrawingPatriarch();
            if (drawing == null) {
                return result;
            }
            for (XSSFShape shape : drawing.getShapes()) {
                if (shape instanceof XSSFPicture picture) {
                    XSSFClientAnchor anchor = picture.getClientAnchor();
                    if (anchor != null) {
                        result.putIfAbsent(anchor.getRow1(), new ImageData(
                                picture.getPictureData().getData(), picture.getPictureData().suggestFileExtension()));
                    }
                }
            }
        } else if (sheet instanceof HSSFSheet hssfSheet && hssfSheet.getDrawingPatriarch() != null) {
            for (HSSFShape shape : hssfSheet.getDrawingPatriarch().getChildren()) {
                if (shape instanceof HSSFPicture picture && picture.getAnchor() instanceof HSSFClientAnchor anchor) {
                    result.putIfAbsent(anchor.getRow1(), new ImageData(
                            picture.getPictureData().getData(), picture.getPictureData().suggestFileExtension()));
                }
            }
        }
        return result;
    }

    private ImageData imageForRow(Row row, int rowIndex, Integer imageColumn,
                                  Map<Integer, ImageData> floatingImages, Map<String, ImageData> wpsImages) {
        if (row != null && imageColumn != null) {
            Cell imageCell = row.getCell(imageColumn);
            if (imageCell != null) {
                String formula = imageCell.getCellType() == CellType.FORMULA ? imageCell.getCellFormula() : "";
                Matcher matcher = DISP_IMG_PATTERN.matcher(formula);
                if (matcher.find()) {
                    ImageData image = wpsImages.get(matcher.group(1));
                    if (image == null) {
                        throw new BusinessException(ResultCode.IMPORT_FILE_INVALID);
                    }
                    return image;
                }
            }
        }
        return floatingImages.get(rowIndex);
    }

    private Map<String, ImageData> extractWpsImages(byte[] content) {
        try {
            Map<String, byte[]> entries = readZipEntries(content);
            byte[] imagesXml = entries.get("xl/cellimages.xml");
            byte[] relationshipsXml = entries.get("xl/_rels/cellimages.xml.rels");
            if (imagesXml == null || relationshipsXml == null) {
                return Map.of();
            }
            Document relationships = parseXml(relationshipsXml);
            Map<String, String> targets = new HashMap<>();
            NodeList relationshipNodes = relationships.getElementsByTagNameNS("*", "Relationship");
            for (int index = 0; index < relationshipNodes.getLength(); index++) {
                Element relationship = (Element) relationshipNodes.item(index);
                targets.put(relationship.getAttribute("Id"), relationship.getAttribute("Target"));
            }

            Document images = parseXml(imagesXml);
            NodeList imageNames = images.getElementsByTagNameNS("*", "cNvPr");
            Map<String, ImageData> result = new HashMap<>();
            for (int index = 0; index < imageNames.getLength(); index++) {
                Element imageName = (Element) imageNames.item(index);
                String imageId = imageName.getAttribute("name");
                Element picture = findAncestor(imageName, "pic");
                Element blip = picture == null ? null : findDescendant(picture, "blip");
                if (imageId.isBlank() || blip == null) {
                    continue;
                }
                String relationshipId = blip.getAttributeNS(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed");
                if (relationshipId.isBlank()) {
                    relationshipId = blip.getAttribute("r:embed");
                }
                String target = targets.get(relationshipId);
                if (target == null) {
                    continue;
                }
                String imagePath = resolveZipPath("xl", target);
                byte[] image = entries.get(imagePath);
                if (image != null) {
                    result.put(imageId, new ImageData(image, extension(imagePath)));
                }
            }
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, byte[]> readZipEntries(byte[] content) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        long totalBytes = 0;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_WPS_ZIP_ENTRIES) {
                    throw new IOException("Too many XLSX zip entries");
                }
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long entryBytes = 0;
                    while ((read = zip.read(buffer)) != -1) {
                        entryBytes += read;
                        totalBytes += read;
                        if (entryBytes > MAX_WPS_ZIP_ENTRY_BYTES || totalBytes > MAX_WPS_ZIP_TOTAL_BYTES) {
                            throw new IOException("XLSX uncompressed content is too large");
                        }
                        output.write(buffer, 0, read);
                    }
                    entries.put(entry.getName(), output.toByteArray());
                }
            }
        }
        return entries;
    }

    private Document parseXml(byte[] source) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(source));
    }

    private Element findAncestor(Node node, String localName) {
        Node current = node.getParentNode();
        while (current instanceof Element element) {
            if (localName.equals(element.getLocalName())) {
                return element;
            }
            current = current.getParentNode();
        }
        return null;
    }

    private Element findDescendant(Element parent, String localName) {
        NodeList descendants = parent.getElementsByTagNameNS("*", localName);
        return descendants.getLength() == 0 ? null : (Element) descendants.item(0);
    }

    private String resolveZipPath(String base, String target) {
        return Path.of(base).resolve(target).normalize().toString().replace('\\', '/');
    }

    private String cellText(Row row, int column) {
        Cell cell = row.getCell(column);
        return cell == null ? "" : dataFormatter.formatCellValue(cell).trim();
    }

    private static boolean isEmptyProductRow(String purchaseOrderNo, String skuCode, String skuName) {
        return purchaseOrderNo.isBlank() && skuCode.isBlank() && skuName.isBlank();
    }

    private static String rowKey(String purchaseOrderNo, String skuCode) {
        return purchaseOrderNo + "\u0000" + skuCode;
    }

    private static String normalizeHeader(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private boolean isExcel(String fileName) {
        return isXlsx(fileName) || fileName.toLowerCase(Locale.ROOT).endsWith(".xls");
    }

    private boolean isXlsx(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx");
    }

    private String extension(String path) {
        int separator = path.lastIndexOf('.');
        return separator < 0 ? "png" : path.substring(separator + 1);
    }

    private record Header(int rowIndex, int purchaseOrderColumn, int skuColumn,
                          Integer skuNameColumn, Integer imageColumn) {
    }

    private record RawProductRow(int rowIndex, String purchaseOrderNo, String skuCode, String skuName) {
    }

    @FunctionalInterface
    private interface CellValueAccessor {
        String valueAt(int columnIndex);
    }
}
