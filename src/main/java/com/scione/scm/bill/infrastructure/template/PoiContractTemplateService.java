package com.scione.scm.bill.infrastructure.template;

import com.scione.api.data.client.S3Client;
import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.port.ContractTemplateService;
import com.scione.scm.bill.application.port.LingxingSupplierClient;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.NumberToChineseUtil;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractItem;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.ContractTemplateMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.ContractTemplatePO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTMarker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.format.DateTimeFormatter;

/**
 * Apache POI 实现的合同模板填充服务。
 */
@Slf4j
@Service
public class PoiContractTemplateService implements ContractTemplateService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Resource
    private ContractTemplateMapper contractTemplateMapper;

    @Autowired
    @Qualifier("com.scione.api.data.client.S3Client")
    private S3Client s3Client;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private LingxingSupplierClient lingxingSupplierClient;

    @Override
    public Long resolveDefaultTemplateId(Integer contractType) {
        return contractTemplateMapper.findPage(null, contractType, 1, true, 0, 1).stream()
                .findFirst()
                .map(ContractTemplatePO::getId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND,
                        "未找到合同类型对应的启用默认模板：" + contractType));
    }

    @Override
    public byte[] fillTemplate(Contract contract) {
        log.info("========== 开始填充合同模板 ==========");
        log.info("合同信息：contractNo={}, contractType={}, supplierName={}",
                contract.getContractNo(), contract.getContractType(), contract.getSupplierName());

        try {
            // 1. 根据合同类型查询默认模板
            log.info("步骤1：查询默认合同模板 - contractType={}", contract.getContractType());
            ContractTemplatePO template = contractTemplateMapper.findPage(
                    null,  // keyword
                    contract.getContractType(),  // contractType（已经是Integer类型）
                    1,     // isActive=1
                    true,  // defaultOnly=true
                    0,     // offset
                    1      // pageSize
            ).stream()
             .findFirst()
             .orElseThrow(() -> new BusinessException(
                     ResultCode.RESOURCE_NOT_FOUND,
                     "未找到合同类型对应的默认模板：" + contract.getContractType()));

            log.info("步骤1完成 - 找到默认模板：");
            log.info("  - 模板ID: {}", template.getId());
            log.info("  - 模板名称: {}", template.getTemplateName());
            log.info("  - S3对象键: {}", template.getObjectKey());
            log.info("  - 是否默认: {}", template.getIsDefault());
            log.info("  - 是否激活: {}", template.getIsActive());

            // 2. 调用Feign客户端获取预签名URL
            log.info("步骤2：获取模板预签名URL");
            log.info("  - 调用服务: scione-data-platform");
            log.info("  - 请求objectKey: {}", template.getObjectKey());
            log.info("  - 有效期: 30分钟");

            ApiResponse<String> response = s3Client.getPresignedUrl(
                    template.getObjectKey(),
                    30L  // 30分钟有效期
            );

            if (!response.isSuccess() || response.getData() == null) {
                log.error("步骤2失败 - 获取预签名URL失败：code={}, message={}",
                        response.getCode(), response.getMessage());
                throw new BusinessException(
                        ResultCode.SYSTEM_ERROR,
                        "获取模板预签名URL失败：" + response.getMessage());
            }

            String presignedUrl = response.getData();
            log.info("步骤2完成 - 获取预签名URL成功");
            log.info("  - URL长度:  字符", presignedUrl.length());
            log.info("  - URL前100字符: {}", presignedUrl.length() > 100 ? presignedUrl.substring(0, 100) + "..." : presignedUrl);

            // 3. 通过URL下载模板到内存
            log.info("步骤3：下载模板文件");
            log.info("  - 下载方式: RestTemplate.getForObject");
            log.info("  - 目标URL: {}", presignedUrl.length() > 100 ? presignedUrl.substring(0, 100) + "..." : presignedUrl);

            // 将预签名URL转换为URI对象，避免RestTemplate二次编码
            URI templateUri = URI.create(presignedUrl);
            log.info("  - 使用URI对象避免二次编码");

            byte[] templateBytes = restTemplate.getForObject(templateUri, byte[].class);

            if (templateBytes == null || templateBytes.length == 0) {
                log.error("步骤3失败 - 下载的模板文件为空");
                throw new BusinessException(
                        ResultCode.SYSTEM_ERROR,
                        "下载模板文件失败：文件为空");
            }

            log.info("步骤3完成 - 模板下载成功");
            log.info("  - 文件大小: {} 字节 ( KB)", templateBytes.length, templateBytes.length / 1024);
            log.info("  - 文件来源: S3存储 -> objectKey={}", template.getObjectKey());

            // 4. 用下载的模板创建Workbook
            log.info("步骤4：开始填充模板内容");
            try (InputStream templateStream = new ByteArrayInputStream(templateBytes);
                 Workbook workbook = new XSSFWorkbook(templateStream);
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

                Sheet sheet = workbook.getSheetAt(0);

            // 填充需方公司信息
            setCellValue(sheet, 0, 0, contract.getBuyerCompanyName());                              // A1：需方公司名称
            setCellValue(sheet, 1, 0, "地址：" + contract.getBuyerAddress() + "     邮编：" + contract.getPostCode()); // A2：地址和邮编
            setCellValue(sheet, 2, 0, "电话：" + contract.getBuyerPhone() + "     传真：" + contract.getFax());      // A3：电话和传真

            // 填充供方信息
            setCellValue(sheet, 4, 1, contract.getSupplierName());           // B5：供方名称
            setCellValue(sheet, 4, 7, contract.getContractNo());             // F5：合同编号
            setCellValue(sheet, 5, 1, contract.getSupplierAddress());        // B6：供方地址
            // H6：签订日期（点击签署时填充，此处留空）
            setCellValue(sheet, 6, 1, contract.getContactPerson());          // B7：联系人
            setCellValue(sheet, 6, 7, contract.getBuyerAddress());           // H7：签订地点（需方公司地址）
            setCellValue(sheet, 7, 1, contract.getSupplierPhone());          // B8：联系电话（供方）

            log.info("填充合同头部信息成功：contractNo={}, 联系人={}, 需方={}",
                    contract.getContractNo(), contract.getContactPerson(), contract.getBuyerCompanyName());

            // 填充明细（从第12行开始）
            int startRow = 11;        // 第12行（索引从0开始）
            int baseDetailRows = 9;   // 模板默认的明细行数（第12-20行）
            int itemCount = contract.getItems().size();

            log.info("开始填充合同明细：contractNo={}, 明细数量={}", contract.getContractNo(), itemCount);

            // 如果明细数量超过模板行数，需要插入新行
            if (itemCount > baseDetailRows) {
                int needInsertRows = itemCount - baseDetailRows;
                log.info("明细数量超过模板行数，需要插入新行: 模板行数=, 实际明细数={}, 需插入行数={}",
                        baseDetailRows, itemCount, needInsertRows);

                // 在第21行（索引20）之前插入新行
                int insertPosition = startRow + baseDetailRows; // 第21行
                sheet.shiftRows(insertPosition, sheet.getLastRowNum(), needInsertRows, true, false);

                // 调整图片位置：将插入位置之后的所有图片向下移动
                adjustDrawingPositions(sheet, insertPosition, needInsertRows);
                log.info("图片位置调整完成：插入位置=第{}行, 下移行数={}", insertPosition + 1, needInsertRows);

                // 复制第20行的样式到新插入的行
                Row templateRow = sheet.getRow(startRow + baseDetailRows - 1); // 第20行作为模板
                for (int i = 0; i < needInsertRows; i++) {
                    Row newRow = sheet.createRow(insertPosition + i);
                    copyRowStyle(templateRow, newRow, workbook);
                }

                log.info("新行插入完成：插入位置=第{}行, 插入行数={}", insertPosition + 1, needInsertRows);
            }

            // 填充明细数据
            log.info("开始填充明细数据并插入图片：明细数量={}", itemCount);

            // 获取或创建Drawing patriarch（用于插入图片）
            Drawing<?> drawingPatriarch = sheet.createDrawingPatriarch();
            CreationHelper helper = workbook.getCreationHelper();

            for (int i = 0; i < itemCount; i++) {
                ContractItem item = contract.getItems().get(i);
                Row row = getOrCreateRow(sheet, startRow + i);
                int currentRowIndex = startRow + i;

                // A列：图片
                if (item.getPicUrl() != null && !item.getPicUrl().isEmpty()) {
                    try {
                        log.info("开始下载商品图片：rowIndex={}, sku={}, picUrl={}",
                                currentRowIndex, item.getSku(), item.getPicUrl());

                        // 1. 下载图片
                        byte[] imageBytes = restTemplate.getForObject(item.getPicUrl(), byte[].class);

                        if (imageBytes != null && imageBytes.length > 0) {
                            // 2. 判断图片类型(根据URL或内容)
                            int pictureType = determinePictureType(item.getPicUrl(), imageBytes);

                            // 3. 添加图片到工作簿
                            int pictureIdx = workbook.addPicture(imageBytes, pictureType);

                            // 4. 创建锚点(定位图片位置)
                            // 使用像素偏移模式，在A列内部居中显示小图片（40x40像素，约1.06cm x 1.06cm）
                            ClientAnchor anchor = helper.createClientAnchor();
                            anchor.setCol1(0);  // A列
                            anchor.setDx1(5 * Units.EMU_PER_PIXEL);  // 左边距5像素
                            anchor.setRow1(currentRowIndex);  // 当前行
                            anchor.setDy1(5 * Units.EMU_PER_PIXEL);  // 上边距5像素
                            anchor.setCol2(0);  // 仍然在A列（不跨列）
                            anchor.setDx2(45 * Units.EMU_PER_PIXEL);  // 右边界 = 5 + 40像素
                            anchor.setRow2(currentRowIndex);  // 仍然在当前行（不跨行）
                            anchor.setDy2(45 * Units.EMU_PER_PIXEL);  // 下边界 = 5 + 40像素

                            // 5. 插入图片
                            Picture picture = drawingPatriarch.createPicture(anchor, pictureIdx);

                            // 6. 不需要调整大小，锚点已经定义了40x40像素的精确尺寸
                            // 设置行高确保图片显示完整（40像素约等于30 points）
                            if (row.getHeight() < 600) { // 如果行高小于30 points
                                row.setHeight((short) 600); // 设置为30 points，足够显示40像素高的图片
                            }

                            log.info("图片插入成功：rowIndex={}, sku={}, imageSize={} bytes",
                                    currentRowIndex, item.getSku(), imageBytes.length);
                        } else {
                            log.warn("下载的图片为空：rowIndex={}, sku={}, picUrl={}",
                                    currentRowIndex, item.getSku(), item.getPicUrl());
                        }

                    } catch (Exception ex) {
                        // 单个图片失败不影响整体,记录日志继续处理
                        log.error("图片下载或插入失败（继续处理其他明细）：rowIndex={}, sku={}, picUrl={}",
                                currentRowIndex, item.getSku(), item.getPicUrl(), ex);
                    }
                } else {
                    log.debug("明细无图片URL：rowIndex={}, sku={}", currentRowIndex, item.getSku());
                }

                setCellValue(row, 1, item.getSku());                          // B列：货号
                setCellValue(row, 2, item.getProductName());                  // C列：品名及规格
                // D列：一箱有几个（暂时留空，领星没有这个字段）
                setCellValue(row, 4, "套");                                   // E列：套
                // F列：有几箱（暂时留空，领星没有这个字段）
                setCellValue(row, 6, "箱");                                   // G列：箱
                setCellValue(row, 7, item.getQuantity());                     // H列：总套数
                setCellValue(row, 8, "套");                                   // I列：套
                setCellValue(row, 9, item.getUnitPrice());                    // J列：不含税单价
                setCellValue(row, 10, item.getAmount());                      // K列：总价
            }

            log.info("明细数据和图片填充完成");


            // 根据实际明细行数动态计算后续行的位置
            // 由于已经通过 shiftRows 完成了行的下移，rowOffset 就是实际明细数与模板默认行数的差值
            int actualDetailRows = Math.max(itemCount, baseDetailRows); // 实际占用的行数
            int rowOffset = actualDetailRows - baseDetailRows; // 需要下移的行数

            // 第21行（索引20）：总价合计（K列显示原价）
            int totalRow = 20 + rowOffset;
            BigDecimal originalAmount = contract.getOriginalAmount();
            setCellValue(sheet, totalRow, 10, originalAmount); // K列：原价合计（所有明细的总价）
            log.info("填充原价合计：行号={}, 原价={}", totalRow + 1, originalAmount);

            // 第22行（索引21）：折扣行
            int discountRow = 21 + rowOffset;
            BigDecimal discountAmount = contract.getDiscountedAmount() != null
                    ? contract.getDiscountedAmount()
                    : BigDecimal.ZERO; // 如果没有折扣，默认为0
            setCellValue(sheet, discountRow, 9, "折扣");            // J列：显示"折扣"文字
            setCellValue(sheet, discountRow, 10, discountAmount);   // K列：折扣金额（可能是0，后续修改接口会改）
            log.info("填充折扣金额：行号={}, 折扣={}", discountRow + 1, discountAmount);

            // 第23行（索引22）：金额合计（实际价格 = 原价 - 折扣）
            int amountRow = 22 + rowOffset;
            BigDecimal finalAmount = contract.getContractAmount(); // 实际合同金额（原价 - 折扣后的金额）
            String amountChinese = NumberToChineseUtil.convert(finalAmount);
            setCellValue(sheet, amountRow, 0, "金额合计（大写）：");   // A列：标题
            setCellValue(sheet, amountRow, 2, amountChinese);        // C列：大写金额
            setCellValue(sheet, amountRow, 9, "整");                  // J列：整
            setCellValue(sheet, amountRow, 10, finalAmount);         // K列：实际合计金额
            log.info("填充实际金额：行号={}, 原价={}, 折扣={}, 实际金额={}",
                    amountRow + 1, originalAmount, discountAmount, finalAmount);

            // 第28行（索引27）：交货方式 - 包含 "由我司专人验货合格后方允许出运，并于  年  月  日前..."
            int deliveryRow = 27 + rowOffset;

            // 使用合同的 deliveryDate（自动创建时从明细取最早日期，手动创建时从前端传入）
            if (contract.getDeliveryDate() != null) {
                String deliveryDateStr = contract.getDeliveryDate().format(DATE_FORMATTER);
                String[] dateParts = deliveryDateStr.split("-");
                if (dateParts.length == 3) {
                    // 在 B28 的文本中填充年月日
                    String deliveryText = "由我司专人验货合格后方允许出运，并于 " + dateParts[0] + " 年 "
                            + dateParts[1] + " 月 " + dateParts[2] + " 日前送至指定仓库.如若不能按时交货，供方承担由此引起的损失。";
                    setCellValue(sheet, deliveryRow, 1, deliveryText); // B列
                }
            } else {
                log.warn("交货日期为空，跳过填充：contractNo={}", contract.getContractNo());
            }

            // 第30行（索引29）：结算方式
            // 结算方式条款保留线上模板原文，不以合同字段覆盖。

            log.info("合同明细填充完成：明细数={}, 实际占用行数={}, 偏移量={}", itemCount, actualDetailRows, rowOffset);

            // 结算条款：自动合同写入领星默认收款账户的账户名称、账号和开户行。
            int settlementRow = 29 + rowOffset;
            String settlementText = settlementText(contract);
            setCellValue(sheet, settlementRow, 1, settlementText);
            // 条款较长时换行展示，避免遮挡下一行内容。
            setWrappedCellStyle(sheet, settlementRow, 1, 32F);
            log.info("填充结算条款：contractNo={}, row={}, defaultAccountNamePresent={}, bankAccountPresent={}, bankNamePresent={}",
                    contract.getContractNo(), settlementRow + 1,
                    org.springframework.util.StringUtils.hasText(contract.getSupplierAccountName()),
                    org.springframework.util.StringUtils.hasText(contract.getSupplierBankAccount()),
                    org.springframework.util.StringUtils.hasText(contract.getSupplierBankName()));

            workbook.write(outputStream);
            byte[] result = outputStream.toByteArray();

            log.info("========== 合同模板填充完成 ==========");
            log.info("最终结果：");
            log.info("  - 合同编号: {}", contract.getContractNo());
            log.info("  - 使用模板: {} (ID: {})", template.getTemplateName(), template.getId());
            log.info("  - 模板来源: S3存储 (objectKey: {})", template.getObjectKey());
            log.info("  - 明细数量: {} 条", contract.getItems().size());
            log.info("  - 输出文件大小: {} 字节 ({} KB)", result.length, result.length / 1024);
            log.info("==========================================");

            return result;

            }  // 关闭 try-with-resources (InputStream, Workbook, ByteArrayOutputStream)

        } catch (BusinessException ex) {
            // 业务异常直接抛出
            throw ex;
        } catch (Exception ex) {
            log.error("合同模板填充失败：contractNo={}", contract.getContractNo(), ex);
            throw new BusinessException(ResultCode.CONTRACT_TEMPLATE_FILL_FAILED);
        }
    }

    private String settlementText(Contract contract) {
        // 固定条款来自合同模板；这里只补充默认收款账户信息，不能擅自改写付款期限。
        String baseText = "到货质检无误入库后，次月月底前支付货款。";
        if (org.springframework.util.StringUtils.hasText(contract.getSupplierAccountName())
                && org.springframework.util.StringUtils.hasText(contract.getSupplierBankAccount())
                && org.springframework.util.StringUtils.hasText(contract.getSupplierBankName())) {
            return baseText + "（收款人：" + contract.getSupplierAccountName()
                    + "；银行账号：" + contract.getSupplierBankAccount()
                    + "；开户行：" + contract.getSupplierBankName() + "）";
        }
        if (contract.getSupplierId() == null) {
            return baseText;
        }
        try {
            return lingxingSupplierClient.findDefaultPaymentAccount(contract.getSupplierId())
                    .map(account -> baseText + "（收款人：" + account.accountName()
                            + "；银行账号：" + account.accountId()
                            + "；开户行：" + account.bankName() + "）")
                    .orElse(baseText);
        } catch (RuntimeException ex) {
            log.warn("获取供应商默认收款账号失败，使用基础结算说明：supplierId={}", contract.getSupplierId());
            return baseText;
        }
    }

    /**
     * 调整图片位置：当插入新行后，将插入位置之后的所有图片向下移动
     *
     * @param sheet          工作表
     * @param insertPosition 插入位置（行索引）
     * @param rowsToShift    需要下移的行数
     */
    private void adjustDrawingPositions(Sheet sheet, int insertPosition, int rowsToShift) {
        if (!(sheet instanceof XSSFSheet)) {
            log.warn("只有XSSFSheet支持图片位置调整，当前sheet类型：{}", sheet.getClass().getName());
            return;
        }

        XSSFSheet xssfSheet = (XSSFSheet) sheet;
        XSSFDrawing drawing = xssfSheet.getDrawingPatriarch();

        if (drawing == null) {
            log.info("工作表中没有图片，跳过位置调整");
            return;
        }

        // 获取底层的绘图XML对象
        org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTDrawing ctDrawing = drawing.getCTDrawing();

        if (ctDrawing == null || ctDrawing.getTwoCellAnchorList() == null) {
            log.info("工作表中没有锚定的图片对象");
            return;
        }

        int adjustedCount = 0;

        // 遍历所有的双单元格锚点（图片通常使用这种锚点）
        for (org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTTwoCellAnchor anchor :
             ctDrawing.getTwoCellAnchorList()) {

            CTMarker from = anchor.getFrom();
            CTMarker to = anchor.getTo();

            if (from != null && to != null) {
                int fromRow = from.getRow();
                int toRow = to.getRow();

                // 如果图片的起始行在插入位置之后，则需要向下移动
                if (fromRow >= insertPosition) {
                    from.setRow(fromRow + rowsToShift);
                    to.setRow(toRow + rowsToShift);
                    adjustedCount++;

                    log.debug("调整图片位置：原始行范围=[{}-{}], 调整后=[{}-{}]",
                            fromRow, toRow, fromRow + rowsToShift, toRow + rowsToShift);
                }
            }
        }

        log.info("共调整了{}个图片的位置", adjustedCount);
    }

    private void setCellValue(Sheet sheet, int rowIndex, int colIndex, Object value) {
        Row row = getOrCreateRow(sheet, rowIndex);
        setCellValue(row, colIndex, value);
    }

    private void setCellValue(Row row, int colIndex, Object value) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) {
            cell = row.createCell(colIndex);
        }

        if (value == null) {
            cell.setBlank();
        } else if (value instanceof String) {
            cell.setCellValue((String) value);
        } else if (value instanceof Integer) {
            cell.setCellValue((Integer) value);
        } else if (value instanceof BigDecimal) {
            cell.setCellValue(((BigDecimal) value).doubleValue());
        } else {
            cell.setCellValue(value.toString());
        }
    }

    /**
     * 模板中结算信息包含收款人、账号和开户行，需显式开启换行并预留足够行高。
     * 合并单元格仅需设置左上角单元格的样式。
     */
    private void setWrappedCellStyle(Sheet sheet, int rowIndex, int colIndex, float minHeightInPoints) {
        Row row = getOrCreateRow(sheet, rowIndex);
        Cell cell = row.getCell(colIndex);
        if (cell == null) {
            cell = row.createCell(colIndex);
        }
        CellStyle wrappedStyle = sheet.getWorkbook().createCellStyle();
        wrappedStyle.cloneStyleFrom(cell.getCellStyle());
        wrappedStyle.setWrapText(true);
        wrappedStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        cell.setCellStyle(wrappedStyle);
        if (row.getHeightInPoints() < minHeightInPoints) {
            row.setHeightInPoints(minHeightInPoints);
        }
    }

    private Row getOrCreateRow(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        return row;
    }

    /**
     * 复制行的样式（包括单元格样式、行高、边框等）
     * 用于当明细数量超过模板行数时，复制模板行的样式到新插入的行
     *
     * @param sourceRow 源行（模板行）
     * @param targetRow 目标行（新插入的行）
     * @param workbook  工作簿对象
     */
    private void copyRowStyle(Row sourceRow, Row targetRow, Workbook workbook) {
        if (sourceRow == null) {
            return;
        }

        // 复制行高
        targetRow.setHeight(sourceRow.getHeight());

        // 复制每个单元格的样式
        for (int i = 0; i < sourceRow.getLastCellNum(); i++) {
            Cell sourceCell = sourceRow.getCell(i);
            if (sourceCell != null) {
                Cell targetCell = targetRow.createCell(i);

                // 复制单元格样式
                CellStyle newStyle = workbook.createCellStyle();
                newStyle.cloneStyleFrom(sourceCell.getCellStyle());
                targetCell.setCellStyle(newStyle);
            }
        }
    }

    /**
     * 根据URL或文件内容判断图片类型
     *
     * @param picUrl     图片URL
     * @param imageBytes 图片字节数组
     * @return POI图片类型常量
     */
    private int determinePictureType(String picUrl, byte[] imageBytes) {
        String url = picUrl.toLowerCase();

        // 优先从URL扩展名判断
        if (url.endsWith(".png") || url.contains(".png?")) {
            return Workbook.PICTURE_TYPE_PNG;
        } else if (url.endsWith(".jpg") || url.endsWith(".jpeg") ||
                url.contains(".jpg?") || url.contains(".jpeg?")) {
            return Workbook.PICTURE_TYPE_JPEG;
        }

        // 如果无法从URL判断,检查文件头
        if (imageBytes.length >= 8) {
            // PNG文件头: 89 50 4E 47 0D 0A 1A 0A
            if (imageBytes[0] == (byte) 0x89 && imageBytes[1] == 0x50 &&
                    imageBytes[2] == 0x4E && imageBytes[3] == 0x47) {
                return Workbook.PICTURE_TYPE_PNG;
            }
            // JPEG文件头: FF D8 FF
            if (imageBytes[0] == (byte) 0xFF && imageBytes[1] == (byte) 0xD8 &&
                    imageBytes[2] == (byte) 0xFF) {
                return Workbook.PICTURE_TYPE_JPEG;
            }
        }

        // 默认返回JPEG（POI 5.x 主要支持 PNG 和 JPEG）
        log.debug("无法识别图片类型，默认使用JPEG：url={}", picUrl);
        return Workbook.PICTURE_TYPE_JPEG;
    }

    /**
     * 调整图片大小以适应单元格（控制图片不要太大）
     *
     * @param picture 图片对象
     * @param row     行对象
     */
    private void resizePictureToFitCell(Picture picture, Row row) {
        // 设置行高为60 points (1 point = 20 twips)
        // 这样图片不会太突兀，大约4厘米高
        if (row.getHeight() < 1200) { // 如果行高小于60 points
            row.setHeight((short) 1200); // 设置为60 points (约4cm)
        }

        // 使用resize方法自动缩放图片以适应锚点定义的区域
        // 由于锚点是从A列到B列，从当前行到下一行，图片会被缩放到这个区域
        picture.resize();

        // 如果觉得图片还是太大，可以进一步缩小
        // picture.resize(0.8); // 缩放到80%
    }
}
