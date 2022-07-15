/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.traits.HttpHeaderTrait
import software.amazon.smithy.model.traits.HttpPrefixHeadersTrait
import software.amazon.smithy.model.traits.HttpQueryTrait
import software.amazon.smithy.model.traits.HttpResponseCodeTrait
import software.amazon.smithy.model.traits.HttpTrait
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.asType
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.withBlock
import software.amazon.smithy.rust.codegen.server.smithy.ServerCargoDependency
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverTestSymbolProvider
import software.amazon.smithy.rust.codegen.testutil.TestRuntimeConfig
import software.amazon.smithy.rust.codegen.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.testutil.unitTest
import software.amazon.smithy.rust.codegen.util.getTrait
import software.amazon.smithy.rust.codegen.util.inputShape

class ServerHttpSensitivityGeneratorTest {
    private val codegenScope = arrayOf(
        "SmithyHttpServer" to ServerCargoDependency.SmithyHttpServer(TestRuntimeConfig).asType(),
        "Http" to CargoDependency.Http.asType(),
    )

    @Test
    fun `find outer sensitive`() {
        val model = """
            namespace test

            operation Secret {
                input: Input,
            }

            @sensitive
            structure Input {
                @required
                @httpResponseCode
                code: Integer,
            }
        """.asSmithyModel()
        val operation = model.getOperationShapes().toList()[0]
        val generator = ServerHttpSensitivityGenerator(model, operation, TestRuntimeConfig)

        val inputShape = operation.inputShape(model)
        val members: List<String> = generator.findSensitiveBound<HttpResponseCodeTrait>(inputShape).map(MemberShape::getMemberName)

        assertEquals(members, listOf("code"))
    }

    @Test
    fun `find inner sensitive`() {
        val model = """
            namespace test

            operation Secret {
                input: Input,
            }

            structure Input {
                @required
                @sensitive
                @httpHeader("header-a")
                headerA: String,

                @required
                @httpHeader("header-b")
                headerB: String
            }
        """.asSmithyModel()

        val operation = model.getOperationShapes().toList()[0]
        val generator = ServerHttpSensitivityGenerator(model, operation, TestRuntimeConfig)

        val inputShape = operation.inputShape(model)
        val members: List<String> = generator.findSensitiveBound<HttpHeaderTrait>(inputShape).map(MemberShape::getMemberName)

        assertEquals(members, listOf("headerA"))
    }

    @Test
    fun `find nested sensitive`() {
        val model = """
            namespace test

            operation Secret {
                input: Input,
            }

            @sensitive
            structure Input {
                @required
                @httpHeader("header-a")
                headerA: String,

                nested: Nested
            }

            structure Nested {
                @required
                @httpHeader("header-b")
                headerB: String
            }
        """.asSmithyModel()
        val operation = model.getOperationShapes().toList()[0]
        val generator = ServerHttpSensitivityGenerator(model, operation, TestRuntimeConfig)

        val inputShape = operation.inputShape(model)
        val members: List<String> = generator.findSensitiveBound<HttpHeaderTrait>(inputShape).map(MemberShape::getMemberName)

        assertEquals(members, listOf("headerB", "headerA"))
    }

    @Test
    fun `query closure`() {
        val model = """
            namespace test

            operation Secret {
                input: Input,
            }

            structure Input {
                @required
                @httpQuery("query_a")
                queryA: String,

                @sensitive
                nestedA: NestedA,

                nestedB: NestedB
            }

            structure NestedA {
                @required
                @httpQuery("query_b")
                queryB: String
            }

            @sensitive
            structure NestedB {
                @required
                @httpQuery("query_c")
                queryC: String
            }
        """.asSmithyModel()
        val operation = model.getOperationShapes().toList()[0]
        val generator = ServerHttpSensitivityGenerator(model, operation, TestRuntimeConfig)

        val inputShape = operation.inputShape(model)
        val requestQueries = generator.findSensitiveBoundTrait<HttpQueryTrait>(inputShape)

        val testProject = TestWorkspace.testProject(serverTestSymbolProvider(model))
        testProject.lib { writer ->
            writer.unitTest("query_closure") {
                withBlock("let closure = ", ";") {
                    generator.renderQueryClosure(writer, requestQueries)
                }
                rustTemplate(
                    """
                    assert_eq!(closure("query_a"), #{SmithyHttpServer}::logging::QueryMarker { key: false, value: false });
                    assert_eq!(closure("query_b"), #{SmithyHttpServer}::logging::QueryMarker { key: false, value: true });
                    assert_eq!(closure("query_c"), #{SmithyHttpServer}::logging::QueryMarker { key: false, value: true });
                    """,
                    *codegenScope
                )
            }
        }
        testProject.compileAndTest()
    }

