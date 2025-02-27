package scenery

plugins {
    jacoco
}

tasks {

    // https://docs.gradle.org/current/userguide/java_testing.html#test_filtering
    test {
        // apparently `testLotsOfProteins` needs also a lot of heap..
        maxHeapSize = "8G"
        // [Debug] before running every test, prints out its name
        //        beforeTest(closureOf<TestDescriptor?> { logger.lifecycle("Running test: $this") })
        val gpuPresent = project.properties["gpu"]?.toString()?.toBoolean() == true
        //        println("gpuPresent=$gpuPresent")
        if (!gpuPresent) {
            filter { excludeTestsMatching("ExampleRunner") }
        } else {
            val testGroup = System.getProperty("scenery.ExampleRunner.TestGroup", "unittest")
            val testConfig = System.getProperty("scenery.ExampleRunner.Configurations", "None")

            configure<JacocoTaskExtension> {
                setDestinationFile(file("$buildDir/jacoco/jacocoTest.$testGroup.$testConfig.exec"))
                println("Destination file for jacoco is $destinationFile (test, $testGroup, $testConfig)")
            }

            filter { excludeTestsMatching("graphics.scenery.tests.unit.**") }

            // this should circumvent Nvidia's Vulkan cleanup issue
            maxParallelForks = 2
            setForkEvery(8)

            // we only want the Vulkan renderer here, and all screenshot to be stored in the screenshots/ dir
            systemProperty("scenery.Renderer", "VulkanRenderer")
            systemProperty("scenery.ExampleRunner.OutputDir", "screenshots")

            val props = System.getProperties().filter { (k, _) -> k.toString().startsWith("scenery.") }

            println("Adding properties ${props.size}/$props")
            val additionalArgs = System.getenv("SCENERY_JVM_ARGS")
            allJvmArgs = if (additionalArgs != null) {
                allJvmArgs + props.flatMap { (k, v) -> listOf("-D$k=$v") } + additionalArgs
            } else {
                allJvmArgs + props.flatMap { (k, v) -> listOf("-D$k=$v") }
            }
        }

        finalizedBy(jacocoTestReport) // report is always generated after tests run
    }

    register("testGpu", Test::class) { // lets take this for comfortability in local development
        maxHeapSize = "8G"
        group = "verification"
        filter { includeTestsMatching("ExampleRunner") }

        val testGroup = System.getProperty("scenery.ExampleRunner.TestGroup", "basic")
        extensions.configure(JacocoTaskExtension::class) {
            setDestinationFile(layout.buildDirectory.file("jacoco/jacocoTest.$testGroup.exec").get().asFile)
        }
    }

    register("compileShader", JavaExec::class) {
        group = "tools"
        mainClass.set("graphics.scenery.backends.ShaderCompiler")
        classpath = sourceSets["main"].runtimeClasspath

    }

    register("fullCodeCoverageReport", JacocoReport::class) {
        executionData(fileTree(project.rootDir.absolutePath).include("**/build/jacoco/*.exec"))

        sourceSets(sourceSets["main"], sourceSets["test"])

        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }

        dependsOn(test)
    }

    named<Jar>("jar") {
        archiveVersion.set(rootProject.version.toString())
        manifest.attributes["Implementation-Build"] = run { // retrieve the git commit hash
            val gitFolder = "$projectDir/.git/"
            val digit = 6
            /*  '.git/HEAD' contains either
             *      in case of detached head: the currently checked out commit hash
             *      otherwise: a reference to a file containing the current commit hash     */
            val head = file(gitFolder + "HEAD").readText().split(":") // .git/HEAD
            val isCommit = head.size == 1 // e5a7c79edabbf7dd39888442df081b1c9d8e88fd
            // def isRef = head.length > 1     // ref: refs/heads/master
            when {
                isCommit -> head[0] // e5a7c79edabb
                else -> file(gitFolder + head[1].trim()) // .git/refs/heads/master
                    .readText()
            }.trim().take(digit)
        }
    }

    jacocoTestReport {
        reports {
            xml.isEnabled = true
            html.apply {
                isEnabled = false
                //                destination = file("$buildDir/jacocoHtml")
            }
        }
        dependsOn(test) // tests are required to run before generating the report
    }

    // This registers gradle tasks for all examples
    sourceSets.test.get().allSource.files
        .filter { it.name.endsWith("Example.kt") }
        .map { it.path.substringAfter("kotlin${File.separatorChar}").replace(File.separatorChar, '.').substringBefore(".kt") }
        .forEach { className ->
            val exampleName = className.substringAfterLast(".")
            val exampleType = className.substringBeforeLast(".").substringAfterLast(".")

            register<JavaExec>(name = className.substringAfterLast(".")) {
                classpath = sourceSets.test.get().runtimeClasspath
                main = className
                group = "examples.$exampleType"

                val props = System.getProperties().filter { (k, _) -> k.toString().startsWith("scenery.") }

                val additionalArgs = System.getenv("SCENERY_JVM_ARGS")
                allJvmArgs = if (additionalArgs != null) {
                    allJvmArgs + props.flatMap { (k, v) -> listOf("-D$k=$v") } + additionalArgs
                } else {
                    allJvmArgs + props.flatMap { (k, v) -> listOf("-D$k=$v") }
                }
            }
        }

    register<JavaExec>("run") {
        classpath = sourceSets.test.get().runtimeClasspath
        if (project.hasProperty("example")) {
            project.property("example")?.let { example ->
                val file = sourceSets.test.get().allSource.files.first { "class $example" in it.readText() }
                main = file.path.substringAfter("kotlin${File.separatorChar}").replace(File.separatorChar, '.').substringBefore(".kt")
                val props = System.getProperties().filter { (k, _) -> k.toString().startsWith("scenery.") }

                val additionalArgs = System.getenv("SCENERY_JVM_ARGS")
                allJvmArgs = if (additionalArgs != null) {
                    allJvmArgs + props.flatMap { (k, v) -> listOf("-D$k=$v") } + additionalArgs
                } else {
                    allJvmArgs + props.flatMap { (k, v) -> listOf("-D$k=$v") }
                }

                println("Will run example $example with classpath $classpath, main=$main")
                println("JVM arguments passed to example: $allJvmArgs")
            }
        }
    }
}

val TaskContainer.jacocoTestReport: TaskProvider<JacocoReport>
    get() = named<JacocoReport>("jacocoTestReport")

val TaskContainer.test: TaskProvider<Test>
    get() = named<Test>("test")

val Project.sourceSets: SourceSetContainer
    get() = (this as ExtensionAware).extensions.getByName("sourceSets") as SourceSetContainer

val SourceSetContainer.test: NamedDomainObjectProvider<SourceSet>
    get() = named<SourceSet>("test")
