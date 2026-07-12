// Global Constants & State
const COLOR_MAP = {
  'Red': '#ef4444',
  'Blue': '#3b82f6',
  'Green': '#10b981',
  'Yellow': '#eab308',
  'Magenta': '#d946ef',
  'Cyan': '#06b6d4',
  'Orange': '#f97316',
  'Indigo': '#6366f1'
};

let currentProject = null;
let attributes = [];
let detections = [];
let projectFiles = [];
let currentFile = null;
let selectedAttribute = null;
let currentPage = 1;
let totalPages = 1;

let canvas, ctx;
let imageElement = null;

let isDrawing = false;
let selectionRect = {};
let startX, startY;

// This variable is no longer used for auto-detection and is only populated for the DOCX sandbox.
let detectedBoxes = [];
// Extract query parameter from URL
function getQueryParam(param) {
  const urlParams = new URLSearchParams(window.location.search);
  return urlParams.get(param);
}

// Security Session check on workspace pages
const managerEmailEl = document.getElementById('manager-email');
if (managerEmailEl) {
  const email = localStorage.getItem('manager_email') || 'manager@app.com';
  managerEmailEl.textContent = email;
}

// Toast Notification Helper
function showToast(message, type = 'success') {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;

  const icon = type === 'success'
    ? '<i class="fa-solid fa-circle-check"></i>'
    : '<i class="fa-solid fa-triangle-exclamation"></i>';

  toast.innerHTML = `${icon} <span>${message}</span>`;
  container.appendChild(toast);

  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(10px)';
    toast.style.transition = 'all 0.3s ease';
    setTimeout(() => toast.remove(), 300);
  }, 3000);
}

// Initialise Workspace if on analysis page
document.addEventListener('DOMContentLoaded', () => {
  const projectId = getQueryParam('id');
  if (projectId && document.getElementById('annotation-canvas')) {
    initWorkspace(projectId);
  }
});

// Setup Workspace Variables & Assets
async function initWorkspace(projectId) {
  canvas = document.getElementById('annotation-canvas');
  ctx = canvas.getContext('2d');

  try {
    // 1. Fetch project meta-details
    const projectResponse = await fetch(`/api/projects/${projectId}`);
    if (!projectResponse.ok) throw new Error('Project not found');
    currentProject = await projectResponse.json();

    document.getElementById('workspace-project-title').textContent = currentProject.name;

    // 2. Fetch project files
    await refreshProjectFiles();

    // 3. Fetch attributes
    await refreshAttributes();

    // 4. Setup Action Listeners
    setupCanvasListeners();
    setupForms();
    setupPDFReportButton();
    setupAddFileInput();

  } catch (err) {
    console.error(err);
    showToast('Failed to initialize project workspace', 'error');
  }
}

// Load Image/Document onto Canvas
async function loadWorkspaceImage() {
  detectedBoxes = []; // Clear any mock boxes
  let imageUrl = '';

  if (!currentFile) {
    drawEmptyWorkspace();
    return;
  }

  if (currentFile.fileType === 'PDF') {
    await loadPdfPageUsingPdfJs();
    return;
  } else if (currentFile.fileType === 'DOCX') {
    // DOCX files will render a beautiful UI sandbox mockup
    drawDocxSandbox();
    return;
  } else {
    // PNG, JPG, JPEG
    imageUrl = `/${currentFile.filePath}`;
  }

  imageElement = new Image();
  imageElement.src = imageUrl;

  imageElement.onload = () => {
    // Set canvas size to match layout viewport (scaled logic)
    const maxWidth = 800;
    const scale = Math.min(1.0, maxWidth / imageElement.width);
    canvas.width = imageElement.width * scale;
    canvas.height = imageElement.height * scale;
    canvas.style.width = '100%';
    canvas.style.height = 'auto';

    // Draw initial view
    redrawCanvas();
  };
}

