package com.pbl4.app;

import com.pbl4.master.MasterServer;
import com.pbl4.worker.WorkerConfig;
import com.pbl4.worker.WorkerNode;
import com.pbl4.worker.WorkerRuntime;

import java.util.ArrayList;
import java.util.List;

/**
 * Tien ich khoi dong toan bo cum gom 1 Master (Port 50) va 3 Worker (Port 51, 52, 53)
 * trong cung mot tien trinh de kiem thu, demo nhanh va chay kich ban tu dong.
 */
public class ClusterLauncher {

    public static void main(String[] args) throws Exception {
        int masterPort = 50;
        int workerCount = 3;
        int startWorkerPort = 51;
        boolean showMenu = false;
        boolean keepAlive = false;

        for (int i = 0; i < args.length; i++) {
            if (("--workers".equals(args[i]) || "--count".equals(args[i])) && i + 1 < args.length) {
                workerCount = Integer.parseInt(args[++i]);
            } else if ("--masterPort".equals(args[i]) && i + 1 < args.length) {
                masterPort = Integer.parseInt(args[++i]);
            } else if ("--startPort".equals(args[i]) && i + 1 < args.length) {
                startWorkerPort = Integer.parseInt(args[++i]);
            } else if ("--menu".equals(args[i])) {
                showMenu = true;
            } else if ("--keep-alive".equals(args[i])) {
                keepAlive = true;
            }
        }

        System.out.println("==================================================================");
        System.out.printf("     KHOI DONG CUM TINH TOAN 1 MASTER VA %d WORKERS TREN LOCALHOST%n", workerCount);
        System.out.println("==================================================================");

        // 1. Khoi dong Master Server (Port 50)
        MasterServer master = new MasterServer(masterPort);
        master.start();
        Thread.sleep(500);

        // 2. Khoi dong N Worker Nodes (Ports tu startWorkerPort)
        List<WorkerNode> workers = new ArrayList<>();
        WorkerConfig defaults = new WorkerConfig();
        WorkerRuntime runtime = new WorkerRuntime(defaults.getHostParallelism());
        for (int i = 0; i < workerCount; i++) {
            int port = startWorkerPort + i;
            String id = defaults.getMachineId() + String.format("-worker-%02d", i + 1);
            WorkerConfig cfg = new WorkerConfig(id, "127.0.0.1", port, "127.0.0.1", masterPort);
            cfg.setCapacity(1);
            WorkerNode node = new WorkerNode(cfg, runtime);
            node.start();
            workers.add(node);
        }

        // Doi cac Worker dang ky xong
        long registrationDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (workers.stream().anyMatch(w -> !w.isRegistered()) && System.nanoTime() < registrationDeadline) Thread.sleep(50);
        System.out.printf("%n[WorkerCluster] REGISTERED %d/%d; opened %d ports.%n", workers.stream().filter(WorkerNode::isRegistered).count(), workerCount, workers.size());

        // Dang ky hook don dep khi tat
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nDang dung toan bo cum...");
            for (WorkerNode w : workers) {
                w.stop();
            }
            runtime.close();
            master.stop();
        }));

        // Gan MasterServer vao MasterApp
        MasterApp.setMasterServer(master);

        if (showMenu) {
            MasterApp.runInteractiveConsole();
            System.out.println("\n>>> Dang dung toan bo cum...");
            for (WorkerNode w : workers) {
                w.stop();
            }
            runtime.close();
            master.stop();
            System.out.println(">>> DA DUNG TOAN BO CUM THANH CONG!");
            System.exit(0);
        } else {
            MasterApp.runAutomatedDemo();
        }

        if (keepAlive) {
            System.out.println("\nCum dang tiep tuc duy tri hoat dong. Nhan Ctrl+C de thoat...");
        } else {
            System.out.println("\n>>> KICH BAN DEMO HOAN TAT. Dang dung cac node va don dep cum...");
            Thread.sleep(1000);
            for (WorkerNode w : workers) {
                w.stop();
            }
            runtime.close();
            master.stop();
            System.out.println(">>> DA DUNG TOAN BO CUM THANH CONG!");
            System.exit(0);
        }
    }
}
