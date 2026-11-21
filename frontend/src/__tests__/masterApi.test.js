import { afterEach, describe, expect, it, vi } from 'vitest';
import { MasterApiError, buildApiBaseUrl, createMasterApi } from '../api/masterApi';

describe('masterApi', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('builds dynamic base URL using current window hostname and configured port', () => {
    const url = buildApiBaseUrl(8080);
    expect(url).toBe(`http://${window.location.hostname}:8080/api`);

    const customUrl = buildApiBaseUrl(9090);
    expect(customUrl).toBe(`http://${window.location.hostname}:9090/api`);
  });

  it('sends Idempotency-Key header on computeAsync', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, jobId: 'async-mul-123', status: 'RUNNING', totalTasks: 4 }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const api = createMasterApi(8080);
    const res = await api.computeAsync({ operation: 'x', rowsA: 100, colsA: 100, rowsB: 100, colsB: 100 }, 'idem-key-1');

    expect(res.jobId).toBe('async-mul-123');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toContain('/api/compute/async');
    expect(options.method).toBe('POST');
    expect(options.headers['Idempotency-Key']).toBe('idem-key-1');
  });

  it('does not send legacy isLargeScale flag on computeSync', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, result: [[2]] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const api = createMasterApi(8080);
    await api.computeSync({ operation: '+', matrixA: [[1]], matrixB: [[1]], compareSequential: true });

    const [, options] = fetchMock.mock.calls[0];
    const body = JSON.parse(options.body);
    expect(body.isLargeScale).toBeUndefined();
    expect(body.compareSequential).toBe(true);
  });

  it('parses backend error message into MasterApiError', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      headers: { get: () => 'application/json' },
      json: async () => ({ error: 'displayName must contain 1-64 visible characters' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const api = createMasterApi(8080);
    await expect(api.renameWorker('w1', '')).rejects.toThrow(MasterApiError);
    await expect(api.renameWorker('w1', '')).rejects.toThrow('displayName must contain 1-64 visible characters');
  });

  it('builds pagination query params for getJobResult', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ jobId: 'j1', status: 'COMPLETED', rows: 100, cols: 100, result: [[1]] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const api = createMasterApi(8080);
    await api.getJobResult('j1', { startRow: 16, rowCount: 16, startCol: 32, colCount: 16 });

    const [url] = fetchMock.mock.calls[0];
    expect(url).toContain('/api/jobs/result?');
    expect(url).toContain('jobId=j1');
    expect(url).toContain('startRow=16');
    expect(url).toContain('rowCount=16');
    expect(url).toContain('startCol=32');
    expect(url).toContain('colCount=16');
  });

  it('calls setWorkersState on POST /api/workers/state', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, updatedWorkerIds: ['w2'], skippedOfflineIds: [] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const api = createMasterApi(8080);
    const res = await api.setWorkersState({ action: 'PAUSE_OTHERS', workerIds: ['w1'] });

    expect(res.updatedWorkerIds).toEqual(['w2']);
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toContain('/api/workers/state');
    expect(options.method).toBe('POST');
    expect(JSON.parse(options.body)).toEqual({ action: 'PAUSE_OTHERS', workerIds: ['w1'] });
  });

  it('calls local-workers endpoints with expected paths, methods, and >= 15000ms start timeout', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'application/json' },
      json: async () => ({ success: true, batches: [], defaultMachineId: 'demo-a' }),
    });
    vi.stubGlobal('fetch', fetchMock);
    const setTimeoutSpy = vi.spyOn(globalThis, 'setTimeout');

    const api = createMasterApi(8080);
    await api.getLocalWorkers();
    expect(fetchMock.mock.calls[0][0]).toContain('/api/local-workers');
    expect(fetchMock.mock.calls[0][1].method).toBe('GET');

    await api.startLocalWorkers({ count: 3, machineId: 'demo-a', startPaused: true });
    expect(fetchMock.mock.calls[1][0]).toContain('/api/local-workers/start');
    expect(fetchMock.mock.calls[1][1].method).toBe('POST');
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({
      count: 3,
      machineId: 'demo-a',
      startPaused: true,
    });
    const startTimeoutCall = setTimeoutSpy.mock.calls.find((call) => typeof call[1] === 'number' && call[1] >= 15000);
    expect(startTimeoutCall).toBeDefined();

    await api.stopLocalWorkers({ batchId: 'batch-1' });
    expect(fetchMock.mock.calls[2][0]).toContain('/api/local-workers/stop');
    expect(fetchMock.mock.calls[2][1].method).toBe('POST');
    expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toEqual({ batchId: 'batch-1' });
  });
});
