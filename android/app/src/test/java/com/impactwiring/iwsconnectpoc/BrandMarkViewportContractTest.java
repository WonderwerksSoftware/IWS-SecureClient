package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class BrandMarkViewportContractTest {
    @Test public void fullBrandMarkViewportContainsBothConnectorSides() throws Exception {
        Path mark = Path.of("src", "main", "res", "drawable", "ic_iws_mark.xml");
        String source = new String(Files.readAllBytes(mark), StandardCharsets.UTF_8);

        assertTrue(source.contains("android:width=\"156dp\""));
        assertTrue(source.contains("android:viewportWidth=\"156\""));
    }
}
