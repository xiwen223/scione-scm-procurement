package com.scione.scm.bill.infrastructure.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.scione.scm.bill.application.port.ContractPdfConverter;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** 将 POI 已填充的工作簿转成 XHTML，再在 Java 内渲染 PDF。 */
@Slf4j
@Component
public class HtmlContractPdfConverter implements ContractPdfConverter {
    private static final String CHINESE_FONT = "/fonts/contract-chinese.ttf";

    @Override
    public byte[] convert(byte[] xlsxBytes, String contractNo) {
        long start = System.nanoTime();
        try {
            if (HtmlContractPdfConverter.class.getResource(CHINESE_FONT) == null) {
                throw new IllegalStateException("JAR 内缺少中文字体资源：" + CHINESE_FONT);
            }
            String html = new ExcelContractHtmlExporter().export(xlsxBytes);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            // 每次渲染提供新的流，OpenHTMLToPDF 负责关闭。支持 IDE 和 Spring Boot JAR，
            // 不把 JAR 内资源转换成 File，也不依赖操作系统字体或外部路径配置。
            builder.useFont(HtmlContractPdfConverter::openChineseFont, "ContractChinese");
            builder.withHtmlContent(html, null);
            builder.toStream(output);
            builder.run();
            byte[] pdf = addPageNumbers(output.toByteArray());
            if (pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F') {
                throw new IllegalStateException("PDF output is invalid");
            }
            log.info("Excel→HTML→PDF转换完成：contractNo={}, bytes={}, elapsedMs={}",
                    contractNo, pdf.length, (System.nanoTime() - start) / 1_000_000);
            return pdf;
        } catch (Exception ex) {
            log.error("Excel→HTML→PDF转换失败：contractNo={}", contractNo, ex);
            throw new BusinessException(ResultCode.CONTRACT_TEMPLATE_FILL_FAILED,
                    "合同PDF生成失败，请检查模板和字体资源");
        }
    }

    private static InputStream openChineseFont() {
        InputStream stream = HtmlContractPdfConverter.class.getResourceAsStream(CHINESE_FONT);
        if (stream == null) throw new IllegalStateException("JAR 内缺少中文字体资源：" + CHINESE_FONT);
        return stream;
    }

    /** 生成结束后写入总页数，避免 HTML 引擎对 pages 计数器支持差异。 */
    private byte[] addPageNumbers(byte[] pdf) throws Exception {
        try (PDDocument document = PDDocument.load(pdf); InputStream fontStream = openChineseFont()) {
            PDType0Font font = PDType0Font.load(document, fontStream, true);
            int total = document.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                var page = document.getPage(i);
                String label = "第 " + (i + 1) + " 页，共 " + total + " 页";
                float size = 9;
                float width = font.getStringWidth(label) / 1000 * size;
                try (PDPageContentStream content = new PDPageContentStream(document, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    content.setNonStrokingColor(0f);
                    content.beginText();
                    content.setFont(font, size);
                    content.newLineAtOffset(page.getMediaBox().getLowerLeftX() + (page.getMediaBox().getWidth() - width) / 2,
                            page.getMediaBox().getLowerLeftY() + 10);
                    content.showText(label);
                    content.endText();
                }
            }
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            document.save(result);
            return result.toByteArray();
        }
    }
}
