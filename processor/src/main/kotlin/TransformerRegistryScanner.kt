import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration

/**
 * Scans all `@TransformerRegistry`-annotated objects and builds a name → reference map.
 *
 * Each property annotated with `@RegisterTransformer(name)` inside a registry object is
 * recorded as `name → "com.example.app.MyRegistry.myProp"`. The resulting map is passed
 * to [MapperGenerator] so that `transformerRef = "name"` in field specs can be resolved
 * to an actual property reference.
 */
class TransformerRegistryScanner(private val logger: KSPLogger) {

    /**
     * Returns a map of transformer name → fully-qualified property reference
     * (e.g. `"upperCase"` → `"com.example.app.AppTransformerRegistry.upperCase"`).
     */
    fun scan(resolver: Resolver): Map<String, String> {
        val registry = mutableMapOf<String, String>()
        resolver.getSymbolsWithAnnotation("com.example.annotations.TransformerRegistry")
            .filterIsInstance<KSClassDeclaration>()
            .forEach { cls ->
                val objectFQN = cls.qualifiedName?.asString() ?: run {
                    logger.warn("TransformerRegistryScanner: skipping unnamed @TransformerRegistry class")
                    return@forEach
                }
                cls.getAllProperties()
                    .filter { prop ->
                        prop.annotations.any { it.shortName.asString() == "RegisterTransformer" }
                    }
                    .forEach { prop ->
                        val regAnnotation = prop.annotations
                            .first { it.shortName.asString() == "RegisterTransformer" }
                        val name = regAnnotation.arguments
                            .firstOrNull { it.name?.asString() == "name" }
                            ?.value as? String ?: run {
                            logger.warn(
                                "TransformerRegistryScanner: @RegisterTransformer on " +
                                    "$objectFQN.${prop.simpleName.asString()} has no name"
                            )
                            return@forEach
                        }
                        val ref = "$objectFQN.${prop.simpleName.asString()}"
                        registry[name] = ref
                        logger.info("TransformerRegistryScanner: registered '$name' -> $ref")
                    }
            }
        return registry
    }
}
