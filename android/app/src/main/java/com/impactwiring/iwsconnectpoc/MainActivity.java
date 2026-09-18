package com.impactwiring.iwsconnectpoc;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.net.VpnService;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;

public final class MainActivity extends Activity implements
        IwsVpnService.Observer, PortalReadinessCoordinator.Driver {
    private static final int VPN_PERMISSION_REQUEST = 4101;
    private static final String STATE_HAS_WEBVIEW_HISTORY =
            "com.impactwiring.iwsconnectpoc.HAS_WEBVIEW_HISTORY";
    private static final String CURRENT_DOCUMENT_QUERY =
            "(function(){"
            + "if(document.readyState!=='complete')return 'L';"
            + "var s=-1;try{var e=performance.getEntriesByType('navigation')[0];"
            + "if(e&&typeof e.responseStatus==='number')s=e.responseStatus;}catch(_e){}"
            + "var o='';try{o=String(performance.timeOrigin);}catch(_e){}"
            + "return 'C|'+s+'|'+o+'|'+encodeURIComponent(String(location.href));"
            + "})()";
    private static final String CURRENT_DOCUMENT_EPOCH_QUERY =
            "(function(){try{return 'E|'+String(performance.timeOrigin);}"
            + "catch(_e){return 'U';}"
            + "})()";

    private WebView webView;
    private Button backButton;
    private Button retryButton;
    private LinearLayout statusPane;
    private TextView statusText;
    private TextView statusDetail;
    private PortalPolicy portalPolicy;
    private IwsVpnService service;
    private boolean bound;
    private boolean permissionRequestInFlight;
    private boolean bootstrapEnrollmentInFlight;
    private final Object readinessCallbackToken = new Object();
    private Handler readinessHandler;
    private PortalReadinessCoordinator readinessCoordinator;
    private PortalDocumentCoordinator documentCoordinator;
    private PortalProbeRunner probeRunner;
    private Bundle pendingWebViewState;
    private long nativeNavigationPreparation;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((IwsVpnService.LocalBinder) binder).service();
            bound = true;
            service.setObserver(MainActivity.this);
            if (portalPolicy != null && service.transportState() != TransportState.CONNECTED) {
                requestVpnPermission();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            onTransportState(TransportState.DISCONNECTED);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildShell();
        configurePortalPolicy();
        configureWebView();
        configureReadiness();
        if (state != null
                && state.getBoolean(STATE_HAS_WEBVIEW_HISTORY, false)
                && readinessCoordinator != null) {
            pendingWebViewState = state;
            readinessCoordinator.onRestoredStatePending();
        }
        registerSystemBackHandler();
        if (readinessCoordinator != null) {
            showConnectionState("Connecting to IWS…", false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (readinessCoordinator != null) {
            readinessCoordinator.onStart();
            probeRunner.attach(() -> postReadinessCallback(
                    readinessCoordinator::onProbeSlotAvailable, 0L));
        }
        bindService(new Intent(this, IwsVpnService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        CookieManager.getInstance().flush();
        if (probeRunner != null) {
            probeRunner.detach();
        }
        if (readinessCoordinator != null) {
            readinessCoordinator.onStop();
        }
        if (documentCoordinator != null) {
            documentCoordinator.invalidatePending();
        }
        cancelNativeNavigationPreparation();
        if (bound) {
            service.setObserver(null);
            unbindService(connection);
            bound = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (readinessHandler != null) {
            readinessHandler.removeCallbacksAndMessages(readinessCallbackToken);
        }
        if (probeRunner != null) {
            probeRunner.close();
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (pendingWebViewState != null) {
            state.putAll(pendingWebViewState);
        } else if (webView.saveState(state) != null) {
            state.putBoolean(STATE_HAS_WEBVIEW_HISTORY, true);
        }
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VPN_PERMISSION_REQUEST) {
            return;
        }
        permissionRequestInFlight = false;
        if (resultCode == RESULT_OK) {
            startAuthorizedTransport();
        } else {
            showConnectionState("IWS needs permission to open its private connection.", true);
        }
    }

    @Override
    @Deprecated
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        navigateBack();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.iws_background));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setBackgroundColor(color(R.color.iws_surface));
        int navigationPadding = dp(8);
        navigation.setPadding(
                navigationPadding, navigationPadding, navigationPadding, navigationPadding);
        navigation.setOnApplyWindowInsetsListener((view, insets) -> {
            int statusBarInset = statusBarInset(insets);
            view.setPadding(
                    navigationPadding,
                    ChromeInsets.navigationTopPadding(navigationPadding, statusBarInset),
                    navigationPadding,
                    navigationPadding);
            return insets;
        });

        backButton = railButton("\u2190  " + getString(R.string.iws_back));
        backButton.setEnabled(false);
        backButton.setOnClickListener(view -> navigateBack());
        navigation.addView(backButton, railButtonParams(0));

        ImageView railMark = new ImageView(this);
        railMark.setImageResource(R.drawable.ic_iws_mark);
        railMark.setColorFilter(color(R.color.iws_accent));
        railMark.setContentDescription(getString(R.string.iws_mark_description));
        LinearLayout.LayoutParams markParams =
                new LinearLayout.LayoutParams(dp(26), dp(20));
        markParams.leftMargin = dp(8);
        markParams.rightMargin = dp(6);
        navigation.addView(railMark, markParams);

        Button portalButton = railButton(getString(R.string.iws_portal));
        portalButton.setOnClickListener(view -> openPortalRoot());
        navigation.addView(portalButton, railButtonParams(0));

        View railSpacer = new View(this);
        navigation.addView(railSpacer, new LinearLayout.LayoutParams(0, 1, 1));
        root.addView(navigation, matchWrap());

        View railRule = new View(this);
        railRule.setBackgroundColor(color(R.color.iws_gradient_mid));
        root.addView(railRule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        FrameLayout content = new FrameLayout(this);
        webView = new WebView(this);
        content.addView(webView, matchMatch());

        statusPane = new LinearLayout(this);
        statusPane.setOrientation(LinearLayout.VERTICAL);
        statusPane.setGravity(Gravity.CENTER);
        statusPane.setPadding(dp(32), dp(32), dp(32), dp(32));
        statusPane.setBackgroundColor(color(R.color.iws_background));

        ImageView brandMark = new ImageView(this);
        brandMark.setImageResource(R.drawable.ic_iws_mark);
        brandMark.setColorFilter(color(R.color.iws_accent));
        brandMark.setContentDescription(getString(R.string.iws_mark_description));
        LinearLayout.LayoutParams brandMarkParams =
                new LinearLayout.LayoutParams(dp(96), dp(74));
        brandMarkParams.gravity = Gravity.CENTER_HORIZONTAL;
        brandMarkParams.bottomMargin = dp(14);
        statusPane.addView(brandMark, brandMarkParams);

        TextView brand = new TextView(this);
        brand.setText(R.string.app_name);
        brand.setTextSize(28);
        brand.setLetterSpacing(0.16f);
        brand.setTypeface(brand.getTypeface(), android.graphics.Typeface.BOLD);
        brand.setTextColor(color(R.color.iws_text));
        brand.setGravity(Gravity.CENTER);
        statusPane.addView(brand, matchWrap());

        TextView company = new TextView(this);
        company.setText(R.string.iws_company);
        company.setTextSize(11);
        company.setLetterSpacing(0.18f);
        company.setAllCaps(true);
        company.setTextColor(color(R.color.iws_text_muted));
        company.setGravity(Gravity.CENTER);
        statusPane.addView(company, matchWrap());

        statusText = new TextView(this);
        statusText.setTextSize(17);
        statusText.setTextColor(color(R.color.iws_text));
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(26);
        statusPane.addView(statusText, statusParams);

        statusDetail = new TextView(this);
        statusDetail.setTextSize(14);
        statusDetail.setTextColor(color(R.color.iws_text_muted));
        statusDetail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(6);
        statusPane.addView(statusDetail, detailParams);

        retryButton = railButton(getString(R.string.iws_retry));
        retryButton.setOnClickListener(view -> manualRetry());
        LinearLayout.LayoutParams retryParams =
                new LinearLayout.LayoutParams(dp(168), dp(48));
        retryParams.gravity = Gravity.CENTER_HORIZONTAL;
        retryParams.topMargin = dp(22);
        statusPane.addView(retryButton, retryParams);
        content.addView(statusPane, matchMatch());

        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        navigation.requestApplyInsets();
    }

    @SuppressWarnings("deprecation")
    private int statusBarInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            return insets.getInsets(WindowInsets.Type.statusBars()).top;
        }
        return insets.getSystemWindowInsetTop();
    }

    private void configurePortalPolicy() {
        try {
            portalPolicy = new PortalPolicy(BuildConfig.IWS_PORTAL_URL);
        } catch (IllegalArgumentException error) {
            portalPolicy = null;
            showConnectionState("This IWS build has no valid portal configured.", false);
        }
    }

    private void configureReadiness() {
        if (portalPolicy == null) {
            return;
        }
        try {
            readinessHandler = new Handler(Looper.getMainLooper());
            probeRunner = new PortalProbeRunner(new PortalHealthProbe(
                    portalPolicy.portalRoot(),
                    new AppVpnDnsReadiness(
                            getApplicationContext(), BuildConfig.ALLOWED_ENDPOINT_IPV4)));
            readinessCoordinator = new PortalReadinessCoordinator(
                    SystemClock::elapsedRealtime, this);
            documentCoordinator = new PortalDocumentCoordinator(readinessCoordinator);
        } catch (IllegalArgumentException error) {
            portalPolicy = null;
            showConnectionState("This IWS build has no valid HTTPS portal configured.", false);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) {
                    return false;
                }
                boolean blocked = !isAllowed(request.getUrl().toString());
                if (blocked) {
                    showConnectionState("That destination is outside IWS.", true);
                } else {
                    trackMainFrameRequest(request.getUrl().toString());
                }
                return blocked;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                if (portalPolicy != null
                        && portalPolicy.isAllowedResource(request.getUrl().toString())) {
                    return null;
                }
                return new WebResourceResponse(
                        "text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                String currentUrl = view.getUrl();
                if (readinessCoordinator != null
                        && isAllowed(url)
                        && url.equals(currentUrl)
                        && !documentCoordinator.expects(url)) {
                    trackMainFrameRequest(url);
                }
                if (documentCoordinator != null) {
                    documentCoordinator.onStarted(url, currentUrl);
                }
                updateBackButton();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                observeCurrentDocument();
                updateBackButton();
            }

            @Override
            public void onReceivedError(
                    WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    if (error.getErrorCode() == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) {
                        latchCurrentDocumentTlsFailure(view);
                        return;
                    }
                    observeCurrentDocument();
                }
            }

            @Override
            public void onReceivedHttpError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                    observeCurrentDocument();
                }
            }

            @Override
            public void onReceivedSslError(
                    WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                latchCurrentDocumentTlsFailure(view);
            }
        });
    }

    private void registerSystemBackHandler() {
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::navigateBack);
        }
    }

    private void requestVpnPermission() {
        if (!bound || portalPolicy == null || permissionRequestInFlight) {
            return;
        }
        if (service.transportState() == TransportState.CONNECTED) {
            readinessCoordinator.onTransportConnected();
            return;
        }
        Intent permissionIntent = VpnService.prepare(this);
        if (permissionIntent == null) {
            startAuthorizedTransport();
        } else {
            permissionRequestInFlight = true;
            startActivityForResult(permissionIntent, VPN_PERMISSION_REQUEST);
        }
    }

    private void startAuthorizedTransport() {
        BootstrapDecision.Action action = BootstrapDecision.decide(
                service.hasPeerIdentity(),
                !BuildConfig.IWS_BOOTSTRAP_SETUP_KEY.isEmpty()
                        && !BuildConfig.IWS_BOOTSTRAP_HOSTNAME.isEmpty(),
                bootstrapEnrollmentInFlight);
        if (action == BootstrapDecision.Action.WAIT) {
            return;
        }
        if (action == BootstrapDecision.Action.FAIL) {
            showConnectionState(
                    "IWS device setup failed. Request a replacement installer.", true);
            return;
        }
        if (action == BootstrapDecision.Action.ENROLL) {
            bootstrapEnrollmentInFlight = true;
            showConnectionState("Setting up IWS…", false);
            service.enroll(
                    BuildConfig.MANAGEMENT_URL,
                    BuildConfig.IWS_BOOTSTRAP_SETUP_KEY,
                    BuildConfig.IWS_BOOTSTRAP_HOSTNAME,
                    new IwsVpnService.EnrollmentCallback() {
                        @Override public void onSuccess() {
                            bootstrapEnrollmentInFlight = false;
                            startTransportOnly();
                        }
                        @Override public void onError() {
                            bootstrapEnrollmentInFlight = false;
                            showConnectionState(
                                    "IWS device setup failed. Request a replacement installer.", true);
                        }
                    });
            return;
        }
        startTransportOnly();
    }

    private void startTransportOnly() {
        showConnectionState("Connecting to IWS…", false);
        Intent intent = new Intent(this, IwsVpnService.class).setAction(IwsVpnService.ACTION_CONNECT);
        startForegroundService(intent);
    }

    private void openPortalRoot() {
        if (readinessCoordinator == null) {
            return;
        }
        cancelNativeNavigationPreparation();
        documentCoordinator.invalidatePending();
        readinessCoordinator.onPortalRootRequested();
        if (!bound || service.transportState() != TransportState.CONNECTED) {
            requestVpnPermission();
        }
    }

    private void manualRetry() {
        if (readinessCoordinator == null) {
            return;
        }
        cancelNativeNavigationPreparation();
        documentCoordinator.invalidatePending();
        readinessCoordinator.onManualRetry();
        if (bound && service.hasPeerIdentity()
                && VpnService.prepare(this) == null) {
            startForegroundService(new Intent(this, IwsVpnService.class)
                    .setAction(IwsVpnService.ACTION_RETRY));
        } else {
            requestVpnPermission();
        }
    }

    private void navigateBack() {
        if (webView.canGoBack()) {
            android.webkit.WebBackForwardList history = webView.copyBackForwardList();
            int targetIndex = history.getCurrentIndex() - 1;
            if (targetIndex >= 0) {
                trackMainFrameRequest(history.getItemAtIndex(targetIndex).getUrl());
            }
            webView.goBack();
        }
        updateBackButton();
    }

    private boolean isAllowed(String url) {
        return portalPolicy != null && portalPolicy.isAllowed(url);
    }

    private void updateBackButton() {
        runOnUiThread(() -> backButton.setEnabled(webView.canGoBack()));
    }

    private void showConnectionState(String message, boolean showRetry) {
        showConnectionState(message, "", showRetry);
    }

    private void showConnectionState(String message, String detail, boolean showRetry) {
        runOnUiThread(() -> {
            statusText.setText(message);
            statusDetail.setText(detail);
            statusDetail.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
            retryButton.setVisibility(showRetry ? View.VISIBLE : View.GONE);
            statusPane.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onTransportState(TransportState state) {
        runOnUiThread(() -> {
            if (readinessCoordinator == null) {
                return;
            }
            switch (state) {
                case CONNECTED:
                    readinessCoordinator.onTransportConnected();
                    break;
                case CONNECTING:
                case DISCONNECTING:
                    cancelNativeNavigationPreparation();
                    documentCoordinator.invalidatePending();
                    readinessCoordinator.onTransportConnecting();
                    break;
                case ERROR:
                case DISCONNECTED:
                default:
                    cancelNativeNavigationPreparation();
                    documentCoordinator.invalidatePending();
                    readinessCoordinator.onTransportDisconnected();
                    break;
            }
        });
    }

    @Override
    public void showConnecting() {
        showConnectionState("Connecting to IWS…", false);
    }

    @Override
    public void showUnavailable(boolean tlsFailure) {
        cancelNativeNavigationPreparation();
        if (tlsFailure) {
            showConnectionState("IWS could not verify the portal connection.", true);
        } else {
            showConnectionState(
                    "IWS is unavailable.", "Check your connection and try again.", true);
        }
    }

    @Override
    public void revealPortal() {
        cancelNativeNavigationPreparation();
        statusPane.setVisibility(View.GONE);
    }

    @Override
    public void navigateToPortalRoot(long navigationIdentity) {
        prepareNativeNavigation(navigationIdentity, portalPolicy.portalRoot());
    }

    @Override
    public void restorePortalState(long navigationIdentity) {
        Bundle state = pendingWebViewState;
        pendingWebViewState = null;
        if (state == null) {
            documentCoordinator.onPreparationFailed(navigationIdentity);
            return;
        }
        android.webkit.WebBackForwardList restored = webView.restoreState(state);
        if (restored == null
                || restored.getCurrentItem() == null
                || !isAllowed(restored.getCurrentItem().getUrl())) {
            documentCoordinator.onPreparationFailed(navigationIdentity);
            return;
        }
        documentCoordinator.expect(navigationIdentity, restored.getCurrentItem().getUrl());
        updateBackButton();
    }

    @Override
    public boolean startProbe(long episode, long remainingMillis) {
        return probeRunner.start(episode, remainingMillis, (completedEpisode, result) ->
                postReadinessCallback(() -> readinessCoordinator.onProbeCompleted(
                        completedEpisode, result), 0L));
    }

    @Override
    public void scheduleRetry(long episode, long delayMillis) {
        postReadinessCallback(
                () -> readinessCoordinator.onRetryDue(episode), delayMillis);
    }

    @Override
    public void scheduleDeadline(long episode, long delayMillis) {
        postReadinessCallback(
                () -> readinessCoordinator.onDeadline(episode), delayMillis);
    }

    @Override
    public void cancelScheduledWork() {
        readinessHandler.removeCallbacksAndMessages(readinessCallbackToken);
    }

    private void postReadinessCallback(Runnable callback, long delayMillis) {
        readinessHandler.postAtTime(
                callback,
                readinessCallbackToken,
                SystemClock.uptimeMillis() + delayMillis);
    }

    private void trackMainFrameRequest(String url) {
        if (readinessCoordinator == null) {
            return;
        }
        cancelNativeNavigationPreparation();
        long navigationIdentity = readinessCoordinator.onMainFrameLoadRequested();
        documentCoordinator.expect(navigationIdentity, url);
    }

    private void observeCurrentDocument() {
        String currentUrl = webView.getUrl();
        long navigationIdentity = documentCoordinator.pendingIdentityForCurrentUrl(currentUrl);
        if (navigationIdentity == MainFrameNavigationGuard.NONE) {
            return;
        }
        webView.evaluateJavascript(CURRENT_DOCUMENT_QUERY, javascriptResult -> {
            CurrentDocumentObservation observation =
                    CurrentDocumentObservation.parse(javascriptResult);
            if (!observation.complete) {
                return;
            }
            String observedCurrentUrl = webView.getUrl();
            documentCoordinator.onObservation(
                    navigationIdentity,
                    observation,
                    observedCurrentUrl,
                    isAllowed(observation.url));
        });
    }

    private void latchCurrentDocumentTlsFailure(WebView view) {
        if (documentCoordinator == null) {
            return;
        }
        documentCoordinator.onTlsError(view.getUrl());
    }

    private void prepareNativeNavigation(long navigationIdentity, String destination) {
        long preparation = ++nativeNavigationPreparation;
        String currentUrl = webView.getUrl();
        if (currentUrl == null || "about:blank".equals(currentUrl)) {
            documentCoordinator.expect(navigationIdentity, destination);
            webView.loadUrl(destination);
            return;
        }
        webView.evaluateJavascript(CURRENT_DOCUMENT_EPOCH_QUERY, javascriptResult -> {
            if (preparation != nativeNavigationPreparation) {
                return;
            }
            String baselineEpoch = CurrentDocumentObservation.parseEpoch(javascriptResult);
            if (baselineEpoch.isEmpty()) {
                documentCoordinator.onPreparationFailed(navigationIdentity);
                return;
            }
            documentCoordinator.expectReplacing(
                    navigationIdentity, destination, baselineEpoch);
            webView.loadUrl(destination);
        });
    }

    private void cancelNativeNavigationPreparation() {
        nativeNavigationPreparation++;
    }

    @Override
    public void onEndpointState(String ignoredState) {}

    @Override
    public void onMessage(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private Button railButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(color(R.color.iws_text));
        button.setBackgroundColor(color(R.color.iws_surface_alt));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setMinHeight(dp(44));
        button.setStateListAnimator(null);
        return button;
    }

    private LinearLayout.LayoutParams railButtonParams(int leftMargin) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        params.leftMargin = leftMargin;
        return params;
    }

    private int color(int resourceId) {
        return getResources().getColor(resourceId, getTheme());
    }

    private ViewGroup.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private ViewGroup.LayoutParams matchMatch() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
