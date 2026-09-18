package com.impactwiring.iwsconnectpoc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.NetworkRequest;
import android.os.ParcelFileDescriptor;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.netbird.gomobile.android.Android;
import io.netbird.gomobile.android.Auth;
import io.netbird.gomobile.android.Client;
import io.netbird.gomobile.android.ConnectionListener;
import io.netbird.gomobile.android.DNSList;
import io.netbird.gomobile.android.EnvList;
import io.netbird.gomobile.android.ErrListener;
import io.netbird.gomobile.android.Preferences;

public final class IwsVpnService extends android.net.VpnService {
    public static final String ACTION_CONNECT =
            "com.impactwiring.iwsconnectpoc.action.CONNECT";
    public static final String ACTION_RETRY =
            "com.impactwiring.iwsconnectpoc.action.RETRY";
    public static final String ACTION_DISCONNECT =
            "com.impactwiring.iwsconnectpoc.action.DISCONNECT";

    public interface Observer {
        void onTransportState(TransportState state);
        void onEndpointState(String state);
        void onMessage(String message);
    }

    public interface EnrollmentCallback {
        void onSuccess();
        void onError();
    }

    private static final int NOTIFICATION_ID = 7101;
    private static final String CHANNEL_ID = "iws_transport";

    private final LocalBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService stopExecutor = Executors.newSingleThreadExecutor();
    private final EngineRecovery recovery = new EngineRecovery();
    private final Object networkLock = new Object();
    private final Set<Network> lostNetworks = new HashSet<>();
    private volatile Network physicalNetwork;
    private List<String> physicalDns = new ArrayList<>();
    private ConnectivityManager connectivity;
    private boolean networkCallbackRegistered;
    private volatile boolean destroyed;
    private final Runnable recoveryDeadline = () -> {
        synchronized (IwsVpnService.this) {
            if (recovery.timeout()) {
                emitTransport(TransportState.ERROR);
                emitMessage("IWS recovery is waiting for connectivity to stop. Please retry shortly.");
            }
        }
    };
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();

    private volatile Observer observer;
    private volatile TransportState transportState = TransportState.DISCONNECTED;
    private volatile String endpointState = "UNKNOWN";
    private volatile Client client;
    private volatile Client preparedClient;
    private volatile boolean stopping;

    private AndroidPlatformFiles platformFiles;

