package com.scione.scm.bill.application;

import com.scione.scm.bill.application.vo.FileUploadVO;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

/** 货品图片复用项目文件服务，数据库仅保存对象键，访问地址按需签发。 */
@Service
public class CompanyGoodsImageService {
    @Resource
    private FileApplicationService fileApplicationService;

    public void validate(String image) {
        if (image.length() > 200000 || !image.matches("^data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/]+={0,2}$")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "图片格式无效，请重新上传 JPG、PNG 或 WEBP 图片");
        }
        try {
            byte[] content = Base64.getDecoder().decode(image.substring(image.indexOf(',') + 1));
            boolean jpeg = content.length > 3 && (content[0] & 0xff) == 0xff && (content[1] & 0xff) == 0xd8 && (content[2] & 0xff) == 0xff;
            boolean png = content.length > 8 && content[0] == (byte) 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G';
            boolean webp = content.length > 12 && new String(content, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(content, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
            if (!(jpeg && image.startsWith("data:image/jpeg;")) && !(png && image.startsWith("data:image/png;"))
                    && !(webp && image.startsWith("data:image/webp;"))) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "图片内容与格式不匹配");
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "图片数据无效，请重新上传");
        }
    }

    /** 图片仅在保存时转为 MultipartFile，经 FileApplicationService -> S3Client 上传。 */
    public String upload(Long goodsId, String image) {
        int separator = image.indexOf(',');
        String contentType = image.substring(5, image.indexOf(';'));
        String extension = contentType.substring("image/".length());
        byte[] content = Base64.getDecoder().decode(image.substring(separator + 1));
        MultipartFile file = new GoodsImageFile(goodsId + "." + extension, contentType, content);
        FileUploadVO uploaded = fileApplicationService.upload(file, "company-goods/" + goodsId);
        return uploaded.objectKey();
    }

    public String accessUrl(String objectKey) {
        return fileApplicationService.presignedUrl(objectKey, null);
    }

    public void delete(String objectKey) {
        fileApplicationService.delete(objectKey);
    }

    /** 与 S3ContractFileStore 一致，用内存文件适配既有 multipart 上传客户端。 */
    private record GoodsImageFile(String filename, String contentType, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), bytes); }
    }
}
