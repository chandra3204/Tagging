package com.smartui.analysis.service;

import com.smartui.analysis.model.AuditLog;
import com.smartui.analysis.repository.AuditLogRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final FileLoggerService fileLoggerService;

    public AuditLogService(AuditLogRepository auditLogRepository, @Lazy FileLoggerService fileLoggerService) {
        this.auditLogRepository = auditLogRepository;
        this.fileLoggerService = fileLoggerService;
    }

    public void log(String projectId, String managerEmail, String action) {
        AuditLog auditLog = new AuditLog();
        auditLog.setProjectId(projectId);
        auditLog.setManagerEmail(managerEmail != null ? managerEmail : "manager@app.com");
        auditLog.setAction(action);
        auditLog.setTimestamp(LocalDateTime.now());
        auditLogRepository.save(auditLog);

        // Also write to file log
        fileLoggerService.logInfo("AUDIT", managerEmail, action, "ProjectID: " + projectId);
    }

    public List<AuditLog> getLogsForProject(String projectId) {
        return auditLogRepository.findByProjectIdOrderByTimestampDesc(projectId);
    }
}
