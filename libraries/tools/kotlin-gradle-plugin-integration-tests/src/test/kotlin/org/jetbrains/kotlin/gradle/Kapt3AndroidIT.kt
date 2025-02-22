package org.jetbrains.kotlin.gradle

import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.configuration.WarningMode
import org.jetbrains.kotlin.gradle.testbase.TestVersions
import org.jetbrains.kotlin.gradle.util.*
import org.jetbrains.kotlin.test.util.KtTestUtil
import org.junit.Assert
import org.junit.Assume
import org.junit.Ignore
import org.junit.Test
import java.io.File

open class Kapt3Android41IT : Kapt3AndroidIT() {
    override val androidGradlePluginVersion: AGPVersion
        get() = AGPVersion.v4_1_0

    // AGP 3.+ is not working with Gradle 7+
    override val defaultGradleVersion: GradleVersionRequired
        get() = GradleVersionRequired.Until(TestVersions.Gradle.G_6_9)

    @Test
    fun testAndroidxNavigationSafeArgs() = with(Project("androidx-navigation-safe-args", directoryPrefix = "kapt2")) {
        // KT-30735
        build("assembleDebug") {
            assertSuccessful()
            assertFileExists("build/generated/source/navigation-args/debug/test/androidx/navigation/StartFragmentDirections.java")
            assertFileExists("build/tmp/kotlin-classes/debug/test/androidx/navigation/StartFragmentKt.class")
        }
    }

    /** Regression test for Android projects and KT-31127. */
    @Test
    fun testKotlinProcessorUsingFiler() {
        val project = Project("AndroidLibraryKotlinProject").apply {
            setupWorkingDir()
            gradleBuildScript().appendText(
                """
                apply plugin: 'kotlin-kapt'
                android {
                    defaultConfig.javaCompileOptions.annotationProcessorOptions.includeCompileClasspath = false

                    libraryVariants.all {
                        it.generateBuildConfig.enabled = false
                    }
                }

                dependencies {
                    kapt "org.jetbrains.kotlin:annotation-processor-example:${"$"}kotlin_version"
                    implementation "org.jetbrains.kotlin:annotation-processor-example:${"$"}kotlin_version"
                }
            """.trimIndent()
            )

            // The test must not contain any java sources in order to detect the issue.
            Assert.assertEquals(emptyList<File>(), projectDir.allJavaFiles().toList())
            projectDir.getFileByName("Dummy.kt").modify {
                it.replace("class Dummy", "@example.KotlinFilerGenerated class Dummy")
            }
        }

        project.build("assembleDebug") {
            assertSuccessful()
            assertFileExists("build/generated/source/kapt/debug/demo/DummyGenerated.kt")
            assertTasksExecuted(":compileDebugKotlin")
            assertTasksSkipped(":compileDebugJavaWithJavac")
        }
    }
}

class Kapt3Android70IT : Kapt3AndroidIT() {
    override val androidGradlePluginVersion: AGPVersion
        get() = AGPVersion.v7_0_0

    override val defaultGradleVersion: GradleVersionRequired
        get() = GradleVersionRequired.AtLeast(TestVersions.Gradle.G_7_0)

    override fun defaultBuildOptions(): BuildOptions {
        val javaHome = File(System.getProperty("jdk11Home")!!)
        Assume.assumeTrue("JDK 11 should be available", javaHome.isDirectory)
        return super.defaultBuildOptions().copy(javaHome = javaHome, warningMode = WarningMode.Summary)
    }

    @Ignore("KT-44350")
    override fun testRealm() = Unit

    @Ignore("KT-44350")
    override fun testDatabinding() = Unit

    @Ignore("KT-44350")
    override fun testDagger() = Unit

    @Ignore("KT-44350")
    override fun testButterKnife() = Unit

