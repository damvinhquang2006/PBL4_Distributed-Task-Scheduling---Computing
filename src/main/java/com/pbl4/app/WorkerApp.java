package com.pbl4.app;

import com.pbl4.worker.WorkerConfig;
import com.pbl4.worker.WorkerNode;

import java.io.IOException;

/**
 * Ung dung Worker Node (Client tinh toan).
 * Chay doc lap tren cong vat ly rieng biet (VD: 127.0.0.1:51, 52, 53).
 */
public class WorkerApp {
    private static WorkerNode workerNode;

    public static void main(String[] args) {
        String id = "worker-01";
        int port = 51;
        String host = "127.0.0.1";
        String masterHost = "127.0.0.1";
        int masterPort = 50;
        int capacity = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        String bindHost = "0.0.0.0";
        boolean demoDelay = false;
        int count = 1;

        for (int i = 0; i < args.length; i++) {
            if ("--demoDelay".equals(args[i])) { demoDelay = true;
            } else if ("--bindHost".equals(args[i]) && i + 1 < args.length) { bindHost = args[++i];
            } else if ("--count".equals(args[i]) && i + 1 < args.length) {
                count = Integer.parseInt(args[++i]);
            } else if ("--id".equals(args[i]) && i + 1 < args.length) {
                id = args[++i];
            } else if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--host".equals(args[i]) && i + 1 < args.length) {
                host = args[++i];
            } else if ("--masterHost".equals(args[i]) && i + 1 < args.length) {
                masterHost = args[++i];
            } else if ("--masterPort".equals(args[i]) && i + 1 < args.length) {
                masterPort = Integer.parseInt(args[++i]);
            } else if ("--capacity".equals(args[i]) && i + 1 < args.length) {
                capacity = Integer.parseInt(args[++i]);
            }
        }

        if (count > 1) {
            WorkerClusterLauncher.startCluster(count, port, host, masterHost, masterPort, capacity, "worker-");
            return;
        }

        System.out.printf("==================================================%n");
        System.out.printf("   KHOI DONG WORKER NODE [%s] TAI %s:%d%n", id, host, port);
        System.out.printf("   Ket noi toi Master: %s:%d | So luong: %d threads%n", masterHost, masterPort, capacity);
        System.out.printf("==================================================%n");

        WorkerConfig config = new WorkerConfig(id, host, port, masterHost, masterPort);
        config.setCapacity(capacity); config.setBindHost(bindHost); config.setDemoDelay(demoDelay);

        workerNode = new WorkerNode(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (workerNode != null) {
                workerNode.stop();
            }
        }));

        try {
            workerNode.start();
            System.out.println("Worker dang chay va san sang nhan tac vu. Nhan Ctrl+C de dung.");
            
            // Giu tien trinh Worker hoat dong
            while (workerNode.isRunning()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.printf("[%s] Loi khoi dong tren port %d: %s%n", id, port, e.getMessage());
        }
    }
}
