package com.trirrin.xiaoshuo

import com.trirrin.xiaoshuo.model.Chapter
import com.trirrin.xiaoshuo.model.Novel
import com.trirrin.xiaoshuo.model.Scene
import com.trirrin.xiaoshuo.model.SceneStatus
import com.trirrin.xiaoshuo.model.SceneTextSource
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ProjectStats(
    val totalWords: Int,
    val approvedScenes: Int,
    val generatedScenes: Int,
    val editedScenes: Int,
    val pendingScenes: Int,
    val chapterCount: Int,
    val sceneCount: Int,
    val bibleEntryCount: Int,
)

fun buildMarkdownManuscript(
    novel: Novel,
    chapters: List<Chapter>,
    scenes: List<Scene>,
): String {
    return buildManuscript(
        novel = novel,
        chapters = chapters,
        scenes = scenes,
        titlePrefix = "# ",
        chapterPrefix = "## ",
    )
}

fun buildTxtManuscript(
    novel: Novel,
    chapters: List<Chapter>,
    scenes: List<Scene>,
): String {
    return buildManuscript(
        novel = novel,
        chapters = chapters,
        scenes = scenes,
        titlePrefix = "",
        chapterPrefix = "",
    )
}

fun buildEpubManuscript(
    novel: Novel,
    chapters: List<Chapter>,
    scenes: List<Scene>,
): ByteArray {
    val sortedChapters = chapters.sortedChaptersForManuscript()
    val scenesByChapterId = scenes.groupBy { scene -> scene.chapterId }
    val chapterDocuments = sortedChapters.mapIndexed { index, chapter ->
        EpubChapterDocument(
            fileName = "chapter-${index + 1}.xhtml",
            title = chapter.exportTitle(),
            body = chapter.toXhtmlBody(scenesByChapterId[chapter.id].orEmpty().sortedScenesForManuscript()),
        )
    }
    val orphanScenes = scenes
        .filter { scene -> scene.chapterId !in sortedChapters.map { it.id }.toSet() }
        .sortedScenesForManuscript()
    val documents = if (orphanScenes.isEmpty()) {
        chapterDocuments
    } else {
        chapterDocuments + EpubChapterDocument(
            fileName = "unassigned-scenes.xhtml",
            title = "Unassigned Scenes",
            body = scenesToXhtmlBody(orphanScenes),
        )
    }

    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        zip.putStoredText("mimetype", "application/epub+zip")
        zip.putText("META-INF/container.xml", epubContainerXml())
        zip.putText("OEBPS/content.opf", epubContentOpf(novel, documents))
        zip.putText("OEBPS/nav.xhtml", epubNav(novel, documents))
        documents.forEach { document ->
            zip.putText("OEBPS/${document.fileName}", epubChapterXhtml(document))
        }
    }
    return output.toByteArray()
}

fun computeProjectStats(
    novel: Novel,
    chapters: List<Chapter>,
    scenes: List<Scene>,
): ProjectStats {
    return ProjectStats(
        totalWords = scenes.sumOf { scene -> countWords(scene.text) },
        approvedScenes = scenes.count { scene -> scene.status == SceneStatus.APPROVED },
        generatedScenes = scenes.count { scene -> scene.status.isGenerated() },
        editedScenes = scenes.count { scene -> scene.textSource == SceneTextSource.EDITED },
        pendingScenes = scenes.count { scene -> scene.status == SceneStatus.PENDING },
        chapterCount = chapters.size,
        sceneCount = scenes.size,
        bibleEntryCount = novel.bible.characters.size +
            novel.bible.locations.size +
            novel.bible.timelineEvents.size +
            novel.bible.worldRules.size +
            novel.bible.themes.size,
    )
}

private fun buildManuscript(
    novel: Novel,
    chapters: List<Chapter>,
    scenes: List<Scene>,
    titlePrefix: String,
    chapterPrefix: String,
): String {
    val scenesByChapterId = scenes.groupBy { scene -> scene.chapterId }
    val chapterIds = chapters.map { chapter -> chapter.id }.toSet()
    val orphanScenes = scenes
        .filter { scene -> scene.chapterId !in chapterIds }
        .sortedScenesForManuscript()

    return buildString {
        appendLine(titlePrefix + novel.title.trim())

        chapters.sortedChaptersForManuscript().forEach { chapter ->
            appendChapter(
                chapter = chapter,
                scenes = scenesByChapterId[chapter.id].orEmpty().sortedScenesForManuscript(),
                chapterPrefix = chapterPrefix,
            )
        }

        if (orphanScenes.isNotEmpty()) {
            appendChapter(
                chapterTitle = "Unassigned Scenes",
                scenes = orphanScenes,
                chapterPrefix = chapterPrefix,
            )
        }
    }.trimEnd()
}

private data class EpubChapterDocument(
    val fileName: String,
    val title: String,
    val body: String,
)

private fun epubContainerXml(): String {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()
}

