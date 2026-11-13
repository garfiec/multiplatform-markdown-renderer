package com.mikepenz.markdown.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.mikepenz.markdown.utils.lookupLinkDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.EmptyStreamingMarkdownFile
import org.intellij.markdown.parser.MarkdownParser
import org.intellij.markdown.parser.StreamingMarkdownFile
import kotlin.coroutines.CoroutineContext

/**
 * Creates a [StreamingMarkdownState] outside composition, for example to keep it in a view model.
 * Its parses run in [parseContext].
 */
fun StreamingMarkdownState(
    lookupLinks: Boolean = true,
    flavour: MarkdownFlavourDescriptor = GFMFlavourDescriptor(),
    referenceLinkHandler: ReferenceLinkHandler = ReferenceLinkHandlerImpl(),
    parseContext: CoroutineContext = Dispatchers.Default,
): StreamingMarkdownState = StreamingMarkdownStateImpl(
    lookupLinks = lookupLinks,
    flavour = flavour,
    referenceLinkHandler = referenceLinkHandler,
    parseContext = parseContext,
)

@Composable
fun rememberStreamingMarkdownState(
    lookupLinks: Boolean = true,
    flavour: MarkdownFlavourDescriptor = remember { GFMFlavourDescriptor() },
    referenceLinkHandler: ReferenceLinkHandler = remember { ReferenceLinkHandlerImpl() },
    parseContext: CoroutineContext = Dispatchers.Default,
): StreamingMarkdownState = remember(lookupLinks, flavour, referenceLinkHandler, parseContext) {
    StreamingMarkdownStateImpl(
        lookupLinks = lookupLinks,
        flavour = flavour,
        referenceLinkHandler = referenceLinkHandler,
        parseContext = parseContext,
    )
}

/**
 * A [StreamingMarkdownState] that follows [content], a text that grows as it streams in.
 *
 * The first [content] is parsed during composition, so the first frame is never empty. Every later
 * one is parsed in [parseContext]: a text that extends the previous one costs only the part that
 * can still change, and any other text starts the document over. Texts that arrive while a parse
 * runs are conflated to the latest.
 *
 * @param caret Text drawn right after the last visible character, such as a typing cursor. It is
 *   parsed with the open end of the document only and never becomes part of it, so it can change or
 *   go away on any update.
 */
@Composable
fun rememberStreamingMarkdownState(
    content: String,
    caret: String? = null,
    lookupLinks: Boolean = true,
    flavour: MarkdownFlavourDescriptor = remember { GFMFlavourDescriptor() },
    referenceLinkHandler: ReferenceLinkHandler = remember { ReferenceLinkHandlerImpl() },
    parseContext: CoroutineContext = Dispatchers.Default,
): StreamingMarkdownState {
    val state = remember(lookupLinks, flavour, referenceLinkHandler, parseContext) {
        StreamingMarkdownStateImpl(
            lookupLinks = lookupLinks,
            flavour = flavour,
            referenceLinkHandler = referenceLinkHandler,
            parseContext = parseContext,
        ).also { it.updateNow(content, caret) }
    }
    val latest by rememberUpdatedState(content to caret)
    LaunchedEffect(state) {
        snapshotFlow { latest }.conflate().collect { (text, latestCaret) -> state.update(text, latestCaret) }
    }
    return state
}

/**
 * Collects append-only markdown chunks from this [Flow] into a remembered [StreamingMarkdownState].
 */
@Composable
fun Flow<String>.collectAsStreamingMarkdownState(
    lookupLinks: Boolean = true,
    flavour: MarkdownFlavourDescriptor = remember { GFMFlavourDescriptor() },
    referenceLinkHandler: ReferenceLinkHandler = remember { ReferenceLinkHandlerImpl() },
): StreamingMarkdownState {
    val markdownState = rememberStreamingMarkdownState(
        lookupLinks = lookupLinks,
        flavour = flavour,
        referenceLinkHandler = referenceLinkHandler,
    )

    LaunchedEffect(this, markdownState) {
        collect { chunk ->
            markdownState.append(chunk)
        }
    }

    return markdownState
}

