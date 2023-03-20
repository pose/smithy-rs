
/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.python.smithy

import software.amazon.smithy.build.PluginContext
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.NullableIndex
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProviderConfig
import software.amazon.smithy.rust.codegen.core.smithy.generators.error.ErrorImplGenerator
import software.amazon.smithy.rust.codegen.core.util.getTrait
import software.amazon.smithy.rust.codegen.core.util.isEventStream
import software.amazon.smithy.rust.codegen.server.python.smithy.generators.PythonServerEnumGenerator
import software.amazon.smithy.rust.codegen.server.python.smithy.generators.PythonServerEventStreamWrapperGenerator
import software.amazon.smithy.rust.codegen.server.python.smithy.generators.PythonServerOperationHandlerGenerator
import software.amazon.smithy.rust.codegen.server.python.smithy.generators.PythonServerServiceGenerator
import software.amazon.smithy.rust.codegen.server.python.smithy.generators.PythonServerStructureGenerator
import software.amazon.smithy.rust.codegen.server.python.smithy.generators.PythonServerUnionGenerator
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenVisitor
import software.amazon.smithy.rust.codegen.server.smithy.ServerModuleDocProvider
import software.amazon.smithy.rust.codegen.server.smithy.ServerModuleProvider
import software.amazon.smithy.rust.codegen.server.smithy.ServerRustModule
import software.amazon.smithy.rust.codegen.server.smithy.ServerRustSettings
import software.amazon.smithy.rust.codegen.server.smithy.ServerSymbolProviders
import software.amazon.smithy.rust.codegen.server.smithy.canReachConstrainedShape
import software.amazon.smithy.rust.codegen.server.smithy.createInlineModuleCreator
import software.amazon.smithy.rust.codegen.server.smithy.customize.ServerCodegenDecorator
import software.amazon.smithy.rust.codegen.server.smithy.generators.ServerOperationErrorGenerator
import software.amazon.smithy.rust.codegen.server.smithy.generators.UnconstrainedUnionGenerator
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocol
import software.amazon.smithy.rust.codegen.server.smithy.protocols.ServerProtocolLoader
import software.amazon.smithy.rust.codegen.server.smithy.traits.isReachableFromOperationInput

/**
 * Entrypoint for Python server-side code generation. This class will walk the in-memory model and
 * generate all the needed types by calling the accept() function on the available shapes.
 *
 * This class inherits from [ServerCodegenVisitor] since it uses most of the functionalities of the super class
 * and have to override the symbol provider with [PythonServerSymbolProvider].
 */
