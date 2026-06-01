package com.wifigruvs.extender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import java.net.NetworkInterface;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WifiRepeaterService extends Service {
    private static final String TAG = "WifiRepeaterService";
    private static final String CHANNEL_ID = "WifiRepeaterChannel";
    private static final int NOTIFICATION_ID = 9182;
    private static final int PROXY_PORT = 8282;

    private static WifiRepeaterService instance = null;
    private static ReactApplicationContext reactContext = null;

    private WifiManager wifiManager;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private ProxyServer proxyServer;
    
    private String ssid = "";
    private String password = "";
    private String ipAddress = "";
    private boolean isHotspotStarted = false;
    
    private ScheduledExecutorService updateScheduler;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ConnectivityManager.NetworkCallback networkCallback = null;
    private Network wifiNetwork = null;

    public class LocalBinder extends Binder {
        public WifiRepeaterService getService() {
            return WifiRepeaterService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    public static WifiRepeaterService getInstance() {
        return instance;
    }

    public static Network getActiveWifiNetwork() {
        return instance != null ? instance.wifiNetwork : null;
    }

    public static void setReactContext(ReactApplicationContext context) {
        reactContext = context;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        createNotificationChannel();

        // Track active Wi-Fi internet network for routing proxy connections
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        super.onAvailable(network);
                        wifiNetwork = network;
                        Log.i(TAG, "Active Wi-Fi Internet network available: " + network);
                    }
                    @Override
                    public void onLost(Network network) {
                        super.onLost(network);
                        if (wifiNetwork != null && wifiNetwork.equals(network)) {
                            wifiNetwork = null;
                        }
                        Log.i(TAG, "Active Wi-Fi Internet network lost: " + network);
                    }
                };
                cm.registerNetworkCallback(request, networkCallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to register network callback", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Starting foreground service");
        
        // Start foreground with an initial notification
        Notification notification = buildNotification("Initializing Extender...", "Starting hotspot...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        startHotspotAndProxy();

        // Periodically update UI and notification
        updateScheduler = Executors.newSingleThreadScheduledExecutor();
        updateScheduler.scheduleAtFixedRate(this::tickService, 1, 1, TimeUnit.SECONDS);

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Stopping foreground service");
        
        stopHotspotAndProxy();

        if (updateScheduler != null) {
            updateScheduler.shutdownNow();
        }

        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister network callback", e);
            }
            networkCallback = null;
        }

        wifiNetwork = null;
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void startHotspotAndProxy() {
        try {
            if (wifiManager == null) {
                updateNotificationError("Wi-Fi hardware unavailable");
                emitEvent("onExtenderError", "Wi-Fi hardware unavailable");
                return;
            }

            wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    super.onStarted(reservation);
                    hotspotReservation = reservation;
                    WifiConfiguration config = reservation.getWifiConfiguration();
                    
                    if (config != null) {
                        ssid = config.SSID;
                        password = config.preSharedKey;
                    }
                    
                    // Look up IP address
                    ipAddress = findHotspotIpAddress();
                    if (ipAddress == null || ipAddress.isEmpty()) {
                        ipAddress = "192.168.49.1"; // Default fallback
                    }

                    // Start Proxy Server
                    try {
                        proxyServer = new ProxyServer(PROXY_PORT);
                        proxyServer.start();
                        isHotspotStarted = true;
                        
                        Log.i(TAG, "Hotspot started. SSID: " + ssid + ", IP: " + ipAddress);
                        
                        updateNotificationInfo();
                        emitState();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to start proxy server", e);
                        updateNotificationError("Proxy start failed: " + e.getMessage());
                        emitEvent("onExtenderError", "Failed to start proxy server: " + e.getMessage());
                        stopSelf();
                    }
                }

                @Override
                public void onStopped() {
                    super.onStopped();
                    Log.i(TAG, "LocalOnlyHotspot stopped");
                    isHotspotStarted = false;
                    emitState();
                    stopSelf();
                }

                @Override
                public void onFailed(int reason) {
                    super.onFailed(reason);
                    Log.e(TAG, "LocalOnlyHotspot failed to start: " + reason);
                    isHotspotStarted = false;
                    String errorText = "Hotspot start failed (Code: " + reason + ")";
                    if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL) {
                        errorText = "No suitable Wi-Fi channel available";
                    }
                    updateNotificationError(errorText);
                    emitEvent("onExtenderError", errorText);
                    stopSelf();
                }
            }, handler);

        } catch (SecurityException e) {
            Log.e(TAG, "Location permission missing", e);
            updateNotificationError("Permission denied: location required");
            emitEvent("onExtenderError", "Location permission is required to start the hotspot.");
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start hotspot", e);
            updateNotificationError("Error: " + e.getMessage());
            emitEvent("onExtenderError", e.getMessage());
            stopSelf();
        }
    }

    private void stopHotspotAndProxy() {
        if (hotspotReservation != null) {
            try {
                hotspotReservation.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing hotspot reservation", e);
            }
            hotspotReservation = null;
        }

        if (proxyServer != null) {
            try {
                proxyServer.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping proxy server", e);
            }
            proxyServer = null;
        }

        isHotspotStarted = false;
        ssid = "";
        password = "";
        ipAddress = "";
        
        emitState();
    }

    private String findHotspotIpAddress() {
        try {
            java.util.ArrayList<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            
            // First pass: Prioritize known SoftAP or Wi-Fi Direct interface names (like "ap0", "p2p-wlan0-0", "wlan1")
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp() || intf.isPointToPoint()) {
                    continue;
                }
                String name = intf.getName().toLowerCase();
                if (name.contains("ap") || name.contains("p2p") || (name.contains("wlan") && !name.equals("wlan0"))) {
                    String ip = getIPv4Address(intf);
                    if (ip != null) {
                        return ip;
                    }
                }
            }
            
            // Second pass: Fallback to wlan0 or other wlan interfaces if that's all we have
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp() || intf.isPointToPoint()) {
                    continue;
                }
                String name = intf.getName().toLowerCase();
                if (name.contains("wlan")) {
                    String ip = getIPv4Address(intf);
                    if (ip != null) {
                        return ip;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resolving network interfaces", e);
        }
        return null;
    }

    private String getIPv4Address(NetworkInterface intf) {
        Enumeration<InetAddress> inetAddresses = intf.getInetAddresses();
        for (InetAddress inetAddress : Collections.list(inetAddresses)) {
            if (inetAddress.getAddress().length == 4) { // IPv4
                String ip = inetAddress.getHostAddress();
                if (ip != null && !ip.equals("127.0.0.1")) {
                    return ip;
                }
            }
        }
        return null;
    }

    private void tickService() {
        if (isHotspotStarted && proxyServer != null) {
            updateNotificationInfo();
            emitState();
        }
    }

    private void updateNotificationInfo() {
        String speedText = String.format("Download: %s | Upload: %s",
                formatSpeed(proxyServer.getRxSpeed()),
                formatSpeed(proxyServer.getTxSpeed()));
        String contentText = String.format("SSID: %s | IP: %s:%d",
                ssid, ipAddress, PROXY_PORT);

        Notification notification = buildNotification("Wi-Fi Extender Active", contentText + "\n" + speedText);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotificationError(String errorMsg) {
        Notification notification = buildNotification("Extender Error", errorMsg);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1048576) return String.format("%.1f KB/s", bytesPerSec / 1024.0);
        return String.format("%.2f MB/s", bytesPerSec / 1048576.0);
    }

    private Notification buildNotification(String title, String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // Use generic system icon
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Wi-Fi Extender Status Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    // React Native Communication Helpers
    private void emitState() {
        WritableMap params = Arguments.createMap();
        params.putBoolean("active", isHotspotStarted);
        params.putString("ssid", ssid);
        params.putString("password", password);
        params.putString("ipAddress", ipAddress);
        params.putInt("port", PROXY_PORT);
        
        if (proxyServer != null) {
            params.putDouble("rxSpeed", (double) proxyServer.getRxSpeed());
            params.putDouble("txSpeed", (double) proxyServer.getTxSpeed());
            params.putDouble("totalRxBytes", (double) proxyServer.getTotalRxBytes());
            params.putDouble("totalTxBytes", (double) proxyServer.getTotalTxBytes());
            params.putString("clientsJson", proxyServer.getClientsJson());
        } else {
            params.putDouble("rxSpeed", 0);
            params.putDouble("txSpeed", 0);
            params.putDouble("totalRxBytes", 0);
            params.putDouble("totalTxBytes", 0);
            params.putString("clientsJson", "[]");
        }

        emitEvent("onExtenderStateChange", params);
    }

    private void emitEvent(String eventName, Object data) {
        if (reactContext == null) return;
        
        try {
            if (data instanceof WritableMap) {
                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                        .emit(eventName, data);
            } else if (data instanceof String) {
                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                        .emit(eventName, data);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to emit event: " + eventName, e);
        }
    }

    // Getters for Module
    public boolean isActive() { return isHotspotStarted; }
    public String getSsid() { return ssid; }
    public String getPassword() { return password; }
    public String getIpAddress() { return ipAddress; }
    public int getPort() { return PROXY_PORT; }
    public ProxyServer getProxyServer() { return proxyServer; }
}
