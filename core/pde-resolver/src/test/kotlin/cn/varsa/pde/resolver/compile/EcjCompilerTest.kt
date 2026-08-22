package cn.varsa.pde.resolver.compile

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EcjCompilerTest {

  @Rule
  @JvmField
  val temp = TemporaryFolder()

  @Test
  fun `compiles simple java sources`() {
    val bundle = temp.newFolder("bundle").toPath()
    val srcDir = bundle.resolve("src/main/java").also { it.toFile().mkdirs() }
    val javaFile = srcDir.resolve("Example.java").toFile()
    javaFile.writeText(
      """
        package demo;
        public class Example { public String greet() { return "hi"; } }
      """.trimIndent()
    )

    val spec = CompileSpec(
      bsn = "demo.example",
      version = "1.0.0",
      origin = "workspace",
      bundlePath = bundle.toString(),
      classpath = emptyList(),
      sourceRoots = listOf(srcDir.toString()),
      resourceIncludes = emptyList(),
      resourceExcludes = emptyList(),
      compilerPrefs = mapOf("org.eclipse.jdt.core.compiler.source" to "17"),
      executionEnvironment = "JavaSE-17",
      outputDirectory = bundle.resolve("bin").toString(),
      isWorkspace = true
    )

    val result = EcjCompiler().compile(spec)

    assertTrue(result.success, "ECJ should compile simple sources")
    assertTrue(bundle.resolve("bin/demo/Example.class").toFile().exists(), "Class file should be emitted")
  }

  @Test
  fun `fails fast when annotation processors present`() {
    val bundle = temp.newFolder("bundle2").toPath()
    val srcDir = bundle.resolve("src").also { it.toFile().mkdirs() }
    srcDir.resolve("Dummy.java").toFile().writeText("class Dummy {}")

    // Create a fake processor jar with service entry
    val procJar = temp.newFile("processor.jar")
    JarOutputStream(procJar.outputStream()).use { jar ->
      jar.putNextEntry(JarEntry("META-INF/services/javax.annotation.processing.Processor"))
      jar.write("com.example.Processor".toByteArray())
      jar.closeEntry()
    }

    val spec = CompileSpec(
      bsn = "demo.processor",
      version = "1.0.0",
      origin = "workspace",
      bundlePath = bundle.toString(),
      classpath = listOf(procJar.absolutePath),
      sourceRoots = listOf(srcDir.toString()),
      resourceIncludes = emptyList(),
      resourceExcludes = emptyList(),
      compilerPrefs = emptyMap(),
      executionEnvironment = null,
      outputDirectory = bundle.resolve("bin").toString(),
      isWorkspace = true
    )

    val result = EcjCompiler().compile(spec)

    assertFalse(result.success, "Compile should fail fast when processors are detected")
    assertTrue(result.output.contains("Annotation processors"), "Output should mention processors")
  }

  /** Bundle with one class containing a non-externalized string literal (ECJ default: ignore). */
  private fun nlsSpec(name: String, extraPrefs: Map<String, String>): CompileSpec {
    val bundle = temp.newFolder(name).toPath()
    val srcDir = bundle.resolve("src").also { it.toFile().mkdirs() }
    srcDir.resolve("Nls.java").toFile().writeText(
      """
        package demo;
        public class Nls { public String greet() { return "hi"; } }
      """.trimIndent()
    )
    return CompileSpec(
      bsn = "demo.$name",
      version = "1.0.0",
      origin = "workspace",
      bundlePath = bundle.toString(),
      classpath = emptyList(),
      sourceRoots = listOf(srcDir.toString()),
      resourceIncludes = emptyList(),
      resourceExcludes = emptyList(),
      compilerPrefs = mapOf("org.eclipse.jdt.core.compiler.source" to "17") + extraPrefs,
      executionEnvironment = "JavaSE-17",
      outputDirectory = bundle.resolve("bin").toString(),
      isWorkspace = true
    )
  }

  private val nlsKey = "org.eclipse.jdt.core.compiler.problem.nonExternalizedStringLiteral"

  @Test
  fun `without prefs the ignored-by-default problem stays silent`() {
    val result = EcjCompiler().compile(nlsSpec("nls-silent", emptyMap()))

    assertTrue(result.success)
    assertEquals(0, result.warningCount, "ECJ default for non-externalized strings is ignore")
    assertEquals(0, result.infoCount)
  }

  @Test
  fun `prefs severity warning is honored and counted`() {
    val result = EcjCompiler().compile(nlsSpec("nls-warn", mapOf(nlsKey to "warning")))

    assertTrue(result.success, "warnings must not fail the compile")
    assertTrue(result.warningCount >= 1, "expected a warning, output: ${result.output}")
    assertEquals(0, result.infoCount)
    assertTrue(result.output.contains("WARNING in"), result.output)
  }

  @Test
  fun `prefs severity info is honored and counted separately`() {
    val result = EcjCompiler().compile(nlsSpec("nls-info", mapOf(nlsKey to "info")))

    assertTrue(result.success, "infos must not fail the compile")
    assertTrue(result.infoCount >= 1, "expected an info, output: ${result.output}")
    assertEquals(0, result.warningCount)
    assertTrue(result.output.contains("INFO in"), result.output)
  }

  @Test
  fun `prefs severity error fails the compile`() {
    val result = EcjCompiler().compile(nlsSpec("nls-error", mapOf(nlsKey to "error")))

    assertFalse(result.success, "error severity must fail the compile")
    assertTrue(result.output.contains("ERROR in"), result.output)
  }


  @Test
  fun `diagnostic prefs coexist with the explicit language level`() {
    // Java-17-only construct plus a diagnostic pref: the -properties file carries only diagnostic
    // configuration, so the level keys in compilerPrefs cannot interfere with the explicit
    // -source/-target while the problem severity still takes effect.
    val bundle = temp.newFolder("levels").toPath()
    val srcDir = bundle.resolve("src").also { it.toFile().mkdirs() }
    srcDir.resolve("Rec.java").toFile().writeText(
      """
        package demo;
        public record Rec(int x) { public String label() { return "x"; } }
      """.trimIndent()
    )
    val spec = CompileSpec(
      bsn = "demo.levels",
      version = "1.0.0",
      origin = "workspace",
      bundlePath = bundle.toString(),
      classpath = emptyList(),
      sourceRoots = listOf(srcDir.toString()),
      resourceIncludes = emptyList(),
      resourceExcludes = emptyList(),
      compilerPrefs = mapOf(
        "org.eclipse.jdt.core.compiler.source" to "17",
        "org.eclipse.jdt.core.compiler.compliance" to "17",
        nlsKey to "warning"
      ),
      executionEnvironment = "JavaSE-17",
      outputDirectory = bundle.resolve("bin").toString(),
      isWorkspace = true
    )

    val result = EcjCompiler().compile(spec)

    assertTrue(result.success, "record at level 17 should compile, output: ${result.output}")
    assertTrue(result.warningCount >= 1, "diagnostic pref should fire, output: ${result.output}")
  }
}
