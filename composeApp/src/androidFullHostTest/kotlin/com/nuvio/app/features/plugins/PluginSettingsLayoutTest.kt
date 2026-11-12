package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginSettingsLayoutTest {
    @Test
    fun rejectsMalformedAndNonArrayLayouts() {
        assertFalse(hasUsablePluginSettingsLayout("not-json"))
        assertFalse(hasUsablePluginSettingsLayout("""{"type":"text","key":"token"}"""))
        assertFalse(hasUsablePluginSettingsLayout("[]"))
    }

    @Test
    fun rejectsUnsupportedOrKeylessFields() {
        assertFalse(hasUsablePluginSettingsLayout("""[{"type":"header","label":"Settings"}]"""))
        assertFalse(hasUsablePluginSettingsLayout("""[{"type":"text","key":""}]"""))
        assertFalse(hasUsablePluginSettingsLayout("""[{"type":"unsupported","key":"value"}]"""))
    }

    @Test
    fun acceptsSupportedFieldsWithNonBlankKeys() {
        assertTrue(hasUsablePluginSettingsLayout("""[{"type":"text","key":"apiKey"}]"""))
        assertTrue(hasUsablePluginSettingsLayout("""[{"type":"select","key":"quality"}]"""))
        assertTrue(hasUsablePluginSettingsLayout("""[{"type":"toggle","key":"enabled"}]"""))
    }
}
