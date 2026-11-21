import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertCircle, Info, WifiOff, X } from 'lucide-react';
import { createMasterApi, getStoredApiPort, setStoredApiPort } from './api/masterApi';
import { useTheme } from './hooks/useTheme';
import { useClusterPoller } from './hooks/useClusterPoller';
import { TopBar } from './components/TopBar';
import { TABS, TabBar } from './components/TabBar';
import { ProblemSolverPage } from './pages/ProblemSolverPage';
import { ClusterManagementPage } from './pages/ClusterManagementPage';
import { BenchmarkPage } from './pages/BenchmarkPage';
import { JobsTelemetryPage } from './pages/JobsTelemetryPage';

function resolveTabFromHash(hash) {
  const clean = String(hash || '').toLowerCase();
  const matched = TABS.find((t) => clean === t.hash || clean.startsWith(`${t.hash}?`));
  return matched ? matched.id : 'solver';
}

export default function App() {
  const { theme, toggleTheme } = useTheme();
  const [apiPort, setApiPort] = useState(getStoredApiPort);
  const [pollIntervalMs, setPollIntervalMs] = useState(2000);
  const [activeTab, setActiveTab] = useState(() => resolveTabFromHash(window.location.hash));
  const [selectedJobId, setSelectedJobId] = useState(null);
  const [toast, setToast] = useState(null);

  const api = useMemo(() => createMasterApi(() => apiPort), [apiPort]);
  const { cluster, jobs, connected, error, loading, refresh } = useClusterPoller(apiPort, pollIntervalMs);

  // Sync hash -> tab
  useEffect(() => {
    if (!window.location.hash) {
      window.location.hash = '#/solver';
    }
    const onHashChange = () => {
      setActiveTab(resolveTabFromHash(window.location.hash));
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  // Auto-hide toast
  useEffect(() => {
    if (!toast) return undefined;
    const t = setTimeout(() => setToast(null), 5000);
    return () => clearTimeout(t);
  }, [toast]);

  const handleSelectTab = useCallback((tabId) => {
    const found = TABS.find((t) => t.id === tabId);
    if (found) {
      window.location.hash = found.hash;
      setActiveTab(found.id);
    }
  }, []);

  const handleApiPortChange = useCallback((newPort) => {
    setApiPort(newPort);
    setStoredApiPort(newPort);
  }, []);

  const handleNotify = useCallback((message, variant = 'info') => {
    setToast({ message, variant, id: Date.now() });
  }, []);

  const handleOpenJob = useCallback(
    (jobId) => {
      setSelectedJobId(jobId);
      handleSelectTab('jobs');
    },
    [handleSelectTab]
  );

  const activeJobsCount = useMemo(
    () => jobs.filter((j) => j.status === 'RUNNING' || j.status === 'RECOVERING').length,
    [jobs]
  );

  return (
    <div className="app-shell">
      <header className="sticky-header">
        <TopBar
          cluster={cluster}
          connected={connected}
          apiPort={apiPort}
          onApiPortChange={handleApiPortChange}
          pollIntervalMs={pollIntervalMs}
          onPollIntervalChange={setPollIntervalMs}
          theme={theme}
          onToggleTheme={toggleTheme}
          onManualRefresh={refresh}
        />
        <TabBar
          activeTab={activeTab}
          onSelectTab={handleSelectTab}
          readyWorkers={cluster?.readyWorkers ?? 0}
          totalWorkers={cluster?.totalWorkers ?? 0}
          activeJobsCount={activeJobsCount}
        />
      </header>

      <main className="main-content">
        {/* Connection / Onboarding Banners */}
        {!loading && !connected && (
          <div className="banner banner-danger" role="alert">
            <div className="banner-body">
              <WifiOff size={18} style={{ flexShrink: 0, marginTop: 2 }} />
              <div>
                <strong>Không thể kết nối tới Master API (cổng {apiPort}).</strong>{' '}
                <span>
                  Hãy kiểm tra tiến trình <code>MasterApp</code> đang chạy trên cổng <code>{apiPort}</code>. Nếu bạn đang
                  truy cập từ máy khác qua Radmin VPN, hãy mở đúng địa chỉ <code>http://&lt;IP_Master&gt;:5173</code>{' '}
                  (không dùng <code>localhost</code> trên máy B).
                </span>
                {error && <div className="muted" style={{ marginTop: 4, fontSize: 12 }}>Chi tiết: {error}</div>}
              </div>
            </div>
          </div>
        )}

        {connected && (cluster?.connectedWorkers ?? 0) === 0 && (
          <div className="banner banner-warning" role="status">
            <div className="banner-body">
              <AlertCircle size={18} style={{ flexShrink: 0, marginTop: 2 }} />
              <div>
                <strong>Master đã sẵn sàng nhưng chưa có Worker nào kết nối (0 Worker online).</strong>{' '}
                <span>
                  Trên laptop Master hoặc máy trạm LAN/Radmin, hãy chạy <code>scripts/start-lan-workers.ps1</code> để đăng
                  ký đội Worker trước khi gửi bài toán.
                </span>
              </div>
            </div>
          </div>
        )}

        {toast && (
          <div className={`banner banner-${toast.variant}`} role="status">
            <div className="banner-body">
              <Info size={16} style={{ flexShrink: 0, marginTop: 2 }} />
              <span>{toast.message}</span>
            </div>
            <button type="button" className="btn btn-sm btn-icon" onClick={() => setToast(null)} aria-label="Đóng thông báo">
              <X size={13} />
            </button>
          </div>
        )}

        {/* Keep all 4 tab pages mounted so running computations/benchmarks never reset when switching tabs */}
        <section
          role="tabpanel"
          aria-label="Trang Giải bài toán"
          hidden={activeTab !== 'solver'}
          style={{ display: activeTab === 'solver' ? 'block' : 'none' }}
        >
          <ProblemSolverPage cluster={cluster} api={api} onOpenJob={handleOpenJob} onNotify={handleNotify} />
        </section>
        <section
          role="tabpanel"
          aria-label="Trang Quản lý Master–Worker"
          hidden={activeTab !== 'cluster'}
          style={{ display: activeTab === 'cluster' ? 'block' : 'none' }}
        >
          <ClusterManagementPage cluster={cluster} api={api} onRefresh={refresh} onNotify={handleNotify} />
        </section>
        <section
          role="tabpanel"
          aria-label="Trang Benchmark"
          hidden={activeTab !== 'benchmark'}
          style={{ display: activeTab === 'benchmark' ? 'block' : 'none' }}
        >
          <BenchmarkPage cluster={cluster} api={api} onRefresh={refresh} onNotify={handleNotify} />
        </section>
        <section
          role="tabpanel"
          aria-label="Trang Job, Kết quả & Failover"
          hidden={activeTab !== 'jobs'}
          style={{ display: activeTab === 'jobs' ? 'block' : 'none' }}
        >
          <JobsTelemetryPage
            cluster={cluster}
            jobs={jobs}
            selectedJobId={selectedJobId}
            onSelectJobId={setSelectedJobId}
            api={api}
            onRefresh={refresh}
            onNotify={handleNotify}
          />
        </section>
      </main>
    </div>
  );
}
