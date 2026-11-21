import { useCallback, useEffect, useRef, useState } from 'react';
import { createMasterApi } from '../api/masterApi';

export function useClusterPoller(apiPort, pollIntervalMs = 2000) {
  const [cluster, setCluster] = useState(null);
  const [jobs, setJobs] = useState([]);
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState(null);
  const [lastUpdated, setLastUpdated] = useState(null);
  const [loading, setLoading] = useState(true);
  const inFlightRef = useRef(false);

  const apiRef = useRef(null);
  apiRef.current = createMasterApi(() => apiPort);

  const refresh = useCallback(async () => {
    if (inFlightRef.current) return;
    inFlightRef.current = true;
    try {
      const [clusterData, jobsData] = await Promise.all([
        apiRef.current.getCluster({ timeoutMs: 5000 }),
        apiRef.current.getJobs({ timeoutMs: 5000 }).catch(() => ({ jobs: [] })),
      ]);
      setCluster(clusterData);
      setJobs(Array.isArray(jobsData?.jobs) ? jobsData.jobs : []);
      setConnected(Boolean(clusterData));
      setError(null);
      setLastUpdated(Date.now());
    } catch (err) {
      setConnected(false);
      setError(err?.message || 'Mất kết nối tới Master API.');
    } finally {
      setLoading(false);
      inFlightRef.current = false;
    }
  }, [apiPort]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    if (!pollIntervalMs || pollIntervalMs <= 0) return undefined;
    const timer = setInterval(() => {
      refresh();
    }, pollIntervalMs);
    return () => clearInterval(timer);
  }, [pollIntervalMs, refresh]);

  return {
    cluster,
    jobs,
    connected,
    error,
    lastUpdated,
    loading,
    refresh,
  };
}
