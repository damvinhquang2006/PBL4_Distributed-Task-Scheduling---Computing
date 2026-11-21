import React from 'react';
import { formatDurationMs } from '../utils/formatters';

/**
 * Bieu do cot SVG so sanh thoi gian thuc thi (ms) giua Tuan tu, 1 Worker va N Worker.
 */
export function ExecutionTimeBarChart({ items = [] }) {
  if (!items || items.length === 0) {
    return (
      <div className="empty-state" style={{ padding: 20 }}>
        Chưa có dữ liệu biểu đồ thời gian thực thi.
      </div>
    );
  }

  const width = 580;
  const height = 240;
  const padLeft = 56;
  const padRight = 20;
  const padTop = 26;
  const padBottom = 46;
  const plotW = width - padLeft - padRight;
  const plotH = height - padTop - padBottom;

  const maxVal = Math.max(1, ...items.map((d) => Number(d.timeMs) || 0));
  const barGap = 14;
  const barWidth = Math.min(64, Math.max(22, (plotW - barGap * (items.length - 1)) / items.length));
  const totalBarsW = items.length * barWidth + (items.length - 1) * barGap;
  const startX = padLeft + Math.max(0, (plotW - totalBarsW) / 2);

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      style={{ width: '100%', height: 'auto', display: 'block' }}
      role="img"
      aria-label="Biểu đồ thời gian thực thi"
    >
      {[0, 0.25, 0.5, 0.75, 1].map((ratio) => {
        const y = padTop + plotH * (1 - ratio);
        const val = Math.round(maxVal * ratio);
        return (
          <g key={ratio}>
            <line
              x1={padLeft}
              y1={y}
              x2={width - padRight}
              y2={y}
              stroke="var(--border-color)"
              strokeDasharray={ratio === 0 ? undefined : '3 3'}
            />
            <text
              x={padLeft - 8}
              y={y + 4}
              textAnchor="end"
              fill="var(--text-muted)"
              fontSize="11"
              fontFamily="var(--font-mono)"
            >
              {val}
            </text>
          </g>
        );
      })}

      {items.map((item, idx) => {
        const val = Number(item.timeMs) || 0;
        const barH = Math.max(2, (val / maxVal) * plotH);
        const x = startX + idx * (barWidth + barGap);
        const y = padTop + plotH - barH;
        const color = item.isSequential ? 'var(--warning)' : idx === items.length - 1 ? 'var(--success)' : 'var(--accent)';

        return (
          <g key={`${item.label}-${idx}`}>
            <rect x={x} y={y} width={barWidth} height={barH} rx="5" fill={color} opacity="0.9">
              <title>{`${item.label}: ${formatDurationMs(val)}`}</title>
            </rect>
            <text
              x={x + barWidth / 2}
              y={y - 6}
              textAnchor="middle"
              fill="var(--text-primary)"
              fontSize="11"
              fontFamily="var(--font-mono)"
            >
              {Math.round(val)}ms
            </text>
            <text
              x={x + barWidth / 2}
              y={height - 18}
              textAnchor="middle"
              fill="var(--text-secondary)"
              fontSize="11"
            >
              {item.label}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

/**
 * Bieu do SVG the hien Speedup (x) va Efficiency (%) theo so Worker.
 */
export function SpeedupEfficiencyChart({ points = [] }) {
  if (!points || points.length === 0) {
    return (
      <div className="empty-state" style={{ padding: 20 }}>
        Chưa có dữ liệu Speedup & Efficiency.
      </div>
    );
  }

  const width = 580;
  const height = 240;
  const padLeft = 50;
  const padRight = 52;
  const padTop = 28;
  const padBottom = 46;
  const plotW = width - padLeft - padRight;
  const plotH = height - padTop - padBottom;

  const maxSpeedup = Math.max(1.5, ...points.map((p) => Number(p.speedup) || 0));
  const maxEff = Math.max(100, ...points.map((p) => Number(p.efficiency) || 0));

  const xAt = (idx) => {
    if (points.length === 1) return padLeft + plotW / 2;
    return padLeft + (idx / (points.length - 1)) * plotW;
  };
  const ySpeedup = (val) => padTop + plotH - (Math.max(0, val) / maxSpeedup) * plotH;
  const yEff = (val) => padTop + plotH - (Math.max(0, val) / maxEff) * plotH;

  const speedupPath = points
    .map((p, idx) => `${idx === 0 ? 'M' : 'L'} ${xAt(idx)} ${ySpeedup(Number(p.speedup) || 0)}`)
    .join(' ');
  const effPath = points
    .map((p, idx) => `${idx === 0 ? 'M' : 'L'} ${xAt(idx)} ${yEff(Number(p.efficiency) || 0)}`)
    .join(' ');

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      style={{ width: '100%', height: 'auto', display: 'block' }}
      role="img"
      aria-label="Biểu đồ Speedup và Hiệu suất song song"
    >
      {/* Legend */}
      <g transform={`translate(${padLeft}, 12)`}>
        <circle cx="6" cy="0" r="4" fill="var(--accent)" />
        <text x="14" y="4" fill="var(--text-secondary)" fontSize="11">
          Speedup (x)
        </text>
        <circle cx="110" cy="0" r="4" fill="var(--success)" />
        <text x="118" y="4" fill="var(--text-secondary)" fontSize="11">
          Hiệu suất (%)
        </text>
      </g>

      {[0, 0.5, 1].map((ratio) => {
        const y = padTop + plotH * (1 - ratio);
        return (
          <g key={ratio}>
            <line
              x1={padLeft}
              y1={y}
              x2={width - padRight}
              y2={y}
              stroke="var(--border-color)"
              strokeDasharray={ratio === 0 ? undefined : '3 3'}
            />
            <text x={padLeft - 8} y={y + 4} textAnchor="end" fill="var(--accent)" fontSize="11" fontFamily="var(--font-mono)">
              {(maxSpeedup * ratio).toFixed(1)}x
            </text>
            <text
              x={width - padRight + 8}
              y={y + 4}
              textAnchor="start"
              fill="var(--success)"
              fontSize="11"
              fontFamily="var(--font-mono)"
            >
              {Math.round(maxEff * ratio)}%
            </text>
          </g>
        );
      })}

      {points.length > 1 && (
        <>
          <path d={speedupPath} fill="none" stroke="var(--accent)" strokeWidth="2.5" />
          <path d={effPath} fill="none" stroke="var(--success)" strokeWidth="2.5" strokeDasharray="5 3" />
        </>
      )}

      {points.map((p, idx) => {
        const cx = xAt(idx);
        const sy = ySpeedup(Number(p.speedup) || 0);
        const ey = yEff(Number(p.efficiency) || 0);
        return (
          <g key={`${p.workerCount}-${idx}`}>
            <circle cx={cx} cy={sy} r="5" fill="var(--accent)">
              <title>{`${p.workerCount} Worker: Speedup ${p.speedup}x`}</title>
            </circle>
            <text x={cx} y={sy - 9} textAnchor="middle" fill="var(--accent)" fontSize="11" fontFamily="var(--font-mono)">
              {Number(p.speedup).toFixed(2)}x
            </text>

            <circle cx={cx} cy={ey} r="4.5" fill="var(--success)">
              <title>{`${p.workerCount} Worker: Hiệu suất ${p.efficiency}%`}</title>
            </circle>
            <text x={cx} y={ey + 15} textAnchor="middle" fill="var(--success)" fontSize="10" fontFamily="var(--font-mono)">
              {Number(p.efficiency).toFixed(1)}%
            </text>

            <text x={cx} y={height - 16} textAnchor="middle" fill="var(--text-secondary)" fontSize="11">
              {p.workerCount} Worker
            </text>
          </g>
        );
      })}
    </svg>
  );
}
