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

import java.awt.Color;
import java.awt.Graphics2D;
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
            Map.entry("#3b82f6", "Blue"),
            Map.entry("#10b981", "Green"),
            Map.entry("#22c55e", "Green"),
            Map.entry("#eab308", "Yellow"),
            Map.entry("#d946ef", "Magenta"),
            Map.entry("#06b6d4", "Cyan"),
            Map.entry("#f97316", "Orange"),
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

            // Mapping each detection to its number in its attribute group for labeling
            Map<String, Integer> detectionToNumberMap = new java.util.HashMap<>();
            for (Map.Entry<String, List<Detection>> entry : groupedByAttr.entrySet()) {
                List<Detection> attrDetections = entry.getValue();
                int num = 1;
                for (Detection det : attrDetections) {
                    detectionToNumberMap.put(det.getId(), num++);
                }
            }

            // Detailed Attribute Sections (starts on a new page)
            if (!grouped.isEmpty()) {
                document.newPage();
                Paragraph r1Header = new Paragraph("Attribute-wise Breakdown", sectionFont);
                r1Header.setSpacingAfter(15);
                document.add(r1Header);

                for (Map.Entry<String, List<Detection>> entry : grouped.entrySet()) {
                    String attrName = entry.getKey();
                    List<Detection> attrDetections = entry.getValue();
                    String colorHex = attrDetections.get(0).getColor();

                    // Attribute Header
                    document.add(createAttributeHeaderTable(
                            attrName, getColorName(colorHex), attrDetections.size(), sectionFont, labelFont));

                    // Element Cards
                    for (Detection det : attrDetections) {
                        // For the card, we render using the specific project file
                        BufferedImage baseImage = null;
                        ProjectFile pFile = projectFileRepository.findById(det.getFileId()).orElse(null);
                        if (pFile != null) {
                            String pagePath = pFile.getFilePath();
                            if ("PDF".equalsIgnoreCase(pFile.getFileType())) {
                                pagePath = project.getFolderPath() + "/" + pFile.getId() + "_page_" + det.getPageNumber() + ".png";
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
                                    det, baseImage, elementIndex, labelFont, valueFont, document, pFile.getFileType());
                            if (card != null) {
                                document.add(card);
                            }
                        }
                    }
                }
            }

            // Full Annotated Screenshot Section (starts on a new page)
            // Group detections by fileId
            Map<String, List<Detection>> detectionsByFile = detections.stream()
                    .collect(Collectors.groupingBy(Detection::getFileId));

            for (Map.Entry<String, List<Detection>> fileEntry : detectionsByFile.entrySet()) {
                String fileId = fileEntry.getKey();
                List<Detection> fileDets = fileEntry.getValue();

                ProjectFile pFile = projectFileRepository.findById(fileId).orElse(null);
                if (pFile == null || pFile.isDeleted()) continue;

                // Group detections of this file by page number to draw page-specific annotations
                Map<Integer, List<Detection>> pageDetsMap = fileDets.stream()
                        .collect(Collectors.groupingBy(Detection::getPageNumber));

                List<Integer> pagesWithDetections = pageDetsMap.keySet().stream()
                        .sorted()
                        .toList();

                for (Integer pageNum : pagesWithDetections) {
                    String pagePath = pFile.getFilePath();
                    if ("PDF".equalsIgnoreCase(pFile.getFileType())) {
                        pagePath = project.getFolderPath() + "/" + pFile.getId() + "_page_" + pageNum + ".png";
                    }
                    File pageImgFile = new File(pagePath);

                    if (pageImgFile.exists()) {
                        document.newPage();

                        String pageTitleText = String.format("Full Annotated Screenshot - %s (Page %d)", pFile.getOriginalFileName(), pageNum);
                        Paragraph previewTitle = new Paragraph(pageTitleText, sectionFont);
                        previewTitle.setSpacingAfter(10);
                        document.add(previewTitle);

                        try {
                            BufferedImage pageImg = ImageIO.read(pageImgFile);

                            // Create a copy to draw on
                            BufferedImage annotatedBimg = new BufferedImage(pageImg.getWidth(), pageImg.getHeight(), BufferedImage.TYPE_INT_ARGB);
                            Graphics2D g2d = annotatedBimg.createGraphics();
                            g2d.drawImage(pageImg, 0, 0, null); // Draw original image first

                            g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                            g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                            List<Detection> pageDets = pageDetsMap.getOrDefault(pageNum, List.of());
                            for (Detection det : pageDets) {
                                try {
                                    String hex = det.getColor();
                                    Color rectColor = (hex != null && hex.startsWith("#")) ? Color.decode(hex) : Color.RED;

                                    Detection.BoundingBox box = getScaledBox(det.getBoundingBox(), pageImg, pFile.getFileType());
                                    if (box != null) {
                                        int drawX = box.getX();
                                        int drawY = box.getY();
                                        int drawW = box.getWidth();
                                        int drawH = box.getHeight();

                                        // Normalize bounding box dimensions
                                        if (drawW < 0) {
                                            drawX = drawX + drawW;
                                            drawW = -drawW;
                                        }
                                        if (drawH < 0) {
                                            drawY = drawY + drawH;
                                            drawH = -drawH;
                                        }

                                        // Draw rectangle border
                                        g2d.setStroke(new java.awt.BasicStroke(4f));
                                        g2d.setColor(rectColor);
                                        g2d.drawRect(drawX, drawY, drawW, drawH);

                                        // Draw light transparent overlay
                                        g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.15f));
                                        g2d.fillRect(drawX, drawY, drawW, drawH);
                                        g2d.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1.0f));

                                        // Label text
                                        String label = String.format("%s - %d (%s)",
                                                det.getAttribute(),
                                                detectionToNumberMap.getOrDefault(det.getId(), 1),
                                                getColorName(det.getColor()));

                                        g2d.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 14));
                                        java.awt.FontMetrics fm = g2d.getFontMetrics();
                                        int labelWidth = fm.stringWidth(label) + 12;
                                        int labelHeight = fm.getHeight() + 6;

                                        int textX = drawX;
                                        int textY = drawY - 6;

                                        // Ensure label stays within image bounds
                                        if (textY - labelHeight < 0) {
                                            textY = drawY + drawH + labelHeight;
                                        }
                                        if (textX + labelWidth > pageImg.getWidth()) {
                                            textX = pageImg.getWidth() - labelWidth;
                                        }
                                        if (textX < 0) {
                                            textX = 0;
                                        }

                                        // Draw background box for text
                                        g2d.setColor(rectColor);
                                        g2d.fillRect(textX, textY - labelHeight + 4, labelWidth, labelHeight);

                                        // Draw white text label
                                        g2d.setColor(Color.WHITE);
                                        g2d.drawString(label, textX + 6, textY - fm.getDescent() + 3);
                                    }
                                } catch (Exception ex) {
                                    System.err.println("Error drawing bounding box: " + ex.getMessage());
                                }
                            }
                            g2d.dispose();

                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(annotatedBimg, "png", baos);
                            Image img = Image.getInstance(baos.toByteArray());

                            img.setAlignment(Element.ALIGN_CENTER);
                            float scaleRatio = (document.getPageSize().getWidth() - document.leftMargin() - document.rightMargin()) / img.getWidth();
                            if (scaleRatio < 1.0f) {
                                img.scalePercent(scaleRatio * 100);
                            } else {
                                img.scalePercent(80);
                            }
                            img.setSpacingAfter(20);
                            document.add(img);
                        } catch (Exception e) {
                            System.err.println("Could not embed annotated image in PDF: " + e.getMessage());
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

    private String getColorName(String hex) {
        if (hex == null) return "Unknown";
        return HEX_TO_COLOR_NAME_MAP.getOrDefault(hex.toLowerCase(), hex);
    }

    private void addMetaCell(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBackgroundColor(new Color(248, 250, 252)); // slate-50
        labelCell.setBorderColor(new Color(226, 232, 240)); // slate-200
        labelCell.setPadding(8);
        labelCell.setBorder(Rectangle.BOX);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, valueFont));
        valueCell.setBackgroundColor(new Color(248, 250, 252)); // slate-50
        valueCell.setBorderColor(new Color(226, 232, 240)); // slate-200
        valueCell.setPadding(8);
        valueCell.setBorder(Rectangle.BOX);

        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private void addHeaderCell(PdfPTable table, String headerText, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(headerText, font));
        cell.setBackgroundColor(new Color(15, 23, 42)); // Slate-900
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
                scaleX = 1.25; // 180 DPI / 144 DPI
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

        // Normalize dimensions
        if (boxW < 0) {
            boxX = boxX + boxW;
            boxW = -boxW;
        }
        if (boxH < 0) {
            boxY = boxY + boxH;
            boxH = -boxH;
        }

        // Add padding for better context
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

        cell.addElement(new Paragraph("ATTRIBUTE: " + attrName, sectionFont));

        String details = String.format("Color: %s | Total Elements: %d", colorName, count);
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

    private PdfPTable createElementCardTable(Detection det, BufferedImage baseImage, int index, Font labelFont, Font valueFont, Document document, String uploadType) {
        // Debug Logging before PDF element card generation (Requirement 6)
        String rawOcrText = det.getDetectedText();
        double confidence = det.getConfidence() != null ? det.getConfidence() : 0.0;
        String displayOcrText = (rawOcrText != null && !rawOcrText.trim().isEmpty()) ? rawOcrText : "OCR Failed";

        System.out.println(String.format(
            "%n=== DEBUG: PDF Generation Element #%d ===%n" +
            "Element ID: %s%n" +
            "Page: %d%n" +
            "Attribute: %s | Color: %s%n" +
            "Bounding Box: x=%d, y=%d, w=%d, h=%d%n" +
            "Extracted Text: %s%n" +
            "Confidence: %.1f%%%n" +
            "Cropped Image Path: %s%n" +
            "============================================",
            index, det.getId(), det.getPageNumber(),
            det.getAttribute(), getColorName(det.getColor()),
            det.getBoundingBox().getX(), det.getBoundingBox().getY(), det.getBoundingBox().getWidth(), det.getBoundingBox().getHeight(),
            displayOcrText,
            confidence,
            det.getCroppedImage() != null ? det.getCroppedImage() : "N/A"
        ));

        BufferedImage croppedBimg;
        try {
            croppedBimg = cropImage(baseImage, det.getBoundingBox(), uploadType);
            if (croppedBimg == null) {
                return null;
            }
        } catch (Exception e) {
            System.err.println("Error cropping image: " + e.getMessage());
            return null;
        }

        PdfPTable cardTable = new PdfPTable(1);
        cardTable.setWidthPercentage(100);
        cardTable.setSpacingBefore(12);
        cardTable.setSpacingAfter(12);
        cardTable.setKeepTogether(true);

        PdfPCell cardCell = new PdfPCell();
        cardCell.setBorder(Rectangle.BOX);
        cardCell.setBorderColor(new Color(226, 232, 240)); // Slate-200
        cardCell.setPadding(15);
        cardCell.setBackgroundColor(Color.WHITE);

        // Card Header: "Element X"
        PdfPTable cardHeader = new PdfPTable(1);
        cardHeader.setWidthPercentage(100);
        PdfPCell headerCell = new PdfPCell(new Phrase("Element " + index, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, new Color(15, 23, 42))));
        headerCell.setBackgroundColor(new Color(241, 245, 249)); // Slate-100
        headerCell.setBorder(Rectangle.NO_BORDER);
        headerCell.setPadding(8);
        cardHeader.addCell(headerCell);
        cardCell.addElement(cardHeader);

        Paragraph space = new Paragraph(" ");
        space.setSpacingBefore(8);
        cardCell.addElement(space);

        // Body Grid Table
        PdfPTable bodyTable = new PdfPTable(2);
        bodyTable.setWidthPercentage(100);
        try {
            bodyTable.setWidths(new float[]{4.5f, 5.5f});
        } catch (Exception e) {}
        bodyTable.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        // Left Cell: Image
        PdfPCell imageCell = new PdfPCell();
        imageCell.setBorder(Rectangle.NO_BORDER);
        imageCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        imageCell.setHorizontalAlignment(Element.ALIGN_CENTER);

        try {
            ByteArrayOutputStream croppedBaos = new ByteArrayOutputStream();
            ImageIO.write(croppedBimg, "png", croppedBaos);
            Image croppedImg = Image.getInstance(croppedBaos.toByteArray());

            float maxImgW = 200f;
            float maxImgH = 150f;
            float wScale = (maxImgW / croppedImg.getWidth()) * 100f;
            float hScale = (maxImgH / croppedImg.getHeight()) * 100f;
            float finalScale = Math.min(wScale, hScale);
            finalScale = Math.min(finalScale, 100f);

            croppedImg.scalePercent(finalScale);
            croppedImg.setAlignment(Element.ALIGN_CENTER);
            imageCell.addElement(croppedImg);
        } catch (Exception e) {
            imageCell.addElement(new Paragraph("[Error loading screenshot: " + e.getMessage() + "]", valueFont));
        }
        bodyTable.addCell(imageCell);

        // Right Cell: Details
        PdfPCell detailsCell = new PdfPCell();
        detailsCell.setBorder(Rectangle.NO_BORDER);
        detailsCell.setPaddingLeft(15);

        PdfPTable detailsGrid = new PdfPTable(2);
        detailsGrid.setWidthPercentage(100);
        try {
            detailsGrid.setWidths(new float[]{3.8f, 6.2f});
        } catch (Exception e) {}

        Font keyFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, new Color(71, 85, 105)); // Slate-600
        Font valFont = FontFactory.getFont(FontFactory.HELVETICA, 10, new Color(15, 23, 42)); // Slate-900

        String bboxStr = String.format("x=%d, y=%d, w=%d, h=%d",
                det.getBoundingBox().getX(), det.getBoundingBox().getY(),
                det.getBoundingBox().getWidth(), det.getBoundingBox().getHeight());

        addGridRow(detailsGrid, "Attribute", det.getAttribute(), keyFont, valFont);
        addGridRow(detailsGrid, "Color", getColorName(det.getColor()), keyFont, valFont);
        addGridRow(detailsGrid, "Page Number", String.valueOf(det.getPageNumber()), keyFont, valFont);
        addGridRow(detailsGrid, "Bounding Box", bboxStr, keyFont, valFont);
        addGridRow(detailsGrid, "OCR Confidence", String.format("%.2f%%", confidence), keyFont, valFont);
        if (det.getProcessingTimeMs() != null) {
            addGridRow(detailsGrid, "Processing Time", det.getProcessingTimeMs() + " ms", keyFont, valFont);
        }
        if (det.getImageResolution() != null) {
            addGridRow(detailsGrid, "Resolution", det.getImageResolution(), keyFont, valFont);
        }
        if (det.getOcrStatus() != null) {
            addGridRow(detailsGrid, "OCR Status", det.getOcrStatus(), keyFont, valFont);
        }

        detailsCell.addElement(detailsGrid);

        Paragraph textHeader = new Paragraph("Detected Text", keyFont);
        textHeader.setSpacingBefore(10);
        textHeader.setSpacingAfter(4);
        detailsCell.addElement(textHeader);

        Paragraph textVal = new Paragraph(displayOcrText, valFont);
        textVal.setSpacingAfter(5);
        detailsCell.addElement(textVal);

        bodyTable.addCell(detailsCell);
        cardCell.addElement(bodyTable);
        cardTable.addCell(cardCell);

        return cardTable;
    }
}
