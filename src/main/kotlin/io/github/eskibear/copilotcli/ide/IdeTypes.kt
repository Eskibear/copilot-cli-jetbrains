package io.github.eskibear.copilotcli.ide

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName

/**
 * Wire types for the Copilot CLI `/ide` MCP integration.
 *
 * Casing matters and is enforced with [SerializedName]:
 *  - `open_diff` / `close_diff` arguments and responses use snake_case.
 *  - selection / diagnostic / notification payloads use camelCase.
 *
 * The CLI validates every payload strictly and silently drops fields that do not match,
 * so the names below must be kept exactly in sync with the protocol contract.
 */

/** Shared Gson. HTML escaping is disabled so file paths / URLs serialize cleanly. */
internal val ideGson = GsonBuilder().disableHtmlEscaping().create()

internal data class IdePosition(
    @SerializedName("line") val line: Int,
    @SerializedName("character") val character: Int,
)

internal data class IdeRange(
    @SerializedName("start") val start: IdePosition,
    @SerializedName("end") val end: IdePosition,
)

internal data class IdeSelectionRange(
    @SerializedName("start") val start: IdePosition,
    @SerializedName("end") val end: IdePosition,
    @SerializedName("isEmpty") val isEmpty: Boolean,
)

internal data class IdeSelectionInfo(
    @SerializedName("text") val text: String,
    @SerializedName("filePath") val filePath: String,
    @SerializedName("fileUrl") val fileUrl: String,
    @SerializedName("selection") val selection: IdeSelectionRange,
    @SerializedName("current") val current: Boolean = true,
)

internal data class IdeDiagnostic(
    @SerializedName("message") val message: String,
    @SerializedName("severity") val severity: String,
    @SerializedName("range") val range: IdeRange,
    @SerializedName("source") val source: String? = null,
    @SerializedName("code") val code: String? = null,
)

internal data class IdeDiagnosticFileEntry(
    @SerializedName("uri") val uri: String,
    @SerializedName("filePath") val filePath: String,
    @SerializedName("diagnostics") val diagnostics: List<IdeDiagnostic>,
)

internal data class IdeDiffResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("result") val result: String, // "SAVED" | "REJECTED"
    @SerializedName("trigger") val trigger: String,
    @SerializedName("message") val message: String,
)

internal data class IdeCloseDiffResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("already_closed") val alreadyClosed: Boolean,
    @SerializedName("tab_name") val tabName: String,
    @SerializedName("message") val message: String,
)

internal data class IdeAddFileReferenceParams(
    @SerializedName("filePath") val filePath: String,
    @SerializedName("fileUrl") val fileUrl: String,
    @SerializedName("selection") val selection: IdeRange?,
    @SerializedName("selectedText") val selectedText: String?,
)

/** Contents of a `~/.copilot/ide/<name>.lock` discovery file. */
internal data class IdeLockFileInfo(
    @SerializedName("socketPath") val socketPath: String,
    @SerializedName("scheme") val scheme: String, // "unix" | "pipe"
    @SerializedName("headers") val headers: Map<String, String>,
    @SerializedName("pid") val pid: Long,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("workspaceFolders") val workspaceFolders: List<String>,
    @SerializedName("ideName") val ideName: String,
    @SerializedName("isTrusted") val isTrusted: Boolean,
)
