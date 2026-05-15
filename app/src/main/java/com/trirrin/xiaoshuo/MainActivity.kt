package com.trirrin.xiaoshuo

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.trirrin.xiaoshuo.agent.BibleFilter
import com.trirrin.xiaoshuo.agent.BibleFilterContext
import com.trirrin.xiaoshuo.data.GenerationSettings
import com.trirrin.xiaoshuo.data.RevisionSnapshot
import com.trirrin.xiaoshuo.data.TokenUsageRecord
import com.trirrin.xiaoshuo.model.Chapter
import com.trirrin.xiaoshuo.model.ChapterBrief
import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.Novel
import com.trirrin.xiaoshuo.model.NovelBible
import com.trirrin.xiaoshuo.model.BibleConflict
import com.trirrin.xiaoshuo.model.ReviewReport
import com.trirrin.xiaoshuo.model.Scene
import java.io.File
import java.util.Locale

private enum class WorkspaceTab(val label: String) {
    Library("Library"),
    Outline("Outline"),
    Draft("Draft"),
    Bible("Bible"),
    History("History"),
    Settings("Settings"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as XiaoShuoApplication
            val viewModel: NovelWorkspaceViewModel = viewModel(
                factory = NovelWorkspaceViewModel.Factory(app.container),
            )
            XiaoShuoTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                XiaoShuoApp(
                    state = state,
                    onCreateNovel = viewModel::createNovel,
                    onSelectNovel = viewModel::selectNovel,
                    onSelectChapter = viewModel::selectChapter,
                    onSelectScene = viewModel::selectScene,
                    onSaveSettings = viewModel::saveSettings,
                    onGenerateOutline = viewModel::generateOutline,
                    onGenerateSynopsis = viewModel::generateSelectedSynopsis,
                    onGenerateScene = viewModel::generateSelectedScene,
                    onQueueChapterScenes = viewModel::queueSelectedChapterScenes,
                    onQueueScenesFromSelection = viewModel::queueScenesFromSelectedScene,
                    onCancelGeneration = viewModel::cancelGeneration,
                    onSaveOutline = viewModel::saveOutlineDraft,
                    onSaveSynopsis = viewModel::saveChapterSynopsisDraft,
                    onSaveSceneText = viewModel::saveSceneText,
                    onAcceptSynopsisReview = viewModel::acceptSelectedSynopsisReview,
                    onRetrySynopsis = viewModel::retrySelectedSynopsis,
                    onManualEditSynopsis = viewModel::markSelectedSynopsisForManualEdit,
                    onApproveSynopsis = viewModel::approveSelectedSynopsis,
                    onAcceptSceneReview = viewModel::acceptSelectedSceneReview,
                    onRetryScene = viewModel::retrySelectedScene,
                    onManualEditScene = viewModel::markSelectedSceneForManualEdit,
                    onApproveScene = viewModel::approveSelectedScene,
                    onSaveBibleEntry = viewModel::saveBibleEntry,
                    onDeleteBibleEntry = viewModel::deleteBibleEntry,
                    onResolveBibleConflict = viewModel::resolveBibleConflict,
                    onRestoreSnapshot = viewModel::restoreRevisionSnapshot,
                    onDeleteSnapshot = viewModel::deleteRevisionSnapshot,
                )
            }
        }
    }
}

@Composable
private fun XiaoShuoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF176B5B),
            secondary = Color(0xFF8A5A2D),
            tertiary = Color(0xFF375A7A),
            background = Color(0xFFF7F8F3),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE7EDE5),
            outline = Color(0xFFC9D2C6),
            onSurface = Color(0xFF1F241F),
        ),
        content = content,
    )
}

