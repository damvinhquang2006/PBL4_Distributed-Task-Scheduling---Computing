import React from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from '../App';

const MOCK_CLUSTER = {
  masterSession: 'session-abcdef123456',
  running: true,
  masterPort: 5000,
  httpPort: 8080,
  protocolVersion: 4,
  jarSha256: '1234567890abcdef',
  policy: 'RESOURCE_AWARE',
  totalWorkers: 2,
  connectedWorkers: 2,
  readyWorkers: 2,
  runningWorkers: 0,
  pausedWorkers: 0,
  offlineWorkers: 0,
  effectiveParallelism: 8,
  machines: [
    {
      machineId: 'demo-a',
      machineName: 'Laptop A',
      host: '192.168.1.10',
      hostParallelism: 8,
      effectiveParallelism: 8,
      totalWorkers: 2,
      connectedWorkers: 2,
      readyWorkers: 2,
      runningWorkers: 0,
      pausedWorkers: 0,
      offlineWorkers: 0,
      activeTasks: 0,
      completedTasks: 5,
      metrics: { cpu: null, ram: 52.3, heap: 31.0 },
    },
  ],
  workers: [
    {
      workerId: 'demo-a-w1',
      displayName: 'Worker A1',
      machineId: 'demo-a',
      machineName: 'Laptop A',
      host: '192.168.1.10',
      port: 5101,
      capacity: 4,
      hostParallelism: 8,
      status: 'ONLINE',
      connected: true,
      processGroupId: 'default',
      supervised: true,
      paused: false,
      quarantined: false,
      admissionReason: 'READY',
      activeTasks: 0,
      completedTasks: 5,
      currentTaskIds: [],
      metrics: null,
      lastHeartbeatTime: Date.now(),
      jarSha256: '1234567890abcdef',
    },
  ],
};

describe('App Navigation & Theme', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.location.hash = '#/solver';

    vi.stubGlobal(
      'fetch',
      vi.fn(async (url) => {
        if (String(url).includes('/api/cluster')) {
          return {
            ok: true,
            headers: { get: () => 'application/json' },
            json: async () => MOCK_CLUSTER,
          };
        }
        if (String(url).includes('/api/jobs')) {
          return {
            ok: true,
            headers: { get: () => 'application/json' },
            json: async () => ({ jobs: [] }),
          };
        }
        return {
          ok: true,
          headers: { get: () => 'application/json' },
          json: async () => ({}),
        };
      })
    );
  });

  it('renders 4 tabs, navigates across all pages, and toggles Dark/Light mode', async () => {
    render(<App />);

    expect(screen.getByText('PBL4 Master–Worker Console')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /1\. Giải bài toán/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /2\. Quản lý Master–Worker/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /3\. Benchmark \(1 vs N\)/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /4\. Job, Kết quả & Failover/i })).toBeInTheDocument();

    // Default tab 1 content + 30x30 preset + Starter diagram reflects selected topOp
    expect(screen.getByText(/1\. Cấu hình & Dữ liệu đầu vào/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '30×30' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Biểu Thức Số Học Song Song/i }));
    expect(screen.getByText('Master gộp (*)')).toBeInTheDocument();

    // Switch to Tab 2 (Cluster Management) and verify null-safe metrics + empty filter state
    fireEvent.click(screen.getByRole('tab', { name: /2\. Quản lý Master–Worker/i }));
    expect(window.location.hash).toBe('#/cluster');
    const clusterPanel = screen.getByRole('tabpanel', { name: 'Trang Quản lý Master–Worker' });
    await waitFor(() => {
      expect(within(clusterPanel).getByText('Worker A1')).toBeInTheDocument();
    });
    expect(within(clusterPanel).getAllByText('Không rõ').length).toBeGreaterThan(0);

    fireEvent.change(within(clusterPanel).getByPlaceholderText('Tìm workerId, tên, IP...'), {
      target: { value: 'nonexistent-worker' },
    });
    expect(within(clusterPanel).getByText('Không có Worker nào khớp với bộ lọc hiện tại.')).toBeInTheDocument();
    fireEvent.click(within(clusterPanel).getByRole('button', { name: 'Xóa bộ lọc' }));
    expect(within(clusterPanel).getByText('Worker A1')).toBeInTheDocument();

    // Switch to Tab 3 (Benchmark)
    fireEvent.click(screen.getByRole('tab', { name: /3\. Benchmark \(1 vs N\)/i }));
    expect(window.location.hash).toBe('#/benchmark');
    expect(screen.getByText(/Thiết lập Thí nghiệm Benchmark/i)).toBeInTheDocument();

    // Switch to Tab 4 (Jobs)
    fireEvent.click(screen.getByRole('tab', { name: /4\. Job, Kết quả & Failover/i }));
    expect(window.location.hash).toBe('#/jobs');
    expect(screen.getByText(/Danh sách Job/i)).toBeInTheDocument();

    // Toggle Dark -> Light Mode
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    fireEvent.click(screen.getByRole('button', { name: /Chuyển sang chế độ Sáng/i }));
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(window.localStorage.getItem('pbl4-theme')).toBe('light');
  });
});

