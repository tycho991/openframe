package io.github.tycho991.openframe.core.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.File

/**
 * Spec `admin-access`: Host check, Limits for connections and request times. Every operation of the contract describes
 * the answers that the pipeline and the transport can give to any request, so that new endpoints cannot forget them.
 */
class ContractConsistencyTest {
    private val file = File(System.getProperty("openframe.openapi") ?: error("System property openframe.openapi is missing"))

    @Test
    fun `Every operation describes 421 and 500, and 408 with a JSON body`() {
        assertEquals(emptyList<String>(), missingResponses(YAMLMapper().readTree(file)))
    }

    @Test
    fun `A contract without one of them fails`() {
        val withoutHost =
            file.readText().replaceFirst(
                """        "421": { ${'$'}ref: "#/components/responses/MisdirectedHost" }""" + "\n",
                "",
            )
        assertEquals(listOf("GET /info: 421"), missingResponses(YAMLMapper().readTree(withoutHost)))

        val withoutError =
            file.readText().replaceFirst(
                """        "500": { ${'$'}ref: "#/components/responses/InternalError" }""" + "\n",
                "",
            )
        assertEquals(listOf("GET /info: 500"), missingResponses(YAMLMapper().readTree(withoutError)))

        val withoutTimeout =
            file.readText().replaceFirst(
                """        "408": { ${'$'}ref: "#/components/responses/RequestTimeout" }""" + "\n",
                "",
            )
        assertEquals(listOf("POST /pairing: 408"), missingResponses(YAMLMapper().readTree(withoutTimeout)))
    }

    private fun missingResponses(doc: JsonNode): List<String> =
        doc
            .path("paths")
            .properties()
            .flatMap { (template, item) ->
                item.properties().filter { it.key in METHODS }.flatMap { (method, operation) ->
                    val responses = operation.path("responses")
                    val json = operation.path("requestBody").path("content").has("application/json")
                    val wanted = listOf("421", "500") + if (json) listOf("408") else emptyList()
                    wanted.filter { !responses.has(it) }.map { "${method.uppercase()} $template: $it" }
                }
            }

    private companion object {
        val METHODS = setOf("get", "post", "put", "patch", "delete")
    }
}
