package com.mikepenz.markdown.model

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Interface to describe the [ReferenceLinkHandler]
 */
@Stable
interface ReferenceLinkHandler {
    /** Keep the provided link */
    fun store(label: String, destination: String?)

    /** Returns the link for the provided label if it exists */
    fun find(label: String): String
}

/**
 * Implementation for [ReferenceLinkHandler] to resolve referenced link within the Markdown.
 *
 * The label is stored lowercase to allow case insensitive lookups. (https://github.com/adam-p/markdown-here/wiki/markdown-cheatsheet#links)
 */
@Stable
class ReferenceLinkHandlerImpl : ReferenceLinkHandler {
    // Copied on write: a streaming state stores links from its parse thread while composition reads them.
    private val stored = MutableStateFlow<Map<String, String?>>(emptyMap())

    override fun store(label: String, destination: String?) {
        stored.update { it + (label.lowercase() to destination) }
    }

    override fun find(label: String): String {
        return stored.value[label.lowercase()] ?: ""
    }
}
