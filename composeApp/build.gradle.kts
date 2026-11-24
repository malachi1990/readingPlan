import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    jvm("desktop")
    
    sourceSets {
        val desktopMain by getting
        
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.crosswire.jsword)
            implementation(libs.jdom2)
            implementation(libs.commons.net)
            implementation(libs.commons.codec)
            implementation(libs.commons.compress)
            implementation(libs.slf4j.simple)
            implementation(libs.pdfbox)
            implementation(libs.jlayer)
            implementation(libs.jump3r)
        }
    }
}

val appId = "org.bible.reading.App"
val appPackageName = "org.bible.reading"
val appVersion = "1.0.0"

compose.desktop {
    application {
        mainClass = "org.bible.reading.MainKt"

        nativeDistributions {
            targetFormats(
                TargetFormat.Exe,
                TargetFormat.Msi,
                TargetFormat.Dmg,
                TargetFormat.Deb,
                TargetFormat.Rpm
            )
            packageName = appPackageName
            packageVersion = appVersion
            description = "Bible Reading Plan Generator"
            vendor = "Bible Reading Plan"

            modules(
                "java.instrument",
                "java.management",
                "java.naming",
                "java.prefs",
                "java.sql",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.unsupported"
            )

            windows {
                shortcut = true
                menuGroup = "Bible Reading Plan"
                perUserInstall = true
                upgradeUuid = "8e6c7b40-9f5c-4d3e-8d1a-5b4f6e2c1a90"
            }

            macOS {
                bundleID = appId
            }

            linux {
                shortcut = true
                menuGroup = "Education"
                appCategory = "Education"
            }
        }
    }
}

