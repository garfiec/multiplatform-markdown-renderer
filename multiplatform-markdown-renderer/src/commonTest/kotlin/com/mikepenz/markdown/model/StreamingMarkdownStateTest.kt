package com.mikepenz.markdown.model

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingMarkdownStateTest {
    @Test
    fun initialStateExposesAllEmptyFieldsAndProvidedReferenceHandler() {
        val referenceLinkHandler = ReferenceLinkHandlerImpl()
        val state = streamingState(referenceLinkHandler = referenceLinkHandler)

        assertEquals("", state.content.toString())
        assertSame(referenceLinkHandler, state.referenceLinkHandler)
        assertTrue(state.snapshot.value.stableAst.isEmpty())
        assertTrue(state.snapshot.value.unstableAstTail.isEmpty())
        assertTrue(state.links.value.isEmpty())
    }

    @Test
    fun appendUpdatesContentSnapshotAndReturnedSnapshot() = runTest {
        val state = streamingState()

        val returned = state.append("draft")

        assertEquals("draft", state.content.toString())
        assertSame(returned, state.snapshot.value)
        assertTrue(returned.stableAst.isEmpty())
        assertEquals(listOf(MarkdownElementTypes.PARAGRAPH), returned.unstableAstTail.types())
    }

    @Test
    fun appendKeepsContentAndStableNodeReferencesAcrossUnstableTailUpdates() = runTest {
        val state = streamingState()

        val completed = state.append("first paragraph\n\n")
        val stableParagraph = completed.stableAst.single { it.type == MarkdownElementTypes.PARAGRAPH }
        val contentReference = state.content

        val withTail = state.append("second paragraph")

        assertEquals("first paragraph\n\nsecond paragraph", state.content.toString())
        assertSame(contentReference, state.content)
        assertSame(stableParagraph, withTail.stableAst.single { it.type == MarkdownElementTypes.PARAGRAPH })
        assertEquals(listOf(MarkdownElementTypes.PARAGRAPH), withTail.unstableAstTail.types())
    }

    @Test
    fun emptyAppendKeepsFieldsConsistent() = runTest {
        val state = streamingState()
        state.append("body")
        val contentReference = state.content

        val returned = state.append("")

        assertEquals("body", state.content.toString())
        assertSame(contentReference, state.content)
        assertSame(returned, state.snapshot.value)
    }

    @Test
    fun snapshotFlowEmitsInitialAndChangedSnapshotsForCollectors() = runTest {
        val state = streamingState()
        val snapshots = mutableListOf<StreamingMarkdownState.Snapshot>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.snapshot.collect { snapshots += it }
        }

        val stableSnapshot = state.append("stable paragraph\n\n")
        val tailSnapshot = state.append("tail paragraph")
        advanceUntilIdle()

        assertEquals(3, snapshots.size)
        assertTrue(snapshots[0].stableAst.isEmpty())
        assertTrue(snapshots[0].unstableAstTail.isEmpty())
        assertSame(stableSnapshot, snapshots[1])
        assertSame(tailSnapshot, snapshots[2])
        assertSame(tailSnapshot, state.snapshot.value)
        assertTrue(snapshots[1].stableAst.any { it.type == MarkdownElementTypes.PARAGRAPH })
        assertTrue(snapshots[1].unstableAstTail.isEmpty())
        assertTrue(snapshots[2].stableAst.any { it.type == MarkdownElementTypes.PARAGRAPH })
        assertEquals(listOf(MarkdownElementTypes.PARAGRAPH), snapshots[2].unstableAstTail.types())
    }

    @Test
    fun snapshotFlowDoesNotEmitAgainWhenAppendProducesEqualSnapshot() = runTest {
        val state = streamingState()
        val snapshots = mutableListOf<StreamingMarkdownState.Snapshot>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.snapshot.collect { snapshots += it }
        }

        state.append("body")
        val countAfterContent = snapshots.size
        val returned = state.append("")
        advanceUntilIdle()

        assertEquals(2, countAfterContent)
        assertEquals(countAfterContent, snapshots.size)
        assertSame(returned, state.snapshot.value)
    }

    @Test
    fun lookupLinksStoresStableAndUnstableDefinitionsWhenEnabled() = runTest {
        val referenceLinkHandler = ReferenceLinkHandlerImpl()
        val state = streamingState(lookupLinks = true, referenceLinkHandler = referenceLinkHandler)

        state.append("[stable]: https://stable.example\n\n")
        state.append("[tail]: https://tail.example")

        assertEquals("https://stable.example", state.links.value["[stable]"])
        assertEquals("https://tail.example", state.links.value["[tail]"])
        assertEquals("https://stable.example", referenceLinkHandler.find("[stable]"))
        assertEquals("https://tail.example", referenceLinkHandler.find("[tail]"))

        state.append("\n\nbody")

        assertEquals("https://stable.example", state.links.value["[stable]"])
        assertEquals("https://tail.example", state.links.value["[tail]"])
    }

    @Test
    fun linksFlowEmitsInitialAndChangedLinksForCollectorsWhenLookupEnabled() = runTest {
        val state = streamingState(lookupLinks = true)
        val links = mutableListOf<Map<String, String?>>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.links.collect { links += it }
        }

        state.append("[stable]: https://stable.example\n\n")
        state.append("[tail]: https://tail.example")
        state.append("\n\nbody")
        advanceUntilIdle()

        assertEquals(3, links.size)
        assertTrue(links[0].isEmpty())
        assertEquals(mapOf("[stable]" to "https://stable.example"), links[1])
        assertEquals(
            mapOf(
                "[stable]" to "https://stable.example",
                "[tail]" to "https://tail.example",
            ),
            links[2]
        )
        assertEquals(links.last(), state.links.value)
    }

    @Test
    fun lookupLinksLeavesLinkFieldsEmptyWhenDisabled() = runTest {
        val referenceLinkHandler = ReferenceLinkHandlerImpl()
        val state = streamingState(lookupLinks = false, referenceLinkHandler = referenceLinkHandler)

        state.append("[ignored]: https://ignored.example\n\n")

        assertTrue(state.links.value.isEmpty())
        assertEquals("", referenceLinkHandler.find("[ignored]"))
    }

    @Test
    fun linksFlowDoesNotEmitWhenLookupDisabled() = runTest {
        val state = streamingState(lookupLinks = false)
        val links = mutableListOf<Map<String, String?>>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.links.collect { links += it }
        }

        state.append("[ignored]: https://ignored.example\n\n")
        state.append("body")
        advanceUntilIdle()

        assertEquals(listOf(emptyMap()), links)
        assertTrue(state.links.value.isEmpty())
    }

    @Test
    fun everySnapshotDrawsWhatAParseOfTheWholeTextDraws() {
        for (step in listOf(1, 7, 40)) {
            assertSnapshotsMatchFullParse(EVERYTHING, step, caret = null)
            assertSnapshotsMatchFullParse(EVERYTHING, step, caret = CARET)
        }
    }

    @Test
    fun stableBlocksKeepOnlyTheTextTheyDraw() {
        val state = streamingState(chunkSize = 4)
        var end = 0
        while (end < EVERYTHING.length) {
            end = minOf(EVERYTHING.length, end + 7)
            state.updateNow(EVERYTHING.substring(0, end), CARET)
        }
        val held = state.snapshot.value.chunks.flatMap { it.blocks }.map { it.content }.distinctBy { it.identity() }
        assertTrue(held.sumOf { it.length } < 2 * EVERYTHING.length, "held ${held.sumOf { it.length }} chars")
    }

    @Test
    fun blocksBeforeTheOneBeingWrittenKeepTheirInstance() {
        val state = streamingState()
        val first = state.updateNow("# Title\n\nFirst paragraph.\n\nSecond par", CARET).blocks()
        val second = state.updateNow("# Title\n\nFirst paragraph.\n\nSecond paragraph grows", CARET).blocks()

        assertEquals(first.size, second.size)
        for (i in 0 until first.lastIndex) assertSame(first[i], second[i])
        assertNotSame(first.last(), second.last())
    }

    @Test
    fun anOpenListIsKeptWhileTheHeadingAfterItIsWritten() {
        val state = streamingState()
        val list = "- one\n- two\n\n"
        val before = state.updateNow(list + "## He", CARET).blocks()
        val after = state.updateNow(list + "## Heading", CARET).blocks()

        assertSame(before.first(), after.first())
        assertEquals(MarkdownElementTypes.UNORDERED_LIST, after.first().node.type)
    }

    @Test
    fun aBlockWhoseTextALaterAppendChangesIsReplaced() {
        val state = streamingState()
        val before = state.updateNow("Title\n\nnext", caret = null).blocks()
        val setext = state.updateNow("Title\n===\n\nnext", caret = null)

        assertEquals(fullDump("Title\n===\n\nnext"), setext.dump())
        assertNotSame(before.first(), setext.blocks().first())
    }

    @Test
    fun sealedChunksKeepTheirInstance() {
        val state = streamingState(chunkSize = 2)
        val text = (1..6).joinToString("\n\n") { "Paragraph $it." }
        val before = state.updateNow(text, CARET)
        val after = state.updateNow("$text\n\nMore", CARET)

        assertTrue(before.chunks.size > 2)
        assertSame(before.chunks.first(), after.chunks.first())
        assertEquals(fullDump("$text\n\nMore$CARET"), after.dump())
    }

    @Test
    fun textThatDoesNotExtendTheDocumentStartsOver() = runTest {
        val state = streamingState(chunkSize = 2)
        state.update("One.\n\nTwo.\n\nThree.\n\nFour.\n\nFive", CARET)
        val snapshot = state.update("Other.\n\nText", CARET)

        assertEquals(fullDump("Other.\n\nText$CARET"), snapshot.dump())
        assertEquals("Other.\n\nText", state.content.toString())
    }

    @Test
    fun theCaretStaysOnTheLastVisibleBlockThroughTrailingBlankLines() {
        val state = streamingState(chunkSize = 2)
        for (text in listOf("One.\n\nTwo.", "One.\n\nTwo.\n", "One.\n\nTwo.\n\n", "One.\n\nTwo.\n\n \n", "One.\n\nTwo.\n\nThree")) {
            val snapshot = state.updateNow(text, CARET)
            assertEquals(fullDump(text.trimEnd() + CARET), snapshot.dump(), text)
            assertTrue(snapshot.blocks().last().content.endsWith(CARET), text)
            assertEquals(1, snapshot.blocks().count { CARET in it.content }, text)
        }
    }

    @Test
    fun droppingTheCaretRedrawsTheSameTextWithoutIt() = runTest {
        val state = streamingState()
        state.update("One.\n\nTwo", CARET)
        val snapshot = state.update("One.\n\nTwo")

        assertEquals(fullDump("One.\n\nTwo"), snapshot.dump())
    }

    /** Streams [text] in [step]-character updates and checks every snapshot against a parse of the whole. */
    private fun assertSnapshotsMatchFullParse(text: String, step: Int, caret: String?) {
        val state = streamingState(chunkSize = 4)
        var end = 0
        while (end < text.length) {
            end = minOf(text.length, end + step)
            val prefix = text.substring(0, end)
            val snapshot = state.updateNow(prefix, caret)
            val drawn = if (caret != null) prefix.trimEnd() + caret else prefix
            val want = fullDump(drawn)
            val got = snapshot.dump()
            val at = (0 until maxOf(want.size, got.size)).firstOrNull { want.getOrNull(it) != got.getOrNull(it) }
            if (at != null) {
                fail("step=$step caret=${caret != null} at $end, block $at:\n want ${want.getOrNull(at)}\n got  ${got.getOrNull(at)}")
            }
            snapshot.blocks().forEach { block ->
                val length = block.node.endOffset - block.node.startOffset
                assertEquals(drawn.substring(block.key, block.key + length), block.content.substring(block.node.startOffset, block.node.endOffset))
            }
        }
    }

    private val fullParser = MarkdownParser(GFMFlavourDescriptor())

    private fun fullDump(text: String): List<String> =
        fullParser.buildMarkdownTreeFromString(text).children.map { it.dump(0) }

    private fun StreamingMarkdownState.Snapshot.blocks(): List<StreamingMarkdownBlock> = chunks.flatMap { it.blocks }

    private fun StreamingMarkdownState.Snapshot.dump(): List<String> = blocks().map { it.node.dump(it.key - it.node.startOffset) }

    /** Every node as `type@start..end`, with offsets moved by [shift] into the whole text. */
    private fun ASTNode.dump(shift: Int, out: StringBuilder = StringBuilder()): String {
        out.append(type.name).append('@').append(startOffset + shift).append("..").append(endOffset + shift).append('(')
        children.forEach { it.dump(shift, out) }
        return out.append(')').toString()
    }

    private fun Any.identity(): Any = IdentityKey(this)

    private class IdentityKey(val value: Any) {
        override fun equals(other: Any?) = other is IdentityKey && other.value === value
        override fun hashCode() = value.hashCode()
    }

    private fun streamingState(
        lookupLinks: Boolean = true,
        referenceLinkHandler: ReferenceLinkHandler = ReferenceLinkHandlerImpl(),
        chunkSize: Int = STREAMING_CHUNK_BLOCKS,
    ): StreamingMarkdownStateImpl = StreamingMarkdownStateImpl(
        lookupLinks = lookupLinks,
        flavour = GFMFlavourDescriptor(),
        referenceLinkHandler = referenceLinkHandler,
        chunkSize = chunkSize,
    )

    private fun List<ASTNode>.types() = map { it.type }

    private companion object {
        const val CARET = "\u2063"

        /** Constructs whose earlier blocks a later line reinterprets, among ordinary ones. */
        val EVERYTHING = """
            # Heading

            A paragraph with **bold**, *emphasis*, `code` and a [link](https://example.com).
            A lazy second line.

            Setext title
            ============

            Another setext
            ---

            - tight one
            - tight two
              continued
            - tight three

            Paragraph right after a list.
            - list interrupting a paragraph
            - second

            1. loose one

            2. loose two

               with a second paragraph

            3. loose three

            > quote line one
            lazy continuation
            > > nested

            ```kotlin
            fun main() {

                println("blank line inside")
            }
            ```

                indented code

                more indented code

            | a | b |
            |---|---|
            | 1 | 2 |

            <div>
            html block

            </div>

            See [ref] and [other][ref].

            [ref]: https://example.com "Title"

            ***

            Trailing paragraph with a hard break  
            and the end.

            - [ ] task
            - [x] done

            Final line
        """.trimIndent() + "\n\n  \n"
    }
}
