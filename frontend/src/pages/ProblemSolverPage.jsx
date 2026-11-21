import React, { useEffect, useMemo, useState } from 'react';
import { ArrowRight, Calculator, ClipboardPaste, Play, RefreshCw, Sparkles, UploadCloud } from 'lucide-react';
import {
  MATRIX_OPERATIONS,
  createFilledMatrix,
  downloadTextFile,
  isUnaryOperation,
  matrixToCsv,
  parseMatrixText,
  resizeMatrix,
  validateMatrixDimensions,
} from '../utils/matrixHelpers';
import { formatDurationMs, formatNumber, generateUuid, resolveWorkerLabel } from '../utils/formatters';
import { StatusBadge, getWorkerStatusKey } from '../components/StatusBadge';

const MAX_FULL_MATRIX_DIM = 30;

function pickSampleIndices(total) {
  if (total <= 6) return Array.from({ length: total }, (_, i) => i);
  const set = new Set([0, 1, 2, Math.floor(total / 2), total - 3, total - 2, total - 1]);
  return Array.from(set).filter((v) => v >= 0 && v < total).sort((a, b) => a - b);
}

function MatrixResultFrame({
  rows,
  cols,
  matrix,
  sampleGrid,
  rowIndices,
  colIndices,
  distributedTimeMs,
  sequentialTimeMs,
  speedup,
  sequentialEstimated,
  totalTasks,
  workersCount,
  onOpenFull,
}) {
  const isFullEligible = rows > 0 && cols > 0 && rows <= MAX_FULL_MATRIX_DIM && cols <= MAX_FULL_MATRIX_DIM && Array.isArray(matrix);

  if (isFullEligible) {
    return (
      <div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8, flexWrap: 'wrap', gap: 8 }}>
          <span className="badge badge-success">
            Hiển thị đầy đủ {rows} × {cols} ({(rows * cols).toLocaleString('vi-VN')} phần tử)
          </span>
          <span className="muted" style={{ fontSize: 12 }}>Khung ma trận đầy đủ (tối đa 30 × 30)</span>
        </div>
        <div className="matrix-result-scroll">
          <table className="matrix-result-table">
            <thead>
              <tr>
                <th className="coord-head">r \ c</th>
                {Array.from({ length: cols }, (_, c) => (
                  <th key={c} className="coord-head">{c}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {matrix.map((row, rIdx) => (
                <tr key={rIdx}>
                  <td className="coord-head">{rIdx}</td>
                  {row.map((cell, cIdx) => (
                    <td key={cIdx}>{formatNumber(cell)}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    );
  }

  // Large Matrix Summary View (> 30 x 30)
  const sRows = Array.isArray(rowIndices) && rowIndices.length > 0 ? rowIndices : pickSampleIndices(rows || 0);
  const sCols = Array.isArray(colIndices) && colIndices.length > 0 ? colIndices : pickSampleIndices(cols || 0);
  const grid = Array.isArray(sampleGrid) && sampleGrid.length > 0
    ? sampleGrid
    : Array.isArray(matrix)
      ? sRows.map((r) => sCols.map((c) => matrix[r]?.[c]))
      : [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div className="banner banner-info">
        <div className="banner-body">
          <span>
            <strong>Khung Tóm lược Ma trận lớn ({rows} × {cols}):</strong> Tổng{' '}
            <strong className="mono">{(rows * cols).toLocaleString('vi-VN')}</strong> phần tử vượt ngưỡng hiển thị trực tiếp{' '}
            <span className="mono">30 × 30</span> — hệ thống tự động rút gọn chỉ còn các vùng góc &amp; tâm đại diện.
          </span>
        </div>
        {onOpenFull && (
          <button type="button" className="btn btn-sm btn-primary" onClick={onOpenFull}>
            <span>Duyệt phân trang đầy đủ (Tab 4)</span>
            <ArrowRight size={14} />
          </button>
        )}
      </div>

      <div className="kpi-grid">
        <div className="kpi-card">
          <div className="kpi-label">Kích thước</div>
          <div className="kpi-value">{rows} × {cols}</div>
          <div className="kpi-sub">{(rows * cols).toLocaleString('vi-VN')} phần tử</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-label">Thời gian đo đạc</div>
          <div className="kpi-value">{formatDurationMs(distributedTimeMs)}</div>
          <div className="kpi-sub">
            {sequentialTimeMs != null
              ? `Tuần tự: ${formatDurationMs(sequentialTimeMs)}${sequentialEstimated ? ' (ước lượng mẫu)' : ''}`
              : 'Chưa đo tuần tự'}
          </div>
        </div>
        <div className="kpi-card">
          <div className="kpi-label">Speed up</div>
          <div className="kpi-value">
            {speedup != null && Number.isFinite(Number(speedup)) ? `${Number(speedup).toFixed(2)}x` : '—'}
          </div>
          <div className="kpi-sub">
            {sequentialEstimated ? 'So với mẫu tuần tự' : 'Tuần tự / Phân tán'}
          </div>
        </div>
        <div className="kpi-card">
          <div className="kpi-label">Phân chia công việc</div>
          <div className="kpi-value">{totalTasks ?? '—'} tasks</div>
          <div className="kpi-sub">
            {workersCount != null ? `${workersCount} Worker hoạt động` : 'Theo Worker đang bật'}
          </div>
        </div>
      </div>

      {grid.length > 0 && (
        <div className="matrix-result-scroll">
          <table className="matrix-result-table">
            <thead>
              <tr>
                <th className="coord-head">r \ c</th>
                {sCols.map((cIdx, pos) => (
                  <React.Fragment key={`h-${cIdx}`}>
                    {pos > 0 && cIdx > sCols[pos - 1] + 1 && (
                      <th className="matrix-ellipsis" title="Các cột ẩn ở giữa">⋯</th>
                    )}
                    <th className="coord-head">{cIdx}</th>
                  </React.Fragment>
                ))}
              </tr>
            </thead>
            <tbody>
              {sRows.map((rIdx, rPos) => (
                <React.Fragment key={`r-${rIdx}`}>
                  {rPos > 0 && rIdx > sRows[rPos - 1] + 1 && (
                    <tr>
                      <td className="matrix-ellipsis">⋮</td>
                      {sCols.map((cIdx, cPos) => (
                        <React.Fragment key={`gap-${rIdx}-${cIdx}`}>
                          {cPos > 0 && cIdx > sCols[cPos - 1] + 1 && (
                            <td className="matrix-ellipsis">⋱</td>
                          )}
                          <td className="matrix-ellipsis">⋮</td>
                        </React.Fragment>
                      ))}
                    </tr>
                  )}
                  <tr>
                    <td className="coord-head">{rIdx}</td>
                    {sCols.map((cIdx, cPos) => (
                      <React.Fragment key={`c-${rIdx}-${cIdx}`}>
                        {cPos > 0 && cIdx > sCols[cPos - 1] + 1 && (
                          <td className="matrix-ellipsis">⋯</td>
                        )}
                        <td>{formatNumber(grid[rPos]?.[cPos])}</td>
                      </React.Fragment>
                    ))}
                  </tr>
                </React.Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

const STARTER_PRESETS = [
  { label: '(12 + 8) * (5 - 2)', expr1: '12+8', topOp: '*', expr2: '5-2' },
  { label: '(100 / 4) + (7 * 3)', expr1: '100/4', topOp: '+', expr2: '7*3' },
  { label: '(50 - 14) / (3 + 3)', expr1: '50-14', topOp: '/', expr2: '3+3' },
];

export function ProblemSolverPage({ cluster, api, onOpenJob, onNotify }) {
  const [mode, setMode] = useState('matrix'); // 'matrix' | 'starter'

  // Matrix Mode State
  const [scaleMode, setScaleMode] = useState('sync'); // 'sync' | 'async'
  const [operation, setOperation] = useState('x');
  const [scalar, setScalar] = useState(2);

  // Sync dimensions & matrices
  const [rowsA, setRowsA] = useState(3);
  const [colsA, setColsA] = useState(3);
  const [rowsB, setRowsB] = useState(3);
  const [colsB, setColsB] = useState(3);
  const [matrixA, setMatrixA] = useState(() => [
    [1, 2, 3],
    [4, 5, 6],
    [7, 8, 9],
  ]);
  const [matrixB, setMatrixB] = useState(() => [
    [9, 8, 7],
    [6, 5, 4],
    [3, 2, 1],
  ]);
  const [compareSequential, setCompareSequential] = useState(true);
  const [pasteTarget, setPasteTarget] = useState(null); // 'A' | 'B' | null
  const [pasteText, setPasteText] = useState('');
  const [pasteError, setPasteError] = useState(null);

  // Async dimensions & generators
  const [asyncRowsA, setAsyncRowsA] = useState(100);
  const [asyncColsA, setAsyncColsA] = useState(100);
  const [asyncRowsB, setAsyncRowsB] = useState(100);
  const [asyncColsB, setAsyncColsB] = useState(100);
  const [genTypeA, setGenTypeA] = useState('random-v1');
  const [genSeedA, setGenSeedA] = useState(42);
  const [genMinA, setGenMinA] = useState(1);
  const [genMaxA, setGenMaxA] = useState(10);
  const [genValA, setGenValA] = useState(1);
  const [genTypeB, setGenTypeB] = useState('random-v1');
  const [genSeedB, setGenSeedB] = useState(99);
  const [genMinB, setGenMinB] = useState(1);
  const [genMaxB, setGenMaxB] = useState(10);
  const [genValB, setGenValB] = useState(1);
  const [taskCount, setTaskCount] = useState(0);
  const [asyncCompareSequential, setAsyncCompareSequential] = useState(true);
  const [idempotencyKey, setIdempotencyKey] = useState(() => generateUuid());

  // Matrix execution state
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState(null);
  const [syncResult, setSyncResult] = useState(null);
  const [asyncJobId, setAsyncJobId] = useState(null);
  const [asyncProgress, setAsyncProgress] = useState(null);

  // Starter Mode State
  const [expr1, setExpr1] = useState('12+8');
  const [topOp, setTopOp] = useState('*');
  const [expr2, setExpr2] = useState('5-2');
  const [starterLoading, setStarterLoading] = useState(false);
  const [starterError, setStarterError] = useState(null);
  const [starterResult, setStarterResult] = useState(null);

  const unary = isUnaryOperation(operation);

  const readyWorkersList = useMemo(() => {
    const list = cluster?.workers || [];
    return list.filter((w) => w.status !== 'OFFLINE' && !w.paused && !w.quarantined);
  }, [cluster]);

  const validationError = useMemo(() => {
    if (scaleMode === 'sync') {
      return validateMatrixDimensions(operation, rowsA, colsA, rowsB, colsB, scalar);
    }
    return validateMatrixDimensions(operation, asyncRowsA, asyncColsA, asyncRowsB, asyncColsB, scalar);
  }, [scaleMode, operation, rowsA, colsA, rowsB, colsB, asyncRowsA, asyncColsA, asyncRowsB, asyncColsB, scalar]);

  // Poll async job progress while running on tab 1
  useEffect(() => {
    if (!asyncJobId) return undefined;
    let cancelled = false;
    let timer = null;

    const poll = async () => {
      try {
        const data = await api.getJobProgress(asyncJobId, { timeoutMs: 5000 });
        if (!cancelled) {
          setAsyncProgress(data);
          if (data?.status === 'RUNNING' || data?.status === 'RECOVERING' || data?.status === 'SUBMITTED') {
            timer = setTimeout(poll, 1000);
          }
        }
      } catch {
        if (!cancelled) {
          timer = setTimeout(poll, 2000);
        }
      }
    };
    poll();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [asyncJobId, api]);

  const updateSyncDims = (target, newR, newC) => {
    const r = Math.min(30, Math.max(1, Number(newR) || 1));
    const c = Math.min(30, Math.max(1, Number(newC) || 1));
    if (target === 'A') {
      setRowsA(r);
      setColsA(c);
      setMatrixA((prev) => resizeMatrix(prev, r, c));
    } else {
      setRowsB(r);
      setColsB(c);
      setMatrixB((prev) => resizeMatrix(prev, r, c));
    }
  };

  const applySyncPreset = (size) => {
    updateSyncDims('A', size, size);
    updateSyncDims('B', size, size);
  };

  const applyAsyncPreset = (size) => {
    setAsyncRowsA(size);
    setAsyncColsA(size);
    setAsyncRowsB(size);
    setAsyncColsB(size);
  };

  const handleCellChange = (target, rIdx, cIdx, val) => {
    const num = val === '' || val === '-' ? 0 : Number(val);
    const setter = target === 'A' ? setMatrixA : setMatrixB;
    setter((prev) =>
      prev.map((row, r) => (r === rIdx ? row.map((cell, c) => (c === cIdx ? (Number.isFinite(num) ? num : 0) : cell)) : row))
    );
  };

  const handleApplyPaste = () => {
    const { error, matrix } = parseMatrixText(pasteText);
    if (error) {
      setPasteError(error);
      return;
    }
    if (matrix.length > 30 || matrix[0].length > 30) {
      setPasteError('Chế độ nhập lưới đồng bộ hỗ trợ tối đa 30×30. Với ma trận lớn hơn, hãy chọn Quy mô lớn Bất đồng bộ.');
      return;
    }
    if (pasteTarget === 'A') {
      setRowsA(matrix.length);
      setColsA(matrix[0].length);
      setMatrixA(matrix);
    } else if (pasteTarget === 'B') {
      setRowsB(matrix.length);
      setColsB(matrix[0].length);
      setMatrixB(matrix);
    }
    setPasteTarget(null);
    setPasteText('');
    setPasteError(null);
  };

  const handleCompute = async () => {
    if (validationError) return;
    setSubmitting(true);
    setSubmitError(null);

    try {
      if (scaleMode === 'sync') {
        const res = await api.computeSync({
          operation,
          matrixA,
          matrixB: unary ? undefined : matrixB,
          scalar: operation === 'scalar' ? Number(scalar) : undefined,
          compareSequential,
        });
        setSyncResult(res);
        onNotify?.('Đã tính toán ma trận đồng bộ thành công.', 'success');
      } else {
        const payload = {
          operation,
          rowsA: Number(asyncRowsA),
          colsA: Number(asyncColsA),
          rowsB: unary ? 1 : Number(asyncRowsB),
          colsB: unary ? 1 : Number(asyncColsB),
          scalar: operation === 'scalar' ? Number(scalar) : 2.0,
          taskCount: Number(taskCount) || 0,
          compareSequential: Boolean(asyncCompareSequential),
          genA: {
            generator: genTypeA,
            seed: Number(genSeedA) || 42,
            min: Number(genMinA),
            max: Number(genMaxA),
            val: Number(genValA),
          },
          genB: {
            generator: genTypeB,
            seed: Number(genSeedB) || 99,
            min: Number(genMinB),
            max: Number(genMaxB),
            val: Number(genValB),
          },
        };
        const res = await api.computeAsync(payload, idempotencyKey);
        setAsyncJobId(res.jobId);
        setAsyncProgress({
          jobId: res.jobId,
          title: res.title,
          status: res.status,
          totalTasks: res.totalTasks,
          completedTasks: 0,
          progressPercent: 0,
        });
        setIdempotencyKey(generateUuid());
        onNotify?.(res.message || `Đã tiếp nhận job ${res.jobId}`, 'info');
      }
    } catch (err) {
      setSubmitError(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  const handleRunStarter = async (e) => {
    e.preventDefault();
    setStarterLoading(true);
    setStarterError(null);
    try {
      const res = await api.runStarter({ expr1, expr2, topOp });
      setStarterResult(res);
      onNotify?.('Đã giải biểu thức song song thành công.', 'success');
    } catch (err) {
      setStarterError(err.message);
    } finally {
      setStarterLoading(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* Top Mode Switcher */}
      <div className="card" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <div className="segmented" role="group" aria-label="Chọn loại bài toán">
            <button
              type="button"
              className={`segmented-btn ${mode === 'matrix' ? 'active' : ''}`}
              onClick={() => setMode('matrix')}
            >
              <Calculator size={15} />
              <span>Tính Toán Ma Trận</span>
            </button>
            <button
              type="button"
              className={`segmented-btn ${mode === 'starter' ? 'active' : ''}`}
              onClick={() => setMode('starter')}
            >
              <Sparkles size={15} />
              <span>Biểu Thức Số Học Song Song</span>
            </button>
          </div>
          <p className="card-subtitle" style={{ marginTop: 8 }}>
            {mode === 'matrix'
              ? 'Gửi phép tính ma trận nhỏ trực tiếp hoặc chia nhỏ ma trận tới 1000×1000 chạy phân tán trên cụm Worker.'
              : 'Minh họa luồng tách 2 biểu thức con sang các Worker tính song song rồi Master gộp kết quả cuối.'}
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }} aria-label="Worker sẵn sàng">
          <span className="muted" style={{ fontSize: 12 }}>
            Worker sẵn sàng ({readyWorkersList.length}):
          </span>
          {readyWorkersList.length === 0 ? (
            <span className="badge badge-warning">Chưa có Worker sẵn sàng</span>
          ) : (
            readyWorkersList.map((w) => {
              const label = resolveWorkerLabel(w.workerId, cluster, w.displayName);
              return (
                <span key={w.workerId} className="badge badge-success" title={w.workerId}>
                  {label}
                </span>
              );
            })
          )}
        </div>
      </div>

      {mode === 'matrix' ? (
        <div className="solver-stack">
          {/* Top Section: Config & Input (Full Width) */}
          <div className="card">
            <div className="card-header">
              <div>
                <h2 className="card-title">1. Cấu hình & Dữ liệu đầu vào</h2>
                <p className="card-subtitle">Chọn quy mô, phép toán và nhập ma trận (hiển thị đầy đủ tới 30×30)</p>
              </div>
            </div>

            {/* Scale Mode */}
            <div className="form-group">
              <span className="form-label">Quy mô thực thi</span>
              <div className="grid-2-equal">
                <button
                  type="button"
                  className={`policy-option ${scaleMode === 'sync' ? 'active' : ''}`}
                  onClick={() => {
                    setScaleMode('sync');
                    setSubmitError(null);
                  }}
                >
                  <div className="policy-option-title">
                    <span>Nhanh / Trực quan (≤ 30×30, Đồng bộ)</span>
                    <span className="mono muted" style={{ fontSize: 11 }}>POST /api/compute</span>
                  </div>
                  <div className="policy-option-desc">Nhập/dán từng ô trực tiếp tối đa 30×30, trả kết quả và Speedup ngay lập tức.</div>
                </button>
                <button
                  type="button"
                  className={`policy-option ${scaleMode === 'async' ? 'active' : ''}`}
                  onClick={() => {
                    setScaleMode('async');
                    setSubmitError(null);
                  }}
                >
                  <div className="policy-option-title">
                    <span>Quy mô lớn (tới 50.000.000 phần tử, Bất đồng bộ)</span>
                    <span className="mono muted" style={{ fontSize: 11 }}>POST /api/compute/async</span>
                  </div>
                  <div className="policy-option-desc">Sinh dữ liệu tự động tới 50M phần tử, tự chia việc theo Worker và tóm lược kết quả lớn.</div>
                </button>
              </div>
            </div>

            {/* Operation */}
            <div className="form-group">
              <label className="form-label" htmlFor="matrix-op-select">
                Phép toán
              </label>
              <select
                id="matrix-op-select"
                className="select"
                value={operation}
                onChange={(e) => setOperation(e.target.value)}
              >
                {MATRIX_OPERATIONS.map((op) => (
                  <option key={op.id} value={op.id}>
                    {op.label}
                  </option>
                ))}
              </select>
            </div>

            {operation === 'scalar' && (
              <div className="form-group">
                <label className="form-label" htmlFor="scalar-input">
                  Hệ số vô hướng (scalar)
                </label>
                <input
                  id="scalar-input"
                  type="number"
                  step="any"
                  className="input"
                  value={scalar}
                  onChange={(e) => setScalar(Number(e.target.value))}
                />
              </div>
            )}

            {unary && (
              <div className="hint-box" style={{ marginBottom: 12 }}>
                Phép toán 1 ngôi chỉ cần <strong>Ma trận A</strong> (Ma trận B tự động ẩn để tránh nhập nhầm).
              </div>
            )}

            {scaleMode === 'sync' ? (
              <>
                <div className="form-group">
                  <span className="form-label">Preset kích thước nhanh</span>
                  <div className="chip-row">
                    {[2, 3, 4, 6, 10, 20, 30].map((sz) => (
                      <button key={sz} type="button" className="chip" onClick={() => applySyncPreset(sz)}>
                        {sz}×{sz}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Matrix A Sync */}
                <div className="card" style={{ padding: 12, marginBottom: 12, background: 'var(--bg-surface-2)' }}>
                  <div className="card-header" style={{ marginBottom: 8 }}>
                    <strong style={{ fontSize: 13 }}>Ma trận A ({rowsA}×{colsA})</strong>
                    <div className="chip-row">
                      <button
                        type="button"
                        className="btn btn-sm"
                        onClick={() => setMatrixA(createFilledMatrix(rowsA, colsA, 'random'))}
                      >
                        Điền ngẫu nhiên
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm"
                        onClick={() => setMatrixA(createFilledMatrix(rowsA, colsA, 'identity'))}
                      >
                        Đơn vị
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm"
                        onClick={() => {
                          setPasteTarget('A');
                          setPasteError(null);
                          setPasteText('');
                        }}
                      >
                        <ClipboardPaste size={13} /> Dán CSV
                      </button>
                    </div>
                  </div>
                  <div className="form-row" style={{ marginBottom: 8 }}>
                    <label className="inline-control">
                      <span>Dòng:</span>
                      <input
                        type="number"
                        min={1}
                        max={30}
                        className="input-sm port-input"
                        value={rowsA}
                        onChange={(e) => updateSyncDims('A', e.target.value, colsA)}
                      />
                    </label>
                    <label className="inline-control">
                      <span>Cột:</span>
                      <input
                        type="number"
                        min={1}
                        max={30}
                        className="input-sm port-input"
                        value={colsA}
                        onChange={(e) => updateSyncDims('A', rowsA, e.target.value)}
                      />
                    </label>
                  </div>
                  <div className="matrix-scroll">
                    <div className="matrix-grid" style={{ gridTemplateColumns: `repeat(${colsA}, 56px)` }}>
                      {matrixA.map((row, rIdx) =>
                        row.map((val, cIdx) => (
                          <input
                            key={`a-${rIdx}-${cIdx}`}
                            type="number"
                            step="any"
                            aria-label={`A[${rIdx}][${cIdx}]`}
                            className="matrix-cell"
                            value={val}
                            onChange={(e) => handleCellChange('A', rIdx, cIdx, e.target.value)}
                          />
                        ))
                      )}
                    </div>
                  </div>
                </div>

                {/* Matrix B Sync */}
                {!unary && (
                  <div className="card" style={{ padding: 12, marginBottom: 12, background: 'var(--bg-surface-2)' }}>
                    <div className="card-header" style={{ marginBottom: 8 }}>
                      <strong style={{ fontSize: 13 }}>Ma trận B ({rowsB}×{colsB})</strong>
                      <div className="chip-row">
                        <button
                          type="button"
                          className="btn btn-sm"
                          onClick={() => setMatrixB(createFilledMatrix(rowsB, colsB, 'random'))}
                        >
                          Điền ngẫu nhiên
                        </button>
                        <button
                          type="button"
                          className="btn btn-sm"
                          onClick={() => setMatrixB(createFilledMatrix(rowsB, colsB, 'identity'))}
                        >
                          Đơn vị
                        </button>
                        <button
                          type="button"
                          className="btn btn-sm"
                          onClick={() => {
                            setPasteTarget('B');
                            setPasteError(null);
                            setPasteText('');
                          }}
                        >
                          <ClipboardPaste size={13} /> Dán CSV
                        </button>
                      </div>
                    </div>
                    <div className="form-row" style={{ marginBottom: 8 }}>
                      <label className="inline-control">
                        <span>Dòng:</span>
                        <input
                          type="number"
                          min={1}
                          max={30}
                          className="input-sm port-input"
                          value={rowsB}
                          onChange={(e) => updateSyncDims('B', e.target.value, colsB)}
                        />
                      </label>
                      <label className="inline-control">
                        <span>Cột:</span>
                        <input
                          type="number"
                          min={1}
                          max={30}
                          className="input-sm port-input"
                          value={colsB}
                          onChange={(e) => updateSyncDims('B', rowsB, e.target.value)}
                        />
                      </label>
                    </div>
                    <div className="matrix-scroll">
                      <div className="matrix-grid" style={{ gridTemplateColumns: `repeat(${colsB}, 56px)` }}>
                        {matrixB.map((row, rIdx) =>
                          row.map((val, cIdx) => (
                            <input
                              key={`b-${rIdx}-${cIdx}`}
                              type="number"
                              step="any"
                              aria-label={`B[${rIdx}][${cIdx}]`}
                              className="matrix-cell"
                              value={val}
                              onChange={(e) => handleCellChange('B', rIdx, cIdx, e.target.value)}
                            />
                          ))
                        )}
                      </div>
                    </div>
                  </div>
                )}

                <label className="inline-control" style={{ marginBottom: 12, cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={compareSequential}
                    onChange={(e) => setCompareSequential(e.target.checked)}
                  />
                  <span>So sánh với chạy tuần tự trên Master (đo Speedup)</span>
                </label>
              </>
            ) : (
              <>
                <div className="form-group">
                  <span className="form-label">Preset kích thước lớn (tối đa 50.000.000 phần tử ≈ 7071×7071)</span>
                  <div className="chip-row">
                    {[100, 500, 1000, 3000, 7071].map((sz) => (
                      <button key={sz} type="button" className="chip" onClick={() => applyAsyncPreset(sz)}>
                        {sz}×{sz}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="form-row" style={{ marginBottom: 12 }}>
                  <div className="form-group" style={{ flex: 1, minWidth: 90, marginBottom: 0 }}>
                    <label className="form-label" htmlFor="async-rows-a">Dòng A</label>
                    <input
                      id="async-rows-a"
                      type="number"
                      min={1}
                      max={1000000}
                      className="input"
                      value={asyncRowsA}
                      onChange={(e) => setAsyncRowsA(Number(e.target.value))}
                    />
                  </div>
                  <div className="form-group" style={{ flex: 1, minWidth: 90, marginBottom: 0 }}>
                    <label className="form-label" htmlFor="async-cols-a">Cột A</label>
                    <input
                      id="async-cols-a"
                      type="number"
                      min={1}
                      max={1000000}
                      className="input"
                      value={asyncColsA}
                      onChange={(e) => setAsyncColsA(Number(e.target.value))}
                    />
                  </div>
                  {!unary && (
                    <>
                      <div className="form-group" style={{ flex: 1, minWidth: 90, marginBottom: 0 }}>
                        <label className="form-label" htmlFor="async-rows-b">Dòng B</label>
                        <input
                          id="async-rows-b"
                          type="number"
                          min={1}
                          max={1000000}
                          className="input"
                          value={asyncRowsB}
                          onChange={(e) => setAsyncRowsB(Number(e.target.value))}
                        />
                      </div>
                      <div className="form-group" style={{ flex: 1, minWidth: 90, marginBottom: 0 }}>
                        <label className="form-label" htmlFor="async-cols-b">Cột B</label>
                        <input
                          id="async-cols-b"
                          type="number"
                          min={1}
                          max={1000000}
                          className="input"
                          value={asyncColsB}
                          onChange={(e) => setAsyncColsB(Number(e.target.value))}
                        />
                      </div>
                    </>
                  )}
                </div>

                {/* Generator A */}
                <div className="card" style={{ padding: 12, marginBottom: 12, background: 'var(--bg-surface-2)' }}>
                  <strong style={{ fontSize: 13, display: 'block', marginBottom: 8 }}>Bộ sinh Ma trận A</strong>
                  <div className="form-row">
                    <div className="form-group" style={{ flex: 2, minWidth: 130, marginBottom: 0 }}>
                      <label className="form-label">Loại sinh</label>
                      <select className="select" value={genTypeA} onChange={(e) => setGenTypeA(e.target.value)}>
                        <option value="random-v1">Ngẫu nhiên (random-v1)</option>
                        <option value="identity-v1">Đơn vị (identity-v1)</option>
                        <option value="constant-v1">Hằng số (constant-v1)</option>
                        <option value="sparse-v1">Thưa 90% số 0 (sparse-v1)</option>
                      </select>
                    </div>
                    <div className="form-group" style={{ flex: 1, minWidth: 80, marginBottom: 0 }}>
                      <label className="form-label">Seed</label>
                      <input type="number" className="input" value={genSeedA} onChange={(e) => setGenSeedA(e.target.value)} />
                    </div>
                  </div>
                  {genTypeA === 'constant-v1' ? (
                    <div className="form-group" style={{ marginTop: 8, marginBottom: 0 }}>
                      <label className="form-label">Giá trị hằng số (val)</label>
                      <input type="number" step="any" className="input" value={genValA} onChange={(e) => setGenValA(e.target.value)} />
                    </div>
                  ) : (genTypeA === 'random-v1' || genTypeA === 'sparse-v1') && (
                    <div className="form-row" style={{ marginTop: 8 }}>
                      <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
                        <label className="form-label">Min</label>
                        <input type="number" step="any" className="input" value={genMinA} onChange={(e) => setGenMinA(e.target.value)} />
                      </div>
                      <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
                        <label className="form-label">Max</label>
                        <input type="number" step="any" className="input" value={genMaxA} onChange={(e) => setGenMaxA(e.target.value)} />
                      </div>
                    </div>
                  )}
                </div>

                {/* Generator B */}
                {!unary && (
                  <div className="card" style={{ padding: 12, marginBottom: 12, background: 'var(--bg-surface-2)' }}>
                    <strong style={{ fontSize: 13, display: 'block', marginBottom: 8 }}>Bộ sinh Ma trận B</strong>
                    <div className="form-row">
                      <div className="form-group" style={{ flex: 2, minWidth: 130, marginBottom: 0 }}>
                        <label className="form-label">Loại sinh</label>
                        <select className="select" value={genTypeB} onChange={(e) => setGenTypeB(e.target.value)}>
                          <option value="random-v1">Ngẫu nhiên (random-v1)</option>
                          <option value="identity-v1">Đơn vị (identity-v1)</option>
                          <option value="constant-v1">Hằng số (constant-v1)</option>
                          <option value="sparse-v1">Thưa 90% số 0 (sparse-v1)</option>
                        </select>
                      </div>
                      <div className="form-group" style={{ flex: 1, minWidth: 80, marginBottom: 0 }}>
                        <label className="form-label">Seed</label>
                        <input type="number" className="input" value={genSeedB} onChange={(e) => setGenSeedB(e.target.value)} />
                      </div>
                    </div>
                    {genTypeB === 'constant-v1' ? (
                      <div className="form-group" style={{ marginTop: 8, marginBottom: 0 }}>
                        <label className="form-label">Giá trị hằng số (val)</label>
                        <input type="number" step="any" className="input" value={genValB} onChange={(e) => setGenValB(e.target.value)} />
                      </div>
                    ) : (genTypeB === 'random-v1' || genTypeB === 'sparse-v1') && (
                      <div className="form-row" style={{ marginTop: 8 }}>
                        <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
                          <label className="form-label">Min</label>
                          <input type="number" step="any" className="input" value={genMinB} onChange={(e) => setGenMinB(e.target.value)} />
                        </div>
                        <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
                          <label className="form-label">Max</label>
                          <input type="number" step="any" className="input" value={genMaxB} onChange={(e) => setGenMaxB(e.target.value)} />
                        </div>
                      </div>
                    )}
                  </div>
                )}

                <div className="form-row" style={{ marginBottom: 12 }}>
                  <div className="form-group" style={{ flex: 1, minWidth: 140, marginBottom: 0 }}>
                    <label className="form-label" htmlFor="task-count-input">Số tác vụ (0 = Tự động)</label>
                    <input
                      id="task-count-input"
                      type="number"
                      min={0}
                      max={1024}
                      className="input"
                      value={taskCount}
                      onChange={(e) => setTaskCount(Number(e.target.value))}
                    />
                  </div>
                  <div className="form-group" style={{ flex: 2, minWidth: 180, marginBottom: 0 }}>
                    <label className="form-label">
                      <span>Idempotency-Key</span>
                      <button
                        type="button"
                        className="btn btn-sm"
                        style={{ padding: '2px 6px' }}
                        onClick={() => setIdempotencyKey(generateUuid())}
                        title="Sinh khóa mới"
                      >
                        <RefreshCw size={12} /> Làm mới
                      </button>
                    </label>
                    <input
                      type="text"
                      className="input mono"
                      style={{ fontSize: 11 }}
                      value={idempotencyKey}
                      onChange={(e) => setIdempotencyKey(e.target.value)}
                    />
                  </div>
                </div>

                <label className="inline-control" style={{ marginBottom: 12 }}>
                  <input
                    type="checkbox"
                    checked={asyncCompareSequential}
                    onChange={(e) => setAsyncCompareSequential(e.target.checked)}
                  />
                  <span>Đo thời gian tuần tự để tính Speed up (tự động hiệu chuẩn mẫu khi ma trận &gt; 1M phần tử)</span>
                </label>
              </>
            )}

            {validationError && (
              <div className="banner banner-warning" style={{ marginBottom: 12 }} role="alert">
                <span>{validationError}</span>
              </div>
            )}

            {submitError && (
              <div className="banner banner-danger" style={{ marginBottom: 12 }} role="alert">
                <span>{submitError}</span>
              </div>
            )}

            <button
              type="button"
              className="btn btn-primary"
              style={{ width: '100%' }}
              disabled={Boolean(validationError) || submitting}
              onClick={handleCompute}
            >
              <Play size={16} />
              <span>
                {submitting
                  ? 'Đang gửi tới Master...'
                  : scaleMode === 'sync'
                    ? 'Thực thi Đồng bộ ngay'
                    : 'Gửi Job Bất đồng bộ (Chạy nền)'}
              </span>
            </button>
          </div>

          {/* Right Column: Results & Live Progress */}
          <div className="card">
            <div className="card-header">
              <div>
                <h2 className="card-title">2. Kết quả & Tiến độ thực thi</h2>
                <p className="card-subtitle">
                  {scaleMode === 'sync' ? 'Kết quả tính toán ma trận đồng bộ' : 'Theo dõi job bất đồng bộ theo thời gian thực'}
                </p>
              </div>
              {scaleMode === 'sync' && syncResult && !syncResult.isScalar && Array.isArray(syncResult.result) && (
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => downloadTextFile(`matrix-result-${Date.now()}.csv`, matrixToCsv(syncResult.result))}
                >
                  <UploadCloud size={14} /> Tải CSV
                </button>
              )}
            </div>

            {scaleMode === 'sync' ? (
              syncResult ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                  <div className="kpi-grid">
                    <div className="kpi-card">
                      <div className="kpi-label">Thời gian phân tán</div>
                      <div className="kpi-value">{formatDurationMs(syncResult.distributedTimeMs)}</div>
                    </div>
                    <div className="kpi-card">
                      <div className="kpi-label">Thời gian tuần tự</div>
                      <div className="kpi-value">{formatDurationMs(syncResult.sequentialTimeMs)}</div>
                    </div>
                    <div className="kpi-card">
                      <div className="kpi-label">Speedup</div>
                      <div className="kpi-value">
                        {syncResult.speedup != null ? `${Number(syncResult.speedup).toFixed(2)}x` : '—'}
                      </div>
                    </div>
                    <div className="kpi-card">
                      <div className="kpi-label">Worker sẵn sàng</div>
                      <div className="kpi-value">{syncResult.workersOnline ?? '—'}</div>
                    </div>
                  </div>

                  {syncResult.isScalar ? (
                    <div className="card" style={{ background: 'var(--bg-surface-2)', textAlign: 'center' }}>
                      <div className="muted" style={{ fontSize: 12 }}>Kết quả vô hướng ({syncResult.operation})</div>
                      <div className="mono" style={{ fontSize: 28, fontWeight: 700, marginTop: 4 }}>
                        {formatNumber(syncResult.resultScalar)}
                      </div>
                    </div>
                  ) : (
                    <MatrixResultFrame
                      rows={syncResult.rows ?? syncResult.result?.length ?? 0}
                      cols={syncResult.cols ?? syncResult.result?.[0]?.length ?? 0}
                      matrix={syncResult.result}
                      distributedTimeMs={syncResult.distributedTimeMs}
                      sequentialTimeMs={syncResult.sequentialTimeMs}
                      speedup={syncResult.speedup}
                      workersCount={syncResult.workersOnline}
                    />
                  )}
                </div>
              ) : (
                <div className="empty-state">
                  Chọn phép toán và nhấn <strong>Thực thi Đồng bộ ngay</strong> để xem ma trận kết quả và Speedup.
                </div>
              )
            ) : asyncProgress ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap' }}>
                  <div>
                    <div style={{ fontWeight: 600 }}>{asyncProgress.title}</div>
                    <div className="mono muted" style={{ fontSize: 12 }}>{asyncProgress.jobId}</div>
                  </div>
                  <StatusBadge status={asyncProgress.status} />
                </div>

                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 6 }}>
                    <span>Tiến độ: {asyncProgress.completedTasks ?? 0} / {asyncProgress.totalTasks ?? 0} tasks</span>
                    <strong className="mono">{Number(asyncProgress.progressPercent || 0).toFixed(1)}%</strong>
                  </div>
                  <div className="progress-track">
                    <div
                      className={`progress-fill ${asyncProgress.status === 'COMPLETED' ? 'success' : asyncProgress.status === 'FAILED' ? 'danger' : ''}`}
                      style={{ width: `${Math.min(100, Number(asyncProgress.progressPercent || 0))}%` }}
                    />
                  </div>
                </div>

                <div className="kpi-grid">
                  <div className="kpi-card">
                    <div className="kpi-label">Đã chạy</div>
                    <div className="kpi-value">{formatDurationMs(asyncProgress.elapsedMs)}</div>
                  </div>
                  <div className="kpi-card">
                    <div className="kpi-label">Ước tính còn lại (ETA)</div>
                    <div className="kpi-value">{formatDurationMs(asyncProgress.etaMs)}</div>
                  </div>
                </div>

                {asyncProgress.waitingReason && (
                  <div className="banner banner-warning">
                    <span>{asyncProgress.waitingReason}</span>
                  </div>
                )}

                {asyncProgress.error && (
                  <div className="banner banner-danger">
                    <span>{asyncProgress.error}</span>
                  </div>
                )}

                {Array.isArray(asyncProgress.workerFleet) && asyncProgress.workerFleet.length > 0 && (
                  <div className="table-wrap">
                    <table>
                      <thead>
                        <tr>
                          <th>Worker</th>
                          <th>Trạng thái</th>
                          <th>Task đang giữ</th>
                          <th>Đã xong</th>
                        </tr>
                      </thead>
                      <tbody>
                        {asyncProgress.workerFleet.map((w) => {
                          const label = resolveWorkerLabel(w.workerId, [cluster, asyncProgress.workerFleet], w.displayName);
                          const clusterWorker = (cluster?.workers || []).find((cw) => cw.workerId === w.workerId);
                          const heldTasks = w.assignedTask
                            ? [w.assignedTask]
                            : Array.isArray(clusterWorker?.currentTaskIds)
                              ? clusterWorker.currentTaskIds
                              : [];
                          return (
                            <tr key={w.workerId}>
                              <td>
                                <strong>{label}</strong>
                                {label !== w.workerId && (
                                  <div className="mono muted" style={{ fontSize: 11 }}>{w.workerId}</div>
                                )}
                              </td>
                              <td>
                                <StatusBadge status={getWorkerStatusKey(w)} />
                              </td>
                              <td className="mono">
                                {heldTasks.length > 0 ? (
                                  <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }} title={heldTasks.join(', ')}>
                                    {heldTasks.slice(0, 2).map((tid) => (
                                      <span key={tid} className="badge badge-info mono">{tid}</span>
                                    ))}
                                    {heldTasks.length > 2 && (
                                      <span className="badge badge-neutral mono">+{heldTasks.length - 2}</span>
                                    )}
                                  </div>
                                ) : w.activeTasks > 0 ? (
                                  <span className="badge badge-info mono">Đang chạy {w.activeTasks} task</span>
                                ) : (
                                  <span className="muted">—</span>
                                )}
                              </td>
                              <td className="mono"><strong>{w.jobCompletedTasks ?? 0}</strong></td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                )}

                {asyncProgress.status === 'COMPLETED' && asyncProgress.resultSummary && (
                  asyncProgress.resultSummary.isScalar ? (
                    <div className="card" style={{ background: 'var(--bg-surface-2)', textAlign: 'center' }}>
                      <div className="muted" style={{ fontSize: 12 }}>Kết quả vô hướng ({asyncProgress.operation})</div>
                      <div className="mono" style={{ fontSize: 28, fontWeight: 700, marginTop: 4 }}>
                        {formatNumber(asyncProgress.resultSummary.resultScalar)}
                      </div>
                    </div>
                  ) : (
                    <MatrixResultFrame
                      rows={asyncProgress.resultSummary.rows ?? 0}
                      cols={asyncProgress.resultSummary.cols ?? 0}
                      matrix={asyncProgress.resultSummary.result}
                      sampleGrid={asyncProgress.resultSummary.sampleGrid}
                      rowIndices={asyncProgress.resultSummary.rowIndices}
                      colIndices={asyncProgress.resultSummary.colIndices}
                      distributedTimeMs={asyncProgress.resultSummary.distributedTimeMs ?? asyncProgress.durationMs ?? asyncProgress.elapsedMs}
                      sequentialTimeMs={asyncProgress.resultSummary.sequentialTimeMs}
                      speedup={asyncProgress.resultSummary.speedup}
                      sequentialEstimated={asyncProgress.resultSummary.sequentialEstimated}
                      totalTasks={asyncProgress.resultSummary.totalChunks ?? asyncProgress.totalTasks}
                      workersCount={asyncProgress.resultSummary.workerIds?.length ?? asyncProgress.resultSummary.workersOnline}
                      onOpenFull={() => onOpenJob?.(asyncProgress.jobId)}
                    />
                  )
                )}

                {!asyncProgress.workerFleet?.length &&
                  Array.isArray(asyncProgress.resultSummary?.activeWorkers || asyncProgress.resultSummary?.workerIds) && (
                    <div className="hint-box">
                      Worker tham gia:{' '}
                      <strong>
                        {(asyncProgress.resultSummary.activeWorkers || asyncProgress.resultSummary.workerIds)
                          .map((item, idx) => {
                            const id = typeof item === 'string' ? item : item?.workerId;
                            const fallback =
                              typeof item === 'object'
                                ? item?.displayName
                                : asyncProgress.resultSummary?.workerDisplayNames?.[idx];
                            return resolveWorkerLabel(id, cluster, fallback);
                          })
                          .join(', ')}
                      </strong>
                    </div>
                  )}

                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={() => onOpenJob?.(asyncProgress.jobId)}
                >
                  <span>Mở chi tiết & Duyệt kết quả tại Tab 4</span>
                  <ArrowRight size={16} />
                </button>
              </div>
            ) : (
              <div className="empty-state">
                Nhập kích thước ma trận lớn và nhấn <strong>Gửi Job Bất đồng bộ</strong> để chạy nền trên cụm Worker.
              </div>
            )}
          </div>
        </div>
      ) : (
        /* Mode B: Arithmetic Starter */
        <div className="grid-2-equal">
          <form className="card" onSubmit={handleRunStarter}>
            <div className="card-header">
              <div>
                <h2 className="card-title">Biểu thức Số học Song song</h2>
                <p className="card-subtitle">Kiểm tra nhanh luồng chia tác vụ sang 2 Worker (POST /api/starter)</p>
              </div>
            </div>

            <div className="form-group">
              <span className="form-label">Ví dụ mẫu nhanh</span>
              <div className="chip-row">
                {STARTER_PRESETS.map((p) => (
                  <button
                    key={p.label}
                    type="button"
                    className="chip"
                    onClick={() => {
                      setExpr1(p.expr1);
                      setTopOp(p.topOp);
                      setExpr2(p.expr2);
                    }}
                  >
                    {p.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="form-row">
              <div className="form-group" style={{ flex: 2, minWidth: 140 }}>
                <label className="form-label" htmlFor="starter-expr1">Biểu thức nhánh 1 (expr1)</label>
                <input
                  id="starter-expr1"
                  type="text"
                  className="input mono"
                  value={expr1}
                  onChange={(e) => setExpr1(e.target.value)}
                  required
                />
              </div>
              <div className="form-group" style={{ flex: 1, minWidth: 90 }}>
                <label className="form-label" htmlFor="starter-topop">Phép gộp</label>
                <select
                  id="starter-topop"
                  className="select mono"
                  value={topOp}
                  onChange={(e) => setTopOp(e.target.value)}
                >
                  <option value="+">+</option>
                  <option value="-">-</option>
                  <option value="*">*</option>
                  <option value="/">/</option>
                </select>
              </div>
              <div className="form-group" style={{ flex: 2, minWidth: 140 }}>
                <label className="form-label" htmlFor="starter-expr2">Biểu thức nhánh 2 (expr2)</label>
                <input
                  id="starter-expr2"
                  type="text"
                  className="input mono"
                  value={expr2}
                  onChange={(e) => setExpr2(e.target.value)}
                  required
                />
              </div>
            </div>

            {starterError && (
              <div className="banner banner-danger" style={{ marginBottom: 12 }}>
                <span>{starterError}</span>
              </div>
            )}

            <button type="submit" className="btn btn-primary" disabled={starterLoading}>
              <Play size={16} />
              <span>{starterLoading ? 'Đang tính song song...' : 'Tính biểu thức song song'}</span>
            </button>
          </form>

          <div className="card">
            <div className="card-header">
              <h2 className="card-title">Sơ đồ thực thi & Kết quả</h2>
            </div>

            <div className="starter-flow" style={{ marginBottom: 16 }}>
              <div className="starter-node">
                <div className="muted">Worker 1</div>
                <strong className="mono">{expr1 || '...'}</strong>
              </div>
              <span className="mono">{topOp}</span>
              <div className="starter-node">
                <div className="muted">Worker 2</div>
                <strong className="mono">{expr2 || '...'}</strong>
              </div>
              <ArrowRight size={16} />
              <div className="starter-node">
                <div className="muted">Master gộp ({topOp})</div>
                <strong className="mono">
                  ({expr1}) {topOp} ({expr2})
                </strong>
              </div>
            </div>

            {starterResult ? (
              <div className="kpi-card" style={{ textAlign: 'center' }}>
                <div className="kpi-label">Kết quả tổng hợp</div>
                <div className="kpi-value" style={{ fontSize: 32, color: 'var(--success)' }}>
                  {formatNumber(starterResult.result)}
                </div>
              </div>
            ) : (
              <div className="empty-state">Nhấn <strong>Tính biểu thức song song</strong> để chạy trên cụm Worker.</div>
            )}
          </div>
        </div>
      )}

      {/* Paste CSV Modal */}
      {pasteTarget && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="Dán dữ liệu CSV ma trận">
          <div className="modal-card">
            <h3 className="modal-title">Dán dữ liệu cho Ma trận {pasteTarget}</h3>
            <p className="card-subtitle" style={{ marginBottom: 10 }}>
              Hỗ trợ copy trực tiếp từ Excel hoặc CSV (phân tách bằng dấu phẩy, tab hoặc khoảng trắng; tối đa 30×30).
            </p>
            <textarea
              rows={6}
              className="textarea"
              placeholder={'1, 2, 3\n4, 5, 6\n7, 8, 9'}
              value={pasteText}
              onChange={(e) => setPasteText(e.target.value)}
            />
            {pasteError && <div className="error-text" style={{ marginTop: 6 }}>{pasteError}</div>}
            <div className="modal-actions">
              <button type="button" className="btn" onClick={() => setPasteTarget(null)}>
                Hủy
              </button>
              <button type="button" className="btn btn-primary" onClick={handleApplyPaste}>
                Áp dụng vào Ma trận {pasteTarget}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
