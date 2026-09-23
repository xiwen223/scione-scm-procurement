package com.scione.scm.bill.infrastructure.template;

import com.scione.scm.bill.application.port.LingxingSupplierClient.SupplierPaymentAccount;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.NumberToChineseUtil;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.contract.Contract;
import com.scione.scm.bill.domain.contract.ContractItem;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellCopyPolicy;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTBooleanProperty;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合同占位符渲染器。只负责取合同快照、定位与排版，不重新拉 PO、不计算税率、不写数据库。
 * 每个明细占一行；模板中连续的 items 行为预留容量，超出时复制样板行并移动后续内容。
 */
final class ContractPlaceholderRenderer {
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([^{}]+)}");
    // 与旧 settlementText 相同：默认账户也取不到时，只保留基础付款条款。
    private static final Pattern TOKEN_WITH_EMPTY_ACCOUNT = Pattern.compile(
            "（收款人：\\$\\{supplierAccountName}；银行账号：\\$\\{supplierBankAccount}；开户行：\\$\\{supplierBankName}）|\\$\\{([^{}]+)}");
    private static final DateTimeFormatter DELIVERY_DATE = DateTimeFormatter.ofPattern("yyyy 年 MM 月 dd 日");
    private static final Set<String> ITEM_KEYS = Set.of("items.picUrl", "items.productImage", "items.sku",
            "items.productName", "items.specification", "items.quantity", "items.unit", "items.unitPrice",
            "items.unitPriceWithoutTax", "items.amount", "items.casesNum", "items.quantityPerCase");
    private static final Set<String> ACCOUNT_KEYS = Set.of("supplierAccountName", "supplierBankAccount", "supplierBankName");

    /**
     * 填充进来的值统一不加粗，去掉加粗后的字体按「模板原字体」缓存复用，
     * 避免明细多时每个单元格都新建一个字体。渲染器每次填充都是新实例，缓存与单个工作簿同生命周期。
     */
    private final Map<XSSFFont, XSSFFont> plainFonts = new HashMap<>();

    static boolean hasPlaceholders(Workbook workbook) {
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().contains("${")) return true;
                }
            }
        }
        return false;
    }

    void fill(XSSFWorkbook workbook, Contract contract,
              Supplier<Optional<SupplierPaymentAccount>> accountResolver,
              BiConsumer<Cell, ContractItem> imageWriter) {
        // 填充步骤1：构建合同主字段的占位符值，不从领星重拉整份采购单覆盖合同快照。
        Map<String, Object> values = contractValues(contract);
        // 填充步骤2：先检查模板占位符，未知字段在生成前明确失败，避免输出带原占位符的合同。
        Set<String> used = validate(workbook, values.keySet());
        // 同一次生成只解析一次默认账户；没有账户占位符就不做额外的远程查询。
        if (used.stream().anyMatch(ACCOUNT_KEYS::contains)) {
            accountResolver.get().ifPresent(account -> {
                values.put("supplierAccountName", account.accountName());
                values.put("supplierBankAccount", account.accountId());
                values.put("supplierBankName", account.bankName());
            });
        }
        // 一个渲染调用内复用样式，避免明细较多时创建成千上万个 CellStyle。
        Map<String, CellStyle> styles = new HashMap<>();
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            XSSFSheet sheet = workbook.getSheetAt(sheetIndex);
            // 填充步骤3：找出明细模板行及可容纳数量，商品多于预留行才扩展模板。
            List<Integer> detailRows = findDetailRows(sheet);
            int first = detailRows.isEmpty() ? -1 : detailRows.get(0);
            int capacity = detailRows.size();
            if (capacity > 0 && contract.getItems().size() > capacity) {
                int extra = contract.getItems().size() - capacity;
                int insertAt = first + capacity;
                // 扩行时同时移动下方条款、绘图锚点和打印范围，避免图片仍留在旧行位置。
                moveFooter(sheet, insertAt, extra);
                for (int n = 0; n < extra; n++) {
                    sheet.copyRows(first + capacity - 1, first + capacity - 1, insertAt + n,
                            new CellCopyPolicy.Builder().rowHeight(true).build());
                }
                capacity += extra;
            }
            for (Row row : sheet) {
                boolean detail = first >= 0 && row.getRowNum() >= first && row.getRowNum() < first + capacity;
                // Excel 行高以磅计：30pt 约为 40px。长品名仍由下方排版按需撑高。
                if (detail) row.setHeightInPoints(30F);
                int itemIndex = row.getRowNum() - first;
                ContractItem item = detail && itemIndex < contract.getItems().size()
                        ? contract.getItems().get(itemIndex) : null;
                // 填充步骤4：每行复制主字段字典，再加入本行商品数据，防止上一行的明细值串到下一行。
                Map<String, Object> rowValues = new HashMap<>(values);
                if (detail) rowValues.putAll(itemValues(item));
                for (Cell cell : row) {
                    if (cell.getCellType() != CellType.STRING || !cell.getStringCellValue().contains("${")) continue;
                    String original = cell.getStringCellValue();
                    if (original.equals("${items.picUrl}") || original.equals("${items.productImage}")) {
                        // 图片占位符不写普通文本：先清空单元格文字，再以锚点插入图片，文字仍由其他列填充。
                        cell.setBlank();
                        if (item != null) imageWriter.accept(cell, item);
                    } else {
                        replace(cell, rowValues, styles);
                        styleAndFit(cell, original, styles);
                    }
                }
            }
        }
        workbook.setForceFormulaRecalculation(true);
    }

    /** 与旧 fillTemplate 的取值逐项一致；名称为模板别名，不要求与数据库列名同名。 */
    private Map<String, Object> contractValues(Contract contract) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("buyerCompanyName", contract.getBuyerCompanyName());
        values.put("buyerAddress", contract.getBuyerAddress());
        values.put("postCode", contract.getPostCode());
        values.put("buyerPhone", contract.getBuyerPhone());
        values.put("fax", contract.getFax());
        values.put("supplierName", contract.getSupplierName());
        values.put("supplierAddress", contract.getSupplierAddress());
        values.put("contactPerson", contract.getContactPerson());
        values.put("supplierPhone", contract.getSupplierPhone());
        values.put("contractNo", contract.getContractNo());
        values.put("deliveryDate", contract.getDeliveryDate() == null ? "" : DELIVERY_DATE.format(contract.getDeliveryDate()));
        // 创建阶段没有实际签署日期。移除标记后留空，由需方 date_sign 控件在签署时填写。
        values.put("buyerSignDate", "");
        ACCOUNT_KEYS.forEach(key -> values.put(key, ""));
        BigDecimal original = contract.getItems().stream()
                .map(item -> item.getAmount() == null ? BigDecimal.ZERO : item.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = contract.getDiscountedAmount() == null ? BigDecimal.ZERO : contract.getDiscountedAmount();
        BigDecimal actual = original.subtract(discount);
        values.put("originalAmount", original);
        values.put("discount", discount);
        values.put("totalAmount", actual);
        values.put("totalAmountChinese", NumberToChineseUtil.convert(actual));
        return values;
    }

    private Map<String, Object> itemValues(ContractItem item) {
        Map<String, Object> values = new HashMap<>();
        ITEM_KEYS.forEach(key -> values.put(key, ""));
        if (item == null) return values;
        values.put("items.sku", item.getSku());
        values.put("items.productName", item.getProductName());
        values.put("items.specification", item.getSpecification());
        values.put("items.quantity", item.getQuantity());
        values.put("items.unit", item.getUnit() == null || item.getUnit().isBlank() ? "套" : item.getUnit());
        // PO 的 unit_price_without_tax 已在建合同时映射到 contract_item.unit_price。
        // 此处必须保留用户后续修改的合同价格，不能再次除税或用 PO 覆盖。
        values.put("items.unitPriceWithoutTax", item.getUnitPrice());
        values.put("items.unitPrice", item.getUnitPrice());
        values.put("items.amount", item.getAmount());
        values.put("items.casesNum", item.getCasesNum());
        values.put("items.quantityPerCase", item.getQuantityPerCase());
        return values;
    }

    /** 先校验全部工作表，拼错标记必须定位报错，不能悄悄输出未填充的合同。 */
    private Set<String> validate(XSSFWorkbook workbook, Set<String> contractKeys) {
        Set<String> used = new HashSet<>();
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() != CellType.STRING) continue;
                    String text = cell.getStringCellValue();
                    Matcher matcher = TOKEN.matcher(text);
                    while (matcher.find()) {
                        String key = matcher.group(1);
                        if (!contractKeys.contains(key) && !ITEM_KEYS.contains(key)) fail(cell, "不支持的占位符：" + matcher.group());
                        if ((key.equals("items.picUrl") || key.equals("items.productImage")) && !text.equals(matcher.group())) {
                            fail(cell, "图片占位符必须独占单元格");
                        }
                        used.add(key);
                    }
                    if (TOKEN.matcher(text).replaceAll("").contains("${")) fail(cell, "占位符格式不完整");
                }
            }
            findDetailRows(sheet);
        }
        if (used.contains("buyerSignDate")) {
            boolean labelFound = false;
            for (Sheet sheet : workbook) for (Row row : sheet) for (Cell cell : row) {
                if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().contains("签订日期：")) labelFound = true;
            }
            if (!labelFound) throw new BusinessException(ResultCode.CONTRACT_TEMPLATE_FILL_FAILED,
                    "模板缺少签署日期定位文字：签订日期：");
        }
        return used;
    }

    private List<Integer> findDetailRows(Sheet sheet) {
        List<Integer> rows = new ArrayList<>();
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().contains("${items.")) {
                    if (!rows.isEmpty() && row.getRowNum() != rows.get(rows.size() - 1) + 1) {
                        fail(cell, "同一工作表的明细样板行必须连续");
                    }
                    rows.add(row.getRowNum());
                    break;
                }
            }
        }
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            if (range.getFirstRow() != range.getLastRow() && rows.stream().anyMatch(r -> r >= range.getFirstRow() && r <= range.getLastRow())) {
                throw new BusinessException(ResultCode.CONTRACT_TEMPLATE_FILL_FAILED,
                        "明细样板不支持跨行合并：" + sheet.getSheetName() + "!" + range.formatAsString());
            }
        }
        return rows;
    }

    /** shiftRows 不负责同步绘图锚点、手动分页和打印范围，三者须一起移动。 */
    private void moveFooter(XSSFSheet sheet, int insertAt, int count) {
        int[] breaks = sheet.getRowBreaks();
        int index = sheet.getWorkbook().getSheetIndex(sheet);
        String printArea = sheet.getWorkbook().getPrintArea(index);
        if (insertAt <= sheet.getLastRowNum()) sheet.shiftRows(insertAt, sheet.getLastRowNum(), count, true, false);
        for (int row : sheet.getRowBreaks()) sheet.removeRowBreak(row);
        for (int row : breaks) sheet.setRowBreak(row >= insertAt ? row + count : row);
        XSSFDrawing drawing = sheet.getDrawingPatriarch();
        if (drawing != null) {
            drawing.getCTDrawing().getTwoCellAnchorList().forEach(anchor -> {
                if (anchor.getFrom().getRow() >= insertAt) anchor.getFrom().setRow(anchor.getFrom().getRow() + count);
                if (anchor.getTo().getRow() >= insertAt) anchor.getTo().setRow(anchor.getTo().getRow() + count);
            });
            drawing.getCTDrawing().getOneCellAnchorList().forEach(anchor -> {
                if (anchor.getFrom().getRow() >= insertAt) anchor.getFrom().setRow(anchor.getFrom().getRow() + count);
            });
        }
        if (printArea != null) {
            List<String> areas = new ArrayList<>();
            for (AreaReference area : AreaReference.generateContiguous(SpreadsheetVersion.EXCEL2007, printArea)) {
                int firstRow = area.getFirstCell().getRow();
                int lastRow = area.getLastCell().getRow();
                // 打印范围必须使用绝对引用，避免转换器按活动单元格偏移范围、输出多余空白页。
                areas.add(new CellReference(firstRow >= insertAt ? firstRow + count : firstRow,
                        area.getFirstCell().getCol(), true, true).formatAsString() + ":"
                        + new CellReference(lastRow >= insertAt ? lastRow + count : lastRow,
                        area.getLastCell().getCol(), true, true).formatAsString());
            }
            sheet.getWorkbook().setPrintArea(index, String.join(",", areas));
        }
    }

    private void replace(Cell cell, Map<String, Object> values, Map<String, CellStyle> styles) {
        String text = cell.getStringCellValue();
        Matcher matcher = TOKEN.matcher(text);
        if (matcher.matches()) {
            Object value = values.get(matcher.group(1));
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
                // 数字占位符整格取值，字体只能由单元格样式决定：换成去粗体的样式副本，
                // 否则模板里加粗的单元格会让金额、数量这类填充值也带粗体。
                cell.setCellStyle(plainStyle(cell, styles));
                return;
            }
        }
        // 按原富文本片段替换，保留条款中红字等局部格式，而非整格降级为普通文本。
        XSSFRichTextString source = (XSSFRichTextString) cell.getRichStringCellValue();
        StringBuilder result = new StringBuilder();
        List<StyledSpan> spans = new ArrayList<>();
        boolean noAccount = ACCOUNT_KEYS.stream().allMatch(key -> values.get(key) == null || values.get(key).toString().isBlank());
        matcher = (noAccount ? TOKEN_WITH_EMPTY_ACCOUNT : TOKEN).matcher(text);
        int offset = 0;
        while (matcher.find()) {
            appendSource(source, offset, matcher.start(), result, spans);
            Object value = values.get(matcher.group(1));
            String replacement = value == null ? "" : value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
            int start = result.length();
            result.append(replacement);
            // 占位符原文的字体只用来定字号与颜色，加粗一律去掉：模板对占位符常见的加粗会原样带到填充值上。
            spans.add(new StyledSpan(start, result.length(), plainFont(cell, fontAt(source, matcher.start()))));
            offset = matcher.end();
        }
        appendSource(source, offset, text.length(), result, spans);
        XSSFRichTextString target = new XSSFRichTextString(result.toString());
        for (StyledSpan span : spans) if (span.font() != null && span.start() < span.end()) target.applyFont(span.start(), span.end(), span.font());
        // Excel/WPS 可能把模板文字保存为 inlineStr；先清除旧内容，避免旧 <is> 残留。
        cell.setBlank();
        cell.setCellValue(target);
    }

    private void appendSource(XSSFRichTextString source, int start, int end, StringBuilder result, List<StyledSpan> spans) {
        int base = result.length();
        result.append(source.getString(), start, end);
        for (int i = 0; i < source.numFormattingRuns(); i++) {
            int left = Math.max(start, source.getIndexOfFormattingRun(i));
            int right = Math.min(end, i + 1 < source.numFormattingRuns() ? source.getIndexOfFormattingRun(i + 1) : source.length());
            if (left < right) spans.add(new StyledSpan(base + left - start, base + right - start, source.getFontOfFormattingRun(i)));
        }
    }

    private XSSFFont fontAt(XSSFRichTextString source, int position) {
        XSSFFont font = null;
        for (int i = 0; i < source.numFormattingRuns(); i++) {
            if (source.getIndexOfFormattingRun(i) > position) break;
            font = source.getFontOfFormattingRun(i);
        }
        return font;
    }

    /** 占位符没有独立富文本片段时，字号与颜色由单元格样式字体决定。 */
    private XSSFFont cellStyleFont(Cell cell) {
        return ((XSSFWorkbook) cell.getSheet().getWorkbook()).getFontAt(cell.getCellStyle().getFontIndex());
    }

    /**
     * 填充值一律不加粗：完整复制模板原字体的名称、字号、颜色、斜体、下划线等属性，只清掉加粗。
     * 原字体本来就不粗（或取不到）时原样返回，顺带避免产生多余的字体对象。
     */
    private XSSFFont plainFont(Cell cell, XSSFFont source) {
        XSSFFont base = source != null ? source : cellStyleFont(cell);
        if (base == null || !base.getBold()) return base;
        return plainFonts.computeIfAbsent(base, bold -> {
            XSSFFont font = ((XSSFWorkbook) cell.getSheet().getWorkbook()).createFont();
            // CTFont 中加粗是 <b/> 元素：整体复制后清空该元素即取消加粗，其余属性保持模板原样。
            font.getCTFont().set(bold.getCTFont());
            font.getCTFont().setBArray(new CTBooleanProperty[0]);
            return font;
        });
    }

    /** 数字占位符是整格取值，字体只能由单元格样式决定，这里换成去粗体的样式副本，与文本填充保持一致。 */
    private CellStyle plainStyle(Cell cell, Map<String, CellStyle> styles) {
        CellStyle current = cell.getCellStyle();
        XSSFFont styleFont = cellStyleFont(cell);
        if (styleFont == null || !styleFont.getBold()) return current;
        return styles.computeIfAbsent("plain:" + current.getIndex(), ignored -> {
            CellStyle copy = cell.getSheet().getWorkbook().createCellStyle();
            copy.cloneStyleFrom(current);
            copy.setFont(plainFont(cell, styleFont));
            return copy;
        });
    }

    private void styleAndFit(Cell cell, String original, Map<String, CellStyle> styles) {
        boolean price = original.equals("${items.unitPriceWithoutTax}") || original.equals("${items.unitPrice}");
        // 数量、单价、金额这类填充值是数字，模板里多是「常规」对齐；通用格式的数字在导出时靠右，
        // 与模板中已经居中的合计行（原价/折扣/总计）并排看就是参差不齐。这里统一改成水平居中，
        // 模板里显式设过左/右对齐的单元格不动，避免覆盖模板作者的选择。
        boolean centered = cell.getCellType() == CellType.NUMERIC
                && cell.getCellStyle().getAlignment() == HorizontalAlignment.GENERAL;
        String key = cell.getCellStyle().getIndex() + ":" + price + ":" + centered;
        CellStyle style = styles.computeIfAbsent(key, ignored -> {
            CellStyle copy = cell.getSheet().getWorkbook().createCellStyle();
            copy.cloneStyleFrom(cell.getCellStyle());
            copy.setWrapText(true);
            copy.setShrinkToFit(false);
            if (centered) copy.setAlignment(HorizontalAlignment.CENTER);
            if (price) {
                String format = copy.getDataFormatString();
                String four = format.replaceAll("\\.0{1,3}(?!0)", ".0000");
                if (four.equals(format) && !format.contains(".0000")) {
                    four = format.contains("￥") || format.contains("¥") ? "\"￥\"#,##0.0000" : "#,##0.0000";
                }
                copy.setDataFormat(cell.getSheet().getWorkbook().createDataFormat().getFormat(four));
            }
            return copy;
        });
        cell.setCellStyle(style);
        int firstCol = cell.getColumnIndex();
        int lastCol = firstCol;
        for (CellRangeAddress merged : cell.getSheet().getMergedRegions()) {
            if (merged.isInRange(cell)) {
                if (merged.getFirstRow() != merged.getLastRow()) return;
                firstCol = merged.getFirstColumn();
                lastCol = merged.getLastColumn();
                break;
            }
        }
        double width = 0;
        for (int col = firstCol; col <= lastCol; col++) width += cell.getSheet().getColumnWidthInPixels(col) * 0.75;
        double fontSize = cell.getSheet().getWorkbook().getFontAt(style.getFontIndex()).getFontHeightInPoints();
        if (cell.getCellType() == CellType.STRING) {
            XSSFRichTextString rich = (XSSFRichTextString) cell.getRichStringCellValue();
            for (int run = 0; run < rich.numFormattingRuns(); run++) {
                XSSFFont font = rich.getFontOfFormattingRun(run);
                if (font != null) fontSize = Math.max(fontSize, font.getFontHeightInPoints());
            }
        }
        int lines = 0;
        String displayed = new DataFormatter().formatCellValue(cell);
        for (String line : displayed.split("\\R", -1)) {
            // 保守估算中英文混排宽度，合并单元格不能依赖 Excel 自动行高。
            double length = line.codePoints().mapToDouble(ch -> ch > 255 ? 1.05 : 0.65).sum() * fontSize;
            lines += Math.max(1, (int) Math.ceil(length / Math.max(1, width - 8)));
        }
        // 单行沿用模板行高，不能因统一加留白把签章区挤到额外空白页。
        if (lines <= 1) return;
        float required = (float) (lines * fontSize * 1.5 + 6);
        if (required > 409) fail(cell, "内容过长，请加宽单元格或拆分条款，避免导出截断");
        cell.getRow().setHeightInPoints(Math.max(cell.getRow().getHeightInPoints(), required));
    }

    private void fail(Cell cell, String message) {
        throw new BusinessException(ResultCode.CONTRACT_TEMPLATE_FILL_FAILED,
                "合同模板 " + cell.getSheet().getSheetName() + "!" + cell.getAddress() + "：" + message);
    }

    private record StyledSpan(int start, int end, XSSFFont font) { }
}
