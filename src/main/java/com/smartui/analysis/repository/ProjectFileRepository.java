package com.smartui.analysis.repository;

import com.smartui.analysis.model.ProjectFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectFileRepository extends JpaRepository<ProjectFile, String> {
    List<ProjectFile> findByProjectId(String projectId);
    List<ProjectFile> findByProjectIdAndIsDeleted(String projectId, boolean isDeleted);
    Optional<ProjectFile> findByProjectIdAndChecksumAndIsDeleted(String projectId, String checksum, boolean isDeleted);
}
