package com.impactwiring.iwsconnectpoc;

import android.content.Context;
import io.netbird.gomobile.android.PlatformFiles;

final class AndroidPlatformFiles implements PlatformFiles {
    private final String configurationFilePath;
    private final String stateFilePath;
    private final String cacheDir;

    AndroidPlatformFiles(Context context) {
        configurationFilePath = context.getFilesDir() + "/iws-transport.cfg";
        stateFilePath = context.getFilesDir() + "/iws-transport.state";
        cacheDir = context.getCacheDir().getAbsolutePath();
    }

    @Override
    public String configurationFilePath() {
        return configurationFilePath;
    }

    @Override
    public String stateFilePath() {
        return stateFilePath;
    }

    @Override
    public String cacheDir() {
        return cacheDir;
    }
}
