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
            Map.entry("#d946ef", "Purple / Magenta"),
            Map.entry("#800080", "Purple"),
            Map.entry("#a855f7", "Purple"),
            Map.entry("#06b6d4", "Cyan"),
            Map.entry("#6366f1", "Indigo")
    );

    /**
     * Enterprise Two-Pass Page Event Helper for headers, footers, and dynamic 'Page X of Y' page numbers.
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
            totalPagesTemplate = writer.getDirectContent().createTemplate(30, 16);
            try {
                baseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            } catch (Exception e) {
                System.err.println("Error initializing BaseFont: " + e.getMessage());
            }
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float x = document.leftMargin();
            float width = document.getPageSize().getWidth() - document.leftMargin() - document.rightMargin();

            // 1. Enterprise Header
            PdfPTable header = new PdfPTable(2);
            try {
                header.setWidths(new float[]{1.5f, 1f});
                header.setTotalWidth(width);
                header.setLockedWidth(true);
                header.getDefaultCell().setBorder(Rectangle.NO_BORDER);
                header.getDefaultCell().setPadding(0);

                PdfPCell leftCell = new PdfPCell(new Phrase("Smart UI Analysis Report | Enterprise Edition", font));
                leftCell.setBorder(Rectangle.NO_BORDER);
                leftCell.setHorizontalAlignment(Element.ALIGN_LEFT);
                header.addCell(leftCell);

                PdfPCell rightCell = new PdfPCell(new Phrase("CONFIDENTIAL & PROPRIETARY", font));
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
                System.err.println("Error rendering PDF header: " + e.getMessage());
            }

            // 2. Enterprise Footer with Page X of Y
            try {
                float yFooter = document.bottomMargin() - 15;

                cb.setColorStroke(new Color(226, 232, 240));
                cb.setLineWidth(0.5f);
                cb.moveTo(x, yFooter + 10);
                cb.lineTo(document.getPageSize().getWidth() - document.rightMargin(), yFooter + 10);
                cb.stroke();

                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
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
                System.err.println("Error rendering PDF footer: " + e.getMessage());
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
        Document document = new Document(PageSize.A4, 40, 40, 50, 40);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new EnterpriseHeaderFooterPageEvent());
            document.open();

            // Corporate Font System
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, new Color(15, 23, 42)); // Slate-900
            Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, new Color(30, 41, 59)); // Slate-800
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(71, 85, 105)); // Slate-600
            Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(15, 23, 42)); // Slate-900
            Font tableHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
            Font tableCellFont = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(15, 23, 42));

            // Title Header
            Paragraph title = new Paragraph("Smart UI Analysis & OCR Report", titleFont);
            title.setSpacingAfter(12);
            document.add(title);

            // Corporate Divider Line
            LineSeparator titleSeparator = new LineSeparator(1.5f, 100, new Color(37, 99, 235), Element.ALIGN_LEFT, -8);
            document.add(new Chunk(titleSeparator));

            Paragraph spacing = new Paragraph(" ");
            spacing.setSpacingAfter(4);
            document.add(spacing);

            // 1. Executive Summary Section
            Paragraph execTitle = new Paragraph("Executive Summary", sectionFont);
            execTitle.setSpacingAfter(8);
            document.add(execTitle);

            // Fetch active project files count & total pages
            List<ProjectFile> activeFiles = projectFileRepository.findByProjectIdAndIsDeleted(project.getId(), false);
            int totalPdfPages = activeFiles.stream().mapToInt(ProjectFile::getPageCount).sum();
            Set<Integer> pagesWithDetectionsSet = detections.stream().map(Detection::getPageNumber).collect(Collectors.toSet());
            int pagesProcessedCount = pagesWithDetectionsSet.size();

            double totalConfidence = 0.0;
            long totalProcessingMs = 0;
            int totalElements = detections.size();
            for (Detection det : detections) {
                if (det.getConfidence() != null) totalConfidence += det.getConfidence();
                if (det.getProcessingTimeMs() != null) totalProcessingMs += det.getProcessingTimeMs();
            }
            double ocrAccuracy = totalElements > 0 ? (totalConfidence / totalElements) : 0.0;

            Map<String, List<Detection>> groupedByAttr = detections.stream()
                    .collect(Collectors.groupingBy(Detection::getAttribute));
            int totalAttributes = groupedByAttr.size();

            PdfPTable metaTable = new PdfPTable(4);
            metaTable.setWidthPercentage(100);
            metaTable.setWidths(new float[]{1.8f, 3.2f, 1.8f, 3.2f});
            metaTable.setSpacingAfter(15);

            String reportId = "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String fileName = activeFiles.size() > 0 ? activeFiles.get(0).getOriginalFileName() : "N/A";
            String fileType = activeFiles.size() > 0 ? activeFiles.get(0).getFileType() : "N/A";

            addMetaCell(metaTable, "Project Name:", project.getName() != null ? project.getName() : "Unknown", labelFont, valueFont);
            addMetaCell(metaTable, "Report ID:", reportId, labelFont, valueFont);
            addMetaCell(metaTable, "Manager/User:", project.getManagerEmail() != null ? project.getManagerEmail() : "manager@app.com", labelFont, valueFont);
            addMetaCell(metaTable, "Generated Date:", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), labelFont, valueFont);
            addMetaCell(metaTable, "File Name:", fileName, labelFont, valueFont);
            addMetaCell(metaTable, "File Type:", fileType, labelFont, valueFont);
            addMetaCell(metaTable, "Total PDF Pages:", String.valueOf(totalPdfPages), labelFont, valueFont);
            addMetaCell(metaTable, "Pages Analyzed:", String.valueOf(pagesProcessedCount), labelFont, valueFont);
            addMetaCell(metaTable, "Total OCR Regions:", String.valueOf(totalElements), labelFont, valueFont);
            addMetaCell(metaTable, "Total Attributes:", String.valueOf(totalAttributes), labelFont, valueFont);
            addMetaCell(metaTable, "Avg OCR Accuracy:", String.format("%.2f%%", ocrAccuracy), labelFont, valueFont);
            addMetaCell(metaTable, "Total Duration:", totalProcessingMs + " ms", labelFont, valueFont);

            document.add(metaTable);

            // 2. Attribute Summary Section
            Paragraph attrTitle = new Paragraph("Attribute Breakdown Summary", sectionFont);
            attrTitle.setSpacingAfter(8);
            document.add(attrTitle);

            PdfPTable summaryTable = new PdfPTable(4);
            summaryTable.setWidthPercentage(100);
            summaryTable.setWidths(new float[]{35f, 25f, 20f, 20f});
            summaryTable.setSpacingAfter(15);

            addHeaderCell(summaryTable, "Attribute", tableHeaderFont);
            addHeaderCell(summaryTable, "Color Key", tableHeaderFont);
            addHeaderCell(summaryTable, "Elements", tableHeaderFont);
            addHeaderCell(summaryTable, "Avg Confidence", tableHeaderFont);

            for (Map.Entry<String, List<Detection>> entry : groupedByAttr.entrySet()) {
                String attrName = entry.getKey();
                List<Detection> attrDetections = entry.getValue();
                String colorHex = attrDetections.get(0).getColor();
                double avgConf = attrDetections.stream().mapToDouble(d -> d.getConfidence() != null ? d.getConfidence() : 0.0).average().orElse(0.0);

                addTableCell(summaryTable, attrName, tableCellFont);
                addTableCell(summaryTable, getColorName(colorHex), tableCellFont);
                addTableCell(summaryTable, String.valueOf(attrDetections.size()), tableCellFont);
                addTableCell(summaryTable, String.format("%.2f%%", avgConf), tableCellFont);
            }
            document.add(summaryTable);

            // 3. Page Summary Section
            Paragraph pageSummaryTitle = new Paragraph("Page-by-Page Summary", sectionFont);
            pageSummaryTitle.setSpacingAfter(8);
            document.add(pageSummaryTitle);

            Map<Integer, List<Detection>> groupedByPage = detections.stream()
                    .collect(Collectors.groupingBy(Detection::getPageNumber, TreeMap::new, Collectors.toList()));

            PdfPTable pageSummaryTable = new PdfPTable(4);
            pageSummaryTable.setWidthPercentage(100);
            pageSummaryTable.setWidths(new float[]{20f, 25f, 30f, 25f});
            pageSummaryTable.setSpacingAfter(20);

            addHeaderCell(pageSummaryTable, "Page Number", tableHeaderFont);
            addHeaderCell(pageSummaryTable, "OCR Regions", tableHeaderFont);
            addHeaderCell(pageSummaryTable, "Page Avg Confidence", tableHeaderFont);
            addHeaderCell(pageSummaryTable, "Processing Time", tableHeaderFont);

            for (Map.Entry<Integer, List<Detection>> entry : groupedByPage.entrySet()) {
                int pageNum = entry.getKey();
                List<Detection> pageDets = entry.getValue();
                double pAvgConf = pageDets.stream().mapToDouble(d -> d.getConfidence() != null ? d.getConfidence() : 0.0).average().orElse(0.0);
                long pTotalMs = pageDets.stream().mapToLong(d -> d.getProcessingTimeMs() != null ? d.getProcessingTimeMs() : 0L).sum();

                addTableCell(pageSummaryTable, "Page " + pageNum, tableCellFont);
                addTableCell(pageSummaryTable, String.valueOf(pageDets.size()), tableCellFont);
                addTableCell(pageSummaryTable, String.format("%.2f%%", pAvgConf), tableCellFont);
                addTableCell(pageSummaryTable, pTotalMs + " ms", tableCellFont);
            }
            document.add(pageSummaryTable);

            // Mapping each detection to its number for labeling
            Map<String, Integer> detectionToNumberMap = new java.util.HashMap<>();
            int globalIndex = 1;
            for (Detection det : detections) {
                detectionToNumberMap.put(det.getId(), globalIndex++);
            }

            // 4. Detailed Element Cards (Attribute-wise)
            if (!groupedByAttr.isEmpty()) {
                document.newPage();
                Paragraph r1Header = new Paragraph("Detailed Element Analysis Cards", sectionFont);
                r1Header.setSpacingAfter(15);
                document.add(r1Header);

                for (Map.Entry<String, List<Detection>> entry : groupedByAttr.entrySet()) {
                    String attrName = entry.getKey();
                    List<Detection> attrDetections = entry.getValue();
                    String colorHex = attrDetections.get(0).getColor();

                    // Attribute Header
                    document.add(createAttributeHeaderTable(
                            attrName, getColorName(colorHex), attrDetections.size(), sectionFont, labelFont));

                    // Element Cards
                    for (Detection det : attrDetections) {
                        BufferedImage baseImage = null;
                        ProjectFile pFile = projectFileRepository.findById(det.getFileId()).orElse(null);
                        if (pFile != null) {
                            String pagePath = pFile.getFilePath();
                            if ("PDF".equalsIgnoreCase(pFile.getFileType())) {
                                try {
                                    pagePath = pdfRenderService.renderSinglePage(new File(pFile.getFilePath()), new File(project.getFolderPath()), pFile.getId(), det.getPageNumber(), project.getFolderPath());
                                } catch (Exception e) {
                                    System.err.println("Failed lazy page render for report card: " + e.getMessage());
                                }
                            }
                            File pageFile = new File(pagePath);
                            if (pageFile.exists()) {
                                try {
                                    baseImage = ImageIO.read(pageFile);
                                } catch (IOException e) {
                                    System.err.println("Could not load base image: " + e.getMessage());
                                }
                            }

                            int elementIndex = detectionToNumberMap.getOrDefault(det.getId(), 1);
                            PdfPTable card = createElementCardTable(
                                    det, baseImage, elementIndex, labelFont, valueFont, document, pFile != null ? pFile.getFileType() : "PDF", detectionToNumberMap);
                            if (card != null) {
                                document.add(card);
                            }
                        }
                    }
                }
            }

            // 5. Full-Page Document Annotated Visual Overview Section
            Map<String, List<Detection>> detectionsByFile = detections.stream()
                    .collect(Collectors.groupingBy(Detection::getFileId));

            if (!detectionsByFile.isEmpty()) {
                document.newPage();
                Paragraph previewSectionTitle = new Paragraph("Full-Page Document Annotated Overview", sectionFont);
                previewSectionTitle.setSpacingAfter(12);
                document.add(previewSectionTitle);

                for (Map.Entry<String, List<Detection>> fileEntry : detectionsByFile.entrySet()) {
                    String fileId = fileEntry.getKey();
                    List<Detection> fileDets = fileEntry.getValue();

                    ProjectFile pFile = projectFileRepository.findById(fileId).orElse(null);
                    if (pFile == null || pFile.isDeleted()) continue;

                    Map<Integer, List<Detection>> pageDetsMap = fileDets.stream()
                            .collect(Collectors.groupingBy(Detection::getPageNumber));

                    List<Integer> pagesWithDetections = pageDetsMap.keySet().stream().sorted().toList();

                    for (Integer pageNum : pagesWithDetections) {
                        String pagePath = pFile.getFilePath();
                        if ("PDF".equalsIgnoreCase(pFile.getFileType())) {
                            try {
                                pagePath = pdfRenderService.renderSinglePage(new File(pFile.getFilePath()), new File(project.getFolderPath()), pFile.getId(), pageNum, project.getFolderPath());
                            } catch (Exception e) {
                                System.err.println("Failed lazy page render for full page overview: " + e.getMessage());
                            }
                        }
                        File pageImgFile = new File(pagePath);

                        if (pageImgFile.exists()) {
                            try {
                                BufferedImage pageImg = ImageIO.read(pageImgFile);
                                List<Detection> pageDets = pageDetsMap.getOrDefault(pageNum, List.of());
                                BufferedImage annotatedImg = createAnnotatedPageImage(pageImg, pageDets, detectionToNumberMap, pFile.getFileType());

                                if (annotatedImg != null) {
                                    document.newPage();
                                    String pageTitleText = String.format("Full Annotated Document - %s (Page %d of %d)", pFile.getOriginalFileName(), pageNum, pFile.getPageCount());
                                    Paragraph pageHeading = new Paragraph(pageTitleText, sectionFont);
                                    pageHeading.setSpacingAfter(10);
                                    document.add(pageHeading);

                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    ImageIO.write(annotatedImg, "png", baos);
                                    Image img = Image.getInstance(baos.toByteArray());
                                    img.setAlignment(Element.ALIGN_CENTER);

                                    float maxW = document.getPageSize().getWidth() - document.leftMargin() - document.rightMargin();
                                    float scaleRatio = maxW / img.getWidth();
                                    img.scalePercent(scaleRatio * 95);
                                    img.setSpacingAfter(15);

                                    document.add(img);
                                }
                            } catch (Exception e) {
                                System.err.println("Could not generate full page annotated overview image: " + e.getMessage());
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            document.close();
        }

        return out.toByteArray();
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

                        // 1. Thick colored border
                        g2d.setStroke(new BasicStroke(4f));
                        g2d.setColor(rectColor);
                        g2d.drawRect(drawX, drawY, drawW, drawH);

                        // 2. Light translucent overlay fill
                        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
                        g2d.fillRect(drawX, drawY, drawW, drawH);
                        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));

                        // 3. Attribute tag badge label
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
                    System.err.println("Error rendering annotation box: " + ex.getMessage());
                }
            }
            g2d.dispose();
            return annotatedBimg;
        } catch (Exception e) {
            System.err.println("Failed to create annotated page image: " + e.getMessage());
            return pageImg;
        }
    }

    private void addMetaCell(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBackgroundColor(new Color(248, 250, 252));
        labelCell.setBorderColor(new Color(226, 232, 240));
        labelCell.setPadding(8);
        labelCell.setBorder(Rectangle.BOX);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, valueFont));
        valueCell.setBackgroundColor(new Color(248, 250, 252));
        valueCell.setBorderColor(new Color(226, 232, 240));
        valueCell.setPadding(8);
        valueCell.setBorder(Rectangle.BOX);

        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private void addHeaderCell(PdfPTable table, String headerText, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(headerText, font));
        cell.setBackgroundColor(new Color(15, 23, 42));
        cell.setPadding(8);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setBorderColor(new Color(226, 232, 240));
        table.addCell(cell);
    }

    private void addTableCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(8);
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

    private PdfPTable createAttributeHeaderTable(String attrName, String colorName, int count, Font sectionFont, Font labelFont) {
        PdfPTable headerTableWrapper = new PdfPTable(1);
        headerTableWrapper.setWidthPercentage(100);
        headerTableWrapper.setKeepTogether(true);
        headerTableWrapper.setSpacingBefore(15);
        headerTableWrapper.setSpacingAfter(8);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);

        cell.addElement(new Paragraph("ATTRIBUTE: " + attrName.toUpperCase(), sectionFont));

        String details = String.format("Color Key: %s | Total Regions Tagged: %d", colorName, count);
        Paragraph detailsPara = new Paragraph(details, labelFont);
        detailsPara.setSpacingBefore(4);
        cell.addElement(detailsPara);

        LineSeparator separator = new LineSeparator(1, 100, new Color(226, 232, 240), Element.ALIGN_CENTER, -5);
        cell.addElement(new Chunk(separator));

        headerTableWrapper.addCell(cell);
        return headerTableWrapper;
    }

    private void addGridRow(PdfPTable table, String key, String val, Font keyFont, Font valFont) {
        PdfPCell kCell = new PdfPCell(new Phrase(key, keyFont));
        kCell.setBorder(Rectangle.NO_BORDER);
        kCell.setPadding(4);
        kCell.setPaddingLeft(0);

        PdfPCell vCell = new PdfPCell(new Phrase(val, valFont));
        vCell.setBorder(Rectangle.NO_BORDER);
        vCell.setPadding(4);

        table.addCell(kCell);
        table.addCell(vCell);
    }

    private PdfPTable createElementCardTable(Detection det, BufferedImage baseImage, int index, Font labelFont, Font valueFont, Document document, String uploadType, Map<String, Integer> detectionToNumberMap) {
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
                System.err.println("Error reading saved crop: " + e.getMessage());
            }
        }

        if (croppedBimg == null) {
            croppedBimg = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = croppedBimg.createGraphics();
            g.setColor(new Color(241, 245, 249));
            g.fillRect(0, 0, 120, 40);
            g.setColor(new Color(148, 163, 184));
            g.drawString("No Crop Preview", 10, 24);
            g.dispose();
        }

        // Generate full-page annotated screenshot containing this element
        BufferedImage annotatedPageBimg = null;
        if (baseImage != null) {
            annotatedPageBimg = createAnnotatedPageImage(baseImage, List.of(det), detectionToNumberMap, uploadType);
        }

        PdfPTable cardTable = new PdfPTable(1);
        cardTable.setWidthPercentage(100);
        cardTable.setSpacingBefore(12);
        cardTable.setSpacingAfter(12);
        cardTable.setKeepTogether(true);

        PdfPCell cardCell = new PdfPCell();
        cardCell.setBorder(Rectangle.BOX);
        cardCell.setBorderColor(new Color(226, 232, 240));
        cardCell.setPadding(12);
        cardCell.setBackgroundColor(Color.WHITE);

        // Card Header
        PdfPTable cardHeader = new PdfPTable(1);
        cardHeader.setWidthPercentage(100);
        String headerTitleStr = String.format("Element #%d  |  Page %d  |  %s (%s)", index, det.getPageNumber(), det.getAttribute(), getColorName(det.getColor()));
        PdfPCell headerCell = new PdfPCell(new Phrase(headerTitleStr, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, new Color(15, 23, 42))));
        headerCell.setBackgroundColor(new Color(241, 245, 249));
        headerCell.setBorder(Rectangle.NO_BORDER);
        headerCell.setPadding(8);
        cardHeader.addCell(headerCell);
        cardCell.addElement(cardHeader);

        Paragraph space = new Paragraph(" ");
        space.setSpacingBefore(6);
        cardCell.addElement(space);

        // Top Grid: 2 Columns (Cropped Region Image on left, Metadata Grid on right)
        PdfPTable bodyTable = new PdfPTable(2);
        bodyTable.setWidthPercentage(100);
        try {
            bodyTable.setWidths(new float[]{4f, 6f});
        } catch (Exception e) {}
        bodyTable.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        // Left Column: Cropped Image Box
        PdfPCell croppedCell = new PdfPCell();
        croppedCell.setBorder(Rectangle.BOX);
        croppedCell.setBorderColor(new Color(226, 232, 240));
        croppedCell.setPadding(8);
        croppedCell.setBackgroundColor(new Color(248, 250, 252));
        croppedCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        croppedCell.setHorizontalAlignment(Element.ALIGN_CENTER);

        Paragraph cropHeading = new Paragraph("Cropped Selected Region", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(71, 85, 105)));
        cropHeading.setAlignment(Element.ALIGN_CENTER);
        cropHeading.setSpacingAfter(6);
        croppedCell.addElement(cropHeading);

        try {
            ByteArrayOutputStream croppedBaos = new ByteArrayOutputStream();
            ImageIO.write(croppedBimg, "png", croppedBaos);
            Image croppedImg = Image.getInstance(croppedBaos.toByteArray());

            float maxImgW = 180f;
            float maxImgH = 120f;
            float wScale = (maxImgW / croppedImg.getWidth()) * 100f;
            float hScale = (maxImgH / croppedImg.getHeight()) * 100f;
            float finalScale = Math.min(Math.min(wScale, hScale), 100f);

            croppedImg.scalePercent(finalScale);
            croppedImg.setAlignment(Element.ALIGN_CENTER);
            croppedCell.addElement(croppedImg);
        } catch (Exception e) {
            croppedCell.addElement(new Paragraph("[Cropped Image Error: " + e.getMessage() + "]", valueFont));
        }
        bodyTable.addCell(croppedCell);

        // Right Column: Metadata Grid
        PdfPCell detailsCell = new PdfPCell();
        detailsCell.setBorder(Rectangle.NO_BORDER);
        detailsCell.setPaddingLeft(12);

        PdfPTable detailsGrid = new PdfPTable(2);
        detailsGrid.setWidthPercentage(100);
        try {
            detailsGrid.setWidths(new float[]{3.8f, 6.2f});
        } catch (Exception e) {}

        Font keyFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(71, 85, 105));
        Font valFont = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(15, 23, 42));

        String bboxStr = String.format("x=%d, y=%d, w=%d, h=%d",
                det.getBoundingBox().getX(), det.getBoundingBox().getY(),
                det.getBoundingBox().getWidth(), det.getBoundingBox().getHeight());

        addGridRow(detailsGrid, "Attribute", det.getAttribute(), keyFont, valFont);
        addGridRow(detailsGrid, "Color", getColorName(det.getColor()), keyFont, valFont);
        addGridRow(detailsGrid, "Page Number", "Page " + det.getPageNumber(), keyFont, valFont);
        addGridRow(detailsGrid, "Bounding Box", bboxStr, keyFont, valFont);
        addGridRow(detailsGrid, "OCR Confidence", String.format("%.2f%%", confidence), keyFont, valFont);
        if (det.getProcessingTimeMs() != null) {
            addGridRow(detailsGrid, "Processing Time", det.getProcessingTimeMs() + " ms", keyFont, valFont);
        }

        detailsCell.addElement(detailsGrid);

        Paragraph textHeader = new Paragraph("Detected OCR Text", keyFont);
        textHeader.setSpacingBefore(8);
        textHeader.setSpacingAfter(3);
        detailsCell.addElement(textHeader);

        Paragraph textVal = new Paragraph(displayOcrText, valFont);
        textVal.setSpacingAfter(4);
        detailsCell.addElement(textVal);

        bodyTable.addCell(detailsCell);
        cardCell.addElement(bodyTable);

        // Bottom Section: Annotated Full Page Screenshot Thumbnail showing exact location
        if (annotatedPageBimg != null) {
            Paragraph fullPageHead = new Paragraph("Annotated Full Page Location (Page " + det.getPageNumber() + ")", keyFont);
            fullPageHead.setSpacingBefore(10);
            fullPageHead.setSpacingAfter(6);
            cardCell.addElement(fullPageHead);

            try {
                ByteArrayOutputStream fullBaos = new ByteArrayOutputStream();
                ImageIO.write(annotatedPageBimg, "png", fullBaos);
                Image fullImg = Image.getInstance(fullBaos.toByteArray());
                fullImg.setAlignment(Element.ALIGN_CENTER);

                float maxW = document.getPageSize().getWidth() - document.leftMargin() - document.rightMargin() - 30;
                float scaleRatio = (maxW / fullImg.getWidth()) * 100f;
                fullImg.scalePercent(Math.min(scaleRatio, 65f));

                cardCell.addElement(fullImg);
            } catch (Exception e) {
                System.err.println("Error embedding annotated full page screenshot: " + e.getMessage());
            }
        }

        cardTable.addCell(cardCell);
        return cardTable;
    }
}
