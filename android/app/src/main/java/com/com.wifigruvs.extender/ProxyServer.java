package com.wifigruvs.extender;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class ProxyServer {
    private static final String TAG = "WifiRepeaterProxy";
    private final int port;
    private ServerSocket serverSocket;
    private boolean running = false;
    private ExecutorService threadPool;

    // Bandwidth monitoring
    private final AtomicLong totalRxBytes = new AtomicLong(0); // Download (remote -> client)
    private final AtomicLong totalTxBytes = new AtomicLong(0); // Upload (client -> remote)
    
    private long rxSpeed = 0; // Bytes/sec
    private long txSpeed = 0; // Bytes/sec
    private long lastRxBytes = 0;
    private long lastTxBytes = 0;
    private long lastSpeedCheckTime = 0;
    
    private ScheduledExecutorService speedScheduler;

    // Client tracking
    public static class ClientStats {
        public String ip;
        public long totalBytes = 0;
        public long lastActivity = 0;

        public ClientStats(String ip) {
            this.ip = ip;
            this.lastActivity = System.currentTimeMillis();
        }
    }
    
    private final ConcurrentHashMap<String, ClientStats> clients = new ConcurrentHashMap<>();

    public ProxyServer(int port) {
        this.port = port;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        running = true;
        
        totalRxBytes.set(0);
        totalTxBytes.set(0);
        rxSpeed = 0;
        txSpeed = 0;
        lastRxBytes = 0;
        lastTxBytes = 0;
        lastSpeedCheckTime = System.currentTimeMillis();
        clients.clear();

        serverSocket = new ServerSocket(port);
        threadPool = Executors.newCachedThreadPool();

        // Start listening thread
        new Thread(this::listenLoop).start();

        // Start speed calculation scheduler
        speedScheduler = Executors.newSingleThreadScheduledExecutor();
        speedScheduler.scheduleAtFixedRate(this::calculateSpeed, 1, 1, TimeUnit.SECONDS);

        Log.i(TAG, "Proxy server started on port " + port);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server socket", e);
        }

        if (threadPool != null) {
            threadPool.shutdownNow();
        }

        if (speedScheduler != null) {
            speedScheduler.shutdownNow();
        }

        Log.i(TAG, "Proxy server stopped");
    }

    private void listenLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (running) {
                    Log.e(TAG, "Error accepting client connection", e);
                }
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        Socket remoteSocket = null;
        String clientIp = "";
        try {
            clientIp = clientSocket.getInetAddress().getHostAddress();
            registerClientActivity(clientIp, 0);

            clientSocket.setSoTimeout(30000); // 30s timeout
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();

            // Read the request headers (buffer up to 16KB)
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            byte[] oneByte = new byte[1];
            int readBytes;
            boolean headersComplete = false;
            int state = 0; // State machine to detect \r\n\r\n

            while (running && (readBytes = clientIn.read(oneByte)) != -1) {
                headerBuffer.write(oneByte[0]);
                if (oneByte[0] == '\r') {
                    if (state == 0) state = 1;
                    else if (state == 2) state = 3;
                    else state = 0;
                } else if (oneByte[0] == '\n') {
                    if (state == 1) state = 2;
                    else if (state == 3) {
                        headersComplete = true;
                        break;
                    } else state = 0;
                } else {
                    state = 0;
                }

                if (headerBuffer.size() > 16384) {
                    break;
                }
            }

            if (!headersComplete) {
                clientSocket.close();
                return;
            }

            byte[] headerBytes = headerBuffer.toByteArray();
            String headerStr = new String(headerBytes, "ISO-8859-1");
            String[] lines = headerStr.split("\r\n");
            if (lines.length == 0 || lines[0].trim().isEmpty()) {
                clientSocket.close();
                return;
            }

            String requestLine = lines[0];
            String[] tokens = requestLine.split(" ");
            if (tokens.length < 2) {
                clientSocket.close();
                return;
            }

            String method = tokens[0];
            String url = tokens[1];

            String host = null;
            int remotePort = 80;

            if (method.equalsIgnoreCase("CONNECT")) {
                // HTTPS Connect Tunnel
                String[] hostPort = url.split(":");
                host = hostPort[0];
                remotePort = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 443;

                remoteSocket = new Socket();
                remoteSocket.connect(new InetSocketAddress(host, remotePort), 10000); // 10s connection timeout
                remoteSocket.setSoTimeout(30000);

                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
                clientOut.flush();

                tunnel(clientSocket, remoteSocket, clientIp);
            } else {
                // HTTP Request
                for (String line : lines) {
                    if (line.toLowerCase().startsWith("host:")) {
                        String hostValue = line.substring(5).trim();
                        String[] hostPort = hostValue.split(":");
                        host = hostPort[0];
                        if (hostPort.length > 1) {
                            remotePort = Integer.parseInt(hostPort[1]);
                        }
                        break;
                    }
                }

                if (host == null) {
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        URL parsedUrl = new URL(url);
                        host = parsedUrl.getHost();
                        remotePort = parsedUrl.getPort() != -1 ? parsedUrl.getPort() : (url.startsWith("https://") ? 443 : 80);
                    }
                }

                if (host == null) {
                    clientSocket.close();
                    return;
                }

                remoteSocket = new Socket();
                remoteSocket.connect(new InetSocketAddress(host, remotePort), 10000);
                remoteSocket.setSoTimeout(30000);

                // Forward headers
                OutputStream remoteOut = remoteSocket.getOutputStream();
                remoteOut.write(headerBytes);
                remoteOut.flush();

                // Track the bytes sent (upload)
                totalTxBytes.addAndGet(headerBytes.length);
                registerClientActivity(clientIp, headerBytes.length);

                tunnel(clientSocket, remoteSocket, clientIp);
            }
        } catch (Exception e) {
            // Socket closure is normal for network requests
            try { clientSocket.close(); } catch (IOException ignored) {}
            if (remoteSocket != null) {
                try { remoteSocket.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void tunnel(Socket client, Socket remote, final String clientIp) {
        CountDownLatch latch = new CountDownLatch(2);

        // Tunnel thread: client -> remote (Upload / Tx)
        threadPool.submit(() -> {
            try {
                copyStream(client.getInputStream(), remote.getOutputStream(), totalTxBytes, clientIp);
            } catch (Exception ignored) {
            } finally {
                latch.countDown();
                try { remote.shutdownOutput(); } catch (Exception ignored) {}
                try { client.shutdownInput(); } catch (Exception ignored) {}
            }
        });

        // Tunnel thread: remote -> client (Download / Rx)
        threadPool.submit(() -> {
            try {
                copyStream(remote.getInputStream(), client.getOutputStream(), totalRxBytes, clientIp);
            } catch (Exception ignored) {
            } finally {
                latch.countDown();
                try { client.shutdownOutput(); } catch (Exception ignored) {}
                try { remote.shutdownInput(); } catch (Exception ignored) {}
            }
        });

        try {
            latch.await(2, TimeUnit.HOURS);
        } catch (InterruptedException ignored) {
        } finally {
            try { client.close(); } catch (Exception ignored) {}
            try { remote.close(); } catch (Exception ignored) {}
        }
    }

    private void copyStream(InputStream in, OutputStream out, AtomicLong byteCounter, String clientIp) throws IOException {
        byte[] buffer = new byte[16384];
        int len;
        while (running && (len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
            out.flush();
            byteCounter.addAndGet(len);
            registerClientActivity(clientIp, len);
        }
    }

    private void registerClientActivity(String ip, long bytes) {
        if (ip == null || ip.isEmpty() || ip.equals("127.0.0.1")) return;
        
        clients.compute(ip, (key, val) -> {
            ClientStats stats = (val == null) ? new ClientStats(ip) : val;
            stats.totalBytes += bytes;
            stats.lastActivity = System.currentTimeMillis();
            return stats;
        });
    }

    private void calculateSpeed() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastSpeedCheckTime;
        if (elapsed <= 0) return;

        long currentRx = totalRxBytes.get();
        long currentTx = totalTxBytes.get();

        rxSpeed = (currentRx - lastRxBytes) * 1000 / elapsed;
        txSpeed = (currentTx - lastTxBytes) * 1000 / elapsed;

        lastRxBytes = currentRx;
        lastTxBytes = currentTx;
        lastSpeedCheckTime = now;

        // Clean up inactive clients (no activity in 5 minutes)
        long inactiveTimeout = System.currentTimeMillis() - (5 * 60 * 1000);
        clients.entrySet().removeIf(entry -> entry.getValue().lastActivity < inactiveTimeout);
    }

    // Getters for stats
    public long getRxSpeed() { return rxSpeed; }
    public long getTxSpeed() { return txSpeed; }
    public long getTotalRxBytes() { return totalRxBytes.get(); }
    public long getTotalTxBytes() { return totalTxBytes.get(); }

    public String getClientsJson() {
        try {
            JSONArray arr = new JSONArray();
            for (ClientStats stat : clients.values()) {
                JSONObject obj = new JSONObject();
                obj.put("ip", stat.ip);
                obj.put("totalBytes", stat.totalBytes);
                obj.put("lastSeen", stat.lastActivity);
                arr.put(obj);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }
}
