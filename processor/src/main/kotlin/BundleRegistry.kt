import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration

/**
 * Index of all [@FieldBundle]-annotated classes, keyed by their declared [name].
 *
 * Built once at the end of Pass 1 by [build] and injected into all generators before Pass 2.
 */
data class BundleRegistry(val bundles: Map<String, KSClassDeclaration>) {

    companion object {
        val EMPTY = BundleRegistry(emptyMap())

        fun build(resolver: Resolver, logger: KSPLogger): BundleRegistry {
            val result = mutableMapOf<String, KSClassDeclaration>()

            resolver.getSymbolsWithAnnotation("com.example.annotations.FieldBundle")
                .filterIsInstance<KSClassDeclaration>()
                .forEach { decl ->
                    val ann = decl.annotations.firstOrNull {
                        it.shortName.asString() == "FieldBundle"
                    } ?: return@forEach

                    val name = ann.argString("name")
                    if (name.isBlank()) {
                        logger.error("@FieldBundle on ${decl.simpleName.asString()} has a blank name")
                        return@forEach
                    }

                    val existing = result[name]
                    if (existing != null) {
                        logger.error(
                            "Duplicate @FieldBundle name '$name': declared on both " +
                                "${existing.simpleName.asString()} and ${decl.simpleName.asString()}"
                        )
                        return@forEach
                    }

                    result[name] = decl
                }

            return BundleRegistry(result)
        }
    }
}
