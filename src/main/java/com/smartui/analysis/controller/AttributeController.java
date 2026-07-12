package com.smartui.analysis.controller;

import com.smartui.analysis.model.Attribute;
import com.smartui.analysis.model.Detection;
import com.smartui.analysis.repository.AttributeRepository;
import com.smartui.analysis.repository.DetectionRepository;
import com.smartui.analysis.service.FileLoggerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AttributeController {

    private final AttributeRepository attributeRepository;
    private final DetectionRepository detectionRepository;
    private final FileLoggerService fileLoggerService;

    public AttributeController(AttributeRepository attributeRepository, DetectionRepository detectionRepository, FileLoggerService fileLoggerService) {
        this.attributeRepository = attributeRepository;
        this.detectionRepository = detectionRepository;
        this.fileLoggerService = fileLoggerService;
    }

    @GetMapping("/projects/{projectId}/attributes")
    public List<Attribute> getAttributesForProject(@PathVariable String projectId) {
        return attributeRepository.findByProjectId(projectId);
    }

    @PostMapping("/projects/{projectId}/attributes")
    public ResponseEntity<?> createAttribute(@PathVariable String projectId, @RequestBody Attribute attribute) {
        if (attribute.getName() == null || attribute.getName().trim().isEmpty() ||
            attribute.getColor() == null || attribute.getColor().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Attribute name and color are required.");
        }

        attribute.setId(UUID.randomUUID().toString());
        attribute.setProjectId(projectId);
        
        Attribute saved = attributeRepository.save(attribute);
        fileLoggerService.logInfo("ATTRIBUTE", null, "Created attribute: " + attribute.getName(), "ProjectID: " + projectId + ", Color: " + attribute.getColor());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/attributes/{id}")
    @Transactional
    public ResponseEntity<?> deleteAttribute(@PathVariable String id) {
        Optional<Attribute> attrOpt = attributeRepository.findById(id);
        if (attrOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Attribute attribute = attrOpt.get();

        // Remove any detections associated with this attribute in this project
        List<Detection> detections = detectionRepository.findByProjectId(attribute.getProjectId());
        List<Detection> toDelete = detections.stream()
                .filter(d -> d.getAttribute().equalsIgnoreCase(attribute.getName()))
                .toList();
        
        detectionRepository.deleteAll(toDelete);
        attributeRepository.deleteById(id);
        fileLoggerService.logInfo("ATTRIBUTE", null, "Deleted attribute: " + attribute.getName(), "ProjectID: " + attribute.getProjectId() + ", Cascaded detections removed: " + toDelete.size());

        return ResponseEntity.ok().build();
    }
}
