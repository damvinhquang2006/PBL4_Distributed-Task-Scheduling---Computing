import React from 'react';

const STATUS_MAP = {
  // Master / connection
  CONNECTED: { label: 'Đang kết nối', variant: 'success' },
  DISCONNECTED: { label: 'Mất kết nối Master', variant: 'danger' },

  // Worker single unified status
  ONLINE: { label: 'Online', variant: 'success' },
  BUSY: { label: 'Online', variant: 'success' },
  OFFLINE: { label: 'Offline', variant: 'danger' },
  READY: { label: 'Online', variant: 'success' },
  PAUSED: { label: 'Tạm dừng', variant: 'warning' },
  QUARANTINED: { label: 'Cách ly', variant: 'danger' },

  // Job status
  SUBMITTED: { label: 'Đã tiếp nhận', variant: 'neutral' },
  RECOVERING: { label: 'Đang khôi phục', variant: 'warning' },
  RUNNING: { label: 'Đang chạy', variant: 'info' },
  COMPLETED: { label: 'Hoàn thành', variant: 'success' },
  FAILED: { label: 'Thất bại', variant: 'danger' },
  CANCELLED: { label: 'Đã hủy', variant: 'neutral' },
};

export function getWorkerStatusKey(worker) {
  if (!worker || worker.status === 'OFFLINE') return 'OFFLINE';
  if (worker.quarantined) return 'QUARANTINED';
  if (worker.paused) return 'PAUSED';
  return 'ONLINE';
}

export function StatusBadge({ status, label, title }) {
  const key = String(status || '').toUpperCase();
  const mapped = STATUS_MAP[key] || { label: label || status || 'Không rõ', variant: 'neutral' };
  const text = label || mapped.label;

  return (
    <span className={`badge badge-${mapped.variant}`} title={title || text}>
      {text}
    </span>
  );
}
