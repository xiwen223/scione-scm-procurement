package com.scione.scm.bill.application;

import com.scione.api.data.client.S3Client;
import com.scione.scm.bill.application.dto.CompanyGoodsImageDTO;
import feign.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import com.scione.api.data.dto.FileInfo;
import com.scione.common.response.ApiResponse;
import org.apache.commons.lang3.StringUtils;
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

/** 货品图片使用公开桶存储，数据库保存对象键及固定URL，页面访问时可按需签发临时地址。 */
@Service
@Slf4j
public class CompanyGoodsImageService {
    @Resource
    private S3Client s3Client;

    /**
     * 校验页面提供的非空Base64图片格式、内容类型及编码文本长度。
     *
     * @param image 页面提交的Base64图片内容
     */
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

    /**
     *  图片仅在保存时转为 MultipartFile，直接上传到公开桶。
     *
     * @param goodsId 货品数据库主键
     * @param image 页面提交的Base64图片内容
     * @return 新图片的公共桶对象键
     */
    public String upload(Long goodsId, String image) {
        int separator = image.indexOf(',');
        String contentType = image.substring(5, image.indexOf(';'));
        String extension = contentType.substring("image/".length());
        byte[] content = Base64.getDecoder().decode(image.substring(separator + 1));
        MultipartFile file = new GoodsImageFile(goodsId + "." + extension, contentType, content);
        FileInfo uploaded = requireSuccess(s3Client.uploadPublic(file, "company-goods/" + goodsId), "上传图片");
        if (uploaded == null || StringUtils.isBlank(uploaded.getObjectKey())) {
            throw new BusinessException(ResultCode.STORAGE_API_ERROR, "存储服务未返回图片objectKey");
        }
        return uploaded.getObjectKey();
    }

    /**
     * 调用公共桶预签名接口生成页面访问图片的临时URL。
     *
     * @param objectKey S3对象键，不包含桶名及访问域名
     * @return 有效期为30分钟的预签名访问地址
     */
    public String accessUrl(String objectKey) {
        String url = requireSuccess(s3Client.getPublicPresignedUrl(objectKey, 30L), "获取图片地址");
        if (StringUtils.isBlank(url)) {
            throw new BusinessException(ResultCode.STORAGE_API_ERROR, "存储服务未返回图片访问地址");
        }
        return url;
    }

    /**
     * 删除公共桶中指定货品图片对象。
     *
     * @param objectKey S3对象键，不包含桶名及访问域名
     */
    public void delete(String objectKey) {
        if (StringUtils.isBlank(objectKey)) return;
        requireSuccess(s3Client.deletePublic(objectKey), "删除图片");
    }

