package com.smartui.analysis.controller;

import com.smartui.analysis.model.Detection;
import com.smartui.analysis.model.Project;
import com.smartui.analysis.model.Report;
import com.smartui.analysis.repository.DetectionRepository;
import com.smartui.analysis.repository.ProjectRepository;
import com.smartui.analysis.repository.ReportRepository;
import com.smartui.analysis.service.PDFReportService;
import com.smartui.analysis.service.AuditLogService;
import com.smartui.analysis.service.FileLoggerService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/report")
public class ReportController {

    private final ProjectRepository projectRepository;
    private final DetectionRepository detectionRepository;
    private final ReportRepository reportRepository;
    private final PDFReportService pdfReportService;
    private final AuditLogService auditLogService;
    private final FileLoggerService fileLoggerService;

    public ReportController(ProjectRepository projectRepository,
                            DetectionRepository detectionRepository,
                            ReportRepository reportRepository,
                            PDFReportService pdfReportService,
                            AuditLogService auditLogService,
                            FileLoggerService fileLoggerService) {
        this.projectRepository = projectRepository;
        this.detectionRepository = detectionRepository;
        this.reportRepository = reportRepository;
        this.pdfReportService = pdfReportService;
        this.auditLogService = auditLogService;
        this.fileLoggerService = fileLoggerService;
    }

    @GetMapping
    public ResponseEntity<?> generateReport(@PathVariable String projectId) {
        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Project project = projectOpt.get();
        List<Detection> detections = detectionRepository.findByProjectId(projectId);

        byte[] pdfBytes = pdfReportService.generateReport(project, detections);

        if (pdfBytes == null || pdfBytes.length == 0) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to generate PDF report.");
        }

        // Format filename to be URL-safe and descriptive
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now());
        String safeProjectName = project.getName().replaceAll("[^a-zA-Z0-9-_\\.]", "_");
        String filename = "Project_" + safeProjectName + "_Report_" + timestamp + ".pdf";

        // Save permanently to project uploads Reports folder
        try {
            File projectFolder = new File(project.getFolderPath());
            File reportsFolder = new File(projectFolder, "Reports");
            if (!reportsFolder.exists()) {
                reportsFolder.mkdirs();
            }
            File reportFile = new File(reportsFolder, filename);
            Files.write(reportFile.toPath(), pdfBytes);

            // Save record to DB
            Report report = new Report();
            report.setId(UUID.randomUUID().toString());
            report.setProjectId(projectId);
            report.setReportName(filename);
            report.setReportPath(project.getFolderPath() + "/Reports/" + filename);
            report.setGeneratedDate(LocalDateTime.now());
            reportRepository.save(report);

            // Log Action in AuditLog
            auditLogService.log(projectId, project.getManagerEmail(), "Generated report: " + filename);
            fileLoggerService.logInfo("REPORT", project.getManagerEmail(), "Generated PDF report: " + filename, "ProjectID: " + projectId + ", Detections: " + detections.size());

        } catch (Exception ex) {
            System.err.println("Failed to permanently save report PDF: " + ex.getMessage());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

        return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
    }
}
