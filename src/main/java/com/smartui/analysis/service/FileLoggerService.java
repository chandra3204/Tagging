package com.smartui.analysis.service;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * FileLoggerService writes every application action to daily log files
 * stored in the Logger/ directory at the project root.
 *
 * Log files are named by date: Logger/app_2026-07-12.log
 * Each entry includes timestamp, level, user, action, and optional details.
 */
@Service
public class FileLoggerService {

    private static final String LOG_DIR = "Logger";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @PostConstruct
    public void init() {
        File dir = new File(LOG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        log("SYSTEM", null, "INFO", "Application started", null);
    }

    /**
     * Log an action to the daily log file.
     *
     * @param category  The category of the action (e.g., PROJECT, FILE, DETECTION, REPORT, AUTH)
     * @param user      The user/email performing the action (nullable)
     * @param level     Log level: INFO, WARN, ERROR
     * @param action    Description of the action
     * @param details   Optional additional details (nullable)
     */
    public synchronized void log(String category, String user, String level, String action, String details) {
        String date = LocalDate.now().format(DATE_FMT);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String logFileName = LOG_DIR + "/app_" + date + ".log";

        String userStr = (user != null && !user.trim().isEmpty()) ? user : "SYSTEM";
        String detailsStr = (details != null && !details.trim().isEmpty()) ? " | Details: " + details : "";

        String logEntry = String.format("[%s] [%-5s] [%-10s] [%-30s] %s%s",
                timestamp, level, category, userStr, action, detailsStr);

        try (PrintWriter writer = new PrintWriter(new FileWriter(logFileName, true))) {
            writer.println(logEntry);
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        }
    }

    // ---- Convenience methods ----

    public void logInfo(String category, String user, String action) {
        log(category, user, "INFO", action, null);
    }

    public void logInfo(String category, String user, String action, String details) {
        log(category, user, "INFO", action, details);
    }

    public void logWarn(String category, String user, String action, String details) {
        log(category, user, "WARN", action, details);
    }

    public void logError(String category, String user, String action, String details) {
        log(category, user, "ERROR", action, details);
    }
}
