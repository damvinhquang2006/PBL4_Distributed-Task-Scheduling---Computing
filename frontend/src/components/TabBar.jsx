import React from 'react';
import { Activity, Calculator, Gauge, Server } from 'lucide-react';

export const TABS = [
  { id: 'solver', hash: '#/solver', label: '1. Giải bài toán', icon: Calculator },
  { id: 'cluster', hash: '#/cluster', label: '2. Quản lý Master–Worker', icon: Server },
  { id: 'benchmark', hash: '#/benchmark', label: '3. Benchmark (1 vs N)', icon: Gauge },
  { id: 'jobs', hash: '#/jobs', label: '4. Job, Kết quả & Failover', icon: Activity },
];

export function TabBar({ activeTab, onSelectTab, readyWorkers = 0, totalWorkers = 0, activeJobsCount = 0 }) {
  return (
    <div className="tabbar-wrap">
      <nav className="tabbar" role="tablist" aria-label="Điều hướng chức năng">
        {TABS.map((tab) => {
          const Icon = tab.icon;
          const isActive = activeTab === tab.id;
          let badgeText = null;
          if (tab.id === 'cluster') {
            badgeText = `${readyWorkers}/${totalWorkers}`;
          } else if (tab.id === 'jobs' && activeJobsCount > 0) {
            badgeText = `${activeJobsCount} đang chạy`;
          }

          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={isActive}
              className={`tab-btn ${isActive ? 'active' : ''}`}
              onClick={() => onSelectTab(tab.id)}
            >
              <Icon size={16} />
              <span>{tab.label}</span>
              {badgeText !== null && <span className="tab-badge">{badgeText}</span>}
            </button>
          );
        })}
      </nav>
    </div>
  );
}
