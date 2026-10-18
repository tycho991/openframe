package io.github.tycho991.openframe.core.testing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecVersion
import java.io.File
import java.net.URI

/**
 * Prüft Antworten gegen `api/openapi.yaml`: Die Kombination aus Methode, Pfad
 * und Status muss im Vertrag stehen, und JSON-Rümpfe müssen dem Schema genügen.
 */
object Contract {
    private val file = File(System.getProperty("openframe.openapi") ?: error("System-Property openframe.openapi fehlt"))
    private val doc: JsonNode = ObjectMapper(YAMLFactory()).readTree(file)
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val basePath =
        URI(
            doc
                .path("servers")
                .path(0)
                .path("url")
                .asText("/"),
        ).path.trimEnd('/')

    /** Wirft einen [AssertionError], wenn die Antwort nicht zum Vertrag passt. */
    fun check(
        method: String,
        path: String,
        status: Int,
        contentType: String?,
        body: String,
    ) {
        val (template, operation) = findOperation(method, path)
        val responses = operation.path("responses")
        val responsePointer = "/paths/${escape(template)}/${method.lowercase()}/responses/$status"
        val response = responses.path(status.toString())
        if (response.isMissingNode) fail("Status $status für $method $template ist nicht im Vertrag beschrieben")
        val (resolved, pointer) = resolve(response, responsePointer)
        val content = resolved.path("content")
        if (content.isMissingNode || content.isEmpty) {
            if (body.isNotEmpty()) fail("$method $path $status: Vertrag beschreibt keinen Rumpf, Antwort hat einen")
            return
        }
        val type = contentType?.substringBefore(';')?.trim() ?: fail("$method $path $status: Content-Type fehlt")
        if (!content.has(
                type,
            )
        ) {
            fail("$method $path $status: Content-Type $type nicht im Vertrag (${content.fieldNames().asSequence().toList()})")
        }
        if (type != "application/json") return
        val schema = factory.getSchema(SchemaLocation.of("${file.toURI()}#$pointer/content/${escape(type)}/schema"))
        val errors = schema.validate(ObjectMapper().readTree(body))
        if (errors.isNotEmpty()) fail("$method $path $status verletzt den Vertrag:\n  ${errors.joinToString("\n  ")}\nRumpf: $body")
    }

    /** Alle Kombinationen aus Methode und Pfadmuster im Vertrag, z. B. `GET /api/v1/info`. */
    fun operations(): Set<String> =
        doc
            .path("paths")
            .properties()
            .asSequence()
            .flatMap { (template, item) ->
                item
                    .fieldNames()
                    .asSequence()
                    .filter { it in METHODS }
                    .map { "${it.uppercase()} $basePath$template" }
            }.toSet()

    /** Ob die Operation laut Vertrag ohne Token aufrufbar ist. */
    fun isPublic(
        method: String,
        path: String,
    ): Boolean {
        val (_, operation) = findOperation(method, path)
        val security = operation.get("security") ?: doc.path("security")
        return security.isArray && (security.isEmpty || security.any { it.isEmpty })
    }

    private fun findOperation(
        method: String,
        path: String,
    ): Pair<String, JsonNode> {
        if (!path.startsWith(basePath)) fail("$path liegt nicht unter $basePath")
        val relative = path.removePrefix(basePath).substringBefore('?')
        val segments = relative.trim('/').split('/')
        val match =
            doc.path("paths").properties().asSequence().firstOrNull { (template, item) ->
                val parts = template.trim('/').split('/')
                parts.size == segments.size &&
                    parts.zip(segments).all { (p, s) -> (p.startsWith('{') && s.isNotEmpty()) || p == s } &&
                    item.has(method.lowercase())
            } ?: fail("$method $relative ist nicht im Vertrag beschrieben")
        return match.key to match.value.path(method.lowercase())
    }

    private fun resolve(
        node: JsonNode,
        pointer: String,
    ): Pair<JsonNode, String> {
        val ref = node.path("\$ref").asText(null) ?: return node to pointer
        val target = ref.removePrefix("#")
        return resolve(doc.at(target), target)
    }

    private fun escape(segment: String) = segment.replace("~", "~0").replace("/", "~1")

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private val METHODS = setOf("get", "post", "put", "patch", "delete")
}
