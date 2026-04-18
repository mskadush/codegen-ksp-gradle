import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName

@Suppress("UNCHECKED_CAST")
internal fun KSAnnotation.dbAnnotationSpecs(): List<AnnotationSpec> {
    val list = arguments.firstOrNull { it.name?.asString() == "annotations" }
        ?.value as? List<*> ?: return emptyList()
    return list.filterIsInstance<KSAnnotation>().mapNotNull { dbAnn ->
        val ksType = dbAnn.arguments.firstOrNull { it.name?.asString() == "annotation" }
            ?.value as? KSType ?: return@mapNotNull null
        val decl = ksType.declaration
        val pkg = decl.packageName.asString()
        val cls = decl.simpleName.asString()
        val specBuilder = AnnotationSpec.builder(ClassName(pkg, cls))
        val members = dbAnn.arguments.firstOrNull { it.name?.asString() == "members" }
            ?.value as? List<*> ?: emptyList<Any>()
        members.filterIsInstance<KSAnnotation>().forEach { member ->
            val mName = member.arguments.firstOrNull { it.name?.asString() == "name" }
                ?.value as? String ?: return@forEach
            val mValue = member.arguments.firstOrNull { it.name?.asString() == "value" }
                ?.value as? String ?: return@forEach
            specBuilder.addMember("$mName = $mValue")
        }
        specBuilder.build()
    }
}
