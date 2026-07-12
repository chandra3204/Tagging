package com.smartui.analysis.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class PDFRenderService {

    /**
     * Converts a PDF file into individual page images (PNG format) and saves them in the project uploads folder.
     * @param pdfFile The PDF file to be rendered.
     * @param uploadDir The project uploads directory to save the output images.
     * @param fileUuid The unique identifier of the file.
     * @param projectFolderRelativePath The relative path of the project folder.
     * @return A list of relative paths to the generated page images.
     * @throws IOException If rendering fails.
     */
    public List<String> renderPdfToImages(File pdfFile, File uploadDir, String fileUuid, String projectFolderRelativePath) throws IOException {
        List<String> imagePaths = new ArrayList<>();
        
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            
            for (int i = 0; i < pageCount; i++) {
                // Render page as high-resolution image (300 DPI for sharp screenshots and better OCR)
                BufferedImage bim = pdfRenderer.renderImageWithDPI(i, 300);
                String pageFileName = fileUuid + "_page_" + (i + 1) + ".png";
                File outputFile = new File(uploadDir, pageFileName);
                
                ImageIO.write(bim, "PNG", outputFile);
                imagePaths.add(projectFolderRelativePath + "/" + pageFileName);
            }
        }
        
        return imagePaths;
    }
}
