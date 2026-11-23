package io.github.tycho991.openframe.core.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Spec `remote-sources`, "Widening the folder of an account", all scenarios and the rule's edges (design decision 13). */
class WideningTest {
    private fun account(
        folder: String,
        subfolders: Boolean = true,
    ) = StoredSource(
        "s1",
        "nextcloud-account",
        "cloud.example.org",
        Connection.Account("https://cloud.example.org", "anna", "Anna", "pw"),
        folder = folder,
        includeSubfolders = subfolders,
    )

    private fun fields(
        prev: StoredSource,
        next: StoredSource,
    ) = Widening.fields(prev, next)

    @Test
    fun `The table of changes`() {
        val cases =
            listOf(
                // Whole account.
                Triple(account("/Photos"), account("/"), setOf("folder")),
                // Deeper folder with subfolders on, and with subfolders off.
                Triple(account("/Photos"), account("/Photos/2024"), emptySet()),
                Triple(account("/Photos", false), account("/Photos/Private", false), setOf("folder")),
                // Another folder, and another case.
                Triple(account("/Photos"), account("/Other"), setOf("folder")),
                Triple(account("/Photos"), account("/photos"), setOf("folder")),
                // A folder whose name only starts like the old one is not below it.
                Triple(account("/Photos"), account("/Photos2"), setOf("folder")),
                // Subfolders switched on, alone and together with a deeper folder.
                Triple(account("/Photos", false), account("/Photos", true), setOf("includeSubfolders")),
                Triple(account("/Photos", false), account("/Photos/2024", true), setOf("folder", "includeSubfolders")),
                // The first folder; `""` is stored as `/`.
                Triple(account(""), account("/Photos"), setOf("folder")),
                Triple(account("/Photos"), account(SourceConfigStore.normalizeFolder("")!!), setOf("folder")),
                // Narrowing: up is wider, down with subfolders on and switching them off are not.
                Triple(account("/"), account("/Photos"), emptySet()),
                Triple(account("/Photos"), account("/Photos", false), emptySet()),
                Triple(account("/Photos/2024"), account("/Photos"), setOf("folder")),
                // Nothing changed, and an account without a folder that keeps none.
                Triple(account("/Photos"), account("/Photos"), emptySet()),
                Triple(account(""), account(""), emptySet()),
            )
        for ((prev, next, expected) in cases) {
            assertEquals(
                expected,
                fields(prev, next),
                "${prev.folder} (${prev.includeSubfolders}) → ${next.folder} (${next.includeSubfolders})",
            )
        }
    }

    @Test
    fun `Other settings never widen`() {
        val prev = account("/Photos")
        assertEquals(emptySet<String>(), fields(prev, prev.copy(name = "Family", enabled = false, intervalMinutes = 45)))
        assertEquals(emptySet<String>(), fields(prev, prev.copy(removeVanished = false, originalsBudgetMb = 100)))
    }
}