    @Test
    fun `query params closure`() {
        val model = """
            namespace test

            operation Secret {}
        """.asSmithyModel()
        val operation = model.getOperationShapes().toList()[0]
        val generator = ServerHttpSensitivityGenerator(model, operation, TestRuntimeConfig)

        val testProject = TestWorkspace.testProject(serverTestSymbolProvider(model))
        testProject.lib { writer ->
            writer.unitTest("query_params_closure") {
                withBlock("let closure = ", ";") {
                    generator.renderQueryParamsClosure(writer)
                }
                rustTemplate(
                    """
                    assert_eq!(closure("wildcard"), #{SmithyHttpServer}::logging::QueryMarker { key: true, value: true });
                    """,
                    *codegenScope
                )
            }
        }
        testProject.compileAndTest()
    }

    @Test
    fun `query params special closure`() {
        val model = """
            namespace test

            operation Secret {
                input: Input,
            }

            structure Input {
                @required
                @httpQueryParams()
                queryMap: QueryMap,
            }

            map QueryMap {
                @sensitive
                key: String,
                value: String
            }

        """.asSmithyModel()
        // val operation = queryParamsModel.getOperationShapes().toList()[0]
        // val generator = ServerHttpSensitivityGenerator(queryParamsModel, operation, TestRuntimeConfig)

        val testProject = TestWorkspace.testProject(serverTestSymbolProvider(model))
        testProject.lib { writer ->
            writer.unitTest("query_params_special_closure") {
                // TODO(special case query params): Restore this test when query params work
            }
        }
        testProject.compileAndTest()
    }

    @Test
    fun `header closure`() {
        val model = """
            namespace test

            operation Secret {
                input: Input,
            }

            structure Input {
                @required
                @httpHeader("header-a")
                headerA: String,

                @sensitive
                nestedA: NestedA,

                nestedB: NestedB
            }

            structure NestedA {
                @required
                @httpHeader("header-b")
                headerB: String
            }

            @sensitive
            structure NestedB {
                @required
                @httpHeader("header-c")
                headerC: String
            }
        """.asSmithyModel()
        val operation = model.getOperationShapes().toList()[0]
        val generator = ServerHttpSensitivityGenerator(model, operation, TestRuntimeConfig)

        val inputShape = operation.inputShape(model)
        val headers = generator.findSensitiveBoundTrait<HttpHeaderTrait>(inputShape)

        val testProject = TestWorkspace.testProject(serverTestSymbolProvider(model))
        testProject.lib { writer ->
            writer.unitTest("header_closure") {
                withBlock("let closure = ", ";") {
                    generator.renderHeaderClosure(writer, headers, emptyList())
                }
                rustTemplate(
                    """
                    let name = #{Http}::header::HeaderName::from_static("header-a");
                    assert_eq!(closure(&name), #{SmithyHttpServer}::logging::HeaderMarker { value: false, key_suffix: None });
                    let name = #{Http}::header::HeaderName::from_static("header-b");
                    assert_eq!(closure(&name), #{SmithyHttpServer}::logging::HeaderMarker { value: true, key_suffix: None });
                    let name = #{Http}::header::HeaderName::from_static("header-c");
                    assert_eq!(closure(&name), #{SmithyHttpServer}::logging::HeaderMarker { value: true, key_suffix: None });
                    """,
                    *codegenScope
                )
            }
        }
        testProject.compileAndTest()
    }

