package com.scione.scm.bill.infrastructure.excel;

import com.scione.scm.bill.application.port.ShippingMarkImportParser.ImageData;
import com.scione.scm.bill.application.port.ShippingMarkImportParser.ImportDocument;
import com.scione.scm.bill.application.port.ShippingMarkImportParser.ParsedDetail;
import com.scione.scm.bill.application.port.ShippingMarkImportParser.ParsedImport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实采购订单 doc/0723订单.xlsx 验证解析器。
 */
@Slf4j
class ShippingMarkExcelParserTest {

    private static final Path ORDER_FILE = Path.of("doc", "0723订单.xlsx");

    private static final ShippingMarkExcelParser parser = new ShippingMarkExcelParser();

    private static byte[] content;

    @BeforeAll
    static void loadFile() throws IOException {
        content = Files.readAllBytes(ORDER_FILE);
    }

    @Test
    void parse_shouldParseRealOrderFile() {
        ParsedImport parsed = parser.parse(new ImportDocument(ORDER_FILE.getFileName().toString(), content));

        assertThat(parsed.fileName()).isEqualTo("0723订单.xlsx");
        assertThat(parsed.details()).hasSize(17);

        ParsedDetail first = parsed.details().get(0);
        assertThat(first.purchaseOrderNo()).isEqualTo("PO260723150");
        assertThat(first.skuCode()).isEqualTo("WJ220479");
        assertThat(first.skuName()).contains("异形本");
        assertThat(first.image()).isNotNull();

        ParsedDetail last = parsed.details().get(16);
        assertThat(last.purchaseOrderNo()).isEqualTo("PO260723229");
        assertThat(last.skuCode()).isEqualTo("WJ219632");
        assertThat(last.skuName()).contains("飞机盒");
    }

    @Test
    void parse_shouldExtractWpsDispimgImages() {
        ParsedImport parsed = parser.parse(new ImportDocument(ORDER_FILE.getFileName().toString(), content));

        // 前 16 行带 DISPIMG 图片，末行（飞机盒备注）无图
        assertThat(parsed.details().stream().filter(d -> d.image() != null)).hasSize(16);
    }

    /**
     * 将解析到的图片落盘到 target/import-images，便于人工核对图片与行对应是否正确。
     */
    @Test
    void dumpParsedImages_toLocalFiles() throws IOException {
        ParsedImport parsed = parser.parse(new ImportDocument(ORDER_FILE.getFileName().toString(), content));
        Path dir = Path.of("target", "import-images");
        Files.createDirectories(dir);

        int index = 1;
        for (ParsedDetail detail : parsed.details()) {
            ImageData image = detail.image();
            if (image == null) {
                continue;
            }
            Path target = dir.resolve(String.format("%02d_%s_%s.%s",
                    index++, detail.purchaseOrderNo(), detail.skuCode(), image.extension()));
            Files.write(target, image.content());
            log.info(target.toAbsolutePath().toString());
            assertThat(target).exists().isNotEmptyFile();
        }
    }
}
