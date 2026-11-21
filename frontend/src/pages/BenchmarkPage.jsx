import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Download, Gauge, Play, RotateCcw, Square, Trash2 } from 'lucide-react';
import { ExecutionTimeBarChart, SpeedupEfficiencyChart } from '../components/SvgCharts';
import { formatDurationMs, formatTimestamp, resolveWorkerLabel } from '../utils/formatters';
import { downloadTextFile } from '../utils/matrixHelpers';

const HISTORY_STORAGE_KEY = 'pbl4-benchmark-history';

const PROBLEM_TYPES = [
  { id: 'matrix_multiply', label: 'Nhân ma trận (matrix_multiply)', group: 'Ma trận', maxSize: 7071 },
  { id: 'matrix_add', label: 'Cộng ma trận (matrix_add)', group: 'Ma trận', maxSize: 7071 },
  { id: 'matrix_inverse', label: 'Nghịch đảo ma trận (matrix_inverse)', group: 'Ma trận', maxSize: 7071 },
  { id: 'matrix_transpose', label: 'Chuyển vị ma trận (matrix_transpose)', group: 'Ma trận', maxSize: 7071 },
  { id: 'matrix_det', label: 'Định thức ma trận (matrix_det)', group: 'Ma trận', maxSize: 7071 },
  { id: 'matrix_rank', label: 'Hạng ma trận (matrix_rank)', group: 'Ma trận', maxSize: 7071 },
  { id: 'graph_dijkstra', label: 'Đồ thị All-Pairs Dijkstra (graph_dijkstra)', group: 'Đồ thị', maxSize: 800 },
];

