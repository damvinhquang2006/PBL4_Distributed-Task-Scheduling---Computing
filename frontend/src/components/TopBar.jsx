import React, { useEffect, useState } from 'react';
import { Cpu, Moon, RefreshCw, Sun } from 'lucide-react';
import { StatusBadge } from './StatusBadge';
import { shortHash } from '../utils/formatters';

export function TopBar({
  cluster,
  connected,
  apiPort,
  onApiPortChange,
  pollIntervalMs,
  onPollIntervalChange,
  theme,
  onToggleTheme,
  onManualRefresh,
}) {
  const [portDraft, setPortDraft] = useState(String(apiPort));

  useEffect(() => {
    setPortDraft(String(apiPort));
  }, [apiPort]);

  const commitPort = () => {
    const parsed = Number(portDraft);
    if (Number.isInteger(parsed) && parsed >= 1 && parsed <= 65535) {
      onApiPortChange(parsed);
    } else {
      setPortDraft(String(apiPort));
    }
  };

  const readyWorkers = cluster?.readyWorkers ?? 0;
  const totalWorkers = cluster?.totalWorkers ?? 0;
  const runningWorkers = cluster?.runningWorkers ?? 0;
  const effectiveParallelism = cluster?.effectiveParallelism ?? 0;
  const policy = cluster?.policy || '—';

  return (
    <div className="topbar">
      <div className="topbar-brand">
        <div className="brand-logo" aria-hidden="true">
          <Cpu size={18} />
        </div>
        <div>
          <h1 className="brand-title">PBL4 Master–Worker Console</h1>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 2 }}>
            <StatusBadge
              status={connected ? 'CONNECTED' : 'DISCONNECTED'}
              label={connected ? 'Đang kết nối' : 'Mất kết nối Master'}
            />
            {cluster?.masterSession && (
              <span className="mono muted" style={{ fontSize: 11 }} title={`Master Session: ${cluster.masterSession}`}>
                session: {shortHash(cluster.masterSession, 8)}
              </span>
            )}
          </div>
        </div>
      </div>

      <div className="topbar-kpis" aria-label="Chỉ số nhanh của cụm">
        <div className="kpi-pill">
          <span>Worker sẵn sàng:</span>
          <strong className="mono">
            {readyWorkers} / {totalWorkers}
          </strong>
        </div>
        <div className="kpi-pill">
          <span>Đang chạy:</span>
          <strong className="mono">{runningWorkers}</strong>
        </div>
        <div className="kpi-pill">
          <span>Hiệu năng cụm:</span>
          <strong className="mono">{effectiveParallelism} luồng</strong>
        </div>
        <div className="kpi-pill">
          <span>Chính sách:</span>
          <strong className="mono">{policy}</strong>
        </div>
      </div>

      <div className="topbar-controls">
        <label className="inline-control">
          <span>Cổng API:</span>
          <input
            type="number"
            min={1}
            max={65535}
            aria-label="Cổng API"
            className="input-sm port-input"
            value={portDraft}
            onChange={(e) => setPortDraft(e.target.value)}
            onBlur={commitPort}
            onKeyDown={(e) => {
              if (e.key === 'Enter') commitPort();
            }}
          />
        </label>

        <label className="inline-control">
          <span>Làm mới:</span>
          <select
            aria-label="Tốc độ làm mới"
            className="select-sm"
            value={String(pollIntervalMs)}
            onChange={(e) => onPollIntervalChange(Number(e.target.value))}
          >
            <option value="1000">1s</option>
            <option value="2000">2s</option>
            <option value="5000">5s</option>
            <option value="0">Tắt</option>
          </select>
        </label>

        <button
          type="button"
          className="btn btn-sm"
          onClick={onManualRefresh}
          title="Làm mới dữ liệu ngay"
          aria-label="Làm mới dữ liệu ngay"
        >
          <RefreshCw size={14} />
        </button>

        <button
          type="button"
          className="btn btn-sm"
          onClick={onToggleTheme}
          aria-label={theme === 'dark' ? 'Chuyển sang chế độ Sáng' : 'Chuyển sang chế độ Tối'}
        >
          {theme === 'dark' ? <Sun size={14} /> : <Moon size={14} />}
          <span>{theme === 'dark' ? 'Chế độ Sáng' : 'Chế độ Tối'}</span>
        </button>
      </div>
    </div>
  );
}