@Composable
private fun XiaoShuoApp(
    state: NovelWorkspaceUiState,
    onCreateNovel: (String, String, Genre, String) -> Unit,
    onSelectNovel: (String) -> Unit,
    onSelectChapter: (String) -> Unit,
    onSelectScene: (String) -> Unit,
    onSaveSettings: (GenerationSettings) -> Unit,
    onGenerateOutline: () -> Unit,
    onGenerateSynopsis: () -> Unit,
    onGenerateScene: () -> Unit,
    onQueueChapterScenes: () -> Unit,
    onQueueScenesFromSelection: () -> Unit,
    onCancelGeneration: () -> Unit,
    onSaveOutline: (OutlineDraft) -> Unit,
    onSaveSynopsis: (ChapterSynopsisDraft) -> Unit,
    onSaveSceneText: (String) -> Unit,
    onAcceptSynopsisReview: () -> Unit,
    onRetrySynopsis: () -> Unit,
    onManualEditSynopsis: () -> Unit,
    onApproveSynopsis: () -> Unit,
    onAcceptSceneReview: () -> Unit,
    onRetryScene: () -> Unit,
    onManualEditScene: () -> Unit,
    onApproveScene: () -> Unit,
    onSaveBibleEntry: (BibleSection, BibleEntryDraft) -> Unit,
    onDeleteBibleEntry: (BibleSection, String) -> Unit,
    onResolveBibleConflict: (String, Boolean) -> Unit,
    onRestoreSnapshot: (String) -> Unit,
    onDeleteSnapshot: (String) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopBar(state = state)
        },
        bottomBar = {
            RunLog(workflow = state.workflow, onCancel = onCancelGeneration)
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                WorkspaceTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.label, maxLines = 1) },
                    )
                }
            }
            when (WorkspaceTab.entries[selectedTab]) {
                WorkspaceTab.Library -> LibraryScreen(state, onCreateNovel, onSelectNovel)
                WorkspaceTab.Outline -> OutlineScreen(state, onGenerateOutline, onSaveOutline)
                WorkspaceTab.Draft -> DraftScreen(
                    state = state,
                    onSelectChapter = onSelectChapter,
                    onSelectScene = onSelectScene,
                    onGenerateSynopsis = onGenerateSynopsis,
                    onGenerateScene = onGenerateScene,
                    onQueueChapterScenes = onQueueChapterScenes,
                    onQueueScenesFromSelection = onQueueScenesFromSelection,
                    onSaveSynopsis = onSaveSynopsis,
                    onSaveSceneText = onSaveSceneText,
                    onAcceptSynopsisReview = onAcceptSynopsisReview,
                    onRetrySynopsis = onRetrySynopsis,
                    onManualEditSynopsis = onManualEditSynopsis,
                    onApproveSynopsis = onApproveSynopsis,
                    onAcceptSceneReview = onAcceptSceneReview,
                    onRetryScene = onRetryScene,
                    onManualEditScene = onManualEditScene,
                    onApproveScene = onApproveScene,
                )
                WorkspaceTab.Bible -> BibleScreen(
                    state = state,
                    onSaveEntry = onSaveBibleEntry,
                    onDeleteEntry = onDeleteBibleEntry,
                    onResolveConflict = onResolveBibleConflict,
                )
                WorkspaceTab.History -> HistoryScreen(
                    state = state,
                    onRestoreSnapshot = onRestoreSnapshot,
                    onDeleteSnapshot = onDeleteSnapshot,
                )
                WorkspaceTab.Settings -> SettingsScreen(state.settings, onSaveSettings)
            }
        }
    }
}

@Composable
private fun TopBar(state: NovelWorkspaceUiState) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Xiao Shuo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = state.selectedNovel?.title ?: "No novel selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusPill(state.selectedNovel?.status?.name ?: "EMPTY")
        }
        val novel = state.selectedNovel
        if (novel != null) {
            val stats = computeProjectStats(novel, state.chapters, state.allScenes)
            val usage = state.tokenUsage.toUsageSummary()
            MetricStrip(
                "Words" to stats.totalWords.toString(),
                "Scenes" to "${stats.generatedScenes}/${stats.sceneCount}",
                "Edited" to stats.editedScenes.toString(),
                "Approved" to stats.approvedScenes.toString(),
                "Cost" to usage.costLabel,
                "Bible" to stats.bibleEntryCount.toString(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        shareText(
                            context = context,
                            chooserTitle = "Export Markdown",
                            mimeType = "text/markdown",
                            title = "${novel.title}.md",
                            text = buildMarkdownManuscript(novel, state.chapters, state.allScenes),
                        )
                    },
                    enabled = state.allScenes.any { it.text.isNotBlank() },
                ) { Text("Share Markdown") }
                OutlinedButton(
                    onClick = {
                        shareText(
                            context = context,
                            chooserTitle = "Export TXT",
                            mimeType = "text/plain",
                            title = "${novel.title}.txt",
                            text = buildTxtManuscript(novel, state.chapters, state.allScenes),
                        )
                    },
                    enabled = state.allScenes.any { it.text.isNotBlank() },
                ) { Text("Share TXT") }
                OutlinedButton(
                    onClick = {
                        shareBytes(
                            context = context,
                            chooserTitle = "Export EPUB",
                            mimeType = "application/epub+zip",
                            fileName = "${novel.title.safeFileName()}.epub",
                            bytes = buildEpubManuscript(novel, state.chapters, state.allScenes),
                        )
                    },
                    enabled = state.allScenes.any { it.text.isNotBlank() },
                ) { Text("Share EPUB") }
            }
        }
        if (state.workflow.isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LibraryScreen(
    state: NovelWorkspaceUiState,
    onCreateNovel: (String, String, Genre, String) -> Unit,
    onSelectNovel: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SurfacePanel(modifier = Modifier.weight(0.9f).fillMaxHeight()) {
            Text("Novels", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (state.novels.isEmpty()) {
                EmptyText("No novels yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.novels, key = { it.id }) { novel ->
                        NovelRow(
                            novel = novel,
                            selected = novel.id == state.selectedNovel?.id,
                            onClick = { onSelectNovel(novel.id) },
                        )
                    }
                }
            }
        }
        SurfacePanel(modifier = Modifier.weight(1.1f).fillMaxHeight()) {
            CreateNovelForm(onCreateNovel)
        }
    }
}