class PythonServerCodegenVisitor(
    context: PluginContext,
    private val codegenDecorator: ServerCodegenDecorator,
) : ServerCodegenVisitor(context, codegenDecorator) {

    init {
        val rustSymbolProviderConfig =
            RustSymbolProviderConfig(
                runtimeConfig = settings.runtimeConfig,
                renameExceptions = false,
                nullabilityCheckMode = NullableIndex.CheckMode.SERVER,
                moduleProvider = ServerModuleProvider,
            )
        val baseModel = baselineTransform(context.model)
        val service = settings.getService(baseModel)
        val (protocol, generator) =
            ServerProtocolLoader(
                codegenDecorator.protocols(
                    service.id,
                    ServerProtocolLoader.DefaultProtocols,
                ),
            )
                .protocolFor(context.model, service)
        protocolGeneratorFactory = generator

        model = codegenDecorator.transformModel(service, baseModel)

        // `publicConstrainedTypes` must always be `false` for the Python server, since Python generates its own
        // wrapper newtypes.
        settings = settings.copy(codegenConfig = settings.codegenConfig.copy(publicConstrainedTypes = false))

        fun baseSymbolProviderFactory(
            settings: ServerRustSettings,
            model: Model,
            serviceShape: ServiceShape,
            rustSymbolProviderConfig: RustSymbolProviderConfig,
            publicConstrainedTypes: Boolean,
            includeConstraintShapeProvider: Boolean,
            codegenDecorator: ServerCodegenDecorator,
        ) = RustServerCodegenPythonPlugin.baseSymbolProvider(settings, model, serviceShape, rustSymbolProviderConfig, publicConstrainedTypes, includeConstraintShapeProvider, codegenDecorator)

        val serverSymbolProviders = ServerSymbolProviders.from(
            settings,
            model,
            service,
            rustSymbolProviderConfig,
            settings.codegenConfig.publicConstrainedTypes,
            codegenDecorator,
            ::baseSymbolProviderFactory,
        )

        // Override `codegenContext` which carries the various symbol providers.
        codegenContext =
            ServerCodegenContext(
                model,
                serverSymbolProviders.symbolProvider,
                null,
                service,
                protocol,
                settings,
                serverSymbolProviders.unconstrainedShapeSymbolProvider,
                serverSymbolProviders.constrainedShapeSymbolProvider,
                serverSymbolProviders.constraintViolationSymbolProvider,
                serverSymbolProviders.pubCrateConstrainedShapeSymbolProvider,
            )

        codegenContext = codegenContext.copy(
            moduleDocProvider = codegenDecorator.moduleDocumentationCustomization(
                codegenContext,
                PythonServerModuleDocProvider(ServerModuleDocProvider()),
            ),
        )

        // Override `rustCrate` which carries the symbolProvider.
        rustCrate = RustCrate(
            context.fileManifest,
            codegenContext.symbolProvider,
            settings.codegenConfig,
            codegenContext.expectModuleDocProvider(),
        )
        // Override `protocolGenerator` which carries the symbolProvider.
        protocolGenerator = protocolGeneratorFactory.buildProtocolGenerator(codegenContext)
    }

    /**
     * Structure Shape Visitor
     *
     * For each structure shape, generate:
     * - A Rust structure for the shape ([StructureGenerator]).
     * - `pyo3::PyClass` trait implementation.
     * - A builder for the shape.
     *
     * This function _does not_ generate any serializers.
     */
    override fun structureShape(shape: StructureShape) {
        logger.info("[python-server-codegen] Generating a structure $shape")
        rustCrate.useShapeWriter(shape) {
            // Use Python specific structure generator that adds the #[pyclass] attribute
            // and #[pymethods] implementation.
            PythonServerStructureGenerator(model, codegenContext.symbolProvider, this, shape).render()

            shape.getTrait<ErrorTrait>()?.also { errorTrait ->
                ErrorImplGenerator(
                    model,
                    codegenContext.symbolProvider,
                    this,
                    shape,
                    errorTrait,
                    codegenDecorator.errorImplCustomizations(codegenContext, emptyList()),
                ).render(CodegenTarget.SERVER)
            }

            renderStructureShapeBuilder(shape, this)
        }
    }

    /**
     * String Shape Visitor
     *
     * Although raw strings require no code generation, enums are actually [EnumTrait] applied to string shapes.
     */
    override fun stringShape(shape: StringShape) {
        fun pythonServerEnumGeneratorFactory(codegenContext: ServerCodegenContext, shape: StringShape) =
            PythonServerEnumGenerator(codegenContext, shape, validationExceptionConversionGenerator)
        stringShape(shape, ::pythonServerEnumGeneratorFactory)
    }

    /**
     * Union Shape Visitor
     *
     * Generate an `enum` for union shapes.
     *
     * Note: this does not generate serializers
     */
    override fun unionShape(shape: UnionShape) {
        logger.info("[python-server-codegen] Generating an union shape $shape")
        rustCrate.useShapeWriter(shape) {
            PythonServerUnionGenerator(model, codegenContext.symbolProvider, this, shape, renderUnknownVariant = false).render()
        }

        if (shape.isReachableFromOperationInput() && shape.canReachConstrainedShape(
                model,
                codegenContext.symbolProvider,
            )
        ) {
            logger.info("[python-server-codegen] Generating an unconstrained type for union shape $shape")
            rustCrate.withModule(ServerRustModule.UnconstrainedModule) modelsModuleWriter@{
                UnconstrainedUnionGenerator(
                    codegenContext,
                    rustCrate.createInlineModuleCreator(),
                    this@modelsModuleWriter,
                    shape,
                ).render()
            }
        }

        if (shape.isEventStream()) {
            rustCrate.withModule(ServerRustModule.Error) {
                ServerOperationErrorGenerator(model, codegenContext.symbolProvider, shape).render(this)
            }
        }
    }

    /**
     * Generate service-specific code for the model:
     * - Serializers
     * - Deserializers
     * - Trait implementations
     * - Protocol tests
     * - Operation structures
     * - Python operation handlers
     */
    override fun serviceShape(shape: ServiceShape) {
        logger.info("[python-server-codegen] Generating a service $shape")
        PythonServerServiceGenerator(
            rustCrate,
            protocolGenerator,
            protocolGeneratorFactory.support(),
            protocolGeneratorFactory.protocol(codegenContext) as ServerProtocol,
            codegenContext,
        )
            .render()
    }

    override fun operationShape(shape: OperationShape) {
        super.operationShape(shape)
        rustCrate.withModule(PythonServerRustModule.PythonOperationAdapter) {
            PythonServerOperationHandlerGenerator(codegenContext, shape).render(this)
        }
    }

    override fun memberShape(shape: MemberShape) {
        super.memberShape(shape)

        if (shape.isEventStream(model)) {
            rustCrate.withModule(PythonServerRustModule.PythonEventStream) {
                PythonServerEventStreamWrapperGenerator(codegenContext, shape).render(this)
            }
        }
    }
}
