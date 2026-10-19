import React, { useState, useEffect, useCallback } from 'react';
import {
  Server,
  Cpu,
  Layers,
  Activity,
  Play,
  RotateCcw,
  CheckCircle2,
  AlertTriangle,
  Zap,
  Pause,
  RefreshCw,
  Clock,
  Sparkles,
  ArrowRight,
  Database,
  BarChart3,
  Sliders
} from 'lucide-react';
import './App.css';

const API_BASE = 'http://localhost:8080/api';

export default function App() {
  const [activeTab, setActiveTab] = useState('compute'); // 'compute' | 'cluster' | 'starter'
  const [status, setStatus] = useState(null);
  const [workers, setWorkers] = useState([]);
  const [loadingStatus, setLoadingStatus] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(true);

  // Matrix Lab State
  const [rowsA, setRowsA] = useState(3);
  const [colsA, setColsA] = useState(3);
  const [rowsB, setRowsB] = useState(3);
  const [colsB, setColsB] = useState(3);
  const [op, setOp] = useState('x'); // '+', '-', 'x', '%'
  const [matrixA, setMatrixA] = useState([
    [1, 2, 3],
    [4, 5, 6],
    [7, 8, 9]
  ]);
  const [matrixB, setMatrixB] = useState([
    [9, 8, 7],
    [6, 5, 4],
    [3, 2, 1]
  ]);
  const [compareSeq, setCompareSeq] = useState(true);
  const [computing, setComputing] = useState(false);
  const [computeResult, setComputeResult] = useState(null);
  const [computeError, setComputeError] = useState(null);

  // Starter demo state
  const [expr1, setExpr1] = useState('1+2');
  const [expr2, setExpr2] = useState('3*2');
  const [topOp, setTopOp] = useState('+');
  const [starterResult, setStarterResult] = useState(null);
  const [starterLoading, setStarterLoading] = useState(false);

  // Fetch status and workers
  const fetchClusterInfo = useCallback(async () => {
    try {
      setLoadingStatus(true);
      const [resStatus, resWorkers] = await Promise.all([
        fetch(`${API_BASE}/status`).then(r => r.json()).catch(() => null),
        fetch(`${API_BASE}/workers`).then(r => r.json()).catch(() => [])
      ]);
      if (resStatus) setStatus(resStatus);
      if (resWorkers) setWorkers(resWorkers);
    } catch (err) {
      console.error('Loi ket noi Master API:', err);
    } finally {
      setLoadingStatus(false);
    }
  }, []);

  useEffect(() => {
    fetchClusterInfo();
    if (!autoRefresh) return;
    const interval = setInterval(fetchClusterInfo, 2500);
    return () => clearInterval(interval);
  }, [fetchClusterInfo, autoRefresh]);

  // Dimension sync helpers
  const handleDimChange = (target, rows, cols) => {
    if (rows < 1) rows = 1;
    if (cols < 1) cols = 1;
    if (rows > 8) rows = 8;
    if (cols > 8) cols = 8;

    if (target === 'A') {
      setRowsA(rows);
      setColsA(cols);
      setMatrixA(prev => resizeMatrix(prev, rows, cols));
      // Auto-adjust B if operation requires it
      if (op === '+' || op === '-' || op === '%') {
        setRowsB(rows);
        setColsB(cols);
        setMatrixB(prev => resizeMatrix(prev, rows, cols));
      } else if (op === 'x') {
        setRowsB(cols);
        setMatrixB(prev => resizeMatrix(prev, cols, colsB));
      }
    } else {
      setRowsB(rows);
      setColsB(cols);
      setMatrixB(prev => resizeMatrix(prev, rows, cols));
    }
  };

  const handleOpChange = newOp => {
    setOp(newOp);
    if (newOp === '+' || newOp === '-' || newOp === '%') {
      setRowsB(rowsA);
      setColsB(colsA);
      setMatrixB(prev => resizeMatrix(prev, rowsA, colsA));
    } else if (newOp === 'x') {
      setRowsB(colsA);
      setMatrixB(prev => resizeMatrix(prev, colsA, colsB));
    }
  };

  const resizeMatrix = (mat, newR, newC) => {
    const next = [];
    for (let i = 0; i < newR; i++) {
      const row = [];
      for (let j = 0; j < newC; j++) {
        row.push(mat[i] && mat[i][j] !== undefined ? mat[i][j] : Math.floor(Math.random() * 10));
      }
      next.push(row);
    }
    return next;
  };

  const updateCell = (target, i, j, val) => {
    const num = val === '' ? 0 : parseFloat(val);
    if (target === 'A') {
      const next = matrixA.map((row, r) =>
        row.map((cell, c) => (r === i && c === j ? num : cell))
      );
      setMatrixA(next);
    } else {
      const next = matrixB.map((row, r) =>
        row.map((cell, c) => (r === i && c === j ? num : cell))
      );
      setMatrixB(next);
    }
  };

  const randomizeMatrix = target => {
    const fn = (r, c) => {
      const res = [];
      for (let i = 0; i < r; i++) {
        const row = [];
        for (let j = 0; j < c; j++) {
          row.push(Math.floor(Math.random() * 19) - 5);
        }
        res.push(row);
      }
      return res;
    };
    if (target === 'A' || target === 'BOTH') setMatrixA(fn(rowsA, colsA));
    if (target === 'B' || target === 'BOTH') setMatrixB(fn(rowsB, colsB));
  };

  const fillZeros = target => {
    const fn = (r, c) => Array.from({ length: r }, () => Array(c).fill(0));
    if (target === 'A' || target === 'BOTH') setMatrixA(fn(rowsA, colsA));
    if (target === 'B' || target === 'BOTH') setMatrixB(fn(rowsB, colsB));
  };

  // Check dimension validity
  const isValidDimensions = () => {
    if (op === '+' || op === '-' || op === '%') {
      return rowsA === rowsB && colsA === colsB;
    }
    if (op === 'x') {
      return colsA === rowsB;
    }
    return true;
  };

  // Run Matrix Computation
  const handleExecuteCompute = async () => {
    if (!isValidDimensions()) {
      setComputeError('Kích thước ma trận không hợp lệ cho phép toán đã chọn.');
      return;
    }
    setComputing(true);
    setComputeError(null);
    setComputeResult(null);

    try {
      const res = await fetch(`${API_BASE}/compute`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          operation: op,
          matrixA,
          matrixB,
          compareSequential: compareSeq
        })
      });
      const data = await res.json();
      if (!res.ok || data.error) {
        setComputeError(data.error || 'Thực thi phân tán thất bại');
      } else {
        setComputeResult(data);
      }
    } catch (err) {
      setComputeError('Không thể kết nối đến Master Web API: ' + err.message);
    } finally {
      setComputing(false);
      fetchClusterInfo();
    }
  };

  // Toggle Policy
  const handleChangePolicy = async newPolicy => {
    try {
      await fetch(`${API_BASE}/policy`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ policy: newPolicy })
      });
      fetchClusterInfo();
    } catch (err) {
      console.error('Loi doi policy:', err);
    }
  };

  // Toggle Worker Pause/Resume
  const handleToggleWorker = async workerId => {
    try {
      await fetch(`${API_BASE}/workers/toggle`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ workerId })
      });
      fetchClusterInfo();
    } catch (err) {
      console.error('Loi toggle worker:', err);
    }
  };

  // Run Starter Arithmetic Demo
  const handleRunStarter = async () => {
    setStarterLoading(true);
    setStarterResult(null);
    try {
      const res = await fetch(`${API_BASE}/starter`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ expr1, expr2, topOp })
      });
      const data = await res.json();
      setStarterResult(data);
    } catch (err) {
      console.error('Loi chay starter:', err);
    } finally {
      setStarterLoading(false);
      fetchClusterInfo();
    }
  };

  return (
    <div className="app-container">
      {/* HEADER */}
      <header className="app-header">
        <div className="header-brand">
          <div className="brand-logo">
            <Zap className="brand-icon" />
          </div>
          <div>
            <h1 className="brand-title">PBL4 Distributed Computing Lab</h1>
            <p className="brand-subtitle">
              He Thong Tinh Toan Phan Tan & Can Bang Tai Master - Worker
            </p>
          </div>
        </div>

        {/* CLUSTER QUICK STATS */}
        <div className="header-stats">
          <div className="stat-pill">
            <span className={`pulse-dot ${status?.running ? 'active' : ''}`}></span>
            <span className="stat-label">Master:</span>
            <span className="stat-value">{status?.running ? 'ONLINE (:50)' : 'OFFLINE'}</span>
          </div>

          <div className="stat-pill">
            <Cpu className="pill-icon" size={16} />
            <span className="stat-label">Workers:</span>
            <span className="stat-value highlight">
              {status?.availableWorkers ?? 0}/{status?.totalWorkers ?? 0}
            </span>
          </div>

          <div className="policy-selector">
            <Sliders size={15} />
            <select
              value={status?.policy || 'ROUND_ROBIN'}
              onChange={e => handleChangePolicy(e.target.value)}
              className="policy-dropdown"
            >
              <option value="ROUND_ROBIN">Round Robin</option>
              <option value="LEAST_LOADED">Least Loaded</option>
              <option value="RESOURCE_AWARE">Resource Aware (CPU/RAM)</option>
            </select>
          </div>

          <button
            className={`refresh-btn ${loadingStatus ? 'spin' : ''}`}
            onClick={fetchClusterInfo}
            title="Lam moi trang thai"
          >
            <RefreshCw size={16} />
          </button>
        </div>
      </header>

      {/* NAVIGATION TABS */}
      <nav className="tab-nav">
        <button
          className={`tab-btn ${activeTab === 'compute' ? 'active' : ''}`}
          onClick={() => setActiveTab('compute')}
        >
          <Layers size={18} />
          <span>Giai Ma Tran Phan Tan</span>
        </button>
        <button
          className={`tab-btn ${activeTab === 'cluster' ? 'active' : ''}`}
          onClick={() => setActiveTab('cluster')}
        >
          <Server size={18} />
          <span>Giam Sat Cum ({workers.length} Workers)</span>
        </button>
        <button
          className={`tab-btn ${activeTab === 'starter' ? 'active' : ''}`}
          onClick={() => setActiveTab('starter')}
        >
          <Sparkles size={18} />
          <span>Vi Du Khoi Dau</span>
        </button>
      </nav>

      {/* MAIN VIEW */}
      <main className="tab-content">
        {/* TAB 1: MATRIX COMPUTATION */}
        {activeTab === 'compute' && (
          <div className="compute-view">
            {/* CONTROLS CARD */}
            <div className="card controls-card">
              <div className="card-header">
                <h3>Cau Hinh Phep Toan & Kich Thuoc</h3>
                <div className="quick-actions">
                  <button className="action-btn" onClick={() => randomizeMatrix('BOTH')}>
                    <Sparkles size={14} /> Ngau nhien A & B
                  </button>
                  <button className="action-btn" onClick={() => fillZeros('BOTH')}>
                    0️⃣ Dien 0
                  </button>
                </div>
              </div>

              <div className="config-grid">
                {/* OPERATION SELECTION */}
                <div className="config-group">
                  <label>Phep tinh:</label>
                  <div className="op-buttons">
                    {[
                      { key: '+', label: 'Cong (+)' },
                      { key: '-', label: 'Tru (-)' },
                      { key: 'x', label: 'Nhan (x)' },
                      { key: '%', label: 'Chia lay du (%)' }
                    ].map(item => (
                      <button
                        key={item.key}
                        className={`op-btn ${op === item.key ? 'selected' : ''}`}
                        onClick={() => handleOpChange(item.key)}
                      >
                        {item.label}
                      </button>
                    ))}
                  </div>
                </div>

                {/* DIMENSIONS */}
                <div className="config-group">
                  <label>Kich thuoc Ma tran A:</label>
                  <div className="dim-inputs">
                    <input
                      type="number"
                      min="1"
                      max="8"
                      value={rowsA}
                      onChange={e => handleDimChange('A', parseInt(e.target.value) || 1, colsA)}
                    />
                    <span>dong  x </span>
                    <input
                      type="number"
                      min="1"
                      max="8"
                      value={colsA}
                      onChange={e => handleDimChange('A', rowsA, parseInt(e.target.value) || 1)}
                    />
                    <span>cot</span>
                  </div>
                </div>

                <div className="config-group">
                  <label>Kich thuoc Ma tran B:</label>
                  <div className="dim-inputs">
                    <input
                      type="number"
                      min="1"
                      max="8"
                      value={rowsB}
                      onChange={e => handleDimChange('B', parseInt(e.target.value) || 1, colsB)}
                    />
                    <span>dong  x </span>
                    <input
                      type="number"
                      min="1"
                      max="8"
                      value={colsB}
                      onChange={e => handleDimChange('B', rowsB, parseInt(e.target.value) || 1)}
                    />
                    <span>cot</span>
                  </div>
                </div>
              </div>

              {/* VALIDATION STATUS */}
              <div className="validation-bar">
                {isValidDimensions() ? (
                  <div className="valid-pill success">
                    <CheckCircle2 size={16} />
                    <span>
                      Kich thuoc hop le: [{rowsA} x {colsA}] {op} [{rowsB} x {colsB}]
                    </span>
                  </div>
                ) : (
                  <div className="valid-pill error">
                    <AlertTriangle size={16} />
                    <span>
                      {op === 'x'
                        ? `Phep nhan yeu cau so cot A (${colsA}) phai bang so dong B (${rowsB})!`
                        : `Phep toan ${op} yeu cau 2 ma tran cung kich thuoc!`}
                    </span>
                  </div>
                )}

                <div className="execute-row">
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={compareSeq}
                      onChange={e => setCompareSeq(e.target.checked)}
                    />
                    <span>So sanh voi Thuat toan tuan tu</span>
                  </label>

                  <button
                    className="execute-btn"
                    disabled={computing || !isValidDimensions() || (status && status.availableWorkers === 0)}
                    onClick={handleExecuteCompute}
                  >
                    {computing ? (
                      <>
                        <RefreshCw className="spin" size={18} />
                        <span>Dang tinh toan phan tan...</span>
                      </>
                    ) : (
                      <>
                        <Play size={18} fill="currentColor" />
                        <span>Chay Tinh Toan Phan Tan</span>
                      </>
                    )}
                  </button>
                </div>
              </div>
            </div>

            {/* MATRICES INTERACTIVE EDITORS */}
            <div className="matrices-wrapper">
              {/* MATRIX A */}
              <div className="matrix-card">
                <div className="matrix-header">
                  <h4>Ma Tran A [{rowsA} x {colsA}]</h4>
                  <button className="small-btn" onClick={() => randomizeMatrix('A')}>
                    Ngau nhien
                  </button>
                </div>
                <div
                  className="matrix-grid-box"
                  style={{ gridTemplateColumns: `repeat(${colsA}, minmax(48px, 1fr))` }}
                >
                  {matrixA.map((row, i) =>
                    row.map((val, j) => (
                      <input
                        key={`A-${i}-${j}`}
                        type="number"
                        className="cell-input"
                        value={val}
                        onChange={e => updateCell('A', i, j, e.target.value)}
                      />
                    ))
                  )}
                </div>
              </div>

              {/* OP SYMBOL */}
              <div className="op-divider">
                <span className="op-badge">{op}</span>
              </div>

              {/* MATRIX B */}
              <div className="matrix-card">
                <div className="matrix-header">
                  <h4>Ma Tran B [{rowsB} x {colsB}]</h4>
                  <button className="small-btn" onClick={() => randomizeMatrix('B')}>
                    Ngau nhien
                  </button>
                </div>
                <div
                  className="matrix-grid-box"
                  style={{ gridTemplateColumns: `repeat(${colsB}, minmax(48px, 1fr))` }}
                >
                  {matrixB.map((row, i) =>
                    row.map((val, j) => (
                      <input
                        key={`B-${i}-${j}`}
                        type="number"
                        className="cell-input"
                        value={val}
                        onChange={e => updateCell('B', i, j, e.target.value)}
                      />
                    ))
                  )}
                </div>
              </div>
            </div>

            {/* COMPUTE ERROR ALERT */}
            {computeError && (
              <div className="alert-box error">
                <AlertTriangle size={20} />
                <span>{computeError}</span>
              </div>
            )}

            {/* RESULTS SECTION */}
            {computeResult && (
              <div className="card result-card">
                <div className="result-header">
                  <div className="result-title">
                    <CheckCircle2 size={24} className="success-icon" />
                    <div>
                      <h3>Ket Qua Tinh Toan Ma Tran C = A {op} B</h3>
                      <p>Kich thuoc ket qua: [{computeResult.rows} x {computeResult.cols}]</p>
                    </div>
                  </div>

                  {/* BENCHMARK STATS */}
                  <div className="benchmark-badges">
                    <div className="bench-badge highlight">
                      <Clock size={16} />
                      <div className="bench-info">
                        <span className="bench-val">{computeResult.distributedTimeMs} ms</span>
                        <span className="bench-name">Thoi gian Phan tan</span>
                      </div>
                    </div>

                    {computeResult.sequentialTimeMs !== null && (
                      <div className="bench-badge">
                        <BarChart3 size={16} />
                        <div className="bench-info">
                          <span className="bench-val">{computeResult.sequentialTimeMs} ms</span>
                          <span className="bench-name">Thoi gian Tuan tu</span>
                        </div>
                      </div>
                    )}

                    {computeResult.speedup !== null && (
                      <div className="bench-badge speedup">
                        <Zap size={16} />
                        <div className="bench-info">
                          <span className="bench-val">{computeResult.speedup}x</span>
                          <span className="bench-name">Toc do (Speedup)</span>
                        </div>
                      </div>
                    )}
                  </div>
                </div>

                {/* RESULT MATRIX DISPLAY */}
                <div className="result-matrix-container">
                  <div
                    className="matrix-grid-box result-grid"
                    style={{
                      gridTemplateColumns: `repeat(${computeResult.cols}, minmax(64px, 1fr))`
                    }}
                  >
                    {computeResult.result.map((row, i) =>
                      row.map((val, j) => (
                        <div key={`res-${i}-${j}`} className="result-cell">
                          {Number.isInteger(val) ? val : val.toFixed(2)}
                        </div>
                      ))
                    )}
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {/* TAB 2: CLUSTER MONITOR */}
        {activeTab === 'cluster' && (
          <div className="cluster-view">
            <div className="cluster-summary-bar">
              <div className="summary-item">
                <span className="summary-label">Tong so Worker:</span>
                <span className="summary-val">{workers.length}</span>
              </div>
              <div className="summary-item">
                <span className="summary-label">Worker San Sang:</span>
                <span className="summary-val green">
                  {workers.filter(w => w.status === 'ONLINE' && !w.paused).length}
                </span>
              </div>
              <div className="summary-item">
                <span className="summary-label">Dang Tam Dung (Paused):</span>
                <span className="summary-val orange">
                  {workers.filter(w => w.paused).length}
                </span>
              </div>
              <div className="summary-item">
                <span className="summary-label">Chinh sach dieu phoi:</span>
                <span className="summary-val cyan">{status?.policy}</span>
              </div>
            </div>

            {/* WORKERS GRID */}
            <div className="workers-grid">
              {workers.map(w => (
                <div key={w.workerId} className={`worker-card ${w.paused ? 'paused' : ''}`}>
                  <div className="worker-card-header">
                    <div className="worker-id-row">
                      <span className={`status-indicator ${w.paused ? 'paused' : 'online'}`}></span>
                      <span className="worker-id">{w.workerId}</span>
                    </div>
                    <span className="worker-port">:{w.port}</span>
                  </div>

                  <div className="worker-metrics">
                    <div className="metric-row">
                      <span>Tasks:</span>
                      <span className="metric-val">{w.activeTasks} / {w.capacity} (Xong: {w.completedTasks})</span>
                    </div>

                    <div className="metric-bar-group">
                      <div className="metric-bar-label">
                        <span>CPU</span>
                        <span>{w.metrics?.cpu !== null ? `${Math.round(w.metrics.cpu)}%` : '--'}</span>
                      </div>
                      <div className="progress-bg">
                        <div
                          className="progress-fill cpu"
                          style={{ width: `${Math.min(100, w.metrics?.cpu || 0)}%` }}
                        ></div>
                      </div>
                    </div>

                    <div className="metric-bar-group">
                      <div className="metric-bar-label">
                        <span>RAM</span>
                        <span>{w.metrics?.ram !== null ? `${Math.round(w.metrics.ram)}%` : '--'}</span>
                      </div>
                      <div className="progress-bg">
                        <div
                          className="progress-fill ram"
                          style={{ width: `${Math.min(100, w.metrics?.ram || 0)}%` }}
                        ></div>
                      </div>
                    </div>
                  </div>

                  <div className="worker-footer">
                    <span className={`admission-badge ${w.admissionReason}`}>
                      {w.admissionReason || (w.paused ? 'PAUSED' : 'READY')}
                    </span>

                    <button
                      className={`toggle-worker-btn ${w.paused ? 'resume' : 'pause'}`}
                      onClick={() => handleToggleWorker(w.workerId)}
                      title={w.paused ? 'Bat lai Worker' : 'Tam dung Worker (Mo phong loi)'}
                    >
                      {w.paused ? <Play size={13} /> : <Pause size={13} />}
                      <span>{w.paused ? 'Bat lai' : 'Tam dung'}</span>
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* TAB 3: STARTER ARITHMETIC DEMO */}
        {activeTab === 'starter' && (
          <div className="starter-view card">
            <div className="starter-header">
              <Sparkles className="starter-icon" size={32} />
              <div>
                <h2>Vi Du Khoi Dau: Tinh Toan Bieu Thuc Song Song</h2>
                <p>
                  Mo phong Master chia phep toan <strong>({expr1})</strong> cho Worker 1 va{' '}
                  <strong>({expr2})</strong> cho Worker 2, sau do tong hop bang phep tinh{' '}
                  <strong>{topOp}</strong>.
                </p>
              </div>
            </div>

            <div className="starter-form">
              <div className="starter-inputs-row">
                <div className="starter-input-box">
                  <label>Phep toan 1 (Worker 01):</label>
                  <input
                    type="text"
                    value={expr1}
                    onChange={e => setExpr1(e.target.value)}
                    placeholder="1+2"
                  />
                </div>

                <div className="starter-op-box">
                  <label>Tong hop:</label>
                  <select value={topOp} onChange={e => setTopOp(e.target.value)}>
                    <option value="+">+</option>
                    <option value="-">-</option>
                    <option value="*">*</option>
                    <option value="/">/</option>
                  </select>
                </div>

                <div className="starter-input-box">
                  <label>Phep toan 2 (Worker 02):</label>
                  <input
                    type="text"
                    value={expr2}
                    onChange={e => setExpr2(e.target.value)}
                    placeholder="3*2"
                  />
                </div>

                <button
                  className="execute-btn"
                  onClick={handleRunStarter}
                  disabled={starterLoading}
                >
                  {starterLoading ? <RefreshCw className="spin" size={18} /> : <Play size={18} />}
                  <span>Thuc Thi</span>
                </button>
              </div>

              {starterResult && (
                <div className="starter-result-banner">
                  <CheckCircle2 size={28} className="success-icon" />
                  <div className="result-text">
                    <h4>Ket qua tong hop tai Master:</h4>
                    <p className="equation">
                      ({starterResult.expr1}) {starterResult.topOp} ({starterResult.expr2}) ={' '}
                      <span className="starter-number">{starterResult.result}</span>
                    </p>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}
      </main>
    </div>
  );
}
