package com.pbl4.app;

import com.pbl4.computing.matrix.*;
import com.pbl4.core.model.LoadBalancingPolicy;
import com.pbl4.master.MasterServer;
import java.util.*;

/** Run on laptop A instead of MasterApp; repeat with A-only, B-only and A+B Workers. */
public class LanBenchmarkApp {
    public static void main(String[] args) throws Exception {
        Map<String,String> options=new HashMap<>();
        if(args.length%2!=0) throw new IllegalArgumentException("Arguments must be --name value pairs");
        for(int i=0;i<args.length;i+=2) options.put(args[i],args[i+1]);
        int port=Integer.parseInt(options.getOrDefault("--port","5000"));
        int workers=Integer.parseInt(options.getOrDefault("--workers","2"));
        int size=Integer.parseInt(options.getOrDefault("--size","300"));
        int rounds=Integer.parseInt(options.getOrDefault("--rounds","3"));
        long seed=Long.parseLong(options.getOrDefault("--seed","42"));
        if(workers<1||size<1||rounds<1) throw new IllegalArgumentException("workers, size and rounds must be positive");
        MasterServer master=new MasterServer(port);
        master.getScheduler().setPolicy(LoadBalancingPolicy.RESOURCE_AWARE);
        Runtime.getRuntime().addShutdownHook(new Thread(master::stop));
        try {
            master.start(); long deadline=System.nanoTime()+120_000_000_000L;
            System.out.println("Waiting up to 120s for exactly " + workers + " Workers");
            while(master.getRegistry().getAvailableWorkers().size()!=workers) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Expected Worker count not reached");
                Thread.sleep(200);
            }
            // Allow registration acknowledgments and initial metric samples to settle.
            Thread.sleep(3500);
            Random random=new Random(seed); Matrix a=new Matrix(size,size),b=new Matrix(size,size);
            for(int i=0;i<size;i++) for(int j=0;j<size;j++) { a.getData()[i][j]=random.nextDouble(); b.getData()[i][j]=random.nextDouble(); }
            Matrix expected=MatrixOperations.multiply(a,b); verify(expected,master.runMatrixMultiplication(a,b));
            System.out.println("BENCHMARK size="+size+" seed="+seed+" workers="+workers+" policy=RESOURCE_AWARE warmup=1");
            System.out.println("round,sequential_ms,distributed_ms,speedup,max_absolute_error");
            for(int round=1;round<=rounds;round++) {
                if(master.getRegistry().getAvailableWorkers().size()!=workers) throw new IllegalStateException("Worker count changed during benchmark");
                long start=System.nanoTime(); Matrix seq=MatrixOperations.multiply(a,b); double sequential=(System.nanoTime()-start)/1e6;
                start=System.nanoTime(); Matrix result=master.runMatrixMultiplication(a,b); double distributed=(System.nanoTime()-start)/1e6;
                double error=verify(seq,result);
                System.out.printf(Locale.ROOT,"%d,%.3f,%.3f,%.4f,%.9g%n",round,sequential,distributed,sequential/distributed,error);
            }
            master.getRegistry().getAllWorkers().forEach(w->System.out.println(w+" "+w.metrics));
        }finally{master.stop();}
    }
    private static double verify(Matrix expected,Matrix actual) {
        if(actual==null)throw new IllegalStateException("Distributed job failed");
        double max=0;
        for(int i=0;i<expected.getRows();i++)for(int j=0;j<expected.getCols();j++){
            double e=expected.getData()[i][j],v=actual.getData()[i][j];
            if(!Double.isFinite(v)||Math.abs(e-v)>1e-6*Math.max(1,Math.abs(e)))throw new IllegalStateException("Result mismatch");
            max=Math.max(max,Math.abs(e-v));
        }
        return max;
    }
}
