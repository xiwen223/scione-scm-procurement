package com.scione.scm.bill.infrastructure.pdf;

import com.scione.scm.bill.application.port.ContractPdfConverter;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Uses LibreOffice to preserve the original Excel contract template layout in PDF. */
@Slf4j
@Component
public class LibreOfficeContractPdfConverter implements ContractPdfConverter {
    @Value("${contract.pdf-converter.command}")
    private String sofficeCommand;

    @Override
    public byte[] convert(byte[] xlsxBytes, String contractNo) {
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("contract-pdf-");
            Path source = workspace.resolve(contractNo + ".xlsx");
            Path profile = workspace.resolve("lo-profile");
            Files.write(source, xlsxBytes);
            List<String> command = List.of(sofficeCommand, "--headless", "--nologo", "--nofirststartwizard",
                    "-env:UserInstallation=" + profile.toUri(), "--convert-to", "pdf", "--outdir", workspace.toString(), source.toString());
            log.info("开始通过LibreOffice转换合同PDF：contractNo={}, command={}", contractNo, sofficeCommand);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            Path pdf = workspace.resolve(contractNo + ".pdf");
            if (exitCode != 0 || !Files.isRegularFile(pdf)) {
                log.error("LibreOffice转换合同PDF失败：contractNo={}, exitCode={}, output={}", contractNo, exitCode, output);
                throw new BusinessException(ResultCode.CONTRACT_TEMPLATE_FILL_FAILED, "合同PDF转换失败，请检查LibreOffice配置");
            }
            byte[] pdfBytes = Files.readAllBytes(pdf);
            if (pdfBytes.length < 4 || pdfBytes[0] != '%' || pdfBytes[1] != 'P' || pdfBytes[2] != 'D' || pdfBytes[3] != 'F') {
                throw new BusinessException(ResultCode.CONTRACT_TEMPLATE_FILL_FAILED, "合同PDF转换结果无效");
            }
            log.info("LibreOffice合同PDF转换成功：contractNo={}, bytes={}", contractNo, pdfBytes.length);
            return pdfBytes;
        } catch (BusinessException ex) { throw ex;
        } catch (IOException ex) {
            log.error("LibreOffice不可用或合同PDF转换失败：contractNo={}, command={}", contractNo, sofficeCommand, ex);
            throw new BusinessException(ResultCode.CONTRACT_TEMPLATE_FILL_FAILED, "合同PDF转换服务不可用，请安装并配置LibreOffice");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.CONTRACT_TEMPLATE_FILL_FAILED, "合同PDF转换被中断");
        } finally {
            if (workspace != null) try (var paths = Files.walk(workspace)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
            } catch (IOException ignored) { }
        }
    }
}
