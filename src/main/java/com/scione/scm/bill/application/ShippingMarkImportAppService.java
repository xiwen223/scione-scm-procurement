package com.scione.scm.bill.application;

import com.scione.scm.bill.application.dto.ShippingMarkImportDTO;
import com.scione.scm.bill.application.dto.ShippingMarkParseDTO;
import com.scione.scm.bill.application.port.ShippingMarkFileStore;
import com.scione.scm.bill.application.port.ShippingMarkImportParser;
import com.scione.scm.bill.application.port.ShippingMarkImportParser.ImageData;
import com.scione.scm.bill.application.port.ShippingMarkImportParser.ImportDocument;
import com.scione.scm.bill.application.port.ShippingMarkTaskDispatcher;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import com.scione.scm.bill.domain.shippingmark.ShippingMark;
import com.scione.scm.bill.domain.shippingmark.ShippingMarkDetail;
import com.scione.scm.bill.domain.shippingmark.ShippingMarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Excel 导入任务创建用例：解析、去重、保存并异步提交标签生成。
 */
@Service
@RequiredArgsConstructor
public class ShippingMarkImportAppService {

    private static final String SYSTEM_OPERATOR_ID = "";
    private static final String SYSTEM_OPERATOR_NAME = "系统";

    private final ShippingMarkImportParser importParser;
    private final ShippingMarkFileStore fileStore;
    private final ShippingMarkRepository repository;
    private final ShippingMarkTaskDispatcher taskDispatcher;

    public ShippingMarkImportDTO importFile(ImportDocument document, String createdBy, String creator) {
        ShippingMarkImportParser.ParsedImport parsed = importParser.parse(document);
        String operatorId = createdBy == null || createdBy.isBlank() ? SYSTEM_OPERATOR_ID : createdBy.trim();
        String operatorName = creator == null || creator.isBlank() ? SYSTEM_OPERATOR_NAME : creator.trim();
        String billNo = generateBillNo();
        ShippingMark mark = ShippingMark.create(billNo, parsed.fileName(), operatorId, operatorName);
        for (ShippingMarkImportParser.ParsedDetail parsedDetail : parsed.details()) {
            String imageUrl = storeImage(billNo, parsedDetail.image());
            mark.addDetail(new ShippingMarkDetail(
                    billNo, parsedDetail.purchaseOrderNo(), parsedDetail.skuCode(), parsedDetail.skuName(), imageUrl,
                    operatorId, operatorName));
        }
        mark.start();
        repository.save(mark);
        taskDispatcher.dispatch(mark.getId());
        return new ShippingMarkImportDTO(mark.getId(), billNo, parsed.fileName(), mark.getDetails().size());
    }

    public ShippingMarkParseDTO parse(ImportDocument document) {
        ShippingMarkImportParser.ParsedImport parsed = importParser.parse(document);
        List<ShippingMarkParseDTO.Detail> details = parsed.details().stream()
                .map(detail -> new ShippingMarkParseDTO.Detail(
                        detail.purchaseOrderNo(), detail.skuCode(), detail.skuName(), toDataUri(detail.image())))
                .toList();
        return new ShippingMarkParseDTO(parsed.fileName(), details);
    }

    private String toDataUri(ImageData image) {
        if (image == null) {
            return null;
        }
        String extension = image.extension() == null || image.extension().isBlank() ? "png" : image.extension();
        return "data:image/" + extension + ";base64," + Base64.getEncoder().encodeToString(image.content());
    }

    private String storeImage(String billNo, ImageData image) {
        if (image == null) {
            return null;
        }
        try {
            return fileStore.storeImage(billNo, image.content(), image.extension());
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.IMPORT_FILE_INVALID);
        }
    }

    private String generateBillNo() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        for (int attempts = 0; attempts < 50; attempts++) {
            String billNo = "XM" + date + ThreadLocalRandom.current().nextInt(1000, 10000);
            if (repository.findByBillNo(billNo).isEmpty()) {
                return billNo;
            }
        }
        throw new BusinessException(ResultCode.SHIPPING_MARK_ALREADY_EXISTS);
    }
}
