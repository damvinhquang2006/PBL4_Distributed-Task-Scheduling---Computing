package com.pbl4.core.model;

/**
 * Các chính sách cân bằng tải khi phân phối tác vụ từ Master sang các Worker.
 */
public enum LoadBalancingPolicy {
    /** Phân phối xoay vòng lần lượt giữa các Worker đang online */
    ROUND_ROBIN,
    
    /** Ưu tiên giao việc cho Worker có số lượng tác vụ đang xử lý (active tasks) ít nhất */
    LEAST_LOADED
}
