import com.pbl4.master.MasterServer;
import com.pbl4.master.http.MasterHttpServer;
import java.util.concurrent.CountDownLatch;

public class UiMatrixSmoke {
    public static void main(String[] args) throws Exception {
        MasterServer master = new MasterServer(15000, "127.0.0.1");
        master.setJobTimeoutMs(5000);
        master.start();
        MasterHttpServer http = new MasterHttpServer(master, 8080);
        http.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { http.stop(); master.stop(); }));
        new CountDownLatch(1).await();
    }
}
