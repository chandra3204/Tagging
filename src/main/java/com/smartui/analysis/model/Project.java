package com.smartui.analysis.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
public class Project {

    @Id
    private String id; // UUID string

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private LocalDateTime uploadDate;

    @Column(name = "custom_project_id")
    private String customProjectId;

    @Column(name = "manager_email")
    private String managerEmail;

    @Column(name = "folder_path")
    private String folderPath;

    @Column(nullable = false, columnDefinition = "VARCHAR(255) DEFAULT 'ACTIVE'")
    private String status = "ACTIVE"; // ACTIVE, ARCHIVED

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getUploadDate() {
        return uploadDate;
    }

    public void setUploadDate(LocalDateTime uploadDate) {
        this.uploadDate = uploadDate;
    }

    public String getCustomProjectId() {
        return customProjectId;
    }

    public void setCustomProjectId(String customProjectId) {
        this.customProjectId = customProjectId;
    }

    public String getManagerEmail() {
        return managerEmail;
    }

    public void setManagerEmail(String managerEmail) {
        this.managerEmail = managerEmail;
    }

    public String getFolderPath() {
        return folderPath;
    }

    public void setFolderPath(String folderPath) {
        this.folderPath = folderPath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