@Stable
interface StreamingMarkdownState {
    /**
     * The accumulated markdown content. It is written while appending, off the composition thread:
     * composition reads [Snapshot.content] instead.
     */
    val content: CharSequence

    /** The current state of the markdown parsing */
    val snapshot: StateFlow<Snapshot>

    /** The links found in the markdown content */
    val links: StateFlow<Map<String, String?>>

    /** The [ReferenceLinkHandler] to store links in. */
    val referenceLinkHandler: ReferenceLinkHandler

    /**
     * Appends [chunk] to the document.
     *
     * Callers should append chunks in source order.
     */
    suspend fun append(chunk: String): Snapshot

    /**
     * Makes [text] the whole document. A text that extends the current one appends the difference;
     * any other text starts over. [caret] is drawn after the last visible character of this snapshot
     * only, see [rememberStreamingMarkdownState].
     */
    suspend fun update(text: String, caret: String? = null): Snapshot

    @Immutable
    data class Snapshot(
        /** Nodes no later append can change. Their offsets index [content]. */
        val stableAst: List<ASTNode>,
        /** Nodes the next append re-parses. Their offsets index [content]. */
        val unstableAstTail: List<ASTNode>,
        /** The text this snapshot was parsed from. */
        val content: String = "",
        /**
         * The document as runs of blocks, each drawn from its own text. A run or block that did not
         * change since the previous snapshot is the same instance, so composition can skip it.
         */
        val chunks: List<StreamingMarkdownChunk> = emptyList(),
    )
}

/** One top-level node of a streaming document. [node]'s offsets index [content]. */
@Immutable
class StreamingMarkdownBlock internal constructor(
    /** Where the block starts in the whole document: its identity across snapshots. */
    val key: Int,
    val node: ASTNode,
    val content: String,
)

/** A run of consecutive blocks, drawn as one unit so that a run that did not change is skipped as a whole. */
@Immutable
class StreamingMarkdownChunk internal constructor(
    /** The run's position in the document: its identity across snapshots. */
    val index: Int,
    val blocks: List<StreamingMarkdownBlock>,
)

