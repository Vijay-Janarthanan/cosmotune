package com.cosmotune.video.source.newpipe

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * NewPipe stays behind `VideoSource` (plan task Y-4, step 7): nothing of Video outside
 * `shared/src/<source set>/kotlin/com/cosmotune/video/source/newpipe/` mentions `org.schabi.newpipe`, so a rewrite of the
 * extractor's stream API (NewPipe issue #858) touches one folder. Music's own files (`com.music.bitchord`) are not Video's.
 */
class NewPipeImportGuardTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
    }

    /** Every source file under a `com/cosmotune/video` folder of [moduleSrc] (one level of source sets, then `kotlin` or `java`). */
    private fun videoSources(moduleSrc: File): List<File> {
        if (!moduleSrc.isDirectory) return emptyList()
        val out = mutableListOf<File>()
        for (sourceSet in moduleSrc.listFiles().orEmpty().filter { it.isDirectory }) {
            for (language in listOf("kotlin", "java")) {
                val root = File(sourceSet, "$language/com/cosmotune/video")
                if (root.isDirectory) {
                    root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { out += it }
                }
            }
        }
        return out
    }

    private fun isComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
    }

    private fun offenders(files: List<File>, allowedFolder: String?): List<String> = files
        .filter { allowedFolder == null || !it.invariantSeparatorsPath.contains(allowedFolder) }
        .filter { file -> file.readLines().any { "org.schabi.newpipe" in it && !isComment(it) } }
        .map { it.invariantSeparatorsPath }

    @Test
    fun noFileOfSharedOutsideTheAdapterFolderMentionsNewPipe() {
        val root = repoRoot()
        val shared = videoSources(File(root, "shared/src"))
        assertTrue(shared.isNotEmpty(), "the guard found no Video source under shared/src")
        assertTrue(shared.any { "/source/newpipe/" in it.invariantSeparatorsPath }, "the adapter folder is where the guard expects it")
        val bad = offenders(shared, "/com/cosmotune/video/source/newpipe/")
        assertTrue(bad.isEmpty(), "NewPipe is imported outside source/newpipe: $bad")
    }

    @Test
    fun noFileOfTheVideoModuleMentionsNewPipe() {
        val bad = offenders(videoSources(File(repoRoot(), "video/src")), null)
        assertTrue(bad.isEmpty(), "NewPipe is imported in :video: $bad")
    }

    @Test
    fun noVideoFileOfTheTwoApplicationsMentionsNewPipe() {
        val root = repoRoot()
        val bad = offenders(videoSources(File(root, "app/src")) + videoSources(File(root, "desktopApp/src")), null)
        assertTrue(bad.isEmpty(), "NewPipe is imported in an application's Video host: $bad")
    }
}
