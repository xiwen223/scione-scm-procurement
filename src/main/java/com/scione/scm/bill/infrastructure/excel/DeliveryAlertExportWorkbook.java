package com.scione.scm.bill.infrastructure.excel;

import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.DeliveryAlertExportRowPO;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 跟单预警看板导出 Excel：一行一个采购单+SKU，字段顺序固定，不再把多条数据拼进同一格。
 */
@Component
@Slf4j
public class DeliveryAlertExportWorkbook {

    private static final List<String> HEADERS = List.of(
            "采购计划", "采购单", "SKU", "商品名称", "商品类型", "供应商", "采购员",
            "采购仓库", "交付进度", "采购单创建日", "跟单员", "预计交期", "原因", "备注");
    private static final int[] COLUMN_WIDTHS = {18, 20, 18, 30, 10, 28, 16, 18, 12, 20, 12, 12, 36, 36};
    private static final DateTimeFormatter CREATED_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_CELL_LENGTH = 32_767;
    private static final int ROW_WINDOW = 200;

    public byte[] write(List<DeliveryAlertExportRowPO> rows, Map<String, String> typeByGroup) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW);
        try {
            Sheet sheet = workbook.createSheet("跟单预警看板");
            sheet.createFreezePane(0, 1);
            writeHeader(workbook, sheet);
            for (int index = 0; index < rows.size(); index++) {
                writeRow(sheet.createRow(index + 1), rows.get(index), typeByGroup);
            }
            if (!rows.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, rows.size(), 0, HEADERS.size() - 1));
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "生成跟单预警看板导出文件失败");
        } finally {
            workbook.dispose();
            try {
                workbook.close();
            } catch (IOException exception) {
                log.warn("关闭跟单预警看板导出工作簿失败", exception);
            }
        }
    }

    private static void writeHeader(SXSSFWorkbook workbook, Sheet sheet) {
        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        Row header = sheet.createRow(0);
        for (int column = 0; column < HEADERS.size(); column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(HEADERS.get(column));
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(column, COLUMN_WIDTHS[column] * 256);
        }
    }

    private static void writeRow(Row row, DeliveryAlertExportRowPO item, Map<String, String> typeByGroup) {
        String[] values = {
                item.getPlanSn(),
                item.getOrderSn(),
                item.getSku(),
                item.getProductName(),
                typeByGroup.get(item.getGroupKey()),
                item.getSupplier(),
                item.getBuyer(),
                item.getWarehouse(),
                progress(item),
                item.getCreatedTime() == null ? "" : item.getCreatedTime().format(CREATED_TIME_FORMAT),
                item.getFollowerName(),
                item.getExpectedDeliveryDate() == null ? "" : item.getExpectedDeliveryDate().toString(),
                item.getReason(),
                item.getRemark()
        };
        for (int column = 0; column < values.length; column++) {
            row.createCell(column).setCellValue(limit(values[column]));
        }
    }

    private static String progress(DeliveryAlertExportRowPO item) {
        long ready = item.getQtyReady() == null ? 0 : item.getQtyReady();
        long plan = item.getQtyPlan() == null ? 0 : item.getQtyPlan();
        return ready + " / " + plan;
    }

    private static String limit(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_CELL_LENGTH ? value : value.substring(0, MAX_CELL_LENGTH);
    }
}