    @Test
    override fun testInterProjectIC() {
        with(Project("android-inter-project-ic", directoryPrefix = "kapt2")) {
            setupWorkingDir()
            // includeCompileClasspath was removed in AGP 7
            projectDir.resolve("app").getFileByName("build.gradle").modify { originalContent ->
                originalContent
                    .lines()
                    .filter { !it.contains("javaCompileOptions") }
                    .joinToString(separator = "\n")
            }
            build("assembleDebug") {
                assertSuccessful()
                assertKaptSuccessful()
            }

            fun modifyAndCheck(utilFileName: String, useUtilFileName: String) {
                val utilKt = projectDir.getFileByName(utilFileName)
                utilKt.modify {
                    it.checkedReplace("Int", "Number")
                }

                build("assembleDebug") {
                    assertSuccessful()
                    val affectedFile = projectDir.getFileByName(useUtilFileName)
                    assertCompiledKotlinSources(
                        relativize(affectedFile),
                        tasks = listOf("app:kaptGenerateStubsDebugKotlin", "app:compileDebugKotlin")
                    )
                }
            }

            modifyAndCheck("libAndroidUtil.kt", "useLibAndroidUtil.kt")
            modifyAndCheck("libJvmUtil.kt", "useLibJvmUtil.kt")
        }
    }
}

class Kapt3Android42IT : BaseGradleIT() {
    companion object {
        private val KAPT_SUCCESSFUL_REGEX = "Annotation processing complete, errors: 0".toRegex()
    }

    private fun kaptOptions(): KaptOptions =
        KaptOptions(verbose = true)

    private fun CompiledProject.assertKaptSuccessful() {
        KAPT_SUCCESSFUL_REGEX.findAll(this.output).count() > 0
    }

    override fun defaultBuildOptions(): BuildOptions =
        super.defaultBuildOptions().copy(
            kaptOptions = kaptOptions(),
            warningMode = WarningMode.Summary,
            androidHome = KtTestUtil.findAndroidSdk(),
            androidGradlePluginVersion = AGPVersion.v4_2_0
        )
    
    /** Regression test for https://youtrack.jetbrains.com/issue/KT-44020. */
    @Test
    fun testDatabindingWithAndroidX() {
        val project = Project("android-databinding-androidX", directoryPrefix = "kapt2")

        project.build("kaptDebugKotlin") {
            assertSuccessful()
            assertKaptSuccessful()
        }
    }
}

abstract class Kapt3AndroidIT : BaseGradleIT() {
    companion object {
        private val KAPT_SUCCESSFUL_REGEX = "Annotation processing complete, errors: 0".toRegex()
    }

    protected open fun kaptOptions(): KaptOptions =
        KaptOptions(verbose = true)

    fun CompiledProject.assertKaptSuccessful() {
        KAPT_SUCCESSFUL_REGEX.findAll(this.output).count() > 0
    }

    protected abstract val androidGradlePluginVersion: AGPVersion

    override fun defaultBuildOptions() =
        super.defaultBuildOptions().copy(
            kaptOptions = kaptOptions(),
            warningMode = WarningMode.Summary,
            androidHome = KtTestUtil.findAndroidSdk(),
            androidGradlePluginVersion = androidGradlePluginVersion
        )

    @Test
    open fun testButterKnife() {
        val project = Project("android-butterknife", directoryPrefix = "kapt2")

        project.build("assembleDebug") {
            assertSuccessful()
            assertKaptSuccessful()
            assertFileExists("app/build/generated/source/kapt/debug/org/example/kotlin/butterknife/SimpleActivity\$\$ViewBinder.java")

            val butterknifeJavaClassesDir =
                "app/build/intermediates/javac/debug/classes/org/example/kotlin/butterknife/"
            assertFileExists(butterknifeJavaClassesDir + "SimpleActivity\$\$ViewBinder.class")

            assertFileExists("app/build/tmp/kotlin-classes/debug/org/example/kotlin/butterknife/SimpleAdapter\$ViewHolder.class")
        }

        project.build("assembleDebug") {
            assertSuccessful()
            assertTasksUpToDate(":compileDebugKotlin", ":compileDebugJavaWithJavac")
        }
    }

