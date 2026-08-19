package com.scione.scm.bill.infrastructure.label;

import com.scione.scm.bill.application.port.ShippingMarkFileStore;
import com.scione.scm.bill.domain.shippingmark.ShippingMarkDetail;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingMarkLabelServiceTest {

    private static final String BARCODE_IMAGE_ID = "ID_19C5C4A065724DC6AD4D971564D4D23F";
    private static final String PRODUCT_IMAGE_ID = "ID_EA79BAC59F5D48DDA73110FD6B8C7F42";

    @Test
    void embedsImagesAsWpsCellImages() throws IOException {
        byte[] image = png(200, 400);
        ShippingMarkLabelService service = new ShippingMarkLabelService(new Code128BarcodeService(), fileStore(image));
        ShippingMarkDetail detail = new ShippingMarkDetail("B", "PO", "SKU", "name", "a.png", "1", "u");

        byte[] rendered = service.render(detail);
        Map<String, byte[]> entries = unzip(rendered);

        assertThat(entries.get("xl/media/image2.png")).isEqualTo(image);
        BufferedImage barcode = ImageIO.read(new ByteArrayInputStream(entries.get("xl/media/image1.png")));
        assertThat(barcode).isNotNull();

        String cellImages = new String(entries.get("xl/cellimages.xml"), StandardCharsets.UTF_8);
        assertThat(cellImages).doesNotContain("extObjData");
        assertThat(cellImages)
                .contains(extent(barcode.getWidth(), barcode.getHeight()))
                .contains(extent(200, 400));

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(rendered))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(3).getCellFormula()).contains("DISPIMG").contains(PRODUCT_IMAGE_ID);
            assertThat(sheet.getRow(6).getCell(2).getCellFormula()).contains("DISPIMG").contains(BARCODE_IMAGE_ID);
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("PO");
            assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("SKU");
        }
    }

    @Test
    void clearsProductImageFormulaWhenImageMissing() throws IOException {
        ShippingMarkLabelService service = new ShippingMarkLabelService(new Code128BarcodeService(), fileStore(null));
        ShippingMarkDetail detail = new ShippingMarkDetail("B", "PO", "SKU", "name", null, "1", "u");

        byte[] rendered = service.render(detail);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(rendered))) {
            Cell imageCell = workbook.getSheetAt(0).getRow(1).getCell(3);
            assertThat(imageCell.getCellType()).isEqualTo(CellType.BLANK);
            assertThat(workbook.getSheetAt(0).getRow(6).getCell(2).getCellFormula()).contains("DISPIMG");
        }
    }

    private static ShippingMarkFileStore fileStore(byte[] image) {
        return new ShippingMarkFileStore() {
            @Override public String storeImage(String billNo, byte[] content, String extension) { return null; }
            @Override public String storeLabel(String billNo, byte[] content) { return null; }
            @Override public byte[] read(String storedFileReference) throws IOException {
                if (image == null) {
                    throw new IOException("missing");
                }
                return image;
            }
            @Override public byte[] load(String billNo, String category, String fileName) { return null; }
        };
    }

    private static String extent(int widthPixels, int heightPixels) {
        return "<a:ext cx=\"" + widthPixels * 9525L + "\" cy=\"" + heightPixels * 9525L + "\"/>";
    }

    private static Map<String, byte[]> unzip(byte[] content) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