@Composable
private fun NovelRow(novel: Novel, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(novel.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(novel.genre.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(novel.concept, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        MetricStrip(
            "Chapters" to (novel.outline?.chapterBriefs?.size?.toString() ?: "0"),
            "Themes" to novel.themes.size.toString(),
            "Status" to novel.status.name,
        )
    }
}

@Composable
private fun CreateNovelForm(onCreateNovel: (String, String, Genre, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var concept by remember { mutableStateOf("") }
    var themes by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf(Genre.FANTASY) }

    Text("New Novel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        GenreMenu(genre = genre, onChange = { genre = it }, modifier = Modifier.width(220.dp))
    }
    OutlinedTextField(
        value = concept,
        onValueChange = { concept = it },
        label = { Text("Concept") },
        minLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = themes,
        onValueChange = { themes = it },
        label = { Text("Themes, comma-separated") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            onCreateNovel(title, concept, genre, themes)
            title = ""
            concept = ""
            themes = ""
        },
        enabled = title.isNotBlank() && concept.isNotBlank(),
    ) {
        Text("Create")
    }
}

@Composable
private fun OutlineScreen(
    state: NovelWorkspaceUiState,
    onGenerateOutline: () -> Unit,
    onSaveOutline: (OutlineDraft) -> Unit,
) {
    val novel = state.selectedNovel
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SurfacePanel(modifier = Modifier.weight(0.9f).fillMaxHeight()) {
            Text("Project", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (novel == null) {
                EmptyText("Create or select a novel first.")
            } else {
                Text(novel.concept, style = MaterialTheme.typography.bodyMedium)
                MetricStrip(
                    "Genre" to novel.genre.label,
                    "Themes" to novel.themes.joinToString().ifBlank { "None" },
                    "Bible" to bibleCount(novel).toString(),
                )
                Button(onClick = onGenerateOutline, enabled = !state.workflow.isBusy) {
                    Text(if (novel.outline == null) "Generate Outline" else "Regenerate Outline")
                }
            }
        }
        SurfacePanel(modifier = Modifier.weight(1.4f).fillMaxHeight()) {
            Text("Outline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val outline = novel?.outline
            if (outline == null) {
                EmptyText("No outline generated.")
            } else {
                OutlineEditor(outline.toDraft(), state.workflow.isBusy, onSaveOutline)
            }
        }
    }
}

@Composable
private fun DraftScreen(
    state: NovelWorkspaceUiState,
    onSelectChapter: (String) -> Unit,
    onSelectScene: (String) -> Unit,
    onGenerateSynopsis: () -> Unit,
    onGenerateScene: () -> Unit,
    onQueueChapterScenes: () -> Unit,
    onQueueScenesFromSelection: () -> Unit,
    onSaveSynopsis: (ChapterSynopsisDraft) -> Unit,
    onSaveSceneText: (String) -> Unit,
    onAcceptSynopsisReview: () -> Unit,
    onRetrySynopsis: () -> Unit,
    onManualEditSynopsis: () -> Unit,
    onApproveSynopsis: () -> Unit,
    onAcceptSceneReview: () -> Unit,
    onRetryScene: () -> Unit,
    onManualEditScene: () -> Unit,
    onApproveScene: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SurfacePanel(modifier = Modifier.width(300.dp).fillMaxHeight()) {
            Text("Chapters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (state.chapters.isEmpty()) {
                EmptyText("Generate an outline first.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.chapters, key = { it.id }) { chapter ->
                        SelectableRow(
                            title = "${chapter.order}. ${chapter.title}",
                            subtitle = chapterListSubtitle(chapter),
                            selected = chapter.id == state.selectedChapter?.id,
                            onClick = { onSelectChapter(chapter.id) },
                        )
                    }
                }
            }
        }
        SurfacePanel(modifier = Modifier.width(340.dp).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scenes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Button(
                    onClick = onGenerateSynopsis,
                    enabled = !state.workflow.isBusy && state.selectedNovel?.outline != null && state.selectedChapter != null,
                ) {
                    Text("Synopsis")
                }
            }
            ChapterSynopsisEditor(
                chapter = state.selectedChapter,
                isBusy = state.workflow.isBusy,
                onSave = onSaveSynopsis,
                onAcceptReview = onAcceptSynopsisReview,
                onRetry = onRetrySynopsis,
                onManualEdit = onManualEditSynopsis,
                onApprove = onApproveSynopsis,
            )
            if (state.scenes.isEmpty()) {
                EmptyText("No scene breakdowns yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.scenes, key = { it.id }) { scene ->
                        SelectableRow(
                            title = "Scene ${scene.order}",
                            subtitle = sceneListSubtitle(scene),
                            selected = scene.id == state.selectedScene?.id,
                            onClick = { onSelectScene(scene.id) },
                        )
                    }
                }
            }
        }
        SurfacePanel(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Draft", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Button(
                    onClick = onGenerateScene,
                    enabled = !state.workflow.isBusy && state.selectedScene != null,
                ) {
                    Text("Generate")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onQueueChapterScenes,
                    enabled = !state.workflow.isBusy && state.selectedChapter != null && state.scenes.any { it.text.isBlank() },
                ) {
                    Text("Queue Chapter")
                }
                OutlinedButton(
                    onClick = onQueueScenesFromSelection,
                    enabled = !state.workflow.isBusy && state.selectedScene != null && state.scenes.any { it.order >= (state.selectedScene?.order ?: Int.MAX_VALUE) && it.text.isBlank() },
                ) {
                    Text("Queue From Scene")
                }
            }
            SceneEditor(
                scene = state.selectedScene,
                isBusy = state.workflow.isBusy,
                streamingText = state.workflow.streamingSceneText.takeIf { state.workflow.streamingSceneId == state.selectedScene?.id },
                onSaveText = onSaveSceneText,
                onAcceptReview = onAcceptSceneReview,
                onRetry = onRetryScene,
                onManualEdit = onManualEditScene,
                onApprove = onApproveScene,
            )
        }
    }
}