    @Test
    open fun testDagger() {
        val project = Project("android-dagger", directoryPrefix = "kapt2")

        project.build("assembleDebug") {
            assertSuccessful()
            assertKaptSuccessful()
            assertFileExists("app/build/generated/source/kapt/debug/com/example/dagger/kotlin/DaggerApplicationComponent.java")
            assertFileExists("app/build/generated/source/kapt/debug/com/example/dagger/kotlin/ui/HomeActivity_MembersInjector.java")

            val daggerJavaClassesDir =
                "app/build/intermediates/javac/debug/classes/com/example/dagger/kotlin/"

            assertFileExists(daggerJavaClassesDir + "DaggerApplicationComponent.class")

            assertFileExists("app/build/tmp/kotlin-classes/debug/com/example/dagger/kotlin/AndroidModule.class")
        }
    }

    @Test
    fun testKt15001() {
        val project = Project("kt15001", directoryPrefix = "kapt2")

        project.build("assembleDebug") {
            assertSuccessful()
            assertKaptSuccessful()
        }
    }

    @Test
    fun testDbFlow() {
        val project = Project(
            "android-dbflow",
            directoryPrefix = "kapt2",
            minLogLevel = LogLevel.INFO
        )

        project.build("assembleDebug") {
            assertSuccessful()
            assertKaptSuccessful()
            assertFileExists("app/build/generated/source/kapt/debug/com/raizlabs/android/dbflow/config/GeneratedDatabaseHolder.java")
            assertFileExists("app/build/generated/source/kapt/debug/com/raizlabs/android/dbflow/config/AppDatabaseAppDatabase_Database.java")
            assertFileExists("app/build/generated/source/kapt/debug/mobi/porquenao/poc/kotlin/core/Item_Table.java")
        }
    }

    @Test
    open fun testRealm() {
        val project = Project("android-realm", directoryPrefix = "kapt2")

        project.build("assembleDebug") {
            assertSuccessful()
            assertKaptSuccessful()
            assertFileExists("build/generated/source/kapt/debug/io/realm/CatRealmProxy.java")
            assertFileExists("build/generated/source/kapt/debug/io/realm/CatRealmProxyInterface.java")
            assertFileExists("build/generated/source/kapt/debug/io/realm/DefaultRealmModule.java")
            assertFileExists("build/generated/source/kapt/debug/io/realm/DefaultRealmModuleMediator.java")
        }
    }

    @Test
    open fun testInterProjectIC() = with(Project("android-inter-project-ic", directoryPrefix = "kapt2")) {
        build("assembleDebug") {
            assertSuccessful()
            assertKaptSuccessful()
        }

        fun modifyAndCheck(utilFileName: String, useUtilFileName: String) {
            val utilKt = projectDir.getFileByName(utilFileName)
            utilKt.modify {
                it.checkedReplace("Int", "Number")
            }

            build("assembleDebug") {
                assertSuccessful()
                val affectedFile = projectDir.getFileByName(useUtilFileName)
                assertCompiledKotlinSources(
                    relativize(affectedFile),
                    tasks = listOf("app:kaptGenerateStubsDebugKotlin", "app:compileDebugKotlin")
                )
            }
        }

        modifyAndCheck("libAndroidUtil.kt", "useLibAndroidUtil.kt")
        modifyAndCheck("libJvmUtil.kt", "useLibJvmUtil.kt")
    }

    @Test
    fun testICWithAnonymousClasses() {
        val project = Project("icAnonymousTypes", directoryPrefix = "kapt2")
        setupDataBinding(project)

        project.build("assembleDebug") {
            assertSuccessful()
            assertKaptSuccessful()
        }

        val aKt = project.projectDir.getFileByName("a.kt").also { assert(it.exists()) }
        aKt.modify {
            assert(it.contains("CrashMe2(1000)"))
            it.replace("CrashMe2(1000)", "CrashMe2(2000)")
        }

        project.build("assembleDebug") {
            assertSuccessful()
            assertKaptSuccessful()
        }
    }

