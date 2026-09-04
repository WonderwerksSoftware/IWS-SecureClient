package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ChromeInsetsTest {
    @Test
    public void navigationStartsBelowTheStatusBar() {
        assertEquals(104, ChromeInsets.navigationTopPadding(8, 96));
    }

    @Test
    public void invalidNegativeInsetCannotPullNavigationUnderSystemChrome() {
        assertEquals(8, ChromeInsets.navigationTopPadding(8, -12));
    }
}
