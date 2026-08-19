package com.scione.scm.bill.interfaces;

import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.application.ShippingMarkImportAppService;
import com.scione.scm.bill.application.dto.ShippingMarkImportDTO;
import com.scione.scm.bill.application.port.ShippingMarkImportParser.ImportDocument;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShippingMarkControllerImportTest {

    @Mock
    private ShippingMarkImportAppService importAppService;

    @InjectMocks
    private ShippingMarkController controller;

    @Test
    void importFile_shouldReturnAcceptedWithResultAndForwardHeaders() throws IOException {
        byte[] content = "excel".getBytes();
        MultipartFile file = mockFile("test.xlsx", content);
        ShippingMarkImportDTO dto = new ShippingMarkImportDTO(1L, "XM202608191234", "test.xlsx", 2);
        when(importAppService.importFile(any(), anyString(), anyString())).thenReturn(dto);

        ResponseEntity<ApiResponse<ShippingMarkImportDTO>> response =
                controller.importFile(file, "100", "张三");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ApiResponse<ShippingMarkImportDTO> body = response.getBody();
        assertThat(body.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
        assertThat(body.getMessage()).isEqualTo("导入任务已创建");
        assertThat(body.getData()).isSameAs(dto);

        ArgumentCaptor<ImportDocument> documentCaptor = ArgumentCaptor.forClass(ImportDocument.class);
        verify(importAppService).importFile(documentCaptor.capture(), org.mockito.ArgumentMatchers.eq("100"),
                org.mockito.ArgumentMatchers.eq("张三"));
        assertThat(documentCaptor.getValue().fileName()).isEqualTo("test.xlsx");
        assertThat(documentCaptor.getValue().content()).isEqualTo(content);
    }

    @Test
    void importFile_whenFileIsNull_shouldForwardEmptyDocument() {
        ShippingMarkImportDTO dto = new ShippingMarkImportDTO(2L, "XM202608190001", null, 0);
        when(importAppService.importFile(any(), isNull(), isNull())).thenReturn(dto);

        ResponseEntity<ApiResponse<ShippingMarkImportDTO>> response =
                controller.importFile(null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().getData()).isSameAs(dto);

        ArgumentCaptor<ImportDocument> documentCaptor = ArgumentCaptor.forClass(ImportDocument.class);
        verify(importAppService).importFile(documentCaptor.capture(), isNull(), isNull());
        assertThat(documentCaptor.getValue().fileName()).isNull();
        assertThat(documentCaptor.getValue().content()).isNull();
    }

    @Test
    void importFile_whenReadBytesFails_shouldReturnImportFileInvalid() throws IOException {
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("bad.xlsx");
        when(file.getBytes()).thenThrow(new IOException("boom"));

        ResponseEntity<ApiResponse<ShippingMarkImportDTO>> response = controller.importFile(file, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.IMPORT_FILE_INVALID.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo(ResultCode.IMPORT_FILE_INVALID.getMessage());
    }

    private MultipartFile mockFile(String name, byte[] content) throws IOException {
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getBytes()).thenReturn(content);
        return file;
    }
}
