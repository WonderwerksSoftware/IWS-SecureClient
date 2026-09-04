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
import java.util.concurrent.Future;
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
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();

    private volatile Observer observer;
    private volatile TransportState transportState = TransportState.DISCONNECTED;
    private volatile String endpointState = "UNKNOWN";
    private volatile Client client;
    private volatile Client preparedClient;
    private volatile Future<?> engineFuture;
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
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CONNECT.equals(action)) {
            startForegroundForState(TransportState.CONNECTING);
            connect();
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
        disconnect();
        engineExecutor.shutdownNow();
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
        if (engineFuture != null && !engineFuture.isDone()) {
            return;
        }
        stopping = false;
        endpointState = "UNKNOWN";
        emitEndpoint();
        emitTransport(TransportState.CONNECTING);

        engineFuture = engineExecutor.submit(() -> {
            try {
                configureFailClosedPreferences();
                EnvList environment = Android.newEnvList();
                environment.put(Android.getEnvKeyNBForceRelay(), "true");

                Client nativeClient = takePreparedClient();
                if (nativeClient == null) {
                    nativeClient = newNativeClient();
                }
                client = nativeClient;
                nativeClient.setConnectionListener(connectionListener);
                nativeClient.setNetworkAvailable(hasUsableNetwork());
                nativeClient.runWithoutLogin(
                        platformFiles,
                        new DNSList(),
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
                client = null;
                if (stopping) {
                    emitTransport(TransportState.DISCONNECTED);
                }
            }
        });
    }

    synchronized void disconnect() {
        stopping = true;
        Client nativeClient = client;
        if (nativeClient == null) {
            emitTransport(TransportState.DISCONNECTED);
            stopForeground(STOP_FOREGROUND_REMOVE);
            return;
        }
        emitTransport(TransportState.DISCONNECTING);
        nativeClient.stop();
        emitTransport(TransportState.DISCONNECTED);
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    boolean hasPeerIdentity() {
        java.io.File config = new java.io.File(platformFiles.configurationFilePath());
        return config.isFile() && config.length() > 0;
    }

    void enroll(String managementUrl, String setupKey, String hostname, EnrollmentCallback callback) {
        try {
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

    private boolean hasUsableNetwork() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        Network network = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities =
                manager == null || network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private final ConnectionListener connectionListener = new ConnectionListener() {
        @Override
        public void onStateChanged(long state) {
            emitTransport(TransportStateMapper.fromNative(state));
        }

        @Override public void onConnected() {
            emitTransport(TransportState.CONNECTED);
        }
        @Override public void onDisconnected() {
            emitTransport(TransportState.DISCONNECTED);
        }
        @Override public void onConnecting() {
            emitTransport(TransportState.CONNECTING);
        }
        @Override public void onDisconnecting() {
            emitTransport(TransportState.DISCONNECTING);
        }
        @Override public void onAddressChanged(String ignoredV4, String ignoredV6) {}
        @Override public void onPeersListChanged(long ignoredCount) {}
    };

    private void emitTransport(TransportState state) {
        transportState = state;
        mainHandler.post(() -> {
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
