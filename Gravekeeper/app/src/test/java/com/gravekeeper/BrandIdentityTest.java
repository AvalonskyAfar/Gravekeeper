package com.gravekeeper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BrandIdentityTest {
    @Test
    public void formalNamesStaySynchronized() {
        assertEquals("守目人", BrandIdentity.DISPLAY_NAME);
        assertEquals("Gravekeeper", BrandIdentity.ENGLISH_NAME);
        assertEquals("守目人（Gravekeeper）", BrandIdentity.FULL_NAME);
    }

    @Test
    public void diagnosticExportUsesFormalEnglishName() {
        assertEquals("Gravekeeper-diagnostic.txt",
                BrandIdentity.DIAGNOSTIC_FILE_NAME);
    }
}
