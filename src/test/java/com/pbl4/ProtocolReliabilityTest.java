package com.pbl4;

import com.pbl4.core.model.*;
import com.pbl4.core.network.*;
import com.pbl4.master.*;
import com.pbl4.master.scheduler.*;
import com.pbl4.worker.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolReliabilityTest {
    static class Peer implements AutoCloseable {
        final ServerSocket server; final ExecutorService pool=Executors.newCachedThreadPool();
        final Function<Message,Message> handler; volatile boolean running=true;
        Peer(Function<Message,Message> handler) throws IOException {
            this.handler=handler; server=new ServerSocket(0);
            pool.submit(()->{
                while(running) try { Socket s=server.accept(); pool.submit(()->handle(s)); }
                catch(IOException e) { if(running)throw new RuntimeException(e); }
            });
        }
        void handle(Socket s) {
            try(s;var in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
                  var out=new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8))) {
                Message request=SocketMessenger.receiveMessage(in); if(request==null)return;
                Message response=handler.apply(request); if(response!=null)SocketMessenger.sendMessage(out,response);
            }catch(IOException ignored){}
        }
        int port(){return server.getLocalPort();}
        public void close() throws IOException {running=false;server.close();pool.shutdownNow();}
    }
    @Test void workerDeduplicatesAcceptedAttemptAndRetriesUnacknowledgedResult() throws Exception {
        AtomicInteger deliveries=new AtomicInteger(),computations=new AtomicInteger();
        AtomicReference<Message> registration=new AtomicReference<>();
        CountDownLatch release=new CountDownLatch(1);
        try(Peer master=new Peer(request->{
            Message response=new Message(MessageType.HEARTBEAT_ACK,"MASTER");response.masterSession="master";
            if(request.getType()==MessageType.REGISTER_WORKER){registration.set(request);response.setType(MessageType.REGISTER_ACK);}
            if(request.getType()==MessageType.TASK_RESULT){
                if(deliveries.incrementAndGet()==1)return null; // Lose the first result ACK.
                response.setType(MessageType.RESULT_ACK);
            }
            return response;
        })) {
            WorkerConfig cfg=new WorkerConfig("w","127.0.0.1",LanLifecycleTest.port(),"127.0.0.1",master.port()); cfg.setCapacity(1);
            WorkerNode worker=new WorkerNode(cfg){
                @Override protected TaskResult executeTask(Task task){
                    computations.incrementAndGet();
                    try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                    return TaskResult.success(task.getTaskId(),task.getJobId(),"w").addResult("value",3);
                }
            };
            try{
                worker.start();LanLifecycleTest.await(()->registration.get()!=null,3000);
                Task t=new Task("t","j",TaskType.ARITHMETIC);t.attemptId="attempt";t.masterSession="master";t.workerSession=worker.getSessionId();
                Message assign=Message.assignTask("w",t);
                // Registration ACK may still be in flight: wait for Worker to adopt the session.
                LanLifecycleTest.await(()->{
                    try{return "master".equals(SocketMessenger.sendAndReceive("127.0.0.1",cfg.getPort(),new Message(MessageType.PING,"test"),1000).masterSession);}
                    catch(IOException e){return false;}
                },3000);
                Message first=SocketMessenger.sendAndReceive("127.0.0.1",cfg.getPort(),assign,2000);
                assertEquals(MessageType.TASK_ACCEPTED,first.getType());
                Message duplicate=SocketMessenger.sendAndReceive("127.0.0.1",cfg.getPort(),assign,2000);
                assertEquals(MessageType.TASK_ACCEPTED,duplicate.getType());
                Task extra=new Task("other","j",TaskType.ARITHMETIC);extra.attemptId="other";extra.masterSession="master";extra.workerSession=worker.getSessionId();
                assertEquals(MessageType.TASK_REJECTED,SocketMessenger.sendAndReceive("127.0.0.1",cfg.getPort(),Message.assignTask("w",extra),2000).getType());
                release.countDown();LanLifecycleTest.await(()->deliveries.get()>=2,5000);
                assertEquals(1,computations.get());
                assertEquals(MessageType.TASK_ACCEPTED,SocketMessenger.sendAndReceive("127.0.0.1",cfg.getPort(),assign,2000).getType());
                assertEquals(1,computations.get());
            }finally{release.countDown();worker.stop();}
        }
    }
    @Test void schedulerRepeatsSameAttemptWhenAcceptanceAckLost() throws Exception {
        List<String> ids=new CopyOnWriteArrayList<>();
        try(Peer peer=new Peer(request->{
            ids.add(request.getTask().attemptId);if(ids.size()==1)return null;
            Message ack=new Message(MessageType.TASK_ACCEPTED,"w");ack.workerSession="w-session";ack.masterSession="m-session";return ack;
        })){
            WorkerRegistry registry=new WorkerRegistry();
            try{
                TaskScheduler scheduler=new TaskScheduler(registry);WorkerInfo w=new WorkerInfo("w","127.0.0.1",peer.port(),1);
                Task task=new Task("t","j",TaskType.ARITHMETIC);task.attemptId="fixed";task.workerSession="w-session";task.masterSession="m-session";
                assertEquals(MessageType.TASK_ACCEPTED,scheduler.sendReservedTask(task,w).getType());
                assertEquals(List.of("fixed","fixed"),ids);
            }finally{registry.shutdown();}
        }
    }
    @Test void legacyMessageHasNoImplicitProtocolUpgrade() throws Exception {
        Message message=SocketMessenger.receiveMessage(new BufferedReader(new StringReader("{\"type\":\"HEARTBEAT\",\"senderId\":\"old\"}\n")));
        assertEquals(0,message.protocolVersion);
    }
}
