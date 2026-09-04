package com.impactwiring.iwsconnectpoc;

final class ChromeInsets {
    private ChromeInsets() {}

    static int navigationTopPadding(int basePadding, int statusBarInset) {
        return basePadding + Math.max(0, statusBarInset);
    }
}
