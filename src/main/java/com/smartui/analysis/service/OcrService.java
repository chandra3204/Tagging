package com.smartui.analysis.service;

import com.smartui.analysis.model.Detection;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

@Service
public class OcrService {

    private final Tesseract tesseract;
    private final ImageProcessingService imageProcessingService;
    private boolean isInitialized = false;

    public OcrService(ImageProcessingService imageProcessingService) {
        this.imageProcessingService = imageProcessingService;
        this.tesseract = new Tesseract();
        
        // Configuration settings matching specifications
        this.tesseract.setLanguage("eng");
        this.tesseract.setPageSegMode(6);
        this.tesseract.setOcrEngineMode(3);

        // Find datapath
        File tessDataFolder = new File("tessdata");
        if (tessDataFolder.exists()) {
            this.tesseract.setDatapath(tessDataFolder.getAbsolutePath());
            this.isInitialized = true;
        } else {
            // Also check if environment variable TESSDATA_PREFIX is set
            String envPrefix = System.getenv("TESSDATA_PREFIX");
            if (envPrefix != null) {
                this.tesseract.setDatapath(envPrefix);
                this.isInitialized = true;
            } else {
                // Set default to project root / tessdata
                this.tesseract.setDatapath(tessDataFolder.getAbsolutePath());
                System.err.println("WARNING: 'tessdata' folder not found in project root. Place Tesseract training files inside it for OCR.");
            }
        }
    }

    /**
     * Performs OCR on a specific bounding box region of a source image.
     * Tries PaddleOCR as primary, and falls back to Tess4J if not available.
     */
    /**
     * Performs OCR on a specific bounding box region of a source image.
     * Tries PaddleOCR as primary, and falls back to Tess4J if not available.
     */
    public OcrResult performOcrOnRegion(BufferedImage sourceImage, Detection.BoundingBox box) {
        return performOcrOnRegion(sourceImage, box, null);
    }

