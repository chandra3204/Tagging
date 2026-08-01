package com.smartui.analysis.service;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.*;
import com.lowagie.text.pdf.draw.LineSeparator;
import com.smartui.analysis.model.Project;
import com.smartui.analysis.model.ProjectFile;
import com.smartui.analysis.model.Detection;
import com.smartui.analysis.repository.ProjectFileRepository;
import org.springframework.stereotype.Service;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PDFReportService {

    private final OcrService ocrService;
    private final ProjectFileRepository projectFileRepository;
    private final PDFRenderService pdfRenderService;

    public PDFReportService(OcrService ocrService, ProjectFileRepository projectFileRepository, PDFRenderService pdfRenderService) {
        this.ocrService = ocrService;
        this.projectFileRepository = projectFileRepository;
        this.pdfRenderService = pdfRenderService;
    }

    private static final Map<String, String> HEX_TO_COLOR_NAME_MAP = Map.ofEntries(
            Map.entry("#ef4444", "Red"),
            Map.entry("#ff0000", "Red"),
            Map.entry("#3b82f6", "Blue"),
            Map.entry("#0000ff", "Blue"),
            Map.entry("#10b981", "Green"),
            Map.entry("#22c55e", "Green"),
            Map.entry("#008000", "Green"),
            Map.entry("#f97316", "Orange"),
            Map.entry("#eab308", "Yellow"),
            Map.entry("#ffff00", "Yellow"),
            Map.entry("#d946ef", "Purple"),
            Map.entry("#800080", "Purple"),
            Map.entry("#a855f7", "Purple"),
            Map.entry("#06b6d4", "Cyan"),
            Map.entry("#6366f1", "Indigo")
    );

    /**
     * Enterprise Two-Pass Page Event Helper for headers, footers, and dynamic 'Page X of Y' page numbers.
     * Suppresses headers/footers on Cover Page (Page 1).
     */
    public static class EnterpriseHeaderFooterPageEvent extends PdfPageEventHelper {
        private PdfTemplate totalPagesTemplate;
        private BaseFont baseFont;
        private final Font font;

        public EnterpriseHeaderFooterPageEvent() {
            this.font = FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(148, 163, 184));
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            totalPagesTemplate = writer.getDirectContent().createTemplate(35, 16);
            try {
                baseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            } catch (Exception e) {
                System.err.println("Error initializing BaseFont: " + e.getMessage());
            }
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            // Suppress headers and footers on Cover Page (Page 1)
            if (writer.getPageNumber() == 1) return;

            PdfContentByte cb = writer.getDirectContent();
            float x = document.leftMargin();
            float width = document.getPageSize().getWidth() - document.leftMargin() - document.rightMargin();

            // 1. Enterprise Running Header
            try {
                PdfPTable header = new PdfPTable(2);
                header.setWidths(new float[]{2f, 1f});
                header.setTotalWidth(width);
                header.setLockedWidth(true);
                header.getDefaultCell().setBorder(Rectangle.NO_BORDER);
                header.getDefaultCell().setPadding(0);

                PdfPCell leftCell = new PdfPCell(new Phrase("DOCUMENT INTELLIGENCE & OCR ANALYSIS REPORT", font));
                leftCell.setBorder(Rectangle.NO_BORDER);
                leftCell.setHorizontalAlignment(Element.ALIGN_LEFT);
                header.addCell(leftCell);

                PdfPCell rightCell = new PdfPCell(new Phrase("CONFIDENTIAL", font));
                rightCell.setBorder(Rectangle.NO_BORDER);
                rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                header.addCell(rightCell);

                float yHeader = document.getPageSize().getHeight() - document.topMargin() + 15;
                header.writeSelectedRows(0, -1, x, yHeader, cb);

                cb.setColorStroke(new Color(226, 232, 240));
                cb.setLineWidth(0.5f);
                cb.moveTo(x, yHeader - 5);
                cb.lineTo(document.getPageSize().getWidth() - document.rightMargin(), yHeader - 5);
                cb.stroke();
            } catch (Exception e) {
                System.err.println("Error rendering header: " + e.getMessage());
            }

            // 2. Enterprise Running Footer with Dynamic 'Page X of Y'
            try {
                float yFooter = document.bottomMargin() - 15;

                cb.setColorStroke(new Color(226, 232, 240));
                cb.setLineWidth(0.5f);
                cb.moveTo(x, yFooter + 10);
                cb.lineTo(document.getPageSize().getWidth() - document.rightMargin(), yFooter + 10);
                cb.stroke();

                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                cb.beginText();
                cb.setFontAndSize(baseFont != null ? baseFont : BaseFont.createFont(), 8);
                cb.setColorFill(new Color(148, 163, 184));
                cb.setTextMatrix(x, yFooter);
                cb.showText("Generated on " + timestamp);
                cb.endText();

                String pageText = "Page " + writer.getPageNumber() + " of ";
                float textWidth = baseFont != null ? baseFont.getWidthPoint(pageText, 8) : 45f;
                float pageX = document.getPageSize().getWidth() - document.rightMargin() - textWidth - 15;

                cb.beginText();
                cb.setFontAndSize(baseFont != null ? baseFont : BaseFont.createFont(), 8);
                cb.setColorFill(new Color(148, 163, 184));
                cb.setTextMatrix(pageX, yFooter);
                cb.showText(pageText);
                cb.endText();

                if (totalPagesTemplate != null) {
                    cb.addTemplate(totalPagesTemplate, pageX + textWidth, yFooter);
                }
            } catch (Exception e) {
                System.err.println("Error rendering footer: " + e.getMessage());
            }
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            if (totalPagesTemplate != null && baseFont != null) {
                totalPagesTemplate.beginText();
                totalPagesTemplate.setFontAndSize(baseFont, 8);
                totalPagesTemplate.setColorFill(new Color(148, 163, 184));
                totalPagesTemplate.showText(String.valueOf(writer.getPageNumber()));
                totalPagesTemplate.endText();
            }
        }
    }

    public byte[] generateReport(Project project, List<Detection> detections) {
        Document document = new Document(PageSize.A4, 36, 36, 45, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new EnterpriseHeaderFooterPageEvent());
            document.open();

            // Font Palette
            Font coverTitleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 24, new Color(15, 23, 42));
            Font coverSubFont = FontFactory.getFont(FontFactory.HELVETICA, 12, new Color(71, 85, 105));
            Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, new Color(15, 23, 42));
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(71, 85, 105));
            Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(15, 23, 42));
            Font tableHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
            Font tableCellFont = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(15, 23, 42));

            List<ProjectFile> activeFiles = projectFileRepository.findByProjectIdAndIsDeleted(project.getId(), false);
            ProjectFile mainFile = activeFiles.size() > 0 ? activeFiles.get(0) : null;
            int totalPdfPages = activeFiles.stream().mapToInt(ProjectFile::getPageCount).sum();
            Set<Integer> pagesWithDetectionsSet = detections.stream().map(Detection::getPageNumber).collect(Collectors.toSet());
            int pagesProcessedCount = pagesWithDetectionsSet.size();

            double totalConfidence = 0.0;
            int totalElements = detections.size();
            for (Detection det : detections) {
                if (det.getConfidence() != null) totalConfidence += det.getConfidence();
            }
            double ocrAccuracy = totalElements > 0 ? (totalConfidence / totalElements) : 0.0;

            String reportId = "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

            // ====================================================
            // PAGE 1: COVER PAGE
            // ====================================================
            renderCoverPage(document, project, mainFile, reportId, totalPdfPages, totalElements, coverTitleFont, coverSubFont, labelFont, valueFont);
            document.newPage();

            // ====================================================
            // PAGE 2: EXECUTIVE SUMMARY & ATTRIBUTE SUMMARY
            // ====================================================
            renderExecutiveSummaryPage(document, project, mainFile, reportId, totalPdfPages, pagesProcessedCount, totalElements, ocrAccuracy, detections, sectionFont, labelFont, valueFont, tableHeaderFont, tableCellFont);
            document.newPage();

            // ====================================================
            // REMAINING PAGES: COMPACT OCR RESULT CARDS (2-6 per page)
            // ====================================================
            Paragraph resultsHeading = new Paragraph("OCR Extraction Results", sectionFont);
            resultsHeading.setSpacingAfter(10);
            document.add(resultsHeading);

            Map<String, Integer> detectionToNumberMap = new java.util.HashMap<>();
            int elementCounter = 1;

            // Sort detections by Page Number ascending, then by Bounding Box Y ascending
            List<Detection> sortedDetections = detections.stream()
                    .sorted(Comparator.comparing(Detection::getPageNumber)
                            .thenComparing(d -> d.getBoundingBox() != null ? d.getBoundingBox().getY() : 0))
                    .collect(Collectors.toList());

            for (Detection det : sortedDetections) {
                detectionToNumberMap.put(det.getId(), elementCounter++);
            }

            // Cache for rendered page images to prevent repeated IO/PDFBox calls
            Map<String, BufferedImage> pageImageCache = new HashMap<>();

            for (Detection det : sortedDetections) {
                int elemIndex = detectionToNumberMap.getOrDefault(det.getId(), 1);

                BufferedImage baseImage = null;
                ProjectFile pFile = projectFileRepository.findById(det.getFileId()).orElse(mainFile);
                if (pFile != null) {
                    String cacheKey = pFile.getId() + "_p" + det.getPageNumber();
                    if (pageImageCache.containsKey(cacheKey)) {
                        baseImage = pageImageCache.get(cacheKey);
                    } else {
                        String pagePath = pFile.getFilePath();
                        if ("PDF".equalsIgnoreCase(pFile.getFileType())) {
                            try {
                                pagePath = pdfRenderService.renderSinglePage(new File(pFile.getFilePath()), new File(project.getFolderPath()), pFile.getId(), det.getPageNumber(), project.getFolderPath());
                            } catch (Exception e) {
                                System.err.println("Error lazy rendering page " + det.getPageNumber() + ": " + e.getMessage());
                            }
                        }
                        File pageFile = new File(pagePath);
                        if (pageFile.exists()) {
                            try {
                                baseImage = ImageIO.read(pageFile);
                                pageImageCache.put(cacheKey, baseImage);
                            } catch (Exception e) {
                                System.err.println("Error reading page image: " + e.getMessage());
                            }
                        }
                    }
                }

                PdfPTable cardTable = createOcrResultCard(det, baseImage, elemIndex, labelFont, valueFont, pFile != null ? pFile.getFileType() : "PDF", detectionToNumberMap);
                if (cardTable != null) {
                    document.add(cardTable);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            document.close();
        }

        return out.toByteArray();
    }

    private void renderCoverPage(Document document, Project project, ProjectFile mainFile, String reportId, int totalPdfPages, int totalElements, Font titleFont, Font subFont, Font labelFont, Font valueFont) throws DocumentException {
        Paragraph spacingTop = new Paragraph(" ");
        spacingTop.setSpacingBefore(80);
        document.add(spacingTop);

        // Corporate Brand Badge
        PdfPTable brandBadge = new PdfPTable(1);
        brandBadge.setWidthPercentage(100);
        PdfPCell badgeCell = new PdfPCell(new Phrase("ENTERPRISE DOCUMENT OCR & LAYOUT ANALYSIS", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, new Color(37, 99, 235))));
        badgeCell.setBackgroundColor(new Color(239, 246, 255)); // blue-50
        badgeCell.setBorderColor(new Color(191, 219, 254)); // blue-200
        badgeCell.setPadding(8);
        badgeCell.setPaddingLeft(12);
        brandBadge.addCell(badgeCell);
        document.add(brandBadge);

        Paragraph space1 = new Paragraph(" ");
        space1.setSpacingBefore(15);
        document.add(space1);

        Paragraph mainTitle = new Paragraph(project.getName() != null ? project.getName() : "Document Analysis Report", titleFont);
        mainTitle.setSpacingAfter(8);
        document.add(mainTitle);

        Paragraph subtitle = new Paragraph("Executive Summary & Precise OCR Region Analysis Report", subFont);
        subtitle.setSpacingAfter(25);
        document.add(subtitle);

        LineSeparator separator = new LineSeparator(2f, 100, new Color(37, 99, 235), Element.ALIGN_LEFT, -10);
        document.add(new Chunk(separator));

        Paragraph space2 = new Paragraph(" ");
        space2.setSpacingBefore(120);
        document.add(space2);

        // Metadata Card Container
        PdfPTable card = new PdfPTable(2);
        card.setWidthPercentage(100);
        card.setWidths(new float[]{1.5f, 3.5f});
        card.setSpacingAfter(20);

        String fileName = mainFile != null ? mainFile.getOriginalFileName() : "N/A";
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));

        addCoverMetaRow(card, "Report Reference:", reportId, labelFont, valueFont);
        addCoverMetaRow(card, "Project Name:", project.getName() != null ? project.getName() : "N/A", labelFont, valueFont);
        addCoverMetaRow(card, "Target File:", fileName, labelFont, valueFont);
        addCoverMetaRow(card, "Manager/User:", project.getManagerEmail() != null ? project.getManagerEmail() : "manager@app.com", labelFont, valueFont);
        addCoverMetaRow(card, "Generated Date:", dateStr, labelFont, valueFont);
        addCoverMetaRow(card, "Total PDF Pages:", String.valueOf(totalPdfPages), labelFont, valueFont);
        addCoverMetaRow(card, "OCR Regions Tagged:", String.valueOf(totalElements), labelFont, valueFont);

        document.add(card);
    }

    private void addCoverMetaRow(PdfPTable table, String key, String val, Font keyFont, Font valFont) {
        PdfPCell kCell = new PdfPCell(new Phrase(key, keyFont));
        kCell.setBackgroundColor(new Color(248, 250, 252));
        kCell.setBorderColor(new Color(226, 232, 240));
        kCell.setPadding(9);

        PdfPCell vCell = new PdfPCell(new Phrase(val, valFont));
        vCell.setBackgroundColor(new Color(248, 250, 252));
        vCell.setBorderColor(new Color(226, 232, 240));
        vCell.setPadding(9);

        table.addCell(kCell);
        table.addCell(vCell);
    }

    private void renderExecutiveSummaryPage(Document document, Project project, ProjectFile mainFile, String reportId, int totalPdfPages, int pagesProcessedCount, int totalElements, double ocrAccuracy, List<Detection> detections, Font sectionFont, Font labelFont, Font valueFont, Font tableHeaderFont, Font tableCellFont) throws DocumentException {
        Paragraph heading = new Paragraph("Executive Summary", sectionFont);
        heading.setSpacingAfter(10);
        document.add(heading);

        PdfPTable metaTable = new PdfPTable(4);
        metaTable.setWidthPercentage(100);
        metaTable.setWidths(new float[]{1.8f, 3.2f, 1.8f, 3.2f});
        metaTable.setSpacingAfter(15);

        String fileName = mainFile != null ? mainFile.getOriginalFileName() : "N/A";

        addMetaCell(metaTable, "Project Name:", project.getName() != null ? project.getName() : "Unknown", labelFont, valueFont);
        addMetaCell(metaTable, "Report ID:", reportId, labelFont, valueFont);
        addMetaCell(metaTable, "File Name:", fileName, labelFont, valueFont);
        addMetaCell(metaTable, "Generated Date:", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), labelFont, valueFont);
        addMetaCell(metaTable, "Total PDF Pages:", String.valueOf(totalPdfPages), labelFont, valueFont);
        addMetaCell(metaTable, "Pages Analyzed:", String.valueOf(pagesProcessedCount), labelFont, valueFont);
        addMetaCell(metaTable, "Total OCR Regions:", String.valueOf(totalElements), labelFont, valueFont);
        addMetaCell(metaTable, "Avg OCR Accuracy:", String.format("%.1f%%", ocrAccuracy), labelFont, valueFont);

        document.add(metaTable);

        Paragraph attrHeading = new Paragraph("Attribute Breakdown Summary", sectionFont);
        attrHeading.setSpacingBefore(10);
        attrHeading.setSpacingAfter(8);
        document.add(attrHeading);

        Map<String, List<Detection>> groupedByAttr = detections.stream().collect(Collectors.groupingBy(Detection::getAttribute));

        PdfPTable summaryTable = new PdfPTable(4);
        summaryTable.setWidthPercentage(100);
        summaryTable.setWidths(new float[]{35f, 25f, 20f, 20f});
        summaryTable.setSpacingAfter(15);

        addHeaderCell(summaryTable, "Attribute", tableHeaderFont);
        addHeaderCell(summaryTable, "Color Key", tableHeaderFont);
        addHeaderCell(summaryTable, "Elements Tagged", tableHeaderFont);
        addHeaderCell(summaryTable, "Avg Confidence", tableHeaderFont);

        for (Map.Entry<String, List<Detection>> entry : groupedByAttr.entrySet()) {
            String attrName = entry.getKey();
            List<Detection> attrDetections = entry.getValue();
            String colorHex = attrDetections.get(0).getColor();
            double avgConf = attrDetections.stream().mapToDouble(d -> d.getConfidence() != null ? d.getConfidence() : 0.0).average().orElse(0.0);

            addTableCell(summaryTable, attrName, tableCellFont);
            addTableCell(summaryTable, getColorName(colorHex), tableCellFont);
            addTableCell(summaryTable, String.valueOf(attrDetections.size()), tableCellFont);
            addTableCell(summaryTable, String.format("%.1f%%", avgConf), tableCellFont);
        }
        document.add(summaryTable);
    }

    private PdfPTable createOcrResultCard(Detection det, BufferedImage baseImage, int elementIndex, Font labelFont, Font valueFont, String uploadType, Map<String, Integer> detectionToNumberMap) {
        double confidence = det.getConfidence() != null ? det.getConfidence() : 0.0;
        String rawOcrText = det.getDetectedText();
        String displayOcrText = (rawOcrText != null && !rawOcrText.trim().isEmpty()) ? rawOcrText : "No readable text found";

        BufferedImage croppedBimg = null;
        try {
            if (baseImage != null && det.getBoundingBox() != null) {
                croppedBimg = cropImage(baseImage, det.getBoundingBox(), uploadType);
            }
        } catch (Exception e) {
            System.err.println("Error cropping image: " + e.getMessage());
        }

        if (croppedBimg == null && det.getCroppedImage() != null) {
            try {
                File savedCropFile = new File(det.getCroppedImage());
                if (savedCropFile.exists()) {
                    croppedBimg = ImageIO.read(savedCropFile);
                }
            } catch (Exception e) {
                System.err.println("Error reading crop file: " + e.getMessage());
            }
        }

        if (croppedBimg == null) {
            croppedBimg = new BufferedImage(120, 36, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = croppedBimg.createGraphics();
            g.setColor(new Color(241, 245, 249));
            g.fillRect(0, 0, 120, 36);
            g.setColor(new Color(148, 163, 184));
            g.drawString("No Crop", 10, 22);
            g.dispose();
        }

        BufferedImage annotatedPageBimg = null;
        if (baseImage != null) {
            annotatedPageBimg = createAnnotatedPageImage(baseImage, List.of(det), detectionToNumberMap, uploadType);
        }

        // Compact Card Container (keepTogether = true ensures cards auto-pack 2-6 per page)
        PdfPTable cardTable = new PdfPTable(1);
        cardTable.setWidthPercentage(100);
        cardTable.setSpacingBefore(6);
        cardTable.setSpacingAfter(6);
        cardTable.setKeepTogether(true);

        PdfPCell cardCell = new PdfPCell();
        cardCell.setBorder(Rectangle.BOX);
        cardCell.setBorderColor(new Color(226, 232, 240));
        cardCell.setPadding(8);
        cardCell.setBackgroundColor(Color.WHITE);

        // Header Row: Element 1
        PdfPTable cardHeader = new PdfPTable(1);
        cardHeader.setWidthPercentage(100);
        String headerStr = String.format("Element %d", elementIndex);
        PdfPCell headerCell = new PdfPCell(new Phrase(headerStr, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, new Color(15, 23, 42))));
        headerCell.setBackgroundColor(new Color(241, 245, 249));
        headerCell.setBorder(Rectangle.NO_BORDER);
        headerCell.setPadding(5);
        cardHeader.addCell(headerCell);
        cardCell.addElement(cardHeader);

        // Main Layout Grid: 2 Columns
        // Left Column: Metadata & Extracted Text
        // Right Column: Side-by-Side Screenshots (Cropped Region + Full Page Highlight)
        PdfPTable gridTable = new PdfPTable(2);
        gridTable.setWidthPercentage(100);
        try {
            gridTable.setWidths(new float[]{4.5f, 5.5f});
        } catch (Exception e) {}
        gridTable.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        // Left Metadata Cell
        PdfPCell metaCell = new PdfPCell();
        metaCell.setBorder(Rectangle.NO_BORDER);
        metaCell.setPaddingRight(8);

        Font keyFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(71, 85, 105));
        Font valFont = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(15, 23, 42));

        metaCell.addElement(new Paragraph("Attribute:", keyFont));
        Paragraph attrVal = new Paragraph(det.getAttribute(), valFont);
        attrVal.setSpacingAfter(4);
        metaCell.addElement(attrVal);

        metaCell.addElement(new Paragraph("Page:", keyFont));
        Paragraph pageVal = new Paragraph(String.valueOf(det.getPageNumber()), valFont);
        pageVal.setSpacingAfter(4);
        metaCell.addElement(pageVal);

        metaCell.addElement(new Paragraph("Confidence:", keyFont));
        Paragraph confVal = new Paragraph(String.format("%.1f%%", confidence), valFont);
        confVal.setSpacingAfter(4);
        metaCell.addElement(confVal);

        metaCell.addElement(new Paragraph("Detected Text:", keyFont));
        Paragraph textVal = new Paragraph(displayOcrText, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(37, 99, 235)));
        metaCell.addElement(textVal);

        gridTable.addCell(metaCell);

        // Right Screenshots Cell (Side-by-Side Images)
        PdfPCell screenshotCell = new PdfPCell();
        screenshotCell.setBorder(Rectangle.NO_BORDER);

        PdfPTable imageTable = new PdfPTable(2);
        imageTable.setWidthPercentage(100);
        try {
            imageTable.setWidths(new float[]{1f, 1f});
        } catch (Exception e) {}
        imageTable.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        // 1. Cropped Screenshot Cell
        PdfPCell cCell = new PdfPCell();
        cCell.setBorder(Rectangle.NO_BORDER);
        cCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Paragraph cTitle = new Paragraph("Cropped Region", FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(100, 116, 139)));
        cTitle.setAlignment(Element.ALIGN_CENTER);
        cTitle.setSpacingAfter(3);
        cCell.addElement(cTitle);

        try {
            ByteArrayOutputStream cBaos = new ByteArrayOutputStream();
            ImageIO.write(croppedBimg, "png", cBaos);
            Image cImg = Image.getInstance(cBaos.toByteArray());
            float scale = Math.min((110f / cImg.getWidth()) * 100f, (90f / cImg.getHeight()) * 100f);
            cImg.scalePercent(Math.min(scale, 100f));
            cImg.setAlignment(Element.ALIGN_CENTER);
            cCell.addElement(cImg);
        } catch (Exception e) {
            cCell.addElement(new Paragraph("[Crop Error]", valueFont));
        }
        imageTable.addCell(cCell);

        // 2. Full Page Highlight Screenshot Cell
        PdfPCell fCell = new PdfPCell();
        fCell.setBorder(Rectangle.NO_BORDER);
        fCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        fCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Paragraph fTitle = new Paragraph("Page Highlight", FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(100, 116, 139)));
        fTitle.setAlignment(Element.ALIGN_CENTER);
        fTitle.setSpacingAfter(3);
        fCell.addElement(fTitle);

        if (annotatedPageBimg != null) {
            try {
                ByteArrayOutputStream fBaos = new ByteArrayOutputStream();
                ImageIO.write(annotatedPageBimg, "png", fBaos);
                Image fImg = Image.getInstance(fBaos.toByteArray());
                float scale = Math.min((110f / fImg.getWidth()) * 100f, (110f / fImg.getHeight()) * 100f);
                fImg.scalePercent(scale);
                fImg.setAlignment(Element.ALIGN_CENTER);
                fCell.addElement(fImg);
            } catch (Exception e) {
                fCell.addElement(new Paragraph("[Page Error]", valueFont));
            }
        }
        imageTable.addCell(fCell);

        screenshotCell.addElement(imageTable);
        gridTable.addCell(screenshotCell);

        cardCell.addElement(gridTable);
        cardTable.addCell(cardCell);

        return cardTable;
    }

    private Color parseAwtColor(String hex) {
        if (hex == null || hex.trim().isEmpty()) return Color.RED;
        try {
            String clean = hex.trim();
            if (!clean.startsWith("#")) clean = "#" + clean;
            return Color.decode(clean);
        } catch (Exception e) {
            return Color.RED;
        }
    }

    private String getColorName(String hex) {
        if (hex == null) return "Unknown";
        return HEX_TO_COLOR_NAME_MAP.getOrDefault(hex.toLowerCase(), hex);
    }

    private BufferedImage createAnnotatedPageImage(BufferedImage pageImg, List<Detection> pageDets, Map<String, Integer> detectionToNumberMap, String uploadType) {
        if (pageImg == null) return null;
        try {
            BufferedImage annotatedBimg = new BufferedImage(pageImg.getWidth(), pageImg.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = annotatedBimg.createGraphics();
            g2d.drawImage(pageImg, 0, 0, null);

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            for (Detection det : pageDets) {
                try {
                    Color rectColor = parseAwtColor(det.getColor());
                    Detection.BoundingBox box = getScaledBox(det.getBoundingBox(), pageImg, uploadType);
                    if (box != null) {
                        int drawX = box.getX();
                        int drawY = box.getY();
                        int drawW = box.getWidth();
                        int drawH = box.getHeight();

                        if (drawW < 0) { drawX += drawW; drawW = -drawW; }
                        if (drawH < 0) { drawY += drawH; drawH = -drawH; }

                        g2d.setStroke(new BasicStroke(4f));
                        g2d.setColor(rectColor);
                        g2d.drawRect(drawX, drawY, drawW, drawH);

                        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
                        g2d.fillRect(drawX, drawY, drawW, drawH);
                        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));

                        int elemNum = detectionToNumberMap.getOrDefault(det.getId(), 1);
                        String label = String.format("%s #%d", det.getAttribute(), elemNum);

                        g2d.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 14));
                        FontMetrics fm = g2d.getFontMetrics();
                        int labelWidth = fm.stringWidth(label) + 14;
                        int labelHeight = fm.getHeight() + 6;

                        int textX = drawX;
                        int textY = drawY - 6;
                        if (textY - labelHeight < 0) {
                            textY = drawY + drawH + labelHeight;
                        }
                        if (textX + labelWidth > pageImg.getWidth()) {
                            textX = pageImg.getWidth() - labelWidth;
                        }
                        if (textX < 0) textX = 0;

                        g2d.setColor(rectColor);
                        g2d.fillRect(textX, textY - labelHeight + 4, labelWidth, labelHeight);

                        g2d.setColor(Color.WHITE);
                        g2d.drawString(label, textX + 7, textY - fm.getDescent() + 3);
                    }
                } catch (Exception ex) {
                    System.err.println("Error rendering box: " + ex.getMessage());
                }
            }
            g2d.dispose();
            return annotatedBimg;
        } catch (Exception e) {
            return pageImg;
        }
    }

    private void addMetaCell(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBackgroundColor(new Color(248, 250, 252));
        labelCell.setBorderColor(new Color(226, 232, 240));
        labelCell.setPadding(7);
        labelCell.setBorder(Rectangle.BOX);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, valueFont));
        valueCell.setBackgroundColor(new Color(248, 250, 252));
        valueCell.setBorderColor(new Color(226, 232, 240));
        valueCell.setPadding(7);
        valueCell.setBorder(Rectangle.BOX);

        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private void addHeaderCell(PdfPTable table, String headerText, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(headerText, font));
        cell.setBackgroundColor(new Color(15, 23, 42));
        cell.setPadding(7);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setBorderColor(new Color(226, 232, 240));
        table.addCell(cell);
    }

    private void addTableCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(7);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setBorderColor(new Color(226, 232, 240));
        table.addCell(cell);
    }

    private Detection.BoundingBox getScaledBox(Detection.BoundingBox box, BufferedImage originalImage, String uploadType) {
        if (box == null || originalImage == null) return box;

        double scaleX;
        double scaleY;

        if (box.getCanvasWidth() != null && box.getCanvasWidth() > 0 &&
            box.getCanvasHeight() != null && box.getCanvasHeight() > 0) {
            scaleX = (double) originalImage.getWidth() / box.getCanvasWidth();
            scaleY = (double) originalImage.getHeight() / box.getCanvasHeight();
        } else {
            if ("PDF".equalsIgnoreCase(uploadType)) {
                scaleX = 1.25;
                scaleY = 1.25;
            } else {
                double s = Math.max(1.0, (double) originalImage.getWidth() / 800.0);
                scaleX = s;
                scaleY = s;
            }
        }

        Detection.BoundingBox scaledBox = new Detection.BoundingBox();
        scaledBox.setX((int) Math.round(box.getX() * scaleX));
        scaledBox.setY((int) Math.round(box.getY() * scaleY));
        scaledBox.setWidth((int) Math.round(box.getWidth() * scaleX));
        scaledBox.setHeight((int) Math.round(box.getHeight() * scaleY));
        scaledBox.setCanvasWidth(originalImage.getWidth());
        scaledBox.setCanvasHeight(originalImage.getHeight());
        return scaledBox;
    }

    private BufferedImage cropImage(BufferedImage originalImage, Detection.BoundingBox rawBox, String uploadType) throws Exception {
        Detection.BoundingBox box = getScaledBox(rawBox, originalImage, uploadType);
        if (originalImage == null || box == null) {
            return null;
        }

        int boxX = box.getX();
        int boxY = box.getY();
        int boxW = box.getWidth();
        int boxH = box.getHeight();

        if (boxW < 0) { boxX += boxW; boxW = -boxW; }
        if (boxH < 0) { boxY += boxH; boxH = -boxH; }

        int padding = 15;
        int x = Math.max(0, boxX - padding);
        int y = Math.max(0, boxY - padding);

        int right = Math.min(originalImage.getWidth(), boxX + boxW + padding);
        int bottom = Math.min(originalImage.getHeight(), boxY + boxH + padding);

        int width = right - x;
        int height = bottom - y;

        if (width <= 0 || height <= 0) {
            return null;
        }

        return originalImage.getSubimage(x, y, width, height);
    }
}
