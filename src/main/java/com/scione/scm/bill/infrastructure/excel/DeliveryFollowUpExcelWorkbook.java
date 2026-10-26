package com.scione.scm.bill.infrastructure.excel;

import com.scione.scm.bill.application.port.DeliveryFollowUpWorkbook;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 六列模板与现有导入表一致，不负责模板管理和文件存储。 */
@Component
public class DeliveryFollowUpExcelWorkbook implements DeliveryFollowUpWorkbook {
    private static final List<String> HEADERS = List.of("采购单", "SKU", "跟单员", "预计交期", "原因", "备注");
    private static final int MAX_ROWS = 5000;
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    @Override
    public Parsed parse(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            return invalid("文件为空");
        }
        if (content.length > MAX_BYTES) {
            return invalid("文件不能超过5MB");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            return invalid("只支持xlsx、xls格式");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() != 1) {
                return invalid("请使用仅包含一张工作表的标准模板");
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            if (!values(sheet.getRow(0), formatter).equals(HEADERS)
                    || hasExtraCells(sheet.getRow(0), formatter)) {
                return invalid("表头必须依次为：" + String.join("、", HEADERS));
            }
            if (sheet.getLastRowNum() > MAX_ROWS) {
                return invalid("数据行不能超过5000行");
            }
            List<InputRow> rows = new ArrayList<>();
            List<RowError> errors = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                List<String> cells = values(row, formatter);
                if (cells.stream().allMatch(String::isBlank) && !hasExtraCells(row, formatter)) {
                    continue;
                }
                boolean formula = false;
                if (row != null) {
                    for (Cell cell : row) {
                        formula |= cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR;
                    }
                }
                if (formula || hasExtraCells(row, formatter)) {
                    errors.add(new RowError(index + 1, cells, formula ? "不支持公式或错误单元格，请粘贴为值"
                            : "模板六列以外存在数据"));
                } else {
                    rows.add(new InputRow(index + 1, cells));
                }
            }
            return rows.isEmpty() && errors.isEmpty() ? invalid("未包含有效数据行") : new Parsed(rows, errors);
        } catch (IOException | RuntimeException exception) {
            // 损坏文件也按可下载的导入错误反馈，不向用户返回底层解析异常。
            return invalid("文件损坏、已加密或不是有效的Excel文件");
        }
    }

    private static List<String> values(Row row, DataFormatter formatter) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < HEADERS.size(); index++) {
            Cell cell = row == null ? null : row.getCell(index);
            if (index == 3 && cell != null && cell.getCellType() == CellType.NUMERIC
                    && DateUtil.isCellDateFormatted(cell) && DateUtil.isValidExcelDate(cell.getNumericCellValue())) {
                values.add(cell.getLocalDateTimeCellValue().toLocalDate().toString());
            } else {
                values.add(formatter.formatCellValue(cell).trim());
            }
        }
        return values;
    }

    private static boolean hasExtraCells(Row row, DataFormatter formatter) {
        if (row == null) {
            return false;
        }
        for (int index = HEADERS.size(); index < row.getLastCellNum(); index++) {
            if (!formatter.formatCellValue(row.getCell(index)).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static Parsed invalid(String message) {
        return new Parsed(List.of(), List.of(new RowError(0, List.of(), message)));
    }

    @Override
    public byte[] errorFile(List<RowError> errors) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("跟单情况");
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADERS.size(); index++) {
                header.createCell(index).setCellValue(HEADERS.get(index));
                sheet.setColumnWidth(index, (index < 3 ? 24 : 36) * 256);
            }
            if (!errors.isEmpty()) {
                header.createCell(HEADERS.size()).setCellValue("原文件行号");
                header.createCell(HEADERS.size() + 1).setCellValue("错误原因");
                sheet.setColumnWidth(HEADERS.size() + 1, 70 * 256);
            }
            int index = 1;
            for (RowError error : errors) {
                Row row = sheet.createRow(index++);
                for (int col = 0; col < Math.min(HEADERS.size(), error.values().size()); col++) {
                    // 显式写字符串，避免用户输入以 = 开头时被解释成Excel公式。
                    row.createCell(col, CellType.STRING).setCellValue(limit(error.values().get(col)));
                }
                row.createCell(HEADERS.size()).setCellValue(error.rowNumber());
                row.createCell(HEADERS.size() + 1).setCellValue(limit(error.message()));
            }
            sheet.createFreezePane(0, 1);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "生成跟单Excel文件失败");
        }
    }

    private static String limit(String value) {
        return value.length() <= 32767 ? value : value.substring(0, 32767);
    }

}