function loadHistory() {
  try {
    const raw = window.localStorage.getItem(HISTORY_STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export function BenchmarkPage({ cluster, api, onRefresh, onNotify }) {
  const [problemType, setProblemType] = useState('matrix_multiply');
  const [size, setSize] = useState(150);
  const [benchMode, setBenchMode] = useState('compare'); // 'compare' | 'single'
  const [selectedMilestones, setSelectedMilestones] = useState([1, 0]); // 0 = all online
  const [singleWorkerCount, setSingleWorkerCount] = useState(0);
  const [compareSequential, setCompareSequential] = useState(true);

  const [running, setRunning] = useState(false);
  const [stepMessage, setStepMessage] = useState('');
  const [benchError, setBenchError] = useState(null);
  const [currentRunResults, setCurrentRunResults] = useState([]);
  const [history, setHistory] = useState(loadHistory);
  const stopRequestedRef = useRef(false);

  useEffect(() => {
    try {
      window.localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(history.slice(0, 50)));
    } catch {
      // Ignore storage error
    }
  }, [history]);

  const onlineWorkers = cluster?.connectedWorkers ?? 0;

  const milestoneOptions = useMemo(() => {
    const base = [1, 2, 3, 4];
    if (onlineWorkers > 4) {
      for (const extra of [6, 8, 12, 16, 24, 32]) {
        if (extra < onlineWorkers && !base.includes(extra)) {
          base.push(extra);
        }
      }
    }
    return base;
  }, [onlineWorkers]);

  const formatRunWorkers = (item) => {
    if (!item) return null;
    if (Array.isArray(item.activeWorkers) && item.activeWorkers.length > 0) {
      return item.activeWorkers
        .map((aw) => resolveWorkerLabel(aw.workerId, cluster, aw.displayName))
        .join(', ');
    }
    if (Array.isArray(item.activeWorkerIds) && item.activeWorkerIds.length > 0) {
      return item.activeWorkerIds
        .map((id, idx) =>
          resolveWorkerLabel(
            id,
            cluster,
            Array.isArray(item.workerDisplayNames) ? item.workerDisplayNames[idx] : undefined
          )
        )
        .join(', ');
    }
    if (Array.isArray(item.workerDisplayNames) && item.workerDisplayNames.length > 0) {
      return item.workerDisplayNames.join(', ');
    }
    return null;
  };

  const selectedProblem = useMemo(
    () => PROBLEM_TYPES.find((p) => p.id === problemType) || PROBLEM_TYPES[0],
    [problemType]
  );

  const sizeError = useMemo(() => {
    const num = Number(size);
    if (!Number.isInteger(num) || num <= 0) {
      return 'Kích thước bài toán (size) phải là số nguyên dương lớn hơn 0.';
    }
    if (problemType === 'graph_dijkstra' && num > 800) {
      return 'Bài toán đồ thị Dijkstra hỗ trợ tối đa 800 đỉnh để đảm bảo hiệu năng.';
    }
    if (problemType !== 'graph_dijkstra' && num * num > 50_000_000) {
      return 'Kích thước ma trận tối đa 50.000.000 phần tử (tối đa 7071 × 7071).';
    }
    return null;
  }, [problemType, size]);

  // Compute deduplicated list of worker counts to execute in compare mode
  const effectiveSteps = useMemo(() => {
    if (benchMode === 'single') {
      return [Number(singleWorkerCount) || 0];
    }
    const maxW = Math.max(1, onlineWorkers);
    const resolved = new Set();
    for (const m of selectedMilestones) {
      if (m === 0) {
        resolved.add(maxW);
      } else if (m <= maxW) {
        resolved.add(m);
      }
    }
    if (resolved.size === 0) resolved.add(1);
    return Array.from(resolved).sort((a, b) => a - b);
  }, [benchMode, selectedMilestones, singleWorkerCount, onlineWorkers]);

  const toggleMilestone = (val) => {
    setSelectedMilestones((prev) => {
      if (prev.includes(val)) {
        return prev.length > 1 ? prev.filter((x) => x !== val) : prev;
      }
      return [...prev, val];
    });
  };

  const handleRestoreWorkers = async () => {
    try {
      await api.limitWorkers(0);
      await onRefresh();
      onNotify?.('Đã khôi phục toàn bộ Worker về trạng thái sẵn sàng.', 'success');
    } catch (err) {
      onNotify?.(err.message, 'danger');
    }
  };

  const handleRunBenchmark = async () => {
    if (sizeError) return;
    setRunning(true);
    setBenchError(null);
    setCurrentRunResults([]);
    stopRequestedRef.current = false;

    const steps = effectiveSteps;
    const collected = [];

    try {
      for (let i = 0; i < steps.length; i++) {
        if (stopRequestedRef.current) break;
        const wCount = steps[i];
        const labelCount = wCount === 0 ? `Toàn bộ (${onlineWorkers || 'tất cả'})` : `${wCount} Worker`;
        setStepMessage(`Đang đo mốc ${i + 1}/${steps.length}: ${labelCount}...`);

        const res = await api.runBenchmark({
          problemType,
          size: Number(size),
          workerCount: wCount,
          compareSequential,
        });

        collected.push(res);
        setCurrentRunResults([...collected]);
        setHistory((prev) => [res, ...prev].slice(0, 50));
      }

      if (stopRequestedRef.current) {
        onNotify?.('Đã dừng chuỗi benchmark theo yêu cầu.', 'warning');
      } else {
        onNotify?.('Hoàn thành thí nghiệm Benchmark!', 'success');
      }
    } catch (err) {
      setBenchError(err.message);
      onNotify?.(err.message, 'danger');
    } finally {
      setStepMessage('Đang khôi phục toàn bộ Worker...');
      try {
        await api.limitWorkers(0);
        await onRefresh();
      } catch {
        // Ignore restore error in finally; user still has manual restore button
      }
      setRunning(false);
      setStepMessage('');
    }
  };

  // Derive KPIs & chart series from currentRunResults
  const kpis = useMemo(() => {
    if (currentRunResults.length === 0) return null;
    const seqEntry = currentRunResults.find((r) => r.sequentialTimeMs != null);
    const oneWorker = currentRunResults.find((r) => r.workerCount === 1);
    const bestRun = currentRunResults.reduce((best, cur) =>
      cur.distributedTimeMs < best.distributedTimeMs ? cur : best
    );
    const maxSpeedupRun = currentRunResults.reduce((best, cur) =>
      (cur.speedup || 0) > (best.speedup || 0) ? cur : best
    );

    return {
      sequentialMs: seqEntry?.sequentialTimeMs ?? null,
      oneWorkerMs: oneWorker?.distributedTimeMs ?? null,
      bestDistMs: bestRun.distributedTimeMs,
      bestWorkers: bestRun.workerCount,
      maxSpeedup: maxSpeedupRun.speedup,
      bestEfficiency: bestRun.efficiency,
    };
  }, [currentRunResults]);

  const timeChartItems = useMemo(() => {
    if (currentRunResults.length === 0) return [];
    const items = [];
    const seq = currentRunResults.find((r) => r.sequentialTimeMs != null);
    if (seq) {
      items.push({ label: 'Tuần tự', timeMs: seq.sequentialTimeMs, isSequential: true });
    }
    for (const r of currentRunResults) {
      items.push({
        label: `${r.workerCount} Worker`,
        timeMs: r.distributedTimeMs,
        isSequential: false,
      });
    }
    return items;
  }, [currentRunResults]);

  const exportCsv = () => {
    if (history.length === 0) return;
    const header = 'timestamp,problemType,size,workerCount,sequentialTimeMs,distributedTimeMs,speedup,efficiency';
    const rows = history.map((h) =>
      [
        h.timestamp || '',
        h.problemType,
        h.size,
        h.workerCount,
        h.sequentialTimeMs ?? '',
        h.distributedTimeMs,
        h.speedup ?? '',
        h.efficiency ?? '',
      ].join(',')
    );
    downloadTextFile(`benchmark-history-${Date.now()}.csv`, [header, ...rows].join('\n'));
  };

  const exportJson = () => {
    if (history.length === 0) return;
    downloadTextFile(
      `benchmark-history-${Date.now()}.json`,
      JSON.stringify(history, null, 2),
      'application/json;charset=utf-8;'
    );
  };

  return (
    <div className="grid-2">
      {/* Left Column: Setup */}
      <div className="card">
        <div className="card-header">
          <div>
            <h2 className="card-title">
              <Gauge size={16} /> Thiết lập Thí nghiệm Benchmark
            </h2>
            <p className="card-subtitle">So sánh hiệu năng Tuần tự vs 1 Worker vs N Worker</p>
          </div>
        </div>

        <div className="form-group">
          <label className="form-label" htmlFor="bench-problem">1. Chọn bài toán</label>
          <select
            id="bench-problem"
            className="select"
            value={problemType}
            onChange={(e) => setProblemType(e.target.value)}
            disabled={running}
          >
            {PROBLEM_TYPES.map((p) => (
              <option key={p.id} value={p.id}>
                [{p.group}] {p.label}
              </option>
            ))}
          </select>
        </div>

        <div className="form-group">
          <label className="form-label" htmlFor="bench-size">
            <span>2. Kích thước bài toán (tối đa {selectedProblem.maxSize})</span>
          </label>
          <div className="chip-row" style={{ marginBottom: 6 }}>
            {[64, 150, 300, 500].map((preset) => (
              <button
                key={preset}
                type="button"
                className={`chip ${Number(size) === preset ? 'active' : ''}`}
                disabled={running}
                onClick={() => setSize(preset)}
              >
                {preset}
              </button>
            ))}
          </div>
          <input
            id="bench-size"
            type="number"
            min={1}
            max={selectedProblem.maxSize}
            className="input"
            value={size}
            onChange={(e) => setSize(Number(e.target.value))}
            disabled={running}
          />
          {sizeError && <div className="error-text">{sizeError}</div>}
        </div>

        <div className="form-group">
          <span className="form-label">3. Chế độ Benchmark</span>
          <div className="segmented" style={{ width: '100%' }}>
            <button
              type="button"
              className={`segmented-btn ${benchMode === 'compare' ? 'active' : ''}`}
              style={{ flex: 1, justifyContent: 'center' }}
              disabled={running}
              onClick={() => setBenchMode('compare')}
            >
              Tự động 1 vs N Worker
            </button>
            <button
              type="button"
              className={`segmented-btn ${benchMode === 'single' ? 'active' : ''}`}
              style={{ flex: 1, justifyContent: 'center' }}
              disabled={running}
              onClick={() => setBenchMode('single')}
            >
              Chạy 1 mốc tùy chọn
            </button>
          </div>
        </div>

        {benchMode === 'compare' ? (
          <div className="form-group">
            <span className="form-label">
              <span>Các mốc số lượng Worker ({onlineWorkers} online)</span>
              <span className="mono">{effectiveSteps.length} bước</span>
            </span>
            <div className="chip-row">
              {milestoneOptions.map((cnt) => (
                <button
                  key={cnt}
                  type="button"
                  className={`chip ${selectedMilestones.includes(cnt) ? 'active' : ''}`}
                  disabled={running || (onlineWorkers > 0 && cnt > onlineWorkers)}
                  onClick={() => toggleMilestone(cnt)}
                >
                  {cnt} Worker
                </button>
              ))}
              <button
                type="button"
                className={`chip ${selectedMilestones.includes(0) ? 'active' : ''}`}
                disabled={running}
                onClick={() => toggleMilestone(0)}
              >
                Tất cả online ({onlineWorkers})
              </button>
            </div>
            <div className="muted" style={{ fontSize: 11 }}>
              Sẽ chạy tuần tự qua các mốc: <strong className="mono">{effectiveSteps.join(', ')}</strong> Worker.
            </div>
          </div>
        ) : (
          <div className="form-group">
            <label className="form-label" htmlFor="bench-single-workers">
              Số lượng Worker tham gia (0 = toàn bộ online)
            </label>
            <input
              id="bench-single-workers"
              type="number"
              min={0}
              className="input"
              value={singleWorkerCount}
              onChange={(e) => setSingleWorkerCount(Number(e.target.value))}
              disabled={running}
            />
          </div>
        )}

        <label className="inline-control" style={{ marginBottom: 14, cursor: 'pointer' }}>
          <input
            type="checkbox"
            checked={compareSequential}
            onChange={(e) => setCompareSequential(e.target.checked)}
            disabled={running}
          />
          <span>So sánh chạy tuần tự trên Master (tính Speedup & Efficiency)</span>
        </label>

        <div className="hint-box" style={{ marginBottom: 14 }}>
          <strong>Lưu ý an toàn:</strong> Trong lúc benchmark N worker, hệ thống tạm giới hạn số worker hoạt động và sẽ{' '}
          <strong>TỰ ĐỘNG mở lại toàn bộ worker</strong> sau khi xong.
        </div>

        {benchError && (
          <div className="banner banner-danger" style={{ marginBottom: 12 }}>
            <span>{benchError}</span>
          </div>
        )}

        {running && stepMessage && (
          <div className="banner banner-info" style={{ marginBottom: 12 }}>
            <span>{stepMessage}</span>
          </div>
        )}

        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {!running ? (
            <button
              type="button"
              className="btn btn-primary"
              disabled={Boolean(sizeError) || onlineWorkers === 0}
              onClick={handleRunBenchmark}
            >
              <Play size={16} />
              <span>
                {onlineWorkers === 0 ? 'Cần ít nhất 1 Worker online' : 'Bắt đầu Benchmark'}
              </span>
            </button>
          ) : (
            <button
              type="button"
              className="btn btn-danger"
              onClick={() => {
                stopRequestedRef.current = true;
              }}
            >
              <Square size={15} />
              <span>Dừng chuỗi sau bước hiện tại</span>
            </button>
          )}

          <button type="button" className="btn btn-sm" disabled={running} onClick={handleRestoreWorkers}>
            <RotateCcw size={14} />
            <span>Khôi phục toàn bộ Worker</span>
          </button>
        </div>
      </div>

      {/* Right Column: KPIs, SVG Charts & History */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Kết quả Benchmark đợt hiện tại</h2>
          </div>

          {kpis ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
              <div className="kpi-grid">
                <div className="kpi-card">
                  <div className="kpi-label">Tuần tự (Sequential)</div>
                  <div className="kpi-value">{formatDurationMs(kpis.sequentialMs)}</div>
                </div>
                <div className="kpi-card">
                  <div className="kpi-label">1 Worker</div>
                  <div className="kpi-value">{formatDurationMs(kpis.oneWorkerMs)}</div>
                </div>
                <div className="kpi-card">
                  <div className="kpi-label">Nhanh nhất ({kpis.bestWorkers} Worker)</div>
                  <div className="kpi-value" style={{ color: 'var(--success)' }}>
                    {formatDurationMs(kpis.bestDistMs)}
                  </div>
                </div>
                <div className="kpi-card">
                  <div className="kpi-label">Speedup tối đa</div>
                  <div className="kpi-value" style={{ color: 'var(--accent)' }}>
                    {Number(kpis.maxSpeedup || 0).toFixed(2)}x
                  </div>
                </div>
                <div className="kpi-card">
                  <div className="kpi-label">Hiệu suất song song</div>
                  <div className="kpi-value">{Number(kpis.bestEfficiency || 0).toFixed(1)}%</div>
                </div>
              </div>

              <div className="grid-2-equal">
                <div className="card" style={{ background: 'var(--bg-surface-2)', padding: 12 }}>
                  <strong style={{ fontSize: 13, display: 'block', marginBottom: 8 }}>
                    Thời gian thực thi (ms) — Thấp hơn là tốt hơn
                  </strong>
                  <ExecutionTimeBarChart items={timeChartItems} />
                </div>
                <div className="card" style={{ background: 'var(--bg-surface-2)', padding: 12 }}>
                  <strong style={{ fontSize: 13, display: 'block', marginBottom: 8 }}>
                    Speedup (x) & Hiệu suất song song (%)
                  </strong>
                  <SpeedupEfficiencyChart points={currentRunResults} />
                </div>
              </div>

              {currentRunResults.some((r) => formatRunWorkers(r)) && (
                <div className="hint-box">
                  <strong style={{ display: 'block', marginBottom: 4 }}>Worker tham gia theo từng mốc:</strong>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                    {currentRunResults.map((r, idx) => {
                      const names = formatRunWorkers(r);
                      if (!names) return null;
                      return (
                        <div key={`${r.workerCount}-${idx}`} style={{ fontSize: 12 }}>
                          <span className="mono">{r.workerCount} Worker:</span> <strong>{names}</strong>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          ) : (
            <div className="empty-state">
              Chọn bài toán, kích thước và nhấn <strong>Bắt đầu Benchmark</strong> để xem biểu đồ so sánh 1 vs N Worker.
            </div>
          )}
        </div>

        {/* History Table */}
        <div className="card">
          <div className="card-header">
            <div>
              <h2 className="card-title">Lịch sử Benchmark ({history.length})</h2>
              <p className="card-subtitle">Lưu tự động trên trình duyệt (localStorage)</p>
            </div>
            <div className="chip-row">
              <button type="button" className="btn btn-sm" disabled={history.length === 0} onClick={exportCsv}>
                <Download size={13} /> Xuất CSV
              </button>
              <button type="button" className="btn btn-sm" disabled={history.length === 0} onClick={exportJson}>
                <Download size={13} /> Xuất JSON
              </button>
              <button
                type="button"
                className="btn btn-sm btn-danger"
                disabled={history.length === 0}
                onClick={() => setHistory([])}
              >
                <Trash2 size={13} /> Xóa lịch sử
              </button>
            </div>
          </div>

          {history.length === 0 ? (
            <div className="empty-state" style={{ padding: 20 }}>Chưa có bản ghi benchmark nào.</div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Thời điểm</th>
                    <th>Bài toán</th>
                    <th>Size</th>
                    <th>Workers</th>
                    <th>Tuần tự</th>
                    <th>Phân tán</th>
                    <th>Speedup</th>
                    <th>Hiệu suất</th>
                  </tr>
                </thead>
                <tbody>
                  {history.map((item, idx) => {
                    const workerNames = formatRunWorkers(item);
                    return (
                      <tr key={`${item.timestamp || idx}-${idx}`}>
                        <td className="mono muted">{formatTimestamp(item.timestamp)}</td>
                        <td className="mono">{item.problemType}</td>
                        <td className="mono">{item.size}</td>
                        <td>
                          <div className="mono">{item.workerCount}</div>
                          {workerNames && (
                            <div className="muted" style={{ fontSize: 11 }}>{workerNames}</div>
                          )}
                        </td>
                        <td className="mono">{formatDurationMs(item.sequentialTimeMs)}</td>
                        <td className="mono"><strong>{formatDurationMs(item.distributedTimeMs)}</strong></td>
                        <td className="mono">{item.speedup != null ? `${Number(item.speedup).toFixed(2)}x` : '—'}</td>
                        <td className="mono">{item.efficiency != null ? `${Number(item.efficiency).toFixed(1)}%` : '—'}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