async function loadPdfPageUsingPdfJs() {
  if (!currentFile) return;
  const pdfUrl = `/${currentFile.filePath}`;
  
  try {
    const helpText = document.getElementById('workspace-help-text');
    if (helpText) {
      helpText.innerHTML = `<i class="fa-solid fa-spinner fa-spin" style="margin-right: 6px;"></i> Rendering PDF page...`;
    }

    if (typeof pdfjsLib !== 'undefined') {
      pdfjsLib.GlobalWorkerOptions.workerSrc = 'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.4.120/pdf.worker.min.js';
    }
    const loadingTask = pdfjsLib.getDocument(pdfUrl);
    const pdfDoc = await loadingTask.promise;
    
    const page = await pdfDoc.getPage(currentPage);
    
    // Render at high scale 3.0 for premium quality (300 DPI equivalent)
    const scale = 3.0;
    const viewport = page.getViewport({ scale: scale });
    
    // Set canvas dimensions
    canvas.width = viewport.width;
    canvas.height = viewport.height;
    canvas.style.width = '100%';
    canvas.style.height = 'auto';
    canvas.style.backgroundColor = '#FFFFFF';
    
    ctx.fillStyle = '#FFFFFF';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    
    const renderContext = {
      canvasContext: ctx,
      viewport: viewport
    };
    
    await page.render(renderContext).promise;
    
    // Cache page visual to imageElement for clean annotation-free cropping
    const dataUrl = canvas.toDataURL('image/png');
    imageElement = new Image();
    imageElement.src = dataUrl;
    
    imageElement.onload = () => {
      redrawCanvas();
      if (helpText) {
        helpText.innerHTML = `<i class="fa-solid fa-mouse-pointer" style="margin-right: 6px;"></i> Select an active attribute, then click and drag to draw a selection rectangle. OCR will run on the selected area.`;
      }
    };
    
  } catch (err) {
    console.error("PDF.js render error: ", err);
    showToast('Failed to render PDF page.', 'error');
    drawFallbackCanvas();
    const helpText = document.getElementById('workspace-help-text');
    if (helpText) {
      helpText.innerHTML = `<i class="fa-solid fa-triangle-exclamation" style="margin-right: 6px; color: var(--danger);"></i> Unable to render PDF.`;
    }
  }
}

// Draw empty workspace
function drawEmptyWorkspace() {
  canvas.width = 700;
  canvas.height = 500;
  ctx.fillStyle = '#1e293b';
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  ctx.fillStyle = '#94a3b8';
  ctx.font = '16px Inter';
  ctx.textAlign = 'center';
  ctx.fillText("No active files inside this project.", canvas.width / 2, canvas.height / 2 - 20);
  ctx.fillText("Click 'Add Files' on the left sidebar to upload files.", canvas.width / 2, canvas.height / 2 + 10);
  ctx.textAlign = 'left';
}

// Draw a beautiful fallback preview
function drawFallbackCanvas() {
  canvas.width = 700;
  canvas.height = 500;
  ctx.fillStyle = '#1e293b';
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  ctx.fillStyle = '#94a3b8';
  ctx.font = '16px Inter';
  ctx.textAlign = 'center';
  ctx.fillText("Failed to load project layout preview.", canvas.width / 2, canvas.height / 2 - 20);
  ctx.fillText("You can still click anywhere inside this box to create manual mock tags.", canvas.width / 2, canvas.height / 2 + 10);
  ctx.textAlign = 'left';
}

// Render simulated HTML workspace for DOCX files
function drawDocxSandbox() {
  canvas.width = 800;
  canvas.height = 600;

  // Outer frame
  ctx.fillStyle = '#0f172a';
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  // Simulated Word Page
  const padding = 40;
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(padding, padding, canvas.width - padding * 2, canvas.height - padding * 2);
  ctx.strokeStyle = '#cbd5e1';
  ctx.lineWidth = 1;
  ctx.strokeRect(padding, padding, canvas.width - padding * 2, canvas.height - padding * 2);

  // Document title
  ctx.fillStyle = '#1e293b';
  ctx.font = 'bold 24px Outfit';
  ctx.fillText("Document Layout Analysis System", 80, 100);

  // Divider
  ctx.fillStyle = '#e2e8f0';
  ctx.fillRect(80, 120, 640, 2);

  // Paragraph blocks
  ctx.fillStyle = '#64748b';
  ctx.font = '14px Inter';
  ctx.fillText("This workspace represents the document structure converted to web layout blocks.", 80, 150);
  ctx.fillText("Please hover over the interactive buttons, tables, and inputs below to auto-tag boundaries.", 80, 170);

  // Draw simulated buttons and inputs (with coordinates cached in detectedBoxes)
  drawSimulatedComponent("Button", "Submit Button", 100, 220, 160, 40, '#6366f1');
  drawSimulatedComponent("Input Field", "Enter name...", 290, 220, 220, 40, '#f1f5f9', '#94a3b8');
  drawSimulatedComponent("Dropdown", "Select role...", 540, 220, 160, 40, '#f1f5f9', '#94a3b8');

  // Simulated Table
  ctx.fillStyle = '#f8fafc';
  ctx.fillRect(100, 310, 600, 180);
  ctx.strokeStyle = '#cbd5e1';
  ctx.strokeRect(100, 310, 600, 180);
  ctx.fillStyle = '#1e293b';
  ctx.font = 'bold 12px Inter';
  ctx.fillText("Header 1", 120, 335);
  ctx.fillText("Header 2", 320, 335);
  ctx.fillText("Header 3", 520, 335);

  detectedBoxes.push({
    x: 100, y: 310, width: 600, height: 180, type: 'Table'
  });

  // Checkboxes
  drawSimulatedCheckbox(100, 520, "Accept terms and conditions");
  drawSimulatedCheckbox(380, 520, "Subscribe to weekly newsletter");

  // Save the image data of the canvas so we can redraw it
  imageElement = new Image();
  imageElement.src = canvas.toDataURL();
}

