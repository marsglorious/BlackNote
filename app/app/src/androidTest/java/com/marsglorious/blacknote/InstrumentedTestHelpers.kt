package com.marsglorious.blacknote

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.marsglorious.blacknote.data.NoteRepository
import com.marsglorious.blacknote.viewmodel.AppViewModel
import com.marsglorious.blacknote.viewmodel.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import java.io.File

/**
 * Shared setup for instrumented ViewModel tests on a managed emulator or physical device.
 * Injects a [TestSafStore] backed by a real on-device directory before each test.
 */
abstract class InstrumentedVmTestBase {
    protected lateinit var ctx: Context
    protected lateinit var rootDir: File
    protected lateinit var saf: TestSafStore
    protected lateinit var repo: NoteRepository
    protected lateinit var vm: AppViewModel

    @Before
    fun baseSetup() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext()
        rootDir = File(ctx.cacheDir, "bn-it-${System.nanoTime()}").also { it.mkdirs() }
        saf = TestSafStore(ctx, rootDir)
        val app = ctx.applicationContext as App
        repo = NoteRepository(ctx, app.searchIndex, saf)
        app.setRepositoryForTest(repo)
        vm = AppViewModel(app, repo)
        vm.bootstrap(ctx)
        awaitNotRefreshing()
    }

    @After
    fun baseTearDown() {
        rootDir.deleteRecursively()
    }

    protected fun seedNote(fileName: String, body: String) {
        File(rootDir, fileName).writeText(body)
    }

    protected suspend fun refreshAndSettle() {
        vm.refreshTree()
        awaitRefreshSettled()
    }

    protected suspend fun awaitEditor() = awaitCondition("editor visible") {
        vm.uiState.value.screen == Screen.EDITOR
    }

    protected suspend fun awaitList() = awaitCondition("list visible") {
        vm.uiState.value.screen == Screen.LIST
    }

    protected suspend fun awaitTrash() = awaitCondition("trash visible") {
        vm.uiState.value.screen == Screen.TRASH
    }

    protected suspend fun awaitSettings() = awaitCondition("settings visible") {
        vm.uiState.value.screen == Screen.SETTINGS
    }

    protected suspend fun awaitNotRefreshing() = awaitCondition("not refreshing") {
        !vm.uiState.value.isRefreshing
    }

    protected suspend fun awaitRefreshSettled() {
        delay(50)
        awaitCondition("refresh idle") { !vm.uiState.value.isRefreshing }
    }

    protected suspend fun awaitCondition(
        label: String,
        timeoutMs: Long = 8000,
        predicate: () -> Boolean,
    ) {
        val ok = withTimeoutOrNull(timeoutMs) {
            while (!predicate()) delay(25)
            true
        }
        if (ok != true) error("Timed out waiting for: $label (state=${vm.uiState.value})")
    }

    protected suspend fun awaitTrashContains(title: String) = awaitCondition("trash contains '$title'") {
        vm.uiState.value.screen == Screen.TRASH &&
            vm.uiState.value.trashNotes.any { it.title.equals(title, ignoreCase = true) }
    }

    protected suspend fun awaitListContains(title: String) = awaitCondition("list contains '$title'") {
        vm.uiState.value.screen == Screen.LIST &&
            vm.uiState.value.tree.notes.any { it.title.equals(title, ignoreCase = true) }
    }

    protected fun tfv(text: String, selectionStart: Int = text.length, selectionEnd: Int = selectionStart) =
        TextFieldValue(text, TextRange(selectionStart, selectionEnd))

    protected fun trashFile(title: String): File {
        val name = if (title.endsWith(".md")) title else "$title.md"
        return File(rootDir, "Trash/$name").takeIf { it.exists() }
            ?: File(rootDir, ".Trash/$name")
    }
}