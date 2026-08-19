package com.scione.scm.bill.infrastructure.label;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * 生成 Code 128 条码 PNG。
 */
@Component
public class Code128BarcodeService {

    private static final int IMAGE_WIDTH = 266;
    private static final int IMAGE_HEIGHT = 142;
    private static final int BARCODE_X = 10;
    private static final int BARCODE_Y = 10;
    private static final int BARCODE_WIDTH = IMAGE_WIDTH - BARCODE_X * 2;
    private static final int BARCODE_HEIGHT = 100;
    private static final int SKU_CAPTION_TOP = BARCODE_Y + BARCODE_HEIGHT;
    private static final Font SKU_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 17);

    public byte[] generate(String value) throws IOException {
        BitMatrix matrix;
        try {
            // 参考“商品编码条码.png”：266×142 画布，条码区为 (10,10) 起的 246×100 区域。
            matrix = new MultiFormatWriter().encode(
                    value, BarcodeFormat.CODE_128, BARCODE_WIDTH, BARCODE_HEIGHT,
                    Map.of(EncodeHintType.MARGIN, 0));
        } catch (WriterException exception) {
            throw new IOException("Failed to generate Code 128 barcode", exception);
        }

        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

            int[] contentBounds = matrix.getEnclosingRectangle();
            if (contentBounds == null) {
                throw new IOException("Generated Code 128 barcode contains no bars");
            }
            // ZXing 会在矩阵中居中保留静区；移除这部分内部留白后，按参考图铺满 246px 条码区。
            for (int y = 0; y < BARCODE_HEIGHT; y++) {
                int sourceY = contentBounds[1] + y * contentBounds[3] / BARCODE_HEIGHT;
                for (int x = 0; x < BARCODE_WIDTH; x++) {
                    int sourceX = contentBounds[0] + x * contentBounds[2] / BARCODE_WIDTH;
                    if (matrix.get(sourceX, sourceY)) {
                        image.setRGB(BARCODE_X + x, BARCODE_Y + y, Color.BLACK.getRGB());
                    }
                }
            }

            graphics.setColor(Color.BLACK);
            graphics.setFont(SKU_FONT);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            FontMetrics fontMetrics = graphics.getFontMetrics();
            int textX = (IMAGE_WIDTH - fontMetrics.stringWidth(value)) / 2;
            int captionHeight = IMAGE_HEIGHT - SKU_CAPTION_TOP;
            int textY = SKU_CAPTION_TOP + (captionHeight - fontMetrics.getHeight()) / 2
                    + fontMetrics.getAscent();
            graphics.drawString(value, textX, textY);
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    public String generateDataUrl(String value) throws IOException {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(generate(value));
    }
}
