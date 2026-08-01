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
let pageAnnotationsCache = new Map();

let isDrawing = false;
let selectionRect = {};
let startX, startY;
let pdfDocument = null;
let pdfLoadingPromise = null;
let pdfPageCache = new Map();

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

function getSelectedAttributeColorHex() {
  if (selectedAttribute && selectedAttribute.color) {
    const explicitColor = COLOR_MAP[selectedAttribute.color] || selectedAttribute.color;
    return explicitColor.startsWith('#') ? explicitColor : `#${explicitColor}`;
  }
  return '#ef4444';
}

function getColorWithAlpha(colorHex, alpha) {
  if (!colorHex) return `rgba(239, 68, 68, ${alpha})`;
  const normalized = colorHex.startsWith('#') ? colorHex : `#${colorHex}`;
  const hex = normalized.slice(1);
  const fullHex = hex.length === 3 ? hex.split('').map(ch => ch + ch).join('') : hex;
  const r = parseInt(fullHex.slice(0, 2), 16);
  const g = parseInt(fullHex.slice(2, 4), 16);
  const b = parseInt(fullHex.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

function applySelectionCursor() {
  if (!canvas) return;
  if (!selectedAttribute) {
    canvas.style.cursor = 'default';
    return;
  }

  const colorHex = getSelectedAttributeColorHex();
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><rect width="24" height="24" fill="transparent"/><line x1="12" y1="2" x2="12" y2="22" stroke="${colorHex}" stroke-width="2" stroke-linecap="round"/><line x1="2" y1="12" x2="22" y2="12" stroke="${colorHex}" stroke-width="2" stroke-linecap="round"/></svg>`;
  const encoded = encodeURIComponent(svg).replace(/'/g, '%27');
  canvas.style.cursor = `url("data:image/svg+xml;charset=utf-8,${encoded}") 12 12, crosshair`;
}

function getAnnotationStorageKey() {
  if (!currentProject || !currentFile) return null;
  return `tagging-annotations-${currentProject.id}-${currentFile.id}`;
}

function persistPageAnnotations() {
  const storageKey = getAnnotationStorageKey();
  if (!storageKey) return;
  const serialized = Object.fromEntries(pageAnnotationsCache.entries());
  localStorage.setItem(storageKey, JSON.stringify(serialized));
}

function restorePageAnnotations() {
  const storageKey = getAnnotationStorageKey();
  if (!storageKey) return;

  const storedValue = localStorage.getItem(storageKey);
  if (!storedValue) return;

  try {
    const parsed = JSON.parse(storedValue);
    const map = new Map();
    Object.keys(parsed).forEach(k => {
      const pageNum = Number(k);
      try {
        map.set(pageNum, parsed[k]);
      } catch (e) {
        map.set(pageNum, parsed[k]);
      }
    });
    pageAnnotationsCache = map;
  } catch (err) {
    console.warn('Failed to restore page annotations from storage', err);
  }
}

function resetAnnotationState() {
  pageAnnotationsCache = new Map();
  persistPageAnnotations();
}

function resetPdfViewerState() {
  pdfDocument = null;
  pdfLoadingPromise = null;
  pdfPageCache = new Map();
}

function drawSelectionHandles(ctx, x, y, width, height, color) {
  const handleSize = Math.max(6, Math.min(10, Math.round(Math.max(canvas.width, canvas.height) / 180)));
  const points = [
    { x, y },
    { x: x + width, y },
    { x, y: y + height },
    { x: x + width, y: y + height }
  ];

  ctx.save();
  ctx.fillStyle = color;
  points.forEach(point => {
    ctx.fillRect(point.x - handleSize / 2, point.y - handleSize / 2, handleSize, handleSize);
  });
  ctx.restore();
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
      setupPageControls();

  } catch (err) {
    console.error(err);
    showToast('Failed to initialize project workspace', 'error');
  }
}

// Load Image/Document onto Canvas
let activeDrawingPage = 1;
let pageObserver = null;

// Load Image/Document onto Multi-Page Continuous Scroll Viewport
async function loadWorkspaceImage() {
  detectedBoxes = [];
  const container = document.getElementById('pages-scroll-wrapper');
  if (!container) return;
  container.innerHTML = '';

  if (currentFile && currentFile.fileType === 'PDF') {
    resetPdfViewerState();
  }

  if (!currentFile) {
    drawEmptyWorkspace();
    return;
  }

  totalPages = currentFile.pageCount || 1;
  const isPdf = currentFile.fileType === 'PDF';

  // If PDF, fetch PDF document info in background to ensure accurate total page count
  if (isPdf) {
    loadPdfDocument().catch(() => {});
  }

  // Create Page Cards for every page in the document (Page 1 -> Page N)
  for (let p = 1; p <= totalPages; p++) {
    const card = document.createElement('div');
    card.className = `pdf-page-card ${p === currentPage ? 'active-page-card' : ''}`;
    card.id = `pdf-page-card-${p}`;
    card.dataset.page = p;

    card.innerHTML = `
      <div class="pdf-page-header">
        <span><i class="fa-regular ${isPdf ? 'fa-file-pdf' : 'fa-file-image'}"></i> Page ${p} of ${totalPages}</span>
        <span class="badge" id="page-tags-badge-${p}" style="background: var(--bg-primary); border: 1px solid var(--border); color: var(--text-secondary); font-size: 11px;">0 Tags</span>
      </div>
      <div class="pdf-page-canvas-wrapper">
        <canvas class="pdf-page-canvas" id="canvas-page-${p}" data-page="${p}"></canvas>
      </div>
    `;
    container.appendChild(card);

    const c = document.getElementById(`canvas-page-${p}`);
    if (c) {
      c.width = 800;
      c.height = 1000;
      attachSelectionServiceToCanvas(c, p);
    }
  }

  // Setup IntersectionObserver for lazy-loading pages on scroll
  setupPageIntersectionObserver();

  // Render current page image immediately
  await loadPageImageAndDraw(currentPage);

  // Update page indicators in toolbar
  const pageControls = document.getElementById('page-controls');
  if (pageControls) {
    pageControls.style.display = isPdf ? 'flex' : 'none';
    updatePageIndicator();
  }
}

function setupPageIntersectionObserver() {
  if (pageObserver) {
    pageObserver.disconnect();
  }

  const scrollContainer = document.getElementById('canvas-container');
  const options = {
    root: scrollContainer,
    rootMargin: '400px 0px',
    threshold: 0.1
  };

  pageObserver = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        const pageNum = parseInt(entry.target.dataset.page, 10);
        if (pageNum) {
          loadPageImageAndDraw(pageNum);
          if (Math.abs(currentPage - pageNum) > 0) {
            currentPage = pageNum;
            updatePageIndicator();
            updatePageCardSelection(pageNum);
          }
        }
      }
    });
  }, options);

  document.querySelectorAll('.pdf-page-card').forEach(card => {
    pageObserver.observe(card);
  });
}

function updatePageCardSelection(pageNum) {
  document.querySelectorAll('.pdf-page-card').forEach(card => {
    if (parseInt(card.dataset.page, 10) === pageNum) {
      card.classList.add('active-page-card');
    } else {
      card.classList.remove('active-page-card');
    }
  });
}

async function loadPageImageAndDraw(pageNum) {
  const c = document.getElementById(`canvas-page-${pageNum}`);
  if (!c) return;

  if (!currentFile) return;

  if (currentFile.fileType === 'PDF') {
    try {
      const pageImage = await renderPdfPage(pageNum);
      if (pageImage) {
        c.width = pageImage.naturalWidth || pageImage.width || 800;
        c.height = pageImage.naturalHeight || pageImage.height || 1000;
        redrawPageCanvas(pageNum);
      }
    } catch (err) {
      console.error(`Error loading PDF page ${pageNum}:`, err);
    }
  } else if (currentFile.fileType === 'DOCX') {
    drawDocxSandboxOnCanvas(c);
  } else {
    // PNG, JPG, JPEG, TIFF, BMP
    const img = new Image();
    img.src = `/${currentFile.filePath}`;
    img.onload = () => {
      c.width = img.naturalWidth || img.width;
      c.height = img.naturalHeight || img.height;
      imageElement = img;
      redrawPageCanvas(1);
    };
  }
}

function redrawAllPageCanvases() {
  for (let p = 1; p <= totalPages; p++) {
    redrawPageCanvas(p);
  }
}

function redrawPageCanvas(pageNum) {
  const pageCanvas = document.getElementById(`canvas-page-${pageNum}`);
  if (!pageCanvas) return;
  const pageCtx = pageCanvas.getContext('2d');

  pageCtx.clearRect(0, 0, pageCanvas.width, pageCanvas.height);

  // 1. Draw Page Image
  const pageImg = pdfPageCache.get(pageNum) || (pageNum === 1 ? imageElement : null);
  if (pageImg && pageImg.complete && pageImg.naturalWidth > 0) {
    pageCtx.drawImage(pageImg, 0, 0, pageCanvas.width, pageCanvas.height);
  } else {
    pageCtx.fillStyle = '#ffffff';
    pageCtx.fillRect(0, 0, pageCanvas.width, pageCanvas.height);
  }

  // 2. Draw Saved Detections for Page pageNum
  const pageDetections = pageAnnotationsCache.get(pageNum) || detections.filter(d => d.pageNumber === pageNum);
  
  // Update badge count in card header
  const badgeEl = document.getElementById(`page-tags-badge-${pageNum}`);
  if (badgeEl) {
    badgeEl.textContent = `${pageDetections.length} Tag${pageDetections.length === 1 ? '' : 's'}`;
  }

  pageDetections.forEach(det => {
    const box = det.boundingBox;
    if (box) {
      let scaleX = (box.canvasWidth && box.canvasWidth > 0) ? (pageCanvas.width / box.canvasWidth) : 1;
      let scaleY = (box.canvasHeight && box.canvasHeight > 0) ? (pageCanvas.height / box.canvasHeight) : 1;

      let drawX = box.x * scaleX;
      let drawY = box.y * scaleY;
      let drawW = box.width * scaleX;
      let drawH = box.height * scaleY;

      if (drawW < 0) { drawX += drawW; drawW = -drawW; }
      if (drawH < 0) { drawY += drawH; drawH = -drawH; }

      pageCtx.strokeStyle = det.color;
      pageCtx.lineWidth = 2.5;
      pageCtx.setLineDash([]);
      pageCtx.strokeRect(drawX, drawY, drawW, drawH);

      pageCtx.fillStyle = det.color + '18';
      pageCtx.fillRect(drawX, drawY, drawW, drawH);

      // Label background box
      pageCtx.fillStyle = det.color;
      pageCtx.font = 'bold 11px Inter';
      const labelText = det.attribute;
      const textWidth = pageCtx.measureText(labelText).width;
      pageCtx.fillRect(drawX, drawY, textWidth + 8, 18);

      pageCtx.fillStyle = '#ffffff';
      pageCtx.fillText(labelText, drawX + 4, drawY + 13);
    }
  });

  // 3. Draw Active User Drag Selection Box
  if (isDrawing && activeDrawingPage === pageNum && selectionRect && selectionRect.width !== undefined) {
    const activeColor = getSelectedAttributeColorHex();
    let drawX = startX;
    let drawY = startY;
    let drawW = selectionRect.width;
    let drawH = selectionRect.height;
    if (drawW < 0) { drawX += drawW; drawW = -drawW; }
    if (drawH < 0) { drawY += drawH; drawH = -drawH; }

    pageCtx.strokeStyle = activeColor;
    pageCtx.lineWidth = 2;
    pageCtx.setLineDash([6, 4]);
    pageCtx.fillStyle = getColorWithAlpha(activeColor, 0.2);
    pageCtx.fillRect(drawX, drawY, drawW, drawH);
    pageCtx.strokeRect(drawX, drawY, drawW, drawH);
    drawSelectionHandles(pageCtx, drawX, drawY, drawW, drawH, activeColor);
    pageCtx.setLineDash([]);
  }
}

function attachSelectionServiceToCanvas(targetCanvas, pageNum) {
  targetCanvas.addEventListener('mousedown', (e) => {
    if (e.button !== 0 || !currentFile) return;
    if (!selectedAttribute) {
      showToast('Select an attribute from the left sidebar before tagging elements.', 'error');
      return;
    }

    currentPage = pageNum;
    activeDrawingPage = pageNum;
    updatePageCardSelection(pageNum);

    const rect = targetCanvas.getBoundingClientRect();
    const scaleX = targetCanvas.width / rect.width;
    const scaleY = targetCanvas.height / rect.height;

    startX = (e.clientX - rect.left) * scaleX;
    startY = (e.clientY - rect.top) * scaleY;
    isDrawing = true;

    selectionRect = {
      x: startX,
      y: startY,
      width: 0,
      height: 0,
      currentX: startX,
      currentY: startY
    };
  });

  targetCanvas.addEventListener('mousemove', (e) => {
    if (!isDrawing || activeDrawingPage !== pageNum) return;

    const rect = targetCanvas.getBoundingClientRect();
    const scaleX = targetCanvas.width / rect.width;
    const scaleY = targetCanvas.height / rect.height;

    const currentX = (e.clientX - rect.left) * scaleX;
    const currentY = (e.clientY - rect.top) * scaleY;

    selectionRect.currentX = currentX;
    selectionRect.currentY = currentY;
    selectionRect.width = currentX - startX;
    selectionRect.height = currentY - startY;

    requestAnimationFrame(() => redrawPageCanvas(pageNum));
  });

  targetCanvas.addEventListener('mouseleave', () => {
    if (isDrawing && activeDrawingPage === pageNum) {
      isDrawing = false;
      redrawPageCanvas(pageNum);
    }
  });

  targetCanvas.addEventListener('mouseup', async (e) => {
    if (!isDrawing || activeDrawingPage !== pageNum || !selectedAttribute || !currentFile) {
      isDrawing = false;
      return;
    }
    isDrawing = false;

    const dragWidth = selectionRect.width;
    const dragHeight = selectionRect.height;
    if (Math.abs(dragWidth) < 5 || Math.abs(dragHeight) < 5) {
      redrawPageCanvas(pageNum);
      return;
    }

    showToast(`Reading text on Page ${pageNum}...`, 'success');

    let normX = startX;
    let normY = startY;
    let normW = dragWidth;
    let normH = dragHeight;
    if (normW < 0) { normX += normW; normW = -normW; }
    if (normH < 0) { normY += normH; normH = -normH; }

    let base64Image = '';
    try {
      const cropCanvas = document.createElement('canvas');
      cropCanvas.width = Math.max(1, Math.round(normW));
      cropCanvas.height = Math.max(1, Math.round(normH));
      const cropCtx = cropCanvas.getContext('2d');
      cropCtx.drawImage(
        targetCanvas,
        normX, normY, normW, normH,
        0, 0, cropCanvas.width, cropCanvas.height
      );
      base64Image = cropCanvas.toDataURL('image/png');
    } catch (err) {
      console.error("Failed to crop selection from canvas: ", err);
    }

    const colorHex = getSelectedAttributeColorHex();
    const payload = {
      attribute: selectedAttribute.name,
      color: colorHex,
      elementType: 'Custom Block',
      pageNumber: pageNum,
      base64Image: base64Image,
      fileId: currentFile.id,
      boundingBox: {
        x: Math.round(normX),
        y: Math.round(normY),
        width: Math.round(normW),
        height: Math.round(normH),
        canvasWidth: targetCanvas.width,
        canvasHeight: targetCanvas.height
      }
    };

    try {
      const response = await fetch(`/api/projects/${currentProject.id}/detections/ocr`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      if (response.ok) {
        const savedData = await response.json();
        const confStr = typeof savedData.confidence === 'number' ? savedData.confidence.toFixed(1) : '0.0';
        showToast(`✓ OCR Success (Page ${pageNum}). Confidence: ${confStr}%`, 'success');
        await refreshDetections();
        await loadPageAnnotations(pageNum);
        redrawPageCanvas(pageNum);
      } else {
        showToast('Failed to save tag', 'error');
      }
    } catch (err) {
      console.error(err);
      showToast('Network error saving tag', 'error');
    }
  });
}

async function loadPdfDocument() {
  if (!currentFile || currentFile.fileType !== 'PDF') return null;
  if (pdfDocument) return pdfDocument;
  if (pdfLoadingPromise) return pdfLoadingPromise;

  const pdfUrl = `/${currentFile.filePath}`;
  if (typeof pdfjsLib === 'undefined') {
    throw new Error('PDF.js is not available');
  }

  pdfjsLib.GlobalWorkerOptions.workerSrc = 'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.4.120/pdf.worker.min.js';
  pdfLoadingPromise = pdfjsLib.getDocument(pdfUrl).promise.then(doc => {
    pdfDocument = doc;
    if (doc && doc.numPages) {
      if (doc.numPages > totalPages) {
        totalPages = doc.numPages;
        if (currentFile) currentFile.pageCount = doc.numPages;
        // Re-render layout with all discovered pages
        loadWorkspaceImage();
      }
    }
    return doc;
  });

  return pdfLoadingPromise;
}

async function renderPdfPage(pageNumber) {
  const cachedPage = pdfPageCache.get(pageNumber);
  if (cachedPage && cachedPage.complete) {
    return cachedPage;
  }

  try {
    const doc = await loadPdfDocument();
    if (doc) {
      const page = await doc.getPage(pageNumber);
      const scale = 2.0;
      const viewport = page.getViewport({ scale });

      const tempCanvas = document.createElement('canvas');
      tempCanvas.width = viewport.width;
      tempCanvas.height = viewport.height;
      const tempContext = tempCanvas.getContext('2d');
      tempContext.fillStyle = '#FFFFFF';
      tempContext.fillRect(0, 0, tempCanvas.width, tempCanvas.height);

      await page.render({ canvasContext: tempContext, viewport }).promise;

      const dataUrl = tempCanvas.toDataURL('image/png');
      const img = new Image();
      img.src = dataUrl;
      await new Promise((resolve, reject) => {
        img.onload = resolve;
        img.onerror = reject;
      });

      pdfPageCache.set(pageNumber, img);
      return img;
    }
  } catch (err) {
    console.warn("Client PDF.js render error on page " + pageNumber + ", falling back to backend lazy render: ", err);
  }

  const backendUrl = `/api/projects/${currentProject.id}/files/${currentFile.id}/pages/${pageNumber}/render`;
  const img = new Image();
  img.src = backendUrl;
  await new Promise((resolve, reject) => {
    img.onload = resolve;
    img.onerror = reject;
  });

  pdfPageCache.set(pageNumber, img);
  return img;
}

function drawEmptyWorkspace() {
  const container = document.getElementById('pages-scroll-wrapper');
  if (container) {
    container.innerHTML = `
      <div style="text-align: center; padding: 60px 20px; color: var(--text-secondary);">
        <i class="fa-solid fa-folder-open" style="font-size: 40px; margin-bottom: 12px; opacity: 0.5;"></i>
        <p style="font-size: 14px; font-weight: 500;">No active files inside this project.</p>
        <p style="font-size: 12px; color: var(--text-muted); margin-top: 4px;">Click 'Add Files' on the left sidebar to upload files.</p>
      </div>
    `;
  }
}

function drawDocxSandboxOnCanvas(canvasEl) {
  const cCtx = canvasEl.getContext('2d');
  canvasEl.width = 800;
  canvasEl.height = 600;

  cCtx.fillStyle = '#0f172a';
  cCtx.fillRect(0, 0, canvasEl.width, canvasEl.height);

  const padding = 40;
  cCtx.fillStyle = '#ffffff';
  cCtx.fillRect(padding, padding, canvasEl.width - padding * 2, canvasEl.height - padding * 2);
  cCtx.strokeStyle = '#cbd5e1';
  cCtx.lineWidth = 1;
  cCtx.strokeRect(padding, padding, canvasEl.width - padding * 2, canvasEl.height - padding * 2);

  cCtx.fillStyle = '#1e293b';
  cCtx.font = 'bold 24px Outfit';
  cCtx.fillText("Document Layout Analysis System", 80, 100);
}
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
      const pageDetections = pageAnnotationsCache.get(currentPage) || detections.filter(d => d.pageNumber === currentPage);
    pageDetections.forEach(det => {
      const box = det.boundingBox;
      if (box) {
        // Normalize bounding box values and scale if canvas dimensions differ
        let scaleX = (box.canvasWidth && box.canvasWidth > 0) ? (this.canvas.width / box.canvasWidth) : 1;
        let scaleY = (box.canvasHeight && box.canvasHeight > 0) ? (this.canvas.height / box.canvasHeight) : 1;

        let drawX = box.x * scaleX;
        let drawY = box.y * scaleY;
        let drawW = box.width * scaleX;
        let drawH = box.height * scaleY;

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
      const activeColor = getSelectedAttributeColorHex();
      let drawX = startX;
      let drawY = startY;
      let drawW = selectionRect.width;
      let drawH = selectionRect.height;
      if (drawW < 0) {
        drawX = drawX + drawW;
        drawW = -drawW;
      }
      if (drawH < 0) {
        drawY = drawY + drawH;
        drawH = -drawH;
      }

      this.ctx.strokeStyle = activeColor;
      this.ctx.lineWidth = 1.6;
      this.ctx.setLineDash([6, 4]);
      this.ctx.fillStyle = getColorWithAlpha(activeColor, 0.2);
      this.ctx.fillRect(drawX, drawY, drawW, drawH);
      this.ctx.strokeRect(drawX, drawY, drawW, drawH);
      drawSelectionHandles(this.ctx, drawX, drawY, drawW, drawH, activeColor);

      if (typeof selectionRect.currentX === 'number' && typeof selectionRect.currentY === 'number') {
        this.ctx.save();
        this.ctx.strokeStyle = activeColor;
        this.ctx.lineWidth = 1;
        this.ctx.setLineDash([3, 3]);
        this.ctx.beginPath();
        this.ctx.moveTo(selectionRect.currentX, 0);
        this.ctx.lineTo(selectionRect.currentX, this.canvas.height);
        this.ctx.moveTo(0, selectionRect.currentY);
        this.ctx.lineTo(this.canvas.width, selectionRect.currentY);
        this.ctx.stroke();
        this.ctx.restore();
      }

      this.ctx.setLineDash([]);
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
        height: 0,
        currentX: startX,
        currentY: startY
      };
    });

    this.canvas.addEventListener('mousemove', (e) => {
      if (!isDrawing) return;

      const rect = this.canvas.getBoundingClientRect();
      const scaleX = this.canvas.width / rect.width;
      const scaleY = this.canvas.height / rect.height;

      const currentX = (e.clientX - rect.left) * scaleX;
      const currentY = (e.clientY - rect.top) * scaleY;

      selectionRect.currentX = currentX;
      selectionRect.currentY = currentY;
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

      const colorHex = getSelectedAttributeColorHex();
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
          height: Math.round(normH),
          canvasWidth: canvas.width,
          canvasHeight: canvas.height
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
          const confStr = typeof savedData.confidence === 'number' ? savedData.confidence.toFixed(1) : '0.0';
          showToast('✓ OCR Success. Confidence: ' + confStr + '%', 'success');
          await refreshDetections();
          await loadPageAnnotations(currentPage);
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

// Attach page control events (first/prev/next/last/jump)
function setupPageControls() {
  const firstBtn = document.getElementById('first-page-btn');
  const prevBtn = document.getElementById('prev-page-btn');
  const nextBtn = document.getElementById('next-page-btn');
  const lastBtn = document.getElementById('last-page-btn');
  const jumpInput = document.getElementById('page-jump-input');
  const pageControls = document.getElementById('page-controls');

  if (firstBtn) {
    firstBtn.onclick = (e) => { e.preventDefault(); changePageTo(1); };
  }
  if (prevBtn) {
    prevBtn.onclick = (e) => { e.preventDefault(); changePageTo(currentPage - 1); };
  }
  if (nextBtn) {
    nextBtn.onclick = (e) => { e.preventDefault(); changePageTo(currentPage + 1); };
  }
  if (lastBtn) {
    lastBtn.onclick = (e) => { e.preventDefault(); changePageTo(totalPages); };
  }
  if (jumpInput) {
    jumpInput.onchange = () => {
      let val = parseInt(jumpInput.value, 10);
      if (isNaN(val)) val = 1;
      val = Math.max(1, Math.min(totalPages, val));
      changePageTo(val);
    };
    jumpInput.onkeyup = (e) => {
      if (e.key === 'Enter') {
        let val = parseInt(jumpInput.value, 10);
        if (isNaN(val)) val = 1;
        val = Math.max(1, Math.min(totalPages, val));
        changePageTo(val);
      }
    };
  }

  // Ensure visibility state is correct
  if (pageControls) {
    pageControls.style.display = (currentFile && currentFile.fileType === 'PDF') ? 'flex' : 'none';
    updatePageIndicator();
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
      document.querySelectorAll('.attr-item').forEach(el => el.classList.remove('active'));
      item.classList.add('active');
      applySelectionCursor();
      redrawCanvas();
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
        applySelectionCursor();
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
      resetAnnotationState();
      restorePageAnnotations();
      resetPdfViewerState();
      
      // Update page indicators
      const pageControls = document.getElementById('page-controls');
      if (file.fileType === 'PDF') {
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

      const helpText = document.getElementById('workspace-help-text');
      if (helpText) {
        helpText.innerHTML = '<i class="fa-solid fa-spinner fa-spin" style="margin-right: 6px;"></i> Uploading files...';
      }

      const formData = new FormData();
      for (let i = 0; i < addFileInput.files.length; i++) {
        formData.append('file', addFileInput.files[i]);
      }

      const managerEmail = encodeURIComponent(localStorage.getItem('manager_email') || 'manager@app.com');
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `/api/projects/${currentProject.id}/files?managerEmail=${managerEmail}`);

      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable && helpText) {
          const percent = Math.round((event.loaded / event.total) * 100);
          helpText.innerHTML = `<i class="fa-solid fa-cloud-arrow-up" style="margin-right: 6px;"></i> Uploading files... ${percent}%`;
        }
      };

      xhr.onload = async () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          showToast('Files uploaded successfully', 'success');
          addFileInput.value = '';
          if (helpText) {
            helpText.innerHTML = '<i class="fa-solid fa-mouse-pointer" style="margin-right: 6px;"></i> Select an active attribute, then click and drag to draw a selection rectangle. OCR will run on the selected area.';
          }
          await refreshProjectFiles();
        } else {
          try {
            const data = JSON.parse(xhr.responseText);
            showToast(data.error || 'Failed to upload files', 'error');
          } catch (e) {
            showToast('Failed to upload files', 'error');
          }
        }
      };

      xhr.onerror = () => {
        showToast('Error uploading files', 'error');
      };

      xhr.send(formData);
    });
  }
}

// Right Sidebar Tag Tables and live summaries
async function refreshDetections() {
  try {
    if (!currentFile) {
      detections = [];
      resetAnnotationState();
      renderDetectionsTable();
      renderSummaryPanel();
      redrawCanvas();
      return;
    }

    const res = await fetch(`/api/projects/${currentProject.id}/files/${currentFile.id}/detections`);
    if (!res.ok) throw new Error();
    detections = await res.json();

    pageAnnotationsCache = new Map();
    detections.forEach(det => {
      const pageNumber = det.pageNumber || 1;
      const pageDetections = pageAnnotationsCache.get(pageNumber) || [];
      pageDetections.push(det);
      pageAnnotationsCache.set(pageNumber, pageDetections);
    });
    persistPageAnnotations();

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

async function loadPageAnnotations(pageNumber = currentPage) {
  if (!currentFile || !currentProject) return;

  try {
    const res = await fetch(`/api/projects/${currentProject.id}/files/${currentFile.id}/pages/${pageNumber}/detections`);
    if (!res.ok) throw new Error();
    const pageDetections = await res.json();

    pageAnnotationsCache.set(pageNumber, pageDetections);
    persistPageAnnotations();

    if (currentPage === pageNumber) {
      renderDetectionsTable();
      redrawCanvas();
    }
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
  if (!tbody) return;
  tbody.innerHTML = '';

  const allDetections = detections || [];

  if (allDetections.length === 0) {
    if (empty) empty.style.display = 'block';
    return;
  }

  if (empty) empty.style.display = 'none';

  allDetections.forEach(det => {
    const row = document.createElement('tr');
    row.style.cursor = 'pointer';
    const confVal = typeof det.confidence === 'number' ? det.confidence.toFixed(1) : '0.0';
    const box = det.boundingBox || { x: 0, y: 0, width: 0, height: 0 };
    const bboxText = `x:${box.x}, y:${box.y}, w:${box.width}, h:${box.height}`;
    const textStr = det.detectedText ? det.detectedText : 'OCR Failed';

    row.innerHTML = `
      <td>
        <div style="display: flex; align-items: center; justify-content: space-between;">
          <span style="font-weight: 600; font-size: 13px;">${escapeHtml(det.attribute)}</span>
          <span class="badge" style="background-color: rgba(16, 185, 129, 0.15); border: 1px solid rgba(16, 185, 129, 0.3); color: #10b981; font-size: 10px;">${confVal}%</span>
        </div>
        <div style="font-size: 11px; color: var(--text-secondary); margin-top: 4px; font-family: monospace; white-space: pre-wrap; word-break: break-all; max-height: 80px; overflow-y: auto; background: rgba(15, 23, 42, 0.5); padding: 4px 6px; border-radius: 4px;">${escapeHtml(textStr)}</div>
        <div style="font-size: 10px; color: var(--text-muted); margin-top: 4px; display: flex; justify-content: space-between;">
          <span class="badge" style="background: var(--bg-tertiary); border: 1px solid var(--border);">Pg ${det.pageNumber || 1}</span>
          <span style="font-family: monospace;">${bboxText}</span>
        </div>
      </td>
      <td>
        <div style="display: flex; align-items: center; gap: 6px;">
          <span class="color-dot" style="background-color: ${det.color}; margin-right: 0;"></span>
          <span style="font-family: monospace; font-size: 11px;">${det.color}</span>
        </div>
      </td>
      <td style="text-align: center;">
        <button class="btn btn-secondary" onclick="deleteDetection('${det.id}', event)" style="padding: 4px 8px; background: none; border: none; color: var(--danger);" title="Delete Tag">
          <i class="fa-regular fa-trash-can"></i>
        </button>
      </td>
    `;

    row.onclick = (e) => {
      if (e.target.closest('button')) return;
      changePageTo(det.pageNumber || 1);
    };

    tbody.appendChild(row);
  });
}

async function deleteDetection(id, event) {
  if (event) event.stopPropagation();
  try {
    const res = await fetch(`/api/detections/${id}`, {
      method: 'DELETE'
    });
    if (res.ok) {
      await refreshDetections();
      redrawAllPageCanvases();
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

function redrawCanvas() {
  redrawAllPageCanvases();
}

// Multi-page PDF paging logic
function changePage(direction) {
  changePageTo(currentPage + direction);
}

function changePageTo(targetPage) {
  if (targetPage < 1 || targetPage > totalPages) return;
  currentPage = targetPage;
  updatePageIndicator();
  updatePageCardSelection(targetPage);
  loadPageAnnotations(targetPage);

  const card = document.getElementById(`pdf-page-card-${targetPage}`);
  if (card) {
    card.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}

function updatePageIndicator() {
  const jumpInput = document.getElementById('page-jump-input');
  const totalSpan = document.getElementById('total-pages-span');
  const firstBtn = document.getElementById('first-page-btn');
  const prevBtn = document.getElementById('prev-page-btn');
  const nextBtn = document.getElementById('next-page-btn');
  const lastBtn = document.getElementById('last-page-btn');

  if (jumpInput) {
    jumpInput.value = currentPage;
    jumpInput.max = totalPages;
  }
  if (totalSpan) {
    totalSpan.textContent = totalPages;
  }
  if (firstBtn) firstBtn.disabled = currentPage === 1;
  if (prevBtn) prevBtn.disabled = currentPage === 1;
  if (nextBtn) nextBtn.disabled = currentPage >= totalPages;
  if (lastBtn) lastBtn.disabled = currentPage >= totalPages;
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
