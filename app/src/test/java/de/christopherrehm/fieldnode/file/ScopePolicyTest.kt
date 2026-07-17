package de.christopherrehm.fieldnode.file

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopePolicyTest {

    private val base = Files.createTempDirectory("scope").toFile()
    private val root = File(base, "writable").apply { mkdirs() }
    private val policy = ScopePolicy(listOf(root))

    @Test fun allowsTheRootItself() {
        assertTrue(policy.canMutate(root))
    }

    @Test fun allowsChildrenOfTheRoot() {
        assertTrue(policy.canMutate(File(root, "sub/file.txt")))
    }

    @Test fun blocksPathsOutsideTheRoot() {
        assertFalse(policy.canMutate(File(base, "elsewhere/file.txt")))
    }

    @Test fun blocksTraversalEscapeOutOfTheRoot() {
        // root/../elsewhere resolves outside the allowlist and must be refused.
        assertFalse(policy.canMutate(File(root, "../elsewhere/secret")))
    }

    @Test fun doesNotMatchSiblingWithSamePrefix() {
        // "writable-extra" shares a string prefix with "writable" but is a different dir.
        assertFalse(policy.canMutate(File(base, "writable-extra/file.txt")))
    }
}