function drawSimulatedComponent(type, label, x, y, w, h, bg, borderCol) {
  ctx.fillStyle = bg;
  ctx.fillRect(x, y, w, h);
  if (borderCol) {
    ctx.strokeStyle = borderCol;
    ctx.strokeRect(x, y, w, h);
  }
  ctx.fillStyle = borderCol ? '#1e293b' : '#ffffff';
  ctx.font = 'bold 13px Inter';
  ctx.textAlign = 'center';
  ctx.fillText(label, x + w / 2, y + h / 2 + 5);
  ctx.textAlign = 'left';

  detectedBoxes.push({ x, y, width: w, height: h, type });
}

function drawSimulatedCheckbox(x, y, label) {
  ctx.fillStyle = '#f1f5f9';
  ctx.fillRect(x, y, 20, 20);
  ctx.strokeStyle = '#94a3b8';
  ctx.strokeRect(x, y, 20, 20);
  ctx.fillStyle = '#1e293b';
  ctx.font = '13px Inter';
  ctx.fillText(label, x + 30, y + 15);

  detectedBoxes.push({ x, y, width: 20, height: 20, type: 'Checkbox' });
}

let canvasRenderer = null;
let selectionService = null;

// Clean architecture modules: CanvasRenderer & SelectionService
class CanvasRenderer {
  constructor(canvas, ctx) {
    this.canvas = canvas;
    this.ctx = ctx;
  }

  redraw() {
    if (!this.canvas || !this.ctx) return;

    // Clear Canvas
    this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

    // 1. Draw Page Image or Mock Page
    if (imageElement && imageElement.complete && imageElement.naturalWidth > 0) {
      this.ctx.drawImage(imageElement, 0, 0, this.canvas.width, this.canvas.height);
    } else {
      // Draw placeholder dark background
      this.ctx.fillStyle = '#1e293b';
      this.ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);
    }

    if (!currentFile) return;

    // 2. Draw Saved Detections for Current File & Page
    const pageDetections = detections.filter(d => d.pageNumber === currentPage);
    pageDetections.forEach(det => {
      const box = det.boundingBox;
      if (box) {
        // Normalize bounding box values to draw them correctly on screen
        let drawX = box.x;
        let drawY = box.y;
        let drawW = box.width;
        let drawH = box.height;

        if (drawW < 0) {
          drawX = drawX + drawW;
          drawW = -drawW;
        }
        if (drawH < 0) {
          drawY = drawY + drawH;
          drawH = -drawH;
        }

        this.ctx.strokeStyle = det.color;
        this.ctx.lineWidth = 2;
        this.ctx.setLineDash([]); // Solid lines for saved boxes
        this.ctx.strokeRect(drawX, drawY, drawW, drawH);

        // Light overlay
        this.ctx.fillStyle = det.color + '15'; // 15 is ~8% opacity
        this.ctx.fillRect(drawX, drawY, drawW, drawH);

        // Draw text label
        this.ctx.fillStyle = det.color;
        this.ctx.font = 'bold 11px Inter';
        this.ctx.fillText(det.attribute, drawX + 4, drawY + 15);
      }
    });

