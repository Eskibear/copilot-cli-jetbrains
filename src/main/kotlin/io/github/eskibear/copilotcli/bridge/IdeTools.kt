package io.github.eskibear.copilotcli.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * IDE-side implementations of the MCP tools the Copilot CLI calls.
 *
 * Tool names match the VS Code surface verbatim (the CLI hardcodes them, e.g. the
 * tool is called `get_vscode_info` everywhere even though it's just "IDE info").
 */
class IdeTools(private val project: Project) {

    private data class ToolDef(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
        val handler: (JsonObject) -> JsonObject,
    )

    private val defs: List<ToolDef> = listOf(
        ToolDef(
            name = "get_vscode_info",
            description = "Returns information about the running IDE (name, version, machineId).",
            inputSchema = emptyObjectSchema(),
            handler = { getIdeInfo() },
        ),
        ToolDef(
            name = "get_selection",
            description = "Returns the current selection (or caret position) in the active editor.",
            inputSchema = emptyObjectSchema(),
            handler = { getSelection() },
        ),
        ToolDef(
            name = "update_session_name",
            description = "Updates the display name of the Copilot CLI session as shown in the IDE.",
            inputSchema = stringPropSchema("name", "Session display name"),
            handler = { updateSessionName(it) },
        ),
    )

    fun listTools(): JsonArray = JsonArray().apply {
        defs.forEach { d ->
            add(JsonObject().apply {
                addProperty("name", d.name)
                addProperty("description", d.description)
                add("inputSchema", d.inputSchema)
            })
        }
    }

    fun callTool(params: JsonObject): JsonObject {
        val name = params.get("name")?.asString
            ?: return toolError("Missing tool name")
        val args = params.getAsJsonObject("arguments") ?: JsonObject()
        val def = defs.firstOrNull { it.name == name }
            ?: return toolError("Unknown tool: $name")
        return def.handler(args)
    }

    private fun getIdeInfo(): JsonObject {
        val app = ApplicationInfo.getInstance()
        val payload = JsonObject().apply {
            addProperty("appName", app.fullApplicationName)
            addProperty("version", app.fullVersion)
            addProperty("apiVersion", app.apiVersion)
            addProperty("build", app.build.asString())
        }
        return textResult(payload.toString())
    }

    private fun getSelection(): JsonObject {
        val payload = ReadAction.nonBlocking<JsonObject> {
            val editor: Editor? = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor == null) {
                JsonObject().apply { addProperty("current", false) }
            } else {
                val selectionModel = editor.selectionModel
                val doc = editor.document
                val caret = editor.caretModel.primaryCaret
                val selStart = if (selectionModel.hasSelection()) selectionModel.selectionStart else caret.offset
                val selEnd = if (selectionModel.hasSelection()) selectionModel.selectionEnd else caret.offset
                val startLine = doc.getLineNumber(selStart)
                val endLine = doc.getLineNumber(selEnd)
                val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(doc)
                JsonObject().apply {
                    addProperty("current", true)
                    addProperty("text", if (selectionModel.hasSelection()) selectionModel.selectedText ?: "" else "")
                    addProperty("filePath", file?.path ?: "")
                    addProperty("fileUrl", file?.url ?: "")
                    add("selection", JsonObject().apply {
                        add("start", JsonObject().apply {
                            addProperty("line", startLine)
                            addProperty("character", selStart - doc.getLineStartOffset(startLine))
                        })
                        add("end", JsonObject().apply {
                            addProperty("line", endLine)
                            addProperty("character", selEnd - doc.getLineStartOffset(endLine))
                        })
                        addProperty("isEmpty", !selectionModel.hasSelection())
                    })
                }
            }
        }.executeSynchronously()
        return textResult(payload.toString())
    }

    private fun updateSessionName(args: JsonObject): JsonObject {
        val name = args.get("name")?.asString ?: return toolError("Missing 'name' argument")
        // PoC: log only. A future revision can rename the launched terminal tab.
        com.intellij.openapi.diagnostic.Logger
            .getInstance(IdeTools::class.java)
            .info("Copilot CLI requested session name: $name")
        return textResult("ok")
    }

    private fun textResult(text: String): JsonObject = JsonObject().apply {
        add("content", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
        })
        addProperty("isError", false)
    }

    private fun toolError(message: String): JsonObject = JsonObject().apply {
        add("content", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", message)
            })
        })
        addProperty("isError", true)
    }

    private fun emptyObjectSchema(): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
    }

    private fun stringPropSchema(prop: String, desc: String): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            add(prop, JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", desc)
            })
        })
        add("required", JsonArray().apply { add(prop) })
    }
}
