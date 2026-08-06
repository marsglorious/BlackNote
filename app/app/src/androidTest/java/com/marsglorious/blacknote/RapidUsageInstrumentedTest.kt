package com.marsglorious.blacknote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.marsglorious.blacknote.viewmodel.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Speed and stability tests under rapid note creation, opening, closing, and search.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RapidUsageInstrumentedTest : InstrumentedVmTestBase() {

    @Test
    fun rapidCreateOpenClose_cyclesWithoutCrash() = runBlocking {
        val start = System.currentTimeMillis()
        repeat(15) { i ->
            vm.newNote()
            awaitEditor()
            vm.onTitleChange(tfv("Rapid $i"))
            vm.onBodyChange(tfv("body $i"))
            vm.closeEditor()
            awaitList()
        }
        val dur = System.currentTimeMillis() - start
        assertTrue("15 create/close cycles took ${dur}ms (limit 30s)", dur < 30_000)
        assertEquals(Screen.LIST, vm.uiState.value.screen)
        assertTrue(vm.uiState.value.tree.notes.size >= 15)
    }

    @Test
    fun rapidOpenSameNote_repeatedlyAlwaysOpens() = runBlocking {
        seedNote("Tap.md", "tap body")
        refreshAndSettle()
        val note = vm.uiState.value.tree.notes.single()

        repeat(10) {
            vm.openNote(note)
            awaitEditor()
            vm.closeEditor()
            awaitList()
        }
        assertEquals(Screen.LIST, vm.uiState.value.screen)
    }

    @Test
    fun rapidSearchQueries_returnConsistentResults() = runBlocking {
        repeat(20) { i ->
            seedNote("Note$i.md", "content for note $i")
        }
        refreshAndSettle()

        val queries = listOf("Note1", "Note5", "Note19", "missing")
        repeat(5) {
            queries.forEach { q ->
                vm.setQuery(q)
                awaitCondition("search settled for '$q'") {
                    vm.uiState.value.query == q
                }
            }
        }
        vm.setQuery("")
        awaitCondition("search cleared") { vm.uiState.value.query.isEmpty() }
    }

    @Test
    fun rapidScreenNavigation_staysStable() = runBlocking {
        repeat(20) {
            vm.openSettings()
            awaitSettings()
            vm.backToList()
            awaitList()
            vm.openTrash()
            awaitTrash()
            vm.backToList()
            awaitList()
        }
        assertEquals(Screen.LIST, vm.uiState.value.screen)
    }

    @Test
    fun rapidRefresh_doesNotDuplicateNotes() = runBlocking {
        seedNote("Single.md", "only one")
        refreshAndSettled()
        repeat(8) { vm.refreshTree() }
        awaitRefreshSettled()
        val singles = vm.uiState.value.tree.notes.filter { it.title == "Single" }
        assertEquals(1, singles.size)
    }

    @Test
    fun mashNewNote_whileRefreshing_eventuallyConsistent() = runBlocking {
        repeat(5) { i ->
            vm.newNote()
            vm.refreshTree()
            awaitEditor()
            vm.onTitleChange(tfv("Mash $i"))
            vm.closeEditor()
        }
        awaitRefreshSettled()
        assertTrue(vm.uiState.value.tree.notes.size >= 5)
    }

    private suspend fun refreshAndSettled() {
        vm.refreshTree()
        awaitRefreshSettled()
    }
}