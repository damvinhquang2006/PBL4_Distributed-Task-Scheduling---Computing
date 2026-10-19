package com.pbl4.app;

import com.pbl4.worker.WorkerConfig;
import com.pbl4.worker.WorkerNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tien ich khoi dong cum Worker so luong lon (VD: 50 Workers hoac hon)
 * trong mot tien trinh Java duy nhat, toi uu tai nguyen he thong va socket.
 */
public class WorkerClusterLauncher {

    public static void main(String[] args) {
        int count = 50;
        int startPort = 51;
        String host = "127.0.0.1";
        String masterHost = "127.0.0.1";
        int masterPort = 50;
        int capacity = 2; // Moi worker chay 2 threads tinh toan song song
        String idPrefix = "worker-";

        for (int i = 0; i < args.length; i++) {
            if ("--count".equals(args[i]) && i + 1 < args.length) {
                count = Integer.parseInt(args[++i]);
            } else if ("--startPort".equals(args[i]) && i + 1 < args.length) {
                startPort = Integer.parseInt(args[++i]);
            } else if ("--host".equals(args[i]) && i + 1 < args.length) {
                host = args[++i];
            } else if ("--masterHost".equals(args[i]) && i + 1 < args.length) {
                masterHost = args[++i];
            } else if ("--masterPort".equals(args[i]) && i + 1 < args.length) {
                masterPort = Integer.parseInt(args[++i]);
            } else if ("--capacity".equals(args[i]) && i + 1 < args.length) {
                capacity = Integer.parseInt(args[++i]);
            } else if ("--idPrefix".equals(args[i]) && i + 1 < args.length) {
                idPrefix = args[++i];
            }
        }

        startCluster(count, startPort, host, masterHost, masterPort, capacity, idPrefix);
    }

    public static List<WorkerNode> startCluster(int count, int startPort, String host,
                                                String masterHost, int masterPort,
                                                int capacity, String idPrefix) {
        int endPort = startPort + count - 1;
        System.out.println("==================================================================");
        System.out.printf("   KHOI DONG CUM %d WORKER NODES (PORTS %d -> %d)%n", count, startPort, endPort);
        System.out.printf("   Ket noi toi Master: %s:%d | Capacity moi Worker: %d threads%n",
                masterHost, masterPort, capacity);
        System.out.println("==================================================================");

        List<WorkerNode> workers = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            int port = startPort + i;
            String id = String.format("%s%02d", idPrefix, (i + 1));
            WorkerConfig config = new WorkerConfig(id, host, port, masterHost, masterPort);
            config.setCapacity(capacity);

            WorkerNode node = new WorkerNode(config);
            try {
                node.start();
                workers.add(node);
                if (count <= 10 || (i + 1) % 10 == 0 || (i + 1) == count) {
                    System.out.printf("[WorkerCluster] Da khoi dong %2d/%2d workers (Moi nhat: %s tai port %d)...%n",
                            (i + 1), count, id, port);
                }
            } catch (IOException e) {
                System.err.printf("[WorkerCluster] Loi khoi dong Worker [%s] tren port %d: %s%n",
                        id, port, e.getMessage());
            }
        }

        System.out.println("------------------------------------------------------------------");
        System.out.printf(">>> TOAN BO %d WORKERS DA SAN SANG NHAN NHIEM VU TU MASTER! <<<%n", workers.size());
        System.out.println("Nhan Ctrl+C de dung toan bo cum Worker an toan.\n");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[WorkerCluster] Dang dung toan bo cac Worker nodes...");
            for (WorkerNode w : workers) {
                w.stop();
            }
            System.out.println("[WorkerCluster] Da dung toan bo cac Worker an toan.");
        }));

        // Giu tien trinh luon hoat dong
        Thread keepAliveThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "WorkerCluster-KeepAlive");
        keepAliveThread.start();

        return workers;
    }
}