@Stable
internal class StreamingMarkdownStateImpl(
    private val lookupLinks: Boolean,
    private val flavour: MarkdownFlavourDescriptor,
    override val referenceLinkHandler: ReferenceLinkHandler,
    private val parseContext: CoroutineContext = Dispatchers.Default,
    private val chunkSize: Int = STREAMING_CHUNK_BLOCKS,
) : StreamingMarkdownState {
    internal constructor(input: Input, parseContext: CoroutineContext = Dispatchers.Default) : this(
        lookupLinks = input.lookupLinks,
        flavour = input.flavour,
        referenceLinkHandler = input.referenceLinkHandler,
        parseContext = parseContext,
    )

    override val content: StringBuilder = StringBuilder()
    private val snapshotStateFlow: MutableStateFlow<StreamingMarkdownState.Snapshot> = MutableStateFlow(
        StreamingMarkdownState.Snapshot(emptyList(), emptyList()),
    )
    private val linkStateFlow: MutableStateFlow<Map<String, String?>> = MutableStateFlow(emptyMap())
    private val appendMutex = Mutex()

    // Everything below is touched only while holding appendMutex, or before the state is shared.
    private val parser = MarkdownParser(flavour)
    private var streamingFile: StreamingMarkdownFile = EmptyStreamingMarkdownFile(flavour)
    private var text = ""
    private var caret: String? = null
    private val stableLinks = mutableMapOf<String, String?>()
    private var processedStableChildren = 0
    private var absorbedStableChildren = 0
    private val sealed = ArrayList<StreamingMarkdownChunk>()

    /** Stable blocks not yet in a sealed chunk. */
    private val pending = ArrayList<StreamingMarkdownBlock>()
    private var unsealed: List<StreamingMarkdownChunk> = emptyList()

    // FIXME: use explicit backing fields with Kotlin 2.4
    override val snapshot: StateFlow<StreamingMarkdownState.Snapshot>
        get() = snapshotStateFlow.asStateFlow()
    // FIXME: use explicit backing fields with Kotlin 2.4
    override val links: StateFlow<Map<String, String?>>
        get() = linkStateFlow.asStateFlow()

    override suspend fun append(chunk: String): StreamingMarkdownState.Snapshot = appendMutex.withLock {
        if (chunk.isEmpty()) return@withLock snapshotStateFlow.value
        withContext(parseContext) {
            content.append(chunk)
            advance(chunk, content.toString(), caret)
        }
    }

    override suspend fun update(text: String, caret: String?): StreamingMarkdownState.Snapshot = appendMutex.withLock {
        if (text == this.text && caret == this.caret) return@withLock snapshotStateFlow.value
        withContext(parseContext) { updateNow(text, caret) }
    }

    /** [update] on the calling thread. Only for a state no other thread can reach yet. */
    internal fun updateNow(text: String, caret: String?): StreamingMarkdownState.Snapshot {
        if (!text.startsWith(this.text)) reset()
        val delta = text.substring(this.text.length)
        content.append(delta)
        return advance(delta, text, caret)
    }

    private fun reset() {
        content.clear()
        streamingFile = EmptyStreamingMarkdownFile(flavour)
        text = ""
        stableLinks.clear()
        processedStableChildren = 0
        absorbedStableChildren = 0
        sealed.clear()
        pending.clear()
        unsealed = emptyList()
    }

    /** Parses [delta], the end of [text], and publishes the snapshot for [text]. */
    private fun advance(delta: String, text: String, caret: String?): StreamingMarkdownState.Snapshot {
        streamingFile.append(delta)
        this.text = text
        this.caret = caret

        val stableChildren = streamingFile.stableChildren
        val unstableTail = streamingFile.unstableTail
        if (lookupLinks) {
            val links = lookupLinks(stableChildren, unstableTail)
            links.forEach { (key, value) -> referenceLinkHandler.store(key, value) }
            linkStateFlow.value = links
        }

        val previous = HashMap<Int, StreamingMarkdownBlock>()
        unsealed.forEach { chunk -> chunk.blocks.forEach { previous[it.key] = it } }
        if (absorbedStableChildren < stableChildren.size) {
            pending += settle(stableChildren.subList(absorbedStableChildren, stableChildren.size))
            absorbedStableChildren = stableChildren.size
        }
        val blocks = ArrayList<StreamingMarkdownBlock>(pending.size + unstableTail.size)
        if (caret == null) {
            blocks += pending
            unstableTail.forEach { blocks += block(it, text, base = 0, previous) }
        } else {
            openBlocksWithCaret(unstableTail, caret, previous, blocks)
        }

        seal(blocks)
        unsealed = chunked(blocks)
        val next = StreamingMarkdownState.Snapshot(
            stableAst = stableChildren.toList(),
            unstableAstTail = unstableTail.toList(),
            content = text,
            chunks = sealed + unsealed,
        )
        snapshotStateFlow.value = next
        return snapshotStateFlow.value
    }

    /**
     * Blocks for [nodes], which just became stable. They are parsed again from their own text, which
     * a stable node's tree does not depend on beyond, so a block keeps only the text it draws.
     */
    private fun settle(nodes: List<ASTNode>): List<StreamingMarkdownBlock> {
        val start = nodes.first().startOffset
        val span = text.substring(start, nodes.last().endOffset)
        val own = parser.buildMarkdownTreeFromString(span).children
        val parsedAlike = own.size == nodes.size && own.indices.all { nodes[it].sameTreeAs(own[it], shift = -start) }
        return nodes.mapIndexed { i, node ->
            if (parsedAlike) {
                StreamingMarkdownBlock(node.startOffset, own[i], span)
            } else {
                StreamingMarkdownBlock(node.startOffset, node, text)
            }
        }
    }

    /**
     * Adds the stable blocks and the open end of the document to [into], with [caret] after the last
     * visible character. When nothing visible is open, the caret trails the last visible stable
     * block, which is parsed again with it.
     */
    private fun openBlocksWithCaret(
        unstableTail: List<ASTNode>,
        caret: String,
        previous: Map<Int, StreamingMarkdownBlock>,
        into: ArrayList<StreamingMarkdownBlock>,
    ) {
        var openStart = unstableTail.firstOrNull()?.startOffset ?: text.length
        var kept = pending.size
        if (text.isBlankFrom(openStart)) {
            val last = pending.indexOfLast { !it.isBlank }
            if (last >= 0) {
                kept = last
                openStart = pending[last].key
            }
        }
        for (i in 0 until kept) into += pending[i]
        val open = text.substring(openStart).trimEnd() + caret
        parser.buildMarkdownTreeFromString(open).children.forEach { into += block(it, open, base = openStart, previous) }
    }

    /**
     * Moves full chunks of stable blocks out of [blocks] and [pending]. The last visible stable block
     * and everything after it stay out, since a caret can move back onto that block.
     */
    private fun seal(blocks: ArrayList<StreamingMarkdownBlock>) {
        var sealable = pending.indexOfLast { !it.isBlank }
        while (sealable >= chunkSize) {
            sealed += StreamingMarkdownChunk(sealed.size, pending.subList(0, chunkSize).toList())
            pending.subList(0, chunkSize).clear()
            blocks.subList(0, chunkSize).clear()
            sealable -= chunkSize
        }
    }

    private fun chunked(blocks: List<StreamingMarkdownBlock>): List<StreamingMarkdownChunk> {
        val before = unsealed.associateBy { it.index }
        return blocks.chunked(chunkSize).mapIndexed { i, group ->
            val index = sealed.size + i
            before[index]?.takeIf { it.blocks.sameInstances(group) } ?: StreamingMarkdownChunk(index, group)
        }
    }

    private fun lookupLinks(
        stableChildren: List<ASTNode>,
        unstableTail: List<ASTNode>,
    ): Map<String, String?> {
        while (processedStableChildren < stableChildren.size) {
            lookupLinkDefinition(stableLinks, stableChildren[processedStableChildren], text, recursive = true)
            processedStableChildren += 1
        }
        return stableLinks.toMutableMap().also { links ->
            unstableTail.forEach { node ->
                lookupLinkDefinition(links, node, text, recursive = true)
            }
        }
    }
}

