import React, { useState } from 'react';
import { Cpu, Pause, Play, Server } from 'lucide-react';

export const admissionLabel = reason => ({
  READY: 'Sẵn sàng', OFFLINE: 'Mất kết nối', PAUSED: 'Đang tạm dừng',
  CAPACITY: 'Đã nhận đủ việc', HOST_CAPACITY: 'Laptop đang đủ tải',
  CPU_HIGH: 'Hoãn nhận việc: CPU cao', RAM_HIGH: 'Hoãn nhận việc: RAM cao',
  HEAP_HIGH: 'Hoãn nhận việc: bộ nhớ Java cao', RETRY_BACKOFF: 'Đang chờ thử lại'
}[reason] || reason || 'Đang cập nhật');

const metric = value => Number.isFinite(value) ? `${Math.round(value)}%` : 'Chưa có số đo';

export default function MachineFleet({ machines, workers, onToggle }) {
  const [showOffline, setShowOffline] = useState(false);
  return (
    <div className="cluster-view">
      <div className="cluster-summary-bar">
        <span>{machines.filter(m => m.connectedWorkers > 0).length} laptop có kết nối</span>
        <span>{workers.filter(w => w.status !== 'OFFLINE').length} Worker đang kết nối</span>
        <label className="checkbox-label">
          <input type="checkbox" checked={showOffline} onChange={e => setShowOffline(e.target.checked)} />
          Hiện Worker OFFLINE ({workers.filter(w => w.status === 'OFFLINE').length})
        </label>
      </div>
      {workers.length === 0 && <div className="card">Chưa có Worker đăng ký trong phiên Master hiện tại. Khởi chạy cụm Worker trên laptop A hoặc B và chờ thông báo REGISTERED.</div>}
      {machines.map(machine => {
        const members = workers.filter(w => w.machineId === machine.machineId && (showOffline || w.status !== 'OFFLINE'));
        if (members.length === 0) return null;
        return (
          <section className="card machine-group" key={machine.machineId} aria-label={`Laptop ${machine.machineId}`}>
            <div className="machine-heading">
              <div><h3><Server size={20} /> {machine.machineName}</h3><p>{machine.machineId} · {machine.host}</p></div>
              <div className="machine-counts">
                <span>Kết nối: <strong>{machine.connectedWorkers}</strong></span>
                <span>Sẵn sàng: <strong>{machine.readyWorkers}</strong></span>
                <span>Đang xử lý: <strong>{machine.activeTasks} / {machine.hostParallelism}</strong></span>
              </div>
            </div>
            <div className="machine-resources">
              <span><Cpu size={14} /> CPU {metric(machine.metrics?.cpu)}</span>
              <span>RAM {metric(machine.metrics?.ram)}</span>
              <span>Bộ nhớ Java {metric(machine.metrics?.heap)}</span>
              <span>Giới hạn laptop: {machine.hostParallelism} phép tính đồng thời</span>
            </div>
            <div className="workers-grid">
              {members.map(worker => (
                <div key={worker.workerId} className={`worker-card ${worker.paused || worker.status === 'OFFLINE' ? 'paused' : ''}`}>
                  <div className="worker-card-header">
                    <div className="worker-id-row">
                      <span className={`status-indicator ${worker.status === 'OFFLINE' ? 'offline' : worker.paused ? 'paused' : 'online'}`} />
                      <span className="worker-id">{worker.workerId}</span>
                    </div>
                  </div>
                  <div className="worker-metrics">
                    <p className="worker-address">{worker.host}:{worker.port}</p>
                    <div className="metric-row"><span>Đã giao:</span><span>{worker.activeTasks} / {worker.capacity}</span></div>
                    <div className="metric-row"><span>Đã hoàn thành:</span><span>{worker.completedTasks}</span></div>
                    {worker.currentTaskIds?.length > 0 && <p className="worker-current-task" title={worker.currentTaskIds.join(', ')}>{worker.currentTaskIds.join(', ')}</p>}
                  </div>
                  <div className="worker-footer">
                    <span className={`admission-badge ${worker.admissionReason}`} title={worker.admissionReason}>{admissionLabel(worker.admissionReason)}</span>
                    <button className={`toggle-worker-btn ${worker.paused ? 'resume' : 'pause'}`} disabled={worker.status === 'OFFLINE'} onClick={() => onToggle(worker.workerId)}>
                      {worker.paused ? <Play size={13} /> : <Pause size={13} />}{worker.paused ? 'Bật lại' : 'Tạm dừng'}
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </section>
        );
      })}
    </div>
  );
}
