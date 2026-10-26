package com.scione.scm.bill.infrastructure.excel;

import cn.hutool.core.collection.CollectionUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.goods.enums.BlacklakeProductCategoryEnum;
import com.scione.scm.bill.domain.goods.enums.BlacklakeKeyPartEnum;
import com.scione.scm.bill.domain.goods.enums.LingxingQualityInspectionMethodEnum;
import com.scione.scm.bill.domain.goods.enums.ProductAttributeEnum;
import com.scione.scm.bill.domain.goods.enums.GoodsInspectionTypeEnum;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import jakarta.annotation.Resource;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/** 依据货品信息导入导出模板解析；每行维护当前 SKU，子 SKU 列仅表示关联。 */
@Component
public class CompanyGoodsExcelParser {
    public static final List<String> HEADERS = List.of("企业商品ID", "SKU*", "SKU名称*", "同步范围*", "子SKU", "子SKU名称", "产品属性*",
            "单品规格长cm*", "单品规格宽cm*", "单品规格高cm*", "单品净重g*", "单品毛重g", "采购成本￥", "供应商*", "供应商报价￥*",
            "采购员*", "产品负责人*", "外箱规格长cm", "外箱规格宽cm", "外箱规格高cm", "包装规格长cm", "包装规格宽cm", "包装规格高cm",
            "单箱数量*", "单箱重量kg*", "工艺路线", "产品分类*", "检验类型", "来料检验规范", "默认仓库", "关键部位",
            "领星默认质检方式*", "领星质检模板", "中文报关名", "英文报关名", "中文材质", "英文材质", "中文用途", "英文用途",
            "报关HSCODE", "报关单价USD", "清关国家", "默认清关HSCODE", "默认清关单价USD");
    @Resource
    private ObjectMapper objectMapper;

    public record ImportRow(int rowNumber, List<String> cells, CompanyGoodsPO goods, String childSku, String error) { }

