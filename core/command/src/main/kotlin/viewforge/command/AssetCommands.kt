package viewforge.command

import viewforge.model.Asset
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.PropValue

/**
 * Imported-asset commands (D-assets, ADR-021, DATA_MODEL §9). An [Asset] is a top-level document
 * entry — like a [Screen] or a [ComponentDef] — so [AddAsset]/[RemoveAsset] transform
 * [Project.assets] directly, mirroring [AddComponent]/[RemoveComponent]. Nodes reference an asset by
 * id via a [PropValue.ResourceRef]; the id is the thin reference and the bytes live in the project's
 * sidecar `assets/` directory (copied on import, never inlined into the IR).
 *
 * Like every mutation these flow through [Command] (CLAUDE.md rule 3), so importing an asset — and the
 * prop rebind that points a node at it — is one undoable step. The commands stay **pure**
 * Project→Project transforms: the on-disk byte copy is the caller's job (it happens before the command
 * runs), so undo/redo never touches the filesystem — an undone import leaves the copied file in place
 * (harmless, unreferenced) exactly as an unused classpath asset would sit.
 */

/**
 * Insert [asset] at [index] in the asset list. [index] is clamped into range on apply, so a large index
 * appends (how [importAsset] adds a freshly imported asset). A duplicate id ⇒ a no-op with a no-op
 * inverse, keeping [History] consistent (re-importing the identical file reuses the existing entry
 * upstream, but the guard makes the command idempotent regardless). The inverse removes it again.
 */
data class AddAsset(val asset: Asset, val index: Int, override val label: String = "Import asset") : Command {
    override fun apply(doc: Project): Project {
        if (doc.assets.any { it.id == asset.id }) return doc
        val at = index.coerceIn(0, doc.assets.size)
        val assets = doc.assets.toMutableList().apply { add(at, asset) }
        return doc.copy(assets = assets)
    }

    override fun invert(doc: Project): Command {
        // If the id is already present in the pre-apply document, apply was a no-op, so undo must be too.
        return if (doc.assets.any { it.id == asset.id }) NoOp else RemoveAsset(asset.id)
    }
}

/**
 * Remove the asset [id]. The inverse restores it to its exact position, so [invert] reads the asset and
 * its index out of the pre-apply document (like [RemoveComponent]). Absent id ⇒ a no-op with a no-op
 * inverse. Mechanical only — it does not rewrite any `ResourceRef` still naming [id], nor delete the
 * copied file on disk; the editor owns not orphaning references, exactly as [RemoveComponent] does not
 * chase inbound instances.
 */
data class RemoveAsset(val id: String, override val label: String = "Remove asset") : Command {
    override fun apply(doc: Project): Project {
        if (doc.assets.none { it.id == id }) return doc
        return doc.copy(assets = doc.assets.filterNot { it.id == id })
    }

    override fun invert(doc: Project): Command {
        val index = doc.assets.indexOfFirst { it.id == id }
        val asset = doc.assets.getOrNull(index)
        return if (asset != null) AddAsset(asset, index) else NoOp
    }
}

/**
 * Import [asset] and point node [nodeId] (in the root [rootId], a screen or component) at it, as one
 * undoable step: the composite appends the asset definition, then sets the node's [propName] prop to a
 * [PropValue.ResourceRef] naming it. Undo reverses both — the prop reverts and the asset entry is
 * removed together. The bytes are copied to disk by the caller before this runs (the command stays pure);
 * see [AddAsset].
 */
fun importAsset(rootId: String, nodeId: NodeId, propName: String, asset: Asset): Command = CompositeCommand(
    commands = listOf(
        AddAsset(asset, index = Int.MAX_VALUE),
        SetProp(rootId, nodeId, propName, PropValue.ResourceRef(asset.id)),
    ),
    label = "Import image",
)
