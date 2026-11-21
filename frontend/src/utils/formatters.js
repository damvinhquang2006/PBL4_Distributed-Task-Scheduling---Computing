export function formatDurationMs(ms) {
  if (ms === null || ms === undefined || !Number.isFinite(Number(ms))) {
    return '—';
  }
  const num = Number(ms);
  if (num < 1000) {
    return `${Math.round(num * 100) / 100} ms`;
  }
  if (num < 60000) {
    return `${(num / 1000).toFixed(2)} s`;
  }
  const mins = Math.floor(num / 60000);
  const secs = ((num % 60000) / 1000).toFixed(1);
  return `${mins}m ${secs}s`;
}

export function formatPercent(value) {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) {
    return 'Không rõ (UNKNOWN)';
  }
  const num = Number(value);
  if (num < 0 || num > 100) {
    return 'Không rõ (UNKNOWN)';
  }
  return `${num.toFixed(1)}%`;
}

export function isValidMetric(value) {
  return value !== null && value !== undefined && Number.isFinite(Number(value)) && Number(value) >= 0 && Number(value) <= 100;
}

export function shortHash(hash, length = 8) {
  if (!hash || typeof hash !== 'string') return '—';
  if (hash.length <= length) return hash;
  return hash.slice(0, length);
}

export function formatTimestamp(ts) {
  if (!ts || !Number.isFinite(Number(ts))) return '—';
  return new Date(Number(ts)).toLocaleTimeString('vi-VN', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function formatRelativeTime(ts, now = Date.now()) {
  if (!ts || !Number.isFinite(Number(ts))) return 'Chưa có';
  const diffSec = Math.max(0, Math.round((now - Number(ts)) / 1000));
  if (diffSec < 3) return 'Vừa xong';
  if (diffSec < 60) return `${diffSec} giây trước`;
  const mins = Math.floor(diffSec / 60);
  if (mins < 60) return `${mins} phút trước`;
  const hours = Math.floor(mins / 60);
  return `${hours} giờ trước`;
}

export function formatNumber(val) {
  if (val === null || val === undefined) return '—';
  if (typeof val === 'number') {
    if (!Number.isFinite(val)) return String(val);
    if (Number.isInteger(val)) return val.toLocaleString('vi-VN');
    return Number(val.toFixed(4)).toString();
  }
  return String(val);
}

export function validateWorkerDisplayName(rawName) {
  if (typeof rawName !== 'string') {
    return { valid: false, error: 'Tên hiển thị phải là chuỗi ký tự.' };
  }
  const trimmed = rawName.trim();
  if (trimmed.length < 1 || trimmed.length > 64) {
    return { valid: false, error: 'Tên hiển thị phải dài từ 1 đến 64 ký tự.' };
  }
  // Check ISO control chars matching backend Character.isISOControl
  for (let i = 0; i < trimmed.length; i++) {
    const code = trimmed.charCodeAt(i);
    if (code <= 0x1f || (code >= 0x7f && code <= 0x9f)) {
      return { valid: false, error: 'Tên hiển thị không được chứa ký tự điều khiển.' };
    }
  }
  return { valid: true, value: trimmed, error: null };
}

export function generateUuid() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

function collectWorkerEntries(sources) {
  const entries = [];
  const visit = (src) => {
    if (!src) return;
    if (Array.isArray(src)) {
      for (const item of src) {
        visit(item);
      }
      return;
    }
    if (typeof src === 'object') {
      if (src.workerId) {
        entries.push(src);
      }
      if (Array.isArray(src.workers)) {
        for (const w of src.workers) entries.push(w);
      }
      if (Array.isArray(src.workerFleet)) {
        for (const w of src.workerFleet) entries.push(w);
      }
      if (Array.isArray(src.activeWorkers)) {
        for (const w of src.activeWorkers) entries.push(w);
      }
    }
  };
  visit(sources);
  return entries;
}

export function resolveWorkerLabel(workerId, sources, fallbackName) {
  if (workerId) {
    const entries = collectWorkerEntries(sources);
    for (const w of entries) {
      if (w && w.workerId === workerId && typeof w.displayName === 'string' && w.displayName.trim() !== '') {
        return w.displayName.trim();
      }
    }
  }
  if (typeof fallbackName === 'string' && fallbackName.trim() !== '') {
    return fallbackName.trim();
  }
  return workerId || '—';
}

export function formatWorkerDisplay(workerId, sources, fallbackName) {
  const label = resolveWorkerLabel(workerId, sources, fallbackName);
  if (!workerId || label === workerId) {
    return label;
  }
  return `${label} (${workerId})`;
}
