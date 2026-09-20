package io.github.ghgongjin.sitemap.service;

/**
 * @ClassName Csv
 * @Description CSV 单元格写入：RFC4180 引号转义 + 表格公式注入防护（=+-@ 与前导空白强制加 ' 前缀）
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
public final class Csv {

    private Csv() {
    }

    public static void row(StringBuilder csv, Object... cells) {
        // 逐字迁移自 SeoReportService.csvRow（行为不变，由 ReportExportIntegrationTest 兜底）
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            String value = cells[i] == null ? "" : cells[i].toString();
            String stripped = value.stripLeading();
            // CSV 引号不能阻止表格软件执行公式，外部文本需额外强制为文本。
            if ((!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0)
                    || value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")) {
                value = "'" + value;
            }
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
