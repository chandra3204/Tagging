package com.smartui.analysis.controller;

import com.smartui.analysis.model.Detection;
import com.smartui.analysis.model.Project;
import com.smartui.analysis.model.ProjectFile;
import com.smartui.analysis.model.Report;
import com.smartui.analysis.model.AuditLog;
import com.smartui.analysis.repository.AttributeRepository;
import com.smartui.analysis.repository.DetectionRepository;
import com.smartui.analysis.repository.ProjectRepository;
import com.smartui.analysis.repository.ProjectFileRepository;
import com.smartui.analysis.repository.ReportRepository;
import com.smartui.analysis.service.PDFRenderService;
import com.smartui.analysis.service.OcrService;
import com.smartui.analysis.service.ProjectService;
import com.smartui.analysis.service.AuditLogService;
import com.smartui.analysis.service.FileLoggerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final ProjectFileRepository projectFileRepository;
    private final AttributeRepository attributeRepository;
    private final DetectionRepository detectionRepository;
    private final ReportRepository reportRepository;
    private final PDFRenderService pdfRenderService;
    private final OcrService ocrService;
    private final ProjectService projectService;
    private final AuditLogService auditLogService;
    private final FileLoggerService fileLoggerService;

    public ProjectController(ProjectRepository projectRepository,
                             ProjectFileRepository projectFileRepository,
                             AttributeRepository attributeRepository,
                             DetectionRepository detectionRepository,
                             ReportRepository reportRepository,
                             PDFRenderService pdfRenderService,
                             OcrService ocrService,
                             ProjectService projectService,
                             AuditLogService auditLogService,
                             FileLoggerService fileLoggerService) {
        this.projectRepository = projectRepository;
        this.projectFileRepository = projectFileRepository;
        this.attributeRepository = attributeRepository;
        this.detectionRepository = detectionRepository;
        this.reportRepository = reportRepository;
        this.pdfRenderService = pdfRenderService;
        this.ocrService = ocrService;
        this.projectService = projectService;
        this.auditLogService = auditLogService;
        this.fileLoggerService = fileLoggerService;
    }

    @GetMapping
    public List<Project> getAllProjects() {
        // Return only ACTIVE projects (exclude ARCHIVED)
        return projectRepository.findAll().stream()
                .filter(p -> "ACTIVE".equalsIgnoreCase(p.getStatus()))
                .sorted((a, b) -> b.getUploadDate().compareTo(a.getUploadDate()))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getProjectById(@PathVariable String id) {
        Optional<Project> projectOpt = projectRepository.findById(id);
        if (projectOpt.isPresent() && "ACTIVE".equalsIgnoreCase(projectOpt.get().getStatus())) {
            return ResponseEntity.ok(projectOpt.get());
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<?> createProject(@RequestParam("name") String name,
                                           @RequestParam(value = "file", required = false) MultipartFile[] files,
                                           @RequestParam(value = "managerEmail", required = false) String managerEmail) {
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Project name is required."));
        }

        try {
            Project project = projectService.createProject(name, managerEmail);
            fileLoggerService.logInfo("PROJECT", managerEmail, "Created project: " + name, "ProjectID: " + project.getId());
            if (files != null && files.length > 0) {
                for (MultipartFile file : files) {
                    if (!file.isEmpty()) {
                        projectService.addFileToProject(project.getId(), file, managerEmail);
                    }
                }
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(project);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create project: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/files")
    public ResponseEntity<?> uploadFiles(@PathVariable String id,
                                         @RequestParam("file") MultipartFile[] files,
                                         @RequestParam(value = "managerEmail", required = false) String managerEmail) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "No files selected."));
        }

        try {
            List<ProjectFile> uploadedFiles = new ArrayList<>();
            for (MultipartFile file : files) {
                if (!file.isEmpty()) {
                    ProjectFile pf = projectService.addFileToProject(id, file, managerEmail);
                    uploadedFiles.add(pf);
                }
            }
            return ResponseEntity.ok(uploadedFiles);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload files: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}/files")
    public ResponseEntity<?> getProjectFiles(@PathVariable String id,
                                             @RequestParam(value = "includeDeleted", defaultValue = "false") boolean includeDeleted) {
        if (includeDeleted) {
            return ResponseEntity.ok(projectFileRepository.findByProjectId(id));
        } else {
            return ResponseEntity.ok(projectService.getProjectFiles(id));
        }
    }

    @DeleteMapping("/{id}/files/{fileId}")
    public ResponseEntity<?> deleteFile(@PathVariable String id,
                                        @PathVariable String fileId,
                                        @RequestParam(value = "managerEmail", required = false) String managerEmail) {
        try {
            projectService.deleteFile(fileId, managerEmail);
            return ResponseEntity.ok(Map.of("success", true, "message", "File soft-deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete file: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/files/{fileId}/restore")
    public ResponseEntity<?> restoreFile(@PathVariable String id,
                                         @PathVariable String fileId,
                                         @RequestParam(value = "managerEmail", required = false) String managerEmail) {
        try {
            projectService.restoreFile(fileId, managerEmail);
            return ResponseEntity.ok(Map.of("success", true, "message", "File restored successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to restore file: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}/reports")
    public List<Report> getReports(@PathVariable String id) {
        return reportRepository.findByProjectId(id);
    }

    @GetMapping("/{id}/audit")
    public List<AuditLog> getAuditLogs(@PathVariable String id) {
        return auditLogService.getLogsForProject(id);
    }

    @PostMapping("/{id}/detections/ocr")
    public ResponseEntity<?> createDetectionWithOcr(@PathVariable String id, @RequestBody Detection detection) {
        Optional<Project> projectOpt = projectRepository.findById(id);
        if (projectOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Project project = projectOpt.get();
        detection.setProject(project);

        String fileId = detection.getFileId();
        if (fileId == null || fileId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fileId is required."));
        }

        Optional<ProjectFile> fileOpt = projectFileRepository.findById(fileId);
        if (fileOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "File record not found."));
        }
        ProjectFile projectFile = fileOpt.get();

        long startTime = System.currentTimeMillis();

        // Determine which image to load for OCR
        String imagePath;
        if ("PDF".equalsIgnoreCase(projectFile.getFileType())) {
            // For PDFs, render page lazily if missing
            File pdfFile = new File(projectFile.getFilePath());
            File projectDir = new File(project.getFolderPath());
            try {
                imagePath = pdfRenderService.renderSinglePage(pdfFile, projectDir, projectFile.getId(), detection.getPageNumber(), project.getFolderPath());
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to render PDF page: " + e.getMessage()));
            }
        } else {
            // For other types, use the main file path
            imagePath = projectFile.getFilePath();
        }

        File imageFile = new File(imagePath);
        if (!imageFile.exists()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Source image not found for OCR."));
        }

        try {
            BufferedImage sourceImage = ImageIO.read(imageFile);
            if (sourceImage != null) {
                detection.setImageResolution(sourceImage.getWidth() + "x" + sourceImage.getHeight());
            }

            // Frontend sends coordinates based on canvas dimensions. Scale back to original resolution.
            Detection.BoundingBox originalScaleBox = new Detection.BoundingBox();
            if (detection.getBoundingBox().getCanvasWidth() != null && detection.getBoundingBox().getCanvasWidth() > 0 &&
                detection.getBoundingBox().getCanvasHeight() != null && detection.getBoundingBox().getCanvasHeight() > 0) {
                double scaleX = (double) sourceImage.getWidth() / detection.getBoundingBox().getCanvasWidth();
                double scaleY = (double) sourceImage.getHeight() / detection.getBoundingBox().getCanvasHeight();
                originalScaleBox.setX((int) Math.round(detection.getBoundingBox().getX() * scaleX));
                originalScaleBox.setY((int) Math.round(detection.getBoundingBox().getY() * scaleY));
                originalScaleBox.setWidth((int) Math.round(detection.getBoundingBox().getWidth() * scaleX));
                originalScaleBox.setHeight((int) Math.round(detection.getBoundingBox().getHeight() * scaleY));
                originalScaleBox.setCanvasWidth(sourceImage.getWidth());
                originalScaleBox.setCanvasHeight(sourceImage.getHeight());
            } else {
                float scale = "PDF".equalsIgnoreCase(projectFile.getFileType()) ? 0.8f : Math.min(1.0f, 800f / sourceImage.getWidth());
                originalScaleBox.setX((int) (detection.getBoundingBox().getX() / scale));
                originalScaleBox.setY((int) (detection.getBoundingBox().getY() / scale));
                originalScaleBox.setWidth((int) (detection.getBoundingBox().getWidth() / scale));
                originalScaleBox.setHeight((int) (detection.getBoundingBox().getHeight() / scale));
                originalScaleBox.setCanvasWidth(sourceImage.getWidth());
                originalScaleBox.setCanvasHeight(sourceImage.getHeight());
            }

            OcrService.OcrResult ocrResult = ocrService.performOcrOnRegion(sourceImage, originalScaleBox, detection.getBase64Image());
            long elapsed = System.currentTimeMillis() - startTime;

            detection.setDetectedText(ocrResult.getDetectedText());
            detection.setConfidence(ocrResult.getConfidence());
            detection.setProcessingTimeMs(elapsed);

            if ("OCR Failed".equalsIgnoreCase(ocrResult.getDetectedText()) || ocrResult.getConfidence() <= 0.0) {
                detection.setOcrStatus("FAILED");
                detection.setOcrReason("Low confidence or no readable text detected.");
            } else {
                detection.setOcrStatus("SUCCESS");
                detection.setOcrReason(null);
            }

            String detectionId = detection.getId() != null ? detection.getId() : UUID.randomUUID().toString();
            detection.setId(detectionId);

            // Save crop image and preprocessed image permanently to uploads/<ProjectFolder>/OCR/
            String croppedFileName = "crop_" + detectionId + ".png";
            String preprocessedFileName = "processed_" + detectionId + ".png";

            try {
                File ocrFolder = new File(new File(project.getFolderPath()), "OCR");
                if (!ocrFolder.exists()) {
                    ocrFolder.mkdirs();
                }

                // 1. Crop image from sourceImage or Base64
                BufferedImage cropImg = null;
                int bx = Math.max(0, originalScaleBox.getX());
                int by = Math.max(0, originalScaleBox.getY());
                int bw = Math.min(originalScaleBox.getWidth(), sourceImage.getWidth() - bx);
                int bh = Math.min(originalScaleBox.getHeight(), sourceImage.getHeight() - by);
                if (bw > 0 && bh > 0) {
                    cropImg = sourceImage.getSubimage(bx, by, bw, bh);
                } else if (detection.getBase64Image() != null && detection.getBase64Image().contains(",")) {
                    String b64 = detection.getBase64Image().substring(detection.getBase64Image().indexOf(",") + 1);
                    byte[] bytes = java.util.Base64.getDecoder().decode(b64);
                    cropImg = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                }

                if (cropImg != null) {
                    File cropFile = new File(ocrFolder, croppedFileName);
                    ImageIO.write(cropImg, "PNG", cropFile);
                    detection.setCroppedImage(project.getFolderPath() + "/OCR/" + croppedFileName);

                    // 2. Preprocessed image
                    BufferedImage procImg = cropImg;
                    File procFile = new File(ocrFolder, preprocessedFileName);
                    ImageIO.write(procImg, "PNG", procFile);
                    detection.setPreprocessedImage(project.getFolderPath() + "/OCR/" + preprocessedFileName);
                }

                // 3. Save JSON structure
                String ocrJsonName = "detection_" + detectionId + ".json";
                File ocrJsonFile = new File(ocrFolder, ocrJsonName);

                Map<String, Object> ocrData = new LinkedHashMap<>();
                ocrData.put("id", detectionId);
                ocrData.put("page", detection.getPageNumber());
                ocrData.put("attribute", detection.getAttribute());
                ocrData.put("color", detection.getColor());

                Map<String, Object> boxData = new LinkedHashMap<>();
                boxData.put("x", detection.getBoundingBox().getX());
                boxData.put("y", detection.getBoundingBox().getY());
                boxData.put("width", detection.getBoundingBox().getWidth());
                boxData.put("height", detection.getBoundingBox().getHeight());
                if (detection.getBoundingBox().getCanvasWidth() != null) {
                    boxData.put("canvasWidth", detection.getBoundingBox().getCanvasWidth());
                }
                if (detection.getBoundingBox().getCanvasHeight() != null) {
                    boxData.put("canvasHeight", detection.getBoundingBox().getCanvasHeight());
                }
                ocrData.put("boundingBox", boxData);

                Map<String, Object> ocrObj = new LinkedHashMap<>();
                ocrObj.put("text", ocrResult.getDetectedText());
                ocrObj.put("confidence", ocrResult.getConfidence());
                ocrData.put("ocr", ocrObj);

                ocrData.put("croppedImage", croppedFileName);
                ocrData.put("preprocessedImage", preprocessedFileName);
                ocrData.put("processingTimeMs", elapsed);
                ocrData.put("imageResolution", detection.getImageResolution());
                ocrData.put("status", detection.getOcrStatus());
                ocrData.put("createdAt", LocalDateTime.now().toString());

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                mapper.writerWithDefaultPrettyPrinter().writeValue(ocrJsonFile, ocrData);

            } catch (Exception ex) {
                System.err.println("Failed to save crop images or OCR JSON: " + ex.getMessage());
            }

        } catch (IOException e) {
            System.err.println("Failed to perform OCR: " + e.getMessage());
        }

        if (detection.getId() == null) {
            detection.setId(UUID.randomUUID().toString());
        }
        detection.setCreatedAt(LocalDateTime.now());

        Detection savedDetection = detectionRepository.save(detection);
        fileLoggerService.logInfo("OCR", null, "OCR detection created", "ProjectID: " + id + ", FileID: " + detection.getFileId() + ", Page: " + detection.getPageNumber());
        return ResponseEntity.status(HttpStatus.CREATED).body(savedDetection);
    }

    @GetMapping("/{id}/files/{fileId}/pages/{pageNumber}/render")
    public ResponseEntity<?> renderAndGetPageImage(
            @PathVariable String id,
            @PathVariable String fileId,
            @PathVariable int pageNumber) {
        Optional<Project> projectOpt = projectRepository.findById(id);
        if (projectOpt.isEmpty()) return ResponseEntity.notFound().build();
        Project project = projectOpt.get();

        Optional<ProjectFile> fileOpt = projectFileRepository.findById(fileId);
        if (fileOpt.isEmpty()) return ResponseEntity.notFound().build();
        ProjectFile projectFile = fileOpt.get();

        try {
            File projectDir = new File(project.getFolderPath());
            String relPath;
            if ("PDF".equalsIgnoreCase(projectFile.getFileType())) {
                File pdfFile = new File(projectFile.getFilePath());
                relPath = pdfRenderService.renderSinglePage(pdfFile, projectDir, fileId, pageNumber, project.getFolderPath());
            } else {
                relPath = projectFile.getFilePath();
            }

            File imgFile = new File(relPath);
            if (!imgFile.exists()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to locate rendered page image.");
            }

            byte[] bytes = java.nio.file.Files.readAllBytes(imgFile.toPath());
            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.IMAGE_PNG)
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error rendering page image: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/pages/{pageNumber}/image")
    public ResponseEntity<?> uploadPageImage(
            @PathVariable String id,
            @PathVariable int pageNumber,
            @RequestBody Map<String, String> payload) {
        return ResponseEntity.ok(Map.of("success", true, "message", "Preserved high-resolution backend render"));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteProject(@PathVariable String id, @RequestParam(value = "managerEmail", required = false) String managerEmail) {
        Optional<Project> projectOpt = projectRepository.findById(id);
        if (projectOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Soft delete/Archive the project
        projectService.archiveProject(id, managerEmail);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Project soft-deleted/archived successfully. Backend files are preserved.");
        return ResponseEntity.ok(response);
    }
}