    @Test
    fun `prefix header closure`() {
        val model = """
            namespace test

            operation Secret {
                input: Input,
            }

            @sensitive
            structure Input {
                @required
                @httpPrefixHeaders("prefix-")
                prefixMap: PrefixMap,
            }

            map PrefixMap {
                key: String,
                value: String
            }

        """.asSmithyModel()
        val operation = model.getOperationShapes().toList()[0]
        val generator = ServerHttpSensitivityGenerator(model, operation, TestRuntimeConfig)

        val inputShape = operation.inputShape(model)
        val prefixHeaders = generator.findSensitiveBoundTrait<HttpPrefixHeadersTrait>(inputShape)

        val testProject = TestWorkspace.testProject(serverTestSymbolProvider(model))
        testProject.lib { writer ->
            writer.unitTest("prefix_headers_closure") {
                withBlock("let closure = ", ";") {
                    generator.renderHeaderClosure(writer, emptyList(), prefixHeaders)
                }
                rustTemplate(
                    """
                    let name = #{Http}::header::HeaderName::from_static("prefix-a");
                    assert_eq!(closure(&name), #{SmithyHttpServer}::logging::HeaderMarker { value: true, key_suffix: Some(7) });
                    let name = #{Http}::header::HeaderName::from_static("prefix-b");
                    assert_eq!(closure(&name), #{SmithyHttpServer}::logging::HeaderMarker { value: true, key_suffix: Some(7) });
                    let name = #{Http}::header::HeaderName::from_static("other");
                    assert_eq!(closure(&name), #{SmithyHttpServer}::logging::HeaderMarker { value: false, key_suffix: None });
                    """,
                    *codegenScope
                )
            }
        }
        testProject.compileAndTest()
    }

    @Test
    fun `prefix headers special closure`() {
        val model = """
            namespace test

            operation Secret {
                input: Input,
            }

            structure Input {
                @required
                @httpPrefixHeaders("prefix-")
                prefix_map: PrefixMap,
            }

            map PrefixMap {
                @sensitive
                key: String,
                value: String
            }

        """.asSmithyModel()
        // val operation = prefixHeadersSpecialModel.getOperationShapes().toList()[0]
        // val generator = ServerHttpSensitivityGenerator(prefixHeadersSpecialModel, operation, TestRuntimeConfig)

        val testProject = TestWorkspace.testProject(serverTestSymbolProvider(model))
        testProject.lib { writer ->
            writer.unitTest("prefix_headers_special_closure") {
                // TODO(special case prefix headers): Restore this test when map > member sensitivity works
            }
        }
        testProject.compileAndTest()
    }

    @Test
    fun `uri closure`() {
        val model = """
            namespace test

            @http(method: "GET", uri: "/secret/{labelA}/{labelB}")
            operation Secret {
                input: Input,
            }

            structure Input {
                @required
                @sensitive
                @httpLabel
                labelA: String,
                @required
                @httpLabel
                @sensitive
                labelB: String,
            }
        """.asSmithyModel()
        val operation = model.getOperationShapes().toList()[0]
        val generator = ServerHttpSensitivityGenerator(model, operation, TestRuntimeConfig)

        val httpTrait = operation.getTrait<HttpTrait>()!!
        val inputShape = operation.inputShape(model)
        val labeledUriIndexes = generator.findUriLabelIndexes(httpTrait, inputShape)

        val testProject = TestWorkspace.testProject(serverTestSymbolProvider(model))
        testProject.lib { writer ->
            writer.unitTest("uri_closure") {
                withBlock("let closure = ", ";") {
                    generator.renderPathClosure(writer, labeledUriIndexes)
                }
                rustTemplate(
                    """
                    assert_eq!(closure(0), false);
                    assert_eq!(closure(1), true);
                    assert_eq!(closure(2), true);
                    """,
                    *codegenScope
                )
            }
        }
        testProject.compileAndTest()
    }

    @Test
    fun `uri greedy label closure`() {
        val model = """
            namespace test

            operation Secret {
                input: Input,
            }

            structure Input {
                @required
                @httpQueryParams()
                queryMap: QueryMap,
            }

            map QueryMap {
                @sensitive
                key: String,
                value: String
            }

        """.asSmithyModel()
        // val operation = queryParamsModel.getOperationShapes().toList()[0]
        // val generator = ServerHttpSensitivityGenerator(queryParamsModel, operation, TestRuntimeConfig)

        val testProject = TestWorkspace.testProject(serverTestSymbolProvider(model))
        testProject.lib { writer ->
            writer.unitTest("uri_greedy_label_closure") {
                // TODO(greedy uri labels): Restore this test when query params work
            }
        }
        testProject.compileAndTest()
    }
}
