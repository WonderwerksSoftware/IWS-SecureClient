package com.impactwiring.iwsconnectpoc;

import io.netbird.gomobile.android.IFaceDiscover;

/** Deliberately exposes no LAN or Tailscale interfaces; relay mode does not need ICE discovery. */
final class EmptyInterfaceDiscover implements IFaceDiscover {
    @Override
    public String iFaces() {
        return "";
    }
}
