package com.pbl4.app;

import com.pbl4.master.MasterServer;
import com.pbl4.worker.WorkerConfig;
import com.pbl4.worker.WorkerNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Tien ich khoi dong toan bo cum gom 1 Master (Port 50) va 3 Worker (Port 51, 52, 53)
 * trong cung mot tien trinh de kiem thu, demo nhanh va chay kich ban tu dong.
 */
public class ClusterLauncher {

    public static void main(String[] args) throws Exception {
        int masterPort = 50;
        int[] workerPorts = {51, 52, 53};
        String[] workerIds = {"client01", "client02", "client03"};

        System.out.println("==================================================================");
        System.out.println("     KHOI DONG CUM TINH TOAN 1 MASTER VA 3 WORKERS TREN LOCALHOST ");
        System.out.println("==================================================================");

        // 1. Khoi dong Master Server (Port 50)
        MasterServer master = new MasterServer(masterPort);
        master.start();
        Thread.sleep(500);

        // 2. Khoi dong 3 Worker Nodes (Port 51, 52, 53)
        List<WorkerNode> workers = new ArrayList<>();
        for (int i = 0; i < workerPorts.length; i++) {
            WorkerConfig cfg = new WorkerConfig(workerIds[i], "127.0.0.1", workerPorts[i], "127.0.0.1", masterPort);
            WorkerNode node = new WorkerNode(cfg);
            node.start();
            workers.add(node);
        }

        // Doi cac Worker dang ky xong
        Thread.sleep(1500);

        System.out.println("\n>>> TAT CA 3 WORKER DA DANG KY VOI MASTER THANH CONG! <<<");

        // Dang ky hook don dep khi tat
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nDang dung toan bo cum...");
            for (WorkerNode w : workers) {
                w.stop();
            }
            master.stop();
        }));

        // Gan MasterServer vao MasterApp
        MasterApp.setMasterServer(master);

        boolean showMenu = false;
        boolean keepAlive = false;
        for (String arg : args) {
            if ("--menu".equals(arg)) {
                showMenu = true;
            } else if ("--keep-alive".equals(arg)) {
                keepAlive = true;
            }
        }

        if (showMenu) {
            MasterApp.runInteractiveConsole();
            System.out.println("\n>>> Dang dung toan bo cum...");
            for (WorkerNode w : workers) {
                w.stop();
            }
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
            master.stop();
            System.out.println(">>> DA DUNG TOAN BO CUM THANH CONG!");
            System.exit(0);
        }
    }
}
