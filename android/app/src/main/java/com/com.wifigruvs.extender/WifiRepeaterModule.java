package com.wifigruvs.extender;

import android.content.Intent;
import android.os.Build;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;

public class WifiRepeaterModule extends ReactContextBaseJavaModule {
    private final ReactApplicationContext reactContext;

    public WifiRepeaterModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        // Bind context to service so it can emit events
        WifiRepeaterService.setReactContext(reactContext);
    }

    @Override
    public String getName() {
        return "WifiRepeater";
    }

    @ReactMethod
    public void startExtender() {
        Intent intent = new Intent(reactContext, WifiRepeaterService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent);
        } else {
            reactContext.startService(intent);
        }
    }

    @ReactMethod
    public void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) 
            reactContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("WifiRepeater", text);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
        }
    }

    @ReactMethod
    public void stopExtender() {
        Intent intent = new Intent(reactContext, WifiRepeaterService.class);
        reactContext.stopService(intent);
    }

    @ReactMethod
    public void getExtenderState(Promise promise) {
        WifiRepeaterService service = WifiRepeaterService.getInstance();
        WritableMap params = Arguments.createMap();
        
        if (service != null && service.isActive()) {
            params.putBoolean("active", true);
            params.putString("ssid", service.getSsid());
            params.putString("password", service.getPassword());
            params.putString("ipAddress", service.getIpAddress());
            params.putInt("port", service.getPort());
            
            ProxyServer proxy = service.getProxyServer();
            if (proxy != null) {
                params.putDouble("rxSpeed", (double) proxy.getRxSpeed());
                params.putDouble("txSpeed", (double) proxy.getTxSpeed());
                params.putDouble("totalRxBytes", (double) proxy.getTotalRxBytes());
                params.putDouble("totalTxBytes", (double) proxy.getTotalTxBytes());
                params.putString("clientsJson", proxy.getClientsJson());
            } else {
                params.putDouble("rxSpeed", 0);
                params.putDouble("txSpeed", 0);
                params.putDouble("totalRxBytes", 0);
                params.putDouble("totalTxBytes", 0);
                params.putString("clientsJson", "[]");
            }
        } else {
            params.putBoolean("active", false);
            params.putString("ssid", "");
            params.putString("password", "");
            params.putString("ipAddress", "");
            params.putInt("port", 8282);
            params.putDouble("rxSpeed", 0);
            params.putDouble("txSpeed", 0);
            params.putDouble("totalRxBytes", 0);
            params.putDouble("totalTxBytes", 0);
            params.putString("clientsJson", "[]");
        }
        
        promise.resolve(params);
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Keep: Required for RN built-in Event Emitter Calls.
    }

    @ReactMethod
    public void removeListeners(Integer count) {
        // Keep: Required for RN built-in Event Emitter Calls.
    }
}
