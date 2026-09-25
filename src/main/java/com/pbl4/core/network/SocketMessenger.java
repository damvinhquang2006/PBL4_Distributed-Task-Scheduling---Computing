package com.pbl4.core.network;

import com.pbl4.core.model.Message;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Tiện ích gửi và nhận thông điệp Message qua kết nối TCP Socket bằng định dạng JSON phân cách bằng dòng mới (\n).
 */
public class SocketMessenger {

    /**
     * Gửi một Message qua socket writer (kèm ký tự xuống dòng và flush).
     */
    public static void sendMessage(BufferedWriter writer, Message message) throws IOException {
        String json = JsonUtils.toJson(message);
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    /**
     * Đọc một Message từ socket reader. Trả về null nếu stream đóng.
     */
    public static Message receiveMessage(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        return JsonUtils.fromJson(line, Message.class);
    }

    /**
     * Gửi một Message đến địa chỉ host:port xác định và đóng kết nối (Fire and Forget).
     */
    public static void sendOneWay(String host, int port, Message message, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                sendMessage(writer, message);
            }
        }
    }

    /**
     * Gửi một Message đến địa chỉ host:port và đợi phản hồi Message trả về (Request - Response).
     */
    public static Message sendAndReceive(String host, int port, Message request, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                sendMessage(writer, request);
                return receiveMessage(reader);
            }
        }
    }
}