    // 3. Draw Active User Drag Selection Box (Snipping Tool Style)
    if (isDrawing && selectionRect && selectionRect.width !== undefined && selectionRect.height !== undefined) {
      this.ctx.strokeStyle = '#ef4444'; // Dashed red border
      this.ctx.lineWidth = 1.5;
      this.ctx.setLineDash([5, 4]); // Dashed line pattern
      
      // Transparent red fill
      this.ctx.fillStyle = 'rgba(239, 68, 68, 0.22)';
      
      this.ctx.fillRect(startX, startY, selectionRect.width, selectionRect.height);
      this.ctx.strokeRect(startX, startY, selectionRect.width, selectionRect.height);
      
      this.ctx.setLineDash([]); // Reset line dash
    }
  }
}

class SelectionService {
  constructor(canvas, renderer) {
    this.canvas = canvas;
    this.renderer = renderer;
  }

  init() {
    this.canvas.addEventListener('mousedown', (e) => {
      if (e.button !== 0) return; // Only left mouse button
      if (!currentFile) return; // No file selected
      if (!selectedAttribute) {
        showToast('Select an attribute from the left sidebar before tagging elements.', 'error');
        return;
      }

      const rect = this.canvas.getBoundingClientRect();
      const scaleX = this.canvas.width / rect.width;
      const scaleY = this.canvas.height / rect.height;

      startX = (e.clientX - rect.left) * scaleX;
      startY = (e.clientY - rect.top) * scaleY;
      isDrawing = true;
      
      selectionRect = {
        x: startX,
        y: startY,
        width: 0,
        height: 0
      };
    });

    this.canvas.addEventListener('mousemove', (e) => {
      if (!isDrawing) return;

      const rect = this.canvas.getBoundingClientRect();
      const scaleX = this.canvas.width / rect.width;
      const scaleY = this.canvas.height / rect.height;

      const currentX = (e.clientX - rect.left) * scaleX;
      const currentY = (e.clientY - rect.top) * scaleY;

      // Follow ONLY the mouse cursor
      selectionRect.width = currentX - startX;
      selectionRect.height = currentY - startY;

      requestAnimationFrame(() => this.renderer.redraw());
    });

    this.canvas.addEventListener('mouseleave', () => {
      if (isDrawing) {
        isDrawing = false;
        this.renderer.redraw();
      }
    });

    this.canvas.addEventListener('mouseup', async (e) => {
      if (!isDrawing || !selectedAttribute || !currentFile) {
        isDrawing = false;
        return;
      }
      isDrawing = false;

      const dragWidth = selectionRect.width;
      const dragHeight = selectionRect.height;

      // Ignore tiny accidental clicks/drags (absolute drag size < 5 px)
      if (Math.abs(dragWidth) < 5 || Math.abs(dragHeight) < 5) {
        this.renderer.redraw();
        return;
      }

      showToast('Reading text...', 'success');

      // Normalize crop coordinates (since user can drag backwards)
      let normX = startX;
      let normY = startY;
      let normW = dragWidth;
      let normH = dragHeight;
      if (normW < 0) {
        normX = normX + normW;
        normW = -normW;
      }
      if (normH < 0) {
        normY = normY + normH;
        normH = -normH;
      }

      // Crop only the selected rectangle from the clean imageElement (without annotations)
      let base64Image = '';
      if (imageElement && imageElement.complete) {
        try {
          const cropCanvas = document.createElement('canvas');
          cropCanvas.width = normW;
          cropCanvas.height = normH;
          const cropCtx = cropCanvas.getContext('2d');
          cropCtx.drawImage(
            imageElement,
            normX, normY, normW, normH, // Source
            0, 0, normW, normH           // Target
          );
          base64Image = cropCanvas.toDataURL('image/png');
        } catch (err) {
          console.error("Failed to crop selection: ", err);
        }
      }

      const colorHex = COLOR_MAP[selectedAttribute.color] || '#EF4444';
      const payload = {
        attribute: selectedAttribute.name,
        color: colorHex,
        elementType: 'Custom Block',
        pageNumber: currentPage,
        base64Image: base64Image,
        fileId: currentFile.id,
        boundingBox: {
          x: Math.round(normX),
          y: Math.round(normY),
          width: Math.round(normW),
          height: Math.round(normH)
        }
      };

      try {
        // Auto Save Detection with OCR
        const response = await fetch(`/api/projects/${currentProject.id}/detections/ocr`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify(payload)
        });

        if (response.ok) {
          const savedData = await response.json();
          showToast('OCR Completed Successfully. Confidence: ' + savedData.confidence.toFixed(1) + '%', 'success');
          await refreshDetections();
        } else {
          showToast('Failed to save tag', 'error');
        }
      } catch (err) {
        console.error(err);
        showToast('Network error saving tag', 'error');
      }
    });

