package com.huangder.lumibooks.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class LiquidGlassCapabilityTest {
    @Test
    fun floatingLightKeepsLumiPagesWhileEnablingOnlyTheGlassDock() {
        val capability = LiquidGlassCapability(supported = true, hdrSupported = false)

        assertEquals("lumi", effectiveAppTheme("fuguang", capability))
        assertEquals(true, usesLiquidGlassDock("fuguang", capability))
    }

    @Test
    fun floatingLightFallsBackToTheLumiDockWithoutGlassSupport() {
        val capability = LiquidGlassCapability(supported = false, hdrSupported = false)

        assertEquals("lumi", effectiveAppTheme("fuguang", capability))
        assertEquals(false, usesLiquidGlassDock("fuguang", capability))
    }

    @Test
    fun glassDockPolicyKeepsExistingThemesAndEasterEggBehavior() {
        val capability = LiquidGlassCapability(supported = true, hdrSupported = false)

        assertEquals(true, usesLiquidGlassDock("liquid_glass", capability))
        assertEquals(true, usesLiquidGlassDock("lumi_chan", capability))
        assertEquals(false, usesLiquidGlassDock("lumi", capability))
        assertEquals(false, usesLiquidGlassDock("material3", capability))
    }

    @Test
    fun unsupportedLiquidGlassFallsBackWithoutChangingStoredTheme() {
        val capability = LiquidGlassCapability(supported = false, hdrSupported = false)

        assertEquals("lumi", effectiveAppTheme("liquid_glass", capability))
    }

    @Test
    fun supportedLiquidGlassRemainsActiveWithoutHdr() {
        val capability = LiquidGlassCapability(supported = true, hdrSupported = false)

        assertEquals("liquid_glass", effectiveAppTheme("liquid_glass", capability))
    }

    @Test
    fun otherThemesAreNeverChangedByCapability() {
        val capability = LiquidGlassCapability(supported = false, hdrSupported = false)

        assertEquals("lumi", effectiveAppTheme("lumi", capability))
        assertEquals("material3", effectiveAppTheme("material3", capability))
    }
}
