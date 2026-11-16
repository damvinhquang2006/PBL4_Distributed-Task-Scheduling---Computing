import React, { useState, useEffect, useCallback, useRef } from 'react';
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
  Sliders,
  Eye,
  Search,
  HardDrive,
  ShieldCheck,
  Radio,
  Users,
  XOctagon
} from 'lucide-react';
import './App.css';
import MachineFleet from './MachineFleet';
import BenchmarkLab from './BenchmarkLab';

const API_BASE = 'http://localhost:8080/api';

const OPERATIONS = [
  { key: '+', label: 'Cộng (+)' },
  { key: '-', label: 'Trừ (-)' },
  { key: 'x', label: 'Nhân (x)' },
  { key: '%', label: 'Chia dư (%)' },
  { key: 'scalar', label: 'Vô hướng (k·A)' },
  { key: 'T', label: 'Chuyển vị (Aᵀ)' },
  { key: 'inv', label: 'Nghịch đảo (A⁻¹)' },
  { key: 'det', label: 'Định thức det(A)' },
  { key: 'rank', label: 'Hạng rank(A)' }
];

export default function App() {
  const [activeTab, setActiveTab] = useState('compute'); // 'compute' | 'cluster' | 'starter'
  const [status, setStatus] = useState(null);
  const [workers, setWorkers] = useState([]);
  const [machines, setMachines] = useState([]);
  const [clusterError, setClusterError] = useState(null);
  const clusterFetchRef = useRef(false);
  const masterSessionRef = useRef(null);
  const [loadingStatus, setLoadingStatus] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(true);

  // Matrix Lab State
  const [rowsA, setRowsA] = useState(3);
  const [colsA, setColsA] = useState(3);
  const [rowsB, setRowsB] = useState(3);
  const [colsB, setColsB] = useState(3);
  const [op, setOp] = useState('x');
  const [scalarVal, setScalarVal] = useState(2);
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

  // Large-scale generation & inspection descriptor state (Up to 50,000 x 50,000)
  // Browser stores lightweight metadata descriptor only (O(1) RAM, never stores 2.5B elements)
  const [genA, setGenA] = useState({
    rows: 3,
    cols: 3,
    generator: 'random-v1',
    type: 'random', // 'random' | 'identity' | 'constant' | 'sparse'
    seed: 42,
    min: 1,
    max: 10,
    val: 1
  });
  const [genB, setGenB] = useState({
    rows: 3,
    cols: 3,
    generator: 'random-v1',
    type: 'random',
    seed: 99,
    min: 1,
    max: 10,
    val: 1
  });
  const [inspectA, setInspectA] = useState({ r: 1, c: 1 });
  const [inspectB, setInspectB] = useState({ r: 1, c: 1 });

  // Async job progress for the connected worker fleet.
  const [asyncJobId, setAsyncJobId] = useState(null);
  const [asyncProgress, setAsyncProgress] = useState(null);
  const [isAsyncRunning, setIsAsyncRunning] = useState(false);
  const jobPollRef = useRef(null);
  const jobPollIdRef = useRef(null);
  const jobPollInFlightRef = useRef(false);
  const jobPollFailuresRef = useRef(0);
  useEffect(() => () => clearInterval(jobPollRef.current), []);
  const [simulatingFailover, setSimulatingFailover] = useState(false);
  const [failoverToast, setFailoverToast] = useState(null);

  // Starter demo state
  const [expr1, setExpr1] = useState('1+2');
  const [expr2, setExpr2] = useState('3*2');
  const [topOp, setTopOp] = useState('+');
  const [starterResult, setStarterResult] = useState(null);
  const [starterLoading, setStarterLoading] = useState(false);

  const isUnary = op === 'scalar' || op === 'T' || op === 'inv' || op === 'det' || op === 'rank';
  const isSquareReq = op === 'inv' || op === 'det';
  const isLargeScale = rowsA > 10 || colsA > 10 || (!isUnary && (rowsB > 10 || colsB > 10));

  // Deterministic O(1) cell generator matching Java backend across workers and failover reruns
  const getDeterministicCellValue = useCallback((gen, row, col) => {
    const rawType = gen.generator || gen.type || 'random';
    const seed = gen.seed !== undefined ? gen.seed : 42;
    const min = gen.min !== undefined ? gen.min : 1;
    const max = gen.max !== undefined ? gen.max : 10;
    const val = gen.val !== undefined ? gen.val : 0;

    if (rawType === 'identity' || rawType === 'identity-v1') {
      return row === col ? 1 : 0;
    }
    if (rawType === 'constant' || rawType === 'constant-v1') {
      return val;
    }
    if (rawType === 'sparse' || rawType === 'sparse-v1') {
      let h = ((seed ^ (row * 374761393 + col * 668265263)) >>> 0);
      h = Math.imul(h ^ (h >>> 16), 2246822519);
      if ((h % 100) < 90) return 0;
    }
    let h = ((seed ^ (row * 374761393 + col * 668265263)) >>> 0);
    h = Math.imul(h ^ (h >>> 16), 2246822519);
    h = Math.imul(h ^ (h >>> 13), 3266489917);
    h = (h ^ (h >>> 16)) >>> 0;
    const range = max - min;
    const r = range <= 0 ? 1 : range + 1;
    return min + (h % r);
  }, []);

  // Read one Master snapshot; never synthesize local or remote Workers in the browser.
  const fetchClusterInfo = useCallback(async () => {
    if (clusterFetchRef.current) return;
    clusterFetchRef.current = true;
    setLoadingStatus(true);
    try {
      const response = await fetch(`${API_BASE}/cluster`, { signal: AbortSignal.timeout(5000), cache: 'no-store' });
      if (!response.ok) throw new Error(`Master API: HTTP ${response.status}. Cần dùng cùng JAR mới trên cả hai laptop.`);
      const snapshot = await response.json();
      if (!snapshot.masterSession || !Array.isArray(snapshot.workers) || !Array.isArray(snapshot.machines)) throw new Error('Master chưa hỗ trợ thông tin laptop. Hãy cập nhật JAR và khởi động lại.');
      if (masterSessionRef.current && masterSessionRef.current !== snapshot.masterSession) {
        clearInterval(jobPollRef.current); jobPollRef.current = null;
        jobPollIdRef.current = null;
        setComputing(false); setIsAsyncRunning(false); setAsyncProgress(null); setComputeResult(null);
        setComputeError('Master đã khởi động lại. Kiểm tra Worker đã đăng ký lại rồi gửi bài toán mới.');
      }
      masterSessionRef.current = snapshot.masterSession;
      setStatus(snapshot); setWorkers(snapshot.workers); setMachines(snapshot.machines); setClusterError(null);
    } catch (error) {
      setStatus(null); setWorkers([]); setMachines([]);
      setClusterError(`Mất kết nối với Master. ${error.message}`);
    } finally {
      clusterFetchRef.current = false; setLoadingStatus(false);
    }
  }, []);
  useEffect(() => {
    fetchClusterInfo();
    if (!autoRefresh) return;
    const interval = setInterval(fetchClusterInfo, 2500);
    return () => clearInterval(interval);
  }, [fetchClusterInfo, autoRefresh]);

  const pollAsyncJob = useCallback(async jobId => {
    if (jobPollInFlightRef.current || jobPollIdRef.current !== jobId) return;
    jobPollInFlightRef.current = true;
    try {
      const response = await fetch(`${API_BASE}/jobs/progress?jobId=${encodeURIComponent(jobId)}`, {
        signal: AbortSignal.timeout(5000), cache: 'no-store'
      });
      if (response.status === 404) throw new Error('Master không còn lưu Job này.');
      if (!response.ok) throw new Error(`Không đọc được tiến trình (HTTP ${response.status}).`);
      const progress = await response.json();
      if (jobPollIdRef.current !== jobId) return;
      jobPollFailuresRef.current = 0;
      setAsyncProgress(progress);
      if (progress.status === 'COMPLETED') {
        clearInterval(jobPollRef.current); jobPollRef.current = null; jobPollIdRef.current = null;
        setIsAsyncRunning(false); setComputing(false); setComputeResult(progress.resultSummary || progress); fetchClusterInfo();
      } else if (progress.status === 'FAILED' || progress.status === 'CANCELLED') {
        clearInterval(jobPollRef.current); jobPollRef.current = null; jobPollIdRef.current = null;
        setIsAsyncRunning(false); setComputing(false); setComputeError(progress.error || 'Bài toán đã kết thúc.'); fetchClusterInfo();
      }
    } catch (error) {
      if (jobPollIdRef.current !== jobId) return;
      jobPollFailuresRef.current += 1;
      if (jobPollFailuresRef.current >= 3) {
        clearInterval(jobPollRef.current); jobPollRef.current = null; jobPollIdRef.current = null;
        setIsAsyncRunning(false); setComputing(false);
        setComputeError(`Mất kết nối khi khôi phục Job ${jobId}: ${error.message}`);
      }
    } finally { jobPollInFlightRef.current = false; }
  }, [fetchClusterInfo]);

  const watchAsyncJob = useCallback(jobId => {
    clearInterval(jobPollRef.current);
    jobPollIdRef.current = jobId; jobPollFailuresRef.current = 0;
    setAsyncJobId(jobId); setIsAsyncRunning(true); setComputing(true);
    pollAsyncJob(jobId);
    jobPollRef.current = setInterval(() => pollAsyncJob(jobId), 1200);
  }, [pollAsyncJob]);

  // Recover the latest accepted Job after a browser reload using the Master registry.
  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const response = await fetch(`${API_BASE}/jobs`, { signal: AbortSignal.timeout(5000), cache: 'no-store' });
        if (!response.ok) return;
        const { jobs = [] } = await response.json();
        if (!active || jobs.length === 0) return;
        const job = [...jobs].reverse().find(item => ['RUNNING', 'RECOVERING'].includes(item.status)) || jobs[jobs.length - 1];
        setAsyncJobId(job.jobId);
        if (['RUNNING', 'RECOVERING'].includes(job.status)) watchAsyncJob(job.jobId);
        else if (job.status === 'COMPLETED') {
          const result = await fetch(`${API_BASE}/jobs/progress?jobId=${encodeURIComponent(job.jobId)}`, { signal: AbortSignal.timeout(5000) });
          if (result.ok && active) {
            const progress = await result.json(); setAsyncProgress(progress); setComputeResult(progress.resultSummary || progress);
          }
        } else if (active) setComputeError(job.error || `Job cuối cùng kết thúc với trạng thái ${job.status}.`);
      } catch { /* The cluster panel reports API connectivity separately. */ }
    })();
    return () => { active = false; };
  }, [watchAsyncJob]);

  // Dimension sync helpers (Supports dimensions up to 50,000 without crashing)
  const handleDimChange = (target, rows, cols) => {
    if (rows < 1) rows = 1;
    if (cols < 1) cols = 1;
    if (rows > 50000) rows = 50000;
    if (cols > 50000) cols = 50000;

    if (target === 'A') {
      if (isSquareReq && rows !== rowsA) {
        cols = rows;
      }
      setRowsA(rows);
      setColsA(cols);
      setGenA(prev => ({ ...prev, rows, cols }));
      if (rows <= 10 && cols <= 10) {
        setMatrixA(prev => resizeMatrix(prev, rows, cols));
      }
      // Auto-adjust B if binary operation requires it
      if (op === '+' || op === '-' || op === '%') {
        setRowsB(rows);
        setColsB(cols);
        setGenB(prev => ({ ...prev, rows, cols }));
        if (rows <= 10 && cols <= 10) {
          setMatrixB(prev => resizeMatrix(prev, rows, cols));
        }
      } else if (op === 'x') {
        setRowsB(cols);
        setGenB(prev => ({ ...prev, rows: cols }));
        if (cols <= 10 && colsB <= 10) {
          setMatrixB(prev => resizeMatrix(prev, cols, colsB));
        }
      }
    } else {
      setRowsB(rows);
      setColsB(cols);
      setGenB(prev => ({ ...prev, rows, cols }));
      if (rows <= 10 && cols <= 10) {
        setMatrixB(prev => resizeMatrix(prev, rows, cols));
      }
    }
  };

  const applyPreset = (r, c) => {
    handleDimChange('A', r, c);
    if (!isUnary) {
      if (op === 'x') {
        setRowsB(c);
        setColsB(c);
        if (c <= 10) setMatrixB(prev => resizeMatrix(prev, c, c));
      } else {
        setRowsB(r);
        setColsB(c);
        if (r <= 10 && c <= 10) setMatrixB(prev => resizeMatrix(prev, r, c));
      }
    }
  };

  const handleOpChange = newOp => {
    setOp(newOp);
    if (newOp === '+' || newOp === '-' || newOp === '%') {
      setRowsB(rowsA);
      setColsB(colsA);
      if (rowsA <= 10 && colsA <= 10) {
        setMatrixB(prev => resizeMatrix(prev, rowsA, colsA));
      }
    } else if (newOp === 'x') {
      setRowsB(colsA);
      if (colsA <= 10 && colsB <= 10) {
        setMatrixB(prev => resizeMatrix(prev, colsA, colsB));
      }
    } else if (newOp === 'inv' || newOp === 'det') {
      if (rowsA !== colsA) {
        setColsA(rowsA);
        if (rowsA <= 10) {
          setMatrixA(prev => resizeMatrix(prev, rowsA, rowsA));
        }
      }
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

    if (target === 'A' || target === 'BOTH') {
      const newSeedA = Math.floor(Math.random() * 900000) + 100000;
      setGenA(prev => ({
        ...prev,
        rows: rowsA,
        cols: colsA,
        generator: 'random-v1',
        type: 'random',
        seed: newSeedA,
        min: prev.min ?? 1,
        max: prev.max ?? 10
      }));
      if (rowsA <= 10 && colsA <= 10) {
        setMatrixA(fn(rowsA, colsA));
      }
    }

    if (target === 'B' || target === 'BOTH') {
      const newSeedB = Math.floor(Math.random() * 900000) + 100000;
      setGenB(prev => ({
        ...prev,
        rows: rowsB,
        cols: colsB,
        generator: 'random-v1',
        type: 'random',
        seed: newSeedB,
        min: prev.min ?? 1,
        max: prev.max ?? 10
      }));
      if (rowsB <= 10 && colsB <= 10) {
        setMatrixB(fn(rowsB, colsB));
      }
    }
  };

  const fillZeros = target => {
    const fn = (r, c) => Array.from({ length: r }, () => Array(c).fill(0));

    if (target === 'A' || target === 'BOTH') {
      setGenA(prev => ({
        ...prev,
        rows: rowsA,
        cols: colsA,
        generator: 'constant-v1',
        type: 'constant',
        val: 0
      }));
      if (rowsA <= 10 && colsA <= 10) {
        setMatrixA(fn(rowsA, colsA));
      }
    }

    if (target === 'B' || target === 'BOTH') {
      setGenB(prev => ({
        ...prev,
        rows: rowsB,
        cols: colsB,
        generator: 'constant-v1',
        type: 'constant',
        val: 0
      }));
      if (rowsB <= 10 && colsB <= 10) {
        setMatrixB(fn(rowsB, colsB));
      }
    }
  };

  // Check dimension validity
  const isValidDimensions = () => {
    if (op === '+' || op === '-' || op === '%') {
      return rowsA === rowsB && colsA === colsB;
    }
    if (op === 'x') {
      return colsA === rowsB;
    }
    if (isSquareReq) {
      return rowsA === colsA;
    }
    return rowsA >= 1 && colsA >= 1;
  };

  const getValidationMessage = () => {
    if (isValidDimensions()) {
      if (op === 'scalar') return `Kích thước hợp lệ: Nhân ma trận [${rowsA} x ${colsA}] với số vô hướng k = ${scalarVal}`;
      if (op === 'T') return `Kích thước hợp lệ: Chuyển vị ma trận [${rowsA} x ${colsA}] ➔ [${colsA} x ${rowsA}]`;
      if (op === 'inv') return `Kích thước hợp lệ: Ma trận vuông [${rowsA} x ${colsA}] (Nghịch đảo)`;
      if (op === 'det') return `Kích thước hợp lệ: Ma trận vuông [${rowsA} x ${colsA}] (Định thức)`;
      if (op === 'rank') return `Kích thước hợp lệ: Tính hạng cho ma trận [${rowsA} x ${colsA}]`;
      return `Kích thước hợp lệ: [${rowsA} x ${colsA}] ${op} [${rowsB} x ${colsB}]`;
    }
    if (op === 'x') {
      return `Phép nhân yêu cầu số cột A (${colsA}) phải bằng số dòng B (${rowsB})!`;
    }
    if (isSquareReq) {
      return `Phép toán ${op === 'inv' ? 'Nghịch đảo (A⁻¹)' : 'Định thức det(A)'} yêu cầu ma trận vuông (số dòng ${rowsA} = số cột ${colsA})!`;
    }
    return `Phép toán ${op} yêu cầu 2 ma trận cùng kích thước!`;
  };

  const handleSimulateFailover = async () => {
    try {
      setSimulatingFailover(true);
      const res = await fetch(`${API_BASE}/cluster/failover-test`, { method: 'POST' });
      const data = await res.json();
      if (data.victimWorkerId) {
        setFailoverToast(`Đã tạm dừng nhận việc mới trên [${data.victimWorkerId}]. Worker vẫn kết nối; phần đang xử lý tiếp tục chạy.`);
        setTimeout(() => setFailoverToast(null), 6000);
      }
      fetchClusterInfo();
    } catch (err) {
      console.error('Lỗi mô phỏng failover:', err);
    } finally {
      setSimulatingFailover(false);
    }
  };

  // Cancel running async job
  const handleCancelAsyncJob = async () => {
    if (!asyncJobId) return;
    try {
      await fetch(`${API_BASE}/jobs/cancel`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ jobId: asyncJobId })
      });
      clearInterval(jobPollRef.current);
      jobPollRef.current = null;
      jobPollIdRef.current = null;
      setIsAsyncRunning(false);
      setComputing(false);
      setComputeError('Bài toán đã bị hủy bởi người dùng.');
    } catch (err) {
      console.error('Lỗi hủy job:', err);
    }
  };

  // Run real matrix computation on the connected workers.
  const handleExecuteCompute = async () => {
    if (!isValidDimensions()) {
      setComputeError('Kích thước ma trận không hợp lệ cho phép toán đã chọn.');
      return;
    }
    const resultCells = ['det', 'rank'].includes(op) ? 1 : op === 'x' ? rowsA * colsB : rowsA * colsA;
    if (rowsA * colsA > 1000000 || (!isUnary && rowsB * colsB > 1000000) || resultCells > 1000000) {
      setComputeError('Tính toán đầy đủ hiện hỗ trợ tối đa 1.000.000 phần tử mỗi ma trận đầu vào và kết quả.');
      return;
    }
    setComputing(true);
    clearInterval(jobPollRef.current);
    jobPollRef.current = null;
    jobPollIdRef.current = null;
    setComputeError(null);
    setComputeResult(null);
    setAsyncProgress(null);
    setIsAsyncRunning(true);

    try {
      const payloadGenA = {
        rows: rowsA,
        cols: colsA,
        generator: genA.generator || (genA.type === 'constant' ? 'constant-v1' : 'random-v1'),
        type: genA.type || 'random',
        seed: genA.seed !== undefined ? genA.seed : 42,
        min: genA.min !== undefined ? genA.min : 1,
        max: genA.max !== undefined ? genA.max : 10,
        val: genA.val !== undefined ? genA.val : 0
      };
      const payloadGenB = isUnary ? undefined : {
        rows: rowsB,
        cols: colsB,
        generator: genB.generator || (genB.type === 'constant' ? 'constant-v1' : 'random-v1'),
        type: genB.type || 'random',
        seed: genB.seed !== undefined ? genB.seed : 99,
        min: genB.min !== undefined ? genB.min : 1,
        max: genB.max !== undefined ? genB.max : 10,
        val: genB.val !== undefined ? genB.val : 0
      };

      const payload = {
        operation: op,
        rowsA,
        colsA,
        rowsB: isUnary ? undefined : rowsB,
        colsB: isUnary ? undefined : colsB,
        genA: payloadGenA,
        genB: payloadGenB,
        matrixA: isLargeScale ? undefined : matrixA,
        matrixB: isUnary || isLargeScale ? undefined : matrixB,
        scalar: op === 'scalar' ? (parseFloat(scalarVal) || 0) : undefined,
        taskCount: 0
      };

      const res = await fetch(`${API_BASE}/compute/async`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify(payload)
      });
      const data = await res.json();

      if (!res.ok || data.error) {
        setComputeError(data.error || 'Khởi tạo bài toán bất đồng bộ thất bại');
        setIsAsyncRunning(false);
        setComputing(false);
        return;
      }

      const jobId = data.jobId;
      watchAsyncJob(jobId);

    } catch (err) {
      setComputeError('Không thể kết nối đến Master Web API: ' + err.message);
      setIsAsyncRunning(false);
      setComputing(false);
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

  const getResultTitle = () => {
    if (!computeResult) return '';
    if (computeResult.operation === 'det') return 'Kết Quả Định Thức det(A)';
    if (computeResult.operation === 'rank') return 'Kết Quả Hạng Ma Trận rank(A)';
    if (computeResult.operation === 'T') return 'Kết Quả Ma Trận Chuyển Vị Aᵀ';
    if (computeResult.operation === 'inv') return 'Kết Quả Ma Trận Nghịch Đảo A⁻¹';
    if (computeResult.operation === 'scalar') return `Kết Quả Nhân Ma Trận Với Số Vô Hướng (k=${scalarVal})`;
    return `Kết Quả Tính Toán Ma Trận C = A ${computeResult.operation} B`;
  };

  const renderMathViewport = (rows, cols, gen) => {
    const getLabels = count => {
      if (count <= 6) return Array.from({ length: count }, (_, i) => i);
      const res = [0, 1, 2, '...'];
      const mid = Math.floor(count / 2);
      if (!res.includes(mid) && mid < count - 3) res.push(mid);
      res.push('...');
      for (let i = Math.max(3, count - 2); i < count; i++) {
        if (!res.includes(i)) res.push(i);
      }
      return res;
    };
    const rowLabels = getLabels(rows);
    const colLabels = getLabels(cols);

    return (
      <div className="math-viewport-wrapper">
        <div className="math-bracket-left">[</div>
        <div className="math-matrix-table">
          {rowLabels.map((r, rIdx) => (
            <div key={`row-${rIdx}`} className="math-matrix-row">
              {colLabels.map((c, cIdx) => {
                if (r === '...' && c === '...') {
                  return (
                    <div key={`c-${rIdx}-${cIdx}`} className="math-cell ellipsis-cell">
                      ⋱
                    </div>
                  );
                }
                if (r === '...') {
                  return (
                    <div key={`c-${rIdx}-${cIdx}`} className="math-cell ellipsis-cell">
                      ⋮
                    </div>
                  );
                }
                if (c === '...') {
                  return (
                    <div key={`c-${rIdx}-${cIdx}`} className="math-cell ellipsis-cell">
                      ···
                    </div>
                  );
                }
                const val = getDeterministicCellValue(gen, r, c);
                return (
                  <div key={`c-${rIdx}-${cIdx}`} className="math-cell">
                    {Number.isInteger(val) ? val : val.toFixed(1)}
                  </div>
                );
              })}
            </div>
          ))}
        </div>
        <div className="math-bracket-right">]</div>
      </div>
    );
  };

  const renderLargeMatrixConfig = (target, rows, cols, gen, setGen, inspect, setInspect) => {
    return (
      <div className="large-matrix-panel">
        <div className="gen-toolbar">
          <div className="gen-item">
            <label>Quy luật tạo số:</label>
            <select
              value={gen.type}
              onChange={e => {
                const newType = e.target.value;
                setGen(prev => ({
                  ...prev,
                  type: newType,
                  generator: `${newType}-v1`
                }));
              }}
            >
              <option value="random">Ngẫu nhiên (PRNG)</option>
              <option value="identity">Ma trận Đơn vị (I)</option>
              <option value="constant">Hằng số cố định</option>
              <option value="sparse">Ma trận Thưa (Sparse 90%)</option>
            </select>
          </div>

          {gen.type === 'random' && (
            <>
              <div className="gen-item">
                <label>Seed:</label>
                <div style={{ display: 'flex', gap: '4px', alignItems: 'center' }}>
                  <input
                    type="number"
                    value={gen.seed}
                    onChange={e => setGen(prev => ({ ...prev, seed: parseInt(e.target.value) || 0 }))}
                    style={{ width: '70px' }}
                  />
                  <button
                    type="button"
                    className="small-btn"
                    style={{ padding: '3px 6px', fontSize: '11px', cursor: 'pointer' }}
                    title="Sinh lại seed ngẫu nhiên mới tức thì"
                    onClick={() =>
                      setGen(prev => ({
                        ...prev,
                        generator: 'random-v1',
                        seed: Math.floor(Math.random() * 900000) + 100000
                      }))
                    }
                  >
                    🎲
                  </button>
                </div>
              </div>
              <div className="gen-item">
                <label>Khoảng [Min - Max]:</label>
                <div className="range-box">
                  <input
                    type="number"
                    value={gen.min}
                    onChange={e => setGen(prev => ({ ...prev, min: parseInt(e.target.value) || 0 }))}
                    style={{ width: '50px' }}
                  />
                  <span>-</span>
                  <input
                    type="number"
                    value={gen.max}
                    onChange={e => setGen(prev => ({ ...prev, max: parseInt(e.target.value) || 10 }))}
                    style={{ width: '50px' }}
                  />
                </div>
              </div>
            </>
          )}

          {gen.type === 'constant' && (
            <div className="gen-item">
              <label>Giá trị C:</label>
              <input
                type="number"
                value={gen.val}
                onChange={e => setGen(prev => ({ ...prev, val: parseFloat(e.target.value) || 0 }))}
                style={{ width: '60px' }}
              />
            </div>
          )}
        </div>

        <div
          className="generator-descriptor-badge"
          style={{
            margin: '4px 0 12px',
            fontSize: '11px',
            color: '#38bdf8',
            background: 'rgba(15, 23, 42, 0.75)',
            padding: '5px 10px',
            borderRadius: '6px',
            fontFamily: 'Consolas, monospace',
            border: '1px solid #334155',
            display: 'inline-flex',
            alignItems: 'center',
            gap: '8px'
          }}
        >
          <span>📦 <strong>Descriptor (O(1) RAM):</strong></span>
          <code>
            {JSON.stringify({
              rows,
              cols,
              generator: gen.generator || `${gen.type}-v1`,
              seed: gen.type === 'random' ? gen.seed : undefined,
              ...(gen.type === 'constant' ? { val: gen.val } : {})
            })}
          </code>
        </div>

        <div className="math-viewport-container">
          <div className="math-viewport-title">
            <Eye size={14} />
            <span>
              Khung nhìn Toán học ({rows.toLocaleString()} x {cols.toLocaleString()} phần tử)
            </span>
          </div>
          {renderMathViewport(rows, cols, gen)}
        </div>

        <div className="coord-inspector">
          <div className="inspector-title">
            <Search size={14} />
            <span>Soi ô tức thì O(1) (Không tốn RAM):</span>
          </div>
          <div className="inspector-controls">
            <span>Dòng</span>
            <input
              type="number"
              min="1"
              max={rows}
              value={inspect.r}
              onChange={e =>
                setInspect(prev => ({
                  ...prev,
                  r: Math.max(1, Math.min(rows, parseInt(e.target.value) || 1))
                }))
              }
              style={{ width: '75px' }}
            />
            <span>Cột</span>
            <input
              type="number"
              min="1"
              max={cols}
              value={inspect.c}
              onChange={e =>
                setInspect(prev => ({
                  ...prev,
                  c: Math.max(1, Math.min(cols, parseInt(e.target.value) || 1))
                }))
              }
              style={{ width: '75px' }}
            />
            <div className="inspector-result-badge">
              {target}[{inspect.r}, {inspect.c}] ={' '}
              <strong>{getDeterministicCellValue(gen, inspect.r - 1, inspect.c - 1)}</strong>
            </div>
          </div>
        </div>
      </div>
    );
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
              Hệ Thống Tính Toán Phân Tán & Cân Bằng Tải Master - Worker
            </p>
          </div>
        </div>

        {/* CLUSTER QUICK STATS */}
        <div className="header-stats">
          <div className="stat-pill">
            <span className={`pulse-dot ${status?.running ? 'active' : ''}`}></span>
            <span className="stat-label">Master:</span>
            <span className="stat-value">{status?.running ? 'ONLINE' : 'OFFLINE'}</span>
          </div>

          <div className="stat-pill">
            <Cpu className="pill-icon" size={16} />
            <span className="stat-label">Worker sẵn sàng / kết nối:</span>
            <span className="stat-value highlight">
              {status?.readyWorkers ?? 0}/{status?.connectedWorkers ?? 0}
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
              <option value="LEAST_LOADED">Least Loaded (Ưu tiên rảnh)</option>
              <option value="RESOURCE_AWARE">Resource Aware (CPU/RAM)</option>
            </select>
          </div>



          <button
            className={`refresh-btn ${loadingStatus ? 'spin' : ''}`}
            onClick={fetchClusterInfo}
            title="Làm mới trạng thái"
          >
            <RefreshCw size={16} />
          </button>
        </div>
      </header>
      {clusterError && <div className="alert-box error" role="alert"><AlertTriangle size={20} /><span>{clusterError}</span></div>}

      {/* FAILOVER TOAST NOTIFICATION */}
      {failoverToast && (
        <div className="failover-toast-banner">
          <Zap size={18} className="amber" />
          <span>{failoverToast}</span>
        </div>
      )}

      {/* NAVIGATION TABS */}
      <nav className="tab-nav">
        <button
          className={`tab-btn ${activeTab === 'compute' ? 'active' : ''}`}
          onClick={() => setActiveTab('compute')}
        >
          <Layers size={18} />
          <span>Giải Ma Trận Phân Tán</span>
        </button>
        <button
          className={`tab-btn ${activeTab === 'cluster' ? 'active' : ''}`}
          onClick={() => setActiveTab('cluster')}
        >
          <Server size={18} />
          <span>Giám Sát Cụm ({status?.connectedWorkers ?? 0} Workers)</span>
        </button>
        <button
          className={`tab-btn ${activeTab === 'starter' ? 'active' : ''}`}
          onClick={() => setActiveTab('starter')}
        >
          <Sparkles size={18} />
          <span>Ví Dụ Khởi Đầu</span>
        </button>
        <button
          className={`tab-btn ${activeTab === 'benchmark' ? 'active' : ''}`}
          onClick={() => setActiveTab('benchmark')}
        >
          <BarChart3 size={18} />
          <span>Chấm Điểm & Benchmark</span>
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
                <h3>Cấu Hình Phép Toán & Kích Thước</h3>
                <div className="quick-actions">
                  <button
                    className="action-btn"
                    onClick={() => randomizeMatrix(isUnary ? 'A' : 'BOTH')}
                    title={isLargeScale ? 'Đổi seed ngẫu nhiên O(1) RAM - Không treo trình duyệt' : 'Sinh ngẫu nhiên số trong bảng'}
                  >
                    <Sparkles size={14} /> {isLargeScale ? (isUnary ? '🎲 Đổi Seed A' : '🎲 Đổi Seed A & B') : (isUnary ? 'Ngẫu nhiên A' : 'Ngẫu nhiên A & B')}
                  </button>
                  <button
                    className="action-btn"
                    onClick={() => fillZeros(isUnary ? 'A' : 'BOTH')}
                    title={isLargeScale ? 'Đổi quy tắc thành hằng số 0 tức thì O(1) RAM' : 'Điền số 0 vào bảng'}
                  >
                    0️⃣ Điền 0 {isLargeScale ? '(Hằng số)' : ''}
                  </button>
                </div>
              </div>

              <div className="config-grid">
                {/* OPERATION SELECTION */}
                <div className="config-group" style={{ gridColumn: '1 / -1' }}>
                  <label>Phép tính ma trận:</label>
                  <div className="op-buttons">
                    {OPERATIONS.map(item => (
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

                {/* SCALAR INPUT (IF SCALAR OP) */}
                {op === 'scalar' && (
                  <div className="config-group">
                    <label>Hệ số vô hướng (k):</label>
                    <div className="dim-inputs">
                      <input
                        type="number"
                        step="any"
                        value={scalarVal}
                        onChange={e => setScalarVal(e.target.value)}
                        style={{ width: '80px' }}
                      />
                      <span>(k · A)</span>
                    </div>
                  </div>
                )}

                {/* PRESET CHIPS */}
                <div className="config-group" style={{ gridColumn: '1 / -1' }}>
                  <div className="preset-bar">
                    <span className="preset-label">Kích thước mẫu nhanh:</span>
                    <div className="preset-chips">
                      <button
                        type="button"
                        className={`chip ${rowsA === 3 && colsA === 3 ? 'active' : ''}`}
                        onClick={() => applyPreset(3, 3)}
                      >
                        3 x 3 (Cơ bản)
                      </button>
                      <button
                        type="button"
                        className={`chip ${rowsA === 8 && colsA === 8 ? 'active' : ''}`}
                        onClick={() => applyPreset(8, 8)}
                      >
                        8 x 8 (Trực quan)
                      </button>
                      <button
                        type="button"
                        className={`chip ${rowsA === 100 && colsA === 100 ? 'active' : ''}`}
                        onClick={() => applyPreset(100, 100)}
                      >
                        100 x 100 (10k)
                      </button>
                      <button
                        type="button"
                        className={`chip ${rowsA === 1000 && colsA === 1000 ? 'active' : ''}`}
                        onClick={() => applyPreset(1000, 1000)}
                      >
                        1.000 x 1.000 (1M)
                      </button>
                    </div>
                  </div>
                </div>

                {/* DIMENSIONS MATRIX A */}
                <div className="config-group">
                  <label>Kích thước Ma trận A (Tối đa 50.000):</label>
                  <div className="dim-inputs">
                    <input
                      type="number"
                      min="1"
                      max="50000"
                      value={rowsA}
                      onChange={e => handleDimChange('A', parseInt(e.target.value) || 1, colsA)}
                      style={{ minWidth: '76px' }}
                    />
                    <span>dòng  x </span>
                    <input
                      type="number"
                      min="1"
                      max="50000"
                      value={colsA}
                      onChange={e => handleDimChange('A', rowsA, parseInt(e.target.value) || 1)}
                      style={{ minWidth: '76px' }}
                    />
                    <span>cột</span>
                    {isSquareReq && rowsA !== colsA && (
                      <button
                        className="small-btn"
                        onClick={() => handleDimChange('A', rowsA, rowsA)}
                        style={{ marginLeft: '6px' }}
                      >
                        Đặt vuông {rowsA}x{rowsA}
                      </button>
                    )}
                  </div>
                </div>

                {/* DIMENSIONS MATRIX B (ONLY FOR BINARY OPS) */}
                {!isUnary && (
                  <div className="config-group">
                    <label>Kích thước Ma trận B (Tối đa 50.000):</label>
                    <div className="dim-inputs">
                      <input
                        type="number"
                        min="1"
                        max="50000"
                        value={rowsB}
                        onChange={e => handleDimChange('B', parseInt(e.target.value) || 1, colsB)}
                        style={{ minWidth: '76px' }}
                      />
                      <span>dòng  x </span>
                      <input
                        type="number"
                        min="1"
                        max="50000"
                        value={colsB}
                        onChange={e => handleDimChange('B', rowsB, parseInt(e.target.value) || 1)}
                        style={{ minWidth: '76px' }}
                      />
                      <span>cột</span>
                    </div>
                  </div>
                )}

                {/* RESOURCE FOOTPRINT & SAFETY ADVISOR */}
                <div className="config-group" style={{ gridColumn: '1 / -1' }}>
                  <div className={`resource-advisor ${isLargeScale ? 'hpc' : 'direct'}`}>
                    <div className="advisor-header">
                      <div className="advisor-mode">
                        <span className={`advisor-pill ${isLargeScale ? 'hpc-pill' : 'direct-pill'}`}>
                          {isLargeScale ? 'Ma trận sinh theo cấu hình' : 'Chế độ nhập trực tiếp'}
                        </span>
                        <span className="advisor-dim-tag">
                          [{rowsA.toLocaleString()} x {colsA.toLocaleString()}]
                        </span>
                      </div>
                      <div className="advisor-safe-badge">
                        <ShieldCheck size={16} />
                        <span>Tính toán đầy đủ: tối đa 1.000.000 phần tử / ma trận</span>
                      </div>
                    </div>

                    <div className="advisor-stats-row">
                      <div className="advisor-stat-box">
                        <span className="stat-label">Số phần tử (Ma trận A):</span>
                        <span className="stat-value">{(rowsA * colsA).toLocaleString()}</span>
                      </div>
                      <div className="advisor-stat-box">
                        <span className="stat-label">RAM lý thuyết (Double 64-bit):</span>
                        <span className="stat-value highlight">
                          {((rowsA * colsA * 8) / (1024 * 1024 * 1024)).toFixed(2)} GB / ma trận
                        </span>
                      </div>
                      <div className="advisor-stat-box">
                        <span className="stat-label">RAM tổng cộng (A, B, C):</span>
                        <span className="stat-value">
                          {(
                            ((rowsA * colsA +
                              (isUnary ? 0 : rowsB * colsB) +
                              (op === 'x' ? rowsA * colsB : isUnary ? 1 : rowsA * colsA)) *
                              8) /
                            (1024 * 1024 * 1024)
                          ).toFixed(2)}{' '}
                          GB
                        </span>
                      </div>
                      <div className="advisor-stat-box">
                        <span className="stat-label">RAM Máy chủ Vật lý:</span>
                        <span className="stat-value green">~14.0 GB Khả dụng</span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              {/* VALIDATION STATUS */}
              <div className="validation-bar">
                {isValidDimensions() ? (
                  <div className="valid-pill success">
                    <CheckCircle2 size={16} />
                    <span>{getValidationMessage()}</span>
                  </div>
                ) : (
                  <div className="valid-pill error">
                    <AlertTriangle size={16} />
                    <span>{getValidationMessage()}</span>
                  </div>
                )}

                <div className="execute-row">
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={compareSeq}
                      onChange={e => setCompareSeq(e.target.checked)}
                    />
                    <span>So sánh với Thuật toán tuần tự</span>
                  </label>

                  <button
                    className="execute-btn"
                    disabled={computing || !isValidDimensions() || !status?.running || status.availableWorkers === 0}
                    onClick={handleExecuteCompute}
                  >
                    {computing ? (
                      <>
                        <RefreshCw className="spin" size={18} />
                        <span>Đang xử lý trên các Worker đã kết nối...</span>
                      </>
                    ) : (
                      <>
                        <Play size={18} fill="currentColor" />
                        <span>Chạy Tính Toán Phân Tán</span>
                      </>
                    )}
                  </button>
                </div>
                {status?.running && status.availableWorkers === 0 && !computing && (
                  <p className="async-sub" role="status">
                    Chưa có Worker sẵn sàng. Kiểm tra tab Giám Sát Cụm: bật lại Worker đang tạm dừng hoặc kiểm tra kết nối của Worker OFFLINE.
                  </p>
                )}
              </div>
            </div>

            {/* LIVE 50-WORKER QUEUE PROGRESS & TELEMETRY DASHBOARD */}
            {isAsyncRunning && (
              <div className="card async-progress-card">
                <div className="async-card-header">
                  <div className="async-title-box">
                    <div className="pulse-indicator-ring">
                      <Radio size={20} className="pulse-icon spin" />
                    </div>
                    <div>
                      <h3>Tiến Trình Phân Tán Qua Hàng Đợi</h3>
                      <p className="async-sub">
                          Ma trận được chia thành {asyncProgress?.totalTasks ?? '…'} tác vụ, phân phối cho các Worker đã kết nối.
                      </p>
                    </div>
                  </div>

                  <div className="async-actions">
                    <button
                      type="button"
                      className="failover-sim-btn"
                      onClick={handleSimulateFailover}
                      disabled={simulatingFailover}
                      title="Tạm dừng một Worker nhận việc mới; không ngắt kết nối hay hủy phần đang chạy"
                    >
                      <Zap size={14} />
                      <span>{simulatingFailover ? 'Đang tạm dừng...' : 'Tạm dừng thử 1 Worker'}</span>
                    </button>
                    <button
                      type="button"
                      className="cancel-job-btn"
                      onClick={handleCancelAsyncJob}
                      title="Hủy bài toán đang chạy"
                    >
                      <XOctagon size={14} />
                      <span>Hủy Bài Toán</span>
                    </button>
                  </div>
                </div>

                {/* ANIMATED PROGRESS BAR */}
                {asyncProgress?.waitingReason && (
                  <p className="async-sub" role="status">Đang chờ: {asyncProgress.waitingReason}</p>
                )}
                <div className="async-progress-bar-container">
                  <div className="progress-labels">
                    <span className="prog-percent-text">
                      {(asyncProgress?.progressPercent || 0).toFixed(1)}%
                    </span>
                    <span className="prog-task-count">
                      Hoàn thành: <strong>{asyncProgress?.completedTasks || 0}</strong> / {asyncProgress?.totalTasks ?? '…'} tác vụ
                    </span>
                  </div>
                  <div className="progress-track">
                    <div
                      className="progress-fill-stripe"
                      style={{ width: `${Math.min(100, asyncProgress?.progressPercent || 0)}%` }}
                    ></div>
                  </div>
                </div>

                {/* STATS TELEMETRY ROW */}
                <div className="async-telemetry-row">
                  <div className="telemetry-box">
                    <Clock size={16} />
                    <div>
                      <span className="t-label">Thời gian đã chạy</span>
                      <span className="t-val">{(((asyncProgress?.elapsedMs || 0) / 1000).toFixed(1))}s</span>
                    </div>
                  </div>
                  <div className="telemetry-box">
                    <Activity size={16} />
                    <div>
                      <span className="t-label">Ước tính còn lại (ETA)</span>
                      <span className="t-val highlight">{Number.isFinite(asyncProgress?.etaMs) ? `${(asyncProgress.etaMs / 1000).toFixed(1)}s` : 'Đang ước lượng'}</span>
                    </div>
                  </div>
                  <div className="telemetry-box">
                    <Cpu size={16} />
                    <div>
                      <span className="t-label">Chính sách hàng đợi</span>
                      <span className="t-val green">{status?.policy || 'Đang cập nhật'}</span>
                    </div>
                  </div>
                  <div className="telemetry-box">
                    <ShieldCheck size={16} />
                    <div>
                      <span className="t-label">Worker đã kết nối</span>
                      <span className="t-val cyan">{workers.length}</span>
                    </div>
                  </div>
                </div>

                {/* 50 WORKER FLEET RADAR GRID */}
                <div className="worker-fleet-radar">
                  <div className="radar-header">
                    <div className="radar-title-row">
                      <Users size={16} />
                      <span>Điều Phối Các Worker Đã Kết Nối</span>
                    </div>
                    <div className="radar-legend">
                      <span className="legend-item"><span className="dot idle"></span> Rảnh</span>
                      <span className="legend-item"><span className="dot busy"></span> Đang tính</span>
                      <span className="legend-item"><span className="dot paused"></span> Tạm dừng / OFFLINE</span>
                    </div>
                  </div>
                  <div className="radar-grid">
                    {(asyncProgress?.workerFleet ?? workers).map((w, idx) => {
                      const isBusy = !!w.assignedTask || w.activeTasks > 0;
                      const isPaused = w.paused || w.status === 'OFFLINE';
                      return (
                        <div
                          key={`fleet-${w.workerId}-${idx}`}
                          className={`fleet-node ${isPaused ? 'paused' : isBusy ? 'busy' : 'idle'}`}
                          title={`${w.workerId} | ${w.machineName} (${w.machineId}) | ${w.host}:${w.port} | Xong trong bài toán: ${w.jobCompletedTasks ?? 0} | ${w.admissionReason}`}
                        >
                          <span className="fleet-id">{w.workerId.replace('worker-', 'W')}</span>
                          {isBusy && <span className="fleet-pulse"></span>}
                        </div>
                      );
                    })}
                  </div>
                </div>

                {/* LIVE FAILOVER & QUEUE EVENT LOG */}
                <div className="failover-log-box">
                  <div className="log-header">
                    <AlertTriangle size={14} className="amber" />
                    <span>Nhật Ký Hàng Đợi &amp; Chịu Lỗi Thời Gian Thực (Failover Event Log):</span>
                  </div>
                  <div className="log-console">
                    {asyncProgress?.failoverEvents && asyncProgress.failoverEvents.length > 0 ? (
                      asyncProgress.failoverEvents.map((evt, eIdx) => (
                        <div key={`evt-${eIdx}`} className="log-line">
                          <span className="log-time">[{new Date(evt.timestamp).toLocaleTimeString()}]</span>
                          <span className="log-alert">⚠️ {evt.message}</span>
                        </div>
                      ))
                    ) : (
                      <div className="log-line-empty">
                        Đang phân phối các tác vụ tới Worker đang sẵn sàng. Chưa ghi nhận sự cố cần giao lại tác vụ.
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )}

            {/* MATRICES INTERACTIVE / VIRTUALIZED EDITORS */}
            <div className="matrices-wrapper">
              {op === 'scalar' && (
                <>
                  <div className="op-divider">
                    <span className="op-badge" style={{ width: 'auto', padding: '0 16px', borderRadius: '14px', fontSize: '18px' }}>
                      k = {scalarVal}
                    </span>
                  </div>
                  <div className="op-divider">
                    <span className="op-badge">·</span>
                  </div>
                </>
              )}

              {/* MATRIX A */}
              <div className="matrix-card">
                <div className="matrix-header">
                  <h4>Ma Trận A [{rowsA.toLocaleString()} x {colsA.toLocaleString()}]</h4>
                  <div className="matrix-header-actions" style={{ display: 'flex', gap: '6px' }}>
                    <button
                      className="small-btn"
                      onClick={() => randomizeMatrix('A')}
                      title="Sinh lại seed ngẫu nhiên cho Ma trận A"
                    >
                      🎲 {rowsA > 10 || colsA > 10 ? 'Đổi Seed' : 'Ngẫu nhiên'}
                    </button>
                    <button
                      className="small-btn"
                      onClick={() => fillZeros('A')}
                      title="Quy về hằng số 0 (Không tốn RAM)"
                    >
                      0️⃣ Điền 0
                    </button>
                  </div>
                </div>

                {rowsA > 10 || colsA > 10 ? (
                  renderLargeMatrixConfig('A', rowsA, colsA, genA, setGenA, inspectA, setInspectA)
                ) : (
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
                )}
              </div>

              {/* OP SYMBOL OR MATRIX B (FOR BINARY OPS) */}
              {!isUnary && (
                <>
                  <div className="op-divider">
                    <span className="op-badge">{op}</span>
                  </div>

                  <div className="matrix-card">
                    <div className="matrix-header">
                      <h4>Ma Trận B [{rowsB.toLocaleString()} x {colsB.toLocaleString()}]</h4>
                      <div className="matrix-header-actions" style={{ display: 'flex', gap: '6px' }}>
                        <button
                          className="small-btn"
                          onClick={() => randomizeMatrix('B')}
                          title="Sinh lại seed ngẫu nhiên cho Ma trận B"
                        >
                          🎲 {rowsB > 10 || colsB > 10 ? 'Đổi Seed' : 'Ngẫu nhiên'}
                        </button>
                        <button
                          className="small-btn"
                          onClick={() => fillZeros('B')}
                          title="Quy về hằng số 0 (Không tốn RAM)"
                        >
                          0️⃣ Điền 0
                        </button>
                      </div>
                    </div>

                    {rowsB > 10 || colsB > 10 ? (
                      renderLargeMatrixConfig('B', rowsB, colsB, genB, setGenB, inspectB, setInspectB)
                    ) : (
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
                    )}
                  </div>
                </>
              )}

              {/* UNARY BADGE */}
              {isUnary && op !== 'scalar' && (
                <div className="op-divider">
                  <span className="op-badge" style={{ width: 'auto', padding: '0 16px', borderRadius: '14px', fontSize: '16px' }}>
                    {op === 'T' ? 'Aᵀ (Chuyển vị)' : op === 'inv' ? 'A⁻¹ (Nghịch đảo)' : op === 'det' ? 'det(A)' : 'rank(A)'}
                  </span>
                </div>
              )}
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
                      <h3>{getResultTitle()}</h3>
                      <p>
                        {computeResult.isScalar
                          ? `Kết quả là một giá trị vô hướng`
                          : `Kích thước kết quả: [${computeResult.rows.toLocaleString()} x ${computeResult.cols.toLocaleString()}]`}
                      </p>
                      {computeResult.workerIds?.length > 0 && (
                        <p>Worker đã trả kết quả: {computeResult.workerIds.join(', ')}</p>
                      )}
                    </div>
                  </div>

                  {/* BENCHMARK STATS */}
                  <div className="benchmark-badges">
                    <div className="bench-badge highlight">
                      <Clock size={16} />
                      <div className="bench-info">
                        <span className="bench-val">{computeResult.distributedTimeMs} ms</span>
                        <span className="bench-name">Thời gian Phân tán</span>
                      </div>
                    </div>

                    {Number.isFinite(computeResult.sequentialTimeMs) && (
                      <div className="bench-badge">
                        <BarChart3 size={16} />
                        <div className="bench-info">
                          <span className="bench-val">{computeResult.sequentialTimeMs} ms</span>
                          <span className="bench-name">Thời gian Tuần tự</span>
                        </div>
                      </div>
                    )}

                    {Number.isFinite(computeResult.speedup) && (
                      <div className="bench-badge speedup">
                        <Zap size={16} />
                        <div className="bench-info">
                          <span className="bench-val">{computeResult.speedup}x</span>
                          <span className="bench-name">Tốc độ (Speedup)</span>
                        </div>
                      </div>
                    )}
                  </div>
                </div>

                {/* RESULT DISPLAY: SCALAR, SAMPLED HPC GRID, OR DIRECT GRID */}
                {computeResult.isScalar ? (
                  <div className="scalar-result-box">
                    <span className="scalar-op-name">
                      {computeResult.operation === 'det' ? 'Định thức det(A) =' : 'Hạng rank(A) ='}
                    </span>
                    <span className="scalar-val-big">
                      {(() => {
                        const raw = computeResult.resultScalar;
                        const num = Number(raw);
                        if (!Number.isFinite(num)) {
                          return String(raw || 'Infinity (Tràn số)');
                        }
                        if (computeResult.operation === 'det') {
                          return Number.isInteger(num) ? num : num.toFixed(4);
                        }
                        return Math.round(num);
                      })()}
                    </span>
                  </div>
                ) : computeResult.isSampled && computeResult.sampleGrid ? (
                  <div className="hpc-result-container">
                    <div className="hpc-meta-badge">
                      <ShieldCheck size={16} />
                      <span>
                        Chế độ: {computeResult.executionMode === 'HPC_TILED_CHUNKED' ? 'Chia khối phân tán HPC (Tiled Chunking)' : 'Phân tán bộ nhớ trực tiếp'} | {computeResult.totalChunks || 1} khối ({computeResult.chunkSize || 'N/A'}) | RAM: {computeResult.ramPerMatrixGB || ((computeResult.rows * computeResult.cols * 8) / 1e9).toFixed(2)} GB
                      </span>
                    </div>

                    <div className="math-viewport-container result-viewport">
                      <div className="math-viewport-title">
                        <CheckCircle2 size={14} className="green" />
                        <span>Xem trước {computeResult.sampleGrid.length} dòng × {computeResult.sampleGrid[0]?.length || 0} cột được chọn từ kết quả [{computeResult.rows.toLocaleString()} × {computeResult.cols.toLocaleString()}]</span>
                      </div>
                      <div className="math-viewport-wrapper">
                        <div className="math-bracket-left">[</div>
                        <div className="math-matrix-table">
                          {computeResult.sampleGrid.map((row, rIdx) => (
                            <div key={`res-r-${rIdx}`} className="math-matrix-row">
                              {row.map((val, cIdx) => (
                                <div key={`res-c-${rIdx}-${cIdx}`} className="math-cell result-cell-val">
                                  {Number.isInteger(val) ? val : val.toFixed(2)}
                                </div>
                              ))}
                            </div>
                          ))}
                        </div>
                        <div className="math-bracket-right">]</div>
                      </div>
                    </div>
                  </div>
                ) : (
                  <div className="result-matrix-container">
                    <p className="async-sub">Hiển thị đầy đủ {computeResult.rows} dòng × {computeResult.cols} cột. Cuộn để xem toàn bộ ma trận.</p>
                    <div
                      className="matrix-grid-box result-grid"
                      style={{
                        gridTemplateColumns: `repeat(${computeResult.cols}, minmax(64px, 1fr))`
                      }}
                    >
                      {(computeResult.result ?? computeResult.sampleGrid ?? []).map((row, i) =>
                        row.map((val, j) => (
                          <div key={`res-${i}-${j}`} className="result-cell">
                            {Number.isInteger(val) ? val : val.toFixed(2)}
                          </div>
                        ))
                      )}
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {/* TAB 2: REGISTERED MACHINES AND WORKERS */}
        {activeTab === 'cluster' && <MachineFleet machines={machines} workers={workers} onToggle={handleToggleWorker} />}
        {/* TAB 3: STARTER ARITHMETIC DEMO */}
        {activeTab === 'starter' && (
          <div className="starter-view card">
            <div className="starter-header">
              <Sparkles className="starter-icon" size={32} />
              <div>
                <h2>Ví Dụ Khởi Đầu: Tính Toán Biểu Thức Song Song</h2>
                <p>
                  Mô phỏng Master chia phép toán <strong>({expr1})</strong> cho Worker 1 và{' '}
                  <strong>({expr2})</strong> cho Worker 2, sau đó tổng hợp bằng phép tính{' '}
                  <strong>{topOp}</strong>.
                </p>
              </div>
            </div>

            <div className="starter-form">
              <div className="starter-inputs-row">
                <div className="starter-input-box">
                  <label>Phép toán 1 (Worker 01):</label>
                  <input
                    type="text"
                    value={expr1}
                    onChange={e => setExpr1(e.target.value)}
                    placeholder="1+2"
                  />
                </div>

                <div className="starter-op-box">
                  <label>Tổng hợp:</label>
                  <select value={topOp} onChange={e => setTopOp(e.target.value)}>
                    <option value="+">+</option>
                    <option value="-">-</option>
                    <option value="*">*</option>
                    <option value="/">/</option>
                  </select>
                </div>

                <div className="starter-input-box">
                  <label>Phép toán 2 (Worker 02):</label>
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
                  <span>Thực Thi</span>
                </button>
              </div>

              {starterResult && (
                <div className="starter-result-banner">
                  <CheckCircle2 size={28} className="success-icon" />
                  <div className="result-text">
                    <h4>Kết quả tổng hợp tại Master:</h4>
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

        {/* TAB 4: BENCHMARK & PERFORMANCE LAB */}
        {activeTab === 'benchmark' && (
          <BenchmarkLab
            workers={workers}
            machines={machines}
            apiBase={API_BASE}
          />
        )}
      </main>
    </div>
  );
}
