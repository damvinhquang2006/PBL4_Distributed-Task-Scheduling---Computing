import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Check,
  Copy,
  Layers,
  Pause,
  Pencil,
  Play,
  PlusCircle,
  PowerOff,
  Server,
  Sliders,
  Square,
  X,
} from 'lucide-react';
import { MetricBar } from '../components/MetricBar';
import { StatusBadge, getWorkerStatusKey } from '../components/StatusBadge';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { formatRelativeTime, resolveWorkerLabel, shortHash, validateWorkerDisplayName } from '../utils/formatters';

const POLICIES = [
  {
    id: 'RESOURCE_AWARE',
    title: 'RESOURCE_AWARE',
    desc: 'Thông minh theo CPU/RAM/Heap (Khuyên dùng)',
  },
  {
    id: 'ROUND_ROBIN',
    title: 'ROUND_ROBIN',
    desc: 'Chia đều xoay vòng từng Worker',
  },
  {
    id: 'LEAST_LOADED',
    title: 'LEAST_LOADED',
    desc: 'Ưu tiên Worker rảnh nhất',
  },
];

export function ClusterManagementPage({ cluster, api, onRefresh, onNotify }) {
  const [machineFilter, setMachineFilter] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [searchText, setSearchText] = useState('');
  const [busyAction, setBusyAction] = useState(false);

  // Rename state
  const [editingWorkerId, setEditingWorkerId] = useState(null);
  const [renameDraft, setRenameDraft] = useState('');
  const [renameError, setRenameError] = useState(null);

  // Confirm dialogs
  const [confirmPauseWorker, setConfirmPauseWorker] = useState(null);
  const [confirmBatchState, setConfirmBatchState] = useState(null);

  // Panel 1: Local Worker Manager (Demo)
  const [localWorkersData, setLocalWorkersData] = useState(null);
  const [localCount, setLocalCount] = useState(3);
  const [localMachineChoice, setLocalMachineChoice] = useState('local-ui');
  const [localMachineCustom, setLocalMachineCustom] = useState('');
  const [machineChoiceTouched, setMachineChoiceTouched] = useState(false);
  const [localPrefix, setLocalPrefix] = useState('');
  const [localStartPaused, setLocalStartPaused] = useState(false);
  const [localBusy, setLocalBusy] = useState(false);
  const [localError, setLocalError] = useState(null);

  // Panel 2: Multi-select for batch worker state control
  const [selectedWorkerIds, setSelectedWorkerIds] = useState([]);

  const machines = cluster?.machines || [];
  const workers = cluster?.workers || [];
  const masterSha = cluster?.jarSha256;

  const refreshLocalWorkers = useCallback(async () => {
    try {
      const data = await api.getLocalWorkers();
      if (data && typeof data === 'object') {
        setLocalWorkersData(data);
        if (!machineChoiceTouched) {
          const suggested = data.defaultMachineId || machines[0]?.machineId || 'local-ui';
          setLocalMachineChoice(suggested);
        }
      }
    } catch {
      // Ignore silent background sync errors when Master is offline or older build
    }
  }, [api, machineChoiceTouched, machines]);

  useEffect(() => {
    refreshLocalWorkers();
  }, [refreshLocalWorkers, cluster]);

  // Keep selectedWorkerIds pruned to existing online workers
  useEffect(() => {
    const onlineSet = new Set(workers.filter((w) => w.status !== 'OFFLINE').map((w) => w.workerId));
    setSelectedWorkerIds((prev) => {
      const next = prev.filter((id) => onlineSet.has(id));
      return next.length === prev.length ? prev : next;
    });
  }, [workers]);

  const localBatches = useMemo(() => {
    if (Array.isArray(localWorkersData)) return localWorkersData;
    if (Array.isArray(localWorkersData?.batches)) return localWorkersData.batches;
    return [];
  }, [localWorkersData]);

  const aliveBatches = useMemo(() => localBatches.filter((b) => b.alive), [localBatches]);

  const machineIdOptions = useMemo(() => {
    const set = new Set();
    if (localWorkersData?.defaultMachineId) set.add(localWorkersData.defaultMachineId);
    for (const m of machines) {
      if (m.machineId) set.add(m.machineId);
    }
    set.add('local-ui');
    return Array.from(set);
  }, [localWorkersData, machines]);

  const effectiveLocalMachineId = useMemo(() => {
    if (localMachineChoice === '__custom__') {
      return localMachineCustom.trim() || 'local-ui';
    }
    return localMachineChoice || 'local-ui';
  }, [localMachineChoice, localMachineCustom]);

  const pausedOnlineCount = useMemo(
    () => workers.filter((w) => w.status !== 'OFFLINE' && w.paused).length,
    [workers]
  );

  const filteredWorkersByMachine = useMemo(() => {
    const q = searchText.trim().toLowerCase();
    const map = new Map();

    for (const w of workers) {
      const isCurrentlyEditing = editingWorkerId !== null && w.workerId === editingWorkerId;
      if (!isCurrentlyEditing) {
        if (machineFilter !== 'ALL' && w.machineId !== machineFilter) continue;

        if (statusFilter === 'READY' && !(w.connected && !w.paused && !w.quarantined && w.activeTasks === 0)) continue;
        if (statusFilter === 'RUNNING' && !(w.connected && w.activeTasks > 0)) continue;
        if (statusFilter === 'PAUSED' && !(w.connected && w.paused)) continue;
        if (statusFilter === 'OFFLINE' && w.status !== 'OFFLINE') continue;
        if (statusFilter === 'QUARANTINED' && !w.quarantined) continue;

        if (q) {
          const hay = `${w.workerId || ''} ${w.displayName || ''} ${w.host || ''} ${w.machineName || ''}`.toLowerCase();
          if (!hay.includes(q)) continue;
        }
      }

      const key = w.machineId || 'unknown';
      if (!map.has(key)) map.set(key, []);
      map.get(key).push(w);
    }

    return map;
  }, [workers, machineFilter, statusFilter, searchText, editingWorkerId]);

  const editingWorkerMachineId = useMemo(() => {
    if (editingWorkerId === null) return null;
    return workers.find((w) => w.workerId === editingWorkerId)?.machineId || null;
  }, [workers, editingWorkerId]);

  const visibleMachines = useMemo(() => {
    if (machineFilter !== 'ALL') {
      return machines.filter(
        (m) => m.machineId === machineFilter || (editingWorkerMachineId && m.machineId === editingWorkerMachineId)
      );
    }
    return machines;
  }, [machines, machineFilter, editingWorkerMachineId]);

  const machinesWithMatches = useMemo(() => {
    const hasFilter = statusFilter !== 'ALL' || Boolean(searchText.trim());
    return visibleMachines.filter((m) => {
      const machineWorkers = filteredWorkersByMachine.get(m.machineId) || [];
      return !(machineWorkers.length === 0 && hasFilter);
    });
  }, [visibleMachines, filteredWorkersByMachine, statusFilter, searchText]);

  const copyText = async (text, label) => {
    try {
      await navigator.clipboard?.writeText(text);
      onNotify?.(`Đã sao chép ${label}`, 'info');
    } catch {
      onNotify?.(`Không thể tự động sao chép ${label}`, 'warning');
    }
  };

  const handlePolicyChange = async (policyId) => {
    setBusyAction(true);
    try {
      await api.setPolicy(policyId);
      await onRefresh();
      onNotify?.(`Đã chuyển chính sách sang ${policyId}`, 'success');
    } catch (err) {
      onNotify?.(err.message, 'danger');
    } finally {
      setBusyAction(false);
    }
  };

  // Panel 1: Local worker start / stop
  const handleStartLocalWorkers = async (e) => {
    e?.preventDefault?.();
    const countNum = Number(localCount);
    if (!Number.isInteger(countNum) || countNum < 1 || countNum > 32) {
      setLocalError('Số lượng Worker khởi động mỗi lần phải từ 1 đến 32.');
      return;
    }

    setLocalBusy(true);
    setLocalError(null);
    try {
      const payload = {
        count: countNum,
        machineId: effectiveLocalMachineId,
        startPaused: Boolean(localStartPaused),
      };
      if (localPrefix.trim()) {
        payload.displayNamePrefix = localPrefix.trim();
      }
      const res = await api.startLocalWorkers(payload);
      await Promise.all([refreshLocalWorkers(), onRefresh()]);
      const startedCount = res?.count ?? countNum;
      onNotify?.(
        localStartPaused
          ? `Đã khởi động ${startedCount} Worker cục bộ ở trạng thái Tạm dừng (Warm Standby).`
          : `Đã khởi động ${startedCount} Worker cục bộ sẵn sàng nhận việc.`,
        'success'
      );
    } catch (err) {
      setLocalError(err.message);
      onNotify?.(err.message, 'danger');
    } finally {
      setLocalBusy(false);
    }
  };

  const handleStopBatch = async (batchId) => {
    setLocalBusy(true);
    setLocalError(null);
    try {
      await api.stopLocalWorkers({ batchId });
      await Promise.all([refreshLocalWorkers(), onRefresh()]);
      onNotify?.(`Đã dừng batch ${batchId} và chuyển Worker sang OFFLINE.`, 'info');
    } catch (err) {
      setLocalError(err.message);
      onNotify?.(err.message, 'danger');
    } finally {
      setLocalBusy(false);
    }
  };

  const handleStopAllBatches = async () => {
    setLocalBusy(true);
    setLocalError(null);
    try {
      await api.stopLocalWorkers({ all: true });
      await Promise.all([refreshLocalWorkers(), onRefresh()]);
      onNotify?.('Đã dừng toàn bộ batch Worker cục bộ.', 'info');
    } catch (err) {
      setLocalError(err.message);
      onNotify?.(err.message, 'danger');
    } finally {
      setLocalBusy(false);
    }
  };

  // Panel 2: Batch worker admission control
  const executeWorkersState = async (payload) => {
    setBusyAction(true);
    try {
      const res = await api.setWorkersState(payload);
      await onRefresh();
      const updatedCount = Array.isArray(res?.updatedWorkerIds) ? res.updatedWorkerIds.length : null;
      if (payload.action === 'RESUME_ALL') {
        onNotify?.(
          updatedCount !== null
            ? `Đã bật nhận việc cho ${updatedCount} Worker.`
            : 'Đã bật tất cả Worker đang tạm dừng.',
          'success'
        );
      } else if (payload.action === 'RESUME') {
        onNotify?.(
          updatedCount !== null
            ? `Đã bật nhận việc cho ${updatedCount} Worker đã chọn.`
            : 'Đã bật các Worker đã chọn.',
          'success'
        );
      } else if (payload.action === 'PAUSE') {
        onNotify?.(
          updatedCount !== null
            ? `Đã tạm dừng ${updatedCount} Worker đã chọn.`
            : 'Đã tạm dừng các Worker đã chọn.',
          'info'
        );
      } else if (payload.action === 'PAUSE_OTHERS') {
        onNotify?.(
          updatedCount !== null
            ? `Đã giữ nhóm được chọn và tạm dừng ${updatedCount} Worker còn lại.`
            : 'Đã tạm dừng các Worker còn lại.',
          'info'
        );
      }
    } catch (err) {
      onNotify?.(err.message, 'danger');
    } finally {
      setBusyAction(false);
    }
  };

  const getBusyWorkersAffectedByBatch = (payload) => {
    const onlineWorkers = workers.filter((w) => w.status !== 'OFFLINE');
    if (payload.action === 'PAUSE') {
      const idSet = new Set(payload.workerIds || []);
      return onlineWorkers.filter((w) => idSet.has(w.workerId) && (w.activeTasks || 0) > 0);
    }
    if (payload.action === 'PAUSE_OTHERS') {
      if (Array.isArray(payload.workerIds) && payload.workerIds.length > 0) {
        const keepSet = new Set(payload.workerIds);
        return onlineWorkers.filter((w) => !keepSet.has(w.workerId) && (w.activeTasks || 0) > 0);
      }
      if (payload.machineId) {
        return onlineWorkers.filter((w) => w.machineId !== payload.machineId && (w.activeTasks || 0) > 0);
      }
    }
    return [];
  };

  const requestWorkersState = (payload) => {
    const busyAffected = getBusyWorkersAffectedByBatch(payload);
    if (busyAffected.length > 0) {
      setConfirmBatchState({ payload, busyWorkers: busyAffected });
      return;
    }
    executeWorkersState(payload);
  };

  const toggleWorkerSelection = (workerId) => {
    setSelectedWorkerIds((prev) =>
      prev.includes(workerId) ? prev.filter((id) => id !== workerId) : [...prev, workerId]
    );
  };

  const toggleMachineSelection = (machineWorkers) => {
    const onlineIds = machineWorkers.filter((w) => w.status !== 'OFFLINE').map((w) => w.workerId);
    if (onlineIds.length === 0) return;
    const allSelected = onlineIds.every((id) => selectedWorkerIds.includes(id));
    setSelectedWorkerIds((prev) => {
      if (allSelected) {
        const removeSet = new Set(onlineIds);
        return prev.filter((id) => !removeSet.has(id));
      }
      const next = new Set(prev);
      for (const id of onlineIds) next.add(id);
      return Array.from(next);
    });
  };

  const executeToggleWorker = async (worker) => {
    setBusyAction(true);
    try {
      const res = await api.toggleWorker(worker.workerId);
      await onRefresh();
      onNotify?.(
        res.paused
          ? `Đã tạm dừng Worker ${worker.displayName || worker.workerId}`
          : `Đã bật lại Worker ${worker.displayName || worker.workerId}`,
        'info'
      );
    } catch (err) {
      onNotify?.(err.message, 'danger');
    } finally {
      setBusyAction(false);
    }
  };

  const handleToggleClick = (worker) => {
    if (!worker.paused && worker.activeTasks > 0) {
      setConfirmPauseWorker(worker);
      return;
    }
    executeToggleWorker(worker);
  };

  const startRename = (worker) => {
    setEditingWorkerId(worker.workerId);
    setRenameDraft(worker.displayName || worker.workerId);
    setRenameError(null);
  };

  const submitRename = async (workerId) => {
    const check = validateWorkerDisplayName(renameDraft);
    if (!check.valid) {
      setRenameError(check.error);
      return;
    }
    setBusyAction(true);
    try {
      await api.renameWorker(workerId, check.value);
      setEditingWorkerId(null);
      setRenameError(null);
      await onRefresh();
      onNotify?.(`Đã đổi tên hiển thị thành "${check.value}"`, 'success');
    } catch (err) {
      setRenameError(err.message);
    } finally {
      setBusyAction(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      {/* Block 1: Master Controls */}
      <div className="grid-2-equal">
        {/* Card 1: Master Info */}
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">
              <Server size={16} /> Thông tin Master
            </h2>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, fontSize: 13 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
              <span className="muted">Phiên (masterSession):</span>
              <span className="mono" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                {shortHash(cluster?.masterSession, 12)}
                {cluster?.masterSession && (
                  <button
                    type="button"
                    className="btn btn-sm btn-icon"
                    onClick={() => copyText(cluster.masterSession, 'masterSession')}
                    title="Sao chép Session ID"
                  >
                    <Copy size={12} />
                  </button>
                )}
              </span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span className="muted">Cổng TCP / HTTP:</span>
              <span className="mono">
                {cluster?.masterPort ?? '—'} / {cluster?.httpPort ?? '—'}
              </span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span className="muted">Giao thức (protocolVersion):</span>
              <span className="mono">v{cluster?.protocolVersion ?? '—'}</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
              <span className="muted">Bản build (jarSha256):</span>
              <span className="mono" title={masterSha || ''} style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                {shortHash(masterSha, 8)}
                {masterSha && (
                  <button
                    type="button"
                    className="btn btn-sm btn-icon"
                    onClick={() => copyText(masterSha, 'jarSha256')}
                    title="Sao chép SHA256 đầy đủ"
                  >
                    <Copy size={12} />
                  </button>
                )}
              </span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span className="muted">Hiệu năng khả dụng:</span>
              <strong className="mono">{cluster?.effectiveParallelism ?? 0} luồng</strong>
            </div>
          </div>
        </div>

        {/* Card 2: Load Balancing Policy */}
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">
              <Sliders size={16} /> Chính sách Cân bằng tải
            </h2>
          </div>
          <div className="policy-list">
            {POLICIES.map((p) => {
              const active = cluster?.policy === p.id;
              return (
                <button
                  key={p.id}
                  type="button"
                  disabled={busyAction || !cluster}
                  className={`policy-option ${active ? 'active' : ''}`}
                  onClick={() => handlePolicyChange(p.id)}
                >
                  <div className="policy-option-title">
                    <span className="mono">{p.title}</span>
                    {active && <span className="badge badge-info">Đang áp dụng</span>}
                  </div>
                  <div className="policy-option-desc">{p.desc}</div>
                </button>
              );
            })}
          </div>
        </div>
      </div>

      {/* Panel 1: Local Worker Manager (Demo) */}
      <div className="card">
        <div className="card-header">
          <div>
            <h2 className="card-title">
              <PlusCircle size={16} /> Khởi động / Dừng Worker Cục bộ (Demo)
            </h2>
            <p className="card-subtitle">
              Khởi chạy thêm tiến trình Worker ngay trên máy Master (tối đa 32 Worker/lần) hoặc dừng nhanh theo từng batch
            </p>
          </div>
          {aliveBatches.length > 0 && (
            <button
              type="button"
              className="btn btn-sm btn-danger"
              disabled={localBusy}
              onClick={handleStopAllBatches}
            >
              <PowerOff size={13} />
              <span>Dừng tất cả ({aliveBatches.length} batch)</span>
            </button>
          )}
        </div>

        <div className="grid-2-equal">
          <form onSubmit={handleStartLocalWorkers}>
            <div className="form-group">
              <span className="form-label">Số lượng Worker cần bật (1..32)</span>
              <div className="form-row" style={{ alignItems: 'center' }}>
                <div className="chip-row">
                  {[1, 3, 5].map((preset) => (
                    <button
                      key={preset}
                      type="button"
                      className={`chip ${Number(localCount) === preset ? 'active' : ''}`}
                      disabled={localBusy}
                      onClick={() => setLocalCount(preset)}
                    >
                      +{preset}
                    </button>
                  ))}
                </div>
                <input
                  type="number"
                  min={1}
                  max={32}
                  aria-label="Số lượng Worker cục bộ"
                  className="input-sm port-input"
                  value={localCount}
                  onChange={(e) => setLocalCount(e.target.value)}
                  disabled={localBusy}
                />
              </div>
            </div>

            <div className="form-row" style={{ marginBottom: 12 }}>
              <div className="form-group" style={{ flex: 1, minWidth: 180, marginBottom: 0 }}>
                <label className="form-label" htmlFor="local-machine-select">
                  Nhóm máy (machineId)
                </label>
                <select
                  id="local-machine-select"
                  className="select"
                  value={localMachineChoice}
                  disabled={localBusy}
                  onChange={(e) => {
                    setMachineChoiceTouched(true);
                    setLocalMachineChoice(e.target.value);
                  }}
                >
                  {machineIdOptions.map((id) => (
                    <option key={id} value={id}>
                      {id === localWorkersData?.defaultMachineId
                        ? `${id} (Mặc định gợi ý)`
                        : id === 'local-ui'
                          ? 'local-ui (Nhóm riêng cục bộ)'
                          : id}
                    </option>
                  ))}
                  <option value="__custom__">Nhập machineId khác...</option>
                </select>
              </div>

              {localMachineChoice === '__custom__' && (
                <div className="form-group" style={{ flex: 1, minWidth: 150, marginBottom: 0 }}>
                  <label className="form-label" htmlFor="local-machine-custom">
                    machineId tùy chỉnh
                  </label>
                  <input
                    id="local-machine-custom"
                    type="text"
                    className="input"
                    placeholder="local-ui"
                    value={localMachineCustom}
                    onChange={(e) => setLocalMachineCustom(e.target.value)}
                    disabled={localBusy}
                  />
                </div>
              )}

              <div className="form-group" style={{ flex: 1, minWidth: 160, marginBottom: 0 }}>
                <label className="form-label" htmlFor="local-prefix-input">
                  Tiền tố tên (displayNamePrefix)
                </label>
                <input
                  id="local-prefix-input"
                  type="text"
                  className="input"
                  placeholder="VD: DemoNode"
                  value={localPrefix}
                  onChange={(e) => setLocalPrefix(e.target.value)}
                  disabled={localBusy}
                />
              </div>
            </div>

            <div className="hint-box" style={{ marginBottom: 12 }}>
              <strong>Cảnh báo CPU dùng chung:</strong> Các Worker khởi động tại đây chạy trên cùng CPU vật lý với Master.
              Nên dùng <code>{localWorkersData?.defaultMachineId || 'machineId của Master'}</code> để{' '}
              <code>TaskScheduler</code> gom chung <code>HostParallelism</code>, tránh nhân đôi tải CPU thật.
            </div>

            <label className="inline-control" style={{ marginBottom: 12, cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={localStartPaused}
                onChange={(e) => setLocalStartPaused(e.target.checked)}
                disabled={localBusy}
              />
              <span>Khởi động ở trạng thái Tạm dừng (Warm Standby)</span>
            </label>

            {localError && (
              <div className="banner banner-danger" style={{ marginBottom: 12 }} role="alert">
                <span>{localError}</span>
              </div>
            )}

            <button
              type="submit"
              className="btn btn-primary"
              disabled={localBusy || !cluster}
            >
              <PlusCircle size={15} />
              <span>{localBusy ? 'Đang xử lý Worker cục bộ...' : `Khởi động ${localCount || 0} Worker cục bộ`}</span>
            </button>
          </form>

          <div>
            <div className="form-label" style={{ marginBottom: 8 }}>
              <span>Danh sách Batch Worker cục bộ ({localBatches.length})</span>
              <span className="mono muted">{aliveBatches.length} đang chạy</span>
            </div>
            {localBatches.length === 0 ? (
              <div className="empty-state" style={{ padding: 20 }}>
                Chưa có batch Worker cục bộ nào được khởi động từ giao diện.
              </div>
            ) : (
              <div className="batch-list">
                {localBatches.map((batch) => {
                  const batchWorkerLabels = Array.isArray(batch.workerIds)
                    ? batch.workerIds.map((id) => resolveWorkerLabel(id, cluster)).join(', ')
                    : '';
                  return (
                    <div key={batch.batchId} className="batch-item">
                      <div style={{ minWidth: 0, flex: 1 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                          <strong className="mono">{batch.batchId}</strong>
                          <span className={`badge ${batch.alive ? 'badge-success' : 'badge-neutral'}`}>
                            {batch.alive ? 'Đang chạy' : 'Đã dừng'}
                          </span>
                          <span className="badge badge-neutral mono">{batch.machineId}</span>
                        </div>
                        <div className="muted" style={{ fontSize: 11, marginTop: 4 }}>
                          Số lượng: <strong className="mono">{batch.count}</strong> | Port bắt đầu:{' '}
                          <span className="mono">{batch.startPort}</span>
                        </div>
                        {batchWorkerLabels && (
                          <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>
                            Workers: <strong>{batchWorkerLabels}</strong>
                          </div>
                        )}
                      </div>
                      {batch.alive && (
                        <button
                          type="button"
                          className="btn btn-sm btn-danger"
                          disabled={localBusy}
                          onClick={() => handleStopBatch(batch.batchId)}
                        >
                          <Square size={12} />
                          <span>Dừng batch</span>
                        </button>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Block 2: Filter Bar + Panel 2 Batch Controls + Machines & Workers */}
      <div className="card">
        <div className="card-header">
          <div>
            <h2 className="card-title">Danh sách Máy & Đội Worker ({workers.length})</h2>
            <p className="card-subtitle">
              Worker được gom nhóm theo từng máy vật lý/ảo (machineId) trong cùng phiên Master
            </p>
          </div>
          <div className="form-row">
            <select
              aria-label="Lọc theo máy"
              className="select-sm"
              value={machineFilter}
              onChange={(e) => setMachineFilter(e.target.value)}
            >
              <option value="ALL">Tất cả máy ({machines.length})</option>
              {machines.map((m) => (
                <option key={m.machineId} value={m.machineId}>
                  {m.machineName} ({m.machineId})
                </option>
              ))}
            </select>

            <select
              aria-label="Lọc theo trạng thái"
              className="select-sm"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
            >
              <option value="ALL">Tất cả trạng thái</option>
              <option value="READY">Sẵn sàng</option>
              <option value="RUNNING">Đang chạy</option>
              <option value="PAUSED">Tạm dừng</option>
              <option value="OFFLINE">Offline</option>
              <option value="QUARANTINED">Cách ly</option>
            </select>

            <input
              type="search"
              className="input-sm"
              style={{ minWidth: 200 }}
              placeholder="Tìm workerId, tên, IP..."
              value={searchText}
              onChange={(e) => setSearchText(e.target.value)}
            />
          </div>
        </div>

        {/* Panel 2: Batch Worker Admission Control */}
        <div className="batch-toolbar" role="region" aria-label="Điều phối Nhận việc Hàng loạt">
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600 }}>
              <Layers size={14} /> Điều phối Hàng loạt:
            </span>
            <button
              type="button"
              className="btn btn-sm btn-success"
              disabled={busyAction || !cluster || pausedOnlineCount === 0}
              onClick={() => requestWorkersState({ action: 'RESUME_ALL' })}
            >
              <Play size={13} />
              <span>Bật tất cả Worker đang tạm dừng ({pausedOnlineCount})</span>
            </button>
            <button
              type="button"
              className="btn btn-sm btn-warning"
              disabled={busyAction || !cluster || machineFilter === 'ALL'}
              onClick={() => requestWorkersState({ action: 'PAUSE_OTHERS', machineId: machineFilter })}
              title={
                machineFilter === 'ALL'
                  ? 'Hãy chọn 1 máy ở bộ lọc phía trên để giữ máy đó và tạm dừng các máy khác'
                  : `Giữ máy ${machineFilter} hoạt động và tạm dừng toàn bộ Worker ở các máy khác`
              }
            >
              <Pause size={13} />
              <span>Chỉ giữ máy đang lọc &amp; Tạm dừng còn lại</span>
            </button>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
            <span className="badge badge-neutral mono">{selectedWorkerIds.length} đã chọn</span>
            <button
              type="button"
              className="btn btn-sm btn-success"
              disabled={busyAction || selectedWorkerIds.length === 0}
              onClick={() => requestWorkersState({ action: 'RESUME', workerIds: selectedWorkerIds })}
            >
              <Play size={12} />
              <span>Bật đã chọn</span>
            </button>
            <button
              type="button"
              className="btn btn-sm btn-warning"
              disabled={busyAction || selectedWorkerIds.length === 0}
              onClick={() => requestWorkersState({ action: 'PAUSE', workerIds: selectedWorkerIds })}
            >
              <Pause size={12} />
              <span>Tạm dừng đã chọn</span>
            </button>
            <button
              type="button"
              className="btn btn-sm"
              disabled={busyAction || selectedWorkerIds.length === 0}
              onClick={() => requestWorkersState({ action: 'PAUSE_OTHERS', workerIds: selectedWorkerIds })}
            >
              <span>Giữ đã chọn &amp; Tạm dừng còn lại</span>
            </button>
            {selectedWorkerIds.length > 0 && (
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => setSelectedWorkerIds([])}
              >
                Bỏ chọn
              </button>
            )}
          </div>
        </div>

        {visibleMachines.length === 0 ? (
          <div className="empty-state">
            Chưa có Worker nào đăng ký với Master. Hãy khởi chạy Worker bằng <code>scripts/start-lan-workers.ps1</code>.
          </div>
        ) : machinesWithMatches.length === 0 ? (
          <div className="empty-state">
            <p style={{ margin: '0 0 10px' }}>Không có Worker nào khớp với bộ lọc hiện tại.</p>
            <button
              type="button"
              className="btn btn-sm"
              onClick={() => {
                setMachineFilter('ALL');
                setStatusFilter('ALL');
                setSearchText('');
              }}
            >
              Xóa bộ lọc
            </button>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {machinesWithMatches.map((m) => {
              const machineWorkers = filteredWorkersByMachine.get(m.machineId) || [];

              return (
                <div key={m.machineId} className="card" style={{ background: 'var(--bg-surface-2)', padding: 14 }}>
                  <div className="card-header" style={{ marginBottom: 12 }}>
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                        <strong style={{ fontSize: 15 }}>{m.machineName || m.machineId}</strong>
                        <span className="badge badge-neutral mono">{m.machineId}</span>
                        {m.host && <span className="mono muted" style={{ fontSize: 12 }}>IP: {m.host}</span>}
                      </div>
                      <div className="muted" style={{ fontSize: 12, marginTop: 2 }}>
                        Sẵn sàng: <strong className="mono">{m.readyWorkers}/{m.totalWorkers}</strong> | Hiệu năng hiệu dụng:{' '}
                        <strong className="mono">{m.effectiveParallelism}/{m.hostParallelism}</strong> luồng | Đã xong:{' '}
                        <strong className="mono">{m.completedTasks}</strong> tasks
                      </div>
                    </div>

                    <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                      <MetricBar label="CPU" value={m.metrics?.cpu} compact />
                      <MetricBar label="RAM" value={m.metrics?.ram} compact />
                      <MetricBar label="Heap" value={m.metrics?.heap} compact />
                    </div>
                  </div>

                  <div className="table-wrap" style={{ background: 'var(--bg-surface)' }}>
                    <table>
                      <thead>
                        <tr>
                          <th style={{ width: 34 }}>
                            <input
                              type="checkbox"
                              aria-label={`Chọn tất cả worker trên ${m.machineName || m.machineId}`}
                              checked={
                                machineWorkers.some((w) => w.status !== 'OFFLINE') &&
                                machineWorkers
                                  .filter((w) => w.status !== 'OFFLINE')
                                  .every((w) => selectedWorkerIds.includes(w.workerId))
                              }
                              onChange={() => toggleMachineSelection(machineWorkers)}
                            />
                          </th>
                          <th>Tên hiển thị & ID</th>
                          <th>Kết nối & Build</th>
                          <th>Trạng thái</th>
                          <th>Task đang giữ</th>
                          <th>Tải & Tài nguyên</th>
                          <th>Heartbeat</th>
                          <th>Thao tác</th>
                        </tr>
                      </thead>
                      <tbody>
                        {machineWorkers.map((w) => {
                          const jarMismatch = Boolean(masterSha && w.jarSha256 && w.jarSha256 !== masterSha);
                          const isEditing = editingWorkerId === w.workerId;
                          const isOffline = w.status === 'OFFLINE';

                          return (
                            <tr key={w.workerId}>
                              <td>
                                <input
                                  type="checkbox"
                                  aria-label={`Chọn ${w.displayName || w.workerId}`}
                                  disabled={isOffline}
                                  checked={selectedWorkerIds.includes(w.workerId)}
                                  onChange={() => toggleWorkerSelection(w.workerId)}
                                />
                              </td>
                              <td>
                                {isEditing ? (
                                  <div>
                                    <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
                                      <input
                                        type="text"
                                        className="input-sm"
                                        style={{ width: 150 }}
                                        value={renameDraft}
                                        onChange={(e) => setRenameDraft(e.target.value)}
                                        onKeyDown={(e) => {
                                          if (e.key === 'Enter') submitRename(w.workerId);
                                          if (e.key === 'Escape') setEditingWorkerId(null);
                                        }}
                                        aria-label={`Tên mới cho ${w.workerId}`}
                                      />
                                      <button
                                        type="button"
                                        className="btn btn-sm btn-primary btn-icon"
                                        onClick={() => submitRename(w.workerId)}
                                        title="Lưu tên mới"
                                      >
                                        <Check size={13} />
                                      </button>
                                      <button
                                        type="button"
                                        className="btn btn-sm btn-icon"
                                        onClick={() => setEditingWorkerId(null)}
                                        title="Hủy"
                                      >
                                        <X size={13} />
                                      </button>
                                    </div>
                                    {renameError && <div className="error-text" style={{ marginTop: 4 }}>{renameError}</div>}
                                  </div>
                                ) : (
                                  <div>
                                    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                                      <strong>{w.displayName || w.workerId}</strong>
                                      <button
                                        type="button"
                                        className="btn btn-sm btn-icon"
                                        onClick={() => startRename(w)}
                                        title="Đổi tên hiển thị Worker"
                                        aria-label={`Đổi tên ${w.displayName || w.workerId}`}
                                      >
                                        <Pencil size={12} />
                                      </button>
                                    </div>
                                    <div className="mono muted" style={{ fontSize: 11 }}>{w.workerId}</div>
                                  </div>
                                )}
                              </td>

                              <td>
                                <div className="mono" style={{ fontSize: 12 }}>
                                  {w.host}:{w.port}
                                </div>
                                <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginTop: 3 }}>
                                  <span className="badge badge-neutral mono">grp:{w.processGroupId || 'default'}</span>
                                  {w.supervised && <span className="badge badge-info">supervised</span>}
                                  {jarMismatch && (
                                    <span className="badge badge-warning" title={`Worker JAR: ${w.jarSha256} != Master: ${masterSha}`}>
                                      Lệch phiên bản JAR
                                    </span>
                                  )}
                                </div>
                              </td>

                              <td>
                                <StatusBadge status={getWorkerStatusKey(w)} />
                              </td>

                              <td>
                                {Array.isArray(w.currentTaskIds) && w.currentTaskIds.length > 0 ? (
                                  <div
                                    className="mono"
                                    style={{ display: 'flex', gap: 4, flexWrap: 'wrap', fontSize: 11 }}
                                    title={w.currentTaskIds.join(', ')}
                                  >
                                    {w.currentTaskIds.slice(0, 2).map((taskId) => (
                                      <span key={taskId} className="badge badge-info mono">
                                        {taskId}
                                      </span>
                                    ))}
                                    {w.currentTaskIds.length > 2 && (
                                      <span className="badge badge-neutral mono">+{w.currentTaskIds.length - 2}</span>
                                    )}
                                  </div>
                                ) : w.activeTasks > 0 ? (
                                  <span className="badge badge-info mono">Đang chạy {w.activeTasks} task</span>
                                ) : (
                                  <span className="muted mono">—</span>
                                )}
                              </td>

                              <td>
                                <div className="mono" style={{ fontSize: 12, marginBottom: 4 }}>
                                  Task: <strong>{w.activeTasks}/{w.capacity}</strong> | Xong: <strong>{w.completedTasks}</strong>
                                </div>
                                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                                  <MetricBar label="CPU" value={w.metrics?.cpu} compact />
                                  <MetricBar label="RAM" value={w.metrics?.ram} compact />
                                  <MetricBar label="Heap" value={w.metrics?.heap} compact />
                                </div>
                              </td>

                              <td className="muted" style={{ fontSize: 12 }}>
                                {formatRelativeTime(w.lastHeartbeatTime)}
                              </td>

                              <td>
                                <button
                                  type="button"
                                  className={`btn btn-sm ${w.paused ? 'btn-success' : 'btn-warning'}`}
                                  disabled={busyAction || isOffline}
                                  onClick={() => handleToggleClick(w)}
                                  title={
                                    isOffline
                                      ? 'Worker đang ngoại tuyến, không thể thao tác'
                                      : w.paused
                                        ? 'Cho phép Worker nhận việc trở lại'
                                        : 'Tạm dừng giao việc mới cho Worker này'
                                  }
                                >
                                  {w.paused ? <Play size={13} /> : <Pause size={13} />}
                                  <span>{w.paused ? 'Bật lại' : 'Tạm dừng'}</span>
                                </button>
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      <ConfirmDialog
        open={Boolean(confirmPauseWorker)}
        title="Worker đang xử lý tác vụ"
        message={`Worker "${confirmPauseWorker?.displayName || confirmPauseWorker?.workerId}" đang có ${confirmPauseWorker?.activeTasks} tác vụ đang chạy. Tạm dừng sẽ không giao việc mới (việc đang chạy vẫn hoàn tất). Bạn có chắc muốn tạm dừng?`}
        confirmLabel="Tạm dừng Worker"
        onConfirm={() => {
          const w = confirmPauseWorker;
          setConfirmPauseWorker(null);
          if (w) executeToggleWorker(w);
        }}
        onCancel={() => setConfirmPauseWorker(null)}
      />

      <ConfirmDialog
        open={Boolean(confirmBatchState)}
        title="Xác nhận tạm dừng Worker đang có tác vụ"
        message={
          confirmBatchState
            ? `Có ${confirmBatchState.busyWorkers.length} Worker bị tác động đang xử lý tác vụ (${confirmBatchState.busyWorkers
                .map((w) => resolveWorkerLabel(w.workerId, cluster, w.displayName))
                .join(', ')}). Tạm dừng sẽ ngừng giao việc mới (các tác vụ đang chạy vẫn tiếp tục hoàn tất). Bạn có chắc muốn tiếp tục?`
            : ''
        }
        confirmLabel="Xác nhận tạm dừng"
        onConfirm={() => {
          const pending = confirmBatchState;
          setConfirmBatchState(null);
          if (pending?.payload) executeWorkersState(pending.payload);
        }}
        onCancel={() => setConfirmBatchState(null)}
      />
    </div>
  );
}
