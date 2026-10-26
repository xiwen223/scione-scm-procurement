package com.scione.scm.bill.application;

import cn.hutool.core.collection.CollectionUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scione.scm.bill.application.dto.CompanyGoodsExportDTO;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.goods.GoodsSkuRelation;
import com.scione.scm.bill.infrastructure.excel.CompanyGoodsExcelParser;
import com.scione.scm.bill.infrastructure.persistence.mybatis.mapper.CompanyGoodsMapper;
import com.scione.scm.bill.infrastructure.persistence.mybatis.po.CompanyGoodsPO;
import jakarta.annotation.Resource;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/** 按当前导入导出模板生成Excel，不读取临时图片链接，不调用外部服务。 */
@Service
public class CompanyGoodsExportService {
    @Resource
    private CompanyGoodsMapper mapper;
    @Resource
    private ObjectMapper objectMapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] export(CompanyGoodsExportDTO request) {
        if (CollectionUtil.isEmpty(request.getIds())) throw invalid("请先选择货品");
        Set<Long> ids = new LinkedHashSet<>(request.getIds());
        if (ids.size() > 2000 || ids.contains(null) || ids.size() != request.getIds().size()) throw invalid("货品ID不能重复或为空，单次最多选择2000条");
        for (Long id : ids) if (id <= 0) throw invalid("货品ID必须为正整数");
        List<CompanyGoodsPO> parents = mapper.findExportParents(new ArrayList<>(ids));
        if (parents.size() != ids.size()) throw invalid("部分货品不存在或已作为子SKU，请刷新列表后重新选择");
        Map<Long, String> childByParent = new LinkedHashMap<>();
        Set<String> childSkus = new LinkedHashSet<>();
        for (CompanyGoodsPO parent : parents) {
            List<GoodsSkuRelation> relations = readRelations(parent);
            if (relations.size() > 1) throw invalid("货品" + parent.getSku() + "包含多个子SKU，当前模板暂不支持导出多个关联");
            if (CollectionUtil.isNotEmpty(relations)) {
                String childSku = relations.get(0).getSku();
                if (parent.getSku().equalsIgnoreCase(childSku)) throw invalid("货品" + parent.getSku() + "存在自身关联，请先修复");
                childByParent.put(parent.getId(), childSku);
                childSkus.add(childSku);
            }
        }
        Map<String, CompanyGoodsPO> children = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(childSkus)) {
            for (CompanyGoodsPO child : mapper.findBySkus(new ArrayList<>(childSkus))) {
                String sku = normalizedSku(child.getSku());
                if (children.put(sku, child) != null) throw invalid("子SKU" + child.getSku() + "存在重复数据，请先修复");
                if (CollectionUtil.isNotEmpty(readRelations(child))) throw invalid("子SKU" + child.getSku() + "存在多层关联，暂不支持导出");
            }
        }
        Map<Long, CompanyGoodsPO> exported = new LinkedHashMap<>();
        for (CompanyGoodsPO parent : parents) {
            exported.put(parent.getId(), parent);
            String childSku = childByParent.get(parent.getId());
            if (childSku == null) continue;
            CompanyGoodsPO child = children.get(normalizedSku(childSku));
            if (child == null) throw invalid("关联子SKU" + childSku + "不存在，请先修复");
            exported.put(child.getId(), child);
        }
        if (exported.size() > 2000) throw invalid("主、子SKU合计超过2000条，请减少选择后分批导出");

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("企业货品库");
            sheet.createFreezePane(3, 1);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            Row header = sheet.createRow(0);
            for (int column = 0; column < CompanyGoodsExcelParser.HEADERS.size(); column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(CompanyGoodsExcelParser.HEADERS.get(column));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(column, (column == 2 || column == 5 ? 50 : 22) * 256);
            }
            CellStyle decimalStyle = workbook.createCellStyle();
            decimalStyle.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            int rowIndex = 1;
            for (CompanyGoodsPO goods : exported.values()) {
                String childSku = childByParent.get(goods.getId());
                CompanyGoodsPO child = childSku == null ? null : children.get(normalizedSku(childSku));
                List<Object> values = values(goods, child);
                Row row = sheet.createRow(rowIndex++);
                for (int column = 0; column < values.size(); column++) {
                    Object value = values.get(column);
                    Cell cell = row.createCell(column);
                    if (value == null) continue;
                    if (value instanceof BigDecimal number) {
                        // Excel数字只有15位有效精度，较大金额用文本保留数据库原值。
                        if (number.precision() > 15) cell.setCellValue(number.toPlainString());
                        else { cell.setCellValue(number.doubleValue()); cell.setCellStyle(decimalStyle); }
                    } else if (value instanceof Integer number) cell.setCellValue(number);
                    else cell.setCellValue(value.toString());
                }
            }
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, rowIndex - 1, 0, 43));
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException("生成货品导出文件失败", exception); }
    }

    private List<Object> values(CompanyGoodsPO goods, CompanyGoodsPO child) {
        return Arrays.asList(goods.getProductId(), goods.getSku(), goods.getGoodsName(),
                label(goods.getSyncScope(), List.of("领星", "领星黑湖"), 0, "同步范围"),
                child == null ? null : child.getSku(), child == null ? null : child.getGoodsName(),
                label(goods.getProductAttribute(), List.of("自制", "外购", "委外"), 0, "产品属性"),
                goods.getCgProductLength(), goods.getCgProductWidth(), goods.getCgProductHeight(), goods.getNetWeight(), goods.getGrossWeight(),
                goods.getPurchaseCost(), goods.getSupplierName(), goods.getSupplierQuote(), goods.getPurchaserName(), goods.getProductOwnerNames(),
                goods.getCgBoxLength(), goods.getCgBoxWidth(), goods.getCgBoxHeight(), goods.getCgPackageLength(), goods.getCgPackageWidth(), goods.getCgPackageHeight(),
                goods.getCartonQuantity(), goods.getCartonWeight(), goods.getProcessRoute(), goods.getProductCategory(), inspectionNames(goods),
                goods.getIncomingInspectionStandard(), goods.getDefaultWarehouse(), label(goods.getIsKeyPart(), List.of("否", "是"), 0, "关键部位"),
                label(goods.getQualityInspectionMethod(), List.of("抽检", "全检", "免检"), 1, "领星默认质检方式"), goods.getQualityTemplate(),
                goods.getCustomsNameCn(), goods.getCustomsNameEn(), goods.getMaterialCn(), goods.getMaterialEn(), goods.getUsageCn(), goods.getUsageEn(),
                goods.getCustomsDeclarationHsCode(), goods.getCustomsImportPrice(), goods.getClearanceCountryCode(), goods.getBgImportHsCode(), goods.getClearancePrice());
    }

    private List<GoodsSkuRelation> readRelations(CompanyGoodsPO goods) {
        if (goods.getGroupList() == null) return Collections.emptyList();
        try {
            List<GoodsSkuRelation> relations = objectMapper.readValue(goods.getGroupList(), new TypeReference<List<GoodsSkuRelation>>() { });
            if (relations == null) throw invalid("货品" + goods.getSku() + "的关联数据异常");
            for (GoodsSkuRelation relation : relations) {
                if (relation == null || relation.getSku() == null || relation.getSku().isBlank()) throw invalid("货品" + goods.getSku() + "的关联数据异常");
            }
            return relations;
        } catch (JsonProcessingException exception) { throw invalid("货品" + goods.getSku() + "的关联数据异常"); }
    }

    private String inspectionNames(CompanyGoodsPO goods) {
        if (goods.getInspectionType() == null) return null;
        try {
            List<String> names = objectMapper.readValue(goods.getInspectionType(), new TypeReference<List<String>>() { });
            if (names == null) return null;
            for (String name : names) if (name == null || name.isBlank()) throw invalid("货品" + goods.getSku() + "的检验类型数据异常");
            return String.join(",", names);
        } catch (JsonProcessingException exception) { throw invalid("货品" + goods.getSku() + "的检验类型数据异常"); }
    }

    private String label(Integer code, List<String> names, int firstCode, String field) {
        if (code == null) return null;
        int index = code - firstCode;
        if (index < 0 || index >= names.size()) throw invalid(field + "存在未知编码：" + code);
        return names.get(index);
    }

    private String normalizedSku(String sku) { return sku.toLowerCase(Locale.ROOT); }
    private BusinessException invalid(String message) { return new BusinessException(ResultCode.PARAM_ERROR, message); }
}
