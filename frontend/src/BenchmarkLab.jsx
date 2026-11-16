import React, { useState, useEffect, useMemo, useCallback } from 'react';
import {
  BarChart3,
  Play,
  Zap,
  TrendingUp,
  Cpu,
  Clock,
  Award,
  AlertTriangle,
  CheckCircle2,
  RotateCcw,
  Trash2,
  Layers,
  ArrowRight,
  Info,
  Sliders,
  ShieldAlert,
  Server
} from 'lucide-react';

const STORAGE_KEY = 'pbl4_benchmark_history_v2';

const PROBLEM_DEFINITIONS = [
  {
    key: 'matrix_multiply',
    name: 'Nhân hai Ma trận (A × B)',
    icon: '✖️',
    complexity: 'O(N³)',
    category: 'compute_heavy',
    description: 'Tính toán cực nặng CPU. Thể hiện rõ ràng nhất khả năng tăng tốc tuyến tính khi tăng số lượng Worker.'
  },
  {
    key: 'matrix_add',
    name: 'Cộng hai Ma trận (A + B)',
    icon: '➕',
    complexity: 'O(N²)',
    category: 'lightweight_io',
    description: 'Tính toán rất nhẹ. Rất nhạy cảm với độ trễ truyền gói tin mạng (Overhead). Minh họa rõ việc dùng quá nhiều Worker cho bài dễ sẽ bị chậm hơn 1 Worker!'
  },
  {
    key: 'matrix_inverse',
    name: 'Nghịch đảo Ma trận (A⁻¹)',
    icon: '🔄',
    complexity: 'O(N³)',
    category: 'compute_heavy',
    description: 'Khử Gauss-Jordan song song. Đòi hỏi độ chính xác cao và khối lượng phép tính dấu phẩy động lớn.'
  },
  {
    key: 'matrix_transpose',
    name: 'Chuyển vị Ma trận (Aᵀ)',
    icon: '🔀',
    complexity: 'O(N²)',
    category: 'memory_io',
    description: 'Chia cắt theo dải dòng và tái ghép cột. Nhấn mạnh vào hiệu năng truyền tải và tổng hợp kết quả.'
  },
  {
    key: 'matrix_det',
    name: 'Định thức Ma trận det(A)',
    icon: '🔢',
    complexity: 'O(N³)',
    category: 'compute_heavy',
    description: 'Khai triển Laplace và định thức con song song, quy về phép khử Gauss phân tán.'
  },
  {
    key: 'graph_dijkstra',
    name: 'Đồ thị: All-Pairs Shortest Path (Dijkstra)',
    icon: '🌐',
    complexity: 'O(V · E log V)',
    category: 'graph_algo',
    description: 'Tìm đường đi ngắn nhất giữa mọi cặp đỉnh. Master phân chia tập đỉnh nguồn cho các Worker xử lý song song.'
  }
];

const DIFFICULTY_LEVELS = [
  {
    key: 'easy',
    label: 'Dễ (Nhỏ)',
    badgeColor: 'badge-easy',
    matrixSize: 40,
    graphSize: 30,
    subtitle: 'Minh họa hiện tượng Nghẽn Truyền Thông (Overhead): Nhiều Worker có thể chậm hơn 1 Worker!'
  },
  {
    key: 'medium',
    label: 'Trung bình',
    badgeColor: 'badge-medium',
    matrixSize: 150,
    graphSize: 100,
    subtitle: 'Quy mô vừa phải, bắt đầu đạt điểm cân bằng giữa chi phí chia việc và tốc độ tính toán.'
  },
  {
    key: 'hard',
    label: 'Khó (Lớn)',
    badgeColor: 'badge-hard',
    matrixSize: 350,
    graphSize: 220,
    subtitle: 'Khối lượng tính toán lớn, nhiều Worker tăng tốc vượt trội so với 1 Worker (Speedup 3x - 6x).'
  },
  {
    key: 'extreme',
    label: 'Rất khó (Cực đại)',
    badgeColor: 'badge-extreme',
    matrixSize: 600,
    graphSize: 380,
    subtitle: 'Stress-test cụm. 1 Worker mất nhiều thời gian; cụm 8-50 Worker phát huy tối đa sức mạnh!'
  },
  {
    key: 'custom',
    label: 'Tùy chỉnh',
    badgeColor: 'badge-custom',
    matrixSize: 100,
    graphSize: 80,
    subtitle: 'Người dùng tự do nhập kích thước dòng/cột hoặc số lượng đỉnh mong muốn.'
  }
];

