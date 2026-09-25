package com.pbl4.computing.graph;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Các thuật toán tìm đường đi ngắn nhất: Dijkstra từ nhiều nguồn (Multi-Source Dijkstra) và Floyd-Warshall.
 */
public class ShortestPathAlgorithms {

    /**
     * Thuật toán Dijkstra tìm đường đi ngắn nhất từ 1 đỉnh nguồn source đến tất cả các đỉnh khác.
     */
    public static double[] dijkstra(Graph graph, int source) {
        int V = graph.getVertices();
        double[][] adj = graph.getAdjMatrix();
        double[] dist = new double[V];
        Arrays.fill(dist, Graph.INF);
        dist[source] = 0;

        // Pair: [vertex, distance]
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[1]));
        pq.add(new double[]{source, 0.0});

        boolean[] visited = new boolean[V];

        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            int u = (int) top[0];
            double d = top[1];

            if (visited[u]) continue;
            visited[u] = true;

            for (int v = 0; v < V; v++) {
                if (adj[u][v] < Graph.INF / 2) {
                    if (dist[u] + adj[u][v] < dist[v]) {
                        dist[v] = dist[u] + adj[u][v];
                        pq.add(new double[]{v, dist[v]});
                    }
                }
            }
        }
        return dist;
    }

    /**
     * Chạy Dijkstra cho một danh sách các đỉnh nguồn (Được Worker thực thi cho phân đoạn đỉnh được giao).
     */
    public static double[][] dijkstraBatch(Graph graph, int[] sourceVertices) {
        int V = graph.getVertices();
        double[][] results = new double[sourceVertices.length][V];
        for (int i = 0; i < sourceVertices.length; i++) {
            results[i] = dijkstra(graph, sourceVertices[i]);
        }
        return results;
    }

    /**
     * Tính toán toàn bộ đường đi ngắn nhất giữa mọi cặp đỉnh (All-Pairs Shortest Path) theo cách tuần tự để đối chiếu.
     */
    public static double[][] allPairsDijkstraSequential(Graph graph) {
        int V = graph.getVertices();
        double[][] allDist = new double[V][V];
        for (int i = 0; i < V; i++) {
            allDist[i] = dijkstra(graph, i);
        }
        return allDist;
    }
}
