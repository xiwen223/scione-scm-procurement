package com.scione.scm.bill.infrastructure.label;

import com.scione.scm.bill.application.port.ShippingMarkFileStore;
import com.scione.scm.bill.application.port.ShippingMarkLabelRenderer;
import com.scione.scm.bill.domain.shippingmark.ShippingMarkDetail;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * 渲染固定版式箱唛 Excel 标签的基础设施适配器。
 *
 * <p>版式以模板 {@code templates/shipping-mark-template.xlsx} 为准：文本单元格由本类填充，
 * 条码与商品图以标准 OOXML 图片（DrawingML）嵌入到模板预留的单元格区域，图片按区域内边距
 * 等比缩放并居中，效果与前端 {@code ShippingMarkLabel} 组件一致。
 */
@Service
@RequiredArgsConstructor
public class ShippingMarkLabelService implements ShippingMarkLabelRenderer {

    private static final String TEMPLATE_PATH = "templates/shipping-mark-template.xlsx";
    private static final int PURCHASE_ORDER_CELL_ROW = 0;
    private static final int SKU_CELL_ROW = 2;
    private static final int TEXT_COLUMN = 2;
    /** 条码占“条形码”行 C7 单元格。 */
    private static final int BARCODE_ROW = 6;
    private static final int BARCODE_COLUMN = 2;
    /** 商品图占模板 D2:D7 合并区。 */
    private static final CellRangeAddress PRODUCT_IMAGE_REGION = new CellRangeAddress(1, 6, 3, 3);
    private static final int PRODUCT_IMAGE_COLUMN = 3;
    /** 图片区内边距，对应前端 {@code p-2.5}。 */
    private static final int IMAGE_PADDING_PIXELS = 10;
    /** 商品图以最长边 512 像素嵌入，等比缩放，避免把原始大图塞进每个标签文件。 */
    private static final int MAX_PRODUCT_IMAGE_EDGE = 512;
    private static final float JPEG_QUALITY = 0.50f;

    private final Code128BarcodeService barcodeService;
    private final ShippingMarkFileStore fileStore;

    @Override
    public String barcodeDataUrl(String skuCode) throws IOException {
        return barcodeService.generateDataUrl(skuCode);
    }

    @Override
    public byte[] render(ShippingMarkDetail detail) throws IOException {
        byte[] barcode = barcodeService.generate(detail.getSkuCode());
        EncodedImage productImage = loadProductImage(detail);
        ClassPathResource template = new ClassPathResource(TEMPLATE_PATH);
        try (InputStream input = template.getInputStream();
             XSSFWorkbook workbook = new XSSFWorkbook(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            setCellValue(sheet, TEXT_COLUMN, PURCHASE_ORDER_CELL_ROW, detail.getPurchaseOrderNo());
            setCellValue(sheet, TEXT_COLUMN, SKU_CELL_ROW, detail.getSkuCode());

            XSSFDrawing drawing = drawingOf(sheet);
            insertPicture(workbook, drawing, sheet,
                    new CellRangeAddress(BARCODE_ROW, BARCODE_ROW, BARCODE_COLUMN, BARCODE_COLUMN),
                    barcode, Workbook.PICTURE_TYPE_PNG);
            if (productImage != null) {
                // 有商品图时清掉模板里的“暂无图片”占位文字，再贴图
                sheet.getRow(PRODUCT_IMAGE_REGION.getFirstRow())
                        .getCell(PRODUCT_IMAGE_COLUMN)
                        .setBlank();
                insertPicture(workbook, drawing, sheet, PRODUCT_IMAGE_REGION,
                        productImage.content(), productImage.pictureType());
            }

            workbook.write(output);
            return output.toByteArray();
        }
    }

    /**
     * 商品图是可选内容；缺失或格式无法识别时返回 null，不阻断标签生成，模板保留“暂无图片”。
     */
    private EncodedImage loadProductImage(ShippingMarkDetail detail) {
        if (detail.getSkuImage() == null || detail.getSkuImage().isBlank()) {
            return null;
        }
        try {
            return encodeProductImage(fileStore.read(detail.getSkuImage()));
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * 商品图按原始宽高比缩小。透明图保存为 PNG，普通照片保存为低质量 JPEG。
     */
    private EncodedImage encodeProductImage(byte[] content) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
        if (image == null) {
            throw new IOException("无法读取图片内容");
        }
        BufferedImage resized = resizeProductImage(image);
        if (image.getColorModel().hasAlpha()) {
            return new EncodedImage(encodePng(resized), Workbook.PICTURE_TYPE_PNG);
        }
        return new EncodedImage(encodeJpeg(resized), Workbook.PICTURE_TYPE_JPEG);
    }

    private BufferedImage resizeProductImage(BufferedImage image) {
        int longestEdge = Math.max(image.getWidth(), image.getHeight());
        if (longestEdge <= MAX_PRODUCT_IMAGE_EDGE) {
            return image;
        }
        double scale = (double) MAX_PRODUCT_IMAGE_EDGE / longestEdge;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int imageType = image.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB;
        BufferedImage resized = new BufferedImage(width, height, imageType);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private byte[] encodePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("PNG 编码器不可用");
            }
            return output.toByteArray();
        }
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG 编码器不可用");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            if (imageOutput == null) {
                throw new IOException("无法创建 JPEG 输出流");
            }
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private XSSFDrawing drawingOf(XSSFSheet sheet) {
        XSSFDrawing existing = sheet.getDrawingPatriarch();
        return existing != null ? existing : sheet.createDrawingPatriarch();
    }

