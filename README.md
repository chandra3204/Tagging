# 🏷️ Smart UI Analysis & Attribute Tagging Platform

A full-stack **Spring Boot** web application for uploading UI screenshots, PDFs, and documents — then interactively tagging interface elements with custom attributes, performing OCR text extraction, and generating professional PDF reports.

---

## 📋 Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [Default Credentials](#default-credentials)
- [Usage Guide](#usage-guide)
- [API Reference](#api-reference)
- [OCR Engine Setup](#ocr-engine-setup)
- [Database](#database)
- [Audit Logging](#audit-logging)
- [File Storage](#file-storage)
- [Troubleshooting](#troubleshooting)
- [License](#license)

---

## Overview

Smart UI Analysis provides a lightweight yet powerful workspace for design review and UI auditing workflows. Teams can upload design files, collaboratively tag UI components with color-coded attributes, extract text via OCR, and generate comprehensive PDF reports — all through an intuitive browser-based interface.

---

## Key Features

| Feature | Description |
|---------|-------------|
| **Project Management** | Create, open, archive, and soft-delete projects with auto-generated IDs (`PRJ-YYYYMMDD-NNN`) |
| **Multi-Format Upload** | Support for PNG, JPG, JPEG, GIF, BMP, TIFF, WebP images and multi-page PDF documents |
| **Interactive Canvas** | Tag UI elements directly on an interactive canvas viewer with bounding box selection |
| **Custom Attributes** | Create reusable, color-coded attributes and assign them to tagged elements |
| **Dual OCR Engine** | Primary **PaddleOCR** engine with automatic **Tesseract (Tess4J)** fallback for text extraction |
| **PDF Report Generation** | Generate professional PDF reports with project metadata, detection summaries, and annotated screenshots |
| **File Management** | Soft-delete and restore files; duplicate detection via SHA-256 checksums |
| **Audit Trail** | Database-persisted audit logs and daily rotating file logs for every user action |
| **Thumbnail Generation** | Automatic thumbnail generation for uploaded images |
| **PDF Page Rendering** | Server-side PDF-to-image conversion for page-by-page analysis |
| **H2 / PostgreSQL Support** | Embedded H2 database for development; PostgreSQL-ready for production |
| **Built-in Auth** | Simple email/password authentication with a pre-seeded manager account |

---

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| **Runtime** | Java | 17+ |
| **Framework** | Spring Boot | 3.2.5 |
| **Web** | Spring Web MVC | — |
| **Persistence** | Spring Data JPA + Hibernate | — |
| **Validation** | Spring Boot Starter Validation | — |
| **Database (Dev)** | H2 Database | Runtime |
| **Database (Prod)** | PostgreSQL | Runtime |
| **PDF Parsing** | Apache PDFBox | 2.0.31 |
| **PDF Generation** | OpenPDF (LibrePDF) | 1.3.40 |
| **OCR (Primary)** | PaddleOCR via Python | — |
| **OCR (Fallback)** | Tess4J (Tesseract) | 5.11.0 |
| **Frontend** | Vanilla HTML / CSS / JavaScript | — |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Browser (Frontend)                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
│  │  index.html   │  │ dashboard.html│  │   analysis.html      │   │
│  │  (Login)      │  │ (Projects)   │  │   (Canvas/Tagging)   │   │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘   │
└─────────┼──────────────────┼────────────────────┼───────────────┘
          │                  │                    │
          ▼                  ▼                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                     REST API Layer (Controllers)                │
│  AuthController │ ProjectController │ DetectionController       │
│  AttributeController │ ReportController                         │
└──────────────────────────┬──────────────────────────────────────┘
                           │
┌──────────────────────────┴──────────────────────────────────────┐
│                       Service Layer                             │
│  ProjectService │ OcrService │ PDFReportService                 │
│  PDFRenderService │ ImageProcessingService                      │
│  AuditLogService │ FileLoggerService                            │
└──────────────────────────┬──────────────────────────────────────┘
                           │
┌──────────────────────────┴──────────────────────────────────────┐
│                    Data / Persistence Layer                      │
│  ┌────────────┐  ┌─────────────┐  ┌──────────────┐             │
│  │ H2 / Postgres│  │ File System │  │ OCR Engines  │             │
│  │ (JPA Repos)  │  │ (uploads/)  │  │ (Paddle/Tess)│             │
│  └────────────┘  └─────────────┘  └──────────────┘             │
└─────────────────────────────────────────────────────────────────┘
```

---

## Project Structure

```
smart-ui-analysis/
├── pom.xml                              # Maven build configuration
├── README.md                            # This file
├── Prompts                              # Prompt templates / notes
│
├── src/main/java/com/smartui/analysis/
│   ├── Application.java                 # Spring Boot entry point & DB seeder
│   │
│   ├── config/
│   │   └── WebConfig.java               # Static resource handler for /uploads/**
│   │
│   ├── controller/
│   │   ├── AuthController.java          # POST /api/auth/login
│   │   ├── ProjectController.java       # CRUD for projects, files, OCR detections
│   │   ├── DetectionController.java     # CRUD for detections (tags)
│   │   ├── AttributeController.java     # CRUD for custom attributes
│   │   └── ReportController.java        # PDF report generation & download
│   │
│   ├── model/
│   │   ├── User.java                    # User entity (email + password)
│   │   ├── Project.java                 # Project entity with metadata
│   │   ├── ProjectFile.java             # Uploaded file entity with checksums
│   │   ├── Detection.java               # Tagged element with bounding box
│   │   ├── Attribute.java               # Reusable attribute (name + color)
│   │   ├── AuditLog.java                # Audit trail entry
│   │   └── Report.java                  # Generated report record
│   │
│   ├── repository/
│   │   ├── UserRepository.java
│   │   ├── ProjectRepository.java
│   │   ├── ProjectFileRepository.java
│   │   ├── DetectionRepository.java
│   │   ├── AttributeRepository.java
│   │   ├── AuditLogRepository.java
│   │   └── ReportRepository.java
│   │
│   └── service/
│       ├── ProjectService.java          # Project lifecycle, file management
│       ├── OcrService.java              # Dual-engine OCR (PaddleOCR + Tess4J)
│       ├── ImageProcessingService.java  # Image preprocessing pipelines for OCR
│       ├── PDFRenderService.java        # PDF-to-image page rendering
│       ├── PDFReportService.java        # PDF report generation with OpenPDF
│       ├── AuditLogService.java         # Database audit log persistence
│       └── FileLoggerService.java       # Daily rotating file logger
│
├── src/main/resources/
│   ├── application.yml                  # Spring Boot configuration
│   └── static/
│       ├── index.html                   # Login page
│       ├── dashboard.html               # Project dashboard
│       ├── analysis.html                # Analysis workspace (canvas)
│       ├── css/styles.css               # Application styles
│       └── js/app.js                    # Frontend logic
│
├── tessdata/                            # Tesseract OCR training data
├── tools/
│   └── paddle_ocr_runner.py             # PaddleOCR Python bridge script
├── ocr_env/                             # Python virtual environment for PaddleOCR
├── uploads/                             # Uploaded project files (auto-created)
├── data/                                # H2 database files (auto-created)
└── Logger/                              # Daily rotating audit log files (auto-created)
```

---

## Prerequisites

| Requirement | Details |
|------------|---------|
| **JDK** | Java 17 or higher |
| **Maven** | 3.6+ (or use the Maven wrapper if included) |
| **Python** *(optional)* | 3.8+ with PaddleOCR for primary OCR engine |
| **Tesseract** *(optional)* | Training data in `tessdata/` for fallback OCR |

---

## Getting Started

### 1. Clone the Repository

```bash
git clone <repository-url>
cd smart-ui-analysis
```

### 2. Build the Project

```bash
mvn clean install
```

### 3. Run the Application

```bash
mvn spring-boot:run
```

The application starts on **http://localhost:8080** by default.

### 4. Access the Application

| Page | URL |
|------|-----|
| **Login** | http://localhost:8080/index.html |
| **Dashboard** | http://localhost:8080/dashboard.html |
| **Analysis Workspace** | http://localhost:8080/analysis.html?id=`<project-id>` |
| **H2 Console** | http://localhost:8080/h2-console |

---

## Configuration

All configuration is managed via `src/main/resources/application.yml`:

```yaml
server:
  port: 8080                              # Application port

spring:
  datasource:
    url: jdbc:h2:file:./data/smartui      # H2 file-based storage
    driver-class-name: org.h2.Driver
    username: sa
    password: password

  h2:
    console:
      enabled: true                       # Enable H2 web console
      path: /h2-console
      settings:
        web-allow-others: true            # Allow remote access to console

  jpa:
    hibernate:
      ddl-auto: update                    # Auto-create/update schema
    show-sql: true

  servlet:
    multipart:
      max-file-size: 30MB                 # Max single file size
      max-request-size: 30MB              # Max total request size
```

### PostgreSQL Configuration (Production)

To switch to PostgreSQL, update `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/smartui
    username: postgres
    password: yourpassword
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: update
```

---

## Default Credentials

A default manager account is automatically seeded on first startup:

| Field | Value |
|-------|-------|
| **Email** | `manager@app.com` |
| **Password** | `manager123` |

> ⚠️ **Note:** This application uses simple string-based password comparison for demonstration purposes. Do not use in production without implementing proper password hashing (e.g., BCrypt).

---

## Usage Guide

### 1. Login
Navigate to http://localhost:8080/index.html and sign in with the default credentials.

### 2. Create a Project
From the dashboard, click **"Create & Analyze"**, enter a project name, optionally attach files, and submit.

### 3. Upload Files
Add screenshots (PNG, JPG, etc.) or multi-page PDFs to your project. The system automatically:
- Generates thumbnails for images
- Renders PDF pages as individual images
- Computes SHA-256 checksums for duplicate detection

### 4. Tag UI Elements
In the analysis workspace:
- **Create Attributes**: Define reusable attributes with names and colors
- **Select an Attribute**: Click on an attribute to activate it
- **Draw Bounding Boxes**: Click and drag on the canvas to tag elements
- **OCR Extraction**: The system automatically extracts text from tagged regions

### 5. Generate Reports
Click the report button to generate a comprehensive PDF containing:
- Project metadata and summary statistics
- All detections with annotated screenshots
- OCR-extracted text and confidence scores

---

## API Reference

### Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/auth/login` | Authenticate with email & password |

**Request Body:**
```json
{ "email": "manager@app.com", "password": "manager123" }
```

---

### Projects

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/projects` | List all active projects |
| `GET` | `/api/projects/{id}` | Get project by ID |
| `POST` | `/api/projects` | Create a new project (multipart: `name`, `file[]`, `managerEmail`) |
| `DELETE` | `/api/projects/{id}` | Soft-delete (archive) a project |

---

### Files

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/projects/{id}/files` | List active files for a project |
| `GET` | `/api/projects/{id}/files?includeDeleted=true` | List all files including deleted |
| `POST` | `/api/projects/{id}/files` | Upload files (multipart: `file[]`, `managerEmail`) |
| `DELETE` | `/api/projects/{id}/files/{fileId}` | Soft-delete a file |
| `POST` | `/api/projects/{id}/files/{fileId}/restore` | Restore a soft-deleted file |

---

### Detections (Tags)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/projects/{projectId}/detections` | List all detections for a project |
| `GET` | `/api/projects/{projectId}/files/{fileId}/detections` | List detections for a specific file |
| `POST` | `/api/projects/{projectId}/detections` | Create a detection (tag) |
| `POST` | `/api/projects/{projectId}/detections/ocr` | Create a detection with OCR text extraction |
| `DELETE` | `/api/detections/{id}` | Delete a detection |

**Detection Request Body:**
```json
{
  "attribute": "Button",
  "color": "#EF4444",
  "elementType": "Interactive",
  "pageNumber": 1,
  "fileId": "file-uuid",
  "boundingBox": {
    "x": 100,
    "y": 200,
    "width": 150,
    "height": 40
  }
}
```

---

### Attributes

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/projects/{projectId}/attributes` | List attributes for a project |
| `POST` | `/api/projects/{projectId}/attributes` | Create a new attribute |
| `DELETE` | `/api/attributes/{id}` | Delete an attribute (cascades to detections) |

---

### Reports

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/projects/{projectId}/report` | Generate & download PDF report |
| `GET` | `/api/projects/{projectId}/reports` | List previously generated reports |

---

### Audit Logs

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/projects/{projectId}/audit` | List audit log entries for a project |

---

## OCR Engine Setup

The application uses a **dual-engine OCR strategy** for maximum accuracy:

### Primary: PaddleOCR (Recommended)

PaddleOCR typically provides higher accuracy, especially for complex layouts.

**Setup:**

```bash
# Create a Python virtual environment
python -m venv ocr_env

# Activate the environment
# Windows:
ocr_env\Scripts\activate
# Linux/macOS:
source ocr_env/bin/activate

# Install dependencies
pip install paddlepaddle paddleocr opencv-python
```

The Java backend calls `tools/paddle_ocr_runner.py` via a subprocess. The script:
1. Accepts an image path and bounding box coordinates
2. Crops the region with padding
3. Runs PaddleOCR inference
4. Returns JSON with `detectedText` and `confidence`

### Fallback: Tesseract (Tess4J)

If PaddleOCR is unavailable or fails, the system automatically falls back to Tesseract.

**Setup:**

1. Download Tesseract trained data files (e.g., `eng.traineddata`) from the [Tesseract GitHub releases](https://github.com/tesseract-ocr/tessdata)
2. Place the `.traineddata` files in the `tessdata/` directory at the project root
3. Alternatively, set the `TESSDATA_PREFIX` environment variable

### OCR Preprocessing Pipelines

The `ImageProcessingService` provides two preprocessing pipelines that are automatically tried when OCR confidence is low:

- **Pipeline 1**: Optimized for clean text (thresholding)
- **Pipeline 2**: Optimized for noisy backgrounds (grayscale + contrast enhancement)

If the first pipeline returns confidence below 70%, the system automatically retries with the alternative pipeline and picks the best result.

---

## Database

### H2 (Default — Development)

The embedded H2 database stores data in `./data/smartui.mv.db` and provides a web console for inspection.

| Setting | Value |
|---------|-------|
| **Console URL** | http://localhost:8080/h2-console |
| **JDBC URL** | `jdbc:h2:file:./data/smartui;AUTO_SERVER=TRUE` |
| **Username** | `sa` |
| **Password** | `password` |

### Entity Schema

| Table | Description |
|-------|-------------|
| `users` | Application users (email, password) |
| `projects` | Projects with metadata, folder paths, and status |
| `project_files` | Uploaded files with checksums, file paths, and soft-delete flags |
| `detections` | Tagged UI elements with bounding boxes, OCR text, and confidence |
| `attributes` | Reusable attributes with names and colors |
| `reports` | Generated report records with file paths |
| `audit_logs` | Audit trail entries (project ID, user, action, timestamp) |

---

## Audit Logging

The application maintains two levels of audit logging:

### 1. Database Audit Logs (`AuditLogService`)
- Stored in the `audit_logs` table
- Queryable via `GET /api/projects/{id}/audit`
- Tracks: project creation, file uploads/deletions, report generation

### 2. File-Based Logs (`FileLoggerService`)
- Daily rotating log files in the `Logger/` directory (auto-created)
- File naming: `Logger/app_YYYY-MM-DD.log`
- Structured format: `[timestamp] [LEVEL] [CATEGORY] [USER] action | Details: ...`
- Categories: `SYSTEM`, `AUTH`, `PROJECT`, `FILE`, `DETECTION`, `OCR`, `REPORT`, `ATTRIBUTE`, `AUDIT`

---

## File Storage

All uploaded files are stored on the local filesystem under the `uploads/` directory:

```
uploads/
├── Project_Name/
│   ├── metadata.json              # Auto-generated project metadata
│   ├── 20260712143000_file.png    # Stored files (timestamped)
│   ├── thumbnails/                # Auto-generated image thumbnails
│   ├── OCR/                       # OCR result JSON files
│   └── Reports/                   # Generated PDF reports
```

Each project folder contains:
- **metadata.json**: Auto-updated with project stats (total files, active, deleted)
- **OCR/**: JSON files with OCR results per detection
- **Reports/**: All generated PDF reports with timestamps
- **thumbnails/**: 150px-wide thumbnails for image files

---

## Troubleshooting

### Port 8080 Already in Use

```bash
# Find the process using port 8080
netstat -ano | findstr :8080

# Kill the process (replace <PID> with actual PID)
taskkill /PID <PID> /F
```

### Database Schema Mismatch

If you encounter column-related errors after modifying entities, delete the H2 database to force a clean recreation:

```bash
# Windows
del data\smartui.mv.db data\smartui.trace.db

# Linux/macOS
rm data/smartui.mv.db data/smartui.trace.db
```

> ⚠️ This deletes all existing data. For production, use proper database migrations (e.g., Flyway or Liquibase).

### OCR Not Working

1. **PaddleOCR**: Ensure the Python virtual environment is set up in `ocr_env/` and PaddleOCR is installed
2. **Tesseract**: Verify that `tessdata/eng.traineddata` exists in the project root
3. Check `uploads/debug/` for intermediate OCR preprocessing images

### Large File Uploads Failing

Increase the multipart limits in `application.yml`:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB
```

---

## License

This project is intended as a demonstration application for UI analysis workflows. It is not a production-grade authentication or authorization system.

---

*Built with ☕ Java 17 and Spring Boot 3.2.5*
