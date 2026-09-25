package com.pbl4.core.model;

/**
 * Dinh nghia loai thong diep trao doi qua mang Socket TCP giua Master va Workers.
 */
public enum MessageType {
    /** Worker gui den Master de dang ky tham gia cum */
    REGISTER_WORKER,
    
    /** Master xac nhan dang ky thanh cong */
    REGISTER_ACK,
    
    /** Master gui tac vu can thuc thi cho Worker */
    ASSIGN_TASK,
    
    /** Worker tra ve ket qua tinh toan cua tac vu cho Master */
    TASK_RESULT,
    
    /** Worker gui dinh ky de bao hieu con song */
    HEARTBEAT,
    
    /** Master phan hoi heartbeat */
    HEARTBEAT_ACK,
    
    /** Master bat/tat trang thai hoat dong cua Worker (On/Off) */
    SET_WORKER_STATE,
    
    /** Lenh dung Worker */
    SHUTDOWN
}