    // Esc key cancels active drawing
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && isDrawing) {
        isDrawing = false;
        this.renderer.redraw();
      }
    });
  }
}

// Global drawing delegates
function redrawCanvas() {
  if (canvasRenderer) {
    canvasRenderer.redraw();
  }
}

function setupCanvasListeners() {
  if (!canvasRenderer) {
    canvasRenderer = new CanvasRenderer(canvas, ctx);
  }
  if (!selectionService) {
    selectionService = new SelectionService(canvas, canvasRenderer);
    selectionService.init();
  }
}

// Left Sidebar Attribute Management Panel API calls
async function refreshAttributes() {
  try {
    const res = await fetch(`/api/projects/${currentProject.id}/attributes`);
    if (!res.ok) throw new Error();
    attributes = await res.json();

    renderAttributesList();
  } catch (e) {
    showToast('Failed to load attributes', 'error');
  }
}

function renderAttributesList() {
  const container = document.getElementById('attributes-list');
  container.innerHTML = '';

  if (attributes.length === 0) {
    container.innerHTML = '<p style="font-size: 13px; color: var(--text-muted); text-align: center; padding: 10px 0;">No attributes created yet.</p>';
    selectedAttribute = null;
    return;
  }

  attributes.forEach(attr => {
    const item = document.createElement('div');
    item.className = `attr-item ${selectedAttribute && selectedAttribute.id === attr.id ? 'active' : ''}`;

    // Calculate tag counts dynamically
    const count = detections.filter(d => d.attribute.toLowerCase() === attr.name.toLowerCase()).length;
    const colorHex = COLOR_MAP[attr.color] || '#cbd5e1';

    item.innerHTML = `
      <div style="display: flex; align-items: center; gap: 8px;">
        <span class="color-dot" style="background-color: ${colorHex};"></span>
        <span style="font-size: 14px; font-weight: 500;">${escapeHtml(attr.name)}</span>
      </div>
      <div style="display: flex; align-items: center; gap: 10px;">
        <span class="badge" style="background-color: var(--bg-tertiary); border: 1px solid var(--border); color: var(--text-secondary);">${count}</span>
        <button class="btn" onclick="deleteAttribute('${attr.id}', event)" style="padding: 4px; background: none; color: var(--text-muted);" title="Delete Attribute">
          <i class="fa-regular fa-trash-can" style="font-size: 13px;"></i>
        </button>
      </div>
    `;

    item.onclick = () => {
      selectedAttribute = attr;
      // Re-render selection outline
      document.querySelectorAll('.attr-item').forEach(el => el.classList.remove('active'));
      item.classList.add('active');
      redrawCanvas();
      // Set cursor to crosshair for selection
      canvas.style.cursor = 'crosshair';
    };

    container.appendChild(item);
  });
}

async function deleteAttribute(id, event) {
  event.stopPropagation();
  if (!confirm('Are you sure you want to delete this attribute? All elements tagged under this attribute will be removed automatically.')) {
    return;
  }

  try {
    const res = await fetch(`/api/attributes/${id}`, {
      method: 'DELETE'
    });
    if (res.ok) {
      if (selectedAttribute && selectedAttribute.id === id) {
        selectedAttribute = null;
        canvas.style.cursor = 'default';
      }
      await refreshDetections(); // Purges associated boxes as well
      await refreshAttributes();
      showToast('Attribute deleted successfully');
    } else {
      showToast('Error deleting attribute', 'error');
    }
  } catch (e) {
    showToast('Network error deleting attribute', 'error');
  }
}

// Sidebar project files logic
async function refreshProjectFiles() {
  try {
    const response = await fetch(`/api/projects/${currentProject.id}/files?includeDeleted=true`);
    if (response.ok) {
      projectFiles = await response.json();
      
      const activeFiles = projectFiles.filter(f => !f.isDeleted);
      if (!currentFile || !projectFiles.some(f => f.id === currentFile.id && !f.isDeleted)) {
        currentFile = activeFiles.length > 0 ? activeFiles[0] : null;
        currentPage = 1;
        totalPages = currentFile ? currentFile.pageCount : 1;
      } else {
        currentFile = projectFiles.find(f => f.id === currentFile.id);
      }

      // Hide or show page controls depending on file type and page count
      const pageControls = document.getElementById('page-controls');
      if (currentFile && currentFile.fileType === 'PDF' && totalPages > 1) {
        pageControls.style.display = 'flex';
        updatePageIndicator();
      } else {
        pageControls.style.display = 'none';
      }

      renderProjectFilesList();
      await refreshDetections();
      await loadWorkspaceImage();
    }
  } catch (err) {
    console.error(err);
  }
}

