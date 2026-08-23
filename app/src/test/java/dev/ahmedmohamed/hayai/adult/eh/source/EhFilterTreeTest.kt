package dev.ahmedmohamed.hayai.adult.eh.source

import eu.kanade.tachiyomi.source.model.Filter
import org.junit.Assert.assertEquals
import org.junit.Test

class EhFilterTreeTest {
    @Test
    fun `advanced options remain visible to recursive projection`() {
        val leaf = object : Filter.CheckBox("Require gallery torrent", true) {}
        val inner = object : Filter.Group<Filter<*>>("Advanced options", listOf(leaf)) {}
        val outer = object : Filter.Group<Filter<*>>("Root", listOf(inner)) {}

        assertEquals(listOf(outer, inner, leaf), listOf<Filter<*>>(outer).flattenEhFilterTree().toList())
    }
}
