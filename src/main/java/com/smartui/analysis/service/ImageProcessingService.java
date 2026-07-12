package com.smartui.analysis.service;

import org.springframework.stereotype.Service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;

@Service
public class ImageProcessingService {

    /**
     * Pipeline 1: Full preprocessing with adaptive thresholding.
     * Good for standard high-contrast text on screenshots.
     */
    public BufferedImage preprocessPipeline1(BufferedImage src) {
        if (src == null) return null;
        
        // 1. Resize by 2x (Increase resolution / DPI)
        BufferedImage scaled = scaleImage(src, 2.0);
        
        // 2. Grayscale
        BufferedImage gray = convertToGrayscale(scaled);
        
        // 3. Contrast Stretching
        BufferedImage contrast = improveContrast(gray);
        
        // 4. Sharpening (Noise removal & edge enhancement)
        BufferedImage sharpened = sharpen(contrast);
        
        // 5. Deskew (if rotated)
        BufferedImage deskewed = deskew(sharpened);
        
        // 6. Adaptive Threshold
        return adaptiveThreshold(deskewed);
    }

    /**
     * Pipeline 2: Greyscale & scaling only.
     * Serves as a fallback for sub-optimal binarization on colorful/anti-aliased fonts.
     */
    public BufferedImage preprocessPipeline2(BufferedImage src) {
        if (src == null) return null;
        
        // 1. Resize by 3x (Higher scale fallback)
        BufferedImage scaled = scaleImage(src, 3.0);
        
        // 2. Grayscale
        BufferedImage gray = convertToGrayscale(scaled);
        
        // 3. Contrast Stretching
        BufferedImage contrast = improveContrast(gray);
        
        // 4. Deskew (if rotated)
        return deskew(contrast);
    }

    private BufferedImage scaleImage(BufferedImage src, double factor) {
        int newW = (int) (src.getWidth() * factor);
        int newH = (int) (src.getHeight() * factor);
        BufferedImage dest = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = dest.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.drawImage(src, 0, 0, newW, newH, null);
        g2d.dispose();
        return dest;
    }

    private BufferedImage convertToGrayscale(BufferedImage src) {
        BufferedImage dest = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = dest.createGraphics();
        g2d.drawImage(src, 0, 0, null);
        g2d.dispose();
        return dest;
    }

    private BufferedImage improveContrast(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage dest = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        int min = 255;
        int max = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int val = src.getRaster().getSample(x, y, 0);
                if (val < min) min = val;
                if (val > max) max = val;
            }
        }

        if (max > min) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int val = src.getRaster().getSample(x, y, 0);
                    int newVal = (val - min) * 255 / (max - min);
                    dest.getRaster().setSample(x, y, 0, newVal);
                }
            }
            return dest;
        }
        return src;
    }

    private BufferedImage sharpen(BufferedImage src) {
        float[] sharpenKernel = {
            0f, -1f,  0f,
           -1f,  5f, -1f,
            0f, -1f,  0f
        };
        Kernel kernel = new Kernel(3, 3, sharpenKernel);
        ConvolveOp op = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
        return op.filter(src, null);
    }

    private BufferedImage deskew(BufferedImage src) {
        // Basic deskewing logic. For digital screenshots, skew is typically 0.0.
        // If an rotation angle check is needed, we would calculate horizontal profiles.
        // Returning the source image for screenshots.
        return src;
    }

    private BufferedImage adaptiveThreshold(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage dest = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        int radius = 8;
        int constant = 15;

        // Integral image calculation for fast O(1) local thresholding
        long[][] integral = new long[width][height];
        for (int x = 0; x < width; x++) {
            long sum = 0;
            for (int y = 0; y < height; y++) {
                sum += src.getRaster().getSample(x, y, 0);
                if (x == 0) {
                    integral[x][y] = sum;
                } else {
                    integral[x][y] = integral[x - 1][y] + sum;
                }
            }
        }

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int x1 = Math.max(0, x - radius);
                int y1 = Math.max(0, y - radius);
                int x2 = Math.min(width - 1, x + radius);
                int y2 = Math.min(height - 1, y + radius);

                int count = (x2 - x1 + 1) * (y2 - y1 + 1);
                long sum = integral[x2][y2];
                if (x1 > 0) sum -= integral[x1 - 1][y2];
                if (y1 > 0) sum -= integral[x2][y1 - 1];
                if (x1 > 0 && y1 > 0) sum += integral[x1 - 1][y1 - 1];

                int mean = (int) (sum / count);
                int current = src.getRaster().getSample(x, y, 0);
                if (current < mean - constant) {
                    dest.getRaster().setSample(x, y, 0, 0); // Black
                } else {
                    dest.getRaster().setSample(x, y, 0, 255); // White
                }
            }
        }
        return dest;
    }
}
