package io.github.tycho991.openframe.core.testing

import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.File
import java.net.URI

/**
 * Checks responses against `api/openapi.yaml`: the combination of method, path and status must be in the contract,
 * JSON bodies must satisfy the schema (formats such as `date-time` included), declared response headers their schema,
 * and for a 2xx answer the request body sent must satisfy the operation's `requestBody`. Methods the contract does not
 * model (HEAD, TRACE, PROPFIND, …) are not checked: their generic behaviour is in the info description.
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
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }
    private val mapper = JsonMapper()
    private val basePath =
        URI(
            doc
                .path("servers")
                .path(0)
                .path("url")
                .asString("/"),
        ).path.trimEnd('/')

    /**
     * Throws an [AssertionError] if the response does not match the contract.
     *
     * @param body the body as text, or `""` when it is not text; [bodyBytes] then tells whether there was one.
     * @param headers the response headers with lower-case names; `null` skips the header check.
     * @param requestBody the JSON body sent, checked against `requestBody` for 2xx answers.
     */
    fun check(
        method: String,
        path: String,
        status: Int,
        contentType: String?,
        body: String,
        headers: Map<String, List<String>>? = null,
        bodyBytes: Int = body.length,
        requestBody: String? = null,
    ) {
        if (method.lowercase() !in METHODS) return
        val (template, operation) = findOperation(method, path)
        val operationPointer = "/paths/${escape(template)}/${method.lowercase()}"
        val responses = operation.path("responses")
        val response = responses.path(status.toString())
        if (response.isMissingNode) fail("Status $status for $method $template is not described in the contract")
        val (resolved, pointer) = resolve(response, "$operationPointer/responses/$status")
        if (headers != null) checkHeaders(method, path, status, resolved, pointer, headers)
        if (status in 200..299 && requestBody != null) checkRequestBody(method, path, operation, operationPointer, requestBody)
        val content = resolved.path("content")
        if (content.isMissingNode || content.isEmpty) {
            if (bodyBytes > 0) fail("$method $path $status: contract describes no body, response has $bodyBytes bytes")
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
        validate("$pointer/content/${escape(type)}/schema", body, "$method $path $status")
    }

    private fun checkHeaders(
        method: String,
        path: String,
        status: Int,
        response: JsonNode,
        pointer: String,
        headers: Map<String, List<String>>,
    ) {
        for ((name, declared) in response.path("headers").properties()) {
            val (header, headerPointer) = resolve(declared, "$pointer/headers/${escape(name)}")
            val value = headers[name.lowercase()]?.firstOrNull()
            if (value == null) {
                if (header.path("required").asBoolean(false)) fail("$method $path $status: required header $name is missing")
                continue
            }
            val schema = header.path("schema")
            val node =
                when (schema.path("type").asString("string")) {
                    "integer" -> value.toLongOrNull()?.let { mapper.nodeFactory.numberNode(it) }
                    "number" -> value.toDoubleOrNull()?.let { mapper.nodeFactory.numberNode(it) }
                    else -> null
                } ?: mapper.nodeFactory.stringNode(value)
            val errors = registry.getSchema(SchemaLocation.of("${file.toURI()}#$headerPointer/schema")).validate(node)
            if (errors.isNotEmpty()) fail("$method $path $status: header $name: $value violates the contract: $errors")
        }
    }

    private fun checkRequestBody(
        method: String,
        path: String,
        operation: JsonNode,
        operationPointer: String,
        requestBody: String,
    ) {
        val (declared, pointer) = resolve(operation.path("requestBody"), "$operationPointer/requestBody")
        if (declared.isMissingNode) return
        val json = "application/json"
        if (!declared.path("content").has(json)) return
        validate("$pointer/content/${escape(json)}/schema", requestBody, "$method $path: the request body sent")
    }

    private fun validate(
        pointer: String,
        body: String,
        what: String,
    ) {
        val schema = registry.getSchema(SchemaLocation.of("${file.toURI()}#$pointer"))
        val errors = schema.validate(mapper.readTree(body))
        if (errors.isNotEmpty()) fail("$what violates the contract:\n  ${errors.joinToString("\n  ")}\nBody: $body")
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