    public List<ImportRow> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid("请选择导入文件");
        if (file.getSize() > 5 * 1024 * 1024) throw invalid("文件大小不得超过5MB");
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw invalid("请使用 .xlsx 格式的导入模板");
        }
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            if (workbook.getNumberOfSheets() == 0) throw invalid("Excel没有可导入的工作表");
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<String> headers = readCells(sheet.getRow(0), formatter);
            if (!HEADERS.equals(headers)) throw invalid("模板表头不匹配，请下载最新导入模板，保留列名称及顺序");
            for (Cell cell : sheet.getRow(0)) {
                int column = cell.getColumnIndex();
                String title = formatter.formatCellValue(cell).trim();
                if (column >= HEADERS.size() && !title.isBlank()
                        && !(column == 44 && "原文件行号".equals(title))
                        && !(column == 45 && "失败原因".equals(title))) throw invalid("存在模板之外的表头列");
            }
            List<ImportRow> rows = new ArrayList<>();
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                List<String> cells = readCells(row, formatter);
                boolean empty = true;
                for (String value : cells) if (!value.isBlank()) { empty = false; break; }
                if (empty) continue;
                // 模板底部的填写说明不是一条货品。
                if (cells.get(0).startsWith("注：") && cells.get(1).isBlank()) continue;
                if (rows.size() == 2000) throw invalid("单次最多导入2000条记录");
                try {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR) {
                            throw invalid("不支持公式或错误单元格，请粘贴为实际值后导入");
                        }
                        int column = cell.getColumnIndex();
                        if (column >= HEADERS.size() && !formatter.formatCellValue(cell).isBlank()) {
                            Cell header = sheet.getRow(0).getCell(column);
                            String title = header == null ? "" : formatter.formatCellValue(header).trim();
                            if (!(column == 44 && "原文件行号".equals(title)) && !(column == 45 && "失败原因".equals(title))) throw invalid("存在模板之外的数据列");
                        }
                    }
                    CompanyGoodsPO goods = parseGoods(cells);
                    String child = cells.get(4);
                    if (child.isBlank() || "/".equals(child)) child = null;
                    else if (child.length() > 128 || child.equalsIgnoreCase(goods.getSku())) throw invalid("子SKU不能为自身，且最长128字符");
                    rows.add(new ImportRow(row.getRowNum() + 1, cells, goods, child, null));
                } catch (BusinessException exception) {
                    rows.add(new ImportRow(row.getRowNum() + 1, cells, null, null, exception.getMessage()));
                }
            }
            if (CollectionUtil.isEmpty(rows)) throw invalid("没有可导入的数据");
            return rows;
        } catch (IOException | org.apache.poi.ooxml.POIXMLException | org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException
                 | org.apache.poi.EncryptedDocumentException exception) {
            throw invalid("无法读取Excel，请确认文件未损坏且为 .xlsx 格式");
        }
    }

    private List<String> readCells(Row row, DataFormatter formatter) {
        List<String> cells = new ArrayList<>();
        for (int column = 0; column < HEADERS.size(); column++) {
            Cell cell = row == null ? null : row.getCell(column);
            // 数字按实际值读取，避免显示格式将多位小数舍入后悄悄导入。
            if (cell != null && cell.getCellType() == CellType.NUMERIC) {
                cells.add(BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString());
            } else cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
        }
        return cells;
    }

    private CompanyGoodsPO parseGoods(List<String> cells) {
        CompanyGoodsPO goods = new CompanyGoodsPO();
        goods.setProductId(text(cells, 0, 128));
        goods.setSku(text(cells, 1, 128));
        goods.setGoodsName(text(cells, 2, 300));
        goods.setSyncScope(option(cells, 3, List.of("领星", "领星黑湖"), 0));
        String productAttributeName = text(cells, 6, 128);
        for (ProductAttributeEnum attribute : ProductAttributeEnum.values()) {
            if (attribute.getName().equals(productAttributeName)) {
                goods.setProductAttribute(attribute.getCode());
                break;
            }
        }
        if (goods.getProductAttribute() == null) throw invalid("产品属性只能填写：自制、外购、委外");
        goods.setCgProductLength(decimal(cells, 7));
        goods.setCgProductWidth(decimal(cells, 8));
        goods.setCgProductHeight(decimal(cells, 9));
        goods.setNetWeight(decimal(cells, 10));
        goods.setGrossWeight(decimal(cells, 11));
        goods.setPurchaseCost(decimal(cells, 12));
        goods.setSupplierName(text(cells, 13, 64));
        if (goods.getSupplierName().matches(".*[;；,，、].*")) throw invalid("供应商仅支持填写一个名称");
        goods.setSupplierQuote(decimal(cells, 14));
        goods.setPurchaserName(text(cells, 15, 32));
        goods.setProductOwnerNames(multipleNames(text(cells, 16, 512), "产品负责人", null));
        goods.setCgBoxLength(decimal(cells, 17));
        goods.setCgBoxWidth(decimal(cells, 18));
        goods.setCgBoxHeight(decimal(cells, 19));
        goods.setCgPackageLength(decimal(cells, 20));
        goods.setCgPackageWidth(decimal(cells, 21));
        goods.setCgPackageHeight(decimal(cells, 22));
        dimensions(goods.getCgBoxLength(), goods.getCgBoxWidth(), goods.getCgBoxHeight(), "外箱规格");
        dimensions(goods.getCgPackageLength(), goods.getCgPackageWidth(), goods.getCgPackageHeight(), "包装规格");
        BigDecimal quantity = decimal(cells, 23);
        try { goods.setCartonQuantity(quantity.intValueExact()); }
        catch (ArithmeticException exception) { throw invalid("单箱数量必须是正整数，且不能超过2147483647"); }
        goods.setCartonWeight(decimal(cells, 24));
        goods.setProcessRoute(text(cells, 25, 128));
        goods.setProductCategory(text(cells, 26, 128));
        boolean categoryFound = false;
        for (BlacklakeProductCategoryEnum category : BlacklakeProductCategoryEnum.values()) {
            if (category.getName().equals(goods.getProductCategory())) { categoryFound = true; break; }
        }
        if (!categoryFound) throw invalid("产品分类不在可选范围内");
        String inspections = text(cells, 27, 128);
        if (inspections != null) {
            List<String> inspectionNames = new ArrayList<>();
            for (GoodsInspectionTypeEnum inspectionType : GoodsInspectionTypeEnum.values()) {
                inspectionNames.add(inspectionType.getName());
            }
            String names = multipleNames(inspections, "检验类型", inspectionNames);
            try { goods.setInspectionType(objectMapper.writeValueAsString(names.split(","))); }
            catch (JsonProcessingException exception) { throw new IllegalStateException("检验类型序列化失败", exception); }
        }
        goods.setIncomingInspectionStandard(text(cells, 28, 128));
        goods.setDefaultWarehouse(text(cells, 29, 128));
        String keyPartName = text(cells, 30, 128);
        if (keyPartName != null) {
            for (BlacklakeKeyPartEnum keyPart : BlacklakeKeyPartEnum.values()) {
                if (keyPart.getName().equals(keyPartName)) {
                    goods.setIsKeyPart(keyPart.getCode());
                    break;
                }
            }
            if (goods.getIsKeyPart() == null) throw invalid("关键部位只能填写：是、否");
        }
        String inspectionMethodName = text(cells, 31, 128);
        for (LingxingQualityInspectionMethodEnum inspectionMethod : LingxingQualityInspectionMethodEnum.values()) {
            if (inspectionMethod.getName().equals(inspectionMethodName)) {
                goods.setQualityInspectionMethod(inspectionMethod.getCode());
                break;
            }
        }
        if (goods.getQualityInspectionMethod() == null) throw invalid("领星默认质检方式只能填写：抽检、全检、免检");
        goods.setQualityTemplate(text(cells, 32, 128));
        goods.setCustomsNameCn(text(cells, 33, 512));
        goods.setCustomsNameEn(text(cells, 34, 512));
        goods.setMaterialCn(text(cells, 35, 512));
        goods.setMaterialEn(text(cells, 36, 512));
        goods.setUsageCn(text(cells, 37, 512));
        goods.setUsageEn(text(cells, 38, 512));
        goods.setCustomsDeclarationHsCode(hsCode(cells, 39));
        goods.setCustomsImportPrice(decimal(cells, 40));
        goods.setClearanceCountryCode(text(cells, 41, 32));
        goods.setBgImportHsCode(hsCode(cells, 42));
        goods.setClearancePrice(decimal(cells, 43));
        return goods;
    }

    private String text(List<String> cells, int index, int maxLength) {
        String value = cells.get(index);
        if (value.isBlank()) {
            if (HEADERS.get(index).endsWith("*")) throw invalid(HEADERS.get(index) + "必填");
            return null;
        }
        if (value.length() > maxLength) throw invalid(HEADERS.get(index) + "最多" + maxLength + "字符");
        return value;
    }

    private BigDecimal decimal(List<String> cells, int index) {
        String value = text(cells, index, 64);
        if (value == null) return null;
        if (!value.matches("[0-9]+(\\.[0-9]{1,2})?")) throw invalid(HEADERS.get(index) + "须为非负数字，最多两位小数");
        BigDecimal number = new BigDecimal(value);
        if (number.precision() - number.scale() > 16) throw invalid(HEADERS.get(index) + "整数部分最多16位");
        boolean money = index == 12 || index == 14 || index == 40 || index == 43;
        if (!money && number.signum() == 0) throw invalid(HEADERS.get(index) + "必须大于0");
        return number;
    }

    private Integer option(List<String> cells, int index, List<String> choices, int firstCode) {
        String value = text(cells, index, 128);
        if (value == null) return null;
        int position = choices.indexOf(value);
        if (position < 0) throw invalid(HEADERS.get(index) + "只能填写：" + String.join("、", choices));
        return position + firstCode;
    }

    private String multipleNames(String value, String label, List<String> choices) {
        Set<String> names = new LinkedHashSet<>();
        for (String name : value.split(",", -1)) {
            String trimmed = name.trim();
            if (trimmed.isEmpty() || trimmed.matches(".*[，;；].*") || !names.add(trimmed)) throw invalid(label + "请用英文逗号分隔，不能空缺或重复");
            if (choices != null && !choices.contains(trimmed)) throw invalid(label + "包含无效选项：" + trimmed);
        }
        return String.join(",", names);
    }

    private String hsCode(List<String> cells, int index) {
        String value = text(cells, index, 32);
        if (value != null && !value.matches("[0-9]+")) throw invalid(HEADERS.get(index) + "只能填写数字，请按文本格式保留前导零");
        return value;
    }

    private void dimensions(BigDecimal length, BigDecimal width, BigDecimal height, String label) {
        if ((length != null || width != null || height != null) && (length == null || width == null || height == null)) throw invalid(label + "请完整填写长、宽、高");
    }

    public String failureFile(List<ImportRow> rows, Map<Integer, String> failures) {
        if (failures.isEmpty()) return null;
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("失败记录");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) header.createCell(i).setCellValue(HEADERS.get(i));
            header.createCell(44).setCellValue("原文件行号");
            header.createCell(45).setCellValue("失败原因");
            int rowIndex = 1;
            for (ImportRow imported : rows) {
                if (!failures.containsKey(imported.rowNumber())) continue;
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < HEADERS.size(); i++) row.createCell(i).setCellValue(imported.cells().get(i));
                row.createCell(44).setCellValue(imported.rowNumber());
                row.createCell(45).setCellValue(failures.get(imported.rowNumber()));
            }
            sheet.createFreezePane(2, 1);
            workbook.write(output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException exception) { throw new IllegalStateException("生成导入失败文件失败", exception); }
    }

    private BusinessException invalid(String message) { return new BusinessException(ResultCode.PARAM_ERROR, message); }
}
