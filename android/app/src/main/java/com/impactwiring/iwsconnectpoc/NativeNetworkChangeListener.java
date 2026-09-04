package com.impactwiring.iwsconnectpoc;

import io.netbird.gomobile.android.NetworkChangeListener;

/** Route changes are intentionally not applied; the POC TUN keeps one static overlay route. */
final class NativeNetworkChangeListener implements NetworkChangeListener {
    @Override
    public void onNetworkChanged(String ignoredRoutes) {}

    @Override
    public void setInterfaceIP(String ignoredAddress) {}

    @Override
    public void setInterfaceIPv6(String ignoredAddress) {}
}
