package com.relaykit.network

import com.relaykit.network.json.RelayJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayJsonTest {
    @Test fun readsNestedValuesAndSafeMissingNodes() {
        val root = RelayJson.parse("""{"name":"Ada","count":3,"paid":true,"items":["x"]}""")
        assertEquals("Ada", root["name"].asString())
        assertEquals(3, root["count"].asInt())
        assertTrue(root["paid"].asBoolean())
        assertEquals("x", root["items"][0].asString())
        assertTrue(root["missing"].isNull)
        assertTrue(root["items"][4].isNull)
        assertFalse(root["missing"].has("child"))
    }
}
