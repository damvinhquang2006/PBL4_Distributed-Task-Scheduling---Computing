import React from 'react';
import { AlertTriangle } from 'lucide-react';

export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = 'Xác nhận',
  cancelLabel = 'Hủy bỏ',
  danger = false,
  onConfirm,
  onCancel,
}) {
  if (!open) return null;

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="confirm-dialog-title">
      <div className="modal-card">
        <h3 id="confirm-dialog-title" className="modal-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <AlertTriangle size={18} color={danger ? 'var(--danger)' : 'var(--warning)'} />
          {title}
        </h3>
        <p style={{ margin: 0, color: 'var(--text-secondary)', fontSize: 13 }}>{message}</p>
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onCancel}>
            {cancelLabel}
          </button>
          <button type="button" className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`} onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
