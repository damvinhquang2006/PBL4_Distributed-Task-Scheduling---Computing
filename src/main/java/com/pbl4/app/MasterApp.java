package com.pbl4.app;

import com.pbl4.computing.graph.Graph;
import com.pbl4.computing.graph.ShortestPathAlgorithms;
import com.pbl4.computing.matrix.Matrix;
import com.pbl4.computing.matrix.MatrixOperations;
import com.pbl4.core.model.LoadBalancingPolicy;
import com.pbl4.core.model.WorkerInfo;
import com.pbl4.core.model.WorkerStatus;
import com.pbl4.master.MasterServer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/**
 * Ung dung dieu phoi chinh (Master Application).
 * Chay tren cong 50 (mac dinh) hoac cong tuy chinh qua dong lenh.
 */
public class MasterApp {
    private static MasterServer masterServer;

    public static void main(String[] args) {
        int port = 50;
        boolean autoDemo = false;

        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--demo".equals(args[i])) {
                autoDemo = true;
            }
        }

        System.out.println("===============================================================");
        System.out.println("   HE THONG TINH TOAN PHAN TAN VA CAN BANG TAI (MASTER - WORKER)   ");
        System.out.println("===============================================================");

        try {
            masterServer = new MasterServer(port);
            masterServer.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (masterServer != null) {
                    masterServer.stop();
                }
            }));

            if (autoDemo) {
                runAutomatedDemo();
                System.exit(0);
            } else {
                runInteractiveConsole();
            }

        } catch (IOException e) {
            System.err.printf("Loi khong the mo Server tren port %d: %s%n", port, e.getMessage());
            System.err.println("Goi y: Neu port 50 bi gioi han tren he thong cua ban, co the chay voi tham so: --port 5000");
        }
    }

    /**
     * Xoa toan bo man hinh terminal de giu giao dien sach se, ro rang.
     */
    public static void clearScreen() {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            }
        } catch (Exception ignored) {
        }
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    /**
     * Tam dung cho nguoi dung nhan ENTER de quay ve trang menu chinh.
     */
    public static void pauseForEnter(Scanner scanner) {
        System.out.println("\nNhan phim ENTER de quay lai trang menu chinh...");
        try {
            scanner.nextLine();
        } catch (Exception ignored) {}
    }

    public static void runInteractiveConsole() {
        Scanner scanner = new Scanner(System.in);
        while (masterServer.isRunning()) {
            clearScreen();
            System.out.println("----------------- BANG DIEU KHIEN MASTER -----------------");
            System.out.println("1. Bat dau tinh toan ma tran ");
            System.out.println("2. Chay vi du khoi dau");
            System.out.println("3. So sanh hieu nang (co che tuan tu vs phan tan)");
            System.out.println("4. Xem danh sach Workers & Thong ke Can bang tai");
            System.out.println("5. Tuy chinh cac Worker (On/Off)");
            System.out.println("0. Thoat");
            System.out.println("---------------------------------------------------------------------");
            System.out.print("Vui long chon chuc nang (0-5): ");

            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1":
                    handleCustomMatrixWorkflow(scanner);
                    break;
                case "2":
                    clearScreen();
                    handleStarterDemo();
                    pauseForEnter(scanner);
                    break;
                case "3":
                    clearScreen();
                    handleBenchmarkInteractive(scanner);
                    pauseForEnter(scanner);
                    break;
                case "4":
                    clearScreen();
                    showWorkerStatsAndToggle(scanner);
                    pauseForEnter(scanner);
                    break;
                case "5":
                    handleWorkerToggleMenu(scanner);
                    break;
                case "0":
                    System.out.println("Dang tat he thong...");
                    masterServer.stop();
                    System.exit(0);
                    break;
                default:
                    System.out.println("Lua chon khong hop le! Vui long nhap tu 0 den 5.");
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    /**
     * Chuc nang 1: Bat dau tinh toan ma tran theo quy trinh 3 buoc tuong tac.
     */
    private static void handleCustomMatrixWorkflow(Scanner scanner) {
        clearScreen();
        System.out.println("=====================================================================");
        System.out.println("                CHUC NANG 1: BAT DAU TINH TOAN MA TRAN               ");
        System.out.println("=====================================================================");

        int rowsA = 0, colsA = 0, rowsB = 0, colsB = 0;
        String op = "";

        // =================================================================
        // BUOC 1: Chon kich thuoc ma tran A, sau do chon phep tinh.
        // =================================================================
        while (true) {
            System.out.println("\n--- BUOC 1: Vui long chon kich thuoc ma tran A ---");
            rowsA = promptPositiveInt(scanner, "Nhap so dong (rows) ma tran A: ");
            colsA = promptPositiveInt(scanner, "Nhap so cot (cols) ma tran A: ");
            rowsB = 0;
            colsB = 0;

            // =============================================================
            // BUOC 2: Chon phep tinh cho bai toan (+, -, x, %, det, rank)
            // =============================================================
            boolean step2Done = false;
            while (!step2Done) {
                System.out.println("\n--- BUOC 2: Vui long chon phep tinh cho bai toan ---");
                System.out.printf("Kich thuoc da chon: Ma tran A [%d x %d]%n", rowsA, colsA);
                System.out.println("Cac phep toan ho tro:");
                System.out.println("  [+] Cong hai ma tran (A + B)");
                System.out.println("  [-] Tru hai ma tran (A - B)");
                System.out.println("  [x] Nhan hai ma tran (A x B)");
                System.out.println("  [%] Chia lay du phan tu ma tran (A % B)");
                System.out.println("  [det] Tinh dinh thuc ma tran A");
                System.out.println("  [rank] Tinh hang ma tran A");
                System.out.print("Vui long nhap phep tinh (+, -, x, %, det, rank): ");

                String chosenOp = scanner.nextLine().trim().toLowerCase();
                if ("*".equals(chosenOp)) chosenOp = "x";
                if ("d".equals(chosenOp)) chosenOp = "det";
                if ("r".equals(chosenOp)) chosenOp = "rank";

                if (!"+".equals(chosenOp) && !"-".equals(chosenOp) && !"x".equals(chosenOp)
                        && !"%".equals(chosenOp) && !"det".equals(chosenOp) && !"rank".equals(chosenOp)) {
                    System.out.println("Phep toan khong hop le! Vui long chi chon +, -, x, %, det, hoac rank.");
                    continue;
                }

                if ("det".equals(chosenOp)) {
                    if (rowsA != colsA) {
                        System.out.printf("%n[LOI TUONG THICH] Dinh thuc chi tinh duoc cho ma tran vuong!%n");
                        System.out.printf("Hien tai: Ma tran A [%d x %d] khong phai ma tran vuong.%n", rowsA, colsA);
                        System.out.println("1. Chon lai phep toan khac");
                        System.out.println("2. Nhap lai kich thuoc ma tran A (Quay ve Buoc 1)");
                        System.out.println("0. Huy bo va quay ve Menu chinh");
                        System.out.print("Lua chon cua ban (0-2): ");
                        String resolveChoice = scanner.nextLine().trim();
                        if ("2".equals(resolveChoice)) {
                            break;
                        } else if ("0".equals(resolveChoice)) {
                            return;
                        } else {
                            continue;
                        }
                    }
                    op = chosenOp;
                    step2Done = true;
                    continue;
                }

                if ("rank".equals(chosenOp)) {
                    op = chosenOp;
                    step2Done = true;
                    continue;
                }

                System.out.println("\n--- Nhap kich thuoc ma tran B cho phep toan nhi phan ---");
                rowsB = promptPositiveInt(scanner, "Nhap so dong (rows) ma tran B: ");
                colsB = promptPositiveInt(scanner, "Nhap so cot (cols) ma tran B: ");

                // Kiem tra tinh tuong thich ve kich thuoc
                if ("+".equals(chosenOp) || "-".equals(chosenOp) || "%".equals(chosenOp)) {
                    if (rowsA != rowsB || colsA != colsB) {
                        System.out.printf("%n[LOI TUONG THICH] Phep toan '%s' yeu cau 2 ma tran phai co cung kich thuoc!%n", chosenOp);
                        System.out.printf("Hien tai: Ma tran A [%d x %d] != Ma tran B [%d x %d].%n", rowsA, colsA, rowsB, colsB);
                        System.out.println("1. Chon lai phep toan khac");
                        System.out.println("2. Nhap lai kich thuoc ma tran A (Quay ve Buoc 1)");
                        System.out.println("0. Huy bo va quay ve Menu chinh");
                        System.out.print("Lua chon cua ban (0-2): ");
                        String resolveChoice = scanner.nextLine().trim();
                        if ("2".equals(resolveChoice)) {
                            break; // break ra ngoai de chay lai Buoc 1
                        } else if ("0".equals(resolveChoice)) {
                            return; // quay ve menu chinh
                        } else {
                            continue; // chon lai phep toan (Buoc 2)
                        }
                    }
                } else if ("x".equals(chosenOp)) {
                    if (colsA != rowsB) {
                        System.out.printf("%n[LOI TUONG THICH] Phep nhan ma tran (A x B) yeu cau so cot cua A phai bang so dong cua B!%n");
                        System.out.printf("Hien tai: Cot A (%d) != Dong B (%d). Khong the nhan [%d x %d] voi [%d x %d]!%n",
                                colsA, rowsB, rowsA, colsA, rowsB, colsB);
                        System.out.println("1. Chon lai phep toan khac");
                        System.out.println("2. Nhap lai kich thuoc ma tran A (Quay ve Buoc 1)");
                        System.out.println("0. Huy bo va quay ve Menu chinh");
                        System.out.print("Lua chon cua ban (0-2): ");
                        String resolveChoice = scanner.nextLine().trim();
                        if ("2".equals(resolveChoice)) {
                            break; // break ra ngoai de chay lai Buoc 1
                        } else if ("0".equals(resolveChoice)) {
                            return; // quay ve menu chinh
                        } else {
                            continue; // chon lai phep toan (Buoc 2)
                        }
                    }
                }

                op = chosenOp;
                step2Done = true;
            }

            if (step2Done) {
                break; // Hoan thanh ca Buoc 1 va Buoc 2 hop le!
            }
        }

        // =================================================================
        // BUOC 3: Nhap gia tri cho tung phan tu ma tran
        // (Hien thi cung luc 2 khung ma tran mau voi con tro [>?<])
        // =================================================================
        boolean unaryOperation = isUnaryMatrixOperation(op);
        Double[][] dataA = new Double[rowsA][colsA];
        Double[][] dataB = unaryOperation ? new Double[0][0] : new Double[rowsB][colsB];

        char activeMatrix = 'A';
        int activeRow = 0;
        int activeCol = 0;

        while (true) {
            clearScreen();
            if (unaryOperation) {
                renderSingleMatrixTemplate(rowsA, colsA, dataA, activeRow, activeCol, op);
            } else {
                renderTwinMatrixTemplates(rowsA, colsA, dataA, rowsB, colsB, dataB,
                        activeMatrix, activeRow, activeCol, op);
            }

            System.out.printf(">> Dang nhap phan tu: MA TRAN %c - Dong %d, Cot %d (Toa do [%d][%d])%n",
                    activeMatrix, (activeRow + 1), (activeCol + 1), activeRow, activeCol);
            System.out.print("Nhap gia tri (so nguyen hoac so thap phan): ");

            String input = scanner.nextLine().trim();
            try {
                double val = Double.parseDouble(input);
                if (activeMatrix == 'A') {
                    dataA[activeRow][activeCol] = val;
                    activeCol++;
                    if (activeCol >= colsA) {
                        activeCol = 0;
                        activeRow++;
                        if (activeRow >= rowsA) {
                            if (unaryOperation) {
                                break;
                            }
                            activeMatrix = 'B';
                            activeRow = 0;
                            activeCol = 0;
                        }
                    }
                } else {
                    dataB[activeRow][activeCol] = val;
                    activeCol++;
                    if (activeCol >= colsB) {
                        activeCol = 0;
                        activeRow++;
                        if (activeRow >= rowsB) {
                            // Da nhap xong toan bo 2 ma tran!
                            break;
                        }
                    }
                }
            } catch (NumberFormatException e) {
                System.out.println("Gia tri khong hop le! Vui long nhap mot con so hop le (VD: 5 hoac 3.5).");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
            }
        }

        // Chuyen mang Double[][] sang doi tuong Matrix
        Matrix matrixA = new Matrix(rowsA, colsA);
        for (int i = 0; i < rowsA; i++) {
            for (int j = 0; j < colsA; j++) {
                matrixA.set(i, j, dataA[i][j]);
            }
        }

        Matrix matrixB = null;
        if (!unaryOperation) {
            matrixB = new Matrix(rowsB, colsB);
            for (int i = 0; i < rowsB; i++) {
                for (int j = 0; j < colsB; j++) {
                    matrixB.set(i, j, dataB[i][j]);
                }
            }
        }

        // =================================================================
        // BUOC 4: Giao nhiem vu va ket qua cho cac Worker
        // (Xoa toan bo noi dung truoc do, hien thi phan bo task, ket qua,
        //  va cho phim ENTER de quay ve menu chinh)
        // =================================================================
        clearScreen();
        System.out.println("=====================================================================");
        System.out.println("            TIEN HANH PHAN PHOI TINH TOAN MA TRAN PHAN TAN           ");
        System.out.println("=====================================================================");
        System.out.printf("Ma tran A [%d x %d]:%n%s%n", rowsA, colsA, matrixA.prettyPrint(6));
        System.out.printf("Phep toan da chon: %s%n%n", op.toUpperCase());
        if (!unaryOperation) {
            System.out.printf("Ma tran B [%d x %d]:%n%s%n", rowsB, colsB, matrixB.prettyPrint(6));
        }
        System.out.println("---------------------------------------------------------------------");

        if (!unaryOperation) {
            List<WorkerInfo> onlineWorkers = masterServer.getRegistry().getAvailableWorkers();
            System.out.printf("[Master] So luong Workers dang truc tuyen: %d%n", onlineWorkers.size());
            for (WorkerInfo w : onlineWorkers) {
                System.out.printf("  |-- Worker ID: %-12s | Dia chi: %s:%d | Capacity: %d%n",
                        w.getWorkerId(), w.getHost(), w.getPort(), w.getCapacity());
            }
            System.out.println("[Master] Dang chia nho cac lat cat dong va phan phoi task den Workers...\n");
        } else {
            List<WorkerInfo> onlineWorkers = masterServer.getRegistry().getAvailableWorkers();
            System.out.printf("[Master] So luong Workers dang truc tuyen: %d%n", onlineWorkers.size());
            for (WorkerInfo w : onlineWorkers) {
                System.out.printf("  |-- Worker ID: %-12s | Dia chi: %s:%d | Capacity: %d%n",
                        w.getWorkerId(), w.getHost(), w.getPort(), w.getCapacity());
            }
            System.out.println("[Master] Dang chia nho bai toan va phan phoi task den Workers...\n");
        }

        Matrix resultMatrix = null;
        Matrix expectedMatrix = null;
        Double scalarResult = null;
        Integer rankResult = null;
        long startTime = System.currentTimeMillis();

        if ("+".equals(op)) {
            resultMatrix = masterServer.runMatrixAddOrSub(matrixA, matrixB, true);
            expectedMatrix = MatrixOperations.add(matrixA, matrixB);
        } else if ("-".equals(op)) {
            resultMatrix = masterServer.runMatrixAddOrSub(matrixA, matrixB, false);
            expectedMatrix = MatrixOperations.subtract(matrixA, matrixB);
        } else if ("x".equalsIgnoreCase(op)) {
            resultMatrix = masterServer.runMatrixMultiplication(matrixA, matrixB);
            expectedMatrix = MatrixOperations.multiply(matrixA, matrixB);
        } else if ("%".equals(op)) {
            resultMatrix = masterServer.runMatrixModulo(matrixA, matrixB);
            expectedMatrix = MatrixOperations.modulo(matrixA, matrixB);
        } else if ("det".equals(op)) {
            scalarResult = masterServer.runMatrixDeterminant(matrixA);
        } else if ("rank".equals(op)) {
            rankResult = masterServer.runMatrixRank(matrixA);
        }

        long totalTime = System.currentTimeMillis() - startTime;

        System.out.println("\n=====================================================================");
        System.out.println("                      KET QUA TINH TOAN CUOI CUNG                    ");
        System.out.println("=====================================================================");
        if (scalarResult != null) {
            System.out.printf("Dinh thuc det(A) = %.6f%n", scalarResult);
            System.out.printf("Kiem tra tinh chinh xac (So sanh voi ket qua tuan tu): %s%n",
                    Math.abs(scalarResult - MatrixOperations.determinant(matrixA)) < 1e-6 ? "CHINH XAC (MATCH 100%)" : "SAI LECH!");
            System.out.printf("Tong thoi gian xu ly: %d ms%n", totalTime);
        } else if (rankResult != null) {
            System.out.printf("Hang ma tran rank(A) = %d%n", rankResult);
            System.out.printf("Kiem tra tinh chinh xac (So sanh voi ket qua tuan tu): %s%n",
                    rankResult == MatrixOperations.rank(matrixA) ? "CHINH XAC (MATCH 100%)" : "SAI LECH!");
            System.out.printf("Tong thoi gian xu ly: %d ms%n", totalTime);
        } else if (resultMatrix != null) {
            System.out.printf("Ket qua Ma tran C = A %s B [%d x %d]:%n%s%n",
                    op, resultMatrix.getRows(), resultMatrix.getCols(), resultMatrix.prettyPrint(6));
            boolean match = (expectedMatrix != null && resultMatrix.equals(expectedMatrix));
            System.out.printf("Kiem tra tinh chinh xac (So sanh voi ket qua tuan tu): %s%n",
                    match ? "CHINH XAC (MATCH 100%)" : "SAI LECH!");
            System.out.printf("Tong thoi gian xu ly phan tan: %d ms%n", totalTime);
        } else {
            System.err.println(">>> TINH TOAN THAT BAI! Vui long kiem tra ket noi cac Workers.");
        }
        System.out.println("=====================================================================");

        pauseForEnter(scanner);
    }

    private static int promptPositiveInt(Scanner scanner, String prompt) {
        while (true) {
            System.out.print(prompt);
            String line = scanner.nextLine().trim();
            try {
                int val = Integer.parseInt(line);
                if (val > 0) {
                    return val;
                }
                System.out.println("Gia tri phai la so nguyen duong (> 0)! Vui long nhap lai.");
            } catch (NumberFormatException e) {
                System.out.println("Dinh dang so khong hop le! Vui long nhap so nguyen duong.");
            }
        }
    }

    private static boolean isUnaryMatrixOperation(String op) {
        return "det".equals(op) || "rank".equals(op);
    }

    private static void renderSingleMatrixTemplate(int rows, int cols, Double[][] data,
                                                   int activeRow, int activeCol,
                                                   String op) {
        System.out.println("====================== BUOC 3: NHAP GIA TRI MA TRAN ======================");
        System.out.printf("Phep toan da chon: %s(A) voi A [%d x %d]%n", op.toUpperCase(), rows, cols);
        System.out.println("Giai thich: [  X  ]: Chua nhap | [>?<]: Con tro dang nhap | [12.0]: Da nhap\n");

        System.out.printf("--- MA TRAN A [%d x %d] ---%n", rows, cols);
        for (int i = 0; i < rows; i++) {
            System.out.print("[ ");
            for (int j = 0; j < cols; j++) {
                if (i == activeRow && j == activeCol) {
                    System.out.print(" [>?<] ");
                } else {
                    System.out.print(formatCell(data[i][j]));
                }
            }
            System.out.println("]");
        }
        System.out.println("---------------------------------------------------------------------------");
    }

    private static void renderTwinMatrixTemplates(int rowsA, int colsA, Double[][] dataA,
                                                  int rowsB, int colsB, Double[][] dataB,
                                                  char activeMatrix, int activeRow, int activeCol,
                                                  String op) {
        System.out.println("====================== BUOC 3: NHAP GIA TRI MA TRAN ======================");
        System.out.printf("Phep toan da chon: [%d x %d]  %s  [%d x %d]%n", rowsA, colsA, op.toUpperCase(), rowsB, colsB);
        System.out.println("Giai thich: [  X  ]: Chua nhap | [>?<]: Con tro dang nhap | [12.0]: Da nhap\n");

        int maxRows = Math.max(rowsA, rowsB);
        boolean sideBySide = (colsA + colsB) <= 9;

        if (sideBySide) {
            String titleA = String.format("--- MA TRAN A [%d x %d] ---", rowsA, colsA);
            String titleB = String.format("--- MA TRAN B [%d x %d] ---", rowsB, colsB);
            int widthA = Math.max(titleA.length(), colsA * 7 + 4);
            System.out.printf("%-" + widthA + "s        %s%n", titleA, titleB);

            for (int i = 0; i < maxRows; i++) {
                StringBuilder lineA = new StringBuilder();
                if (i < rowsA) {
                    lineA.append("[ ");
                    for (int j = 0; j < colsA; j++) {
                        if (activeMatrix == 'A' && i == activeRow && j == activeCol) {
                            lineA.append(" [>?<] ");
                        } else {
                            lineA.append(formatCell(dataA[i][j]));
                        }
                    }
                    lineA.append("]");
                } else {
                    lineA.append(" ".repeat(widthA));
                }

                StringBuilder lineB = new StringBuilder();
                if (i < rowsB) {
                    lineB.append("[ ");
                    for (int j = 0; j < colsB; j++) {
                        if (activeMatrix == 'B' && i == activeRow && j == activeCol) {
                            lineB.append(" [>?<] ");
                        } else {
                            lineB.append(formatCell(dataB[i][j]));
                        }
                    }
                    lineB.append("]");
                }

                System.out.printf("%-" + widthA + "s   |    %s%n", lineA.toString(), lineB.toString());
            }
        } else {
            System.out.printf("--- MA TRAN A [%d x %d] ---%n", rowsA, colsA);
            for (int i = 0; i < rowsA; i++) {
                System.out.print("[ ");
                for (int j = 0; j < colsA; j++) {
                    if (activeMatrix == 'A' && i == activeRow && j == activeCol) {
                        System.out.print(" [>?<] ");
                    } else {
                        System.out.print(formatCell(dataA[i][j]));
                    }
                }
                System.out.println("]");
            }

            System.out.printf("%n--- MA TRAN B [%d x %d] ---%n", rowsB, colsB);
            for (int i = 0; i < rowsB; i++) {
                System.out.print("[ ");
                for (int j = 0; j < colsB; j++) {
                    if (activeMatrix == 'B' && i == activeRow && j == activeCol) {
                        System.out.print(" [>?<] ");
                    } else {
                        System.out.print(formatCell(dataB[i][j]));
                    }
                }
                System.out.println("]");
            }
        }
        System.out.println("---------------------------------------------------------------------------");
    }

    private static String formatCell(Double val) {
        if (val == null) {
            return "   X   ";
        }
        if (val == Math.floor(val) && !Double.isInfinite(val)) {
            return String.format("%6.0f ", val);
        } else {
            return String.format("%6.2f ", val);
        }
    }

    private static int promptMatrixDimension(Scanner scanner, int defaultDim) {
        System.out.printf("Nhap kich thuoc ma tran vuong N (Enter de mac dinh %d): ", defaultDim);
        String input = scanner.nextLine().trim();
        if (input.isEmpty()) return defaultDim;
        try {
            int val = Integer.parseInt(input);
            return val > 0 ? val : defaultDim;
        } catch (NumberFormatException e) {
            return defaultDim;
        }
    }

    /**
     * Chuc nang 2: Chay vi du khoi dau
     */
    private static void handleStarterDemo() {
        System.out.println("\n----------------- VI DU KHOI DAU -----------------");
        System.out.println("Phep toan yeu cau: Server gui 1+2 den client01, gui 3*2 den client02.");
        System.out.println("Cac client tinh toan va tra ket qua ve Server A tong hop ra 9.");
        Double result = masterServer.runArithmeticStarter("1+2", "3*2", "+");
        if (result != null) {
            System.out.printf("==> KET QUA TONG HOP XUAT RA: (1 + 2) + (3 * 2) = %.0f%n", result);
        }

        System.out.println("\n----------------- VI DU DET VA HANG MT -----------------");
        Matrix squareMatrix = new Matrix(new double[][]{
                {6, 1, 1},
                {4, -2, 5},
                {2, 8, 7}
        });
        Matrix rectangularMatrix = new Matrix(new double[][]{
                {1, 2, 3, 4},
                {2, 4, 6, 8},
                {0, 1, 0, 1}
        });

        System.out.println("Ma tran vuong A:\n" + squareMatrix.prettyPrint(6));
        Double determinant = masterServer.runMatrixDeterminant(squareMatrix);
        if (determinant != null) {
            System.out.printf("==> Dinh thuc det(A) = %.6f%n", determinant);
        }
        Integer squareRank = masterServer.runMatrixRank(squareMatrix);
        if (squareRank != null) {
            System.out.printf("==> Hang ma tran rank(A) = %d%n", squareRank);
        }

        System.out.println("\nMa tran chu nhat B:\n" + rectangularMatrix.prettyPrint(6));
        Integer rectangularRank = masterServer.runMatrixRank(rectangularMatrix);
        if (rectangularRank != null) {
            System.out.printf("==> Hang ma tran rank(B) = %d%n", rectangularRank);
        }
    }

    /**
     * Chuc nang 3: So sanh hieu nang (co che tuan tu vs phan tan)
     */
    private static void handleBenchmarkInteractive(Scanner scanner) {
        int n = promptMatrixDimension(scanner, 300);
        executeBenchmarkWithSize(n);
    }

    private static void handleBenchmark() {
        executeBenchmarkWithSize(300);
    }

    private static void executeBenchmarkWithSize(int N) {
        System.out.printf("%n=== BAT DAU BENCHMARK MA TRAN [%d x %d] ===%n", N, N);
        Matrix A = Matrix.random(N, N, 1.0, 10.0);
        Matrix B = Matrix.random(N, N, 1.0, 10.0);

        // 1. Chay tuan tu don luong (Single Thread)
        System.out.println("1. Dang tinh toan tuan tu tren 1 luong Master...");
        long startSeq = System.currentTimeMillis();
        Matrix seqResult = MatrixOperations.multiply(A, B);
        long timeSeq = System.currentTimeMillis() - startSeq;
        System.out.printf("--> Thoi gian tuan tu (Single Thread): %d ms%n", timeSeq);

        // 2. Chay phan tan tren cac Workers
        System.out.println("2. Dang phan phoi tinh toan song song phan tan qua cac Workers...");
        long startDist = System.currentTimeMillis();
        Matrix distResult = masterServer.runMatrixMultiplication(A, B);
        long timeDist = System.currentTimeMillis() - startDist;
        System.out.printf("--> Thoi gian phan tan (Distributed Master-Workers): %d ms%n", timeDist);

        if (distResult != null && distResult.equals(seqResult)) {
            System.out.println("--> Tinh dung dan: KET QUA HOAN TOAN KHOP 100%!");
            if (timeDist > 0) {
                double speedup = (double) timeSeq / timeDist;
                System.out.printf("--> He so tang toc (Speedup Factor): %.2fx%n", speedup);
            }
        }
    }

    /**
     * Chuc nang 4: Xem danh sach Workers & Thong ke Can bang tai
     */
    private static void showWorkerStatsAndToggle(Scanner scanner) {
        showWorkerStats();
        System.out.print("\nBan co muon doi chinh sach can bang tai khong (y/n)? ");
        String ans = scanner.nextLine().trim();
        if ("y".equalsIgnoreCase(ans) || "yes".equalsIgnoreCase(ans)) {
            toggleLoadBalancingPolicy();
        }
    }

    private static void showWorkerStats() {
        List<WorkerInfo> workers = masterServer.getRegistry().getAllWorkers();
        System.out.println("\n---------------- DANH SACH WORKER & TAI HIEN TAI ----------------");
        System.out.printf("%-15s %-18s %-12s %-10s %-14s %-12s%n",
                "Worker ID", "Dia chi", "Trang thai", "Capacity", "Active Tasks", "Hoan thanh");
        System.out.println("----------------------------------------------------------------------------------");
        for (WorkerInfo w : workers) {
            System.out.printf("%-15s %-18s %-12s %-10d %-14d %-12d%n",
                    w.getWorkerId(), w.getHost() + ":" + w.getPort(),
                    w.getStatus(), w.getCapacity(), w.getActiveTasks(), w.getCompletedTasks());
        }
        System.out.println("----------------------------------------------------------------------------------");
        System.out.printf("Chinh sach can bang tai hien tai: %s%n", masterServer.getScheduler().getPolicy());
    }

    private static void toggleLoadBalancingPolicy() {
        LoadBalancingPolicy cur = masterServer.getScheduler().getPolicy();
        LoadBalancingPolicy next = (cur == LoadBalancingPolicy.ROUND_ROBIN) ?
                LoadBalancingPolicy.LEAST_LOADED : LoadBalancingPolicy.ROUND_ROBIN;
        masterServer.getScheduler().setPolicy(next);
        System.out.printf(">> Da chuyen chinh sach Can bang tai thanh: %s%n", next);
    }

    /**
     * Chuc nang 5: Tuy chinh cac Worker (On/Off)
     */
    private static void handleWorkerToggleMenu(Scanner scanner) {
        while (true) {
            clearScreen();
            List<WorkerInfo> workers = masterServer.getRegistry().getAllWorkers();
            if (workers.isEmpty()) {
                System.out.println("\nChua co Worker nao dang ky voi Master!");
                pauseForEnter(scanner);
                return;
            }

            System.out.println("----------------- TUY CHINH CAC WORKER (ON/OFF) -----------------");
            for (int i = 0; i < workers.size(); i++) {
                WorkerInfo w = workers.get(i);
                String badge = (w.getStatus() == WorkerStatus.ONLINE) ? "[ONLINE - HOAT DONG]" : "[OFFLINE - TAM DUNG]";
                System.out.printf("%d. %-12s (%s:%d) -> %s%n",
                        (i + 1), w.getWorkerId(), w.getHost(), w.getPort(), badge);
            }
            System.out.println("0. Quay lai menu chinh");
            System.out.print("Chon Worker de chuyen doi trang thai On/Off (0-" + workers.size() + "): ");

            String input = scanner.nextLine().trim();
            if ("0".equals(input) || input.isEmpty()) {
                break;
            }

            try {
                int idx = Integer.parseInt(input) - 1;
                if (idx >= 0 && idx < workers.size()) {
                    WorkerInfo chosen = workers.get(idx);
                    masterServer.toggleWorkerState(chosen.getWorkerId());
                    try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                } else {
                    System.out.println("Chi so Worker khong hop le!");
                    try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                }
            } catch (NumberFormatException e) {
                System.out.println("Vui long nhap so hop le!");
                try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            }
        }
    }

    private static void handleMatrixAddSub() {
        int r = 6, c = 6;
        Matrix A = Matrix.random(r, c, 1.0, 10.0);
        Matrix B = Matrix.random(r, c, 1.0, 10.0);

        System.out.println("Ma tran A:\n" + A.prettyPrint(6));
        System.out.println("Ma tran B:\n" + B.prettyPrint(6));

        Matrix C_add = masterServer.runMatrixAddOrSub(A, B, true);
        if (C_add != null) {
            System.out.println("Ket qua A + B:\n" + C_add.prettyPrint(6));
            Matrix expected = MatrixOperations.add(A, B);
            boolean match = C_add.equals(expected);
            System.out.printf("Kiem tra tinh chinh xac (So sanh voi tuan tu): %s%n", match ? "CHINH XAC (MATCH 100%)" : "SAI LECH!");
        }

        Matrix C_sub = masterServer.runMatrixAddOrSub(A, B, false);
        if (C_sub != null) {
            System.out.println("Ket qua A - B:\n" + C_sub.prettyPrint(6));
            Matrix expectedSub = MatrixOperations.subtract(A, B);
            boolean match = C_sub.equals(expectedSub);
            System.out.printf("Kiem tra tinh chinh xac (So sanh voi tuan tu): %s%n", match ? "CHINH XAC (MATCH 100%)" : "SAI LECH!");
        }
    }

    private static void handleMatrixMultiply() {
        int r = 9, k = 6, c = 9;
        Matrix A = Matrix.random(r, k, 1.0, 5.0);
        Matrix B = Matrix.random(k, c, 1.0, 5.0);

        System.out.println("Ma tran A [9x6]:\n" + A.prettyPrint(6));
        System.out.println("Ma tran B [6x9]:\n" + B.prettyPrint(6));

        Matrix C = masterServer.runMatrixMultiplication(A, B);
        if (C != null) {
            System.out.println("Ket qua C = A * B [9x9]:\n" + C.prettyPrint(6));
            Matrix expected = MatrixOperations.multiply(A, B);
            boolean match = C.equals(expected);
            System.out.printf("Kiem tra tinh chinh xac (So sanh voi tuan tu): %s%n", match ? "CHINH XAC (MATCH 100%)" : "SAI LECH!");
        }
    }

    private static void handleMatrixChainMultiply() {
        Matrix A = Matrix.random(6, 5, 1.0, 4.0);
        Matrix B = Matrix.random(5, 4, 1.0, 4.0);
        Matrix C = Matrix.random(4, 6, 1.0, 4.0);

        List<Matrix> chain = Arrays.asList(A, B, C);
        Matrix result = masterServer.runMatrixChainMultiplication(chain);
        if (result != null) {
            System.out.println("Ket qua tich chuoi A * B * C [6x6]:\n" + result.prettyPrint(6));
            Matrix expected = MatrixOperations.multiplyChain(chain);
            boolean match = result.equals(expected);
            System.out.printf("Kiem tra tinh chinh xac chuoi: %s%n", match ? "CHINH XAC (MATCH 100%)" : "SAI LECH!");
        }
    }

    private static void handleGraphShortestPath() {
        int V = 12;
        Graph g = Graph.randomGraph(V, 0.4, 25.0);
        System.out.println("Ma tran trong so do thi ngau nhien 12 dinh:\n" + g.prettyPrint(8));

        double[][] dist = masterServer.runGraphShortestPath(g);
        if (dist != null) {
            System.out.println("Ma tran khoang cach ngan nhat giua moi cap dinh (All-Pairs Shortest Path):");
            for (int i = 0; i < Math.min(dist.length, 6); i++) {
                System.out.printf("Tu dinh %2d: %s%n", i, Arrays.toString(
                        Arrays.stream(dist[i]).map(d -> Math.round(d * 10.0) / 10.0).limit(8).toArray()
                ));
            }
            double[][] expected = ShortestPathAlgorithms.allPairsDijkstraSequential(g);
            boolean match = Arrays.deepEquals(
                    Arrays.stream(dist).map(r -> Arrays.stream(r).map(d -> Math.round(d * 10.0) / 10.0).toArray()).toArray(),
                    Arrays.stream(expected).map(r -> Arrays.stream(r).map(d -> Math.round(d * 10.0) / 10.0).toArray()).toArray()
            );
            System.out.printf("Kiem tra tinh chinh xac duong di: %s%n", match ? "CHINH XAC (MATCH 100%)" : "SAI LECH!");
        }
    }

    public static void runAutomatedDemo() {
        System.out.println("\n>>> [DEMO] BAT DAU CHAY TU DONG CAC KICH BAN KIEM THU...");

        // Doi workers ket noi
        waitWorkersOnline(1, 4000);

        System.out.println("\n--- BAI TEST 1: VI DU KHOI DAU THEO DUNG YEU CAU DE BAI ---");
        handleStarterDemo();

        System.out.println("\n--- BAI TEST 2: CONG VA TRU MA TRAN PHAN TAN ---");
        handleMatrixAddSub();

        System.out.println("\n--- BAI TEST 3: NHAN MA TRAN PHAN TAN (A x B) ---");
        handleMatrixMultiply();

        System.out.println("\n--- BAI TEST 4: NHAN CHUOI NHIEU MA TRAN (A x B x C) ---");
        handleMatrixChainMultiply();

        System.out.println("\n--- BAI TEST 5: TIM DUONG DI NGAN NHAT PHAN TAN TREN DO THI ---");
        handleGraphShortestPath();

        System.out.println("\n--- BAI TEST 6: DO HIEU NANG & HE SO TANG TOC (BENCHMARK) ---");
        handleBenchmark();

        System.out.println("\n--- THONG KE TAI VA KET QUA CUOI CUNG ---");
        showWorkerStats();
        System.out.println("\n>>> [DEMO] TAT CA KICH BAN DA HOAN TAT THANH CONG 100%!");
    }

    private static void waitWorkersOnline(int minWorkers, long maxWaitMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < maxWaitMs) {
            if (masterServer.getRegistry().getAvailableWorkers().size() >= minWorkers) {
                return;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {}
        }
    }

    public static void setMasterServer(MasterServer server) {
        masterServer = server;
    }

    public static MasterServer getMasterServer() {
        return masterServer;
    }
}
