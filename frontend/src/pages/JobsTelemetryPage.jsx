import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
  Download,
  RefreshCw,
  XCircle,
} from 'lucide-react';
import { StatusBadge, getWorkerStatusKey } from '../components/StatusBadge';
import { MetricBar } from '../components/MetricBar';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { formatDurationMs, formatNumber, formatTimestamp, resolveWorkerLabel, shortHash } from '../utils/formatters';
import { downloadTextFile, matrixToCsv } from '../utils/matrixHelpers';

const JOB_STATUSES = ['ALL', 'RUNNING', 'RECOVERING', 'COMPLETED', 'FAILED', 'CANCELLED'];

export function JobsTelemetryPage({
  cluster,
  jobs = [],
  selectedJobId,
  onSelectJobId,
  api,
  onRefresh,
  onNotify,
}) {
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [jobProgress, setJobProgress] = useState(null);
  const [progressError, setProgressError] = useState(null);
  const [cancelTarget, setCancelTarget] = useState(null);

  // Result pagination state
  const [startRow, setStartRow] = useState(0);
  const [startCol, setStartCol] = useState(0);
  const [windowSize, setWindowSize] = useState(16); // 16 => 16x16, 32 => 32x16
  const [jumpRow, setJumpRow] = useState('0');
  const [jumpCol, setJumpCol] = useState('0');
  const [resultPage, setResultPage] = useState(null);
  const [resultLoading, setResultLoading] = useState(false);
  const [resultError, setResultError] = useState(null);

  const sortedJobs = useMemo(() => {
    const list = [...jobs].sort((a, b) => (b.startedAt || 0) - (a.startedAt || 0));
    if (statusFilter === 'ALL') return list;
    return list.filter((j) => j.status === statusFilter);
  }, [jobs, statusFilter]);

  // Auto select first job if none selected
  useEffect(() => {
    if (!selectedJobId && sortedJobs.length > 0) {
      onSelectJobId?.(sortedJobs[0].jobId);
    }
  }, [selectedJobId, sortedJobs, onSelectJobId]);

  // Reset pagination and stale progress when changing selected job
  useEffect(() => {
    setJobProgress(null);
    setProgressError(null);
    setStartRow(0);
    setStartCol(0);
    setJumpRow('0');
    setJumpCol('0');
    setResultPage(null);
    setResultError(null);
  }, [selectedJobId]);

  const fetchProgress = useCallback(async () => {
    if (!selectedJobId) {
      setJobProgress(null);
      return;
    }
    try {
      const data = await api.getJobProgress(selectedJobId, { timeoutMs: 5000 });
      if (data?.jobId && data.jobId !== selectedJobId) return;
      setJobProgress(data);
      setProgressError(null);
    } catch (err) {
      setProgressError(err.message);
    }
  }, [selectedJobId, api]);

  // Poll selected job progress
  useEffect(() => {
    fetchProgress();
    if (!selectedJobId) return undefined;
    const timer = setInterval(fetchProgress, 1500);
    return () => clearInterval(timer);
  }, [fetchProgress, selectedJobId]);

  const isCurrentProgress = Boolean(jobProgress && jobProgress.jobId === selectedJobId);

  const fetchResultPage = useCallback(async () => {
    if (!selectedJobId || !isCurrentProgress || jobProgress?.status !== 'COMPLETED') return;
    setResultLoading(true);
    setResultError(null);
    try {
      const rowCount = windowSize;
      const colCount = 16;
      const page = await api.getJobResult(selectedJobId, {
        startRow,
        rowCount,
        startCol,
        colCount,
      });
      setResultPage(page);
    } catch (err) {
      setResultError(err.message);
    } finally {
      setResultLoading(false);
    }
  }, [selectedJobId, isCurrentProgress, jobProgress?.status, startRow, startCol, windowSize, api]);

  useEffect(() => {
    if (isCurrentProgress && jobProgress?.status === 'COMPLETED') {
      fetchResultPage();
    }
  }, [isCurrentProgress, jobProgress?.status, fetchResultPage]);

  const handleCancelJob = async () => {
    if (!cancelTarget) return;
    const id = cancelTarget.jobId;
    setCancelTarget(null);
    try {
      const res = await api.cancelJob(id);
      await onRefresh?.();
      await fetchProgress();
      onNotify?.(
        res.cancelled ? `Đã hủy Job ${shortHash(id, 12)}` : 'Không thể hủy Job (có thể đã hoàn tất).',
        res.cancelled ? 'warning' : 'info'
      );
    } catch (err) {
      onNotify?.(err.message, 'danger');
    }
  };

  const sortedFailoverEvents = useMemo(() => {
    const evts = jobProgress?.failoverEvents || [];
    return [...evts].sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
  }, [jobProgress]);

  const scalarValue = useMemo(() => {
    if (resultPage && resultPage.scalar !== undefined) return resultPage.scalar;
    if (jobProgress?.resultSummary?.isScalar) return jobProgress.resultSummary.resultScalar;
    return null;
  }, [resultPage, jobProgress]);

  const totalRows = resultPage?.rows ?? 0;
  const totalCols = resultPage?.cols ?? 0;
  const pageCols = 16;

  const handleJump = (e) => {
    e.preventDefault();
    const r = Math.max(0, Math.min(Math.max(0, totalRows - 1), Number(jumpRow) || 0));
    const c = Math.max(0, Math.min(Math.max(0, totalCols - 1), Number(jumpCol) || 0));
    setStartRow(r);
    setStartCol(c);
  };

  return (
    <div className="jobs-layout">
      {/* Left Column: Job List */}
      <div className="card">
        <div className="card-header">
          <div>
            <h2 className="card-title">
              <Activity size={16} /> Danh sách Job ({sortedJobs.length})
            </h2>
            <p className="card-subtitle">Mới nhất hiển thị trên đầu</p>
          </div>
          <select
            aria-label="Lọc trạng thái Job"
            className="select-sm"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
          >
            {JOB_STATUSES.map((st) => (
              <option key={st} value={st}>
                {st === 'ALL' ? 'Tất cả trạng thái' : st}
              </option>
            ))}
          </select>
        </div>

        {sortedJobs.length === 0 ? (
          <div className="empty-state">
            Chưa có Job bất đồng bộ nào. Hãy gửi job quy mô lớn ở Tab <strong>1. Giải bài toán</strong>.
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, maxHeight: '72vh', overflowY: 'auto' }}>
            {sortedJobs.map((job) => {
              const active = job.jobId === selectedJobId;
              return (
                <div
                  key={job.jobId}
                  className={`job-item ${active ? 'active' : ''}`}
                  role="button"
                  tabIndex={0}
                  onClick={() => onSelectJobId?.(job.jobId)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      onSelectJobId?.(job.jobId);
                    }
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
                    <strong style={{ fontSize: 13 }}>{job.title}</strong>
                    <StatusBadge status={job.status} />
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11 }} className="muted mono">
                    <span title={job.jobId}>{shortHash(job.jobId, 18)}</span>
                    <span>{formatTimestamp(job.startedAt)}</span>
                  </div>
                  <div className="progress-track">
                    <div
                      className={`progress-fill ${job.status === 'COMPLETED' ? 'success' : job.status === 'FAILED' ? 'danger' : ''}`}
                      style={{ width: `${Math.min(100, Number(job.progressPercent || 0))}%` }}
                    />
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 12 }}>
                    <span className="mono">
                      {job.completedTasks}/{job.totalTasks} tasks ({Number(job.progressPercent || 0).toFixed(1)}%)
                    </span>
                    {job.status === 'RUNNING' && (
                      <button
                        type="button"
                        className="btn btn-sm btn-danger"
                        onClick={(e) => {
                          e.stopPropagation();
                          setCancelTarget(job);
                        }}
                      >
                        <XCircle size={13} /> Hủy
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Right Column: Selected Job Telemetry & Result Browser */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        {!selectedJobId ? (
          <div className="card empty-state">Chọn một Job ở danh sách bên trái để xem tiến độ và kết quả chi tiết.</div>
        ) : progressError ? (
          <div className="card banner banner-danger">
            <span>{progressError}</span>
          </div>
        ) : !isCurrentProgress ? (
          <div className="card empty-state">Đang tải thông tin chi tiết Job...</div>
        ) : (
          <>
            {/* 1. Progress Overview */}
            <div className="card">
              <div className="card-header">
                <div>
                  <h2 className="card-title">{jobProgress.title}</h2>
                  <div className="mono muted" style={{ fontSize: 12 }}>{jobProgress.jobId}</div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <StatusBadge status={jobProgress.status} />
                  <button type="button" className="btn btn-sm" onClick={fetchProgress} title="Làm mới tiến độ">
                    <RefreshCw size={13} />
                  </button>
                </div>
              </div>

              <div style={{ marginBottom: 14 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 6 }}>
                  <span>
                    Hoàn thành: <strong className="mono">{jobProgress.completedTasks}/{jobProgress.totalTasks}</strong> tasks
                  </span>
                  <strong className="mono">{Number(jobProgress.progressPercent || 0).toFixed(1)}%</strong>
                </div>
                <div className="progress-track">
                  <div
                    className={`progress-fill ${jobProgress.status === 'COMPLETED' ? 'success' : jobProgress.status === 'FAILED' ? 'danger' : ''}`}
                    style={{ width: `${Math.min(100, Number(jobProgress.progressPercent || 0))}%` }}
                  />
                </div>
              </div>

              <div className="kpi-grid" style={{ marginBottom: 12 }}>
                <div className="kpi-card">
                  <div className="kpi-label">Thời gian đã chạy</div>
                  <div className="kpi-value">{formatDurationMs(jobProgress.elapsedMs)}</div>
                </div>
                <div className="kpi-card">
                  <div className="kpi-label">Ước tính còn lại (ETA)</div>
                  <div className="kpi-value">{formatDurationMs(jobProgress.etaMs)}</div>
                </div>
                <div className="kpi-card">
                  <div className="kpi-label">Trong hàng đợi</div>
                  <div className="kpi-value">{jobProgress.queueSize ?? 0}</div>
                </div>
                <div className="kpi-card">
                  <div className="kpi-label">Đang thực thi (In-flight)</div>
                  <div className="kpi-value">{jobProgress.inflightCount ?? 0}</div>
                </div>
              </div>

              {jobProgress.waitingReason &&
                jobProgress.inflightCount === 0 &&
                (jobProgress.status === 'RUNNING' || jobProgress.status === 'RECOVERING') && (
                  <div className="banner banner-warning" style={{ marginBottom: 10 }}>
                    <div className="banner-body">
                      <AlertTriangle size={16} />
                      <span>
                        <strong>Hàng đợi đang chờ:</strong> {jobProgress.waitingReason}
                      </span>
                    </div>
                  </div>
                )}

              {(jobProgress.status === 'FAILED' || jobProgress.status === 'CANCELLED') && jobProgress.error && (
                <div className="banner banner-danger">
                  <span>{jobProgress.error}</span>
                </div>
              )}
            </div>

            {/* 2. Worker Fleet Contribution */}
            <div className="card">
              <div className="card-header">
                <h2 className="card-title">Đóng góp theo từng Worker ({jobProgress.workerFleet?.length ?? 0})</h2>
              </div>

              {!jobProgress.workerFleet || jobProgress.workerFleet.length === 0 ? (
                <div className="empty-state" style={{ padding: 16 }}>Chưa có Worker nào ghi nhận trong phiên.</div>
              ) : (
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>Worker</th>
                        <th>Máy</th>
                        <th>Trạng thái</th>
                        <th>Task xong (Job)</th>
                        <th>Task đang giữ</th>
                        <th>Tài nguyên</th>
                      </tr>
                    </thead>
                    <tbody>
                      {jobProgress.workerFleet.map((w) => {
                        const workerLabel = resolveWorkerLabel(
                          w.workerId,
                          [cluster, jobProgress.workerFleet],
                          w.displayName
                        );
                        const clusterWorker = (cluster?.workers || []).find((cw) => cw.workerId === w.workerId);
                        const heldTasks = w.assignedTask
                          ? [w.assignedTask]
                          : Array.isArray(clusterWorker?.currentTaskIds)
                            ? clusterWorker.currentTaskIds
                            : [];
                        return (
                          <tr key={w.workerId}>
                            <td>
                              <strong>{workerLabel}</strong>
                              <div className="mono muted" style={{ fontSize: 11 }}>{w.workerId}</div>
                            </td>
                            <td>
                              <div>{w.machineName || w.machineId}</div>
                              <div className="mono muted" style={{ fontSize: 11 }}>{w.host}:{w.port}</div>
                            </td>
                            <td>
                              <StatusBadge status={getWorkerStatusKey(w)} />
                            </td>
                            <td className="mono"><strong>{w.jobCompletedTasks ?? 0}</strong></td>
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
                            <td>
                              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                                <MetricBar label="CPU" value={w.metrics?.cpu} compact />
                                <MetricBar label="RAM" value={w.metrics?.ram} compact />
                                <MetricBar label="Heap" value={w.metrics?.heap} compact />
                              </div>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            {/* 3. Failover Events */}
            <div className="card">
              <div className="card-header">
                <h2 className="card-title">Nhật ký Failover ({sortedFailoverEvents.length})</h2>
              </div>

              {sortedFailoverEvents.length === 0 ? (
                <div className="hint-box">Chưa có sự cố failover nào trong phiên làm việc hiện tại.</div>
              ) : (
                <div className="timeline">
                  {sortedFailoverEvents.map((evt, idx) => {
                    const workerLabel = resolveWorkerLabel(
                      evt.workerId,
                      [cluster, jobProgress?.workerFleet],
                      evt.displayName
                    );
                    return (
                      <div key={`${evt.timestamp || idx}-${evt.taskId || idx}`} className="timeline-item">
                        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, marginBottom: 4 }}>
                          <strong>
                            Worker: <span>{workerLabel}</span>
                            {workerLabel !== evt.workerId && evt.workerId && (
                              <span className="mono muted"> ({evt.workerId})</span>
                            )}{' '}
                            | Task: <span className="mono">{evt.taskId}</span> (Lần thử #{evt.retryCount})
                          </strong>
                          <span className="mono muted">{formatTimestamp(evt.timestamp)}</span>
                        </div>
                        <div>{evt.message || evt.reason}</div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {/* 4. Result Browser (when COMPLETED) */}
            {jobProgress.status === 'COMPLETED' && (
              <div className="card">
                <div className="card-header">
                  <div>
                    <h2 className="card-title">Trình duyệt Kết quả</h2>
                    {resultPage?.rows != null && (
                      <p className="card-subtitle">
                        Ma trận kích thước <strong className="mono">{resultPage.rows} × {resultPage.cols}</strong>
                      </p>
                    )}
                  </div>
                  {Array.isArray(resultPage?.result) && (
                    <button
                      type="button"
                      className="btn btn-sm"
                      onClick={() =>
                        downloadTextFile(
                          `${selectedJobId}-r${startRow}-c${startCol}.csv`,
                          matrixToCsv(resultPage.result)
                        )
                      }
                    >
                      <Download size={13} /> Tải CSV trang hiện tại
                    </button>
                  )}
                </div>

                {resultError && (
                  <div className="banner banner-danger" style={{ marginBottom: 12 }}>
                    <span>{resultError}</span>
                  </div>
                )}

                {scalarValue !== null ? (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                    <div className="kpi-card" style={{ textAlign: 'center' }}>
                      <div className="kpi-label">Giá trị vô hướng ({jobProgress.operation})</div>
                      <div className="kpi-value" style={{ fontSize: 30, color: 'var(--success)' }}>
                        {formatNumber(scalarValue)}
                      </div>
                    </div>
                    {jobProgress.resultSummary?.overflow && jobProgress.resultSummary?.note && (
                      <div className="banner banner-warning">
                        <span>{jobProgress.resultSummary.note}</span>
                      </div>
                    )}
                  </div>
                ) : resultLoading && !resultPage ? (
                  <div className="empty-state" style={{ padding: 20 }}>Đang tải trang kết quả...</div>
                ) : Array.isArray(resultPage?.result) ? (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                    {/* Pagination controls */}
                    <div className="form-row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
                      <div className="chip-row">
                        <button
                          type="button"
                          className="btn btn-sm"
                          disabled={startRow <= 0 || resultLoading}
                          onClick={() => setStartRow((r) => Math.max(0, r - windowSize))}
                        >
                          <ChevronLeft size={14} /> Dòng trước
                        </button>
                        <button
                          type="button"
                          className="btn btn-sm"
                          disabled={startRow + windowSize >= totalRows || resultLoading}
                          onClick={() => setStartRow((r) => r + windowSize)}
                        >
                          Dòng sau <ChevronRight size={14} />
                        </button>
                        <button
                          type="button"
                          className="btn btn-sm"
                          disabled={startCol <= 0 || resultLoading}
                          onClick={() => setStartCol((c) => Math.max(0, c - pageCols))}
                        >
                          <ChevronLeft size={14} /> Cột trước
                        </button>
                        <button
                          type="button"
                          className="btn btn-sm"
                          disabled={startCol + pageCols >= totalCols || resultLoading}
                          onClick={() => setStartCol((c) => c + pageCols)}
                        >
                          Cột sau <ChevronRight size={14} />
                        </button>
                      </div>

                      <form onSubmit={handleJump} className="form-row" style={{ alignItems: 'center' }}>
                        <label className="inline-control">
                          <span>Cửa sổ:</span>
                          <select
                            className="select-sm"
                            value={String(windowSize)}
                            onChange={(e) => setWindowSize(Number(e.target.value))}
                          >
                            <option value="16">16×16</option>
                            <option value="32">32×16</option>
                          </select>
                        </label>
                        <label className="inline-control">
                          <span>Dòng:</span>
                          <input
                            type="number"
                            min={0}
                            className="input-sm port-input"
                            value={jumpRow}
                            onChange={(e) => setJumpRow(e.target.value)}
                          />
                        </label>
                        <label className="inline-control">
                          <span>Cột:</span>
                          <input
                            type="number"
                            min={0}
                            className="input-sm port-input"
                            value={jumpCol}
                            onChange={(e) => setJumpCol(e.target.value)}
                          />
                        </label>
                        <button type="submit" className="btn btn-sm btn-primary">Nhảy tới</button>
                      </form>
                    </div>

                    <div className="muted mono" style={{ fontSize: 12 }}>
                      Đang xem dòng [{resultPage.startRow} .. {resultPage.startRow + resultPage.rowCount - 1}] / {totalRows}, cột [{resultPage.startCol} .. {resultPage.startCol + resultPage.colCount - 1}] / {totalCols}
                    </div>

                    <div className="table-wrap">
                      <table className="matrix-result-table">
                        <thead>
                          <tr>
                            <th className="coord-head">r \ c</th>
                            {Array.from({ length: resultPage.colCount }).map((_, cOffset) => (
                              <th key={cOffset} className="coord-head">
                                {resultPage.startCol + cOffset}
                              </th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {resultPage.result.map((row, rOffset) => (
                            <tr key={rOffset}>
                              <td className="coord-head">{resultPage.startRow + rOffset}</td>
                              {row.map((cell, cOffset) => (
                                <td key={cOffset}>{formatNumber(cell)}</td>
                              ))}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>
                ) : (
                  <div className="empty-state" style={{ padding: 20 }}>Không có dữ liệu hiển thị.</div>
                )}
              </div>
            )}
          </>
        )}
      </div>

      <ConfirmDialog
        open={Boolean(cancelTarget)}
        title="Xác nhận Hủy Job"
        message={`Bạn có chắc muốn hủy Job "${cancelTarget?.title}"? Các tác vụ chưa hoàn thành sẽ bị dừng.`}
        confirmLabel="Hủy Job ngay"
        danger
        onConfirm={handleCancelJob}
        onCancel={() => setCancelTarget(null)}
      />
    </div>
  );
}
