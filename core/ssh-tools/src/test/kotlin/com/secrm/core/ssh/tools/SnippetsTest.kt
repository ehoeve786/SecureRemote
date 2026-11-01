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

    @Test fun valueInsideSingleQuotesStaysQuoted() {
        // Plain quoting would give grep '''; rm -rf ~; ''' f, which runs the rm.
        assertEquals("grep ''\\''; rm -rf ~; '\\''' f", fillSnippet("grep '{p}' f", mapOf("p" to "'; rm -rf ~; '")))
        assertEquals("grep 'a b' f", fillSnippet("grep '{p}' f", mapOf("p" to "a b")))
    }

    @Test fun valueInsideDoubleQuotesCannotExpand() {
        assertEquals("echo \"\\\$(id) \\`id\\` \\\"\"", fillSnippet("echo \"{v}\"", mapOf("v" to "\$(id) `id` \"")))
    }

    @Test fun escapedQuoteDoesNotChangeContext() {
        assertEquals("echo \\' 'x'", fillSnippet("echo \\' {v}", mapOf("v" to "x")))
    }

    @Test fun shellExpansionIsLeftAlone() {
        assertEquals("echo \${HOME}", fillSnippet("echo \${HOME}", emptyMap()))
    }
}
