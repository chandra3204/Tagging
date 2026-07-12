package com.smartui.analysis.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "detections")
public class Detection {

    @Id
    private String id; // UUID string

    @Column(nullable = false)
    private String projectId; // Associated project UUID

    @Column(name = "file_id")
    private String fileId; // Associated file UUID

    @Column(name = "attribute_name", nullable = false)
    private String attribute; // Attribute name (e.g. "A")

    @Column(nullable = false)
    private String color; // Hex/CSS color code (e.g. "#EF4444")

    @Column(nullable = false)
    private String elementType; // Element category (e.g. "Button")

    @Column(nullable = false)
    private int pageNumber;

    @Embedded
    private BoundingBox boundingBox;

    @Column(columnDefinition = "TEXT")
    private String detectedText;

    @Column
    private Double confidence;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Embeddable
    public static class BoundingBox {
        private int x;
        private int y;
        private int width;
        private int height;

        public BoundingBox() {}

        public BoundingBox(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public int getX() {
            return x;
        }

        public void setX(int x) {
            this.x = x;
        }

        public int getY() {
            return y;
        }

        public void setY(int y) {
            this.y = y;
        }

        public int getWidth() {
            return width;
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public int getHeight() {
            return height;
        }

        public void setHeight(int height) {
            this.height = height;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getAttribute() {
        return attribute;
    }

    public void setAttribute(String attribute) {
        this.attribute = attribute;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getElementType() {
        return elementType;
    }

    public void setElementType(String elementType) {
        this.elementType = elementType;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    public BoundingBox getBoundingBox() {
        return boundingBox;
    }

    public void setBoundingBox(BoundingBox boundingBox) {
        this.boundingBox = boundingBox;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getDetectedText() {
        return detectedText;
    }

    public void setDetectedText(String detectedText) {
        this.detectedText = detectedText;
    }

    public void setProject(Project project) {
        if (project != null) {
            this.projectId = project.getId();
        }
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    @jakarta.persistence.Transient
    private String base64Image;

    public String getBase64Image() {
        return base64Image;
    }

    public void setBase64Image(String base64Image) {
        this.base64Image = base64Image;
    }
}
