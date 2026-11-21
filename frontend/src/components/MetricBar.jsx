import React from 'react';
import { formatPercent, isValidMetric } from '../utils/formatters';

export function MetricBar({ label, value, compact = false }) {
  const valid = isValidMetric(value);
  const pct = valid ? Math.min(100, Math.max(0, Number(value))) : 0;

  let tone = '';
  if (valid) {
    if (pct >= 85) tone = 'danger';
    else if (pct >= 70) tone = 'warning';
    else tone = 'success';
  }

  return (
    <div className="metric-bar-row" style={compact ? { minWidth: 85 } : undefined}>
      <div className="metric-bar-head">
        <span>{label}</span>
        <span className="mono">{valid ? formatPercent(value) : 'Không rõ'}</span>
      </div>
      <div className="progress-track" aria-label={`${label}: ${formatPercent(value)}`}>
        <div
          className={`progress-fill ${tone}`}
          style={{ width: valid ? `${pct}%` : '0%', opacity: valid ? 1 : 0.25 }}
        />
      </div>
    </div>
  );
}
