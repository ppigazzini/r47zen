package io.github.ppigazzini.r47zen

import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

/** A [DocumentProvider] that answers as the test tells it to. */
internal class FakeDocumentProvider(
    private val input: (Uri) -> InputStream? = { null },
    private val output: (Uri) -> OutputStream? = { null },
    private val name: (Uri) -> String? = { null },
) : DocumentProvider {
    override fun openInput(uri: Uri): InputStream? = input(uri)

    override fun openOutput(uri: Uri): OutputStream? = output(uri)

    override fun displayName(uri: Uri): String? = name(uri)
}
