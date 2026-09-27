package com.scione.scm.bill.application.port;

/** Converts a filled contract workbook to a PDF without changing its layout. */
public interface ContractPdfConverter {

    byte[] convert(byte[] xlsxBytes, String contractNo);
}