/** Blocks per [StreamingMarkdownChunk], line breaks included: about a screen of prose. */
internal const val STREAMING_CHUNK_BLOCKS = 24

/**
 * The block for [node], whose offsets index [source], which starts at [base] in the document. The
 * block [previous] holds at the same place is returned instead when it draws the same text and tree.
 */
private fun block(
    node: ASTNode,
    source: String,
    base: Int,
    previous: Map<Int, StreamingMarkdownBlock>,
): StreamingMarkdownBlock {
    val key = base + node.startOffset
    val before = previous[key]
    if (before != null &&
        before.node.endOffset - before.node.startOffset == node.endOffset - node.startOffset &&
        source.regionMatches(node.startOffset, before.content, before.node.startOffset, node.endOffset - node.startOffset) &&
        before.node.sameTreeAs(node, shift = node.startOffset - before.node.startOffset)
    ) {
        return before
    }
    return StreamingMarkdownBlock(key, node, source)
}

/** A line break or whitespace between blocks: nothing a caret can trail. */
private val StreamingMarkdownBlock.isBlank: Boolean
    get() = node.children.isEmpty() && content.isBlankFrom(node.startOffset, node.endOffset)

/** Whether [other] is this tree moved by [shift]: the same types and ranges all the way down. */
private fun ASTNode.sameTreeAs(other: ASTNode, shift: Int): Boolean {
    if (type != other.type || startOffset + shift != other.startOffset || endOffset + shift != other.endOffset) {
        return false
    }
    val mine = children
    val theirs = other.children
    if (mine.size != theirs.size) return false
    for (i in mine.indices) if (!mine[i].sameTreeAs(theirs[i], shift)) return false
    return true
}

private fun List<StreamingMarkdownBlock>.sameInstances(other: List<StreamingMarkdownBlock>): Boolean {
    if (size != other.size) return false
    for (i in indices) if (this[i] !== other[i]) return false
    return true
}

private fun String.isBlankFrom(start: Int, end: Int = length): Boolean {
    for (i in start until end) if (!this[i].isWhitespace()) return false
    return true
}