describe('Worker Management, Safe Rename & Live DisplayName Sync', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('separates 2-layer status badges, starts/stops local workers, and confirms batch pause on busy workers', async () => {
    window.localStorage.clear();
    window.location.hash = '#/cluster';

    const clusterState = {
      ...MOCK_CLUSTER,
      totalWorkers: 3,
      connectedWorkers: 3,
      readyWorkers: 1,
      runningWorkers: 1,
      pausedWorkers: 1,
      workers: [
        {
          ...MOCK_CLUSTER.workers[0],
          workerId: 'demo-a-w1',
          displayName: 'Alpha Node',
          paused: false,
          activeTasks: 0,
        },
        {
          ...MOCK_CLUSTER.workers[0],
          workerId: 'demo-a-w2',
          displayName: 'Busy Worker',
          port: 5102,
          paused: false,
          activeTasks: 2,
          currentTaskIds: ['job1-task-01', 'job1-task-02'],
        },
        {
          ...MOCK_CLUSTER.workers[0],
          workerId: 'demo-a-w3',
          displayName: 'Standby Worker',
          port: 5103,
          paused: true,
          activeTasks: 0,
        },
      ],
    };

    const localWorkersState = {
      defaultMachineId: 'demo-a',
      batches: [
        {
          batchId: 'batch-101',
          machineId: 'demo-a',
          count: 2,
          startPort: 5201,
          alive: true,
          workerIds: ['demo-a-w2', 'demo-a-w3'],
        },
      ],
    };

    const fetchMock = vi.fn(async (url, opts) => {
      const u = String(url);
      if (u.includes('/api/cluster')) {
        return { ok: true, headers: { get: () => 'application/json' }, json: async () => clusterState };
      }
      if (u.includes('/api/jobs')) {
        return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ jobs: [] }) };
      }
      if (u.includes('/api/local-workers/start')) {
        return {
          ok: true,
          headers: { get: () => 'application/json' },
          json: async () => ({ success: true, batchId: 'batch-102', count: 5 }),
        };
      }
      if (u.includes('/api/local-workers/stop')) {
        return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ success: true }) };
      }
      if (u.includes('/api/local-workers')) {
        return { ok: true, headers: { get: () => 'application/json' }, json: async () => localWorkersState };
      }
      if (u.includes('/api/workers/state')) {
        return {
          ok: true,
          headers: { get: () => 'application/json' },
          json: async () => ({ success: true, updatedWorkerIds: ['demo-a-w2'], skippedOfflineIds: [] }),
        };
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({}) };
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);

    const clusterPanel = screen.getByRole('tabpanel', { name: 'Trang Quản lý Master–Worker' });
    await waitFor(() => {
      expect(within(clusterPanel).getByText('Alpha Node')).toBeInTheDocument();
    });

    // Single unified worker status + Task đang giữ + no Điều tiết & Kiểm thử An toàn / Admission Reason
    expect(within(clusterPanel).queryByText('Sẵn sàng (ONLINE)')).not.toBeInTheDocument();
    expect(within(clusterPanel).queryByText('Trực tuyến (ONLINE)')).not.toBeInTheDocument();
    expect(within(clusterPanel).queryByText(/Điều tiết & Kiểm thử An toàn/i)).not.toBeInTheDocument();
    expect(within(clusterPanel).queryByText('Admission Reason')).not.toBeInTheDocument();
    expect(within(clusterPanel).getAllByText('Online').length).toBeGreaterThan(0);
    expect(within(clusterPanel).getAllByText('Tạm dừng').length).toBeGreaterThan(0);
    expect(within(clusterPanel).getByText('job1-task-01')).toBeInTheDocument();
    expect(within(clusterPanel).getByText('job1-task-02')).toBeInTheDocument();

    // Panel 1: start 5 local workers in Warm Standby
    fireEvent.click(screen.getByRole('button', { name: '+5' }));
    fireEvent.change(screen.getByLabelText(/Tiền tố tên/i), { target: { value: 'DemoNode' } });
    fireEvent.click(screen.getByLabelText(/Khởi động ở trạng thái Tạm dừng/i));
    fireEvent.click(screen.getByRole('button', { name: /Khởi động 5 Worker cục bộ/i }));

    await waitFor(() => {
      const startCall = fetchMock.mock.calls.find(([u]) => String(u).includes('/api/local-workers/start'));
      expect(startCall).toBeDefined();
      expect(JSON.parse(startCall[1].body)).toEqual({
        count: 5,
        machineId: 'demo-a',
        startPaused: true,
        displayNamePrefix: 'DemoNode',
      });
    });

    // Panel 1: stop batch
    fireEvent.click(screen.getByRole('button', { name: /Dừng batch/i }));
    await waitFor(() => {
      const stopCall = fetchMock.mock.calls.find(([u]) => String(u).includes('/api/local-workers/stop'));
      expect(stopCall).toBeDefined();
      expect(JSON.parse(stopCall[1].body)).toEqual({ batchId: 'batch-101' });
    });

    // Panel 2: RESUME_ALL
    fireEvent.click(screen.getByRole('button', { name: /Bật tất cả Worker đang tạm dừng/i }));
    await waitFor(() => {
      const stateCall = fetchMock.mock.calls.find(
        ([u, o]) => String(u).includes('/api/workers/state') && JSON.parse(o.body).action === 'RESUME_ALL'
      );
      expect(stateCall).toBeDefined();
    });

    // Panel 2: PAUSE_OTHERS with ConfirmDialog because Busy Worker has activeTasks > 0
    fireEvent.click(screen.getByLabelText('Chọn Alpha Node'));
    fireEvent.click(screen.getByRole('button', { name: /Giữ đã chọn & Tạm dừng còn lại/i }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toBeInTheDocument();
    expect(within(dialog).getByText(/Busy Worker/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận tạm dừng' }));
    await waitFor(() => {
      const pauseOthersCall = fetchMock.mock.calls.find(
        ([u, o]) => String(u).includes('/api/workers/state') && JSON.parse(o.body).action === 'PAUSE_OTHERS'
      );
      expect(pauseOthersCall).toBeDefined();
      expect(JSON.parse(pauseOthersCall[1].body)).toEqual({
        action: 'PAUSE_OTHERS',
        workerIds: ['demo-a-w1'],
      });
    });
  });

  it('keeps inline rename draft visible even when polling moves worker out of statusFilter', async () => {
    window.localStorage.clear();
    window.location.hash = '#/cluster';

    let currentCluster = {
      ...MOCK_CLUSTER,
      workers: [
        {
          ...MOCK_CLUSTER.workers[0],
          workerId: 'demo-a-w1',
          displayName: 'Worker A1',
          paused: false,
          activeTasks: 0,
        },
      ],
    };

    const fetchMock = vi.fn(async (url) => {
      const u = String(url);
      if (u.includes('/api/cluster')) {
        return { ok: true, headers: { get: () => 'application/json' }, json: async () => currentCluster };
      }
      if (u.includes('/api/jobs')) {
        return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ jobs: [] }) };
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({}) };
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);

    const clusterPanel = screen.getByRole('tabpanel', { name: 'Trang Quản lý Master–Worker' });
    await waitFor(() => {
      expect(within(clusterPanel).getByText('Worker A1')).toBeInTheDocument();
    });

    // Filter to READY only, then open inline rename
    fireEvent.change(within(clusterPanel).getByLabelText('Lọc theo trạng thái'), { target: { value: 'READY' } });
    fireEvent.click(within(clusterPanel).getByRole('button', { name: 'Đổi tên Worker A1' }));

    const renameInput = within(clusterPanel).getByLabelText('Tên mới cho demo-a-w1');
    fireEvent.change(renameInput, { target: { value: 'Ten Dang Sua' } });

    // Simulate polling update where worker becomes PAUSED (no longer matches READY filter)
    currentCluster = {
      ...currentCluster,
      readyWorkers: 0,
      pausedWorkers: 1,
      workers: [
        {
          ...currentCluster.workers[0],
          paused: true,
        },
      ],
    };

    fireEvent.click(screen.getByRole('button', { name: 'Làm mới dữ liệu ngay' }));

    await waitFor(() => {
      const table = within(clusterPanel).getByRole('table');
      expect(within(table).getByText('Tạm dừng')).toBeInTheDocument();
    });
    expect(within(clusterPanel).getByLabelText('Tên mới cho demo-a-w1')).toHaveValue('Ten Dang Sua');
  });

  it('synchronizes live displayName across Solver, Benchmark (with >4 milestones), and Jobs Failover log', async () => {
    window.localStorage.clear();
    window.location.hash = '#/solver';
    window.localStorage.setItem(
      'pbl4-benchmark-history',
      JSON.stringify([
        {
          timestamp: 1731800000000,
          problemType: 'matrix_multiply',
          size: 150,
          workerCount: 2,
          activeWorkers: [{ workerId: 'demo-a-w1', displayName: 'Old Name 1' }],
          sequentialTimeMs: 200,
          distributedTimeMs: 110,
          speedup: 1.82,
          efficiency: 91.0,
        },
      ])
    );

    const cluster6Workers = {
      ...MOCK_CLUSTER,
      totalWorkers: 6,
      connectedWorkers: 6,
      readyWorkers: 6,
      workers: Array.from({ length: 6 }, (_, i) => ({
        ...MOCK_CLUSTER.workers[0],
        workerId: `demo-a-w${i + 1}`,
        displayName: i === 0 ? 'Máy Chủ Mới Đổi Tên' : `Worker ${i + 1}`,
        port: 5101 + i,
      })),
    };

    const fetchMock = vi.fn(async (url) => {
      const u = String(url);
      if (u.includes('/api/cluster')) {
        return { ok: true, headers: { get: () => 'application/json' }, json: async () => cluster6Workers };
      }
      if (u.includes('/api/jobs/progress')) {
        return {
          ok: true,
          headers: { get: () => 'application/json' },
          json: async () => ({
            jobId: 'job-1',
            title: 'Test Matrix Job',
            status: 'RUNNING',
            totalTasks: 4,
            completedTasks: 2,
            progressPercent: 50,
            workerFleet: [
              {
                workerId: 'demo-a-w1',
                displayName: 'Stale Name',
                machineId: 'demo-a',
                machineName: 'Laptop A',
                host: '192.168.1.10',
                port: 5101,
                status: 'ONLINE',
                paused: false,
                quarantined: false,
                jobCompletedTasks: 2,
              },
            ],
            failoverEvents: [
              {
                workerId: 'demo-a-w1',
                displayName: 'Stale Name',
                taskId: 'task-9',
                retryCount: 1,
                timestamp: 1731800005000,
                message: 'Worker disconnected during task',
              },
            ],
          }),
        };
      }
      if (u.includes('/api/jobs')) {
        return {
          ok: true,
          headers: { get: () => 'application/json' },
          json: async () => ({
            jobs: [
              {
                jobId: 'job-1',
                title: 'Test Matrix Job',
                status: 'RUNNING',
                totalTasks: 4,
                completedTasks: 2,
                progressPercent: 50,
                startedAt: 1731800000000,
              },
            ],
          }),
        };
      }
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({}) };
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);

    // Tab 1 (Solver): shows live displayName in ready workers bar
    await waitFor(() => {
      const readyBar = screen.getByLabelText('Worker sẵn sàng');
      expect(within(readyBar).getByText('Máy Chủ Mới Đổi Tên')).toBeInTheDocument();
    });

    // Tab 3 (Benchmark): dynamic milestone > 4 ("6 Worker" via Tất cả online (6)) and live displayName in history
    fireEvent.click(screen.getByRole('tab', { name: /3\. Benchmark \(1 vs N\)/i }));
    const benchPanel = screen.getByRole('tabpanel', { name: 'Trang Benchmark' });
    expect(within(benchPanel).getByRole('button', { name: 'Tất cả online (6)' })).toBeInTheDocument();
    expect(within(benchPanel).getByText('Máy Chủ Mới Đổi Tên')).toBeInTheDocument();
    expect(within(benchPanel).queryByText('Old Name 1')).not.toBeInTheDocument();

    // Tab 4 (Jobs & Failover): live displayName in workerFleet and Failover log
    fireEvent.click(screen.getByRole('tab', { name: /4\. Job, Kết quả & Failover/i }));
    const jobsPanel = screen.getByRole('tabpanel', { name: 'Trang Job, Kết quả & Failover' });
    await waitFor(() => {
      expect(within(jobsPanel).getByText(/Nhật ký Failover \(1\)/i)).toBeInTheDocument();
    });
    const matches = within(jobsPanel).getAllByText('Máy Chủ Mới Đổi Tên');
    expect(matches.length).toBeGreaterThanOrEqual(2); // workerFleet + failover log
    expect(within(jobsPanel).queryByText('Stale Name')).not.toBeInTheDocument();
  });

  it('preserves running/configured state across tab switches and shows Thời gian đo đạc & Speed up on large matrices', async () => {
    window.localStorage.clear();
    window.location.hash = '#/solver';

    const asyncJobId = 'async-mul-large-test';
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url) => {
        const u = String(url);
        if (u.includes('/api/cluster')) {
          return { ok: true, headers: { get: () => 'application/json' }, json: async () => MOCK_CLUSTER };
        }
        if (u.includes('/api/compute/async')) {
          return {
            ok: true,
            headers: { get: () => 'application/json' },
            json: async () => ({
              success: true,
              jobId: asyncJobId,
              title: 'Large Matrix 100x100',
              operation: 'x',
              totalTasks: 16,
              status: 'RUNNING',
            }),
          };
        }
        if (u.includes('/api/jobs/progress')) {
          return {
            ok: true,
            headers: { get: () => 'application/json' },
            json: async () => ({
              jobId: asyncJobId,
              title: 'Large Matrix 100x100',
              operation: 'x',
              status: 'COMPLETED',
              totalTasks: 16,
              completedTasks: 16,
              progressPercent: 100,
              elapsedMs: 420,
              etaMs: 0,
              resultSummary: {
                rows: 100,
                cols: 100,
                isSampled: true,
                sampleGrid: [
                  [1, 2],
                  [3, 4],
                ],
                rowIndices: [0, 99],
                colIndices: [0, 99],
                distributedTimeMs: 420,
                sequentialTimeMs: 1260,
                speedup: 3.0,
                totalChunks: 16,
                workerIds: ['demo-a-w1', 'demo-a-w2'],
              },
            }),
          };
        }
        if (u.includes('/api/jobs')) {
          return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ jobs: [] }) };
        }
        return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({}) };
      })
    );

    render(<App />);

    // Switch to Async large-scale mode on Tab 1 and submit a large matrix job
    fireEvent.click(screen.getByRole('button', { name: /Quy mô lớn \(tới 50\.000\.000 phần tử/i }));
    fireEvent.click(screen.getByRole('button', { name: '7071×7071' }));
    expect(screen.getByLabelText('Dòng A').value).toBe('7071');

    fireEvent.click(screen.getByRole('button', { name: '100×100' }));
    fireEvent.click(screen.getByRole('button', { name: /Gửi Job Bất đồng bộ/i }));

    await waitFor(() => {
      expect(screen.getByText('Thời gian đo đạc')).toBeInTheDocument();
      expect(screen.getByText('Speed up')).toBeInTheDocument();
      expect(screen.getByText('3.00x')).toBeInTheDocument();
    });
    expect(screen.queryByText('Min (mẫu)')).not.toBeInTheDocument();
    expect(screen.queryByText('Max (mẫu)')).not.toBeInTheDocument();

    // Switch to Tab 2 and Tab 3, then return to Tab 1 -> state and results must remain intact
    fireEvent.click(screen.getByRole('tab', { name: /2\. Quản lý Master–Worker/i }));
    fireEvent.click(screen.getByRole('tab', { name: /3\. Benchmark \(1 vs N\)/i }));
    fireEvent.click(screen.getByRole('tab', { name: /1\. Giải bài toán/i }));

    expect(screen.getByLabelText('Dòng A').value).toBe('100');
    expect(screen.getByText('3.00x')).toBeInTheDocument();
  });
});