private fun epubContentOpf(novel: Novel, documents: List<EpubChapterDocument>): String {
    val manifestItems = documents.joinToString("\n") { document ->
        """    <item id="${document.itemId()}" href="${document.fileName}" media-type="application/xhtml+xml"/>"""
    }
    val spineItems = documents.joinToString("\n") { document ->
        """    <itemref idref="${document.itemId()}"/>"""
    }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="book-id">urn:uuid:${novel.id.xmlEscape()}</dc:identifier>
            <dc:title>${novel.title.xmlEscape()}</dc:title>
            <dc:language>en</dc:language>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
        $manifestItems
          </manifest>
          <spine>
        $spineItems
          </spine>
        </package>
    """.trimIndent()
}

private fun epubNav(novel: Novel, documents: List<EpubChapterDocument>): String {
    val items = documents.joinToString("\n") { document ->
        """      <li><a href="${document.fileName}">${document.title.xmlEscape()}</a></li>"""
    }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en">
          <head><title>${novel.title.xmlEscape()}</title></head>
          <body>
            <nav epub:type="toc" id="toc">
              <h1>${novel.title.xmlEscape()}</h1>
              <ol>
        $items
              </ol>
            </nav>
          </body>
        </html>
    """.trimIndent()
}

private fun epubChapterXhtml(document: EpubChapterDocument): String {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" lang="en">
          <head>
            <title>${document.title.xmlEscape()}</title>
            <style>body{font-family:serif;line-height:1.55;margin:2em;} h1{font-size:1.4em;} .scene-break{text-align:center;margin:1.5em 0;}</style>
          </head>
          <body>
            <h1>${document.title.xmlEscape()}</h1>
        ${document.body}
          </body>
        </html>
    """.trimIndent()
}

private fun Chapter.toXhtmlBody(scenes: List<Scene>): String = scenesToXhtmlBody(scenes)

private fun scenesToXhtmlBody(scenes: List<Scene>): String {
    val texts = scenes.mapNotNull { scene -> scene.text.trim().takeIf { it.isNotBlank() } }
    if (texts.isEmpty()) return "    <p></p>"
    return texts.mapIndexed { index, text ->
        val paragraphs = text.split(Regex("\\n{2,}"))
            .map { paragraph -> paragraph.trim() }
            .filter { paragraph -> paragraph.isNotBlank() }
            .joinToString("\n") { paragraph -> "    <p>${paragraph.xmlEscape().replace("\n", "<br/>")}</p>" }
        if (index == 0) paragraphs else "    <div class=\"scene-break\">***</div>\n$paragraphs"
    }.joinToString("\n")
}

private fun EpubChapterDocument.itemId(): String {
    return fileName.substringBefore('.').replace(Regex("[^A-Za-z0-9_-]+"), "-")
}

private fun ZipOutputStream.putText(path: String, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    putNextEntry(ZipEntry(path))
    write(bytes)
    closeEntry()
}

private fun ZipOutputStream.putStoredText(path: String, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val crc = CRC32().apply { update(bytes) }
    val entry = ZipEntry(path).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        this.crc = crc.value
    }
    putNextEntry(entry)
    write(bytes)
    closeEntry()
}

private fun String.xmlEscape(): String {
    return replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

private fun StringBuilder.appendChapter(
    chapter: Chapter,
    scenes: List<Scene>,
    chapterPrefix: String,
) {
    appendChapter(
        chapterTitle = chapter.exportTitle(),
        scenes = scenes,
        chapterPrefix = chapterPrefix,
    )
}

private fun StringBuilder.appendChapter(
    chapterTitle: String,
    scenes: List<Scene>,
    chapterPrefix: String,
) {
    appendLine()
    appendLine(chapterPrefix + chapterTitle)

    val texts = scenes.mapNotNull { scene -> scene.text.trim().takeIf { text -> text.isNotBlank() } }
    if (texts.isEmpty()) return

    texts.forEachIndexed { index, text ->
        appendLine()
        if (index > 0) {
            appendLine("***")
            appendLine()
        }
        appendLine(text)
    }
}

private fun Chapter.exportTitle(): String {
    val fallback = "Chapter $order"
    val cleanTitle = title.trim()
    return if (cleanTitle.isBlank()) fallback else "$fallback: $cleanTitle"
}

private fun SceneStatus.isGenerated(): Boolean {
    return this == SceneStatus.GENERATED ||
        this == SceneStatus.REVIEWED ||
        this == SceneStatus.APPROVED
}

private fun List<Chapter>.sortedChaptersForManuscript(): List<Chapter> {
    return sortedWith(compareBy<Chapter> { chapter -> chapter.order }.thenBy { chapter -> chapter.title })
}

private fun List<Scene>.sortedScenesForManuscript(): List<Scene> {
    return sortedWith(compareBy<Scene> { scene -> scene.order }.thenBy { scene -> scene.id })
}

private fun countWords(text: String): Int {
    return WordRegex.findAll(text).count()
}

private val WordRegex = Regex("\\S+")