function renderProjectFilesList() {
  const container = document.getElementById('project-files-list');
  if (!container) return;
  container.innerHTML = '';

  if (projectFiles.length === 0) {
    container.innerHTML = '<p style="font-size: 11px; color: var(--text-muted); text-align: center; padding: 10px 0;">No files in project.</p>';
    currentFile = null;
    return;
  }

  projectFiles.forEach(file => {
    const item = document.createElement('div');
    item.className = `file-item ${currentFile && currentFile.id === file.id ? 'active' : ''} ${file.isDeleted ? 'deleted' : ''}`;
    
    // Inline layout styles for files
    item.style.display = 'flex';
    item.style.justifyContent = 'space-between';
    item.style.alignItems = 'center';
    item.style.padding = '8px 10px';
    item.style.borderRadius = '6px';
    item.style.cursor = 'pointer';
    item.style.fontSize = '12px';
    item.style.transition = 'all 0.2s';
    
    if (currentFile && currentFile.id === file.id) {
      item.style.backgroundColor = 'rgba(59, 130, 246, 0.15)';
      item.style.border = '1px solid var(--primary)';
      item.style.color = 'var(--primary-light)';
    } else if (file.isDeleted) {
      item.style.opacity = '0.45';
      item.style.border = '1px solid transparent';
    } else {
      item.style.backgroundColor = 'var(--bg-tertiary)';
      item.style.border = '1px solid var(--border)';
    }

    const typeColor = file.fileType === 'PDF' ? '#ef4444' : '#10b981';
    const textDecoration = file.isDeleted ? 'line-through' : 'none';

    item.innerHTML = `
      <div style="display: flex; align-items: center; gap: 8px; text-decoration: ${textDecoration}; max-width: 75%;" class="text-truncate" title="${escapeHtml(file.originalFileName)}">
        <i class="fa-regular ${file.fileType === 'PDF' ? 'fa-file-pdf' : 'fa-file-image'}" style="color: ${typeColor}; font-size: 14px;"></i>
        <span style="font-weight: 500;">${escapeHtml(file.originalFileName)}</span>
      </div>
      <div style="display: flex; align-items: center; gap: 4px;">
        ${file.isDeleted ? `
          <button class="btn-icon" onclick="restoreProjectFile('${file.id}', event)" style="padding: 4px; color: var(--success); background:none; border:none; cursor:pointer;" title="Restore File">
            <i class="fa-solid fa-arrow-rotate-left"></i>
          </button>
        ` : `
          <button class="btn-icon" onclick="deleteProjectFile('${file.id}', event)" style="padding: 4px; color: var(--text-muted); background:none; border:none; cursor:pointer;" title="Delete File">
            <i class="fa-regular fa-trash-can"></i>
          </button>
        `}
      </div>
    `;

    item.onclick = async () => {
      if (file.isDeleted) return;
      currentFile = file;
      currentPage = 1;
      totalPages = file.pageCount;
      
      // Update page indicators
      const pageControls = document.getElementById('page-controls');
      if (file.fileType === 'PDF' && totalPages > 1) {
        pageControls.style.display = 'flex';
        updatePageIndicator();
      } else {
        pageControls.style.display = 'none';
      }

      renderProjectFilesList();
      await refreshDetections();
      await loadWorkspaceImage();
    };

    container.appendChild(item);
  });
}

async function deleteProjectFile(fileId, event) {
  event.stopPropagation();
  if (!confirm('Are you sure you want to delete this file? The backend file storage remains intact.')) {
    return;
  }

  try {
    const res = await fetch(`/api/projects/${currentProject.id}/files/${fileId}`, {
      method: 'DELETE'
    });
    if (res.ok) {
      showToast('File soft-deleted successfully');
      await refreshProjectFiles();
    } else {
      showToast('Error deleting file', 'error');
    }
  } catch (e) {
    showToast('Network error deleting file', 'error');
  }
}

