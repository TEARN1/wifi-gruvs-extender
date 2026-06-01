import React, { useState, useEffect, useRef } from 'react';
import {
  StyleSheet,
  Text,
  View,
  TouchableOpacity,
  ScrollView,
  StatusBar,
  Animated,
  Platform,
  PermissionsAndroid,
  Alert,
  NativeModules,
  NativeEventEmitter,
  ActivityIndicator,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
// @ts-ignore
import qrcode from 'qrcode-generator';

const { WifiRepeater } = NativeModules;
const WifiRepeaterEmitter = WifiRepeater ? new NativeEventEmitter(WifiRepeater) : null;

interface ClientDevice {
  ip: string;
  totalBytes: number;
  lastSeen: number;
}

interface WiFiQRCodeProps {
  value: string;
  size?: number;
}

function WiFiQRCode({ value, size = 160 }: WiFiQRCodeProps) {
  try {
    const qr = qrcode(0, 'M');
    qr.addData(value);
    qr.make();
    
    const cells = qr.getModuleCount();
    const cellSize = Math.floor(size / cells);
    const actualSize = cellSize * cells;
    
    const rows = [];
    for (let r = 0; r < cells; r++) {
      const cols = [];
      for (let c = 0; c < cells; c++) {
        const isDark = qr.isDark(r, c);
        cols.push(
          <View
            key={`c-${c}`}
            style={{
              width: cellSize,
              height: cellSize,
              backgroundColor: isDark ? '#ffffff' : '#070a13',
            }}
          />
        );
      }
      rows.push(
        <View key={`r-${r}`} style={{ flexDirection: 'row' }}>
          {cols}
        </View>
      );
    }
    
    return (
      <View style={{
        padding: 10,
        backgroundColor: '#070a13',
        borderRadius: 8,
        alignItems: 'center',
        justifyContent: 'center',
        width: actualSize + 20,
        height: actualSize + 20,
        borderWidth: 1,
        borderColor: '#1e293b',
      }}>
        {rows}
      </View>
    );
  } catch (e) {
    console.error('Failed to generate QR code', e);
    return (
      <View style={{ width: size, height: size, backgroundColor: '#070a13', justifyContent: 'center', alignItems: 'center' }}>
        <Text style={{ color: '#64748b', fontSize: 12 }}>QR Code Error</Text>
      </View>
    );
  }
}

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" backgroundColor="#070a13" />
      <SafeAreaView style={styles.safeArea}>
        <MainContainer />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

function MainContainer() {
  const [active, setActive] = useState(false);
  const [loading, setLoading] = useState(false);
  
  // Connection info
  const [ssid, setSsid] = useState('');
  const [password, setPassword] = useState('');
  const [ipAddress, setIpAddress] = useState('');
  const [port, setPort] = useState(8282);

  // Speed and volume metrics
  const [rxSpeed, setRxSpeed] = useState(0);
  const [txSpeed, setTxSpeed] = useState(0);
  const [totalRx, setTotalRx] = useState(0);
  const [totalTx, setTotalTx] = useState(0);

  // Client registry
  const [clients, setClients] = useState<ClientDevice[]>([]);

  // Ripple animations for active state
  const scale1 = useRef(new Animated.Value(1)).current;
  const opacity1 = useRef(new Animated.Value(1)).current;
  const scale2 = useRef(new Animated.Value(1)).current;
  const opacity2 = useRef(new Animated.Value(1)).current;

  // Poll state as fallback or for regular sync
  useEffect(() => {
    let syncInterval: ReturnType<typeof setInterval>;
    
    const fetchState = async () => {
      if (!WifiRepeater) return;
      try {
        const state = await WifiRepeater.getExtenderState();
        updateUIState(state);
      } catch (err) {
        console.error('Failed to get extender state:', err);
      }
    };

    fetchState();

    // Setup event listeners
    let stateSubscription: any;
    let errorSubscription: any;

    if (WifiRepeaterEmitter) {
      stateSubscription = WifiRepeaterEmitter.addListener(
        'onExtenderStateChange',
        (state: any) => {
          updateUIState(state);
        }
      );

      errorSubscription = WifiRepeaterEmitter.addListener(
        'onExtenderError',
        (error: string) => {
          setLoading(false);
          Alert.alert('Extender Error', error);
        }
      );
    }

    // Backup polling every 2 seconds for fresh speeds
    syncInterval = setInterval(fetchState, 2000);

    return () => {
      if (stateSubscription) stateSubscription.remove();
      if (errorSubscription) errorSubscription.remove();
      if (syncInterval) clearInterval(syncInterval);
    };
  }, []);

  const updateUIState = (state: any) => {
    setActive(state.active);
    setSsid(state.ssid || '');
    setPassword(state.password || '');
    setIpAddress(state.ipAddress || '');
    setPort(state.port || 8282);
    setRxSpeed(state.rxSpeed || 0);
    setTxSpeed(state.txSpeed || 0);
    setTotalRx(state.totalRxBytes || 0);
    setTotalTx(state.totalTxBytes || 0);
    
    if (state.active) {
      setLoading(false);
    }

    try {
      if (state.clientsJson) {
        const list = JSON.parse(state.clientsJson);
        setClients(list);
      } else {
        setClients([]);
      }
    } catch (e) {
      setClients([]);
    }
  };

  // Pulse animation controller
  useEffect(() => {
    if (active) {
      const startPulse = () => {
        scale1.setValue(1);
        opacity1.setValue(0.6);
        scale2.setValue(1);
        opacity2.setValue(0.6);

        Animated.parallel([
          Animated.loop(
            Animated.parallel([
              Animated.timing(scale1, {
                toValue: 2.8,
                duration: 2500,
                useNativeDriver: true,
              }),
              Animated.timing(opacity1, {
                toValue: 0,
                duration: 2500,
                useNativeDriver: true,
              }),
            ])
          ),
          Animated.loop(
            Animated.sequence([
              Animated.delay(1250),
              Animated.parallel([
                Animated.timing(scale2, {
                  toValue: 2.8,
                  duration: 2500,
                  useNativeDriver: true,
                }),
                Animated.timing(opacity2, {
                  toValue: 0,
                  duration: 2500,
                  useNativeDriver: true,
                }),
              ]),
            ])
          ),
        ]).start();
      };
      
      startPulse();
    } else {
      scale1.setValue(1);
      opacity1.setValue(0);
      scale2.setValue(1);
      opacity2.setValue(0);
    }
  }, [active]);

  const requestPermissions = async () => {
    if (Platform.OS === 'android') {
      try {
        const locationGranted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
          {
            title: 'Location Permission Required',
            message: 'Wi-Fi Extender requires Location access to configure and start the Local Hotspot.',
            buttonNeutral: 'Ask Later',
            buttonNegative: 'Cancel',
            buttonPositive: 'Grant',
          }
        );
        
        let nearbyGranted = true;
        let notificationsGranted = true;

        const apiLevel = typeof Platform.Version === 'string' ? parseInt(Platform.Version, 10) : Platform.Version;

        if (apiLevel >= 33) {
          const nearbyPermission = PermissionsAndroid.PERMISSIONS.NEARBY_WIFI_DEVICES || 'android.permission.NEARBY_WIFI_DEVICES' as any;
          const nearbyResult = await PermissionsAndroid.request(
            nearbyPermission,
            {
              title: 'Nearby Devices Permission Required',
              message: 'Wi-Fi Extender requires permission to scan for and connect to nearby Wi-Fi devices to set up the local hotspot.',
              buttonNeutral: 'Ask Later',
              buttonNegative: 'Cancel',
              buttonPositive: 'Grant',
            }
          );
          nearbyGranted = nearbyResult === PermissionsAndroid.RESULTS.GRANTED;

          const notificationsPermission = PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS || 'android.permission.POST_NOTIFICATIONS' as any;
          const notificationsResult = await PermissionsAndroid.request(
            notificationsPermission,
            {
              title: 'Notification Permission Required',
              message: 'Wi-Fi Extender requires permission to display a persistent background service notification.',
              buttonNeutral: 'Ask Later',
              buttonNegative: 'Cancel',
              buttonPositive: 'Grant',
            }
          );
          notificationsGranted = notificationsResult === PermissionsAndroid.RESULTS.GRANTED;
        }

        return locationGranted === PermissionsAndroid.RESULTS.GRANTED && nearbyGranted && notificationsGranted;
      } catch (err) {
        console.warn(err);
        return false;
      }
    }
    return true;
  };

  const openTetherSettings = () => {
    if (WifiRepeater && WifiRepeater.openTetherSettings) {
      WifiRepeater.openTetherSettings();
    } else {
      Alert.alert('Unavailable', 'Settings shortcut is not supported on this platform.');
    }
  };

  const toggleExtender = async () => {
    if (!WifiRepeater) {
      Alert.alert('Unavailable', 'Wi-Fi Repeater module is not available on this platform.');
      return;
    }
    if (active) {
      setLoading(true);
      WifiRepeater.stopExtender();
      // Graceful timeout for loading state
      setTimeout(() => {
        setLoading(false);
        setActive(false);
      }, 1500);
    } else {
      setLoading(true);
      const hasPermission = await requestPermissions();
      if (hasPermission) {
        WifiRepeater.startExtender();
      } else {
        setLoading(false);
        Alert.alert('Permission Required', 'Permissions are necessary to start the hotspot.');
      }
    }
  };

  const handleCopy = (text: string, label: string) => {
    if (WifiRepeater && WifiRepeater.copyToClipboard) {
      WifiRepeater.copyToClipboard(text);
    }
    // Simple custom notice since ToastAndroid is lightweight
    if (Platform.OS === 'android') {
      ToastAndroid.show(`${label} copied to clipboard`, ToastAndroid.SHORT);
    } else {
      Alert.alert('Copied', `${label} copied to clipboard`);
    }
  };

  const formatBytes = (bytes: number) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  };

  const formatSpeed = (bytesPerSec: number) => {
    if (bytesPerSec === 0) return '0 KB/s';
    const k = 1024;
    if (bytesPerSec < k) return bytesPerSec + ' B/s';
    if (bytesPerSec < k * k) return (bytesPerSec / k).toFixed(1) + ' KB/s';
    return (bytesPerSec / (k * k)).toFixed(1) + ' MB/s';
  };

  return (
    <View style={styles.appContainer}>
      <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.headerTitle}>WiFi Gruvs</Text>
          <Text style={styles.headerSubtitle}>Wi-Fi Repeater & Extender</Text>
        </View>

        {/* Pulse Radar Status */}
        <View style={styles.radarContainer}>
          {active && (
            <>
              <Animated.View
                style={[
                  styles.radarRing,
                  {
                    transform: [{ scale: scale1 }],
                    opacity: opacity1,
                  },
                ]}
              />
              <Animated.View
                style={[
                  styles.radarRing,
                  {
                    transform: [{ scale: scale2 }],
                    opacity: opacity2,
                  },
                ]}
              />
            </>
          )}

          {/* Central Button */}
          <TouchableOpacity
            activeOpacity={0.8}
            onPress={toggleExtender}
            disabled={loading}
            style={[
              styles.powerButton,
              active ? styles.powerButtonActive : styles.powerButtonInactive,
            ]}>
            {loading ? (
              <ActivityIndicator size="large" color="#ffffff" />
            ) : (
              <View style={styles.innerPowerContainer}>
                <Text style={styles.powerIcon}>⚡</Text>
                <Text style={styles.powerLabel}>{active ? 'ACTIVE' : 'OFFLINE'}</Text>
              </View>
            )}
          </TouchableOpacity>
        </View>

        <Text style={styles.statusDescription}>
          {active 
            ? 'Repeater is running. Devices can connect to your sharing network.' 
            : 'Tap the shield button above to start Wi-Fi sharing.'
          }
        </Text>

        {/* Speedometer Gauges */}
        {active && (
          <View style={styles.gaugesRow}>
            <View style={styles.gaugeCard}>
              <Text style={styles.gaugeEmoji}>📥</Text>
              <Text style={styles.gaugeVal}>{formatSpeed(rxSpeed)}</Text>
              <Text style={styles.gaugeLabel}>DOWNLOAD SPEED</Text>
              <Text style={styles.gaugeTotal}>Total: {formatBytes(totalRx)}</Text>
            </View>

            <View style={styles.gaugeCard}>
              <Text style={styles.gaugeEmoji}>📤</Text>
              <Text style={styles.gaugeVal}>{formatSpeed(txSpeed)}</Text>
              <Text style={styles.gaugeLabel}>UPLOAD SPEED</Text>
              <Text style={styles.gaugeTotal}>Total: {formatBytes(totalTx)}</Text>
            </View>
          </View>
        )}

        {/* Hotspot details Card */}
        {active && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Hotspot Access Credentials</Text>
            <Text style={styles.cardInfo}>Connect your clients to this Wi-Fi network:</Text>

            <View style={styles.detailRow}>
              <View>
                <Text style={styles.detailLabel}>SSID (Network Name)</Text>
                <Text style={styles.detailValue}>{ssid || 'Fetching...'}</Text>
              </View>
              <TouchableOpacity 
                style={styles.copyBtn} 
                onPress={() => handleCopy(ssid, 'SSID')}
              >
                <Text style={styles.copyBtnText}>Copy</Text>
              </TouchableOpacity>
            </View>

            <View style={styles.divider} />

            <View style={styles.detailRow}>
              <View>
                <Text style={styles.detailLabel}>Wi-Fi Password</Text>
                <Text style={styles.detailValue}>{password || 'Fetching...'}</Text>
              </View>
              <TouchableOpacity 
                style={styles.copyBtn} 
                onPress={() => handleCopy(password, 'Password')}
              >
                <Text style={styles.copyBtnText}>Copy</Text>
              </TouchableOpacity>
            </View>

            <View style={styles.divider} />

            <View style={styles.qrSection}>
              <Text style={styles.qrTitle}>Scan to Connect Instantly</Text>
              {ssid && password ? (
                <WiFiQRCode value={`WIFI:S:${ssid.startsWith('"') && ssid.endsWith('"') ? ssid.slice(1, -1) : ssid};T:WPA;P:${password};;`} size={160} />
              ) : (
                <ActivityIndicator size="small" color="#6366f1" />
              )}
            </View>
          </View>
        )}

        {/* Instructions Card */}
        {active && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Manual Proxy Configuration</Text>
            <Text style={styles.cardWarning}>
              🚨 Connection requires setting the proxy on your connecting devices so they can access the Internet.
            </Text>

            <View style={styles.stepsContainer}>
              <Text style={styles.stepItem}>1. Connect your device to the Wi-Fi network shown above.</Text>
              <Text style={styles.stepItem}>2. Open the Wi-Fi settings for that connection on the device.</Text>
              <Text style={styles.stepItem}>3. Set the Proxy option to <Text style={styles.boldText}>Manual</Text>.</Text>
              <Text style={styles.stepItem}>4. Enter these proxy details:</Text>
            </View>

            <View style={styles.proxySpecsRow}>
              <View style={styles.proxySpecsCard}>
                <Text style={styles.proxySpecsLabel}>Proxy Host Name (IP)</Text>
                <Text style={styles.proxySpecsValue}>{ipAddress}</Text>
                <TouchableOpacity 
                  style={styles.copyBtnMini} 
                  onPress={() => handleCopy(ipAddress, 'Proxy Host IP')}
                >
                  <Text style={styles.copyBtnTextMini}>Copy IP</Text>
                </TouchableOpacity>
              </View>

              <View style={styles.proxySpecsCard}>
                <Text style={styles.proxySpecsLabel}>Proxy Port</Text>
                <Text style={styles.proxySpecsValue}>{port}</Text>
                <TouchableOpacity 
                  style={styles.copyBtnMini} 
                  onPress={() => handleCopy(port.toString(), 'Proxy Port')}
                >
                  <Text style={styles.copyBtnTextMini}>Copy Port</Text>
                </TouchableOpacity>
              </View>
            </View>

            <View style={styles.divider} />

            <View style={styles.appleSection}>
              <Text style={styles.appleTitle}>🍏 Apple (iOS / macOS) Auto-Setup</Text>
              <Text style={styles.appleText}>
                Connect to the Wi-Fi first, then scan this QR code to download the Wi-Fi profile and connect automatically:
              </Text>
              {ipAddress ? (
                <WiFiQRCode value={`http://${ipAddress}:${port}/setup`} size={150} />
              ) : (
                <ActivityIndicator size="small" color="#6366f1" />
              )}
            </View>
          </View>
        )}

        {/* Connected devices Card */}
        {active && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Connected Client Devices ({clients.length})</Text>
            {clients.length === 0 ? (
              <Text style={styles.noClientsText}>No devices routing traffic yet. Configure client proxy to connect.</Text>
            ) : (
              <View style={styles.clientsList}>
                {clients.map((client) => {
                  const elapsedSeconds = Math.max(0, Math.floor((Date.now() - client.lastSeen) / 1000));
                  const timeText = elapsedSeconds < 5 ? 'Just now' : `${elapsedSeconds}s ago`;
                  return (
                    <View key={client.ip} style={styles.clientItem}>
                      <View style={styles.clientIconCol}>
                        <Text style={styles.clientIcon}>💻</Text>
                      </View>
                      <View style={styles.clientDetailsCol}>
                        <Text style={styles.clientIp}>{client.ip}</Text>
                        <Text style={styles.clientMeta}>Shared: {formatBytes(client.totalBytes)}</Text>
                      </View>
                      <View style={styles.clientActiveCol}>
                        <View style={styles.activeIndicator} />
                        <Text style={styles.clientTime}>{timeText}</Text>
                      </View>
                    </View>
                  );
                })}
              </View>
            )}
          </View>
        )}

        {/* Device Information (Android requirement details) */}
        {!active && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Option A: Zero-Config Wi-Fi Sharing</Text>
            <Text style={styles.cardInfo}>
              Many modern devices allow you to share your active Wi-Fi connection automatically via the system hotspot. Connected clients will get internet immediately without any proxy configuration!
            </Text>
            <TouchableOpacity
              activeOpacity={0.8}
              onPress={openTetherSettings}
              style={styles.actionButton}>
              <Text style={styles.actionButtonText}>⚙️ Open System Hotspot Settings</Text>
            </TouchableOpacity>
          </View>
        )}

        {!active && (
          <View style={styles.infoCard}>
            <Text style={styles.infoCardTitle}>Option B: Local Hotspot & Proxy (Universal)</Text>
            <Text style={styles.infoCardText}>
              If your phone disables Wi-Fi when activating system hotspot, tap the lightning ⚡ button above. This starts a rootless Wi-Fi repeater utilizing a local HTTP proxy server.
            </Text>
            <View style={styles.infoSpecs}>
              <Text style={styles.infoSpecItem}>• Requires setting proxy on client (e.g. via QR code)</Text>
              <Text style={styles.infoSpecItem}>• Safe, stable and runs fully rootless</Text>
            </View>
          </View>
        )}
      </ScrollView>
    </View>
  );
}

