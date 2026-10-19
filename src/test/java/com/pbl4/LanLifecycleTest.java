package com.pbl4;

import com.pbl4.computing.matrix.*;
import com.pbl4.core.model.*;
import com.pbl4.core.network.*;
import com.pbl4.master.MasterServer;
import com.pbl4.worker.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class LanLifecycleTest {
    @Test void unfinishedTaskSurvivesLossOfAllWorkersAndNewWorkerJoining() throws Exception {
        int mp=port(); MasterServer master=new MasterServer(mp);
        WorkerConfig cfg=new WorkerConfig("lost","127.0.0.1",port(),"127.0.0.1",mp); cfg.setCapacity(1);
        CountDownLatch entered=new CountDownLatch(1);
        WorkerNode lost=new WorkerNode(cfg) {
            @Override protected TaskResult executeTask(Task task) {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                return TaskResult.failure(task.getTaskId(),task.getJobId(),"lost","stopped");
            }
        };
        WorkerNode replacement=worker("replacement",port(),mp);
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try {
            master.start(); lost.start(); await(()->master.getRegistry().getWorker("lost")!=null,5000);
            master.setJobTimeoutMs(8000);
            Future<Matrix> result=caller.submit(()->master.runMatrixMultiplication(Matrix.identity(3),Matrix.identity(3)));
            assertTrue(entered.await(3,TimeUnit.SECONDS)); lost.stop();
            master.getRegistry().getWorker("lost").setLastHeartbeatTime(System.currentTimeMillis()-10000);
            master.getRegistry().checkHealth(); assertFalse(result.isDone());
            replacement.start(); assertEquals(Matrix.identity(3),result.get(6,TimeUnit.SECONDS));
        } finally { lost.stop(); replacement.stop(); master.stop(); caller.shutdownNow(); }
    }
    static int port() throws Exception { try(ServerSocket s=new ServerSocket(0)) { return s.getLocalPort(); } }
    static void await(BooleanSupplier condition,long millis) throws Exception {
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(),"Condition did not become true before timeout");
    }
    static WorkerNode worker(String id,int workerPort,int masterPort) {
        WorkerConfig cfg=new WorkerConfig(id,"127.0.0.1",workerPort,"127.0.0.1",masterPort); cfg.setCapacity(2);
        cfg.setHeartbeatIntervalSeconds(1); return new WorkerNode(cfg);
    }
    @Test void startsBeforeMasterAndReconnectsAfterMasterRestart() throws Exception {
        int mp=port(); WorkerNode w=worker("early",port(),mp); MasterServer first=new MasterServer(mp),second=null;
        try {
            w.start(); Thread.sleep(200); first.start();
            await(()->first.getRegistry().getAvailableWorkers().size()==1,8000);
            assertEquals(9.,first.runArithmeticStarter("1+2","3*2","+"));
            first.stop(); second=new MasterServer(mp); second.start(); MasterServer restored=second;
            await(()->restored.getRegistry().getAvailableWorkers().size()==1,8000);
            assertEquals(9.,restored.runArithmeticStarter("1+2","3*2","+"));
        } finally { w.stop(); first.stop(); if(second!=null)second.stop(); }
    }
    @Test void rejectsUnreachableAndDuplicateRegistrationAndPreservesPause() throws Exception {
        int mp=port(); MasterServer master=new MasterServer(mp); WorkerNode w=worker("unique",port(),mp);
        try {
            master.start(); w.start(); await(()->master.getRegistry().getWorker("unique")!=null,5000);
            WorkerInfo fake=new WorkerInfo("bad","127.0.0.1",port(),1); fake.sessionId="missing";
            Message rejected=SocketMessenger.sendAndReceive("127.0.0.1",mp,Message.register(fake),4000);
            assertEquals(MessageType.REGISTER_REJECTED,rejected.getType()); assertNull(master.getRegistry().getWorker("bad"));
            WorkerNode duplicate=worker("unique",port(),mp);
            try {
                duplicate.start(); Thread.sleep(300);
                assertEquals(w.getSessionId(),master.getRegistry().getWorker("unique").sessionId);
                master.toggleWorkerState("unique");
                await(()->!w.isEnabled(),3000); Thread.sleep(1200);
                assertTrue(master.getRegistry().getWorker("unique").paused);
                assertEquals(WorkerStatus.ONLINE,master.getRegistry().getWorker("unique").getStatus());
                master.toggleWorkerState("unique"); await(w::isEnabled,3000);
            } finally { duplicate.stop(); }
        } finally { w.stop(); master.stop(); }
    }
    @Test void realWorkerRestartReceivesNewJobs() throws Exception {
        int mp=port(),wp=port(); MasterServer master=new MasterServer(mp); WorkerNode old=worker("restart",wp,mp),fresh=null;
        try {
            master.start(); old.start(); await(()->master.getRegistry().getWorker("restart")!=null,5000);
            old.stop(); master.getRegistry().getWorker("restart").setLastHeartbeatTime(System.currentTimeMillis()-10000);
            master.getRegistry().checkHealth(); fresh=worker("restart",wp,mp); fresh.start(); WorkerNode replacement=fresh;
            await(()->replacement.getSessionId().equals(master.getRegistry().getWorker("restart").sessionId),5000);
            assertEquals(9.,master.runArithmeticStarter("1+2","3*2","+"));
        } finally { old.stop(); if(fresh!=null)fresh.stop(); master.stop(); }
    }
    @Test void unevenRowsAndAllMatrixOperationsAcrossTwoWorkers() throws Exception {
        int mp=port(); MasterServer master=new MasterServer(mp); WorkerNode a=worker("a",port(),mp),b=worker("b",port(),mp);
        try {
            master.start(); a.start(); b.start(); await(()->master.getRegistry().getAvailableWorkers().size()==2,5000);
            // Resource policy is tested with deterministic metrics separately; don't depend on host CPU load here.
            Matrix x=new Matrix(new double[][]{{1,2,3},{4,5,6},{7,8,10},{2,4,8},{3,6,9}});
            Matrix y=new Matrix(new double[][]{{2,3,4},{3,4,5},{4,5,6},{5,6,7},{6,7,8}});
            assertEquals(MatrixOperations.add(x,y),master.runMatrixAddOrSub(x,y,true));
            assertEquals(MatrixOperations.subtract(x,y),master.runMatrixAddOrSub(x,y,false));
            assertEquals(MatrixOperations.modulo(x,y),master.runMatrixModulo(x,y));
            Matrix z=new Matrix(new double[][]{{1,2},{3,4},{5,6}});
            assertEquals(MatrixOperations.multiply(x,z),master.runMatrixMultiplication(x,z));
            assertEquals(MatrixOperations.multiply(x,z),master.runMatrixChainMultiplication(List.of(x,z,Matrix.identity(2))));
            Matrix singular=new Matrix(new double[][]{{1,2,3},{2,4,6},{0,1,1}});
            assertEquals(0.,master.runMatrixDeterminant(singular),1e-6);
            assertEquals(2,master.runMatrixRank(singular));
            assertTrue(master.getRegistry().getWorker("a").getCompletedTasks()>0);
            assertTrue(master.getRegistry().getWorker("b").getCompletedTasks()>0);
            assertThrows(IllegalArgumentException.class,()->master.runMatrixMultiplication(x,x));
        } finally { a.stop(); b.stop(); master.stop(); }
    }
}
