package com.screensmithy.app.data.transfer

/**
 * Self-contained, responsive HTML5 sender interface served by the temporary TV transfer server.
 * Uses vanilla CSS and JavaScript with zero external CDN dependencies, ensuring it loads instantly
 * even on isolated offline local Wi-Fi networks.
 */
object SenderHtmlTemplate {

    fun render(initialPairingCode: String = ""): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>ScreenSmithy &bull; Transfer HTML to TV</title>
  <style>
    :root {
      --bg-dark: #0a0e17;
      --card-bg: #141b2d;
      --border-color: #23304d;
      --accent: #38bdf8;
      --accent-hover: #0ea5e9;
      --accent-subtle: rgba(56, 189, 248, 0.12);
      --text-main: #f8fafc;
      --text-muted: #94a3b8;
      --success: #10b981;
      --error: #ef4444;
      --font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    }
    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
    }
    body {
      background-color: var(--bg-dark);
      color: var(--text-main);
      font-family: var(--font-family);
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 1.25rem;
    }
    .container {
      width: 100%;
      max-width: 480px;
      background: var(--card-bg);
      border: 1px solid var(--border-color);
      border-radius: 16px;
      box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.5), 0 8px 10px -6px rgba(0, 0, 0, 0.4);
      padding: 2rem 1.75rem;
    }
    .header {
      text-align: center;
      margin-bottom: 1.75rem;
    }
    .badge {
      display: inline-block;
      background: var(--accent-subtle);
      color: var(--accent);
      border: 1px solid rgba(56, 189, 248, 0.3);
      padding: 0.25rem 0.75rem;
      border-radius: 9999px;
      font-size: 0.8rem;
      font-weight: 600;
      letter-spacing: 0.05em;
      text-transform: uppercase;
      margin-bottom: 0.75rem;
    }
    h1 {
      font-size: 1.5rem;
      font-weight: 700;
      color: var(--text-main);
      margin-bottom: 0.5rem;
    }
    p.subtitle {
      color: var(--text-muted);
      font-size: 0.9rem;
      line-height: 1.4;
    }
    .form-group {
      margin-bottom: 1.25rem;
    }
    label {
      display: block;
      font-size: 0.85rem;
      font-weight: 600;
      color: var(--text-muted);
      margin-bottom: 0.5rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    input[type="text"] {
      width: 100%;
      background: #0d1322;
      border: 1px solid var(--border-color);
      border-radius: 10px;
      padding: 0.85rem 1rem;
      font-size: 1.25rem;
      font-family: monospace;
      color: var(--text-main);
      text-align: center;
      letter-spacing: 0.2em;
      transition: border-color 0.2s, box-shadow 0.2s;
    }
    input[type="text"]:focus {
      outline: none;
      border-color: var(--accent);
      box-shadow: 0 0 0 3px rgba(56, 189, 248, 0.2);
    }
    .file-dropzone {
      border: 2px dashed var(--border-color);
      border-radius: 12px;
      padding: 1.5rem 1rem;
      text-align: center;
      cursor: pointer;
      background: #0d1322;
      transition: border-color 0.2s, background 0.2s;
      position: relative;
    }
    .file-dropzone:hover, .file-dropzone.dragover {
      border-color: var(--accent);
      background: rgba(56, 189, 248, 0.04);
    }
    .file-dropzone input[type="file"] {
      position: absolute;
      top: 0;
      left: 0;
      width: 100%;
      height: 100%;
      opacity: 0;
      cursor: pointer;
    }
    .file-icon {
      font-size: 2rem;
      margin-bottom: 0.5rem;
    }
    .file-name-display {
      font-weight: 600;
      font-size: 0.95rem;
      color: var(--accent);
      word-break: break-all;
    }
    .file-meta {
      font-size: 0.8rem;
      color: var(--text-muted);
      margin-top: 0.35rem;
    }
    .btn {
      width: 100%;
      background: var(--accent);
      color: #07101e;
      font-weight: 700;
      font-size: 1rem;
      border: none;
      border-radius: 10px;
      padding: 0.95rem;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 0.5rem;
      transition: background 0.2s, transform 0.1s;
    }
    .btn:hover:not(:disabled) {
      background: var(--accent-hover);
    }
    .btn:active:not(:disabled) {
      transform: scale(0.99);
    }
    .btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .progress-container {
      display: none;
      margin-top: 1.25rem;
    }
    .progress-bar-bg {
      width: 100%;
      height: 8px;
      background: #1e293b;
      border-radius: 4px;
      overflow: hidden;
      margin-bottom: 0.5rem;
    }
    .progress-bar-fill {
      width: 0%;
      height: 100%;
      background: linear-gradient(90deg, #38bdf8, #0ea5e9);
      transition: width 0.15s ease-out;
    }
    .progress-status {
      display: flex;
      justify-content: space-between;
      font-size: 0.8rem;
      color: var(--text-muted);
    }
    .status-alert {
      display: none;
      margin-top: 1.25rem;
      padding: 0.9rem 1rem;
      border-radius: 10px;
      font-size: 0.9rem;
      line-height: 1.4;
    }
    .status-alert.success {
      display: block;
      background: rgba(16, 185, 129, 0.15);
      border: 1px solid rgba(16, 185, 129, 0.4);
      color: #6ee7b7;
    }
    .status-alert.error {
      display: block;
      background: rgba(239, 68, 68, 0.15);
      border: 1px solid rgba(239, 68, 68, 0.4);
      color: #fca5a5;
    }
    .info-box {
      margin-top: 1.5rem;
      padding: 0.85rem;
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.02);
      border: 1px solid rgba(255, 255, 255, 0.05);
      font-size: 0.78rem;
      color: var(--text-muted);
      line-height: 1.5;
    }
    .info-box strong {
      color: var(--text-main);
    }
  </style>
</head>
<body>
  <div class="container">
    <div class="header">
      <div class="badge">ScreenSmithy Transfer</div>
      <h1>Transfer HTML to TV</h1>
      <p class="subtitle">Upload a standalone HTML document to your Android TV kiosk display over local Wi-Fi.</p>
    </div>

    <form id="uploadForm" autocomplete="off">
      <div class="form-group">
        <label for="pairingCode">Pairing Code</label>
        <input type="text" id="pairingCode" maxlength="6" placeholder="000000" inputmode="numeric" required value="$initialPairingCode">
      </div>

      <div class="form-group">
        <label>Select HTML Document</label>
        <div class="file-dropzone" id="dropzone">
          <input type="file" id="fileInput" accept=".html,.htm,text/html">
          <div class="file-icon">&#128196;</div>
          <div class="file-name-display" id="fileLabel">Choose a .html or .htm file</div>
          <div class="file-meta" id="fileMeta">Maximum file size: 25 MB</div>
        </div>
      </div>

      <button type="submit" class="btn" id="btnUpload" disabled>
        <span>Send to TV</span> &rarr;
      </button>
    </form>

    <div class="progress-container" id="progressContainer">
      <div class="progress-bar-bg">
        <div class="progress-bar-fill" id="progressBar"></div>
      </div>
      <div class="progress-status">
        <span id="progressText">Uploading...</span>
        <span id="progressPercent">0%</span>
      </div>
    </div>

    <div class="status-alert" id="statusAlert"></div>

    <div class="info-box">
      <strong>Requirements:</strong> Your device must be connected to the same Wi-Fi network as the TV. Only self-contained HTML files (with embedded styles, scripts, and Base64 images) are supported.
    </div>
  </div>

  <script>
    const urlParams = new URLSearchParams(window.location.search);
    const codeParam = urlParams.get('code');
    const pairingInput = document.getElementById('pairingCode');
    if (codeParam && codeParam.trim().length === 6) {
      pairingInput.value = codeParam.trim();
    }

    const fileInput = document.getElementById('fileInput');
    const fileLabel = document.getElementById('fileLabel');
    const fileMeta = document.getElementById('fileMeta');
    const btnUpload = document.getElementById('btnUpload');
    const uploadForm = document.getElementById('uploadForm');
    const progressContainer = document.getElementById('progressContainer');
    const progressBar = document.getElementById('progressBar');
    const progressPercent = document.getElementById('progressPercent');
    const progressText = document.getElementById('progressText');
    const statusAlert = document.getElementById('statusAlert');

    const MAX_SIZE_BYTES = 25 * 1024 * 1024; // 25 MB

    function validateForm() {
      const codeValid = pairingInput.value.trim().length === 6;
      const file = fileInput.files[0];
      const fileValid = file && (file.name.endsWith('.html') || file.name.endsWith('.htm')) && file.size <= MAX_SIZE_BYTES && file.size > 0;
      btnUpload.disabled = !(codeValid && fileValid);
    }

    pairingInput.addEventListener('input', validateForm);

    fileInput.addEventListener('change', () => {
      const file = fileInput.files[0];
      if (!file) {
        fileLabel.textContent = 'Choose a .html or .htm file';
        fileMeta.textContent = 'Maximum file size: 25 MB';
        validateForm();
        return;
      }

      if (!file.name.toLowerCase().endsWith('.html') && !file.name.toLowerCase().endsWith('.htm')) {
        showError('Please select a file ending in .html or .htm.');
        fileInput.value = '';
        validateForm();
        return;
      }

      if (file.size > MAX_SIZE_BYTES) {
        showError('File exceeds the 25 MB maximum size limit (' + (file.size / (1024 * 1024)).toFixed(1) + ' MB).');
        fileInput.value = '';
        validateForm();
        return;
      }

      hideAlert();
      fileLabel.textContent = file.name;
      fileMeta.textContent = (file.size / 1024).toFixed(1) + ' KB';
      validateForm();
    });

    function showAlert(message, isSuccess) {
      statusAlert.className = 'status-alert ' + (isSuccess ? 'success' : 'error');
      statusAlert.textContent = message;
      statusAlert.style.display = 'block';
    }

    function showError(message) { showAlert(message, false); }
    function showSuccess(message) { showAlert(message, true); }
    function hideAlert() { statusAlert.style.display = 'none'; }

    uploadForm.addEventListener('submit', (e) => {
      e.preventDefault();
      const file = fileInput.files[0];
      const code = pairingInput.value.trim();
      if (!file || code.length !== 6) return;

      hideAlert();
      btnUpload.disabled = true;
      progressContainer.style.display = 'block';
      progressBar.style.width = '0%';
      progressPercent.textContent = '0%';
      progressText.textContent = 'Uploading...';

      const formData = new FormData();
      formData.append('file', file, file.name);
      formData.append('pairingCode', code);

      const xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/upload', true);
      xhr.setRequestHeader('X-Pairing-Code', code);

      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable) {
          const percent = Math.round((event.loaded / event.total) * 100);
          progressBar.style.width = percent + '%';
          progressPercent.textContent = percent + '%';
          progressText.textContent = 'Uploading (' + (event.loaded / 1024).toFixed(0) + ' KB of ' + (event.total / 1024).toFixed(0) + ' KB)...';
        }
      };

      xhr.onload = () => {
        btnUpload.disabled = false;
        if (xhr.status === 200) {
          try {
            const res = JSON.parse(xhr.responseText);
            if (res.success) {
              progressBar.style.width = '100%';
              progressPercent.textContent = '100%';
              progressText.textContent = 'Complete';
              showSuccess('File uploaded successfully! Check your TV screen to preview and activate the new content.');
              uploadForm.reset();
              validateForm();
            } else {
              showError(res.error || 'Upload failed.');
            }
          } catch (err) {
            showSuccess('Upload complete! Please confirm on your TV screen.');
          }
        } else {
          try {
            const res = JSON.parse(xhr.responseText);
            showError(res.error || 'Server returned status ' + xhr.status);
          } catch (_) {
            showError('Upload failed (HTTP ' + xhr.status + '). Please verify pairing code and Wi-Fi connection.');
          }
        }
      };

      xhr.onerror = () => {
        btnUpload.disabled = false;
        progressContainer.style.display = 'none';
        showError('Network error connecting to TV. Ensure your device is on the same local network.');
      };

      xhr.send(formData);
    });

    validateForm();
  </script>
</body>
</html>
""".trimIndent()
    }
}