    /**
     *  将默认桶中的图片复制到公开桶，保留主图标识；事务回滚时清理新文件，原文件保留。
     *
     * @param pictures 包含图片地址、对象键及主图标识的图片列表
     * @return 包含新对象键、固定图片地址及主图标识的图片列表
     */
    public List<CompanyGoodsImageDTO> copyToPublic(List<CompanyGoodsImageDTO> pictures) {
        List<CompanyGoodsImageDTO> copiedPictures = new ArrayList<>();
        for (CompanyGoodsImageDTO picture : pictures) {
            String sourceKey = picture.getObjectKey();
            if (StringUtils.isBlank(sourceKey)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "图片objectKey不能为空");
            }
            String filename = sourceKey.substring(sourceKey.lastIndexOf('/') + 1);
            try (Response response = s3Client.download(sourceKey)) {
                if (response == null || response.status() != 200 || response.body() == null) {
                    throw new BusinessException(ResultCode.STORAGE_API_ERROR, "图片下载失败：" + sourceKey);
                }
                byte[] content;
                try (InputStream input = response.body().asInputStream()) {
                    content = input.readAllBytes();
                }
                if (content.length == 0) {
                    throw new BusinessException(ResultCode.STORAGE_API_ERROR, "下载的图片为空：" + sourceKey);
                }
                String contentType = "application/octet-stream";
                for (var header : response.headers().entrySet()) {
                    if ("Content-Type".equalsIgnoreCase(header.getKey())) {
                        for (String value : header.getValue()) {
                            contentType = value;
                            break;
                        }
                        break;
                    }
                }
                MultipartFile file = new GoodsImageFile(filename, contentType, content);
                FileInfo uploaded = requireSuccess(s3Client.uploadPublic(file, "company-goods"), "复制图片");
                if (uploaded == null || StringUtils.isBlank(uploaded.getObjectKey())) {
                    throw new BusinessException(ResultCode.STORAGE_API_ERROR, "存储服务未返回图片objectKey");
                }
                String copiedKey = uploaded.getObjectKey();
                // 主、子货品统一事务，后续任意货品保存失败都清理本次复制的文件。
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    /**
                     * 图片复制所属事务回滚时清理本次上传的公共桶对象。
                     *
                     * @param status 事务完成状态，使用Spring事务同步状态常量
                     */
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            try {
                                delete(copiedKey);
                            } catch (RuntimeException exception) {
                                log.error("回滚清理货品图片失败，objectKey={}", copiedKey, exception);
                            }
                        }
                    }
                });
                CompanyGoodsImageDTO copied = new CompanyGoodsImageDTO();
                copied.setObjectKey(copiedKey);
                copied.setIsPrimary(picture.getIsPrimary());
                copied.setPicUrl(publicUrl(copiedKey));
                copiedPictures.add(copied);
            } catch (IOException exception) {
                throw new BusinessException(ResultCode.STORAGE_API_ERROR, "读取图片失败：" + sourceKey);
            }
        }
        return copiedPictures;
    }

    /**
     *  按公开桶对象键生成固定访问地址，供各保存入口统一落库。
     *
     * @param objectKey S3对象键，不包含桶名及访问域名
     * @return 不含预签名过期参数的公共访问URL
     */
    public String publicUrl(String objectKey) {
        String encodedKey = URLEncoder.encode(objectKey, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("%2F", "/");
        return "https://scione-public-bucket-dev.s3.us-east-1.amazonaws.com/" + encodedKey;
    }

    /**
     * 校验存储服务统一响应成功后提取业务结果。
     *
     * @param <T> 业务返回值或JSON数组元素类型
     * @param response S3服务返回的统一业务响应
     * @param action 存储操作名称，用于生成失败提示
     * @return 存储服务返回的业务内容
     */
    private <T> T requireSuccess(ApiResponse<T> response, String action) {
        if (response == null) {
            throw new BusinessException(ResultCode.STORAGE_API_ERROR, action + "失败：存储服务无响应");
        }
        if (!response.isSuccess()) {
            throw new BusinessException(ResultCode.STORAGE_API_ERROR,
                    action + "失败：" + response.getCode() + "，" + response.getMessage());
        }
        return response.getData();
    }

    /** 与 S3ContractFileStore 一致，用内存文件适配既有 multipart 上传客户端。 */
    private record GoodsImageFile(String filename, String contentType, byte[] bytes) implements MultipartFile {
        /**
         * 返回S3上传接口约定的multipart字段名。
         *
         * @return 固定字段名file
         */
        @Override public String getName() { return "file"; }
        /**
         * 返回当前内存图片的文件名。
         *
         * @return 上传时使用的图片文件名
         */
        @Override public String getOriginalFilename() { return filename; }
        /**
         * 返回当前内存图片的MIME类型。
         *
         * @return 图片内容类型
         */
        @Override public String getContentType() { return contentType; }
        /**
         * 判断当前内存图片是否没有内容。
         *
         * @return 图片字节长度为0时返回true
         */
        @Override public boolean isEmpty() { return bytes.length == 0; }
        /**
         * 取得当前内存图片的大小。
         *
         * @return 图片字节数
         */
        @Override public long getSize() { return bytes.length; }
        /**
         * 取得当前内存图片的二进制内容。
         *
         * @return 内存中保存的图片字节数组
         */
        @Override public byte[] getBytes() { return bytes; }
        /**
         * 为当前内存图片创建读取流。
         *
         * @return 基于图片字节数组的输入流
         */
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        /**
         * 将当前内存图片写入指定本地文件。
         *
         * @param destination 接收图片内容的本地目标文件
         * @throws IOException 目标文件写入失败时抛出
         */
        @Override public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), bytes); }
    }
}
