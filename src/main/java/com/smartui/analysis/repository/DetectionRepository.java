package com.smartui.analysis.repository;

import com.smartui.analysis.model.Detection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DetectionRepository extends JpaRepository<Detection, String> {
    List<Detection> findByProjectId(String projectId);
    void deleteByProjectId(String projectId);
    List<Detection> findByFileId(String fileId);
    void deleteByFileId(String fileId);
}
