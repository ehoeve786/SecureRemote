package com.secrm.core.ssh.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class SnippetsTest {
    @Test fun findsPlaceholdersButNotShellExpansions() {
        assertEquals(listOf("unit", "lines"), placeholdersOf("journalctl -u {unit} -n {lines} \${HOME}"))
    }

    @Test fun valuesAreQuotedSoTheyCannotAddCommands() {
        val out = fillSnippet("cat {file}", mapOf("file" to "a; rm -rf ~"))
        assertEquals("cat 'a; rm -rf ~'", out)
    }

    @Test fun quotesInsideValuesAreEscaped() {
        assertEquals("echo 'it'\\''s'", fillSnippet("echo {msg}", mapOf("msg" to "it's")))
    }

    @Test fun shellExpansionIsLeftAlone() {
        assertEquals("echo \${HOME}", fillSnippet("echo \${HOME}", emptyMap()))
    }
}