    @Test
    open fun testDatabinding() {
        val javaHome = File(System.getProperty("jdk11Home")!!)
        Assume.assumeTrue("JDK 11 should be available", javaHome.isDirectory)

        val project = Project("android-databinding", directoryPrefix = "kapt2")
        setupDataBinding(project)

        project.build(
            "assembleDebug", "assembleAndroidTest",
            options = defaultBuildOptions().copy(javaHome = javaHome)
        ) {
            assertSuccessful()
            assertKaptSuccessful()
            assertFileExists("app/build/generated/source/kapt/debug/com/example/databinding/BR.java")

            // databinding compiler v2 was introduced in AGP 3.1.0, was enabled by default in AGP 3.2.0
            assertContains("-Aandroid.databinding.enableV2=1")
            assertNoSuchFile("library/build/generated/source/kapt/debugAndroidTest/android/databinding/DataBinderMapperImpl.java")
            assertFileExists("app/build/generated/source/kapt/debug/com/example/databinding/databinding/ActivityTestBindingImpl.java")

            // KT-23866
            assertNotContains("The following options were not recognized by any processor")
        }
    }

    // KT-45532
    @Test
    open fun kaptTasksShouldNotCreateOutputsOnConfigurationPhase() {
        Project(
            "android-dagger",
            directoryPrefix = "kapt2"
        ).build("--dry-run", "assembleDebug") {
            assertSuccessful()
            assertNoSuchFile("app/build/tmp")
            assertNoSuchFile("app/build/generated")
        }
    }

    @Test
    fun testStaticDslOptionsPassedToKapt() = with(Project("android-dagger", directoryPrefix = "kapt2")) {
        setupWorkingDir()

        gradleBuildScript(subproject = "app").appendText(
            """

            apply plugin: 'kotlin-kapt'

            android {
                defaultConfig {
                    javaCompileOptions {
                        annotationProcessorOptions {
                            arguments += ["enable.some.test.option": "true"]
                        }
                    }
                }
            }
            """.trimIndent()
        )

        build(":app:kaptDebugKotlin") {
            assertSuccessful()
            assertContainsRegex(Regex("AP options.*enable\\.some\\.test\\.option=true"))
        }
    }

    @Test
    fun generateStubsTaskShouldRunIncrementallyOnChangesInAndroidVariantJavaSources() {
        with(Project("android-dagger", directoryPrefix = "kapt2")) {
            setupWorkingDir()

            val javaFile = projectDir.resolve("app/src/main/java/com/example/dagger/kotlin/Utils.java")
            javaFile.writeText(
                //language=Java
                """
                package com.example.dagger.kotlin;

                class Utils {
                    public String oneMethod() {
                        return "fake!";
                    }
                }
                """.trimIndent()
            )

            build(":app:kaptDebugKotlin") {
                assertSuccessful()
                assertTasksExecuted(":app:kaptGenerateStubsDebugKotlin")
            }

            javaFile.writeText(
                //language=Java
                """
                package com.example.dagger.kotlin;

                class Utils {
                    public String oneMethod() {
                        return "fake!";
                    }
                    
                    public void anotherMethod() {
                        int one = 1;
                    }
                }
                """.trimIndent()
            )

            build(":app:kaptDebugKotlin") {
                assertSuccessful()
                assertTasksExecuted(":app:kaptGenerateStubsDebugKotlin")
                assertNotContains(
                    "The input changes require a full rebuild for incremental task ':app:kaptGenerateStubsDebugKotlin'."
                )
            }
        }
    }

    private fun setupDataBinding(project: Project) {
        project.setupWorkingDir()

        project.gradleBuildScript().modify {
            it + "\n\n" + """
                allprojects {
                    plugins.withId("kotlin-kapt") {
                        println("${'$'}project android.databinding.enableV2=${'$'}{project.findProperty('android.databinding.enableV2')}")

                        // With new AGP, there's no need in the Databinding kapt dependency:
                        configurations.kapt.exclude group: "com.android.databinding", module: "compiler"
                    }
                }
            """.trimIndent()
        }
    }
}
