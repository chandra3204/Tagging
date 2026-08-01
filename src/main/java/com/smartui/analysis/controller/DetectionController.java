package com.smartui.analysis.controller;

import com.smartui.analysis.model.Detection;
import com.smartui.analysis.repository.DetectionRepository;
import com.smartui.analysis.service.FileLoggerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class DetectionController {

    private final DetectionRepository detectionRepository;
    private final FileLoggerService fileLoggerService;

    public DetectionController(DetectionRepository detectionRepository, FileLoggerService fileLoggerService) {
        this.detectionRepository = detectionRepository;
        this.fileLoggerService = fileLoggerService;
    }

    @GetMapping("/projects/{projectId}/detections")
    public List<Detection> getDetectionsForProject(@PathVariable String projectId) {
        return detectionRepository.findByProjectId(projectId);
    }

    @GetMapping("/projects/{projectId}/files/{fileId}/detections")
    public List<Detection> getDetectionsForFile(@PathVariable String projectId, @PathVariable String fileId) {
        return detectionRepository.findByFileId(fileId);
    }

    @GetMapping("/projects/{projectId}/files/{fileId}/pages/{pageNumber}/detections")
    public List<Detection> getDetectionsForFilePage(@PathVariable String projectId,
                                                    @PathVariable String fileId,
                                                    @PathVariable int pageNumber) {
        return detectionRepository.findByFileIdAndPageNumber(fileId, pageNumber);
    }

    @PostMapping("/projects/{projectId}/detections")
    public ResponseEntity<?> createDetection(@PathVariable String projectId, @RequestBody Detection detection) {
        if (detection.getAttribute() == null || detection.getColor() == null ||
            detection.getElementType() == null || detection.getBoundingBox() == null) {
            return ResponseEntity.badRequest().body("Invalid detection payload. All attributes and boundingBox coordinates are required.");
        }

        // Auto-assign properties
        detection.setId(UUID.randomUUID().toString());
        detection.setProjectId(projectId);
        detection.setCreatedAt(LocalDateTime.now());

        Detection saved = detectionRepository.save(detection);
        fileLoggerService.logInfo("DETECTION", null, "Created detection", "ProjectID: " + projectId + ", DetectionID: " + saved.getId() + ", Attribute: " + detection.getAttribute());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/detections/{id}")
    public ResponseEntity<?> deleteDetection(@PathVariable String id) {
        Optional<Detection> detOpt = detectionRepository.findById(id);
        if (detOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        detectionRepository.deleteById(id);
        fileLoggerService.logInfo("DETECTION", null, "Deleted detection", "DetectionID: " + id);
        return ResponseEntity.ok().build();
    }
}