// Light Weight ToastAndroid placeholder
const ToastAndroid = {
  show: (msg: string, duration: number) => {
    if (Platform.OS === 'android') {
      NativeModules.ToastAndroid ? NativeModules.ToastAndroid.show(msg, duration) : console.log(msg);
    }
  },
  SHORT: 0,
};

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#070a13',
  },
  appContainer: {
    flex: 1,
    backgroundColor: '#070a13',
  },
  scrollContent: {
    padding: 20,
    paddingBottom: 40,
  },
  header: {
    alignItems: 'center',
    marginVertical: 15,
  },
  headerTitle: {
    fontSize: 28,
    fontWeight: '800',
    color: '#ffffff',
    letterSpacing: 1,
  },
  headerSubtitle: {
    fontSize: 14,
    color: '#6366f1',
    fontWeight: '500',
    marginTop: 4,
  },
  radarContainer: {
    height: 220,
    justifyContent: 'center',
    alignItems: 'center',
    marginVertical: 10,
    position: 'relative',
  },
  radarRing: {
    position: 'absolute',
    width: 120,
    height: 120,
    borderRadius: 60,
    borderWidth: 2,
    borderColor: '#4f46e5',
    backgroundColor: 'rgba(79, 70, 229, 0.1)',
  },
  powerButton: {
    width: 120,
    height: 120,
    borderRadius: 60,
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 10,
    shadowColor: '#6366f1',
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.5,
    shadowRadius: 15,
    elevation: 12,
  },
  powerButtonActive: {
    backgroundColor: '#10b981',
    shadowColor: '#10b981',
  },
  powerButtonInactive: {
    backgroundColor: '#4f46e5',
    shadowColor: '#4f46e5',
  },
  innerPowerContainer: {
    alignItems: 'center',
  },
  powerIcon: {
    fontSize: 32,
    color: '#ffffff',
  },
  powerLabel: {
    color: '#ffffff',
    fontSize: 12,
    fontWeight: '800',
    marginTop: 6,
    letterSpacing: 1.2,
  },
  statusDescription: {
    color: '#94a3b8',
    textAlign: 'center',
    fontSize: 14,
    lineHeight: 20,
    paddingHorizontal: 20,
    marginBottom: 20,
  },
  gaugesRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 20,
  },
  gaugeCard: {
    width: '48%',
    backgroundColor: '#131b2e',
    borderRadius: 16,
    padding: 15,
    borderWidth: 1,
    borderColor: '#1e293b',
    alignItems: 'center',
  },
  gaugeEmoji: {
    fontSize: 20,
    marginBottom: 6,
  },
  gaugeVal: {
    fontSize: 18,
    fontWeight: '700',
    color: '#ffffff',
  },
  gaugeLabel: {
    fontSize: 10,
    color: '#64748b',
    fontWeight: '700',
    marginVertical: 4,
    letterSpacing: 0.5,
  },
  gaugeTotal: {
    fontSize: 11,
    color: '#94a3b8',
    marginTop: 2,
  },
  card: {
    backgroundColor: '#131b2e',
    borderRadius: 18,
    padding: 20,
    marginBottom: 20,
    borderWidth: 1,
    borderColor: '#1e293b',
  },
  cardTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: '#ffffff',
    marginBottom: 10,
  },
  cardInfo: {
    fontSize: 13,
    color: '#94a3b8',
    marginBottom: 15,
  },
  cardWarning: {
    fontSize: 13,
    color: '#fbbf24',
    lineHeight: 18,
    backgroundColor: 'rgba(251, 191, 36, 0.1)',
    padding: 10,
    borderRadius: 8,
    borderWidth: 0.5,
    borderColor: 'rgba(251, 191, 36, 0.2)',
    marginBottom: 15,
  },
  detailRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 10,
  },
  detailLabel: {
    fontSize: 11,
    color: '#64748b',
    fontWeight: '600',
    textTransform: 'uppercase',
  },
  detailValue: {
    fontSize: 16,
    color: '#ffffff',
    fontWeight: '700',
    marginTop: 2,
  },
  divider: {
    height: 1,
    backgroundColor: '#1e293b',
    marginVertical: 4,
  },
  copyBtn: {
    backgroundColor: '#1e293b',
    paddingVertical: 8,
    paddingHorizontal: 16,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#334155',
  },
  copyBtnText: {
    color: '#ffffff',
    fontSize: 13,
    fontWeight: '600',
  },
  stepsContainer: {
    marginBottom: 15,
  },
  stepItem: {
    fontSize: 13,
    color: '#94a3b8',
    marginVertical: 4,
    lineHeight: 18,
  },
  boldText: {
    fontWeight: '700',
    color: '#6366f1',
  },
  proxySpecsRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  proxySpecsCard: {
    width: '48%',
    backgroundColor: '#0c111d',
    borderRadius: 12,
    padding: 12,
    borderWidth: 1,
    borderColor: '#1e293b',
    alignItems: 'center',
  },
  proxySpecsLabel: {
    fontSize: 10,
    color: '#64748b',
    fontWeight: '700',
  },
  proxySpecsValue: {
    fontSize: 15,
    fontWeight: '800',
    color: '#ffffff',
    marginVertical: 6,
  },
  copyBtnMini: {
    backgroundColor: '#1e293b',
    paddingVertical: 4,
    paddingHorizontal: 10,
    borderRadius: 6,
  },
  copyBtnTextMini: {
    color: '#6366f1',
    fontSize: 11,
    fontWeight: '700',
  },
  noClientsText: {
    fontSize: 13,
    color: '#64748b',
    textAlign: 'center',
    paddingVertical: 15,
    lineHeight: 18,
  },
  clientsList: {
    marginTop: 5,
  },
  clientItem: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#0c111d',
    borderRadius: 12,
    padding: 12,
    marginBottom: 10,
    borderWidth: 1,
    borderColor: '#1e293b',
  },
  clientIconCol: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: 'rgba(99, 102, 241, 0.1)',
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  clientIcon: {
    fontSize: 18,
  },
  clientDetailsCol: {
    flex: 1,
  },
  clientIp: {
    fontSize: 14,
    fontWeight: '700',
    color: '#ffffff',
  },
  clientMeta: {
    fontSize: 12,
    color: '#64748b',
    marginTop: 2,
  },
  clientActiveCol: {
    alignItems: 'flex-end',
  },
  activeIndicator: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: '#10b981',
    marginBottom: 4,
  },
  clientTime: {
    fontSize: 10,
    color: '#64748b',
  },
  infoCard: {
    backgroundColor: '#0c111d',
    borderRadius: 18,
    padding: 20,
    borderWidth: 1,
    borderColor: '#131b2e',
  },
  infoCardTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: '#ffffff',
    marginBottom: 8,
  },
  infoCardText: {
    fontSize: 13,
    color: '#94a3b8',
    lineHeight: 18,
    marginBottom: 15,
  },
  infoSpecs: {
    gap: 6,
  },
  infoSpecItem: {
    fontSize: 12,
    color: '#6366f1',
    fontWeight: '600',
  },
  qrSection: {
    alignItems: 'center',
    marginTop: 15,
  },
  qrTitle: {
    fontSize: 13,
    color: '#94a3b8',
    fontWeight: '700',
    marginBottom: 10,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  appleSection: {
    alignItems: 'center',
    marginTop: 15,
  },
  appleTitle: {
    fontSize: 14,
    color: '#ffffff',
    fontWeight: '700',
    marginBottom: 6,
  },
  appleText: {
    fontSize: 12,
    color: '#94a3b8',
    textAlign: 'center',
    marginBottom: 12,
    lineHeight: 16,
    paddingHorizontal: 10,
  },
  actionButton: {
    backgroundColor: '#6366f1',
    paddingVertical: 12,
    borderRadius: 8,
    alignItems: 'center',
    marginTop: 12,
  },
  actionButtonText: {
    color: '#ffffff',
    fontSize: 14,
    fontWeight: '700',
  },
});