    private void insertPicture(XSSFWorkbook workbook, XSSFDrawing drawing, XSSFSheet sheet,
                               CellRangeAddress region, byte[] imageBytes, int pictureType) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            throw new IOException("无法读取图片尺寸");
        }
        XSSFClientAnchor anchor = anchorFor(sheet, region, image.getWidth(), image.getHeight());
        int pictureIndex = workbook.addPicture(imageBytes, pictureType);
        drawing.createPicture(anchor, pictureIndex);
    }

    private record EncodedImage(byte[] content, int pictureType) {
    }

    /**
     * 计算图片在目标区域内“等比缩放、居中”的锚点，等价于前端 {@code object-contain}。
     */
    private XSSFClientAnchor anchorFor(XSSFSheet sheet, CellRangeAddress region, int imageWidth, int imageHeight)
            throws IOException {
        int boxLeft = 0;
        for (int column = 0; column < region.getFirstColumn(); column++) {
            boxLeft += Math.round(sheet.getColumnWidthInPixels(column));
        }
        int boxTop = 0;
        for (int row = 0; row < region.getFirstRow(); row++) {
            boxTop += rowHeightInPixels(sheet, row);
        }
        int boxWidth = 0;
        for (int column = region.getFirstColumn(); column <= region.getLastColumn(); column++) {
            boxWidth += Math.round(sheet.getColumnWidthInPixels(column));
        }
        int boxHeight = 0;
        for (int row = region.getFirstRow(); row <= region.getLastRow(); row++) {
            boxHeight += rowHeightInPixels(sheet, row);
        }
        boxWidth -= IMAGE_PADDING_PIXELS * 2;
        boxHeight -= IMAGE_PADDING_PIXELS * 2;
        if (boxWidth <= 0 || boxHeight <= 0) {
            throw new IOException("箱唛模板图片区域尺寸无效: " + region.formatAsString());
        }
        double scale = Math.min((double) boxWidth / imageWidth, (double) boxHeight / imageHeight);
        int width = Math.max(1, (int) Math.round(imageWidth * scale));
        int height = Math.max(1, (int) Math.round(imageHeight * scale));
        int left = boxLeft + IMAGE_PADDING_PIXELS + (boxWidth - width) / 2;
        int top = boxTop + IMAGE_PADDING_PIXELS + (boxHeight - height) / 2;
        return anchorAt(sheet, left, top, width, height);
    }

    private XSSFClientAnchor anchorAt(XSSFSheet sheet, int left, int top, int boxWidth, int boxHeight)
            throws IOException {
        int[] horizontal = spanOf(sheet, left, boxWidth, true);
        int[] vertical = spanOf(sheet, top, boxHeight, false);
        return new XSSFClientAnchor(
                horizontal[1] * Units.EMU_PER_PIXEL, vertical[1] * Units.EMU_PER_PIXEL,
                horizontal[3] * Units.EMU_PER_PIXEL, vertical[3] * Units.EMU_PER_PIXEL,
                horizontal[0], vertical[0], horizontal[2], vertical[2]);
    }

    /**
     * 把 [offset, offset + size) 这段像素区间换算成 (起始列/行, 起始偏移, 结束列/行, 结束偏移)。
     */
    private int[] spanOf(XSSFSheet sheet, int offset, int size, boolean horizontal) {
        int startIndex = indexAt(sheet, offset, horizontal);
        int startOffset = offset - pixelStart(sheet, startIndex, horizontal);
        int endPixel = offset + size;
        int endIndex = indexAt(sheet, endPixel, horizontal);
        int endOffset = endPixel - pixelStart(sheet, endIndex, horizontal);
        return new int[]{startIndex, startOffset, endIndex, endOffset};
    }

    private int indexAt(XSSFSheet sheet, int pixel, boolean horizontal) {
        int index = 0;
        while (pixelStart(sheet, index + 1, horizontal) <= pixel && index < 255) {
            index++;
        }
        return index;
    }

    private int pixelStart(XSSFSheet sheet, int index, boolean horizontal) {
        int start = 0;
        for (int cursor = 0; cursor < index; cursor++) {
            start += horizontal
                    ? Math.round(sheet.getColumnWidthInPixels(cursor))
                    : rowHeightInPixels(sheet, cursor);
        }
        return start;
    }

    private int rowHeightInPixels(XSSFSheet sheet, int row) {
        if (sheet.getRow(row) == null) {
            return Math.round(sheet.getDefaultRowHeightInPoints() * 96f / 72f);
        }
        return Math.round(sheet.getRow(row).getHeightInPoints() * 96f / 72f);
    }

    private void setCellValue(XSSFSheet sheet, int column, int row, String value) {
        sheet.getRow(row).getCell(column).setCellValue(value == null ? "" : value);
    }
}
