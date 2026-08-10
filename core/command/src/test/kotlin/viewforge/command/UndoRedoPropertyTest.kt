package viewforge.command

import viewforge.model.ChildAddress
import viewforge.model.Node
import viewforge.model.NodeId
import viewforge.model.Project
import viewforge.model.findById
import viewforge.model.subtreeContains
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The core undo/redo guarantee (ARCHITECTURE §5, D2: "correct across ≥50 mixed operations"): for any
 * sequence of valid commands, undoing all of them returns the document to its exact initial state, and
 * then redoing all of them returns it to the exact final state — with every intermediate state
 * reproduced along the way.
 *
 * Randomized with several fixed seeds so it explores many op sequences while staying deterministic and
 * debuggable. Only *structural* validity is enforced here (a real container check is a UI concern);
 * that is enough to stress the command/history machinery.
 */
class UndoRedoPropertyTest {
    @Test
    fun `undo-all then redo-all reproduces every state across many mixed ops`() {
        for (seed in listOf(1L, 7L, 42L, 123L, 2024L)) {
            runSequence(seed, ops = 60)
        }
    }

    private fun runSequence(seed: Long, ops: Int) {
        val rnd = Random(seed)
        val history = History()
        val doc0 = Fixtures.project()

        val states = ArrayList<Project>()
        states.add(doc0)
        var doc = doc0
        var counter = 0

        repeat(ops) {
            val command = randomCommand(doc, rnd, counter++)
            doc = history.execute(command, doc)
            states.add(doc)
        }

        // Unwind: each undo must reproduce the immediately prior state, ending exactly at the initial.
        for (i in ops downTo 1) {
            doc = history.undo(doc)
            assertEquals(states[i - 1], doc, "seed=$seed: undo to step ${i - 1}")
        }
        assertEquals(doc0, doc, "seed=$seed: fully unwound equals the initial document")

        // Rewind: each redo must reproduce the next state, ending exactly at the final.
        for (i in 1..ops) {
            doc = history.redo(doc)
            assertEquals(states[i], doc, "seed=$seed: redo to step $i")
        }
        assertEquals(states[ops], doc, "seed=$seed: fully rewound equals the final document")
    }

    /** A structurally valid command against [doc], drawn from the full mutation set. */
    private fun randomCommand(doc: Project, rnd: Random, gen: Int): Command {
        val root = doc.screens.first().root
        val allIds = collectIds(root)
        val nonRootIds = allIds.filter { it != root.id }

        return when (rnd.nextInt(5)) {
            0 -> { // add a fresh Text into some node's default region
                val parent = allIds.random(rnd)
                val childCount = doc.screens.first().root.findById(parent)!!.children.size
                AddNode(
                    Fixtures.SCREEN,
                    ChildAddress(parent, null, rnd.nextInt(childCount + 1)),
                    Node(NodeId("gen_$gen"), "compose.material3.Text"),
                )
            }
            1 -> { // remove a random non-root node (or no-op if none)
                if (nonRootIds.isEmpty()) return RenameNode(Fixtures.SCREEN, root.id, "r$gen")
                RemoveNode(Fixtures.SCREEN, nonRootIds.random(rnd))
            }
            2 -> { // move a non-root node under a parent that isn't in its own subtree
                if (nonRootIds.isEmpty()) return RenameNode(Fixtures.SCREEN, root.id, "r$gen")
                val moved = nonRootIds.random(rnd)
                val movedSubtree = root.findById(moved)!!
                val targets = allIds.filter { !movedSubtree.subtreeContains(it) }
                if (targets.isEmpty()) return RenameNode(Fixtures.SCREEN, moved, "m$gen")
                val target = targets.random(rnd)
                val size = root.findById(target)!!.children.size
                MoveNode(Fixtures.SCREEN, moved, ChildAddress(target, null, rnd.nextInt(size + 1)))
            }
            3 -> RenameNode(Fixtures.SCREEN, allIds.random(rnd), if (rnd.nextBoolean()) "name_$gen" else null)
            else -> SetNodeFlags(
                Fixtures.SCREEN,
                allIds.random(rnd),
                locked = if (rnd.nextBoolean()) rnd.nextBoolean() else null,
                hidden = if (rnd.nextBoolean()) rnd.nextBoolean() else null,
            )
        }
    }

    private fun collectIds(node: Node): List<NodeId> = buildList {
        add(node.id)
        node.children.forEach { addAll(collectIds(it)) }
        node.slots.values.forEach { list -> list.forEach { addAll(collectIds(it)) } }
    }
}
