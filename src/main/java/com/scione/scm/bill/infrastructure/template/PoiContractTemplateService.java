package com.scione.scm.bill.infrastructure.template;

import com.scione.scm.bill.application.port.ContractTemplateService;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.NumberToChineseUtil;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * Apache POI 实现的合同模板填充服务。
 */
@Slf4j
@Service
public class PoiContractTemplateService implements ContractTemplateService {

    private static final String TEMPLATE_PATH = "templates/采购合同模版.xlsx";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public byte[] fillTemplate(Contract contract) {
        try (InputStream templateStream = new ClassPathResource(TEMPLATE_PATH).getInputStream();
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

                // 复制第20行的样式到新插入的行
                Row templateRow = sheet.getRow(startRow + baseDetailRows - 1); // 第20行作为模板
                for (int i = 0; i < needInsertRows; i++) {
                    Row newRow = sheet.createRow(insertPosition + i);
                    copyRowStyle(templateRow, newRow, workbook);
                }

                log.info("新行插入完成：插入位置=第{}行, 插入行数={}", insertPosition + 1, needInsertRows);
            }

            // 填充明细数据
            for (int i = 0; i < itemCount; i++) {
                ContractItem item = contract.getItems().get(i);
                Row row = getOrCreateRow(sheet, startRow + i);

                // A列：图片（暂时留空，后续处理）
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

            // 根据实际明细行数动态计算后续行的位置
            // 由于已经通过 shiftRows 完成了行的下移，rowOffset 就是实际明细数与模板默认行数的差值
            int actualDetailRows = Math.max(itemCount, baseDetailRows); // 实际占用的行数
            int rowOffset = actualDetailRows - baseDetailRows; // 需要下移的行数

            // 第21行（索引20）：总价合计（K列有公式）
            int totalRow = 20 + rowOffset;

            // 第22行（索引21）：折扣行
            int discountRow = 21 + rowOffset;
            setCellValue(sheet, discountRow, 10, BigDecimal.ZERO); // K列：折扣金额

            // 第23行（索引22）：金额合计（大写）
            int amountRow = 22 + rowOffset;
            String amountChinese = NumberToChineseUtil.convert(contract.getContractAmount());
            setCellValue(sheet, amountRow, 2, amountChinese);      // C列：大写金额
            setCellValue(sheet, amountRow, 9, "整");                // J列：整
            setCellValue(sheet, amountRow, 10, contract.getContractAmount()); // K列：合计金额

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
            int settlementRow = 29 + rowOffset;
            setCellValue(sheet, settlementRow, 1, "到货质检无误清点数量后凭本合同7天后付清"); // B列

            log.info("合同明细填充完成：明细数={}, 实际占用行数={}, 偏移量={}", itemCount, actualDetailRows, rowOffset);

            workbook.write(outputStream);
            log.info("合同模板填充成功：contractNo={}", contract.getContractNo());
            return outputStream.toByteArray();

        } catch (Exception ex) {
            log.error("合同模板填充失败：contractNo={}", contract.getContractNo(), ex);
            throw new BusinessException(ResultCode.CONTRACT_TEMPLATE_FILL_FAILED);
        }
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
}