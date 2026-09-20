package io.github.ghgongjin.sitemap.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ghgongjin.sitemap.entity.SeoReport;
import io.github.ghgongjin.sitemap.repository.SeoReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * @ClassName SeoReportService
 * @Description SEO 报告落库与读取（爬取完成后把内存审计结果写入 H2，并按归属用户隔离读取）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeoReportService {

    private final SeoAuditService seoAuditService;
    private final SeoReportRepository seoReportRepository;
    private final MessageSource messageSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 把任务的内存审计结果持久化并记录归属用户；无审计数据时返回 null
     *
     * @param userId 报告归属用户；游客爬取传 null（数据保留但对任何登录用户不可见）
     */
    @Transactional
    public SeoReport save(String taskId, String siteUrl, Long userId) {
        if (taskId == null || !seoAuditService.hasAudit(taskId)) {
            return null;
        }
        SeoAuditService.AuditSummary summary = seoAuditService.summarize(taskId);
        SeoReport report = seoReportRepository.findByTaskId(taskId).orElseGet(SeoReport::new);
        report.setTaskId(taskId);
        report.setSiteUrl(siteUrl);
        report.setUserId(userId);
        report.setScore(summary.score());
        report.setPagesAudited(summary.pagesAudited());
        report.setBrokenLinks(summary.brokenLinks());
        report.setSkippedPages(summary.skippedPages());
        report.setErrorCount(summary.errorCount());
        report.setWarningCount(summary.warningCount());
        report.setInfoCount(summary.infoCount());
        report.setTruncated(summary.truncated());
        report.setIssuesJson(writeIssues(summary.issues()));
        report.setCreatedAt(LocalDateTime.now());
        SeoReport saved = seoReportRepository.save(report);
        log.info("SEO 报告已保存：taskId={}, userId={}, 评分={}, 问题数={}",
                taskId, userId, summary.score(), summary.issues().size());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<SeoReport> findByTaskId(String taskId) {
        return taskId == null ? Optional.empty() : seoReportRepository.findByTaskId(taskId);
    }

    /**
     * 仅取归属当前用户的报告；任务或用户任一为空时返回 empty（越权与不存在统一按 404 处理）
     */
    @Transactional(readOnly = true)
    public Optional<SeoReport> findOwned(String taskId, Long userId) {
        if (taskId == null || userId == null) {
            return Optional.empty();
        }
        return seoReportRepository.findByTaskIdAndUserId(taskId, userId);
    }

    @Transactional(readOnly = true)
    public boolean hasReport(String taskId) {
        return findByTaskId(taskId).isPresent();
    }

    @Transactional(readOnly = true)
    public List<SeoReport> recent(Long userId) {
        return userId == null ? List.of()
                : seoReportRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<SeoAuditService.Issue> parseIssues(String issuesJson) {
        if (issuesJson == null || issuesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(issuesJson, new TypeReference<List<SeoAuditService.Issue>>() {
            });
        } catch (Exception e) {
            log.warn("SEO 报告问题清单解析失败：{}", e.getMessage());
            return List.of();
        }
    }

    public byte[] exportCsv(SeoReport report, Locale locale) throws JsonProcessingException {
        String json = report.getIssuesJson();
        List<SeoAuditService.Issue> issues = json == null || json.isBlank() ? List.of()
                : objectMapper.readValue(json, new TypeReference<List<SeoAuditService.Issue>>() {});
        StringBuilder csv = new StringBuilder("\uFEFF");
        csvRow(csv, message("report.title", locale), "", "", "");
        csvRow(csv, message("preview.site", locale), report.getSiteUrl(), "", "");
        csvRow(csv, message("preview.task", locale), report.getTaskId(), "", "");
        csvRow(csv, message("report.generated", locale),
                report.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), "", "");
        csvRow(csv, message("reports.th.score", locale), report.getScore(), "", "");
        csvRow(csv, message("report.m.pages", locale), report.getPagesAudited(), "", "");
        csvRow(csv, message("report.m.broken", locale), report.getBrokenLinks(), "", "");
        csvRow(csv, message("report.m.skipped", locale), report.getSkippedPages(), "", "");
        csvRow(csv, message("report.m.errors", locale), report.getErrorCount(), "", "");
        csvRow(csv, message("report.m.warnings", locale), report.getWarningCount(), "", "");
        csvRow(csv, message("report.m.infos", locale), report.getInfoCount(), "", "");
        if (report.isTruncated()) {
            csvRow(csv, message("report.truncated", locale), "", "", "");
        }
        csvRow(csv, "", "", "", "");
        csvRow(csv, message("report.th.severity", locale), message("report.th.rule", locale),
                message("report.th.url", locale), message("report.th.detail", locale));
        for (SeoAuditService.Issue issue : issues) {
            String detail = issue.detail();
            if ("slow-page".equals(issue.rule()) && detail != null && !detail.isEmpty()) {
                detail += " ms";
            }
            csvRow(csv, message("report.sev." + issue.severity().name().toLowerCase(Locale.ROOT), locale),
                    messageSource.getMessage("report.rule." + issue.rule(), null, issue.rule(), locale),
                    issue.url(), detail);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] exportPdf(SeoReport report, Locale locale) throws IOException {
        List<SeoAuditService.Issue> issues = parseIssues(report.getIssuesJson());
        try (PDDocument doc = new PDDocument();
             InputStream fontStream = new ClassPathResource("fonts/NotoSansSC-Regular.ttf").getInputStream()) {
            PDType0Font font = PDType0Font.load(doc, fontStream);
            float fontSize = 10f;
            float titleSize = 16f;
            float headerSize = 12f;
            float margin = 50f;
            float usableWidth = PDRectangle.A4.getWidth() - 2 * margin;
            float lineHeight = fontSize * 1.8f;

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = PDRectangle.A4.getHeight() - margin;

            cs.beginText();
            cs.setFont(font, titleSize);
            cs.newLineAtOffset(margin, y);
            cs.showText(message("report.title", locale));
            cs.endText();
            y -= titleSize * 2.2f;

            cs.beginText();
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(margin, y);
            cs.showText(message("preview.site", locale) + ": " + report.getSiteUrl());
            cs.endText();
            y -= lineHeight;

            cs.beginText();
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(margin, y);
            cs.showText(message("preview.task", locale) + ": " + report.getTaskId());
            cs.endText();
            y -= lineHeight;

            cs.beginText();
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(margin, y);
            cs.showText(message("report.generated", locale) + ": "
                    + report.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            cs.endText();
            y -= lineHeight * 1.5f;

            String[] metrics = {
                    message("reports.th.score", locale) + ": " + report.getScore(),
                    message("report.m.pages", locale) + ": " + report.getPagesAudited(),
                    message("report.m.broken", locale) + ": " + report.getBrokenLinks(),
                    message("report.m.skipped", locale) + ": " + report.getSkippedPages(),
                    message("report.m.errors", locale) + ": " + report.getErrorCount(),
                    message("report.m.warnings", locale) + ": " + report.getWarningCount(),
                    message("report.m.infos", locale) + ": " + report.getInfoCount()
            };
            for (String metric : metrics) {
                cs.beginText();
                cs.setFont(font, fontSize);
                cs.newLineAtOffset(margin, y);
                cs.showText(metric);
                cs.endText();
                y -= lineHeight;
            }
            y -= lineHeight * 0.5f;

            if (report.isTruncated()) {
                y = writeLine(cs, font, fontSize, margin, y, usableWidth, lineHeight,
                        message("report.truncated", locale), PDRectangle.A4, doc, page);
            }

            y -= lineHeight * 0.5f;
            cs.beginText();
            cs.setFont(font, headerSize);
            cs.newLineAtOffset(margin, y);
            cs.showText(message("report.th.severity", locale) + "  |  "
                    + message("report.th.rule", locale) + "  |  "
                    + message("report.th.url", locale) + "  |  "
                    + message("report.th.detail", locale));
            cs.endText();
            y -= lineHeight * 1.2f;

            for (SeoAuditService.Issue issue : issues) {
                String severity = message("report.sev." + issue.severity().name().toLowerCase(Locale.ROOT), locale);
                String rule = messageSource.getMessage("report.rule." + issue.rule(), null, issue.rule(), locale);
                String detail = issue.detail();
                if ("slow-page".equals(issue.rule()) && detail != null && !detail.isEmpty()) {
                    detail += " ms";
                }
                String line = severity + "  |  " + rule + "  |  " + nullSafe(issue.url()) + "  |  " + nullSafe(detail);
                y = writeLine(cs, font, fontSize, margin, y, usableWidth, lineHeight,
                        line, PDRectangle.A4, doc, page);
            }
            cs.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private float writeLine(PDPageContentStream cs, PDType0Font font, float fontSize,
                            float margin, float y, float usableWidth, float lineHeight,
                            String text, PDRectangle pageSize, PDDocument doc, PDPage currentPage) throws IOException {
        String wrapped = wrapText(font, fontSize, text, usableWidth);
        String[] lines = wrapped.split("\n", -1);
        for (String line : lines) {
            if (y < margin + lineHeight) {
                cs.close();
                PDPage newPage = new PDPage(PDRectangle.A4);
                doc.addPage(newPage);
                cs = new PDPageContentStream(doc, newPage);
                y = pageSize.getHeight() - margin;
            }
            cs.beginText();
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(margin, y);
            cs.showText(line);
            cs.endText();
            y -= lineHeight;
        }
        return y;
    }

    private String wrapText(PDType0Font font, float fontSize, String text, float maxWidth) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        StringBuilder currentLine = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                result.append(currentLine).append('\n');
                currentLine.setLength(0);
                continue;
            }
            String test = currentLine.toString() + c;
            try {
                float width = font.getStringWidth(test) / 1000 * fontSize;
                if (width > maxWidth && currentLine.length() > 0) {
                    result.append(currentLine).append('\n');
                    currentLine.setLength(0);
                }
            } catch (Exception ignored) {
            }
            currentLine.append(c);
        }
        result.append(currentLine);
        return result.toString();
    }

    public byte[] exportDocx(SeoReport report, Locale locale) throws IOException {
        List<SeoAuditService.Issue> issues = parseIssues(report.getIssuesJson());
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            String fontName = "Noto Sans SC";

            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = title.createRun();
            titleRun.setText(message("report.title", locale));
            titleRun.setBold(true);
            titleRun.setFontSize(18);
            titleRun.setFontFamily(fontName);

            addMetaLine(doc, fontName, message("preview.site", locale) + ": " + report.getSiteUrl());
            addMetaLine(doc, fontName, message("preview.task", locale) + ": " + report.getTaskId());
            addMetaLine(doc, fontName, message("report.generated", locale) + ": "
                    + report.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            addMetaLine(doc, fontName, "");
            addMetaLine(doc, fontName, message("reports.th.score", locale) + ": " + report.getScore());
            addMetaLine(doc, fontName, message("report.m.pages", locale) + ": " + report.getPagesAudited());
            addMetaLine(doc, fontName, message("report.m.broken", locale) + ": " + report.getBrokenLinks());
            addMetaLine(doc, fontName, message("report.m.skipped", locale) + ": " + report.getSkippedPages());
            addMetaLine(doc, fontName, message("report.m.errors", locale) + ": " + report.getErrorCount());
            addMetaLine(doc, fontName, message("report.m.warnings", locale) + ": " + report.getWarningCount());
            addMetaLine(doc, fontName, message("report.m.infos", locale) + ": " + report.getInfoCount());

            if (report.isTruncated()) {
                addMetaLine(doc, fontName, "");
                addMetaLine(doc, fontName, message("report.truncated", locale));
            }

            addMetaLine(doc, fontName, "");

            XWPFTable table = doc.createTable(issues.size() + 1, 4);
            table.setWidth("100%");
            String[] headers = {
                    message("report.th.severity", locale),
                    message("report.th.rule", locale),
                    message("report.th.url", locale),
                    message("report.th.detail", locale)
            };
            XWPFTableRow headerRow = table.getRow(0);
            for (int i = 0; i < headers.length; i++) {
                XWPFTableCell cell = headerRow.getCell(i);
                cell.setText(headers[i]);
                setCellFont(cell, fontName, true, "D5E8F0");
            }

            for (int idx = 0; idx < issues.size(); idx++) {
                SeoAuditService.Issue issue = issues.get(idx);
                XWPFTableRow row = table.getRow(idx + 1);
                String severity = message("report.sev." + issue.severity().name().toLowerCase(Locale.ROOT), locale);
                String rule = messageSource.getMessage("report.rule." + issue.rule(), null, issue.rule(), locale);
                String detail = issue.detail();
                if ("slow-page".equals(issue.rule()) && detail != null && !detail.isEmpty()) {
                    detail += " ms";
                }
                String[] values = {severity, rule, nullSafe(issue.url()), nullSafe(detail)};
                for (int i = 0; i < values.length; i++) {
                    XWPFTableCell cell = row.getCell(i);
                    cell.setText(values[i]);
                    setCellFont(cell, fontName, false, null);
                }
            }

            doc.write(out);
            return out.toByteArray();
        }
    }

    private void addMetaLine(XWPFDocument doc, String fontName, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(11);
        run.setFontFamily(fontName);
    }

    private void setCellFont(XWPFTableCell cell, String fontName, boolean bold, String bgColor) {
        for (XWPFParagraph p : cell.getParagraphs()) {
            for (XWPFRun run : p.getRuns()) {
                run.setFontFamily(fontName);
                run.setFontSize(10);
                run.setBold(bold);
            }
        }
        if (bgColor != null) {
            CTShd shd = cell.getCTTc().addNewTcPr().addNewShd();
            shd.setVal(STShd.CLEAR);
            shd.setFill(bgColor);
        }
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String message(String key, Locale locale) {
        return messageSource.getMessage(key, null, locale);
    }

    private void csvRow(StringBuilder csv, Object... cells) {
        Csv.row(csv, cells);
    }

    private String writeIssues(List<SeoAuditService.Issue> issues) {
        try {
            return objectMapper.writeValueAsString(issues);
        } catch (Exception e) {
            log.warn("SEO 报告问题清单序列化失败：{}", e.getMessage());
            return "[]";
        }
    }
}
