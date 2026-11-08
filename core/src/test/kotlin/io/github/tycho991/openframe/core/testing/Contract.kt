package io.github.tycho991.openframe.core.testing

import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.File
import java.net.URI

/**
 * Checks responses against `api/openapi.yaml`: the combination of method, path
 * and status must be in the contract, and JSON bodies must satisfy the schema.
 */
object Contract {
    private val file = File(System.getProperty("openframe.openapi") ?: error("System property openframe.openapi is missing"))
    private val doc: JsonNode = YAMLMapper().readTree(file)

    // Version 2 and later load no schema from a URI unless told to, the contract file included.
    private val registry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaLoader { loader ->
                loader.fetchRemoteResources()
            }
        }
    private val basePath =
        URI(
            doc
                .path("servers")
                .path(0)
                .path("url")
                .asString("/"),
        ).path.trimEnd('/')

    /** Throws an [AssertionError] if the response does not match the contract. */
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
        if (response.isMissingNode) fail("Status $status for $method $template is not described in the contract")
        val (resolved, pointer) = resolve(response, responsePointer)
        val content = resolved.path("content")
        if (content.isMissingNode || content.isEmpty) {
            if (body.isNotEmpty()) fail("$method $path $status: contract describes no body, response has one")
            return
        }
        val type = contentType?.substringBefore(';')?.trim() ?: fail("$method $path $status: Content-Type is missing")
        if (!content.has(
                type,
            )
        ) {
            fail("$method $path $status: Content-Type $type not in the contract (${content.propertyNames().toList()})")
        }
        if (type != "application/json") return
        val schema = registry.getSchema(SchemaLocation.of("${file.toURI()}#$pointer/content/${escape(type)}/schema"))
        val errors = schema.validate(JsonMapper().readTree(body))
        if (errors.isNotEmpty()) fail("$method $path $status violates the contract:\n  ${errors.joinToString("\n  ")}\nBody: $body")
    }

    /** All combinations of method and path pattern in the contract, e.g. `GET /api/v1/info`. */
    fun operations(): Set<String> =
        doc
            .path("paths")
            .properties()
            .asSequence()
            .flatMap { (template, item) ->
                item
                    .propertyNames()
                    .asSequence()
                    .filter { it in METHODS }
                    .map { "${it.uppercase()} $basePath$template" }
            }.toSet()

    /** Whether the operation is callable without a token according to the contract. */
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
        if (!path.startsWith(basePath)) fail("$path is not under $basePath")
        val relative = path.removePrefix(basePath).substringBefore('?')
        val segments = relative.trim('/').split('/')
        val match =
            doc.path("paths").properties().asSequence().firstOrNull { (template, item) ->
                val parts = template.trim('/').split('/')
                parts.size == segments.size &&
                    parts.zip(segments).all { (p, s) -> (p.startsWith('{') && s.isNotEmpty()) || p == s } &&
                    item.has(method.lowercase())
            } ?: fail("$method $relative is not described in the contract")
        return match.key to match.value.path(method.lowercase())
    }

    private fun resolve(
        node: JsonNode,
        pointer: String,
    ): Pair<JsonNode, String> {
        val ref = node.path("\$ref").asString(null) ?: return node to pointer
        val target = ref.removePrefix("#")
        return resolve(doc.at(target), target)
    }

    private fun escape(segment: String) = segment.replace("~", "~0").replace("/", "~1")

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private val METHODS = setOf("get", "post", "put", "patch", "delete")
}
