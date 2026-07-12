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

        // Determine which image to load for OCR
        String imagePath;
        if ("PDF".equalsIgnoreCase(projectFile.getFileType())) {
            // For PDFs, use the specific page image inside project folder
            imagePath = project.getFolderPath() + "/" + projectFile.getId() + "_page_" + detection.getPageNumber() + ".png";
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

            // The frontend sends coordinates based on a scaled-down canvas (max-width: 800px or PDF scale 3.0).
            // We need to scale these coordinates back up to match the original high-resolution image.
            float scale;
            if ("PDF".equalsIgnoreCase(projectFile.getFileType())) {
                scale = 0.72f;
            } else {
                scale = Math.min(1.0f, 800f / sourceImage.getWidth());
            }

            Detection.BoundingBox originalScaleBox = new Detection.BoundingBox();
            originalScaleBox.setX((int) (detection.getBoundingBox().getX() / scale));
            originalScaleBox.setY((int) (detection.getBoundingBox().getY() / scale));
            originalScaleBox.setWidth((int) (detection.getBoundingBox().getWidth() / scale));
            originalScaleBox.setHeight((int) (detection.getBoundingBox().getHeight() / scale));

            OcrService.OcrResult ocrResult = ocrService.performOcrOnRegion(sourceImage, originalScaleBox, detection.getBase64Image());
            detection.setDetectedText(ocrResult.getDetectedText());
            detection.setConfidence(ocrResult.getConfidence());

            String detectionId = detection.getId() != null ? detection.getId() : UUID.randomUUID().toString();
            detection.setId(detectionId);

            // Save OCR JSON permanently to uploads/<ProjectFolder>/OCR/<StoredFileName>_page_<PageNum>_<DetectionID>.json
            try {
                File ocrFolder = new File(new File(project.getFolderPath()), "OCR");
                if (!ocrFolder.exists()) {
                    ocrFolder.mkdirs();
                }

                String ocrFileName = projectFile.getStoredFileName() + "_page_" + detection.getPageNumber() + "_" + detectionId + ".json";
                File ocrFile = new File(ocrFolder, ocrFileName);

                Map<String, Object> ocrData = new HashMap<>();
                ocrData.put("file", projectFile.getOriginalFileName());
                ocrData.put("page", detection.getPageNumber());
                ocrData.put("ocrText", ocrResult.getDetectedText());
                ocrData.put("confidence", ocrResult.getConfidence());

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                mapper.writerWithDefaultPrettyPrinter().writeValue(ocrFile, ocrData);
            } catch (Exception ex) {
                System.err.println("Failed to save OCR result JSON: " + ex.getMessage());
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

    @PostMapping("/{id}/pages/{pageNumber}/image")
    public ResponseEntity<?> uploadPageImage(
            @PathVariable String id,
            @PathVariable int pageNumber,
            @RequestBody Map<String, String> payload) {
        // Skip saving canvas since we use high-resolution rendered PDF files on the backend
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