val packageFlatpak by tasks.registering {
    group = "compose desktop"
    description = "Packages the Linux distributable into a single-file Flatpak bundle (.flatpak)"
    dependsOn("createDistributable")

    val distAppDir = layout.buildDirectory.dir("compose/binaries/main/app/$appPackageName")
    val flatpakAssetsDir = rootProject.layout.projectDirectory.dir("flatpak")
    val flatpakWorkDir = layout.buildDirectory.dir("flatpak")
    val flatpakOutDir = layout.buildDirectory.dir("compose/binaries/main/flatpak")
    val bundleFile = flatpakOutDir.map { it.file("$appPackageName-$appVersion.flatpak") }
    val rootDir = rootProject.layout.projectDirectory.asFile
    val pkgName = appPackageName
    val flatpakAppId = appId

    inputs.dir(distAppDir)
    inputs.dir(flatpakAssetsDir)
    outputs.file(bundleFile)

    doLast {
        check(System.getProperty("os.name").lowercase().contains("linux")) {
            "Flatpak packaging is only supported on Linux hosts."
        }

        val appSourceDir = distAppDir.get().asFile
        check(appSourceDir.exists()) {
            "Distributable directory not found at: ${appSourceDir.absolutePath}"
        }

        val workDir = flatpakWorkDir.get().asFile
        val buildDir = workDir.resolve("build-dir")
        val repoDir = workDir.resolve("repo")
        val outFile = bundleFile.get().asFile

        workDir.deleteRecursively()
        outFile.parentFile.mkdirs()

        fun findExecutable(name: String): String? {
            val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
            return pathDirs
                .map { dir -> File(dir, name) }
                .firstOrNull { file -> file.exists() && file.canExecute() }
                ?.absolutePath
        }

        fun runCommand(vararg command: String) {
            val process = ProcessBuilder(command.toList())
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException("Command failed ($exitCode): ${command.joinToString(" ")}\n$output")
            }
        }

        val flatpakBuilderBin = findExecutable("flatpak-builder")
        val flatpakBin = findExecutable("flatpak")
            ?: throw GradleException(
                "Neither 'flatpak' nor 'flatpak-builder' was found on PATH. Install flatpak (and optionally flatpak-builder) to build a Flatpak bundle."
            )

        val manifestFile = flatpakAssetsDir.file("$flatpakAppId.yml").asFile
        if (flatpakBuilderBin != null) {
            runCommand(
                flatpakBuilderBin,
                "--force-clean",
                "--repo=${repoDir.absolutePath}",
                buildDir.absolutePath,
                manifestFile.absolutePath
            )
        } else {
            val runtimeName = "org.freedesktop.Platform"
            val sdkName = "org.freedesktop.Sdk"
            val preferredBranches = listOf("24.08", "25.08", "23.08", "26.08", "22.08")

            val listProc = ProcessBuilder(flatpakBin, "list", "--runtime", "--columns=application,branch")
                .redirectErrorStream(true)
                .start()
            val installedLines = listProc.inputStream.bufferedReader().readLines()
            listProc.waitFor()

            val installedPlatforms = installedLines.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[0] == runtimeName) parts[1] else null
            }.toSet()
            val installedSdks = installedLines.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[0] == sdkName) parts[1] else null
            }.toSet()

            val runtimeBranch = preferredBranches.firstOrNull { it in installedPlatforms }
                ?: installedPlatforms.firstOrNull()
                ?: throw GradleException(
                    "No $runtimeName Flatpak runtime is installed. Run: flatpak install flathub $runtimeName//24.08"
                )
            val sdkBranch = when {
                runtimeBranch in installedSdks -> runtimeBranch
                else -> preferredBranches.firstOrNull { it in installedSdks }
                    ?: installedSdks.firstOrNull()
                    ?: runtimeBranch
            }

            runCommand(
                flatpakBin,
                "build-init",
                buildDir.absolutePath,
                flatpakAppId,
                "$sdkName//$sdkBranch",
                "$runtimeName//$runtimeBranch",
                runtimeBranch
            )

            val filesDir = buildDir.resolve("files")
            val targetAppLibDir = filesDir.resolve("lib/$pkgName")
            targetAppLibDir.mkdirs()
            appSourceDir.copyRecursively(targetAppLibDir, overwrite = true)

            // Preserve executable bits on launcher and bundled JRE binaries
            targetAppLibDir.resolve("bin/$pkgName").setExecutable(true, false)
            targetAppLibDir.resolve("lib/runtime/bin").listFiles()?.forEach { it.setExecutable(true, false) }
            targetAppLibDir.resolve("lib/runtime/lib/jspawnhelper").let {
                if (it.exists()) it.setExecutable(true, false)
            }

            val binDir = filesDir.resolve("bin").apply { mkdirs() }
            Files.createSymbolicLink(
                binDir.resolve(pkgName).toPath(),
                Paths.get("/app/lib/$pkgName/bin/$pkgName")
            )

            val desktopTarget = filesDir.resolve("share/applications/$flatpakAppId.desktop")
            desktopTarget.parentFile.mkdirs()
            flatpakAssetsDir.file("$flatpakAppId.desktop").asFile.copyTo(desktopTarget, overwrite = true)

            val metainfoTarget = filesDir.resolve("share/metainfo/$flatpakAppId.metainfo.xml")
            metainfoTarget.parentFile.mkdirs()
            flatpakAssetsDir.file("$flatpakAppId.metainfo.xml").asFile.copyTo(metainfoTarget, overwrite = true)

            val svgIconFile = flatpakAssetsDir.file("$flatpakAppId.svg").asFile
            if (svgIconFile.exists()) {
                val svgTarget = filesDir.resolve("share/icons/hicolor/scalable/apps/$flatpakAppId.svg")
                svgTarget.parentFile.mkdirs()
                svgIconFile.copyTo(svgTarget, overwrite = true)
            }

            val iconSource = appSourceDir.resolve("lib/$pkgName.png")
            if (iconSource.exists()) {
                val srcImg = ImageIO.read(iconSource)
                if (srcImg != null) {
                    val scaled = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
                    val g2d = scaled.createGraphics()
                    try {
                        g2d.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC
                        )
                        g2d.drawImage(srcImg, 0, 0, 512, 512, null)
                    } finally {
                        g2d.dispose()
                    }
                    val iconTarget = filesDir.resolve("share/icons/hicolor/512x512/apps/$flatpakAppId.png")
                    iconTarget.parentFile.mkdirs()
                    ImageIO.write(scaled, "PNG", iconTarget)
                }
            }

            runCommand(
                flatpakBin,
                "build-finish",
                buildDir.absolutePath,
                "--command=$pkgName",
                "--share=ipc",
                "--socket=x11",
                "--socket=wayland",
                "--socket=pulseaudio",
                "--share=network",
                "--device=dri",
                "--filesystem=home"
            )

            runCommand(
                flatpakBin,
                "build-export",
                repoDir.absolutePath,
                buildDir.absolutePath
            )
        }

        runCommand(
            flatpakBin,
            "build-bundle",
            repoDir.absolutePath,
            outFile.absolutePath,
            flatpakAppId
        )

        logger.lifecycle("Flatpak bundle written to ${outFile.absolutePath}")
    }
}

tasks.register("buildFlatpak") {
    group = "compose desktop"
    description = "Alias for packageFlatpak: builds a single-file Flatpak bundle (.flatpak)"
    dependsOn(packageFlatpak)
}
