package com.pbl4;

import com.pbl4.computing.graph.Graph;
import com.pbl4.computing.graph.ShortestPathAlgorithms;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GraphAlgorithmTest {

    @Test
    public void testDijkstraBasic() {
        Graph g = new Graph(4);
        g.addEdge(0, 1, 2.0);
        g.addEdge(1, 2, 3.0);
        g.addEdge(0, 2, 7.0);
        g.addEdge(2, 3, 1.0);

        double[] dist = ShortestPathAlgorithms.dijkstra(g, 0);

        assertEquals(0.0, dist[0]);
        assertEquals(2.0, dist[1]);
        assertEquals(5.0, dist[2]); // 0 -> 1 -> 2 is 2+3=5 < 7
        assertEquals(6.0, dist[3]); // 5 + 1 = 6
    }
}
