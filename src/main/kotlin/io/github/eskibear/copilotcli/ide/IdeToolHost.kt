package io.github.eskibear.copilotcli.ide

/**
 * IDE-side implementation of the five `/ide` tools. The MCP server calls these off the EDT;
 * implementations are responsible for marshalling to the EDT / read actions as needed.
 */
internal interface IdeToolHost {
    fun getDiagnostics(uri: String?): List<IdeDiagnosticFileEntry>

    fun getSelection(): IdeSelectionInfo?

    /** Blocks until the user accepts or rejects the diff. */
    fun openDiff(originalFilePath: String, newFileContents: String, tabName: String): IdeDiffResponse

    fun closeDiff(tabName: String): IdeCloseDiffResponse

    fun updateSessionName(name: String): Boolean
}
