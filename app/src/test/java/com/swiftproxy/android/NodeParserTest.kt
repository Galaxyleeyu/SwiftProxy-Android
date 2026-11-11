package com.swiftproxy.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NodeParserTest {
    @Test
    fun parsesVlessNodeAndFragmentName() {
        val node = NodeParser.parseUri(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls&sni=example.com#primary"
        )
        assertNotNull(node)
        assertEquals("VLESS", node?.type)
        assertEquals("example.com", node?.server)
        assertEquals(443, node?.port)
        assertEquals("primary", node?.name)
    }

    @Test
    fun parsesTrojanNode() {
        val node = NodeParser.parseUri("trojan://test-password@example.net:8443?security=tls#backup")
        assertNotNull(node)
        assertEquals("TROJAN", node?.type)
        assertEquals("example.net", node?.server)
        assertEquals(8443, node?.port)
        assertEquals("backup", node?.name)
    }

    @Test
    fun rejectsUnsupportedProtocol() {
        assertNull(NodeParser.parseUri("http://example.com:8080"))
    }

    @Test
    fun parsesPlainTextSubscriptionAndRemovesDuplicates() {
        val content = """
            vless://00000000-0000-0000-0000-000000000000@one.example:443#one
            # comment
            trojan://password@two.example:443#two
            vless://00000000-0000-0000-0000-000000000000@one.example:443#one
        """.trimIndent()
        val nodes = NodeParser.parseSubscription(content)
        assertEquals(2, nodes.size)
        assertEquals("one.example", nodes[0].server)
        assertEquals("two.example", nodes[1].server)
    }
}
