package com.smartui.analysis.service;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.draw.LineSeparator;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfContentByte;
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

    public PDFReportService(OcrService ocrService, ProjectFileRepository projectFileRepository) {
        this.ocrService = ocrService;
        this.projectFileRepository = projectFileRepository;
    }

    private static final Map<String, String> HEX_TO_COLOR_NAME_MAP = Map.ofEntries(
            Map.entry("#ef4444", "Red"),
            Map.entry("#3b82f6", "Blue"),
            Map.entry("#10b981", "Green"),
            Map.entry("#22c55e", "Green"), // Supporting multiple shades
            Map.entry("#eab308", "Yellow"),
            Map.entry("#d946ef", "Magenta"),
            Map.entry("#06b6d4", "Cyan"),
            Map.entry("#f97316", "Orange"),
            Map.entry("#6366f1", "Indigo")
    );

    public static class HeaderFooterPageEvent extends PdfPageEventHelper {
        private final Font font;

        public HeaderFooterPageEvent() {
            this.font = FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(148, 163, 184));
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            float x = document.leftMargin();
            float width = document.getPageSize().getWidth() - document.leftMargin() - document.rightMargin();

            // Header
            PdfPTable header = new PdfPTable(2);
            try {
                header.setWidths(new float[]{1f, 1f});
                header.setTotalWidth(width);
                header.setLockedWidth(true);
                header.getDefaultCell().setBorder(Rectangle.NO_BORDER);
                header.getDefaultCell().setPadding(0);

                PdfPCell leftCell = new PdfPCell(new Phrase("Smart UI Analysis Report", font));
                leftCell.setBorder(Rectangle.NO_BORDER);
                leftCell.setHorizontalAlignment(Element.ALIGN_LEFT);
                leftCell.setPadding(0);
                header.addCell(leftCell);

                PdfPCell rightCell = new PdfPCell(new Phrase("CONFIDENTIAL", font));
                rightCell.setBorder(Rectangle.NO_BORDER);
                rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                rightCell.setPadding(0);
                header.addCell(rightCell);

                float y = document.getPageSize().getHeight() - document.topMargin() + 15;
                header.writeSelectedRows(0, -1, x, y, writer.getDirectContent());

                // Subtle gray separator line
                PdfContentByte cb = writer.getDirectContent();
                cb.setColorStroke(new Color(226, 232, 240));
                cb.setLineWidth(0.5f);
                cb.moveTo(x, y - 5);
                cb.lineTo(document.getPageSize().getWidth() - document.rightMargin(), y - 5);
                cb.stroke();

            } catch (Exception e) {
                System.err.println("Error rendering PDF header page event: " + e.getMessage());
            }

            // Footer
            PdfPTable footer = new PdfPTable(2);
            try {
                footer.setWidths(new float[]{1f, 1f});
                footer.setTotalWidth(width);
                footer.setLockedWidth(true);
                footer.getDefaultCell().setBorder(Rectangle.NO_BORDER);
                footer.getDefaultCell().setPadding(0);

                String generatedTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                PdfPCell leftCell = new PdfPCell(new Phrase("Generated on " + generatedTime, font));
                leftCell.setBorder(Rectangle.NO_BORDER);
                leftCell.setHorizontalAlignment(Element.ALIGN_LEFT);
                leftCell.setPadding(0);
                footer.addCell(leftCell);

                PdfPCell rightCell = new PdfPCell(new Phrase("Page " + writer.getPageNumber(), font));
                rightCell.setBorder(Rectangle.NO_BORDER);
                rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                rightCell.setPadding(0);
                footer.addCell(rightCell);

                float y = document.bottomMargin() - 15;

                // Subtle gray separator line
                PdfContentByte cb = writer.getDirectContent();
                cb.setColorStroke(new Color(226, 232, 240));
                cb.setLineWidth(0.5f);
                cb.moveTo(x, y + 10);
                cb.lineTo(document.getPageSize().getWidth() - document.rightMargin(), y + 10);
                cb.stroke();

                footer.writeSelectedRows(0, -1, x, y, writer.getDirectContent());

            } catch (Exception e) {
                System.err.println("Error rendering PDF footer page event: " + e.getMessage());
            }
        }
    }

    public byte[] generateReport(Project project, List<Detection> detections) {
        Document document = new Document(PageSize.A4, 40, 40, 50, 40);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new HeaderFooterPageEvent());
            document.open();

            // Font configurations
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, new Color(15, 23, 42)); // Slate-900
            Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, new Color(30, 41, 59)); // Slate-800
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, new Color(71, 85, 105)); // Slate-600
            Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 10, new Color(15, 23, 42)); // Slate-900
            Font tableHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE);
            Font tableCellFont = FontFactory.getFont(FontFactory.HELVETICA, 10, new Color(15, 23, 42));

            // Title
            Paragraph title = new Paragraph("Smart UI Analysis Report", titleFont);
            title.setSpacingAfter(15);
            document.add(title);

            // Divider Line
            LineSeparator titleSeparator = new LineSeparator(1.5f, 100, new Color(37, 99, 235), Element.ALIGN_LEFT, -10);
            document.add(new Chunk(titleSeparator));

            Paragraph spacing = new Paragraph(" ");
            spacing.setSpacingAfter(5);
            document.add(spacing);

            // Grid layout table for metadata (4 columns)
            PdfPTable metaTable = new PdfPTable(4);
            metaTable.setWidthPercentage(100);
            metaTable.setWidths(new float[]{1.8f, 3.2f, 1.8f, 3.2f});
            metaTable.setSpacingAfter(20);

            double totalConfidence = 0;
            int ocrCount = 0;
            for (Detection det : detections) {
                if (det.getConfidence() != null && det.getConfidence() > 0) {
                    totalConfidence += det.getConfidence();
                    ocrCount++;
                }
            }
            double ocrAccuracy = ocrCount > 0 ? (totalConfidence / ocrCount) : 0.0;

            Map<String, List<Detection>> grouped = detections.stream()
                    .collect(Collectors.groupingBy(Detection::getAttribute));
            int totalAttributes = grouped.size();

            // Fetch active project files count
            List<ProjectFile> activeFiles = projectFileRepository.findByProjectIdAndIsDeleted(project.getId(), false);

            addMetaCell(metaTable, "Project Name:", project.getName() != null ? project.getName() : "Unknown", labelFont, valueFont);
            addMetaCell(metaTable, "Project ID:", project.getCustomProjectId() != null ? project.getCustomProjectId() : project.getId(), labelFont, valueFont);
            addMetaCell(metaTable, "Uploaded File Name:", activeFiles.size() > 0 ? activeFiles.get(0).getOriginalFileName() : "N/A", labelFont, valueFont);
            addMetaCell(metaTable, "File Type:", activeFiles.size() > 0 ? activeFiles.get(0).getFileType() : "N/A", labelFont, valueFont);
            addMetaCell(metaTable, "Upload Date:", project.getUploadDate() != null ? project.getUploadDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : "Unknown", labelFont, valueFont);
            addMetaCell(metaTable, "Generated Date:", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), labelFont, valueFont);
            addMetaCell(metaTable, "Manager:", project.getManagerEmail() != null ? project.getManagerEmail() : "manager@app.com", labelFont, valueFont);
            addMetaCell(metaTable, "Total Attributes:", String.valueOf(totalAttributes), labelFont, valueFont);
            addMetaCell(metaTable, "Total Selected Elements:", String.valueOf(detections.size()), labelFont, valueFont);
            addMetaCell(metaTable, "OCR Accuracy:", String.format("%.1f%%", ocrAccuracy), labelFont, valueFont);

            document.add(metaTable);

            // Attribute Summary Section
            Paragraph summaryTitle = new Paragraph("Attribute Summary", sectionFont);
            summaryTitle.setSpacingAfter(10);
            document.add(summaryTitle);

            PdfPTable summaryTable = new PdfPTable(3);
            summaryTable.setWidthPercentage(100);
            summaryTable.setWidths(new float[]{40f, 30f, 30f});
            summaryTable.setSpacingAfter(20);

            addHeaderCell(summaryTable, "Attribute", tableHeaderFont);
            addHeaderCell(summaryTable, "Color", tableHeaderFont);
            addHeaderCell(summaryTable, "Elements", tableHeaderFont);

            for (Map.Entry<String, List<Detection>> entry : grouped.entrySet()) {
                String attrName = entry.getKey();
                List<Detection> attrDetections = entry.getValue();
                String colorHex = attrDetections.get(0).getColor();

                addTableCell(summaryTable, attrName, tableCellFont);
                addTableCell(summaryTable, getColorName(colorHex), tableCellFont);
                addTableCell(summaryTable, String.valueOf(attrDetections.size()), tableCellFont);
            }
            document.add(summaryTable);

            // Mapping each detection to its number in its attribute group for annotation labeling
            Map<String, Integer> detectionToNumberMap = new java.util.HashMap<>();
            for (Map.Entry<String, List<Detection>> entry : grouped.entrySet()) {
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

        float scale;
        if ("PDF".equalsIgnoreCase(uploadType)) {
            scale = 0.72f;
        } else {
            scale = Math.min(1.0f, 800f / originalImage.getWidth());
        }

        Detection.BoundingBox scaledBox = new Detection.BoundingBox();
        scaledBox.setX(Math.round(box.getX() / scale));
        scaledBox.setY(Math.round(box.getY() / scale));
        scaledBox.setWidth(Math.round(box.getWidth() / scale));
        scaledBox.setHeight(Math.round(box.getHeight() / scale));
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
        if (baseImage == null || det.getBoundingBox() == null) {
            return null;
        }

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
            detailsGrid.setWidths(new float[]{3.5f, 6.5f});
        } catch (Exception e) {}

        Font keyFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, new Color(71, 85, 105)); // Slate-600
        Font valFont = FontFactory.getFont(FontFactory.HELVETICA, 10, new Color(15, 23, 42)); // Slate-900

        String ocrText = det.getDetectedText();
        double confidence = det.getConfidence() != null ? det.getConfidence() : 0.0;
        if (ocrText == null || ocrText.trim().isEmpty()) {
            ocrText = "Unable to detect readable text.";
            confidence = 0.0;
        }

        addGridRow(detailsGrid, "Attribute", det.getAttribute(), keyFont, valFont);
        addGridRow(detailsGrid, "Color", getColorName(det.getColor()), keyFont, valFont);
        addGridRow(detailsGrid, "OCR Confidence", String.format("%.1f%%", confidence), keyFont, valFont);

        detailsCell.addElement(detailsGrid);

        Paragraph textHeader = new Paragraph("Detected Text", keyFont);
        textHeader.setSpacingBefore(10);
        textHeader.setSpacingAfter(4);
        detailsCell.addElement(textHeader);

        Paragraph textVal = new Paragraph(ocrText, valFont);
        textVal.setSpacingAfter(5);
        detailsCell.addElement(textVal);

        bodyTable.addCell(detailsCell);
        cardCell.addElement(bodyTable);
        cardTable.addCell(cardCell);

        return cardTable;
    }
}