async function restoreProjectFile(fileId, event) {
  event.stopPropagation();
  try {
    const res = await fetch(`/api/projects/${currentProject.id}/files/${fileId}/restore`, {
      method: 'POST'
    });
    if (res.ok) {
      showToast('File restored successfully');
      await refreshProjectFiles();
    } else {
      showToast('Error restoring file', 'error');
    }
  } catch (e) {
    showToast('Network error restoring file', 'error');
  }
}

function setupAddFileInput() {
  const addFileInput = document.getElementById('add-file-input');
  if (addFileInput) {
    addFileInput.addEventListener('change', async () => {
      if (addFileInput.files.length === 0) return;
      
      showToast('Uploading files...', 'success');
      const formData = new FormData();
      for (let i = 0; i < addFileInput.files.length; i++) {
        formData.append('file', addFileInput.files[i]);
      }
      formData.append('managerEmail', localStorage.getItem('manager_email') || 'manager@app.com');

      try {
        const response = await fetch(`/api/projects/${currentProject.id}/files`, {
          method: 'POST',
          body: formData
        });

        if (response.ok) {
          showToast('Files uploaded successfully', 'success');
          addFileInput.value = '';
          await refreshProjectFiles();
        } else {
          const data = await response.json();
          showToast(data.error || 'Failed to upload files', 'error');
        }
      } catch (err) {
        console.error(err);
        showToast('Error uploading files', 'error');
      }
    });
  }
}

// Right Sidebar Tag Tables and live summaries
async function refreshDetections() {
  try {
    if (!currentFile) {
      detections = [];
      renderDetectionsTable();
      renderSummaryPanel();
      redrawCanvas();
      return;
    }

    const res = await fetch(`/api/projects/${currentProject.id}/files/${currentFile.id}/detections`);
    if (!res.ok) throw new Error();
    detections = await res.json();

    renderDetectionsTable();
    renderSummaryPanel();

    if (attributes.length > 0) {
      renderAttributesList();
    }

    redrawCanvas();
    refreshDebugImages();
  } catch (e) {
    console.error(e);
  }
}

function refreshDebugImages() {
  const cropEl = document.getElementById('debug-crop');
  const grayEl = document.getElementById('debug-gray');
  const threshEl = document.getElementById('debug-thresh');
  const finalEl = document.getElementById('debug-final');
  const linkEl = document.getElementById('debug-orig-link');

  if (cropEl && grayEl && threshEl && finalEl && linkEl) {
    const t = Date.now();
    cropEl.src = `/uploads/debug/cropped.png?t=${t}`;
    grayEl.src = `/uploads/debug/grayscale.png?t=${t}`;
    threshEl.src = `/uploads/debug/threshold.png?t=${t}`;
    finalEl.src = `/uploads/debug/final_ocr_input.png?t=${t}`;
    linkEl.href = `/uploads/debug/original.png?t=${t}`;
  }
}

function renderDetectionsTable() {
  const tbody = document.getElementById('detections-tbody');
  const empty = document.getElementById('detections-empty-state');
  tbody.innerHTML = '';

  const pageDetections = detections.filter(d => d.pageNumber === currentPage);

  if (pageDetections.length === 0) {
    empty.style.display = 'block';
    return;
  }

  empty.style.display = 'none';

  pageDetections.forEach(det => {
    const row = document.createElement('tr');

    row.innerHTML = `
      <td>
        <span style="font-weight: 500;">${escapeHtml(det.attribute)}</span>
        <div style="font-size: 11px; color: var(--text-secondary); margin-top: 4px; font-family: monospace; white-space: pre-wrap; word-break: break-all;">${escapeHtml(det.detectedText || '')}</div>
        <div style="font-size: 10px; color: var(--text-muted); margin-top: 2px;">${det.elementType}</div>
      </td>
      <td>
        <div style="display: flex; align-items: center; gap: 6px;">
          <span class="color-dot" style="background-color: ${det.color}; margin-right: 0;"></span>
          <span style="font-family: monospace; font-size: 11px;">${det.color}</span>
        </div>
      </td>
      <td style="text-align: center;">
        <button class="btn btn-secondary" onclick="deleteDetection('${det.id}')" style="padding: 4px 8px; background: none; border: none; color: var(--danger);">
          <i class="fa-regular fa-trash-can"></i>
        </button>
      </td>
    `;
    tbody.appendChild(row);
  });
}

