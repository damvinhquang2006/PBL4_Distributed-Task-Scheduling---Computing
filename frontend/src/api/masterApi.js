/**
 * REST API Client cho MasterHttpServer (Java).
 * Mac dinh dung cung hostname voi trinh duyet de tuong thich same-host CORS va Radmin VPN LAN.
 */

export const DEFAULT_API_PORT = 8080;

export function getStoredApiPort() {
  try {
    const raw = window.localStorage.getItem('pbl4-api-port');
    const parsed = Number(raw);
    if (Number.isInteger(parsed) && parsed >= 1 && parsed <= 65535) {
      return parsed;
    }
  } catch {
    // Ignore storage errors in restricted environments
  }
  return DEFAULT_API_PORT;
}

export function setStoredApiPort(port) {
  try {
    window.localStorage.setItem('pbl4-api-port', String(port));
  } catch {
    // Ignore storage errors
  }
}

export function buildApiBaseUrl(apiPort = DEFAULT_API_PORT) {
  const host =
    typeof window !== 'undefined' && window.location && window.location.hostname
      ? window.location.hostname
      : 'localhost';
  const portNum = Number(apiPort) || DEFAULT_API_PORT;
  return `http://${host}:${portNum}/api`;
}

export class MasterApiError extends Error {
  constructor(message, status = 0, payload = null) {
    super(message);
    this.name = 'MasterApiError';
    this.status = status;
    this.payload = payload;
  }
}

async function request(path, { method = 'GET', body, headers = {}, timeoutMs = 15000, apiPort, signal } = {}) {
  const baseUrl = buildApiBaseUrl(apiPort ?? getStoredApiPort());
  const url = `${baseUrl}${path.startsWith('/') ? path : `/${path}`}`;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error('TIMEOUT')), timeoutMs);

  const onExternalAbort = () => controller.abort(signal?.reason);
  if (signal) {
    if (signal.aborted) {
      clearTimeout(timer);
      throw new MasterApiError('Yêu cầu đã bị hủy.', 0);
    }
    signal.addEventListener('abort', onExternalAbort, { once: true });
  }

  try {
    const fetchOptions = {
      method,
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...headers,
      },
      signal: controller.signal,
    };

    if (body !== undefined) {
      fetchOptions.body = JSON.stringify(body);
    }

    const response = await fetch(url, fetchOptions);
    const contentType = response.headers?.get?.('content-type') || '';
    let data = null;

    if (contentType.includes('application/json') || typeof response.json === 'function') {
      try {
        data = await response.json();
      } catch {
        data = null;
      }
    }

    if (!response.ok) {
      const serverMsg = data && typeof data.error === 'string' ? data.error : null;
      const fallback = `Lỗi máy chủ (${response.status})`;
      throw new MasterApiError(serverMsg || fallback, response.status, data);
    }

    return data;
  } catch (err) {
    if (err instanceof MasterApiError) throw err;
    if (err?.name === 'AbortError' || err?.message === 'TIMEOUT') {
      throw new MasterApiError('Quá thời gian chờ phản hồi từ Master API.', 408);
    }
    throw new MasterApiError(
      'Không thể kết nối tới Master API. Kiểm tra MasterApp và cổng API.',
      0,
      { cause: err?.message }
    );
  } finally {
    clearTimeout(timer);
    if (signal) {
      signal.removeEventListener('abort', onExternalAbort);
    }
  }
}

export function createMasterApi(getPort = getStoredApiPort) {
  const resolvePort = () => (typeof getPort === 'function' ? getPort() : getPort);

  return {
    getStatus: (opts) => request('/status', { ...opts, apiPort: resolvePort() }),
    getCluster: (opts) => request('/cluster', { ...opts, apiPort: resolvePort() }),
    getWorkers: (opts) => request('/workers', { ...opts, apiPort: resolvePort() }),

    toggleWorker: (workerId, opts) =>
      request('/workers/toggle', { method: 'POST', body: { workerId }, ...opts, apiPort: resolvePort() }),

    renameWorker: (workerId, displayName, opts) =>
      request('/workers/rename', {
        method: 'POST',
        body: { workerId, displayName },
        ...opts,
        apiPort: resolvePort(),
      }),

    limitWorkers: (count, opts) =>
      request('/workers/limit', {
        method: 'POST',
        body: { count },
        ...opts,
        apiPort: resolvePort(),
      }),

    setWorkersState: (payload, opts) =>
      request('/workers/state', {
        method: 'POST',
        body: payload,
        ...opts,
        apiPort: resolvePort(),
      }),

    getLocalWorkers: (opts) => request('/local-workers', { ...opts, apiPort: resolvePort() }),

    startLocalWorkers: (payload, opts) =>
      request('/local-workers/start', {
        method: 'POST',
        body: payload,
        timeoutMs: 25000,
        ...opts,
        apiPort: resolvePort(),
      }),

    stopLocalWorkers: (payload, opts) =>
      request('/local-workers/stop', {
        method: 'POST',
        body: payload,
        timeoutMs: 15000,
        ...opts,
        apiPort: resolvePort(),
      }),

    setPolicy: (policy, opts) =>
      request('/policy', { method: 'POST', body: { policy }, ...opts, apiPort: resolvePort() }),

    triggerFailoverTest: (opts) =>
      request('/cluster/failover-test', { method: 'POST', body: {}, ...opts, apiPort: resolvePort() }),

    runStarter: ({ expr1, expr2, topOp }, opts) =>
      request('/starter', {
        method: 'POST',
        body: { expr1, expr2, topOp },
        timeoutMs: 60000,
        ...opts,
        apiPort: resolvePort(),
      }),

    computeSync: ({ operation, matrixA, matrixB, scalar, compareSequential }, opts) =>
      request('/compute', {
        method: 'POST',
        body: {
          operation,
          matrixA,
          ...(matrixB ? { matrixB } : {}),
          ...(scalar !== undefined ? { scalar } : {}),
          compareSequential: Boolean(compareSequential),
        },
        timeoutMs: 120000,
        ...opts,
        apiPort: resolvePort(),
      }),

    computeAsync: (payload, idempotencyKey, opts) =>
      request('/compute/async', {
        method: 'POST',
        body: payload,
        headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {},
        timeoutMs: 30000,
        ...opts,
        apiPort: resolvePort(),
      }),

    getJobs: (opts) => request('/jobs', { ...opts, apiPort: resolvePort() }),

    getJobProgress: (jobId, opts) =>
      request(`/jobs/progress?jobId=${encodeURIComponent(jobId)}`, { ...opts, apiPort: resolvePort() }),

    getJobResult: (jobId, { startRow = 0, rowCount = 16, startCol = 0, colCount = 16 } = {}, opts) => {
      const qs = new URLSearchParams({
        jobId,
        startRow: String(startRow),
        rowCount: String(rowCount),
        startCol: String(startCol),
        colCount: String(colCount),
      });
      return request(`/jobs/result?${qs.toString()}`, { ...opts, apiPort: resolvePort() });
    },

    cancelJob: (jobId, opts) =>
      request('/jobs/cancel', { method: 'POST', body: { jobId }, ...opts, apiPort: resolvePort() }),

    runBenchmark: ({ problemType, size, workerCount, compareSequential }, opts) =>
      request('/benchmark/run', {
        method: 'POST',
        body: { problemType, size, workerCount, compareSequential },
        timeoutMs: 300000,
        ...opts,
        apiPort: resolvePort(),
      }),
  };
}

export const masterApi = createMasterApi();