@Composable
private fun OutlineEditor(initialDraft: OutlineDraft, isBusy: Boolean, onSave: (OutlineDraft) -> Unit) {
    var premise by remember(initialDraft) { mutableStateOf(initialDraft.premise) }
    var plotPoints by remember(initialDraft) { mutableStateOf(initialDraft.majorPlotPoints) }
    var characterArcs by remember(initialDraft) { mutableStateOf(initialDraft.characterArcs) }
    var thematicStructure by remember(initialDraft) { mutableStateOf(initialDraft.thematicStructure) }
    var chapters by remember(initialDraft) { mutableStateOf(initialDraft.chapters) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = premise,
            onValueChange = { premise = it },
            label = { Text("Premise") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = plotPoints,
            onValueChange = { plotPoints = it },
            label = { Text("Plot points: name | position | description") },
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = characterArcs,
            onValueChange = { characterArcs = it },
            label = { Text("Character arcs, one per line") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = thematicStructure,
            onValueChange = { thematicStructure = it },
            label = { Text("Thematic structure") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = chapters,
            onValueChange = { chapters = it },
            label = { Text("Chapters: index | title | plot beats | purpose") },
            minLines = 10,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                onSave(
                    OutlineDraft(
                        premise = premise,
                        majorPlotPoints = plotPoints,
                        characterArcs = characterArcs,
                        thematicStructure = thematicStructure,
                        chapters = chapters,
                    ),
                )
            },
            enabled = !isBusy && premise.isNotBlank() && chapters.isNotBlank(),
        ) {
            Text("Save Outline")
        }
    }
}

@Composable
private fun ChapterSynopsisEditor(
    chapter: Chapter?,
    isBusy: Boolean,
    onSave: (ChapterSynopsisDraft) -> Unit,
    onAcceptReview: () -> Unit,
    onRetry: () -> Unit,
    onManualEdit: () -> Unit,
    onApprove: () -> Unit,
) {
    val synopsis = chapter?.synopsis
    if (synopsis == null) {
        EmptyText("No chapter synopsis yet.")
        return
    }

    val initialDraft = synopsis.toDraft()
    var goal by remember(chapter.id, initialDraft) { mutableStateOf(initialDraft.chapterGoal) }
    var scenes by remember(chapter.id, initialDraft) { mutableStateOf(initialDraft.sceneBreakdowns) }
    var ending by remember(chapter.id, initialDraft) { mutableStateOf(initialDraft.chapterEnding) }
    var transition by remember(chapter.id, initialDraft) { mutableStateOf(initialDraft.transitionNotes) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ReviewPanel(
            report = chapter.reviewReport,
            isBusy = isBusy,
            onAccept = onAcceptReview,
            onRetry = onRetry,
            onManualEdit = onManualEdit,
            onApprove = onApprove,
        )
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            label = { Text("Chapter goal") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = scenes,
            onValueChange = { scenes = it },
            label = { Text("Scenes: index | target words | synopsis") },
            minLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ending,
            onValueChange = { ending = it },
            label = { Text("Chapter ending") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = transition,
            onValueChange = { transition = it },
            label = { Text("Transition notes") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                onSave(
                    ChapterSynopsisDraft(
                        chapterGoal = goal,
                        sceneBreakdowns = scenes,
                        chapterEnding = ending,
                        transitionNotes = transition,
                    ),
                )
            },
            enabled = !isBusy && goal.isNotBlank() && scenes.isNotBlank(),
        ) {
            Text("Save Synopsis")
        }
    }
}

@Composable
private fun SceneEditor(
    scene: Scene?,
    isBusy: Boolean,
    streamingText: String?,
    onSaveText: (String) -> Unit,
    onAcceptReview: () -> Unit,
    onRetry: () -> Unit,
    onManualEdit: () -> Unit,
    onApprove: () -> Unit,
) {
    if (scene == null) {
        EmptyText("Select a scene.")
        return
    }
    var text by remember(scene.id, scene.text, streamingText) { mutableStateOf(streamingText ?: scene.text) }
    val shownText = streamingText ?: text
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompactBlock("Synopsis", scene.synopsis)
        ReviewPanel(
            report = scene.reviewReport,
            isBusy = isBusy,
            onAccept = onAcceptReview,
            onRetry = onRetry,
            onManualEdit = onManualEdit,
            onApprove = onApprove,
        )
        OutlinedTextField(
            value = shownText,
            onValueChange = { if (streamingText == null) text = it },
            label = { Text("Scene prose") },
            minLines = 18,
            readOnly = streamingText != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onSaveText(shownText) }, enabled = !isBusy && streamingText == null) {
                Text("Save Prose")
            }
            Text("${countDraftWords(shownText)} words", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun BibleScreen(
    state: NovelWorkspaceUiState,
    onSaveEntry: (BibleSection, BibleEntryDraft) -> Unit,
    onDeleteEntry: (BibleSection, String) -> Unit,
    onResolveConflict: (String, Boolean) -> Unit,
) {
    SurfacePanel(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        val novel = state.selectedNovel
        Text("Novel Bible", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (novel == null) {
            EmptyText("Create or select a novel first.")
            return@SurfacePanel
        }

        var section by remember(novel.id) { mutableStateOf(BibleSection.CHARACTERS) }
        var draft by remember(novel.id, section) { mutableStateOf(BibleEntryDraft()) }
        val entries = novel.bible.entrySummaries(section)

        TabRow(selectedTabIndex = section.ordinal) {
            BibleSection.entries.forEach { item ->
                Tab(
                    selected = section == item,
                    onClick = {
                        section = item
                        draft = BibleEntryDraft()
                    },
                    text = { Text(item.label, maxLines = 1) },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BibleConflictPanel(novel.bible.conflicts, state.workflow.isBusy, onResolveConflict)
            BibleWarnings(state.bibleWarnings.filter { it.conflictId == null })
            BibleContextPreview(
                bible = novel.bible,
                chapter = state.selectedChapter,
                scene = state.selectedScene,
            )
            BibleEntryEditor(
                section = section,
                draft = draft,
                isBusy = state.workflow.isBusy,
                onDraftChange = { draft = it },
                onSave = { onSaveEntry(section, draft) },
                onNew = { draft = BibleEntryDraft() },
                onDelete = {
                    onDeleteEntry(section, draft.id)
                    draft = BibleEntryDraft()
                },
            )
            Text(section.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (entries.isEmpty()) {
                EmptyText("None")
            } else {
                entries.forEach { entry ->
                    BibleEntryRow(
                        entry = entry,
                        selected = draft.id.isNotBlank() && draft.id == entry.id,
                        onEdit = { draft = entry.draft },
                        onDelete = {
                            onDeleteEntry(section, entry.id)
                            if (draft.id == entry.id) draft = BibleEntryDraft()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BibleEntryEditor(
    section: BibleSection,
    draft: BibleEntryDraft,
    isBusy: Boolean,
    onDraftChange: (BibleEntryDraft) -> Unit,
    onSave: () -> Unit,
    onNew: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Editor", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = draft.primary,
            onValueChange = { onDraftChange(draft.copy(primary = it)) },
            label = { Text(section.primaryLabel()) },
            singleLine = section != BibleSection.TIMELINE,
            minLines = if (section == BibleSection.TIMELINE) 2 else 1,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.secondary,
            onValueChange = { onDraftChange(draft.copy(secondary = it)) },
            label = { Text(section.secondaryLabel()) },
            minLines = if (section == BibleSection.WORLD_RULES) 2 else 1,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.tertiary,
            onValueChange = { onDraftChange(draft.copy(tertiary = it)) },
            label = { Text(section.tertiaryLabel()) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        if (section == BibleSection.CHARACTERS || section == BibleSection.TIMELINE) {
            OutlinedTextField(
                value = draft.quaternary,
                onValueChange = { onDraftChange(draft.copy(quaternary = it)) },
                label = { Text(section.quaternaryLabel()) },
                minLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = !isBusy && draft.primary.isNotBlank()) { Text("Save Entry") }
            OutlinedButton(onClick = onNew, enabled = !isBusy) { Text("New") }
            OutlinedButton(onClick = onDelete, enabled = !isBusy && draft.id.isNotBlank()) { Text("Delete") }
        }
    }
}

@Composable
private fun BibleEntryRow(entry: BibleEntrySummary, selected: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(entry.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
        Text(entry.detail.ifBlank { "None" }, style = MaterialTheme.typography.bodySmall)
        if (entry.extra.isNotBlank()) {
            Text(entry.extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun BibleConflictPanel(
    conflicts: List<BibleConflict>,
    isBusy: Boolean,
    onResolveConflict: (String, Boolean) -> Unit,
) {
    if (conflicts.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF1D6), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFC98212), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Canon Conflicts", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        conflicts.forEach { conflict ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFE2B35B), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${conflict.section.name}: ${conflict.title} / ${conflict.field}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                CompactBlock("Canon", conflict.existingValue)
                CompactBlock("Incoming", conflict.incomingValue)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onResolveConflict(conflict.id, false) }, enabled = !isBusy) { Text("Keep Canon") }
                    Button(onClick = { onResolveConflict(conflict.id, true) }, enabled = !isBusy) { Text("Use Incoming") }
                }
            }
        }
    }
}

@Composable
private fun BibleWarnings(warnings: List<BibleConflictWarning>) {
    if (warnings.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF1D6), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFC98212), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Duplicate Warnings", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        warnings.forEach { warning ->
            Text("${warning.section.label}: ${warning.title} - ${warning.detail}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BibleContextPreview(bible: NovelBible, chapter: Chapter?, scene: Scene?) {
    val preview = bible.relevancePreview(chapter, scene)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Context Preview", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (preview.isEmpty()) {
            EmptyText("No matching Bible entries for the selected chapter or scene.")
        } else {
            preview.forEach { entry -> CompactBlock(entry.title, entry.detail) }
        }
    }
}

@Composable
private fun HistoryScreen(
    state: NovelWorkspaceUiState,
    onRestoreSnapshot: (String) -> Unit,
    onDeleteSnapshot: (String) -> Unit,
) {
    SurfacePanel(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        val usage = state.tokenUsage.toUsageSummary()
        Text("History And Metrics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        MetricStrip(
            "Snapshots" to state.revisionSnapshots.size.toString(),
            "Input" to usage.inputTokens.toString(),
            "Output" to usage.outputTokens.toString(),
            "Total" to usage.totalTokens.toString(),
            "Cost" to usage.costLabel,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Revision Snapshots", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (state.revisionSnapshots.isEmpty()) {
                EmptyText("No snapshots yet.")
            } else {
                state.revisionSnapshots.forEach { snapshot ->
                    RevisionSnapshotRow(
                        snapshot = snapshot,
                        isBusy = state.workflow.isBusy,
                        onRestore = { onRestoreSnapshot(snapshot.id) },
                        onDelete = { onDeleteSnapshot(snapshot.id) },
                    )
                }
            }

            Text("Token Usage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (state.tokenUsage.isEmpty()) {
                EmptyText("No recorded token usage yet.")
            } else {
                state.tokenUsage.take(60).forEach { record -> UsageRecordRow(record) }
            }
        }
    }
}

@Composable
private fun RevisionSnapshotRow(
    snapshot: RevisionSnapshot,
    isBusy: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(snapshot.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("${snapshot.targetType} / ${snapshot.createdAt}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f))
            }
            TextButton(onClick = onRestore, enabled = !isBusy) { Text("Restore") }
            TextButton(onClick = onDelete, enabled = !isBusy) { Text("Delete") }
        }
    }
}

@Composable
private fun UsageRecordRow(record: TokenUsageRecord) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(record.agentName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("${record.provider} / ${record.model}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f))
            }
            Text(record.costLabel(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        MetricStrip(
            "Input" to record.inputTokens.toString(),
            "Output" to record.outputTokens.toString(),
            "Total" to (record.inputTokens + record.outputTokens).toString(),
        )
    }
}

@Composable
private fun SettingsScreen(settings: GenerationSettings, onSave: (GenerationSettings) -> Unit) {
    SurfacePanel(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        SettingsForm(settings, onSave)
    }
}

@Composable
private fun SettingsForm(settings: GenerationSettings, onSave: (GenerationSettings) -> Unit) {
    var provider by remember(settings) { mutableStateOf(settings.provider) }
    var apiKey by remember(settings) { mutableStateOf(settings.apiKey) }
    var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl) }
    var outlineModel by remember(settings) { mutableStateOf(settings.outlineModel) }
    var synopsisModel by remember(settings) { mutableStateOf(settings.synopsisModel) }
    var textModel by remember(settings) { mutableStateOf(settings.textModel) }
    var reviewModel by remember(settings) { mutableStateOf(settings.reviewModel) }
    var continuityModel by remember(settings) { mutableStateOf(settings.continuityModel) }

    Text("Provider Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderMenu(provider, onChange = { provider = it }, modifier = Modifier.width(220.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = if (apiKey.isBlank()) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.width(320.dp),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.width(320.dp),
        )
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModelField("Outline model", outlineModel, { outlineModel = it })
        ModelField("Synopsis model", synopsisModel, { synopsisModel = it })
        ModelField("Text model", textModel, { textModel = it })
        ModelField("Review model", reviewModel, { reviewModel = it })
        ModelField("Continuity model", continuityModel, { continuityModel = it })
    }
    Button(
        onClick = {
            onSave(
                GenerationSettings(
                    provider = provider,
                    apiKey = apiKey.trim(),
                    baseUrl = baseUrl.trim(),
                    outlineModel = outlineModel.trim(),
                    synopsisModel = synopsisModel.trim(),
                    textModel = textModel.trim(),
                    reviewModel = reviewModel.trim(),
                    continuityModel = continuityModel.trim(),
                ),
            )
        },
        enabled = provider.isNotBlank(),
    ) {
        Text("Save Settings")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderMenu(provider: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = provider,
            onValueChange = {},
            readOnly = true,
            label = { Text("Provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("ANTHROPIC", "OPENAI").forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = {
                        onChange(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreMenu(genre: Genre, onChange: (Genre) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = genre.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Genre") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Genre.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        onChange(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.width(260.dp),
    )
}

@Composable
private fun SurfacePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun SelectableRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
    }
}

@Composable
private fun ReviewPanel(
    report: ReviewReport?,
    isBusy: Boolean,
    onAccept: () -> Unit,
    onRetry: () -> Unit,
    onManualEdit: () -> Unit,
    onApprove: () -> Unit,
) {
    if (report == null) return
    val accent = if (report.passed) MaterialTheme.colorScheme.primary else Color(0xFF9A2E2E)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .border(1.dp, accent.copy(alpha = 0.42f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Review", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${report.score}/10", style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.SemiBold)
        }
        MetricStrip(
            "Result" to if (report.passed) "Passed" else "Failed",
            "Status" to report.status.name,
            "Retries" to report.retryCount.toString(),
        )
        ReviewList("Issues", report.issues)
        ReviewList("Suggested fixes", report.suggestedFixes)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAccept, enabled = !isBusy) { Text("Accept") }
            OutlinedButton(onClick = onRetry, enabled = !isBusy) { Text("Retry") }
            OutlinedButton(onClick = onManualEdit, enabled = !isBusy) { Text("Edit Manually") }
            Button(onClick = onApprove, enabled = !isBusy) { Text("Mark Approved") }
        }
    }
}

@Composable
private fun ReviewList(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        items.forEach { item ->
            Text("- $item", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CompactBlock(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(body.ifBlank { "None" }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChapterBriefBlock(brief: ChapterBrief) {
    CompactBlock(
        title = "${brief.chapterIndex}. ${brief.title}",
        body = "${brief.plotBeats}\n\n${brief.purposeInStory}",
    )
}

private fun chapterListSubtitle(chapter: Chapter): String {
    return listOfNotNull(
        chapter.status.name,
        chapter.reviewReport?.let { reviewBadge(it) },
    ).joinToString(" - ")
}

private fun sceneListSubtitle(scene: Scene): String {
    return listOfNotNull(
        scene.status.name,
        "${scene.wordCount} words",
        scene.reviewReport?.let { reviewBadge(it) },
    ).joinToString(" - ")
}

private fun reviewBadge(report: ReviewReport): String {
    val result = if (report.passed) "review passed" else "review failed"
    return "$result ${report.score}/10 ${report.status.name}"
}

@Composable
private fun MetricStrip(vararg metrics: Pair<String, String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        metrics.forEach { (label, value) ->
            Column(
                modifier = Modifier
                    .background(Color(0xFFEAF0E7), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f))
            }
        }
    }
}

@Composable
private fun RunLog(workflow: WorkflowState, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val queue = if (workflow.queueTotal > 0) " (${workflow.queuePosition}/${workflow.queueTotal})" else ""
            val status = workflow.error ?: workflow.events.lastOrNull()?.plus(queue) ?: "Idle"
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (workflow.error == null) MaterialTheme.colorScheme.primary else Color(0xFF9A2E2E), RoundedCornerShape(8.dp)),
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (workflow.error == null) MaterialTheme.colorScheme.onSurface else Color(0xFF9A2E2E),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel, enabled = workflow.isBusy) {
                Text(if (workflow.isBusy) "Cancel" else "Ready")
            }
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
    )
}

private fun shareText(
    context: Context,
    chooserTitle: String,
    mimeType: String,
    title: String,
    text: String,
) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_TITLE, title)
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
}

private fun shareBytes(
    context: Context,
    chooserTitle: String,
    mimeType: String,
    fileName: String,
    bytes: ByteArray,
) {
    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val exportFile = File(exportDir, fileName).apply { writeBytes(bytes) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_TITLE, fileName)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
}

private data class UsageSummary(
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedCostUsd: Double,
) {
    val totalTokens: Int = inputTokens + outputTokens
    val costLabel: String = estimatedCostUsd.costLabel()
}

private fun List<TokenUsageRecord>.toUsageSummary(): UsageSummary {
    return UsageSummary(
        inputTokens = sumOf { it.inputTokens },
        outputTokens = sumOf { it.outputTokens },
        estimatedCostUsd = sumOf { it.estimatedCostUsd },
    )
}

private fun TokenUsageRecord.costLabel(): String = estimatedCostUsd.costLabel()

private fun Double.costLabel(): String {
    return if (this <= 0.0) "$0.0000" else "$" + String.format(Locale.US, "%.4f", this)
}

private fun String.safeFileName(): String {
    return trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "manuscript" }
}

private data class BibleEntrySummary(
    val id: String,
    val title: String,
    val detail: String,
    val extra: String,
    val source: String,
    val draft: BibleEntryDraft,
)

private fun NovelBible.entrySummaries(section: BibleSection): List<BibleEntrySummary> {
    return when (section) {
        BibleSection.CHARACTERS -> characters.map {
            BibleEntrySummary(
                id = it.id,
                title = it.name,
                detail = it.description,
                extra = listOf(it.personality, it.currentState).filter { value -> value.isNotBlank() }.joinToString("\n"),
                source = it.source.name,
                draft = BibleEntryDraft(it.id, it.name, it.description, it.personality, it.currentState),
            )
        }
        BibleSection.LOCATIONS -> locations.map {
            BibleEntrySummary(
                id = it.id,
                title = it.name,
                detail = it.description,
                extra = it.significance,
                source = it.source.name,
                draft = BibleEntryDraft(it.id, it.name, it.description, it.significance),
            )
        }
        BibleSection.TIMELINE -> timelineEvents.sortedBy { it.chronologicalOrder }.map {
            BibleEntrySummary(
                id = it.id,
                title = it.description,
                detail = listOfNotNull(it.chapterId?.let { chapter -> "Chapter $chapter" }, it.sceneId?.let { scene -> "Scene $scene" }).joinToString(" - "),
                extra = "Order ${it.chronologicalOrder}",
                source = it.source.name,
                draft = BibleEntryDraft(it.id, it.description, it.chapterId.orEmpty(), it.sceneId.orEmpty(), it.chronologicalOrder.toString()),
            )
        }
        BibleSection.WORLD_RULES -> worldRules.map {
            BibleEntrySummary(
                id = it.id,
                title = it.category,
                detail = it.rule,
                extra = it.details,
                source = it.source.name,
                draft = BibleEntryDraft(it.id, it.category, it.rule, it.details),
            )
        }
        BibleSection.THEMES -> themes.map {
            BibleEntrySummary(
                id = it.id,
                title = it.name,
                detail = it.description,
                extra = it.motifSymbols.joinToString(", "),
                source = it.source.name,
                draft = BibleEntryDraft(it.id, it.name, it.description, it.motifSymbols.joinToString(", ")),
            )
        }
    }
}

private fun NovelBible.relevancePreview(chapter: Chapter?, scene: Scene?): List<BibleEntrySummary> {
    val filtered = BibleFilter(tokenBudget = 900).filterRelevant(
        bible = this,
        context = BibleFilterContext(
            chapterSynopsis = chapter?.synopsis?.let { synopsis ->
                listOf(
                    chapter.title,
                    synopsis.chapterGoal,
                    synopsis.sceneBreakdowns.joinToString(" ") { it.synopsis },
                ).joinToString(" ")
            },
            sceneSynopsis = scene?.synopsis,
        ),
    )
    return BibleSection.entries.flatMap { filtered.entrySummaries(it) }.take(8)
}

private fun BibleSection.primaryLabel(): String {
    return when (this) {
        BibleSection.CHARACTERS -> "Name"
        BibleSection.LOCATIONS -> "Name"
        BibleSection.TIMELINE -> "Event description"
        BibleSection.WORLD_RULES -> "Category"
        BibleSection.THEMES -> "Name"
    }
}

private fun BibleSection.secondaryLabel(): String {
    return when (this) {
        BibleSection.CHARACTERS -> "Description"
        BibleSection.LOCATIONS -> "Description"
        BibleSection.TIMELINE -> "Chapter ID"
        BibleSection.WORLD_RULES -> "Rule"
        BibleSection.THEMES -> "Description"
    }
}

private fun BibleSection.tertiaryLabel(): String {
    return when (this) {
        BibleSection.CHARACTERS -> "Personality"
        BibleSection.LOCATIONS -> "Significance"
        BibleSection.TIMELINE -> "Scene ID"
        BibleSection.WORLD_RULES -> "Details"
        BibleSection.THEMES -> "Motif symbols, comma-separated"
    }
}

private fun BibleSection.quaternaryLabel(): String {
    return when (this) {
        BibleSection.CHARACTERS -> "Current state"
        BibleSection.TIMELINE -> "Chronological order"
        else -> "Extra"
    }
}

private fun countDraftWords(text: String): Int {
    return text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
}

private fun bibleCount(novel: Novel): Int {
    return novel.bible.characters.size + novel.bible.locations.size + novel.bible.timelineEvents.size + novel.bible.worldRules.size + novel.bible.themes.size
}
