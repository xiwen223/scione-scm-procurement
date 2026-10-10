package com.scione.scm.bill.infrastructure.template;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 合同明细图片的嵌入前压缩。
 *
 * <p>表格里的商品图只按 40×40 像素显示（见 {@code insertPlaceholderImage} 里的锚点偏移），
 * 但领星回传的是原图，单张常见 0.5MB 左右。直接把原图字节塞进工作簿，18 条明细就能把
 * 导出的 PDF 顶到 9MB，而 PDF 还要跨境上传到 S3，实测要几十秒。</p>
 *
 * <p>这里按显示尺寸降采样并重编码为 JPEG，把单张从 0.5MB 压到十几 KB，观感不变。
 * 只处理明细商品图；模板自带的 logo、二维码等图形不经过本类。</p>
 */
@Slf4j
@Component
public class ContractImageCompressor {

    /**
     * 目标最长边像素。图片显示尺寸是 40px，256px 相当于约 600 DPI，
     * 打印绰绰有余，在阅读器里放大到 6 倍以上才会看出损失。
     */
    private static final int MAX_DIMENSION = 256;

    /** JPEG 质量。40px 的显示尺寸下，0.85 与更高品质肉眼无差别。 */
    private static final float JPEG_QUALITY = 0.85F;

    /** 原图本身已经很小就不再重编码，避免无谓的质量损失。 */
    private static final int SKIP_BELOW_BYTES = 32 * 1024;

    /**
     * 批量压缩，保持入参顺序。
     *
     * @param images 已按 URL 去重的图片字节（键为图片 URL）
     * @return 压缩后的图片字节；输入为空时原样返回
     */
    public Map<String, byte[]> compressAll(String contractNo, Map<String, byte[]> images) {
        if (images == null || images.isEmpty()) {
            return images;
        }
        long start = System.nanoTime();
        Map<String, byte[]> compressed = new LinkedHashMap<>(images.size());
        long bytesBefore = 0;
        long bytesAfter = 0;
        int reEncoded = 0;
        for (Map.Entry<String, byte[]> entry : images.entrySet()) {
            byte[] raw = entry.getValue();
            byte[] result = compress(raw);
            compressed.put(entry.getKey(), result);
            bytesBefore += raw == null ? 0 : raw.length;
            bytesAfter += result == null ? 0 : result.length;
            if (result != raw) {
                reEncoded++;
            }
        }
        log.info("合同图片压缩完成：contractNo={}, 张数={}, 重编码={}, {}KB->{}KB, elapsedMs={}",
                contractNo, images.size(), reEncoded, bytesBefore / 1024, bytesAfter / 1024,
                (System.nanoTime() - start) / 1_000_000);
        return compressed;
    }

    /**
     * 压缩单张图片。
     *
     * <p>解码失败（例如 ImageIO 不支持的 webp）或重编码后反而更大时，返回原字节，
     * 由调用方按原格式嵌入 —— 压缩只是优化，不能因为它失败而丢掉图片。</p>
     */
    byte[] compress(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return raw;
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(raw));
            if (source == null) {
                log.debug("图片无法解码，按原样嵌入：bytes={}", raw.length);
                return raw;
            }
            int maxSide = Math.max(source.getWidth(), source.getHeight());
            if (maxSide <= MAX_DIMENSION && raw.length <= SKIP_BELOW_BYTES) {
                return raw;
            }
            byte[] encoded = encodeJpeg(scale(source, maxSide));
            return encoded.length < raw.length ? encoded : raw;
        } catch (IOException | RuntimeException ex) {
            log.info("图片压缩失败，按原样嵌入：bytes={}, exception={}", raw.length, ex.getClass().getSimpleName());
            return raw;
        }
    }

    /** 等比例缩放到最长边不超过 {@link #MAX_DIMENSION}；已足够小的图片只做白底合成。 */
    private BufferedImage scale(BufferedImage source, int maxSide) {
        int targetMax = Math.min(maxSide, MAX_DIMENSION);
        BufferedImage current = source;
        // 一次缩太多会出锯齿，先按 2 倍逐级收缩，最后一步缩到精确尺寸。
        while (Math.max(current.getWidth(), current.getHeight()) / 2 >= targetMax) {
            current = draw(current, Math.max(1, current.getWidth() / 2), Math.max(1, current.getHeight() / 2));
        }
        double ratio = (double) targetMax / Math.max(current.getWidth(), current.getHeight());
        int width = Math.max(1, (int) Math.round(current.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(current.getHeight() * ratio));
        return draw(current, width, height);
    }

    /** 统一画到白底 RGB 上：商品图可能是带透明通道的 PNG，直接转 JPEG 会把透明区域变黑。 */
    private BufferedImage draw(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("运行环境缺少 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            stream.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