    /**
     * Performs OCR on a specific bounding box region, using Base64 cropped image directly if provided.
     * Falls back to cropping from the sourceImage if Base64 crop is not provided or fails.
     */
    public OcrResult performOcrOnRegion(BufferedImage sourceImage, Detection.BoundingBox box, String base64Image) {
        if (box == null) {
            return new OcrResult("", 0.0);
        }

        OcrResult result = null;

        // 1. Try OCR directly on the Base64 crop image if available
        // 1. Prioritize standard flow: crop from high-resolution sourceImage on the backend if available
        if (sourceImage != null) {
            // Normalize coordinates in case width/height are negative
            int x1 = box.getX();
            int y1 = box.getY();
            int w = box.getWidth();
            int h = box.getHeight();
            if (w < 0) {
                x1 = x1 + w;
                w = -w;
            }
            if (h < 0) {
                y1 = y1 + h;
                h = -h;
            }

            int x = Math.max(0, x1);
            int y = Math.max(0, y1);
            int width = Math.min(w, sourceImage.getWidth() - x);
            int height = Math.min(h, sourceImage.getHeight() - y);

            if (width > 0 && height > 0) {
                // Try PaddleOCR primary engine by saving original region
                File tempFolder = new File("uploads/temp");
                if (!tempFolder.exists()) {
                    tempFolder.mkdirs();
                }
                File tempFile = new File(tempFolder, "ocr_" + System.nanoTime() + ".png");
                try {
                    javax.imageio.ImageIO.write(sourceImage, "PNG", tempFile);
                    result = performPaddleOcr(tempFile.getAbsolutePath(), box);
                } catch (Exception e) {
                    System.err.println("Failed to execute PaddleOCR, falling back to Tess4J: " + e.getMessage());
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete();
                    }
                }

                // Fallback to Tess4J cropping if PaddleOCR failed or returned null
                if (result == null) {
                    System.out.println("PaddleOCR failed or not initialized. Falling back to Tess4J...");
                    try {
                        BufferedImage cropped = sourceImage.getSubimage(x, y, width, height);

                        // Run OCR on standard pipeline (Pipeline 1)
                        BufferedImage preprocessed1 = imageProcessingService.preprocessPipeline1(cropped);
                        result = runOcrOnImage(preprocessed1);

                        // If confidence is below 70%, automatically retry with Pipeline 2
                        if (result.getConfidence() < 70.0) {
                            System.out.println("Tess4J OCR Confidence low. Retrying with alternative pipeline...");
                            BufferedImage preprocessed2 = imageProcessingService.preprocessPipeline2(cropped);
                            OcrResult result2 = runOcrOnImage(preprocessed2);
                            
                            if (result2.getConfidence() > result.getConfidence()) {
                                result = result2;
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Error processing image in Tess4J fallback: " + e.getMessage());
                    }
                }
            }
        }

        // 2. Fallback flow: try OCR directly on the Base64 crop image if backend sourceImage was not available or failed
        if (result == null && base64Image != null && base64Image.contains(",")) {
            try {
                String base64Data = base64Image.substring(base64Image.indexOf(",") + 1);
                byte[] decodedBytes = java.util.Base64.getDecoder().decode(base64Data);
                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(decodedBytes);
                BufferedImage decodedImage = javax.imageio.ImageIO.read(bais);

                if (decodedImage != null) {
                    // Save crop to temp file for PaddleOCR
                    File tempFolder = new File("uploads/temp");
                    if (!tempFolder.exists()) {
                        tempFolder.mkdirs();
                    }
                    File tempFile = new File(tempFolder, "ocr_crop_" + System.nanoTime() + ".png");
                    try {
                        javax.imageio.ImageIO.write(decodedImage, "PNG", tempFile);
                        Detection.BoundingBox cropBox = new Detection.BoundingBox();
                        cropBox.setX(0);
                        cropBox.setY(0);
                        cropBox.setWidth(decodedImage.getWidth());
                        cropBox.setHeight(decodedImage.getHeight());
                        
                        result = performPaddleOcr(tempFile.getAbsolutePath(), cropBox);
                    } finally {
                        if (tempFile.exists()) {
                            tempFile.delete();
                        }
                    }

                    // Fallback to Tess4J on the decoded crop if PaddleOCR is not available
                    if (result == null) {
                        System.out.println("PaddleOCR failed on Base64 crop. Falling back to Tess4J...");
                        BufferedImage preprocessed1 = imageProcessingService.preprocessPipeline1(decodedImage);
                        result = runOcrOnImage(preprocessed1);

                        if (result.getConfidence() < 70.0) {
                            BufferedImage preprocessed2 = imageProcessingService.preprocessPipeline2(decodedImage);
                            OcrResult result2 = runOcrOnImage(preprocessed2);
                            if (result2.getConfidence() > result.getConfidence()) {
                                result = result2;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to perform OCR on Base64 image crop: " + e.getMessage());
            }
        }

        // Save intermediate preprocessing images to uploads/debug/ for troubleshooting
        try {
            File debugFolder = new File("uploads/debug");
            if (!debugFolder.exists()) {
                debugFolder.mkdirs();
            }
            
            // 1. original.png
            if (sourceImage != null) {
                javax.imageio.ImageIO.write(sourceImage, "PNG", new File(debugFolder, "original.png"));
            }
            
            // 2. cropped.png
            BufferedImage croppedImg = null;
            if (base64Image != null && base64Image.contains(",")) {
                String base64Data = base64Image.substring(base64Image.indexOf(",") + 1);
                byte[] decodedBytes = java.util.Base64.getDecoder().decode(base64Data);
                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(decodedBytes);
                croppedImg = javax.imageio.ImageIO.read(bais);
            }
            
            if (croppedImg == null && sourceImage != null) {
                int x1 = box.getX();
                int y1 = box.getY();
                int w = box.getWidth();
                int h = box.getHeight();
                if (w < 0) {
                    x1 = x1 + w;
                    w = -w;
                }
                if (h < 0) {
                    y1 = y1 + h;
                    h = -h;
                }
                int x = Math.max(0, x1);
                int y = Math.max(0, y1);
                int width = Math.min(w, sourceImage.getWidth() - x);
                int height = Math.min(h, sourceImage.getHeight() - y);
                if (width > 0 && height > 0) {
                    croppedImg = sourceImage.getSubimage(x, y, width, height);
                }
            }
            
            if (croppedImg != null) {
                javax.imageio.ImageIO.write(croppedImg, "PNG", new File(debugFolder, "cropped.png"));
                
                // 3. grayscale.png
                BufferedImage grayscaleImg = imageProcessingService.preprocessPipeline2(croppedImg);
                javax.imageio.ImageIO.write(grayscaleImg, "PNG", new File(debugFolder, "grayscale.png"));
                
                // 4. threshold.png
                BufferedImage thresholdImg = imageProcessingService.preprocessPipeline1(croppedImg);
                javax.imageio.ImageIO.write(thresholdImg, "PNG", new File(debugFolder, "threshold.png"));
                
                // 5. final_ocr_input.png
                // Save thresholded if Tess4J fallback was active, otherwise cropped image
                BufferedImage finalInput = (result != null && result.getConfidence() > 0.0) ? croppedImg : thresholdImg;
                javax.imageio.ImageIO.write(finalInput, "PNG", new File(debugFolder, "final_ocr_input.png"));
            }
        } catch (Exception e) {
            System.err.println("Failed to write OCR debug images: " + e.getMessage());
        }

        // If OCR failed entirely
        if (result == null) {
            return new OcrResult("Unable to detect readable text.", 0.0);
        }

        // Custom failure error messages based on confidence values
        String text = result.getDetectedText();
        double confidence = result.getConfidence();

        if (text.trim().isEmpty() || confidence < 35.0) {
            if (confidence < 10.0) {
                text = "No readable text found";
            } else {
                text = "Unable to detect readable text.";
            }
        }

        return new OcrResult(text, confidence);
    }

    private OcrResult performPaddleOcr(String imgPath, Detection.BoundingBox box) {
        try {
            String pythonCmd = "ocr_env/Scripts/python";
            String scriptPath = "tools/paddle_ocr_runner.py";

            // Coordinate arguments
            String x = String.valueOf(box.getX());
            String y = String.valueOf(box.getY());
            String w = String.valueOf(box.getWidth());
            String h = String.valueOf(box.getHeight());

            ProcessBuilder pb = new ProcessBuilder(
                pythonCmd, 
                scriptPath, 
                imgPath, 
                x, 
                y, 
                w, 
                h
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read output stream
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.err.println("PaddleOCR runner exited with code: " + exitCode + ". Output:\n" + output);
                return null;
            }

            String jsonStr = output.toString().trim();
            int startIdx = jsonStr.indexOf("{");
            int endIdx = jsonStr.lastIndexOf("}");
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                jsonStr = jsonStr.substring(startIdx, endIdx + 1);
            } else {
                System.err.println("PaddleOCR output did not contain valid JSON: " + jsonStr);
                return null;
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(jsonStr);

            if (node.has("error")) {
                System.err.println("PaddleOCR script returned error: " + node.get("error").asText());
                return null;
            }

            String text = node.get("detectedText").asText();
            double confidence = node.get("confidence").asDouble();

            return new OcrResult(text, confidence);
        } catch (Exception e) {
            System.err.println("Failed to run PaddleOCR python command: " + e.getMessage());
            return null;
        }
    }

    private OcrResult runOcrOnImage(BufferedImage img) {
        if (!isInitialized && !new File("tessdata").exists() && System.getenv("TESSDATA_PREFIX") == null) {
            return new OcrResult("Tesseract tessdata not configured.", 0.0);
        }
        try {
            // Run Tesseract OCR and preserve line breaks, spaces, paragraphs
            String text = tesseract.doOCR(img).trim();

            // Fetch word-level confidence to compute accurate average confidence
            List<net.sourceforge.tess4j.Word> words = tesseract.getWords(img, TessPageIteratorLevel.RIL_WORD);
            double totalConf = 0;
            int count = 0;
            for (net.sourceforge.tess4j.Word w : words) {
                totalConf += w.getConfidence();
                count++;
            }
            double avgConfidence = count > 0 ? (totalConf / count) : 0.0;
            return new OcrResult(text, avgConfidence);

        } catch (TesseractException e) {
            System.err.println("Tess4J OCR error: " + e.getMessage());
            return new OcrResult("", 0.0);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Tess4J UnsatisfiedLinkError: " + e.getMessage());
            return new OcrResult("Tesseract binaries missing.", 0.0);
        } catch (Exception e) {
            System.err.println("Error running OCR on image: " + e.getMessage());
            return new OcrResult("", 0.0);
        }
    }

    public static class OcrResult {
        private final String detectedText;
        private final double confidence;

        public OcrResult(String detectedText, double confidence) {
            this.detectedText = detectedText;
            this.confidence = confidence;
        }

        public String getDetectedText() {
            return detectedText;
        }

        public double getConfidence() {
            return confidence;
        }
    }
}
