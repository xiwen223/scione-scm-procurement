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
    private static final List<String> HEADERS = List.of("采购单", "SKU", "跟单员", "预计交期", "原因", "备注");//六列表头，顺序固定
    private static final int MAX_ROWS = 5000;//数据行上限
    private static final int MAX_BYTES = 5 * 1024 * 1024;//文件大小上限5MB

    /** 解析上传的跟单Excel：先做文件级校验，再逐行读成 InputRow，坏行单独记进 errors 不影响其他有效行。 */
    @Override
    public Parsed parse(String fileName, byte[] content) {
        // 第1步：文件级校验（空文件、超5MB、非xlsx/xls 都直接返回错误）。
        if (content == null || content.length == 0) {
            return invalid("文件为空");
        }
        if (content.length > MAX_BYTES) {
            return invalid("文件不能超过5MB");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);//文件名转小写，方便判断后缀
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            return invalid("只支持xlsx、xls格式");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {//用字节流创建Workbook
            // 第2步：结构校验（只允许一张表、表头必须严格等于六列模板）。
            if (workbook.getNumberOfSheets() != 1) {
                return invalid("请使用仅包含一张工作表的标准模板");//多张表不行
            }
            Sheet sheet = workbook.getSheetAt(0);//取第一张表
            DataFormatter formatter = new DataFormatter(Locale.CHINA);//格式化单元格内容为文本，中文环境
            if (!values(sheet.getRow(0), formatter).equals(HEADERS)
                    || hasExtraCells(sheet.getRow(0), formatter)) {
                return invalid("表头必须依次为：" + String.join("、", HEADERS));//表头和六列模板不一致，或表头后面有多余列
            }
            if (sheet.getLastRowNum() > MAX_ROWS) {
                return invalid("数据行不能超过5000行");
            }
            // 第3步：逐行读取。空白行跳过；含公式/错误单元格或六列外有数据的行记为错误，其余进 rows。
            List<InputRow> rows = new ArrayList<>();//有效行
            List<RowError> errors = new ArrayList<>();//错误行
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {//从第2行开始（第1行是表头）
                Row row = sheet.getRow(index);//当前行
                List<String> cells = values(row, formatter);//读这一行的六列值
                if (cells.stream().allMatch(String::isBlank) && !hasExtraCells(row, formatter)) {
                    continue;//整行空白且没有多余数据，跳过
                }
                boolean formula = false;//这行是否含公式/错误单元格
                if (row != null) {
                    for (Cell cell : row) {
                        formula |= cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR;//有公式或错误单元格就标记
                    }
                }
                if (formula || hasExtraCells(row, formatter)) {
                    errors.add(new RowError(index + 1, cells, formula ? "不支持公式或错误单元格，请粘贴为值"
                            : "模板六列以外存在数据"));//含公式或有多余列，记错误行
                } else {
                    rows.add(new InputRow(index + 1, cells));//正常行，记入有效行
                }
            }
            return rows.isEmpty() && errors.isEmpty() ? invalid("未包含有效数据行") : new Parsed(rows, errors);//全空则返回错误，否则返回解析结果
        } catch (IOException | RuntimeException exception) {
            // 损坏文件也按可下载的导入错误反馈，不向用户返回底层解析异常。
            return invalid("文件损坏、已加密或不是有效的Excel文件");
        }
    }

    /** 读一行六列的值；第4列（预计交期）是日期单元格时统一转成 yyyy-MM-dd 字符串，其余按文本trim。 */
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

    /** 检查一行是否在六列模板之外还写了数据（防止用户加列导致内容错位）。 */
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

    /** 生成一个"整体失败"的解析结果：没有有效行，只有一个行号为0的错误。 */
    private static Parsed invalid(String message) {
        return new Parsed(List.of(), List.of(new RowError(0, List.of(), message)));
    }

    /** 把解析出的错误行生成一份可下载的错误Excel：六列模板 + 原行号 + 错误原因，便于用户对照修正后重新导入。 */
    @Override
    public byte[] errorFile(List<RowError> errors) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {//新建xlsx工作簿和输出流
            Sheet sheet = workbook.createSheet("跟单情况");//工作表叫"跟单情况"
            Row header = sheet.createRow(0);//第0行是表头
            // 先写六列表头，并给前三列（短字段）和后三列（长字段）不同的列宽。
            for (int index = 0; index < HEADERS.size(); index++) {
                header.createCell(index).setCellValue(HEADERS.get(index));//写入六列表头
                sheet.setColumnWidth(index, (index < 3 ? 24 : 36) * 256);//前三列窄、后三列宽
            }
            // 有错误时才追加「原文件行号」「错误原因」两列。
            if (!errors.isEmpty()) {
                header.createCell(HEADERS.size()).setCellValue("原文件行号");//第7列表头
                header.createCell(HEADERS.size() + 1).setCellValue("错误原因");//第8列表头
                sheet.setColumnWidth(HEADERS.size() + 1, 70 * 256);//错误原因列加宽
            }
            // 逐条错误写一行：原始六列值 + 行号 + 原因。
            int index = 1;//从第1行开始写数据
            for (RowError error : errors) {
                Row row = sheet.createRow(index++);//每写一行，行号+1
                for (int col = 0; col < Math.min(HEADERS.size(), error.values().size()); col++) {
                    // 显式写字符串，避免用户输入以 = 开头时被解释成Excel公式。
                    row.createCell(col, CellType.STRING).setCellValue(limit(error.values().get(col)));//写原始六列值（截断超长）
                }
                row.createCell(HEADERS.size()).setCellValue(error.rowNumber());//写原文件行号
                row.createCell(HEADERS.size() + 1).setCellValue(limit(error.message()));//写错误原因
            }
            sheet.createFreezePane(0, 1);//冻结首行，方便滚动时看到表头
            workbook.write(output);//把工作簿写入字节流
            return output.toByteArray();//返回字节数组
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "生成跟单Excel文件失败");
        }
    }

    /** Excel单元格文本上限32767字符，超长截断防止POI写入异常。 */
    private static String limit(String value) {
        return value.length() <= 32767 ? value : value.substring(0, 32767);
    }

}
