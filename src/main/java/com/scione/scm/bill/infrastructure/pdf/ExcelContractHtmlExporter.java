package com.scione.scm.bill.infrastructure.pdf;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;

import java.io.ByteArrayInputStream;
import java.util.*;

/** 导出合同用 XHTML：保留打印区域、列宽比例、行高、合并单元格、富文本和嵌入图片。 */
final class ExcelContractHtmlExporter {
    String export(byte[] bytes) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            StringBuilder html = new StringBuilder("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"UTF-8\"/><style>");
            html.append("body{margin:0;font-family:ContractChinese;font-size:10pt;} table{border-collapse:collapse;table-layout:fixed;width:100%;} ")
                    .append("td{padding:0;word-wrap:break-word;vertical-align:middle;} tr{page-break-inside:avoid;} ")
                    .append(".cell{position:relative;} .text{padding:1px 2px;line-height:1.2;} .picture{position:absolute;} ")
                    .append("thead{display:table-header-group;} ");
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                XSSFSheet sheet = workbook.getSheetAt(i);
                double[] page = pageSize(sheet);
                html.append("@page sheet").append(i).append("{size:").append(page[0]).append("pt ").append(page[1]).append("pt;")
                        .append("margin:").append(sheet.getMargin(Sheet.TopMargin) * 72).append("pt ")
                        .append(sheet.getMargin(Sheet.RightMargin) * 72).append("pt ")
                        .append(Math.max(24, sheet.getMargin(Sheet.BottomMargin) * 72)).append("pt ")
                        .append(sheet.getMargin(Sheet.LeftMargin) * 72).append("pt;}");
            }
            html.append("</style></head><body>");
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                if (workbook.isSheetHidden(i) || workbook.isSheetVeryHidden(i)) continue;
                XSSFSheet sheet = workbook.getSheetAt(i);
                for (CellRangeAddress area : printAreas(workbook, i)) {
                    html.append("<div class=\"sheet\" style=\"page:sheet").append(i).append("\">");
                    table(html, sheet, area);
                    html.append("</div>");
                }
            }
            return html.append("</body></html>").toString();
        }
    }

    private void table(StringBuilder out, XSSFSheet sheet, CellRangeAddress area) {
        Map<String, List<Image>> pictures = pictures(sheet);
        int lastRow = lastContentRow(sheet, area, pictures);
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        // POI 填充阶段已算好金额；使用工作簿缓存，避免重新计算不支持的 Excel 函数。
        formatter.setUseCachedValuesForFormulaCells(true);
        tableSegment(out, sheet, area, area.getFirstRow(), lastRow, pictures, formatter);
    }

    private void tableSegment(StringBuilder out, XSSFSheet sheet, CellRangeAddress area, int firstRow, int lastRow,
                              Map<String, List<Image>> pictures, DataFormatter formatter) {
        double total = 0;
        for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) if (!sheet.isColumnHidden(c)) total += sheet.getColumnWidthInPixels(c);
        if (total == 0) throw new IllegalArgumentException("打印区域没有可见列");
        // 导出步骤1：按纸张和页边距计算可用宽度，再按原列比例缩放，不让商品图片宽度决定整表列宽。
        double printableWidth = pageSize(sheet)[0] - (sheet.getMargin(Sheet.LeftMargin) + sheet.getMargin(Sheet.RightMargin)) * 72 - 2;
        double contentScale = printableWidth / (total * 0.75);
        Map<Integer, Double> columnWidths = new HashMap<>();
        for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
            if (sheet.isColumnHidden(c)) continue;
            // Keep the workbook's original column proportions throughout the one continuous table.
            // The 40x40 product image is anchored inside its cell; it does not define column width.
            columnWidths.put(c, sheet.getColumnWidthInPixels(c) * 0.75 * contentScale);
        }
        out.append("<table style=\"width:").append(printableWidth).append("pt;\"><colgroup>");
        for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
            if (!sheet.isColumnHidden(c)) out.append("<col style=\"width:").append(columnWidths.get(c)).append("pt;\"/>");
        }
        out.append("</colgroup><tbody>");
        // 连续输出模板内容，保留内部留白，不应用 Excel 手动分页符。
        // 超过纸张高度时由 PDF 引擎自然换页，正文、签章区和箱唛不强制另起一页。
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row != null && row.getZeroHeight()) continue;
            boolean productImage = false;
            for (int column = area.getFirstColumn(); column <= area.getLastColumn(); column++) {
                if (pictures.getOrDefault(r + ":" + column, List.of()).stream()
                        .anyMatch(p -> Math.abs(p.width() - 40) < 0.1 && Math.abs(p.height() - 40) < 0.1)) productImage = true;
            }
            double rowHeight = productImage ? 33 : (row == null ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints()) * contentScale;
            out.append("<tr>");
            for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
                if (sheet.isColumnHidden(c)) continue;
                // 导出步骤2：合并区域只输出左上角，并用 colspan/rowspan 表达跨度，避免重复输出同一内容。
                CellRangeAddress merge = merged(sheet, r, c);
                if (merge != null && (r != merge.getFirstRow() || c != merge.getFirstColumn())) continue;
                XSSFCell cell = row == null ? null : (XSSFCell) row.getCell(c);
                // Excel 未换行文本可以溢出到右侧空白格；HTML 默认只在本格换行。
                // 仅对无边框、未合并的文本扩展显示范围，不跨过已有数据、合并格或图片。
                int overflowEnd = merge == null ? overflowEnd(sheet, row, cell, r, c, area, pictures) : c;
                out.append("<td");
                if (merge != null) out.append(" colspan=\"").append(visibleColumns(sheet, merge)).append("\" rowspan=\"").append(visibleRows(sheet, merge)).append("\"");
                else if (overflowEnd > c) out.append(" colspan=\"").append(visibleColumns(sheet, new CellRangeAddress(r, r, c, overflowEnd))).append("\"");
                double cellWidth = 0;
                int lastColumn = merge == null ? overflowEnd : merge.getLastColumn();
                for (int column = c; column <= lastColumn; column++) if (!sheet.isColumnHidden(column)) cellWidth += columnWidths.get(column);
                List<Image> cellPictures = pictures.getOrDefault(r + ":" + c, List.of());
                out.append(" style=\"width:").append(cellWidth).append("pt;").append(cellStyle(cell, contentScale, cellWidth));
                if (!cellPictures.isEmpty()) out.append("vertical-align:top;");
                out.append("\"><div class=\"cell\" style=\"");
                // Excel 行高作为最小高度；换行文本或较高图片需要时允许行自然增高。
                out.append("min-height:").append(rowHeight).append("pt;");
                if (productImage && !cellPictures.isEmpty()) out.append("min-height:44px;");
                out.append("\">");
                String content = text(cell, formatter, contentScale);
                if (!content.isEmpty()) out.append("<div class=\"text\">").append(content).append("</div>");
                // 导出步骤3：图片按 Excel 锚点绝对定位，不能参与文本流把表格行额外撑大。
                for (Image image : cellPictures) {
                    boolean fixedProductImage = Math.abs(image.width() - 40) < 0.1 && Math.abs(image.height() - 40) < 0.1;
                    double imageScale = fixedProductImage ? 1 : contentScale;
                    // Excel drawings are overlays anchored to cells; they must not participate in
                    // text flow. A logo anchored in the merged title cell must not stretch that row.
                    out.append("<img class=\"picture\" src=\"data:").append(image.mime()).append(";base64,").append(image.data())
                            .append("\" style=\"");
                    out.append("left:").append(image.x() * imageScale).append("px;top:").append(image.y() * imageScale).append("px;");
                    out.append("width:")
                            .append(image.width() * imageScale).append("px;height:").append(image.height() * imageScale).append("px;\"/>");
                }
                out.append("</div></td>");
                if (merge == null) c = overflowEnd;
            }
            out.append("</tr>");
        }
        out.append("</tbody></table>");
    }

    private String text(XSSFCell cell, DataFormatter formatter, double scale) {
        if (cell == null) return "";
        if (cell.getCellType() != CellType.STRING) return escape(formatter.formatCellValue(cell));
        XSSFRichTextString rich = cell.getRichStringCellValue();
        if (rich.numFormattingRuns() == 0) return escape(rich.getString());
        StringBuilder text = new StringBuilder();
        int offset = 0;
        for (int i = 0; i < rich.numFormattingRuns(); i++) {
            int start = rich.getIndexOfFormattingRun(i);
            int end = i + 1 < rich.numFormattingRuns() ? rich.getIndexOfFormattingRun(i + 1) : rich.length();
            if (start > offset) text.append(escape(rich.getString().substring(offset, start)));
            text.append("<span style=\"").append(fontStyle(rich.getFontOfFormattingRun(i), scale)).append("\">")
                    .append(escape(rich.getString().substring(start, end))).append("</span>");
            offset = end;
        }
        return text.toString();
    }

    private String cellStyle(XSSFCell cell, double scale, double cellWidth) {
        if (cell == null) return "";
        XSSFCellStyle style = cell.getCellStyle();
        if (style.getRotation() != 0) throw new IllegalArgumentException("暂不支持旋转文字：" + cell.getAddress());
        StringBuilder css = new StringBuilder(fontStyle(style.getFont(), scale));
        css.append(shouldWrapText(cell, style, cellWidth, scale) ? "white-space:normal;" : "white-space:nowrap;");
        css.append("text-align:").append(switch (style.getAlignment()) {
            case CENTER, CENTER_SELECTION -> "center";
            case RIGHT -> "right";
            case JUSTIFY, DISTRIBUTED -> "justify";
            default -> cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA ? "right" : "left";
        }).append(";vertical-align:").append(switch (style.getVerticalAlignment()) {
            case TOP -> "top"; case BOTTOM -> "bottom"; default -> "middle";
        }).append(";");
        if (style.getFillPattern() == FillPatternType.SOLID_FOREGROUND) css.append("background-color:").append(color(style.getFillForegroundXSSFColor(), "#ffffff")).append(";");
        css.append(border("top", style.getBorderTop(), style.getTopBorderXSSFColor(), scale))
                .append(border("bottom", style.getBorderBottom(), style.getBottomBorderXSSFColor(), scale))
                .append(border("left", style.getBorderLeft(), style.getLeftBorderXSSFColor(), scale))
                .append(border("right", style.getBorderRight(), style.getRightBorderXSSFColor(), scale));
        return css.toString();
    }

    /** 窄列中的不换行文本改为自动换行，避免标签压到相邻正文或图片上。 */
    private boolean shouldWrapText(XSSFCell cell, XSSFCellStyle style, double cellWidth, double scale) {
        if (style.getWrapText()) return true;
        if (cell.getCellType() != CellType.STRING) return false;
        String value = cell.getStringCellValue();
        if (value.isEmpty()) return false;
        XSSFFont font = style.getFont();
        double fontSize = font == null ? 10 : font.getFontHeightInPoints() * scale;
        double availableWidth = Math.max(1, cellWidth - 3);
        double widestLine = 0;
        double lineWidth = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\n' || codePoint == '\r') {
                widestLine = Math.max(widestLine, lineWidth);
                lineWidth = 0;
            } else if (codePoint <= 0x7f) {
                lineWidth += fontSize * (Character.isWhitespace(codePoint) ? 0.3 : 0.6);
            } else {
                lineWidth += fontSize;
            }
        }
        widestLine = Math.max(widestLine, lineWidth);
        return widestLine > availableWidth;
    }

    private String fontStyle(XSSFFont font, double scale) {
        if (font == null) return "";
        return "font-family:ContractChinese;font-size:" + (font.getFontHeightInPoints() * scale) + "pt;font-weight:" + (font.getBold() ? "bold" : "normal")
                + ";font-style:" + (font.getItalic() ? "italic" : "normal") + ";color:" + color(font.getXSSFColor(), "#000000")
                + ";" + (font.getUnderline() != Font.U_NONE ? "text-decoration:underline;" : "");
    }

    private String border(String side, BorderStyle style, XSSFColor color, double scale) {
        if (style == BorderStyle.NONE) return "";
        double width = switch (style) { case THICK -> 2; case MEDIUM, MEDIUM_DASHED, MEDIUM_DASH_DOT, MEDIUM_DASH_DOT_DOT -> 1.2; default -> 0.5; };
        String line = switch (style) { case DOUBLE -> "double"; case DOTTED, HAIR -> "dotted"; case DASHED, MEDIUM_DASHED -> "dashed"; default -> "solid"; };
        return "border-" + side + ":" + (width * scale) + "pt " + line + " " + color(color, "#000000") + ";";
    }

    private String color(XSSFColor color, String fallback) {
        if (color == null || color.isAuto()) return fallback;
        byte[] rgb = color.getRGB();
        if (rgb == null && color.isIndexed()) rgb = new DefaultIndexedColorMap().getRGB(color.getIndexed());
        if (rgb == null || rgb.length < 3) return fallback;
        return String.format("#%02x%02x%02x", rgb[0] & 255, rgb[1] & 255, rgb[2] & 255);
    }

    private Map<String, List<Image>> pictures(XSSFSheet sheet) {
        Map<String, List<Image>> result = new HashMap<>();
        XSSFDrawing drawing = sheet.getDrawingPatriarch();
        if (drawing == null) return result;
        if (!drawing.getCharts().isEmpty()) throw new IllegalArgumentException("合同模板暂不支持图表转 HTML");
        for (XSSFShape shape : drawing.getShapes()) {
            if (!(shape instanceof XSSFPicture picture)) throw new IllegalArgumentException("合同模板含暂不支持的绘图对象");
            XSSFClientAnchor a = picture.getClientAnchor();
            if (a == null) throw new IllegalArgumentException("图片缺少单元格锚点");
            double width = (a.getDx2() - a.getDx1()) / 9525.0;
            for (int c = a.getCol1(); c < a.getCol2(); c++) width += sheet.getColumnWidthInPixels(c);
            double height = (a.getDy2() - a.getDy1()) / 9525.0;
            for (int r = a.getRow1(); r < a.getRow2(); r++) height += (sheet.getRow(r) == null ? sheet.getDefaultRowHeightInPoints() : sheet.getRow(r).getHeightInPoints()) / 0.75;
            XSSFPictureData data = picture.getPictureData();
            if (!List.of("image/png", "image/jpeg").contains(data.getMimeType())) throw new IllegalArgumentException("图片请使用 PNG/JPEG 格式");
            Image image = new Image(data.getMimeType(), Base64.getEncoder().encodeToString(data.getData()), a.getDx1() / 9525.0,
                    a.getDy1() / 9525.0, Math.max(1, width), Math.max(1, height), a.getRow1(), a.getCol1(), a.getCol2());
            // 图片如果落在合并单元格内部，挂到左上角并补上偏移。
            int row = a.getRow1(), col = a.getCol1();
            CellRangeAddress merge = merged(sheet, row, col);
            if (merge != null) {
                double x = image.x(), y = image.y();
                for (int c = merge.getFirstColumn(); c < col; c++) x += sheet.getColumnWidthInPixels(c);
                for (int r = merge.getFirstRow(); r < row; r++) y += (sheet.getRow(r) == null ? sheet.getDefaultRowHeightInPoints() : sheet.getRow(r).getHeightInPoints()) / 0.75;
                image = new Image(image.mime(), image.data(), x, y, image.width(), image.height(), merge.getFirstRow(),
                        merge.getFirstColumn(), Math.max(merge.getFirstColumn(), a.getCol2()));
                row = merge.getFirstRow(); col = merge.getFirstColumn();
            }
            result.computeIfAbsent(row + ":" + col, ignored -> new ArrayList<>()).add(image);
        }
        return result;
    }

    private List<CellRangeAddress> printAreas(XSSFWorkbook workbook, int index) {
        XSSFSheet sheet = workbook.getSheetAt(index);
        String print = workbook.getPrintArea(index);
        if (print != null) {
            List<CellRangeAddress> result = new ArrayList<>();
            for (AreaReference area : AreaReference.generateContiguous(SpreadsheetVersion.EXCEL2007, print)) {
                result.add(new CellRangeAddress(area.getFirstCell().getRow(), area.getLastCell().getRow(), area.getFirstCell().getCol(), area.getLastCell().getCol()));
            }
            return result;
        }
        int col = 0;
        for (Row row : sheet) col = Math.max(col, row.getLastCellNum() - 1);
        return List.of(new CellRangeAddress(sheet.getFirstRowNum(), sheet.getLastRowNum(), 0, col));
    }

    private double[] pageSize(XSSFSheet sheet) {
        short paper = sheet.getPrintSetup().getPaperSize();
        double w = 595.28, h = 841.89;
        if (paper == PrintSetup.LETTER_PAPERSIZE) { w = 612; h = 792; }
        else if (paper == PrintSetup.A3_PAPERSIZE) { w = 841.89; h = 1190.55; }
        return sheet.getPrintSetup().getLandscape() ? new double[]{h, w} : new double[]{w, h};
    }

    private CellRangeAddress merged(Sheet sheet, int r, int c) {
        for (CellRangeAddress merge : sheet.getMergedRegions()) if (merge.isInRange(r, c)) return merge;
        return null;
    }
    /** 打印范围常包含模板末尾的纯样式空行，不把它们生成到空白尾页。 */
    private int lastContentRow(XSSFSheet sheet, CellRangeAddress area, Map<String, List<Image>> pictures) {
        int last = area.getFirstRow();
        for (int r = area.getFirstRow(); r <= area.getLastRow(); r++) {
            Row row = sheet.getRow(r);
            for (int c = area.getFirstColumn(); c <= area.getLastColumn(); c++) {
                Cell cell = row == null ? null : row.getCell(c);
                if ((cell != null && cell.getCellType() != CellType.BLANK
                        && !(cell.getCellType() == CellType.STRING && cell.getStringCellValue().isEmpty()))
                        || pictures.containsKey(r + ":" + c)) {
                    CellRangeAddress merge = merged(sheet, r, c);
                    last = Math.max(last, merge == null ? r : merge.getLastRow());
                }
            }
        }
        // 图片使用绝对定位，必须保留其覆盖到的行，避免裁掉页尾二维码。
        XSSFDrawing drawing = sheet.getDrawingPatriarch();
        if (drawing != null) for (XSSFShape shape : drawing.getShapes()) {
            if (shape instanceof XSSFPicture picture && picture.getClientAnchor() != null) {
                XSSFClientAnchor anchor = picture.getClientAnchor();
                if (anchor.getRow1() >= area.getFirstRow() && anchor.getRow1() <= area.getLastRow()) last = Math.max(last, anchor.getRow2());
            }
        }
        return Math.min(last, area.getLastRow());
    }

    private int overflowEnd(XSSFSheet sheet, Row row, XSSFCell cell, int r, int c,
                            CellRangeAddress area, Map<String, List<Image>> pictures) {
        if (cell == null || cell.getCellType() != CellType.STRING || cell.getStringCellValue().isEmpty()
                || cell.getCellStyle().getWrapText() || !borderless(cell.getCellStyle())) return c;
        int end = c;
        for (int next = c + 1; next <= area.getLastColumn(); next++) {
            Cell other = row == null ? null : row.getCell(next);
            if (merged(sheet, r, next) != null || pictureOccupiesColumn(pictures, r, next)) break;
            if (other != null && ((other.getCellType() != CellType.BLANK
                    && !(other.getCellType() == CellType.STRING && other.getStringCellValue().isEmpty()))
                    || !borderless(other.getCellStyle()))) break;
            end = next;
        }
        return end;
    }
    /** 跨列图片的整段锚点都要挡住文本溢出，避免文字绘制到图片上。 */
    private boolean pictureOccupiesColumn(Map<String, List<Image>> pictures, int row, int column) {
        return pictures.values().stream().flatMap(Collection::stream)
                .anyMatch(image -> image.row() == row && column >= image.firstColumn() && column <= image.lastColumn());
    }
    private boolean borderless(CellStyle s) {
        return s.getBorderTop() == BorderStyle.NONE && s.getBorderBottom() == BorderStyle.NONE
                && s.getBorderLeft() == BorderStyle.NONE && s.getBorderRight() == BorderStyle.NONE;
    }
    private int visibleColumns(Sheet s, CellRangeAddress a) { int n = 0; for (int c = a.getFirstColumn(); c <= a.getLastColumn(); c++) if (!s.isColumnHidden(c)) n++; return n; }
    private int visibleRows(Sheet s, CellRangeAddress a) { int n = 0; for (int r = a.getFirstRow(); r <= a.getLastRow(); r++) if (s.getRow(r) == null || !s.getRow(r).getZeroHeight()) n++; return n; }
    private String escape(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br/>");
    }
    private record Image(String mime, String data, double x, double y, double width, double height,
                         int row, int firstColumn, int lastColumn) { }
}
