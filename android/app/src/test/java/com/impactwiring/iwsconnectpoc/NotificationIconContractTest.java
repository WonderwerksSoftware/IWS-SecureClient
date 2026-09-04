package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class NotificationIconContractTest {
    @Test public void manifestDeclaresNotificationPermissionWithoutRuntimePromptCode() throws Exception {
        Path manifest = Path.of("src", "main", "AndroidManifest.xml");
        String manifestSource = new String(
                Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        Path activitySource = Path.of(
                "src", "main", "java", "com", "impactwiring",
                "iwsconnectpoc", "MainActivity.java");
        String activity = new String(
                Files.readAllBytes(activitySource), StandardCharsets.UTF_8);

        assertTrue(manifestSource.contains(
                "<uses-permission android:name=\"android.permission.POST_NOTIFICATIONS\" />"));
        assertFalse(activity.contains("requestPermissions("));
        assertFalse(activity.contains("POST_NOTIFICATIONS"));
    }

    @Test public void foregroundNotificationUsesTheIwsIcon() throws Exception {
        Path serviceSource = Path.of(
                "src", "main", "java", "com", "impactwiring",
                "iwsconnectpoc", "IwsVpnService.java");
        String source = new String(Files.readAllBytes(serviceSource), StandardCharsets.UTF_8);

        assertTrue(source.contains(".setSmallIcon(R.drawable.ic_iws_notification)"));
        assertFalse(source.contains("android.R.drawable.stat_sys_upload"));
    }
}
