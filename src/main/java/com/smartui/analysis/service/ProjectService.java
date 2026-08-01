package com.smartui.analysis.service;

import com.lowagie.text.pdf.PdfReader;
import com.smartui.analysis.model.Project;
import com.smartui.analysis.model.ProjectFile;
import com.smartui.analysis.repository.ProjectRepository;
import com.smartui.analysis.repository.ProjectFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectFileRepository projectFileRepository;
    private final PDFRenderService pdfRenderService;
    private final AuditLogService auditLogService;

    public ProjectService(ProjectRepository projectRepository,
                          ProjectFileRepository projectFileRepository,
                          PDFRenderService pdfRenderService,
                          AuditLogService auditLogService) {
        this.projectRepository = projectRepository;
        this.projectFileRepository = projectFileRepository;
        this.pdfRenderService = pdfRenderService;
        this.auditLogService = auditLogService;
    }

    public Project createProject(String name, String managerEmail) throws IOException {
        String baseFolderName = name.replaceAll("[^a-zA-Z0-9-_]", "_");
        File uploadsDir = new File("uploads");
        if (!uploadsDir.exists()) {
            uploadsDir.mkdirs();
        }

        File folder = new File(uploadsDir, baseFolderName);
        int index = 1;
        while (folder.exists()) {
            folder = new File(uploadsDir, baseFolderName + "_" + index);
            index++;
        }
        folder.mkdirs();

        // Create OCR and Reports subdirectories
        new File(folder, "OCR").mkdirs();
        new File(folder, "Reports").mkdirs();
        new File(folder, "thumbnails").mkdirs();

        String projectUuid = UUID.randomUUID().toString();
        Project project = new Project();
        project.setId(projectUuid);
        project.setName(name);
        project.setUploadDate(LocalDateTime.now());
        project.setFolderPath("uploads/" + folder.getName());
        project.setManagerEmail(managerEmail != null && !managerEmail.trim().isEmpty() ? managerEmail : "manager@app.com");
        project.setStatus("ACTIVE");

        // Custom Project ID generation format: PRJ-YYYYMMDD-NNN
        String dateStr = DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDateTime.now());
        long countToday = projectRepository.findAll().stream()
                .filter(p -> p.getUploadDate() != null && p.getUploadDate().toLocalDate().isEqual(LocalDateTime.now().toLocalDate()))
                .count();
        String customProjectId = String.format("PRJ-%s-%03d", dateStr, countToday + 1);
        project.setCustomProjectId(customProjectId);

        Project savedProject = projectRepository.save(project);
        updateProjectMetadata(savedProject);
        auditLogService.log(projectUuid, managerEmail, "Created project " + name);

        return savedProject;
    }

    public ProjectFile addFileToProject(String projectId, MultipartFile file, String managerEmail) throws IOException {
        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) {
            throw new NoSuchElementException("Project not found");
        }
        Project project = projectOpt.get();

        byte[] bytes = file.getBytes();
        String checksum = calculateChecksum(bytes);

        // Check for duplicates inside the project
        Optional<ProjectFile> duplicate = projectFileRepository.findByProjectIdAndChecksumAndIsDeleted(projectId, checksum, false);
        if (duplicate.isPresent()) {
            throw new IllegalArgumentException("File already exists in this project: " + file.getOriginalFilename());
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String extension = "";
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = originalFilename.substring(dotIndex + 1).toLowerCase();
        }

        String fileUuid = UUID.randomUUID().toString();
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now());
        String storedFilename = timestamp + "_" + originalFilename.replaceAll("[^a-zA-Z0-9-_\\.]", "_");

        File projectDir = new File(project.getFolderPath());
        File destinationFile = new File(projectDir, storedFilename);

        Files.copy(file.getInputStream(), destinationFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        ProjectFile projectFile = new ProjectFile();
        projectFile.setId(fileUuid);
        projectFile.setProjectId(projectId);
        projectFile.setOriginalFileName(originalFilename);
        projectFile.setStoredFileName(storedFilename);
        projectFile.setFileType(extension.toUpperCase());
        projectFile.setFileSize(file.getSize());
        projectFile.setUploadDate(LocalDateTime.now());
        projectFile.setFilePath(project.getFolderPath() + "/" + storedFilename);
        projectFile.setChecksum(checksum);
        projectFile.setDeleted(false);
        projectFile.setOcrStatus("PENDING");

        int pageCount = 1;
        if (extension.equalsIgnoreCase("pdf")) {
            try {
                pageCount = pdfRenderService.getPdfPageCount(destinationFile);
                projectFile.setPageCount(pageCount);
                projectFile.setOcrStatus("COMPLETED");
            } catch (Exception e) {
                System.err.println("Error reading PDF page count: " + e.getMessage());
                projectFile.setOcrStatus("FAILED");
            }
        } else {
            // Generate thumbnail for image files
            List<String> imageExts = Arrays.asList("png", "jpg", "jpeg", "gif", "bmp", "tiff", "webp");
            if (imageExts.contains(extension)) {
                generateThumbnail(destinationFile, new File(new File(projectDir, "thumbnails"), storedFilename));
            }
            projectFile.setOcrStatus("COMPLETED");
        }
        projectFile.setPageCount(pageCount);

        ProjectFile savedFile = projectFileRepository.save(projectFile);
        updateProjectMetadata(project);
        auditLogService.log(projectId, managerEmail, "Uploaded file: " + originalFilename);

        return savedFile;
    }

    public void deleteFile(String fileId, String managerEmail) {
        Optional<ProjectFile> fileOpt = projectFileRepository.findById(fileId);
        if (fileOpt.isEmpty()) {
            throw new NoSuchElementException("File not found");
        }
        ProjectFile file = fileOpt.get();
        file.setDeleted(true);
        projectFileRepository.save(file);

        Optional<Project> projectOpt = projectRepository.findById(file.getProjectId());
        projectOpt.ifPresent(project -> {
            updateProjectMetadata(project);
            auditLogService.log(project.getId(), managerEmail, "Soft-deleted file: " + file.getOriginalFileName());
        });
    }

    public void restoreFile(String fileId, String managerEmail) {
        Optional<ProjectFile> fileOpt = projectFileRepository.findById(fileId);
        if (fileOpt.isEmpty()) {
            throw new NoSuchElementException("File not found");
        }
        ProjectFile file = fileOpt.get();
        file.setDeleted(false);
        projectFileRepository.save(file);

        Optional<Project> projectOpt = projectRepository.findById(file.getProjectId());
        projectOpt.ifPresent(project -> {
            updateProjectMetadata(project);
            auditLogService.log(project.getId(), managerEmail, "Restored file: " + file.getOriginalFileName());
        });
    }

    public void archiveProject(String projectId, String managerEmail) {
        Optional<Project> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) {
            throw new NoSuchElementException("Project not found");
        }
        Project project = projectOpt.get();
        project.setStatus("ARCHIVED");
        projectRepository.save(project);
        
        auditLogService.log(projectId, managerEmail, "Archived project: " + project.getName());
    }

    public List<ProjectFile> getProjectFiles(String projectId) {
        List<ProjectFile> files = projectFileRepository.findByProjectIdAndIsDeleted(projectId, false);
        for (ProjectFile pf : files) {
            if ("PDF".equalsIgnoreCase(pf.getFileType()) && pf.getPageCount() <= 1) {
                try {
                    File f = new File(pf.getFilePath());
                    if (f.exists()) {
                        int pc = pdfRenderService.getPdfPageCount(f);
                        if (pc > 1) {
                            pf.setPageCount(pc);
                            projectFileRepository.save(pf);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Failed to auto-correct PDF page count: " + e.getMessage());
                }
            }
        }
        return files;
    }

    public List<ProjectFile> getDeletedProjectFiles(String projectId) {
        return projectFileRepository.findByProjectIdAndIsDeleted(projectId, true);
    }

    public void updateProjectMetadata(Project project) {
        try {
            File folder = new File(project.getFolderPath());
            if (!folder.exists()) return;

            List<ProjectFile> files = projectFileRepository.findByProjectId(project.getId());
            long total = files.size();
            long deleted = files.stream().filter(ProjectFile::isDeleted).count();
            long active = total - deleted;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("projectId", project.getCustomProjectId() != null ? project.getCustomProjectId() : project.getId());
            metadata.put("projectName", project.getName());
            metadata.put("manager", project.getManagerEmail());
            metadata.put("createdDate", project.getUploadDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            metadata.put("totalFiles", total);
            metadata.put("deletedFiles", deleted);
            metadata.put("activeFiles", active);

            File metaFile = new File(folder, "metadata.json");
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(metaFile, metadata);
        } catch (Exception e) {
            System.err.println("Failed to update project metadata.json: " + e.getMessage());
        }
    }

    private String calculateChecksum(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate SHA-256 checksum", e);
        }
    }

    private void generateThumbnail(File sourceFile, File targetFile) {
        try {
            BufferedImage original = ImageIO.read(sourceFile);
            if (original == null) return;

            int targetWidth = 150;
            int targetHeight = (int) (original.getHeight() * (150.0 / original.getWidth()));

            BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = thumbnail.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
            g.dispose();

            ImageIO.write(thumbnail, "png", targetFile);
        } catch (Exception e) {
            System.err.println("Failed to generate thumbnail: " + e.getMessage());
        }
    }
}
