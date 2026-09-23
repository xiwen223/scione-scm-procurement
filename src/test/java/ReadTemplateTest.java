import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.FileInputStream;

public class ReadTemplateTest {
    public static void main(String[] args) throws Exception {
        String filePath = "C:\Users\Administrator\AppData\Roaming\CherryStudio\Data\Files\01a0cd81-911d-72e3-afed-af2275b45214.xlsx";
        Workbook wb = new XSSFWorkbook(new FileInputStream(filePath));
        Sheet sheet = wb.getSheetAt(0);
        
        for (int r = 0; r < 40; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            
            StringBuilder sb = new StringBuilder("Row" + (r+1) + ": ");
            for (int c = 0; c < 12; c++) {
                Cell cell = row.getCell(c);
                if (cell != null && cell.getCellType() != CellType.BLANK) {
                    String col = String.valueOf((char)('A' + c));
                    String val = "";
                    try {
                        if (cell.getCellType() == CellType.STRING) {
                            val = cell.getStringCellValue();
                            if (val.length() > 15) val = val.substring(0, 15) + "...";
                        } else if (cell.getCellType() == CellType.NUMERIC) {
                            val = String.valueOf(cell.getNumericCellValue());
                        }
                    } catch (Exception e) {
                        val = "[error]";
                    }
                    sb.append(col + (r+1) + "=" + val + " | ");
                }
            }
            if (sb.length() > 10) System.out.println(sb.toString());
        }
        wb.close();
    }
}
