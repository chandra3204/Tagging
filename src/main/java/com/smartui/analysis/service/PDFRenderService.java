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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class PDFRenderService {

    private final ExecutorService renderExecutor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

    /**
     * Gets the page count of a PDF file without rendering pages.
     */
    public int getPdfPageCount(File pdfFile) throws IOException {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            return document.getNumberOfPages();
        }
    }

    /**
     * Lazily renders a single page of a PDF file on demand.
     * @param pageNumber 1-based page index.
     */
    public String renderSinglePage(File pdfFile, File uploadDir, String fileUuid, int pageNumber, String projectFolderRelativePath) throws IOException {
        String pageFileName = fileUuid + "_page_" + pageNumber + ".png";
        File outputFile = new File(uploadDir, pageFileName);

        if (outputFile.exists() && outputFile.length() > 0) {
            return projectFolderRelativePath + "/" + pageFileName;
        }

        synchronized (this) {
            if (outputFile.exists() && outputFile.length() > 0) {
                return projectFolderRelativePath + "/" + pageFileName;
            }

            try (PDDocument document = PDDocument.load(pdfFile)) {
                int pageIndex = pageNumber - 1;
                if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
                    throw new IllegalArgumentException("Page number out of bounds: " + pageNumber);
                }
                PDFRenderer pdfRenderer = new PDFRenderer(document);
                BufferedImage bim = pdfRenderer.renderImageWithDPI(pageIndex, 180);
                ImageIO.write(bim, "PNG", outputFile);
            }
        }

        return projectFolderRelativePath + "/" + pageFileName;
    }

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
            int pageCount = document.getNumberOfPages();
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < pageCount; i++) {
                final int pageNum = i + 1;
                futures.add(renderExecutor.submit(() -> renderSinglePage(pdfFile, uploadDir, fileUuid, pageNum, projectFolderRelativePath)));
            }

            for (int i = 0; i < futures.size(); i++) {
                imagePaths.add(futures.get(i).get());
            }
        } catch (Exception e) {
            throw new IOException("Failed to render PDF to images", e);
        }

        return imagePaths;
    }

    public CompletableFuture<List<String>> renderPdfToImagesAsync(File pdfFile, File uploadDir, String fileUuid, String projectFolderRelativePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return renderPdfToImages(pdfFile, uploadDir, fileUuid, projectFolderRelativePath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, renderExecutor);
    }
}
