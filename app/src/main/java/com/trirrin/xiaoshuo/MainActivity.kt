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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.res.stringResource
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

private enum class WorkspaceTab(val labelRes: Int) {
    Library(R.string.tab_library),
    Outline(R.string.tab_outline),
    Draft(R.string.tab_draft),
    Bible(R.string.tab_bible),
    History(R.string.tab_history),
    Settings(R.string.tab_settings),
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
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 12.dp,
            ) {
                WorkspaceTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(stringResource(tab.labelRes), maxLines = 1) },
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
    val novel = state.selectedNovel
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f).widthIn(min = 180.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = novel?.title ?: stringResource(R.string.no_novel_selected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusPill(novel?.status?.name ?: stringResource(R.string.status_empty))
        }
        if (novel != null) {
            val stats = computeProjectStats(novel, state.chapters, state.allScenes)
            val usage = state.tokenUsage.toUsageSummary()
            MetricStrip(
                stringResource(R.string.metric_words) to stats.totalWords.toString(),
                stringResource(R.string.metric_scenes) to "${stats.generatedScenes}/${stats.sceneCount}",
                stringResource(R.string.metric_edited) to stats.editedScenes.toString(),
                stringResource(R.string.metric_approved) to stats.approvedScenes.toString(),
                stringResource(R.string.metric_cost) to usage.costLabel,
                stringResource(R.string.metric_bible) to stats.bibleEntryCount.toString(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val hasDraftText = state.allScenes.any { it.text.isNotBlank() }
                OutlinedButton(
                    onClick = {
                        shareText(
                            context = context,
                            chooserTitle = context.getString(R.string.export_markdown),
                            mimeType = "text/markdown",
                            title = "${novel.title}.md",
                            text = buildMarkdownManuscript(novel, state.chapters, state.allScenes),
                        )
                    },
                    enabled = hasDraftText,
                ) { Text(stringResource(R.string.share_markdown)) }
                OutlinedButton(
                    onClick = {
                        shareText(
                            context = context,
                            chooserTitle = context.getString(R.string.export_txt),
                            mimeType = "text/plain",
                            title = "${novel.title}.txt",
                            text = buildTxtManuscript(novel, state.chapters, state.allScenes),
                        )
                    },
                    enabled = hasDraftText,
                ) { Text(stringResource(R.string.share_txt)) }
                OutlinedButton(
                    onClick = {
                        shareBytes(
                            context = context,
                            chooserTitle = context.getString(R.string.export_epub),
                            mimeType = "application/epub+zip",
                            fileName = "${novel.title.safeFileName()}.epub",
                            bytes = buildEpubManuscript(novel, state.chapters, state.allScenes),
                        )
                    },
                    enabled = hasDraftText,
                ) { Text(stringResource(R.string.share_epub)) }
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
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        val compact = maxWidth < 760.dp
        val novelList: @Composable (Modifier) -> Unit = { modifier ->
            SurfacePanel(modifier = modifier) {
                Text(stringResource(R.string.section_novels), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (state.novels.isEmpty()) {
                    EmptyText(stringResource(R.string.empty_no_novels))
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
        }
        val newNovelForm: @Composable (Modifier) -> Unit = { modifier ->
            SurfacePanel(modifier = modifier) {
                CreateNovelForm(onCreateNovel)
            }
        }

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                novelList(Modifier.weight(1f).fillMaxWidth())
                newNovelForm(Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                novelList(Modifier.weight(0.9f).fillMaxHeight())
                newNovelForm(Modifier.weight(1.1f).fillMaxHeight())
            }
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
            Text(stringResource(novel.genre.labelRes()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(novel.concept, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        MetricStrip(
            stringResource(R.string.metric_chapters) to (novel.outline?.chapterBriefs?.size?.toString() ?: "0"),
            stringResource(R.string.metric_themes) to novel.themes.size.toString(),
            stringResource(R.string.metric_status) to novel.status.name,
        )
    }
}

@Composable
private fun CreateNovelForm(onCreateNovel: (String, String, Genre, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var concept by remember { mutableStateOf("") }
    var themes by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf(Genre.FANTASY) }

    Text(stringResource(R.string.section_new_novel), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.field_title)) },
            singleLine = true,
            modifier = Modifier.weight(1f).widthIn(min = 220.dp),
        )
        GenreMenu(genre = genre, onChange = { genre = it }, modifier = Modifier.weight(1f).widthIn(min = 180.dp))
    }
    OutlinedTextField(
        value = concept,
        onValueChange = { concept = it },
        label = { Text(stringResource(R.string.field_concept)) },
        minLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = themes,
        onValueChange = { themes = it },
        label = { Text(stringResource(R.string.field_themes_csv)) },
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
        Text(stringResource(R.string.action_create))
    }
}

@Composable
private fun OutlineScreen(
    state: NovelWorkspaceUiState,
    onGenerateOutline: () -> Unit,
    onSaveOutline: (OutlineDraft) -> Unit,
) {
    val novel = state.selectedNovel
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        val compact = maxWidth < 840.dp
        val projectPanel: @Composable (Modifier) -> Unit = { modifier ->
            SurfacePanel(modifier = modifier) {
                Text(stringResource(R.string.section_project), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (novel == null) {
                    EmptyText(stringResource(R.string.empty_select_novel_first))
                } else {
                    Text(novel.concept, style = MaterialTheme.typography.bodyMedium)
                    MetricStrip(
                        stringResource(R.string.field_genre) to stringResource(novel.genre.labelRes()),
                        stringResource(R.string.metric_themes) to novel.themes.joinToString().ifBlank { stringResource(R.string.none) },
                        stringResource(R.string.metric_bible) to bibleCount(novel).toString(),
                    )
                    Button(onClick = onGenerateOutline, enabled = !state.workflow.isBusy) {
                        Text(stringResource(if (novel.outline == null) R.string.action_generate_outline else R.string.action_regenerate_outline))
                    }
                }
            }
        }
        val outlinePanel: @Composable (Modifier) -> Unit = { modifier ->
            SurfacePanel(modifier = modifier) {
                Text(stringResource(R.string.section_outline), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val outline = novel?.outline
                if (outline == null) {
                    EmptyText(stringResource(R.string.empty_no_outline))
                } else {
                    OutlineEditor(outline.toDraft(), state.workflow.isBusy, onSaveOutline)
                }
            }
        }

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                projectPanel(Modifier.fillMaxWidth().heightIn(min = 160.dp))
                outlinePanel(Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                projectPanel(Modifier.weight(0.9f).fillMaxHeight())
                outlinePanel(Modifier.weight(1.4f).fillMaxHeight())
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
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        val compact = maxWidth < 980.dp
        val chapterPanel: @Composable (Modifier) -> Unit = { modifier ->
            SurfacePanel(modifier = modifier) {
                Text(stringResource(R.string.section_chapters), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (state.chapters.isEmpty()) {
                    EmptyText(stringResource(R.string.empty_generate_outline_first))
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
        }
        val scenePanel: @Composable (Modifier) -> Unit = { modifier ->
            SurfacePanel(modifier = modifier) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.section_scenes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Button(
                        onClick = onGenerateSynopsis,
                        enabled = !state.workflow.isBusy && state.selectedNovel?.outline != null && state.selectedChapter != null,
                    ) {
                        Text(stringResource(R.string.action_synopsis))
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
                    EmptyText(stringResource(R.string.empty_no_scene_breakdowns))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.scenes, key = { it.id }) { scene ->
                            SelectableRow(
                                title = stringResource(R.string.scene_number, scene.order),
                                subtitle = sceneListSubtitle(scene),
                                selected = scene.id == state.selectedScene?.id,
                                onClick = { onSelectScene(scene.id) },
                            )
                        }
                    }
                }
            }
        }
        val draftPanel: @Composable (Modifier) -> Unit = { modifier ->
            SurfacePanel(modifier = modifier) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.section_draft), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Button(
                        onClick = onGenerateScene,
                        enabled = !state.workflow.isBusy && state.selectedScene != null,
                    ) {
                        Text(stringResource(R.string.action_generate))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onQueueChapterScenes,
                        enabled = !state.workflow.isBusy && state.selectedChapter != null && state.scenes.any { it.text.isBlank() },
                    ) {
                        Text(stringResource(R.string.action_queue_chapter))
                    }
                    OutlinedButton(
                        onClick = onQueueScenesFromSelection,
                        enabled = !state.workflow.isBusy && state.selectedScene != null && state.scenes.any { it.order >= (state.selectedScene?.order ?: Int.MAX_VALUE) && it.text.isBlank() },
                    ) {
                        Text(stringResource(R.string.action_queue_from_scene))
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

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                chapterPanel(Modifier.weight(0.8f).fillMaxWidth())
                scenePanel(Modifier.weight(1f).fillMaxWidth())
                draftPanel(Modifier.weight(1.4f).fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                chapterPanel(Modifier.weight(0.75f).fillMaxHeight())
                scenePanel(Modifier.weight(0.95f).fillMaxHeight())
                draftPanel(Modifier.weight(1.6f).fillMaxHeight())
            }
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
            label = { Text(stringResource(R.string.field_premise)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = plotPoints,
            onValueChange = { plotPoints = it },
            label = { Text(stringResource(R.string.field_plot_points)) },
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = characterArcs,
            onValueChange = { characterArcs = it },
            label = { Text(stringResource(R.string.field_character_arcs)) },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = thematicStructure,
            onValueChange = { thematicStructure = it },
            label = { Text(stringResource(R.string.field_thematic_structure)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = chapters,
            onValueChange = { chapters = it },
            label = { Text(stringResource(R.string.field_chapters_outline)) },
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
            Text(stringResource(R.string.action_save_outline))
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
        EmptyText(stringResource(R.string.empty_no_chapter_synopsis))
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
            label = { Text(stringResource(R.string.field_chapter_goal)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = scenes,
            onValueChange = { scenes = it },
            label = { Text(stringResource(R.string.field_scene_breakdowns)) },
            minLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ending,
            onValueChange = { ending = it },
            label = { Text(stringResource(R.string.field_chapter_ending)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = transition,
            onValueChange = { transition = it },
            label = { Text(stringResource(R.string.field_transition_notes)) },
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
            Text(stringResource(R.string.action_save_synopsis))
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
        EmptyText(stringResource(R.string.empty_select_scene))
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
        CompactBlock(stringResource(R.string.section_synopsis), scene.synopsis)
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
            label = { Text(stringResource(R.string.field_scene_prose)) },
            minLines = 18,
            readOnly = streamingText != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onSaveText(shownText) }, enabled = !isBusy && streamingText == null) {
                Text(stringResource(R.string.action_save_prose))
            }
            Text(stringResource(R.string.word_count, countDraftWords(shownText)), style = MaterialTheme.typography.labelMedium)
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
        Text(stringResource(R.string.section_novel_bible), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (novel == null) {
            EmptyText(stringResource(R.string.empty_select_novel_first))
            return@SurfacePanel
        }

        var section by remember(novel.id) { mutableStateOf(BibleSection.CHARACTERS) }
        var draft by remember(novel.id, section) { mutableStateOf(BibleEntryDraft()) }
        val entries = novel.bible.entrySummaries(section)

        ScrollableTabRow(selectedTabIndex = section.ordinal, edgePadding = 0.dp) {
            BibleSection.entries.forEach { item ->
                Tab(
                    selected = section == item,
                    onClick = {
                        section = item
                        draft = BibleEntryDraft()
                    },
                    text = { Text(stringResource(item.labelRes()), maxLines = 1) },
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
            Text(stringResource(section.labelRes()), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (entries.isEmpty()) {
                EmptyText(stringResource(R.string.none))
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
        Text(stringResource(R.string.section_editor), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = draft.primary,
            onValueChange = { onDraftChange(draft.copy(primary = it)) },
            label = { Text(stringResource(section.primaryLabelRes())) },
            singleLine = section != BibleSection.TIMELINE,
            minLines = if (section == BibleSection.TIMELINE) 2 else 1,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.secondary,
            onValueChange = { onDraftChange(draft.copy(secondary = it)) },
            label = { Text(stringResource(section.secondaryLabelRes())) },
            minLines = if (section == BibleSection.WORLD_RULES) 2 else 1,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.tertiary,
            onValueChange = { onDraftChange(draft.copy(tertiary = it)) },
            label = { Text(stringResource(section.tertiaryLabelRes())) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        if (section == BibleSection.CHARACTERS || section == BibleSection.TIMELINE) {
            OutlinedTextField(
                value = draft.quaternary,
                onValueChange = { onDraftChange(draft.copy(quaternary = it)) },
                label = { Text(stringResource(section.quaternaryLabelRes())) },
                minLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = !isBusy && draft.primary.isNotBlank()) { Text(stringResource(R.string.action_save_entry)) }
            OutlinedButton(onClick = onNew, enabled = !isBusy) { Text(stringResource(R.string.action_new)) }
            OutlinedButton(onClick = onDelete, enabled = !isBusy && draft.id.isNotBlank()) { Text(stringResource(R.string.action_delete)) }
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
            TextButton(onClick = onEdit) { Text(stringResource(R.string.action_edit)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
        }
        Text(entry.detail.ifBlank { stringResource(R.string.none) }, style = MaterialTheme.typography.bodySmall)
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
        Text(stringResource(R.string.section_canon_conflicts), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        conflicts.forEach { conflict ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFE2B35B), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${conflict.section.name}: ${conflict.title} / ${conflict.field}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                CompactBlock(stringResource(R.string.label_canon), conflict.existingValue)
                CompactBlock(stringResource(R.string.label_incoming), conflict.incomingValue)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onResolveConflict(conflict.id, false) }, enabled = !isBusy) { Text(stringResource(R.string.action_keep_canon)) }
                    Button(onClick = { onResolveConflict(conflict.id, true) }, enabled = !isBusy) { Text(stringResource(R.string.action_use_incoming)) }
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
        Text(stringResource(R.string.section_duplicate_warnings), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        warnings.forEach { warning ->
            Text("${stringResource(warning.section.labelRes())}: ${warning.title} - ${warning.detail}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BibleContextPreview(bible: NovelBible, chapter: Chapter?, scene: Scene?) {
    val preview = bible.relevancePreview(chapter, scene)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.section_context_preview), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (preview.isEmpty()) {
            EmptyText(stringResource(R.string.empty_no_matching_bible))
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
        Text(stringResource(R.string.section_history_metrics), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        MetricStrip(
            stringResource(R.string.metric_snapshots) to state.revisionSnapshots.size.toString(),
            stringResource(R.string.metric_input) to usage.inputTokens.toString(),
            stringResource(R.string.metric_output) to usage.outputTokens.toString(),
            stringResource(R.string.metric_total) to usage.totalTokens.toString(),
            stringResource(R.string.metric_cost) to usage.costLabel,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.section_revision_snapshots), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (state.revisionSnapshots.isEmpty()) {
                EmptyText(stringResource(R.string.empty_no_snapshots))
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

            Text(stringResource(R.string.section_token_usage), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (state.tokenUsage.isEmpty()) {
                EmptyText(stringResource(R.string.empty_no_token_usage))
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
            TextButton(onClick = onRestore, enabled = !isBusy) { Text(stringResource(R.string.action_restore)) }
            TextButton(onClick = onDelete, enabled = !isBusy) { Text(stringResource(R.string.action_delete)) }
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
            stringResource(R.string.metric_input) to record.inputTokens.toString(),
            stringResource(R.string.metric_output) to record.outputTokens.toString(),
            stringResource(R.string.metric_total) to (record.inputTokens + record.outputTokens).toString(),
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

    Text(stringResource(R.string.section_provider_settings), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderMenu(provider, onChange = { provider = it }, modifier = Modifier.widthIn(min = 180.dp, max = 320.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(stringResource(R.string.field_api_key)) },
            singleLine = true,
            visualTransformation = if (apiKey.isBlank()) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.widthIn(min = 240.dp, max = 420.dp),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text(stringResource(R.string.field_base_url)) },
            singleLine = true,
            modifier = Modifier.widthIn(min = 240.dp, max = 420.dp),
        )
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModelField(stringResource(R.string.field_outline_model), outlineModel, { outlineModel = it })
        ModelField(stringResource(R.string.field_synopsis_model), synopsisModel, { synopsisModel = it })
        ModelField(stringResource(R.string.field_text_model), textModel, { textModel = it })
        ModelField(stringResource(R.string.field_review_model), reviewModel, { reviewModel = it })
        ModelField(stringResource(R.string.field_continuity_model), continuityModel, { continuityModel = it })
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
        Text(stringResource(R.string.action_save_settings))
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
            label = { Text(stringResource(R.string.field_provider)) },
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
            value = stringResource(genre.labelRes()),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_genre)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Genre.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.labelRes())) },
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
        modifier = Modifier.widthIn(min = 220.dp, max = 360.dp),
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
            Text(stringResource(R.string.section_review), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${report.score}/10", style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.SemiBold)
        }
        MetricStrip(
            stringResource(R.string.metric_result) to stringResource(if (report.passed) R.string.review_passed else R.string.review_failed),
            stringResource(R.string.metric_status) to report.status.name,
            stringResource(R.string.metric_retries) to report.retryCount.toString(),
        )
        ReviewList(stringResource(R.string.section_issues), report.issues)
        ReviewList(stringResource(R.string.section_suggested_fixes), report.suggestedFixes)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAccept, enabled = !isBusy) { Text(stringResource(R.string.action_accept)) }
            OutlinedButton(onClick = onRetry, enabled = !isBusy) { Text(stringResource(R.string.action_retry)) }
            OutlinedButton(onClick = onManualEdit, enabled = !isBusy) { Text(stringResource(R.string.action_edit_manually)) }
            Button(onClick = onApprove, enabled = !isBusy) { Text(stringResource(R.string.action_mark_approved)) }
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
        Text(body.ifBlank { stringResource(R.string.none) }, style = MaterialTheme.typography.bodySmall)
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
            val status = workflow.error ?: workflow.events.lastOrNull()?.plus(queue) ?: stringResource(R.string.status_idle)
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
                Text(stringResource(if (workflow.isBusy) R.string.action_cancel else R.string.status_ready))
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

private fun Genre.labelRes(): Int {
    return when (this) {
        Genre.FANTASY -> R.string.genre_fantasy
        Genre.SCI_FI -> R.string.genre_sci_fi
        Genre.ROMANCE -> R.string.genre_romance
        Genre.MYSTERY -> R.string.genre_mystery
        Genre.THRILLER -> R.string.genre_thriller
        Genre.LITERARY -> R.string.genre_literary
        Genre.HISTORICAL -> R.string.genre_historical
        Genre.HORROR -> R.string.genre_horror
        Genre.OTHER -> R.string.genre_other
    }
}

private fun BibleSection.labelRes(): Int {
    return when (this) {
        BibleSection.CHARACTERS -> R.string.bible_characters
        BibleSection.LOCATIONS -> R.string.bible_locations
        BibleSection.TIMELINE -> R.string.bible_timeline
        BibleSection.WORLD_RULES -> R.string.bible_world_rules
        BibleSection.THEMES -> R.string.bible_themes
    }
}

private fun BibleSection.primaryLabelRes(): Int {
    return when (this) {
        BibleSection.CHARACTERS -> R.string.field_name
        BibleSection.LOCATIONS -> R.string.field_name
        BibleSection.TIMELINE -> R.string.field_event_description
        BibleSection.WORLD_RULES -> R.string.field_category
        BibleSection.THEMES -> R.string.field_name
    }
}

private fun BibleSection.secondaryLabelRes(): Int {
    return when (this) {
        BibleSection.CHARACTERS -> R.string.field_description
        BibleSection.LOCATIONS -> R.string.field_description
        BibleSection.TIMELINE -> R.string.field_chapter_id
        BibleSection.WORLD_RULES -> R.string.field_rule
        BibleSection.THEMES -> R.string.field_description
    }
}

private fun BibleSection.tertiaryLabelRes(): Int {
    return when (this) {
        BibleSection.CHARACTERS -> R.string.field_personality
        BibleSection.LOCATIONS -> R.string.field_significance
        BibleSection.TIMELINE -> R.string.field_scene_id
        BibleSection.WORLD_RULES -> R.string.field_details
        BibleSection.THEMES -> R.string.field_motif_symbols_csv
    }
}

private fun BibleSection.quaternaryLabelRes(): Int {
    return when (this) {
        BibleSection.CHARACTERS -> R.string.field_current_state
        BibleSection.TIMELINE -> R.string.field_chronological_order
        else -> R.string.field_extra
    }
}

private fun countDraftWords(text: String): Int {
    return text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
}

private fun bibleCount(novel: Novel): Int {
    return novel.bible.characters.size + novel.bible.locations.size + novel.bible.timelineEvents.size + novel.bible.worldRules.size + novel.bible.themes.size
}