export default function BenchmarkLab({ workers = [], machines = [], apiBase = 'http://localhost:8080/api' }) {
  const [selectedProblemKey, setSelectedProblemKey] = useState('matrix_multiply');
  const [selectedDifficultyKey, setSelectedDifficultyKey] = useState('medium');
  const [customSize, setCustomSize] = useState(200);
  const [workerCountInput, setWorkerCountInput] = useState(4);
  const [isRunning, setIsRunning] = useState(false);
  const [autoRunning, setAutoRunning] = useState(false);
  const [autoProgressText, setAutoProgressText] = useState('');
  const [errorMessage, setErrorMessage] = useState(null);
  const [successToast, setSuccessToast] = useState(null);

  // Persistent storage for benchmark results
  const [historyStore, setHistoryStore] = useState(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      return saved ? JSON.parse(saved) : {};
    } catch {
      return {};
    }
  });

  // Save to localStorage whenever historyStore changes
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(historyStore));
    } catch (err) {
      console.warn('Could not persist benchmark history to localStorage', err);
    }
  }, [historyStore]);

  const onlineWorkers = useMemo(() => {
    return workers.filter(w => w.status !== 'OFFLINE');
  }, [workers]);

  const onlineCount = onlineWorkers.length;

  const currentProblemDef = useMemo(() => {
    return PROBLEM_DEFINITIONS.find(p => p.key === selectedProblemKey) || PROBLEM_DEFINITIONS[0];
  }, [selectedProblemKey]);

  const currentDiffDef = useMemo(() => {
    return DIFFICULTY_LEVELS.find(d => d.key === selectedDifficultyKey) || DIFFICULTY_LEVELS[1];
  }, [selectedDifficultyKey]);

  const activeProblemSize = useMemo(() => {
    if (selectedDifficultyKey === 'custom') {
      return Math.max(10, Math.min(1000, Number(customSize) || 100));
    }
    const isGraph = selectedProblemKey === 'graph_dijkstra';
    return isGraph ? currentDiffDef.graphSize : currentDiffDef.matrixSize;
  }, [selectedDifficultyKey, customSize, selectedProblemKey, currentDiffDef]);

  // Unique identifier key for this specific problem + size
  const problemSessionKey = useMemo(() => {
    return `${selectedProblemKey}_sz${activeProblemSize}`;
  }, [selectedProblemKey, activeProblemSize]);

  // Read the saved record for this exact problem
  const currentRecord = useMemo(() => {
    return historyStore[problemSessionKey] || {
      baseline1: null,
      multiRuns: []
    };
  }, [historyStore, problemSessionKey]);

  const baseline1 = currentRecord.baseline1;
  const multiRuns = currentRecord.multiRuns || [];

  // Update worker input slider max bound
  useEffect(() => {
    if (onlineCount > 0 && workerCountInput > onlineCount) {
      setWorkerCountInput(onlineCount);
    }
  }, [onlineCount, workerCountInput]);

  // Helper to show temporary toasts
  const triggerToast = useCallback((msg) => {
    setSuccessToast(msg);
    setTimeout(() => setSuccessToast(null), 4000);
  }, []);

  // Execute a benchmark run against the Master REST API
  const executeBenchmarkRun = useCallback(async (targetWorkers) => {
    setErrorMessage(null);
    try {
      const payload = {
        problemType: selectedProblemKey,
        size: activeProblemSize,
        workerCount: targetWorkers,
        compareSequential: true
      };

      const res = await fetch(`${apiBase}/benchmark/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      const data = await res.json();
      if (!res.ok) {
        throw new Error(data.error || `HTTP ${res.status}: Lỗi thực thi Benchmark`);
      }

      // Process and save result
      setHistoryStore(prev => {
        const existing = prev[problemSessionKey] || { baseline1: null, multiRuns: [] };
        const runData = {
          workerCount: data.workerCount,
          activeWorkerIds: data.activeWorkerIds || [],
          distributedTimeMs: data.distributedTimeMs,
          sequentialTimeMs: data.sequentialTimeMs,
          speedup: data.speedup,
          efficiency: data.efficiency,
          timestamp: data.timestamp || Date.now(),
          size: data.size,
          problemType: data.problemType
        };

        if (targetWorkers === 1) {
          // Update the fixed baseline
          return {
            ...prev,
            [problemSessionKey]: {
              ...existing,
              baseline1: runData
            }
          };
        } else {
          // Multi-worker run: recalculate speedup relative to baseline if baseline exists
          const baseMs = existing.baseline1 ? existing.baseline1.distributedTimeMs : runData.sequentialTimeMs;
          if (baseMs && baseMs > 0 && runData.distributedTimeMs > 0) {
            runData.speedup = Math.round((baseMs / runData.distributedTimeMs) * 1000) / 1000;
            runData.efficiency = Math.round((runData.speedup / runData.workerCount) * 1000) / 10;
          }

          // Filter out prior run with same workerCount, then insert and sort
          const updatedRuns = [
            ...existing.multiRuns.filter(r => r.workerCount !== data.workerCount),
            runData
          ].sort((a, b) => a.workerCount - b.workerCount);

          return {
            ...prev,
            [problemSessionKey]: {
              ...existing,
              multiRuns: updatedRuns
            }
          };
        }
      });

      return data;
    } catch (err) {
      setErrorMessage(err.message || 'Lỗi không xác định khi chạy Benchmark.');
      throw err;
    }
  }, [apiBase, selectedProblemKey, activeProblemSize, problemSessionKey]);

  // Handle single run (either 1-worker baseline or K-workers)
  const handleRun = async (workersToUse) => {
    if (onlineCount === 0) {
      setErrorMessage('Chưa có Worker nào kết nối tới Master. Vui lòng bật ít nhất 1 Worker!');
      return;
    }
    setIsRunning(true);
    try {
      await executeBenchmarkRun(workersToUse);
      triggerToast(`Đã hoàn tất đo đạc với ${workersToUse} Worker!`);
    } catch {
      // Error handled inside executeBenchmarkRun
    } finally {
      setIsRunning(false);
    }
  };

  // Automated Scaling Run: 1 -> 2 -> 4 -> 8 -> ... up to max available workers
  const handleAutoScaling = async () => {
    if (onlineCount === 0) {
      setErrorMessage('Chưa có Worker nào kết nối tới Master!');
      return;
    }

    setAutoRunning(true);
    setErrorMessage(null);

    // Build steps: 1, 2, 4, 8, 16... and finally onlineCount
    const steps = [1];
    let cur = 2;
    while (cur < onlineCount) {
      steps.push(cur);
      cur *= 2;
    }
    if (!steps.includes(onlineCount) && onlineCount > 1) {
      steps.push(onlineCount);
    }

    try {
      for (let i = 0; i < steps.length; i++) {
        const wCount = steps[i];
        setAutoProgressText(`[${i + 1}/${steps.length}] Đang chạy Benchmark với ${wCount} Worker...`);
        await executeBenchmarkRun(wCount);
        // Small delay to let cluster metrics settle
        await new Promise(r => setTimeout(r, 600));
      }
      triggerToast(`Đã hoàn thành chuỗi đo tự động qua ${steps.length} mốc Worker!`);
    } catch (err) {
      setErrorMessage('Chuỗi đo tự động bị gián đoạn: ' + err.message);
    } finally {
      setAutoRunning(false);
      setAutoProgressText('');
    }
  };

  // Clear current problem's history
  const handleClearCurrent = () => {
    setHistoryStore(prev => {
      const next = { ...prev };
      delete next[problemSessionKey];
      return next;
    });
    triggerToast('Đã xóa dữ liệu lưu trữ của bài toán này.');
  };

  // Reset all stored benchmarks
  const handleResetAll = () => {
    if (window.confirm('Bạn có chắc chắn muốn xóa toàn bộ lịch sử Benchmark đã lưu?')) {
      setHistoryStore({});
      localStorage.removeItem(STORAGE_KEY);
      triggerToast('Đã đặt lại toàn bộ Benchmark!');
    }
  };

  // Productivity Scoring & Analytical Insights
  const analysis = useMemo(() => {
    if (!baseline1) {
      return {
        hasData: false,
        score: null,
        status: 'waiting',
        title: 'Chưa có Thang đo Chuẩn (1 Worker)',
        description: 'Hãy nhấn "Đo Baseline (1 Worker)" để tạo mốc thời gian chuẩn cố định cho bài toán này.',
        recommendation: 'Cần thiết lập baseline 1 worker để làm căn cứ chấm điểm năng suất và tính hệ số tăng tốc (Speedup).'
      };
    }

    const t1 = baseline1.distributedTimeMs;
    if (multiRuns.length === 0) {
      return {
        hasData: true,
        score: 60,
        status: 'baseline_only',
        title: 'Đã thiết lập Thang đo Cố định 1 Worker',
        description: `Thời gian 1 Worker xử lý bài toán này là ${t1.toFixed(1)} ms. Tiếp tục đo với 2, 4 hoặc nhiều Worker hơn để so sánh hiệu năng.`,
        recommendation: 'Hãy chọn số Worker và bấm "Chạy với K Worker" hoặc dùng "Chạy chuỗi Scaling tự động".'
      };
    }

    // Evaluate best run and overhead penalties
    let bestSpeedup = 0;
    let bestWorkerCount = 1;
    let worstRun = null;
    let hasOverheadPenalty = false;

    for (const run of multiRuns) {
      const s = t1 / run.distributedTimeMs;
      if (s > bestSpeedup) {
        bestSpeedup = s;
        bestWorkerCount = run.workerCount;
      }
      if (run.distributedTimeMs > t1) {
        hasOverheadPenalty = true;
        if (!worstRun || run.distributedTimeMs > worstRun.distributedTimeMs) {
          worstRun = run;
        }
      }
    }

    // Scoring calculation (0 - 100)
    let score = 50;
    let status = 'neutral';
    let title = '';
    let description = '';
    let recommendation = '';

    if (hasOverheadPenalty) {
      // Small problem penalty: Too many workers caused slowdown!
      const slowdownPct = worstRun ? Math.round(((worstRun.distributedTimeMs - t1) / t1) * 100) : 20;
      score = Math.max(30, 65 - slowdownPct / 2);
      status = 'overhead_penalty';
      title = '⚠️ Cảnh báo: Hao tổn phân tán (Overhead Penalty)';
      description = `Bài toán quá nhẹ (${activeProblemSize} phần tử)! Khi chạy với ${worstRun?.workerCount} Worker, thời gian tăng thêm ${slowdownPct}% so với 1 Worker do chi phí đóng gói TCP, phân mảnh task và tổng hợp mạng lấn át thời gian tính toán.`;
      recommendation = `Với bài toán quy mô này: Khuyến nghị sử dụng 1 Worker hoặc tối đa 2 Worker để đạt tốc độ cao nhất và tiết kiệm tài nguyên mạng.`;
    } else if (bestSpeedup >= 3.0) {
      // Excellent scaling!
      score = Math.min(100, Math.round(75 + bestSpeedup * 3));
      status = 'optimal_scaling';
      title = '🚀 Phân tán Tối ưu: Tăng tốc Xuất sắc';
      description = `Bài toán có độ phức tạp cao! Cụm ${bestWorkerCount} Worker giúp giảm thời gian từ ${t1.toFixed(1)} ms xuống còn ${(t1 / bestSpeedup).toFixed(1)} ms (Tăng tốc ${bestSpeedup.toFixed(2)}x, Hiệu suất ${Math.round((bestSpeedup / bestWorkerCount) * 100)}%).`;
      recommendation = `Rất nên phân chia cho nhiều Worker (${bestWorkerCount}+ Worker) cho loại bài toán này để rút ngắn thời gian tính toán tối đa.`;
    } else if (bestSpeedup > 1.2) {
      // Moderate speedup
      score = Math.min(85, Math.round(65 + bestSpeedup * 8));
      status = 'moderate_speedup';
      title = '⚡ Có hiệu quả tăng tốc (Speedup ' + bestSpeedup.toFixed(2) + 'x)';
      description = `Đạt mốc tăng tốc tốt nhất ở ${bestWorkerCount} Worker. Thời gian giảm được ${Math.round(((t1 - (t1 / bestSpeedup)) / t1) * 100)}% so với khi chạy đơn lẻ 1 Worker.`;
      recommendation = `Điểm tối ưu (Sweet spot) nằm ở khoảng ${bestWorkerCount} Worker. Tăng thêm Worker vượt mức này có thể không đem lại nhiều khác biệt do định luật Amdahl.`;
    } else {
      score = 55;
      status = 'diminishing_returns';
      title = 'Hiệu quả Tăng tốc ở mức Thấp';
      description = `Hệ số tăng tốc chỉ đạt ${bestSpeedup.toFixed(2)}x. Lợi ích từ việc thêm Worker chưa đủ bù đắp độ trễ trung gian.`;
      recommendation = `Xem xét tăng độ khó bài toán (chọn mức Khó hoặc Tùy chỉnh kích thước lớn hơn) để thấy rõ hơn ưu thế tính toán phân tán.`;
    }

    return {
      hasData: true,
      score: Math.round(score),
      status,
      title,
      description,
      recommendation,
      bestSpeedup,
      bestWorkerCount
    };
  }, [baseline1, multiRuns, activeProblemSize]);

  // Combined chart data: [Baseline 1 Worker, ...Multi-Worker Runs]
  const chartData = useMemo(() => {
    const list = [];
    if (baseline1) {
      list.push({
        workerCount: 1,
        timeMs: baseline1.distributedTimeMs,
        speedup: 1.0,
        efficiency: 100,
        isBaseline: true
      });
    }
    multiRuns.forEach(r => {
      const sp = baseline1 ? baseline1.distributedTimeMs / r.distributedTimeMs : r.speedup || 1.0;
      list.push({
        workerCount: r.workerCount,
        timeMs: r.distributedTimeMs,
        speedup: Math.round(sp * 100) / 100,
        efficiency: Math.round((sp / r.workerCount) * 1000) / 10,
        isBaseline: false
      });
    });
    return list;
  }, [baseline1, multiRuns]);

  const maxTimeMs = useMemo(() => {
    if (chartData.length === 0) return 100;
    return Math.max(...chartData.map(d => d.timeMs)) * 1.15;
  }, [chartData]);

  return (
    <div className="benchmark-lab-container">
      {/* HEADER SECTION */}
      <div className="benchmark-header card">
        <div className="benchmark-title-row">
          <div className="benchmark-title-group">
            <div className="benchmark-badge-pill">
              <BarChart3 size={18} />
              <span>Phòng Thí Nghiệm & Chấm Điểm Năng Suất</span>
            </div>
            <h2>Đánh Giá Hiệu Năng: 1 Worker vs Nhiều Worker</h2>
            <p className="benchmark-sub">
              Thực nghiệm và định lượng quy luật Amdahl: Xác định điểm hòa vốn giữa thời gian tính toán và độ trễ chia việc qua mạng trên nhiều bài toán thực tế.
            </p>
          </div>

          <div className="benchmark-cluster-chip">
            <Server size={16} />
            <span>Worker Khả Dụng:</span>
            <strong>{onlineCount} Online</strong>
            {onlineCount === 0 && <span className="text-warning">(Chưa có worker)</span>}
          </div>
        </div>

        {/* CONTROLS BAR: PROBLEM & DIFFICULTY */}
        <div className="benchmark-config-grid">
          {/* PROBLEM SELECTOR */}
          <div className="config-block">
            <label className="config-label">
              <Layers size={14} />
              <span>Chọn Bài Toán Thực Nghiệm:</span>
            </label>
            <div className="problem-picker">
              {PROBLEM_DEFINITIONS.map(p => (
                <button
                  key={p.key}
                  type="button"
                  className={`problem-pill-btn ${selectedProblemKey === p.key ? 'active' : ''}`}
                  onClick={() => setSelectedProblemKey(p.key)}
                  disabled={isRunning || autoRunning}
                >
                  <span className="p-icon">{p.icon}</span>
                  <span className="p-name">{p.name}</span>
                  <span className="p-complexity">{p.complexity}</span>
                </button>
              ))}
            </div>
            <p className="problem-desc-hint">
              <Info size={13} /> {currentProblemDef.description}
            </p>
          </div>

          {/* DIFFICULTY / SCALE PICKER */}
          <div className="config-block">
            <label className="config-label">
              <Sliders size={14} />
              <span>Độ Khó / Quy Mô ({selectedProblemKey === 'graph_dijkstra' ? 'Số đỉnh đồ thị' : 'Ma trận N×N'}):</span>
            </label>
            <div className="difficulty-picker">
              {DIFFICULTY_LEVELS.map(d => (
                <button
                  key={d.key}
                  type="button"
                  className={`diff-pill-btn ${d.badgeColor} ${selectedDifficultyKey === d.key ? 'active' : ''}`}
                  onClick={() => setSelectedDifficultyKey(d.key)}
                  disabled={isRunning || autoRunning}
                >
                  <span className="diff-name">{d.label}</span>
                  <span className="diff-val">
                    {d.key === 'custom'
                      ? `${customSize}x${customSize}`
                      : selectedProblemKey === 'graph_dijkstra'
                      ? `${d.graphSize} đỉnh`
                      : `${d.matrixSize}x${d.matrixSize}`}
                  </span>
                </button>
              ))}
            </div>

            {selectedDifficultyKey === 'custom' && (
              <div className="custom-size-row">
                <span>Nhập kích thước N:</span>
                <input
                  type="number"
                  min="10"
                  max={selectedProblemKey === 'graph_dijkstra' ? '600' : '1000'}
                  step="20"
                  value={customSize}
                  onChange={e => setCustomSize(Math.max(10, parseInt(e.target.value) || 10))}
                  disabled={isRunning || autoRunning}
                  className="custom-size-input"
                />
                <span className="text-muted">
                  (Ma trận: {customSize}×{customSize} = {(customSize * customSize).toLocaleString()} ô)
                </span>
              </div>
            )}

            <p className="diff-desc-hint">{currentDiffDef.subtitle}</p>
          </div>
        </div>
      </div>

      {/* TOAST & ERROR ALERTS */}
      {errorMessage && (
        <div className="benchmark-alert error">
          <AlertTriangle size={18} />
          <span>{errorMessage}</span>
          <button className="close-alert-btn" onClick={() => setErrorMessage(null)}>×</button>
        </div>
      )}

      {successToast && (
        <div className="benchmark-alert success">
          <CheckCircle2 size={18} />
          <span>{successToast}</span>
        </div>
      )}

      {autoRunning && (
        <div className="benchmark-alert info running-pulse">
          <Zap size={18} className="spin-icon" />
          <span>{autoProgressText || 'Đang thực thi chuỗi đo Benchmark tự động...'}</span>
        </div>
      )}

      {/* ACTION DASHBOARD: 1 WORKER BASELINE vs K WORKERS */}
      <div className="benchmark-action-panel card">
        <div className="panel-title-row">
          <div className="panel-title-text">
            <h3>Bảng Điều Khiển Đo Lường</h3>
            <span className="panel-sub">
              Đang chọn: <strong>{currentProblemDef.name}</strong> · Quy mô: <strong>{activeProblemSize} {selectedProblemKey === 'graph_dijkstra' ? 'đỉnh' : 'dòng/cột'}</strong>
            </span>
          </div>

          <div className="panel-history-actions">
            <button
              className="btn-history-clear"
              onClick={handleClearCurrent}
              title="Xóa kết quả của bài toán này"
              disabled={isRunning || autoRunning || (!baseline1 && multiRuns.length === 0)}
            >
              <Trash2 size={14} /> Xóa bài này
            </button>
            <button
              className="btn-history-reset"
              onClick={handleResetAll}
              title="Đặt lại toàn bộ các bài toán"
              disabled={isRunning || autoRunning}
            >
              <RotateCcw size={14} /> Đặt lại tất cả
            </button>
          </div>
        </div>

        <div className="action-buttons-row">
          {/* BUTTON 1: MEASURE BASELINE 1 WORKER */}
          <div className="action-card-btn baseline-action">
            <div className="action-card-header">
              <span className="action-step-num">Bước 1</span>
              <h4>Thang Đo Chuẩn (1 Worker)</h4>
            </div>
            <p className="action-card-desc">
              Chỉ giao việc cho duy nhất 1 Worker xử lý từ đầu đến cuối để đo thời gian mốc (Baseline T₁).
            </p>
            <button
              className={`btn-action primary-baseline ${isRunning ? 'disabled' : ''}`}
              onClick={() => handleRun(1)}
              disabled={isRunning || autoRunning || onlineCount === 0}
            >
              <Zap size={16} />
              <span>{baseline1 ? '⚡ Đo Lại Baseline (1 Worker)' : '⚡ Đo Baseline (1 Worker)'}</span>
            </button>
          </div>

          {/* BUTTON 2: MEASURE K WORKERS */}
          <div className="action-card-btn multi-action">
            <div className="action-card-header">
              <span className="action-step-num">Bước 2</span>
              <h4>Đo Với Cụm Nhiều Worker</h4>
            </div>
            <div className="worker-slider-group">
              <div className="slider-label-row">
                <span>Số Worker tham gia:</span>
                <strong className="slider-count-val">{workerCountInput} Workers</strong>
              </div>
              <input
                type="range"
                min="2"
                max={Math.max(2, onlineCount)}
                value={workerCountInput}
                onChange={e => setWorkerCountInput(parseInt(e.target.value))}
                disabled={isRunning || autoRunning || onlineCount < 2}
                className="worker-range-slider"
              />
              <div className="worker-preset-buttons">
                {[2, 4, 8, 16, 32, 50].map(cnt => {
                  if (cnt > onlineCount && cnt !== 2) return null;
                  return (
                    <button
                      key={cnt}
                      type="button"
                      className={`btn-worker-preset ${workerCountInput === cnt ? 'active' : ''}`}
                      onClick={() => setWorkerCountInput(cnt)}
                      disabled={isRunning || autoRunning || cnt > onlineCount}
                    >
                      {cnt}W
                    </button>
                  );
                })}
                {onlineCount > 1 && (
                  <button
                    type="button"
                    className={`btn-worker-preset ${workerCountInput === onlineCount ? 'active' : ''}`}
                    onClick={() => setWorkerCountInput(onlineCount)}
                    disabled={isRunning || autoRunning}
                  >
                    Max ({onlineCount}W)
                  </button>
                )}
              </div>
            </div>

            <button
              className="btn-action primary-multi"
              onClick={() => handleRun(workerCountInput)}
              disabled={isRunning || autoRunning || onlineCount < 2}
            >
              <Play size={16} />
              <span>🚀 Chạy Với {workerCountInput} Worker</span>
            </button>
          </div>

          {/* BUTTON 3: AUTO SCALING SEQUENCE */}
          <div className="action-card-btn auto-action">
            <div className="action-card-header">
              <span className="action-step-num">Tự Động</span>
              <h4>Chuỗi Scaling Hoàn Chỉnh</h4>
            </div>
            <p className="action-card-desc">
              Tự động chạy qua các mốc 1 → 2 → 4 → 8 → ... → Max Worker để vẽ toàn bộ đường cong hiệu năng.
            </p>
            <button
              className="btn-action primary-auto"
              onClick={handleAutoScaling}
              disabled={isRunning || autoRunning || onlineCount === 0}
            >
              <TrendingUp size={16} />
              <span>⚡ Chạy Chuỗi Tự Động</span>
            </button>
          </div>
        </div>
      </div>

      {/* CORE DISPLAY: PINNED 1-WORKER BASELINE & PRODUCTIVITY SCORE */}
      <div className="benchmark-results-split">
        {/* PINNED BASELINE CARD (GIỮ CỐ ĐỊNH KHI RESET HOẶC THAY ĐỔI) */}
        <div className={`pinned-baseline-card card ${baseline1 ? 'has-baseline' : 'no-baseline'}`}>
          <div className="pinned-badge-header">
            <div className="pinned-indicator">
              <span className="pin-dot" />
              <span>THANG ĐO CHUẨN CỐ ĐỊNH (1 WORKER)</span>
            </div>
            <span className="pinned-persisted-tag" title="Kết quả được lưu trữ bền vững trong trình duyệt">
              Đã Lưu Trữ
            </span>
          </div>

          {baseline1 ? (
            <div className="baseline-content">
              <div className="baseline-main-metric">
                <div className="metric-time-huge">
                  {baseline1.distributedTimeMs.toFixed(1)}
                  <span className="unit-ms">ms</span>
                </div>
                <div className="metric-time-sub">
                  {(baseline1.distributedTimeMs / 1000).toFixed(3)} giây · <strong>Mốc So Sánh Chuẩn (1.00x)</strong>
                </div>
              </div>

              <div className="baseline-details-list">
                <div className="detail-item">
                  <span className="d-label"><Cpu size={14} /> Worker Thực Hiện:</span>
                  <span className="d-val code-font">{baseline1.activeWorkerIds?.join(', ') || 'Worker 01'}</span>
                </div>
                <div className="detail-item">
                  <span className="d-label"><Clock size={14} /> Tuần Tự (Single Thread):</span>
                  <span className="d-val">
                    {baseline1.sequentialTimeMs ? `${baseline1.sequentialTimeMs.toFixed(1)} ms` : 'N/A'}
                  </span>
                </div>
                <div className="detail-item">
                  <span className="d-label"><Info size={14} /> Thời Điểm Đo:</span>
                  <span className="d-val text-muted">
                    {new Date(baseline1.timestamp).toLocaleTimeString()} · {new Date(baseline1.timestamp).toLocaleDateString()}
                  </span>
                </div>
              </div>

              <div className="baseline-footer-note">
                <ShieldAlert size={14} />
                <span>
                  Thang đo này được ghim cố định. Khi bạn thêm nhiều Worker hoặc reload trang web, kết quả mốc 1 Worker này vẫn được giữ nguyên để đối chiếu.
                </span>
              </div>
            </div>
          ) : (
            <div className="baseline-empty-prompt">
              <div className="empty-icon-wrap">
                <Clock size={36} />
              </div>
              <h4>Chưa có Thang đo 1 Worker</h4>
              <p>
                Hãy bấm <strong>"⚡ Đo Baseline (1 Worker)"</strong> để đo thời gian chuẩn và kích hoạt thang đo so sánh.
              </p>
            </div>
          )}
        </div>

        {/* PRODUCTIVITY & INTELLIGENT SCORING CARD */}
        <div className={`productivity-score-card card status-${analysis.status}`}>
          <div className="card-header-simple">
            <div className="score-header-title">
              <Award size={18} />
              <span>Chấm Điểm Năng Suất & Nhận Định</span>
            </div>
            {analysis.score !== null && (
              <div className={`score-badge-circle score-${analysis.status}`}>
                <span className="score-number">{analysis.score}</span>
                <span className="score-max">/100</span>
              </div>
            )}
          </div>

          <div className="score-body">
            <h4 className="score-heading">{analysis.title}</h4>
            <p className="score-desc">{analysis.description}</p>

            <div className="score-recommendation-box">
              <div className="rec-title">
                <CheckCircle2 size={15} />
                <span>Khuyến Nghị Hệ Thống:</span>
              </div>
              <p className="rec-text">{analysis.recommendation}</p>
            </div>

            {analysis.bestSpeedup > 1.0 && (
              <div className="speedup-highlight-pill">
                <span>Điểm Ngọt Tối Ưu (Sweet Spot):</span>
                <strong>{analysis.bestWorkerCount} Worker</strong> đạt <strong>{analysis.bestSpeedup.toFixed(2)}x Speedup</strong>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* VISUAL CHARTS & COMPARISON */}
      {chartData.length > 0 && (
        <div className="benchmark-visual-section card">
          <div className="section-header-row">
            <div>
              <h3>Trực Quan Hóa So Sánh Thời Gian (Execution Time)</h3>
              <p className="section-sub">
                So sánh trực tiếp thời gian hoàn thành giữa 1 Worker (màu cam) và các mốc K Worker (màu xanh/tím).
              </p>
            </div>
            <div className="chart-legend">
              <span className="legend-item"><span className="color-box baseline" /> 1 Worker (Baseline)</span>
              <span className="legend-item"><span className="color-box multi" /> Nhiều Worker</span>
            </div>
          </div>

          {/* BAR CHART */}
          <div className="bar-chart-container">
            {chartData.map(item => {
              const heightPct = Math.max(6, Math.min(100, (item.timeMs / maxTimeMs) * 100));
              const isOverheadSlower = baseline1 && !item.isBaseline && item.timeMs > baseline1.distributedTimeMs;
              return (
                <div key={item.workerCount} className="chart-bar-column">
                  <div className="chart-bar-val-top">
                    <span className="time-val-text">{item.timeMs.toFixed(1)} ms</span>
                    {!item.isBaseline && (
                      <span className={`speedup-badge ${isOverheadSlower ? 'slower' : 'faster'}`}>
                        {item.speedup.toFixed(2)}x
                      </span>
                    )}
                  </div>
                  <div className="chart-bar-track">
                    <div
                      className={`chart-bar-fill ${item.isBaseline ? 'fill-baseline' : isOverheadSlower ? 'fill-overhead' : 'fill-multi'}`}
                      style={{ height: `${heightPct}%` }}
                    />
                  </div>
                  <div className="chart-bar-label">
                    <strong>{item.workerCount}W</strong>
                    <span className="eff-tag">{item.efficiency.toFixed(0)}% eff</span>
                  </div>
                </div>
              );
            })}
          </div>

          {/* DETAILED RESULTS TABLE */}
          <div className="table-responsive-wrap">
            <table className="benchmark-data-table">
              <thead>
                <tr>
                  <th>Cấu Hình</th>
                  <th>Số Worker</th>
                  <th>Thời Gian Thực Thi</th>
                  <th>Hệ Số Tăng Tốc (Speedup)</th>
                  <th>Hiệu Suất (Efficiency)</th>
                  <th>Tiết Kiệm Thời Gian</th>
                  <th>Đánh Giá Thực Tiễn</th>
                </tr>
              </thead>
              <tbody>
                {chartData.map(item => {
                  const baseTime = baseline1 ? baseline1.distributedTimeMs : item.timeMs;
                  const diffMs = baseTime - item.timeMs;
                  const diffPct = baseTime > 0 ? Math.round((diffMs / baseTime) * 100) : 0;
                  return (
                    <tr key={item.workerCount} className={item.isBaseline ? 'row-baseline' : ''}>
                      <td>
                        {item.isBaseline ? (
                          <span className="badge-config baseline">1 Worker (Chuẩn)</span>
                        ) : (
                          <span className="badge-config multi">Cụm {item.workerCount} Workers</span>
                        )}
                      </td>
                      <td><strong>{item.workerCount}</strong> Worker</td>
                      <td>
                        <strong className="code-font">{item.timeMs.toFixed(2)} ms</strong>
                        <span className="text-muted"> ({(item.timeMs / 1000).toFixed(3)}s)</span>
                      </td>
                      <td>
                        <strong className={`code-font ${item.speedup >= 1.0 ? 'text-success' : 'text-danger'}`}>
                          {item.speedup.toFixed(2)}x
                        </strong>
                      </td>
                      <td>
                        <span className="code-font">{item.efficiency.toFixed(1)}%</span>
                      </td>
                      <td>
                        {item.isBaseline ? (
                          <span className="text-muted">Mốc chuẩn (0 ms)</span>
                        ) : diffMs >= 0 ? (
                          <span className="text-success">Giảm {diffMs.toFixed(1)} ms (-{diffPct}%)</span>
                        ) : (
                          <span className="text-danger">Tăng thêm {Math.abs(diffMs).toFixed(1)} ms (+{Math.abs(diffPct)}%)</span>
                        )}
                      </td>
                      <td>
                        {item.isBaseline ? (
                          <span className="status-note baseline">Thang đo cơ sở</span>
                        ) : item.timeMs > baseTime ? (
                          <span className="status-note penalty">⚠️ Phạt Overhead mạng (chậm hơn 1 Worker)</span>
                        ) : item.speedup >= 3.0 ? (
                          <span className="status-note optimal">🚀 Tăng tốc vượt trội</span>
                        ) : (
                          <span className="status-note good">✓ Đạt hiệu quả tốt</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
