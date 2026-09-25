package com.pbl4.computing.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Arrays;
import java.util.Random;

/**
 * Biểu diễn đồ thị có trọng số bằng ma trận kề (Adjacency Matrix) phục vụ bài toán tìm đường đi ngắn nhất phân tán.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Graph {
    public static final double INF = 1e9; // Vô cực đại diện cho không có cạnh nối
    
    private int vertices;
    private double[][] adjMatrix;

    public Graph() {
    }

    public Graph(int vertices) {
        this.vertices = vertices;
        this.adjMatrix = new double[vertices][vertices];
        for (int i = 0; i < vertices; i++) {
            Arrays.fill(this.adjMatrix[i], INF);
            this.adjMatrix[i][i] = 0;
        }
    }

    public static Graph randomGraph(int vertices, double density, double maxWeight) {
        Graph g = new Graph(vertices);
        Random rng = new Random();
        for (int i = 0; i < vertices; i++) {
            for (int j = 0; j < vertices; j++) {
                if (i != j && rng.nextDouble() < density) {
                    double weight = Math.round((1.0 + rng.nextDouble() * (maxWeight - 1.0)) * 10.0) / 10.0;
                    g.addEdge(i, j, weight);
                }
            }
        }
        return g;
    }

    public void addEdge(int u, int v, double weight) {
        if (u >= 0 && u < vertices && v >= 0 && v < vertices) {
            adjMatrix[u][v] = weight;
        }
    }

    public int getVertices() {
        return vertices;
    }

    public void setVertices(int vertices) {
        this.vertices = vertices;
    }

    public double[][] getAdjMatrix() {
        return adjMatrix;
    }

    public void setAdjMatrix(double[][] adjMatrix) {
        this.adjMatrix = adjMatrix;
        if (adjMatrix != null) {
            this.vertices = adjMatrix.length;
        }
    }

    public String prettyPrint(int maxDisplay) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(vertices, maxDisplay);
        sb.append(String.format("Graph [Vertices: %d]:\n", vertices));
        for (int i = 0; i < n; i++) {
            sb.append("  [ ");
            for (int j = 0; j < n; j++) {
                if (adjMatrix[i][j] >= INF / 2) {
                    sb.append("     INF ");
                } else {
                    sb.append(String.format("%8.2f ", adjMatrix[i][j]));
                }
            }
            if (vertices > maxDisplay) sb.append(" ... ");
            sb.append("]\n");
        }
        return sb.toString();
    }
}
