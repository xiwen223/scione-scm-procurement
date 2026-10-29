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

    /**
     * 校验勾选主货品及group_list关联，按当前模板导出主、子货品Excel。
     *
     * @param request 当前操作的请求参数
     * @return 生成的xlsx文件二进制内容
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] export(CompanyGoodsExportDTO request) {
        if (CollectionUtil.isEmpty(request.getIds())) throw invalid("请先选择货品");
        Set<Long> ids = new LinkedHashSet<>(request.getIds());
        if (ids.size() > 2000 || ids.contains(null) || ids.size() != request.getIds().size()) throw invalid("货品ID不能重复或为空，单次最多选择2000条");
        for (Long id : ids) if (id <= 0) throw invalid("货品ID必须为正整数");
        List<CompanyGoodsPO> parents = mapper.findExportParents(new ArrayList<>(ids));
        if (parents.size() != ids.size()) throw invalid("部分货品不存在或已作为子SKU，请刷新列表后重新选择");
        Map<Long, List<String>> childByParent = new LinkedHashMap<>();
        Set<String> childSkus = new LinkedHashSet<>();
        for (CompanyGoodsPO parent : parents) {
            List<GoodsSkuRelation> relations = readRelations(parent);
            List<String> parentChildSkus = new ArrayList<>();
            for (GoodsSkuRelation relation : relations) {
                String childSku = relation.getSku();
                if (parent.getSku().equalsIgnoreCase(childSku)) throw invalid("货品" + parent.getSku() + "存在自身关联，请先修复");
                for (String existing : parentChildSkus) {
                    if (existing.equalsIgnoreCase(childSku)) throw invalid("货品" + parent.getSku() + "存在重复子SKU，请先修复");
                }
                parentChildSkus.add(childSku);
                childSkus.add(childSku);
            }
            childByParent.put(parent.getId(), parentChildSkus);
        }
        Map<String, CompanyGoodsPO> children = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(childSkus)) {
            for (CompanyGoodsPO child : mapper.findBySkus(new ArrayList<>(childSkus))) {
                String sku = normalizedSku(child.getSku());
                if (children.put(sku, child) != null) throw invalid("子SKU" + child.getSku() + "存在重复数据，请先修复");
                if (CollectionUtil.isNotEmpty(readRelations(child))) throw invalid("子SKU" + child.getSku() + "存在多层关联，暂不支持导出");
            }
        }
        List<CompanyGoodsPO> exported = new ArrayList<>();
        List<String> productIds = new ArrayList<>();
        Set<String> exportedProductIds = new HashSet<>();
        for (CompanyGoodsPO parent : parents) {
            String productId = parent.getProductId();
            if (productId == null || productId.isBlank()) throw invalid("货品" + parent.getSku() + "缺少企业商品ID，请先补充");
            if (!exportedProductIds.add(productId.trim().toLowerCase(Locale.ROOT))) throw invalid("所选主货品的企业商品ID重复，无法按新版模板区分主子关系");
            exported.add(parent);
            productIds.add(productId);
            for (String childSku : childByParent.get(parent.getId())) {
                CompanyGoodsPO child = children.get(normalizedSku(childSku));
                if (child == null) throw invalid("关联子SKU" + childSku + "不存在，请先修复");
                exported.add(child);
                // 导出同组使用主货品的企业商品ID，保证第一行主SKU、后续行子SKU。
                productIds.add(productId);
            }
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
                sheet.setColumnWidth(column, (column == 2 ? 50 : 22) * 256);
            }
            CellStyle decimalStyle = workbook.createCellStyle();
            decimalStyle.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            int rowIndex = 1;
            for (int index = 0; index < exported.size(); index++) {
                CompanyGoodsPO goods = exported.get(index);
                List<Object> values = values(goods, productIds.get(index));
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
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, rowIndex - 1, 0, CompanyGoodsExcelParser.HEADERS.size() - 1));
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException("生成货品导出文件失败", exception); }
    }

    /**
     * 按固定43列表头顺序映射货品字段，并将业务编码转换为导出名称。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @param productId 导出当前分组统一使用的主货品企业商品ID
     * @return 与模板列顺序一致的一行单元格值
     */
    private List<Object> values(CompanyGoodsPO goods, String productId) {
        return Arrays.asList(productId, goods.getSku(), goods.getGoodsName(),
                label(goods.getSyncScope(), List.of("领星", "领星黑湖"), 0, "同步范围"),
                goods.getUnit(),
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

    /**
     * 解析导出货品的关联数组，并拒绝空关联项或缺少SKU的关联。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @return 子SKU关联列表；数据库字段为null时为空列表
     */
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

    /**
     * 解析数据库检验类型名称数组并使用英文逗号拼接。
     *
     * @param goods 待处理货品记录或同步消息中的货品快照
     * @return 逗号分隔的检验类型名称；未填写时返回null
     */
    private String inspectionNames(CompanyGoodsPO goods) {
        if (goods.getInspectionType() == null) return null;
        try {
            List<String> names = objectMapper.readValue(goods.getInspectionType(), new TypeReference<List<String>>() { });
            if (names == null) return null;
            for (String name : names) if (name == null || name.isBlank()) throw invalid("货品" + goods.getSku() + "的检验类型数据异常");
            return String.join(",", names);
        } catch (JsonProcessingException exception) { throw invalid("货品" + goods.getSku() + "的检验类型数据异常"); }
    }

    /**
     * 根据连续业务编码取得显示名称，未知编码抛出校验异常。
     *
     * @param code 稳定业务编码，不使用枚举序号
     * @param names 按连续业务编码排列的显示名称列表
     * @param firstCode 第一个选项对应的业务编码
     * @param field JSON或业务字段名称
     * @return 对应的显示名称；编码为空时返回null
     */
    private String label(Integer code, List<String> names, int firstCode, String field) {
        if (code == null) return null;
        int index = code - firstCode;
        if (index < 0 || index >= names.size()) throw invalid(field + "存在未知编码：" + code);
        return names.get(index);
    }

    /**
     * 将SKU转换为不区分大小写比较时使用的键。
     *
     * @param sku 货品SKU编码
     * @return 使用固定语言环境转小写的SKU
     */
    private String normalizedSku(String sku) { return sku.toLowerCase(Locale.ROOT); }
    /**
     * 创建货品参数校验失败的业务异常。
     *
     * @param message 向调用方说明的业务失败原因
     * @return 参数错误业务异常
     */
    private BusinessException invalid(String message) { return new BusinessException(ResultCode.PARAM_ERROR, message); }
}