async function deleteDetection(id) {
  try {
    const res = await fetch(`/api/detections/${id}`, {
      method: 'DELETE'
    });
    if (res.ok) {
      await refreshDetections();
      showToast('Tag deleted successfully');
    } else {
      showToast('Failed to delete tag', 'error');
    }
  } catch (e) {
    showToast('Network error deleting tag', 'error');
  }
}

function renderSummaryPanel() {
  const container = document.getElementById('summary-items-container');
  const grandTotalEl = document.getElementById('grand-total-count');
  container.innerHTML = '';

  // Calculate totals grouped by Attribute Name
  const summaryMap = {};
  detections.forEach(det => {
    summaryMap[det.attribute] = (summaryMap[det.attribute] || 0) + 1;
  });

  const uniqueAttrNames = Object.keys(summaryMap);

  if (uniqueAttrNames.length === 0) {
    container.innerHTML = '<p style="font-size: 12px; color: var(--text-muted); text-align: center; padding: 12px 0;">No elements tagged</p>';
    grandTotalEl.textContent = '0';
    return;
  }

  uniqueAttrNames.forEach(name => {
    const count = summaryMap[name];

    // Find matching hex color from attributes cache or detections
    const attr = attributes.find(a => a.name.toLowerCase() === name.toLowerCase());
    const colorHex = attr ? (COLOR_MAP[attr.color] || '#94a3b8') : '#94a3b8';

    const item = document.createElement('div');
    item.className = 'summary-item';
    item.innerHTML = `
      <div style="display: flex; align-items: center;">
        <span class="color-dot" style="background-color: ${colorHex};"></span>
        <span>${escapeHtml(name)}</span>
      </div>
      <span style="font-weight: 600;">${count} Element${count > 1 ? 's' : ''}</span>
    `;
    container.appendChild(item);
  });

  grandTotalEl.textContent = detections.length;
}

// Form Handlers
function setupForms() {
  // Attribute Creation Form
  const form = document.getElementById('attribute-form');
  if (form) {
    form.addEventListener('submit', async (e) => {
      e.preventDefault();

      const name = document.getElementById('attr-name').value.trim();
      const color = document.getElementById('attr-color').value;

      if (!name) return;

      // Check for duplicate attribute name
      const exists = attributes.some(a => a.name.toLowerCase() === name.toLowerCase());
      if (exists) {
        showToast('An attribute with this name already exists.', 'error');
        return;
      }

      try {
        const res = await fetch(`/api/projects/${currentProject.id}/attributes`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ name, color })
        });

        if (res.ok) {
          showToast('Attribute added successfully', 'success');
          form.reset();
          await refreshAttributes();
        } else {
          showToast('Failed to add attribute', 'error');
        }
      } catch (err) {
        showToast('Error connecting to backend', 'error');
      }
    });
  }
}

// Multi-page PDF paging logic
function changePage(direction) {
  const targetPage = currentPage + direction;
  if (targetPage < 1 || targetPage > totalPages) return;

  currentPage = targetPage;
  updatePageIndicator();
  loadWorkspaceImage();
}

function updatePageIndicator() {
  document.getElementById('page-indicator').textContent = `Page ${currentPage} of ${totalPages}`;
  document.getElementById('prev-page-btn').disabled = currentPage === 1;
  document.getElementById('next-page-btn').disabled = currentPage === totalPages;
}

// PDF Report Trigger
function setupPDFReportButton() {
  const btn = document.getElementById('pdf-report-btn');
  if (btn) {
    btn.onclick = async () => {
      showToast('Generating PDF Report... Please wait.', 'success');
      try {
        const response = await fetch(`/api/projects/${currentProject.id}/report`);
        if (!response.ok) throw new Error('Failed to generate report');

        const blob = await response.blob();
        const url = window.URL.createObjectURL(blob);

        const link = document.createElement('a');
        link.href = url;
        const safeProjectName = currentProject.name.replace(/[^a-zA-Z0-9-_\.]/g, '_');
        link.download = `Project_${safeProjectName}_Report.pdf`;

        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.URL.revokeObjectURL(url);

        showToast('PDF Report downloaded successfully', 'success');
      } catch (err) {
        console.error(err);
        showToast('Failed to download PDF report', 'error');
      }
    };
  }
}

// HTML Escaping Utility
function escapeHtml(text) {
  const map = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#039;'
  };
  return text.replace(/[&<>"']/g, function (m) { return map[m]; });
}
