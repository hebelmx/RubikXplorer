package dev.cubentia.domain.cube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class SingleGateTest {
    private val domainPackage = "dev.cubentia.domain"

    /** Every compiled class under [domainPackage] in the production output directory. */
    private val classes: List<Class<*>> by lazy {
        val root =
            File(
                CubeValidator::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val dir = File(root, domainPackage.replace('.', '/'))
        check(dir.isDirectory) { "no classes in $dir" }
        dir
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map {
                it
                    .relativeTo(root)
                    .path
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
            }.map { Class.forName(it, false, javaClass.classLoader) }
            .toList()
    }

    private val gated = listOf(CubeState::class.java, Cubies::class.java, ValidationResult::class.java)

    /** True when [e] returns a gated type, a subtype of one, or a generic type that mentions one (e.g. `List<CubeState>`). */
    private fun returnsGated(e: Executable): Boolean {
        if (e !is Method) return gated.any { it.isAssignableFrom(e.declaringClass) }
        return gated.any { it.isAssignableFrom(e.returnType) || it.name in e.genericReturnType.typeName }
    }

    @Test
    fun `CubeValidator validate is the only public route from Facelets to a cube`() {
        assertTrue(classes.contains(Facelets::class.java) && classes.contains(ValidationResult.Valid::class.java), "$classes")
        assertTrue(classes.contains(dev.cubentia.domain.text.Text::class.java), "$classes")
        val routes =
            classes
                .flatMap { it.declaredMethods.toList() + it.declaredConstructors.toList() }
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name }
                .filter { e -> e.parameterTypes.contains(Facelets::class.java) }
                .filter { returnsGated(it) }
                .map { "${it.declaringClass.simpleName}.${it.name}" }
                .sorted()
        // problems reports failures only: its elements are Invalid, which carries no state.
        assertEquals(listOf("CubeValidator.problems", "CubeValidator.validate"), routes)
    }

    @Test
    fun `CubeValidator problems returns only Invalid results`() {
        val problems = CubeValidator::class.java.getMethod("problems", Facelets::class.java)
        assertEquals(
            "java.util.List<${ValidationResult.Invalid::class.java.name}>",
            problems.genericReturnType.typeName.replace("? extends ", ""),
        )
    }

    @Test
    fun `Valid has no public constructor`() {
        val public = ValidationResult.Valid::class.java.constructors.filter { !it.isSynthetic }
        assertEquals(emptyList<Any>(), public)
    }
}
