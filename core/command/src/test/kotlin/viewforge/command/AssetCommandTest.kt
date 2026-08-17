package viewforge.command

import viewforge.command.Fixtures.rootOf
import viewforge.model.Asset
import viewforge.model.PropValue
import viewforge.model.findById
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The imported-asset commands (ADR-021, DATA_MODEL §9). Each must apply cleanly and invert back to the
 * exact prior state so undo/redo round-trips; [importAsset] is the composite that adds the asset and
 * rebinds a node's prop to it in one undoable step. Guards (duplicate/absent id) collapse to a no-op
 * inverse rather than corrupting history. The commands are pure document transforms — no disk I/O.
 */
class AssetCommandTest {
    private fun asset(id: String, name: String = "$id.png") =
        Asset(id = id, type = "image", path = "assets/$name", originalName = name, width = 10, height = 20)

    @Test
    fun `AddAsset inserts at the index and inverts by removing it`() {
        val before = Fixtures.project().copy(assets = listOf(asset("a1"), asset("a2")))
        val cmd = AddAsset(asset("a3"), index = 1)
        val after = cmd.apply(before)
        assertEquals(listOf("a1", "a3", "a2"), after.assets.map { it.id })

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.assets, restored.assets)
    }

    @Test
    fun `AddAsset clamps an out-of-range index to an append`() {
        val before = Fixtures.project().copy(assets = listOf(asset("a1")))
        val after = AddAsset(asset("a2"), index = Int.MAX_VALUE).apply(before)
        assertEquals(listOf("a1", "a2"), after.assets.map { it.id })
    }

    @Test
    fun `AddAsset of a duplicate id is a no-op with a no-op inverse`() {
        val before = Fixtures.project().copy(assets = listOf(asset("a1")))
        val cmd = AddAsset(asset("a1"), index = 0)
        assertEquals(before.assets, cmd.apply(before).assets)
        assertTrue(cmd.invert(before) is NoOp)
    }

    @Test
    fun `RemoveAsset removes it and inverts by restoring it at its old index`() {
        val before = Fixtures.project().copy(assets = listOf(asset("a1"), asset("a2")))
        val cmd = RemoveAsset("a1") // remove the first so restoring at index 0 is meaningful
        val after = cmd.apply(before)
        assertEquals(listOf("a2"), after.assets.map { it.id })

        val restored = cmd.invert(before).apply(after)
        assertEquals(before.assets, restored.assets)
    }

    @Test
    fun `RemoveAsset of an absent id is a no-op with a no-op inverse`() {
        val before = Fixtures.project().copy(assets = listOf(asset("a1")))
        assertEquals(before.assets, RemoveAsset("nope").apply(before).assets)
        assertTrue(RemoveAsset("nope").invert(before) is NoOp)
    }

    @Test
    fun `importAsset adds the asset and points the node at it, undoing exactly`() {
        val before = Fixtures.project()
        val imported = asset("img1", "hero.png")
        val cmd = importAsset(Fixtures.SCREEN, Fixtures.text.id, "source", imported)
        val after = cmd.apply(before)

        // The asset is in the document...
        assertEquals(listOf(imported), after.assets)
        // ...and the target node's prop now references it by id.
        assertEquals(
            PropValue.ResourceRef("img1"),
            after.rootOf().findById(Fixtures.text.id)?.props?.get("source"),
        )

        // One undo removes the asset and restores the exact original screen.
        val restored = cmd.invert(before).apply(after)
        assertEquals(before.assets, restored.assets)
        assertEquals(before.rootOf(), restored.rootOf())
    }
}
