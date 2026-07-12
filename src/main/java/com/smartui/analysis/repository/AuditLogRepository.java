package com.smartui.analysis.repository;

import com.smartui.analysis.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByProjectIdOrderByTimestampDesc(String projectId);
}
