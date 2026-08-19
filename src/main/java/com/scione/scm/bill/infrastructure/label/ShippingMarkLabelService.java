package com.scione.scm.bill.infrastructure.label;

import com.scione.scm.bill.application.port.ShippingMarkFileStore;
import com.scione.scm.bill.application.port.ShippingMarkLabelRenderer;
import com.scione.scm.bill.domain.shippingmark.ShippingMarkDetail;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 渲染固定版式箱唛 Excel 标签的基础设施适配器。
 *
 * <p>条码和商品图通过 WPS 的 DISPIMG 函数嵌入单元格：模板中 C7、D2 已包含指向
 * {@code xl/cellimages.xml} 嵌入图片的 DISPIMG 公式，渲染时保留公式不动，
 * 仅替换包内对应的 media 图片内容。
 */
@Service
@RequiredArgsConstructor
public class ShippingMarkLabelService implements ShippingMarkLabelRenderer {

    private static final String TEMPLATE_PATH = "templates/shipping-mark-template.xlsx";
    private static final int PURCHASE_ORDER_CELL_ROW = 0;
    private static final int SKU_CELL_ROW = 2;
    private static final int IMAGE_ROW = 1;
    private static final int IMAGE_COLUMN = 3;
    /** 模板 DISPIMG 公式引用的嵌入图片 ID 及其 media 路径，必须与模板保持一致。 */
    private static final String BARCODE_IMAGE_ID = "ID_19C5C4A065724DC6AD4D971564D4D23F";
    private static final String PRODUCT_IMAGE_ID = "ID_EA79BAC59F5D48DDA73110FD6B8C7F42";
    private static final String BARCODE_MEDIA_ENTRY = "xl/media/image1.png";
    private static final String PRODUCT_MEDIA_ENTRY = "xl/media/image2.png";
    private static final String CELL_IMAGES_ENTRY = "xl/cellimages.xml";
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    private final Code128BarcodeService barcodeService;
    private final ShippingMarkFileStore fileStore;

    @Override
    public String barcodeDataUrl(String skuCode) throws IOException {
        return barcodeService.generateDataUrl(skuCode);
    }

    @Override
    public byte[] render(ShippingMarkDetail detail) throws IOException {
        byte[] barcode = barcodeService.generate(detail.getSkuCode());
        byte[] productImage = loadProductImage(detail);
        byte[] workbookContent = fillTextCells(detail, productImage != null);
        return embedCellImages(workbookContent, barcode, productImage);
    }

    private byte[] fillTextCells(ShippingMarkDetail detail, boolean hasProductImage) throws IOException {
        ClassPathResource template = new ClassPathResource(TEMPLATE_PATH);
        try (InputStream input = template.getInputStream(); Workbook workbook = new XSSFWorkbook(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheetAt(0);
            setCellValue(sheet, 2, PURCHASE_ORDER_CELL_ROW, detail.getPurchaseOrderNo());
            setCellValue(sheet, 2, SKU_CELL_ROW, detail.getSkuCode());
            if (!hasProductImage) {
                // 无商品图时移除 DISPIMG 公式，避免展示模板占位图
                sheet.getRow(IMAGE_ROW).getCell(IMAGE_COLUMN).setBlank();
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    /**
     * 商品图是可选内容；缺失或格式无法识别时返回 null，不阻断标签生成。
     */
    private byte[] loadProductImage(ShippingMarkDetail detail) {
        if (detail.getSkuImage() == null || detail.getSkuImage().isBlank()) {
            return null;
        }
        try {
            return toPng(fileStore.read(detail.getSkuImage()));
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * media 部件以 .png 命名（内容类型 image/png），非 PNG 图片统一转码。
     */
    private byte[] toPng(byte[] content) throws IOException {
        if (isPng(content)) {
            return content;
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
        if (image == null) {
            throw new IOException("无法读取图片内容");
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private boolean isPng(byte[] content) {
        if (content.length < PNG_MAGIC.length) {
            return false;
        }
        for (int index = 0; index < PNG_MAGIC.length; index++) {
            if (content[index] != PNG_MAGIC[index]) {
                return false;
            }
        }
        return true;
    }

    private byte[] embedCellImages(byte[] workbookContent, byte[] barcode, byte[] productImage) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(workbookContent));
             ZipOutputStream output = new ZipOutputStream(result)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                byte[] content = input.readAllBytes();
                if (BARCODE_MEDIA_ENTRY.equals(entry.getName())) {
                    content = barcode;
                } else if (PRODUCT_MEDIA_ENTRY.equals(entry.getName()) && productImage != null) {
                    content = productImage;
                } else if (CELL_IMAGES_ENTRY.equals(entry.getName())) {
                    content = patchCellImages(content, barcode, productImage);
                }
                output.putNextEntry(new ZipEntry(entry.getName()));
                output.write(content);
                output.closeEntry();
            }
        }
        return result.toByteArray();
    }

    private byte[] patchCellImages(byte[] source, byte[] barcode, byte[] productImage) throws IOException {
        String xml = new String(source, StandardCharsets.UTF_8);
        // 移除 WPS 条码对象元数据，防止 WPS 按模板旧条码内容重绘覆盖替换后的图片
        xml = xml.replaceAll("<etc:extObjData[^>]*/>", "");
        xml = updateImageExtent(xml, BARCODE_IMAGE_ID, barcode);
        if (productImage != null) {
            xml = updateImageExtent(xml, PRODUCT_IMAGE_ID, productImage);
        }
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 将嵌入图片声明的原始尺寸（a:ext，EMU）更新为替换后图片的实际尺寸，保证显示比例正确。
     */
    private String updateImageExtent(String xml, String imageId, byte[] imageContent) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageContent));
        if (image == null) {
            throw new IOException("无法读取图片尺寸");
        }
        int idIndex = xml.indexOf(imageId);
        if (idIndex < 0) {
            throw new IOException("箱唛模板缺少嵌入图片定义: " + imageId);
        }
        int extStart = xml.indexOf("<a:ext ", idIndex);
        int extEnd = extStart < 0 ? -1 : xml.indexOf("/>", extStart);
        if (extStart < 0 || extEnd < 0) {
            throw new IOException("箱唛模板嵌入图片定义无效: " + imageId);
        }
        String extent = "<a:ext cx=\"" + (long) image.getWidth() * Units.EMU_PER_PIXEL
                + "\" cy=\"" + (long) image.getHeight() * Units.EMU_PER_PIXEL + "\"/>";
        return xml.substring(0, extStart) + extent + xml.substring(extEnd + 2);
    }

    private void setCellValue(Sheet sheet, int column, int row, String value) {
        sheet.getRow(row).getCell(column).setCellValue(value == null ? "" : value);
    }
}
