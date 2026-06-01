package com.wifigruvs.extender;

import android.util.Log;
import android.net.Network;
import android.os.Build;
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

            // Resolve host from header lines (to check if it targets the local proxy server IP)
            for (String line : lines) {
                if (line.toLowerCase().startsWith("host:")) {
                    String hostValue = line.substring(5).trim();
                    String[] hostPort = hostValue.split(":");
                    host = hostPort[0];
                    break;
                }
            }

            String lohsIp = "";
            WifiRepeaterService service = WifiRepeaterService.getInstance();
            if (service != null) {
                lohsIp = service.getIpAddress();
            }

            boolean isLocalRequest = url.startsWith("/");
            if (host != null && !lohsIp.isEmpty() && (host.equals(lohsIp) || host.equals("127.0.0.1") || host.equals("localhost"))) {
                isLocalRequest = true;
            }

            if (isLocalRequest && !method.equalsIgnoreCase("CONNECT")) {
                handleLocalRequest(clientSocket, url, headerStr);
                return;
            }

            if (method.equalsIgnoreCase("CONNECT")) {
                // HTTPS Connect Tunnel
                String[] hostPort = url.split(":");
                host = hostPort[0];
                remotePort = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 443;

                InetAddress address;
                Network net = WifiRepeaterService.getActiveWifiNetwork();
                if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    address = net.getByName(host);
                } else {
                    address = InetAddress.getByName(host);
                }

                remoteSocket = new Socket();
                if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    net.bindSocket(remoteSocket);
                }
                remoteSocket.connect(new InetSocketAddress(address, remotePort), 10000); // 10s connection timeout
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

                InetAddress address;
                Network net = WifiRepeaterService.getActiveWifiNetwork();
                if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    address = net.getByName(host);
                } else {
                    address = InetAddress.getByName(host);
                }

                remoteSocket = new Socket();
                if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    net.bindSocket(remoteSocket);
                }
                remoteSocket.connect(new InetSocketAddress(address, remotePort), 10000);
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

    private void handleLocalRequest(Socket clientSocket, String url, String headerStr) {
        OutputStream out = null;
        try {
            out = clientSocket.getOutputStream();
            String path = url;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    URL parsed = new URL(url);
                    path = parsed.getPath();
                } catch (Exception e) {
                    int pathStart = url.indexOf("/", 8); // after http:// or https://
                    if (pathStart != -1) {
                        path = url.substring(pathStart);
                    } else {
                        path = "/";
                    }
                }
            }

            int qIndex = path.indexOf("?");
            if (qIndex != -1) {
                path = path.substring(0, qIndex);
            }

            if (path.equals("/mobileconfig")) {
                serveMobileConfig(out);
            } else if (path.equals("/enable-proxy.bat")) {
                serveEnableProxyBat(out);
            } else if (path.equals("/disable-proxy.bat")) {
                serveDisableProxyBat(out);
            } else {
                serveSetupPage(out, headerStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling local request", e);
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    private void serveMobileConfig(OutputStream out) throws IOException {
        String ssid = "";
        String password = "";
        WifiRepeaterService service = WifiRepeaterService.getInstance();
        if (service != null) {
            ssid = service.getSsid();
            password = service.getPassword();
        }
        if (ssid == null) ssid = "";
        if (password == null) password = "";

        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 1) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }

        String config = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\">\n" +
                "<dict>\n" +
                "    <key>ConsentText</key>\n" +
                "    <dict>\n" +
                "        <key>default</key>\n" +
                "        <key>This profile configures your Wi-Fi network and proxy settings for the Wifi Gruvs Extender.</key>\n" +
                "    </dict>\n" +
                "    <key>PayloadContent</key>\n" +
                "    <array>\n" +
                "        <dict>\n" +
                "            <key>AutoJoin</key>\n" +
                "            <true/>\n" +
                "            <key>EncryptionType</key>\n" +
                "            <string>WPA</string>\n" +
                "            <key>HIDDEN</key>\n" +
                "            <false/>\n" +
                "            <key>PayloadDescription</key>\n" +
                "            <string>Configures Wi-Fi settings</string>\n" +
                "            <key>PayloadDisplayName</key>\n" +
                "            <string>Wifi Gruvs Hotspot</string>\n" +
                "            <key>PayloadIdentifier</key>\n" +
                "            <string>com.wifigruvs.extender.wifi1</string>\n" +
                "            <key>PayloadType</key>\n" +
                "            <string>com.apple.wifi.managed</string>\n" +
                "            <key>PayloadUUID</key>\n" +
                "            <string>a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d</string>\n" +
                "            <key>PayloadVersion</key>\n" +
                "            <integer>1</integer>\n" +
                "            <key>ProxyServer</key>\n" +
                "            <string>192.168.49.1</string>\n" +
                "            <key>ProxyServerPort</key>\n" +
                "            <integer>8282</integer>\n" +
                "            <key>ProxyType</key>\n" +
                "            <string>Manual</string>\n" +
                "            <key>SSID_STR</key>\n" +
                "            <string>" + ssid + "</string>\n" +
                "            <key>Password</key>\n" +
                "            <string>" + password + "</string>\n" +
                "        </dict>\n" +
                "    </array>\n" +
                "    <key>PayloadDisplayName</key>\n" +
                "    <string>Wifi Gruvs Configuration</string>\n" +
                "    <key>PayloadIdentifier</key>\n" +
                "    <string>com.wifigruvs.extender</string>\n" +
                "    <key>PayloadRemovalDisallowed</key>\n" +
                "    <false/>\n" +
                "    <key>PayloadType</key>\n" +
                "    <string>Configuration</string>\n" +
                "    <key>PayloadUUID</key>\n" +
                "    <string>f1e2d3c4-b5a6-9f8e-7d6c-5b4a3f2e1d0c</string>\n" +
                "    <key>PayloadVersion</key>\n" +
                "    <integer>1</integer>\n" +
                "</dict>\n" +
                "</plist>";

        byte[] body = config.getBytes("UTF-8");
        String responseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/x-apple-aspen-config\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Content-Disposition: attachment; filename=\"wifi-repeater.mobileconfig\"\r\n" +
                "Connection: close\r\n\r\n";

        out.write(responseHeaders.getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }

    private void serveEnableProxyBat(OutputStream out) throws IOException {
        String script = "@echo off\r\n" +
                "echo ==========================================\r\n" +
                "echo  Wifi Gruvs - Auto Proxy Enable\r\n" +
                "echo ==========================================\r\n" +
                "echo Configuring Wi-Fi proxy server settings...\r\n" +
                "reg add \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\" /v ProxyEnable /t REG_DWORD /d 1 /f >nul\r\n" +
                "reg add \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\" /v ProxyServer /t REG_SZ /d \"192.168.49.1:8282\" /f >nul\r\n" +
                "echo.\r\n" +
                "echo Proxy configuration applied successfully!\r\n" +
                "echo You can now use your internet connection.\r\n" +
                "echo Keep this window open or close it. Press any key to exit.\r\n" +
                "pause >nul\r\n";
        byte[] body = script.getBytes("UTF-8");
        String responseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Content-Disposition: attachment; filename=\"enable-proxy.bat\"\r\n" +
                "Connection: close\r\n\r\n";
        out.write(responseHeaders.getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }

    private void serveDisableProxyBat(OutputStream out) throws IOException {
        String script = "@echo off\r\n" +
                "echo ==========================================\r\n" +
                "echo  Wifi Gruvs - Auto Proxy Disable\r\n" +
                "echo ==========================================\r\n" +
                "echo Resetting Wi-Fi proxy settings...\r\n" +
                "reg add \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\" /v ProxyEnable /t REG_DWORD /d 0 /f >nul\r\n" +
                "echo.\r\n" +
                "echo Proxy settings reset to normal!\r\n" +
                "echo Press any key to exit.\r\n" +
                "pause >nul\r\n";
        byte[] body = script.getBytes("UTF-8");
        String responseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Content-Disposition: attachment; filename=\"disable-proxy.bat\"\r\n" +
                "Connection: close\r\n\r\n";
        out.write(responseHeaders.getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }

    private void serveSetupPage(OutputStream out, String headerStr) throws IOException {
        boolean isApple = headerStr.toLowerCase().contains("iphone") || 
                         headerStr.toLowerCase().contains("ipad") || 
                         headerStr.toLowerCase().contains("macintosh") || 
                         headerStr.toLowerCase().contains("os x");

        String ssid = "";
        String password = "";
        WifiRepeaterService service = WifiRepeaterService.getInstance();
        if (service != null) {
            ssid = service.getSsid();
            password = service.getPassword();
        }
        if (ssid == null) ssid = "";
        if (password == null) password = "";
        
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 1) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }

        String html = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <meta charset=\"utf-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>Wifi Gruvs - Setup Guide</title>\n" +
                "    <style>\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #070a13; color: #ffffff; padding: 20px; margin: 0; line-height: 1.5; }\n" +
                "        .container { max-width: 600px; margin: 0 auto; background: #131b2e; border-radius: 16px; padding: 24px; box-shadow: 0 4px 20px rgba(0,0,0,0.3); border: 1px solid #1e293b; }\n" +
                "        h1 { color: #6366f1; font-size: 24px; font-weight: 800; margin-top: 0; border-bottom: 2px solid #1e293b; padding-bottom: 12px; }\n" +
                "        .btn { display: inline-block; background: #4f46e5; color: #ffffff; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-weight: bold; font-size: 16px; text-align: center; margin: 15px 0; border: none; cursor: pointer; transition: background 0.2s; }\n" +
                "        .btn:hover { background: #6366f1; }\n" +
                "        .card { background: #0c111d; border-radius: 12px; padding: 16px; margin: 15px 0; border: 1px solid #1e293b; }\n" +
                "        .label { font-size: 11px; color: #64748b; font-weight: 700; text-transform: uppercase; margin-bottom: 4px; }\n" +
                "        .value { font-size: 18px; font-weight: bold; color: #ffffff; }\n" +
                "        .instructions { margin-top: 20px; }\n" +
                "        .step { margin-bottom: 15px; padding-left: 30px; position: relative; }\n" +
                "        .step-num { position: absolute; left: 0; top: 0; background: #4f46e5; width: 22px; height: 22px; border-radius: 11px; text-align: center; font-size: 13px; font-weight: bold; line-height: 22px; }\n" +
                "        .highlight { color: #10b981; font-weight: bold; }\n" +
                "        .tab-btn { background: #1e293b; color: #64748b; padding: 8px 16px; border: none; border-radius: 6px; font-weight: bold; margin-right: 8px; cursor: pointer; }\n" +
                "        .tab-btn.active { background: #4f46e5; color: #ffffff; }\n" +
                "        .tab-content { display: none; }\n" +
                "        .tab-content.active { display: block; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"container\">\n" +
                "    <h1>⚡ Wifi Gruvs setup portal</h1>\n" +
                "    <p>You have successfully connected to the repeater hotspot. Follow the steps below to enable internet access.</p>\n" +
                "\n" +
                "    <div class=\"card\">\n" +
                "        <div class=\"label\">Hotspot Network Name (SSID)</div>\n" +
                "        <div class=\"value\">" + ssid + "</div>\n" +
                "    </div>\n" +
                "\n" +
                "    <div class=\"instructions\">\n" +
                (isApple ? 
                "        <h2>🍏 Auto-configure for Apple devices (iOS / macOS)</h2>\n" +
                "        <p>Click the button below to download the Wi-Fi Auto-Configuration Profile. It will set up your connection and proxy settings automatically!</p>\n" +
                "        <a href=\"/mobileconfig\" class=\"btn\">Install Auto-Config Profile</a>\n" +
                "        <div class=\"step\">\n" +
                "            <div class=\"step-num\">1</div>\n" +
                "            Tap the button above. iOS will show a prompt saying \"Profile Downloaded\".\n" +
                "        </div>\n" +
                "        <div class=\"step\">\n" +
                "            <div class=\"step-num\">2</div>\n" +
                "            Open your device <b>Settings</b>. You will see a new item at the top called <b>Profile Downloaded</b> (or go to General -> VPN & Device Management).\n" +
                "        </div>\n" +
                "        <div class=\"step\">\n" +
                "            <div class=\"step-num\">3</div>\n" +
                "            Tap it, click <b>Install</b> in the top right, enter your passcode, and confirm installation. Your internet will begin working immediately!\n" +
                "        </div>\n" :
                "        <h2>Manual Proxy Setup</h2>\n" +
                "        <div style=\"margin-bottom: 20px;\">\n" +
                "            <button class=\"tab-btn active\" onclick=\"openTab('windows')\">Windows</button>\n" +
                "            <button class=\"tab-btn\" onclick=\"openTab('android')\">Android</button>\n" +
                "        </div>\n" +
                "\n" +
                "        <div id=\"windows\" class=\"tab-content active\">\n" +
                "            <h3>🍏 Option A: 1-Click Auto-Configuration (Recommended)</h3>\n" +
                "            <p>Download and run this quick script to automatically configure your Wi-Fi proxy settings:</p>\n" +
                "            <a href=\"/enable-proxy.bat\" class=\"btn\">Download Auto-Proxy Script</a>\n" +
                "            <p style=\"font-size: 12px; color: #64748b;\">Note: To turn the proxy off later when you disconnect, download and run the <a href=\"/disable-proxy.bat\" style=\"color: #6366f1; text-decoration: underline;\">Disable Proxy Script</a>.</p>\n" +
                "            <br>\n" +
                "            <h3>🔧 Option B: Manual Configuration</h3>\n" +
                "            <div class=\"step\">\n" +
                "                <div class=\"step-num\">1</div>\n" +
                "                Open Windows <b>Settings</b> and go to <b>Network & Internet</b>.\n" +
                "            </div>\n" +
                "            <div class=\"step\">\n" +
                "                <div class=\"step-num\">2</div>\n" +
                "                Select the <b>Proxy</b> tab from the left sidebar.\n" +
                "            </div>\n" +
                "            <div class=\"step\">\n" +
                "                <div class=\"step-num\">3</div>\n" +
                "                Under <i>Manual proxy setup</i>, toggle <b>Use a proxy server</b> to <span class=\"highlight\">ON</span>.\n" +
                "            </div>\n" +
                "            <div class=\"step\">\n" +
                "                <div class=\"step-num\">4</div>\n" +
                "                Enter these proxy parameters and click <b>Save</b>:\n" +
                "                <div class=\"card\" style=\"margin-top: 8px; padding: 10px;\">\n" +
                "                    Address: <span class=\"highlight\">192.168.49.1</span><br>\n" +
                "                    Port: <span class=\"highlight\">8282</span>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "\n" +
                "        <div id=\"android\" class=\"tab-content\">\n" +
                "            <div class=\"step\">\n" +
                "                <div class=\"step-num\">1</div>\n" +
                "                Open your device <b>Wi-Fi Settings</b>.\n" +
                "            </div>\n" +
                "            <div class=\"step\">\n" +
                "                <div class=\"step-num\">2</div>\n" +
                "                Tap the <b>cog icon</b> or long-press your connected Wi-Fi network and select <b>Modify Network</b>.\n" +
                "            </div>\n" +
                "            <div class=\"step\">\n" +
                "                <div class=\"step-num\">3</div>\n" +
                "                Expand the <b>Advanced options</b> dropdown.\n" +
                "            </div>\n" +
                "            <div class=\"step\">\n" +
                "                <div class=\"step-num\">4</div>\n" +
                "                Set the Proxy dropdown to <span class=\"highlight\">Manual</span> and enter:\n" +
                "                <div class=\"card\" style=\"margin-top: 8px; padding: 10px;\">\n" +
                "                    Proxy Host Name: <span class=\"highlight\">192.168.49.1</span><br>\n" +
                "                    Proxy Port: <span class=\"highlight\">8282</span>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "        </div>\n") +
                "    </div>\n" +
                "</div>\n" +
                "<script>\n" +
                "    function openTab(tabId) {\n" +
                "        var i;\n" +
                "        var x = document.getElementsByClassName(\"tab-content\");\n" +
                "        for (i = 0; i < x.length; i++) {\n" +
                "            x[i].classList.remove(\"active\");\n" +
                "        }\n" +
                "        var buttons = document.getElementsByClassName(\"tab-btn\");\n" +
                "        for (i = 0; i < buttons.length; i++) {\n" +
                "            buttons[i].classList.remove(\"active\");\n" +
                "        }\n" +
                "        document.getElementById(tabId).classList.add(\"active\");\n" +
                "        event.currentTarget.classList.add(\"active\");\n" +
                "    }\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";

        byte[] body = html.getBytes("UTF-8");
        String responseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";

        out.write(responseHeaders.getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }
}
