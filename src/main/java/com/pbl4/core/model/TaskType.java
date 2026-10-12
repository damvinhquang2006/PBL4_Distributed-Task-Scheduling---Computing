package com.pbl4.core.model;

/**
 * Các loại tác vụ tính toán mà hệ thống hỗ trợ.
 */
public enum TaskType {
    /** Phép toán biểu thức khởi đầu (VD: 1+2, 3*2) */
    ARITHMETIC,
    
    /** Phép cộng ma trận theo khối dòng */
    MATRIX_ADD,
    
    /** Phép trừ ma trận theo khối dòng */
    MATRIX_SUB,
    
    /** Phep nhan ma tran theo khoi dong (A_slice x B) */
    MATRIX_MULTIPLY,
    
    /** Phep chia lay du phan tu ma tran theo khoi dong (A_slice % B_slice) */
    MATRIX_MOD,

    /** Tinh mot phan tong khai trien Laplace cua dinh thuc ma tran */
    MATRIX_DETERMINANT,

    /** Rut gon mot lat cat dong de ho tro tinh hang ma tran */
    MATRIX_RANK,

    /** Tim duong di ngan nhat do thi (Parallel Multi-source Dijkstra) */
    GRAPH_SHORTEST_PATH
}