    public final class LocalBinder extends Binder {
        IwsVpnService service() {
            return IwsVpnService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        platformFiles = new AndroidPlatformFiles(this);
        createNotificationChannel();
        connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity != null) {
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), physicalNetworkCallback);
            networkCallbackRegistered = true;
        }
        refreshPhysicalNetwork(null, false, false);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CONNECT.equals(action)) {
            startForegroundForState(recovery.isRunning() ? transportState : TransportState.CONNECTING);
            connect();
        } else if (ACTION_RETRY.equals(action)) {
            startForegroundForState(TransportState.CONNECTING);
            retry();
        } else if (ACTION_DISCONNECT.equals(action)) {
            disconnect();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        disconnect();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (networkCallbackRegistered) {
            connectivity.unregisterNetworkCallback(physicalNetworkCallback);
            networkCallbackRegistered = false;
        }
        disconnect();
        engineExecutor.shutdown();
        stopExecutor.shutdown();
        probeExecutor.shutdownNow();
        super.onDestroy();
    }

    android.net.VpnService.Builder newBuilder() {
        return new Builder();
    }

    void setObserver(Observer observer) {
        this.observer = observer;
        if (observer != null) {
            observer.onTransportState(transportState);
            observer.onEndpointState(endpointState);
        }
    }

    TransportState transportState() {
        return transportState;
    }

    synchronized void connect() {
        if (destroyed || !recovery.start()) {
            return;
        }
        stopping = false;
        endpointState = "UNKNOWN";
        emitEndpoint();
        emitTransport(TransportState.CONNECTING);

        engineExecutor.submit(() -> {
            try {
                if (stopping) return;
                initializeTransportDns();
                configureFailClosedPreferences();
                EnvList environment = Android.newEnvList();
                environment.put(Android.getEnvKeyNBForceRelay(), "true");

                Client nativeClient = takePreparedClient();
                if (nativeClient == null) {
                    nativeClient = newNativeClient();
                }
                synchronized (IwsVpnService.this) {
                    if (stopping) return;
                    client = nativeClient;
                }
                nativeClient.setConnectionListener(connectionListener);
                DNSList hostDns = initializeTransportDns();
                nativeClient.setNetworkAvailable(physicalNetwork != null);
                nativeClient.runWithoutLogin(
                        platformFiles,
                        hostDns,
                        () -> {},
                        environment);
                if (!stopping && transportState != TransportState.DISCONNECTED) {
                    emitTransport(TransportState.ERROR);
                }
            } catch (Exception error) {
                if (!stopping) {
                    emitTransport(TransportState.ERROR);
                    emitMessage("IWS could not connect. Please retry.");
                }
            } finally {
                Client finished = client;
                if (finished != null) {
                    finished.removeConnectionListener();
                }
                synchronized (IwsVpnService.this) {
                    client = null;
                    mainHandler.removeCallbacks(recoveryDeadline);
                    boolean restart = recovery.finished();
                    if (restart && !destroyed) connect();
                    else if (stopping) emitTransport(TransportState.DISCONNECTED);
                }
            }
        });
    }

    synchronized void retry() {
        if (destroyed) return;
        if (!recovery.isRunning()) {
            connect();
            return;
        }
        if (!recovery.retry()) return;
        stopping = true;
        emitTransport(TransportState.CONNECTING);
        mainHandler.postDelayed(recoveryDeadline, 15000);
        requestNativeStop(client);
    }

    private void requestNativeStop(Client nativeClient) {
        if (nativeClient != null) stopExecutor.execute(nativeClient::stop);
    }

    synchronized void disconnect() {
        recovery.cancel();
        mainHandler.removeCallbacks(recoveryDeadline);
        stopping = true;
        Client nativeClient = client;
        if (nativeClient == null) {
            emitTransport(TransportState.DISCONNECTED);
            stopForeground(STOP_FOREGROUND_REMOVE);
            return;
        }
        emitTransport(TransportState.DISCONNECTING);
        requestNativeStop(nativeClient);
        emitTransport(TransportState.DISCONNECTED);
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    boolean hasPeerIdentity() {
        java.io.File config = new java.io.File(platformFiles.configurationFilePath());
        return config.isFile() && config.length() > 0;
    }

    void enroll(String managementUrl, String setupKey, String hostname, EnrollmentCallback callback) {
        try {
            initializeTransportDns();
            configureFailClosedPreferences();
            EnrollmentOrder.protectThenAuthenticate(
                    this::prepareNativeClientForProtectedSockets,
                    () -> {
                        Auth auth = Android.newAuth(
                                platformFiles.configurationFilePath(), managementUrl);
                        auth.loginWithSetupKeyAndSaveConfig(new ErrListener() {
                            @Override
                            public void onSuccess() {
                                mainHandler.post(() -> {
                                    emitMessage("IWS device setup completed.");
                                    callback.onSuccess();
                                });
                            }

                            @Override
                            public void onError(Exception ignored) {
                                mainHandler.post(() -> {
                                    emitMessage("IWS device setup failed.");
                                    callback.onError();
                                });
                            }
                        }, setupKey, hostname);
                    });
        } catch (Exception ignored) {
            mainHandler.post(() -> {
                emitMessage("IWS device setup failed.");
                callback.onError();
            });
        }
    }

    private Client newNativeClient() {
        return Android.newClient(
                Build.VERSION.SDK_INT,
                Build.MODEL,
                BuildConfig.VERSION_NAME,
                new NativeTunAdapter(this),
                new EmptyInterfaceDiscover(),
                new NativeNetworkChangeListener());
    }

    private synchronized void prepareNativeClientForProtectedSockets() {
        if (client == null && preparedClient == null) {
            preparedClient = newNativeClient();
        }
    }

    private synchronized Client takePreparedClient() {
        Client nativeClient = preparedClient;
        preparedClient = null;
        return nativeClient;
    }

    String validatedEndpoint() {
        if (BuildConfig.TEST_ENDPOINT_URL.isEmpty()) {
            return null;
        }
        RoutePolicy policy = new RoutePolicy(
                BuildConfig.EXPECTED_OVERLAY_CIDR,
                BuildConfig.PROHIBITED_CIDRS,
                BuildConfig.ALLOWED_ENDPOINT_IPV4,
                BuildConfig.ALLOWED_ENDPOINT_PORT);
        return policy.requireAllowedEndpoint(BuildConfig.TEST_ENDPOINT_URL).toString();
    }

    private void configureFailClosedPreferences() throws Exception {
        Preferences preferences = Android.newPreferences(platformFiles.configurationFilePath());
        preferences.setBlockInbound(true);
        preferences.setDisableClientRoutes(true);
        preferences.setDisableServerRoutes(true);
        preferences.setDisableIPv6(true);
        preferences.setDisableDNS(true);
        preferences.setDisableFirewall(false);
        preferences.setServerSSHAllowed(false);
        preferences.setEnableSSHLocalPortForwarding(false);
        preferences.setEnableSSHRemotePortForwarding(false);
        preferences.setEnableSSHSFTP(false);
        preferences.commit();
    }

    private final ConnectivityManager.NetworkCallback physicalNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { refreshPhysicalNetwork(network, false, false); }
        @Override public void onLost(Network network) { refreshPhysicalNetwork(network, true, false); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            refreshPhysicalNetwork(null, false, false);
        }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            refreshPhysicalNetwork(null, false, network.equals(physicalNetwork));
        }
    };

    private List<String> ipv4Dns(Network network) {
        List<String> result = new ArrayList<>();
        LinkProperties properties = connectivity.getLinkProperties(network);
        if (properties != null) {
            for (InetAddress address : properties.getDnsServers()) {
                if (address instanceof Inet4Address) result.add(address.getHostAddress());
            }
        }
        return result;
    }

    private DNSList initializeTransportDns() throws Exception {
        synchronized (networkLock) {
            DNSList dns = new DNSList();
            for (String address : physicalDns) dns.add(address);
            Android.setTransportDNS(dns);
            return dns;
        }
    }

    private void refreshPhysicalNetwork(Network eventNetwork, boolean lost, boolean linkChanged) {
        synchronized (networkLock) {
            if (destroyed || connectivity == null) return;
            if (eventNetwork != null) {
                if (lost) lostNetworks.add(eventNetwork);
                else lostNetworks.remove(eventNetwork);
            }
            Network[] networks = connectivity.getAllNetworks();
            lostNetworks.retainAll(java.util.Arrays.asList(networks));
            List<PhysicalNetworkChoice.Candidate> candidates = new ArrayList<>();
            Integer current = null;
            for (int i = 0; i < networks.length; i++) {
                Network network = networks[i];
                if (network.equals(physicalNetwork)) current = i;
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                if (caps == null || lostNetworks.contains(network)) continue;
                candidates.add(new PhysicalNetworkChoice.Candidate(i,
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        !ipv4Dns(network).isEmpty(),
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)));
            }
            Integer selected = PhysicalNetworkChoice.choose(candidates, current);
            Network next = selected == null ? null : networks[selected];
            List<String> dns = next == null ? new ArrayList<>() : ipv4Dns(next);
            boolean changed = linkChanged || !java.util.Objects.equals(next, physicalNetwork) || !dns.equals(physicalDns);
            physicalNetwork = next;
            physicalDns = dns;
            try {
                initializeTransportDns();
                setUnderlyingNetworks(next == null ? new Network[0] : new Network[] {next});
                Client active = client;
                if (active != null) {
                    // The VPN DNS server is private and DNS management is disabled.
                    // Only the Go transport resolver consumes physical DNS updates.
                    active.setNetworkAvailable(next != null);
                    if (changed) active.notifyNetworkChange();
                }
            } catch (Exception error) {
                physicalNetwork = null;
                physicalDns = new ArrayList<>();
                try { Android.setTransportDNS(new DNSList()); } catch (Exception ignored) {}
                try { setUnderlyingNetworks(new Network[0]); } catch (Exception ignored) {}
                Client active = client;
                if (active != null) active.setNetworkAvailable(false);
                emitMessage("IWS is waiting for a usable physical network.");
            }
        }
    }

    boolean protectTransportSocket(int fd) {
        Network network = physicalNetwork;
        if (network == null || !protect(fd)) return false;
        // fromFd duplicates the descriptor; closing it must never close Go's original socket.
        try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.fromFd(fd)) {
            network.bindSocket(duplicate.getFileDescriptor());
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private final ConnectionListener connectionListener = new ConnectionListener() {
        @Override
        public void onStateChanged(long state) {
            emitNativeTransport(TransportStateMapper.fromNative(state));
        }

        @Override public void onConnected() {
            emitNativeTransport(TransportState.CONNECTED);
        }
        @Override public void onDisconnected() {
            emitNativeTransport(TransportState.DISCONNECTED);
        }
        @Override public void onConnecting() {
            emitNativeTransport(TransportState.CONNECTING);
        }
        @Override public void onDisconnecting() {
            emitNativeTransport(TransportState.DISCONNECTING);
        }
        @Override public void onAddressChanged(String ignoredV4, String ignoredV6) {}
        @Override public void onPeersListChanged(long ignoredCount) {}
    };

    private synchronized void emitNativeTransport(TransportState state) {
        if (!stopping && !destroyed) emitTransport(state);
    }

    private void emitTransport(TransportState state) {
        transportState = state;
        mainHandler.post(() -> {
            if (destroyed) return;
            updateNotification();
            Observer current = observer;
            if (current != null) {
                current.onTransportState(state);
            }
            if (state == TransportState.CONNECTED) {
                probeConfiguredEndpoint();
            } else if (state == TransportState.DISCONNECTED || state == TransportState.ERROR) {
                endpointState = "UNKNOWN";
                emitEndpoint();
            }
        });
    }

    private void probeConfiguredEndpoint() {
        final String endpoint;
        try {
            endpoint = validatedEndpoint();
        } catch (SecurityException error) {
            endpointState = "UNREACHABLE";
            emitEndpoint();
            return;
        }
        if (endpoint == null) {
            endpointState = "UNKNOWN";
            emitEndpoint();
            return;
        }
        probeExecutor.submit(() -> {
            boolean reachable = false;
            HttpURLConnection connection = null;
            try {
                URI uri = URI.create(endpoint);
                connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                int status = connection.getResponseCode();
                reachable = status >= 200 && status < 400;
            } catch (Exception ignored) {
                reachable = false;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            endpointState = reachable ? "REACHABLE" : "UNREACHABLE";
            emitEndpoint();
        });
    }

    private void emitEndpoint() {
        String state = endpointState;
        mainHandler.post(() -> {
            Observer current = observer;
            if (current != null) {
                current.onEndpointState(state);
            }
        });
    }

    private void emitMessage(String message) {
        mainHandler.post(() -> {
            Observer current = observer;
            if (current != null) {
                current.onMessage(message);
            }
        });
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "IWS private connectivity",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void startForegroundForState(TransportState state) {
        transportState = state;
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        if (transportState == TransportState.DISCONNECTED) {
            return;
        }
        startForegroundForState(transportState);
    }

    private Notification buildNotification() {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_iws_notification)
                .setContentTitle("IWS")
                .setContentText(IwsPresentation.notificationText(transportState))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
