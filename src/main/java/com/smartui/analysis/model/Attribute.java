package com.smartui.analysis.model;

import jakarta.persistence.*;

@Entity
@Table(name = "attributes")
public class Attribute {

    @Id
    private String id; // UUID string

    @Column(nullable = false)
    private String projectId; // Associated project UUID

    @Column(nullable = false)
    private String name; // e.g. "Attribute A"

    @Column(nullable = false)
    private String color; // e.g. "Red", "Blue", etc.

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
