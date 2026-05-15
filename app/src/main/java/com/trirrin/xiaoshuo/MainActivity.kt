package com.trirrin.xiaoshuo

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trirrin.xiaoshuo.data.GenerationSettings
import com.trirrin.xiaoshuo.model.Chapter
import com.trirrin.xiaoshuo.model.ChapterBrief
import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.Novel
import com.trirrin.xiaoshuo.model.Scene

private enum class WorkspaceTab(val label: String) {
    Library("Library"),
    Outline("Outline"),
    Draft("Draft"),
    Bible("Bible"),
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
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopBar(state = state)
        },
        bottomBar = {
            RunLog(workflow = state.workflow)
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
                WorkspaceTab.Outline -> OutlineScreen(state, onGenerateOutline)
                WorkspaceTab.Draft -> DraftScreen(state, onSelectChapter, onSelectScene, onGenerateSynopsis, onGenerateScene)
                WorkspaceTab.Bible -> BibleScreen(state.selectedNovel)
                WorkspaceTab.Settings -> SettingsScreen(state.settings, onSaveSettings)
            }
        }
    }
}

@Composable
private fun TopBar(state: NovelWorkspaceUiState) {
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
private fun OutlineScreen(state: NovelWorkspaceUiState, onGenerateOutline: () -> Unit) {
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
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(outline.premise, style = MaterialTheme.typography.bodyMedium)
                    }
                    item {
                        Text("Plot Points", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    items(outline.majorPlotPoints) { point ->
                        CompactBlock(point.name, "${(point.position * 100).toInt()}% - ${point.description}")
                    }
                    item {
                        Text("Chapters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    items(outline.chapterBriefs) { brief ->
                        ChapterBriefBlock(brief)
                    }
                }
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
                            subtitle = chapter.status.name,
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
            ChapterSynopsis(state.selectedChapter)
            if (state.scenes.isEmpty()) {
                EmptyText("No scene breakdowns yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.scenes, key = { it.id }) { scene ->
                        SelectableRow(
                            title = "Scene ${scene.order}",
                            subtitle = "${scene.status.name} - ${scene.wordCount} words",
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
            SceneEditor(state.selectedScene)
        }
    }
}

@Composable
private fun ChapterSynopsis(chapter: Chapter?) {
    val synopsis = chapter?.synopsis ?: return
    CompactBlock("Goal", synopsis.chapterGoal)
    CompactBlock("Ending", synopsis.chapterEnding)
}

@Composable
private fun SceneEditor(scene: Scene?) {
    if (scene == null) {
        EmptyText("Select a scene.")
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompactBlock("Synopsis", scene.synopsis)
        val reviewNotes = scene.reviewNotes
        if (reviewNotes?.isNotBlank() == true) {
            CompactBlock("Review", reviewNotes)
        }
        Text(
            text = scene.text.ifBlank { "No prose generated." },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (scene.text.isBlank()) 0.62f else 1f),
        )
    }
}

@Composable
private fun BibleScreen(novel: Novel?) {
    SurfacePanel(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("Novel Bible", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (novel == null) {
            EmptyText("Create or select a novel first.")
            return@SurfacePanel
        }
        val bible = novel.bible
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { BibleGroup("Characters", bible.characters.map { it.name to it.description }) }
            item { BibleGroup("Locations", bible.locations.map { it.name to it.description }) }
            item { BibleGroup("Timeline", bible.timelineEvents.map { it.description to "Chapter ${it.chapterId}" }) }
            item { BibleGroup("World Rules", bible.worldRules.map { it.category to it.rule }) }
            item { BibleGroup("Themes", bible.themes.map { it.name to it.description }) }
        }
    }
}

@Composable
private fun BibleGroup(title: String, entries: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (entries.isEmpty()) {
            EmptyText("None")
        } else {
            entries.forEach { (name, detail) -> CompactBlock(name, detail) }
        }
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
private fun RunLog(workflow: WorkflowState) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val status = workflow.error ?: workflow.events.lastOrNull() ?: "Idle"
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
            TextButton(onClick = {}) {
                Text(if (workflow.isBusy) "Running" else "Ready")
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

private fun bibleCount(novel: Novel): Int {
    return novel.bible.characters.size + novel.bible.locations.size + novel.bible.timelineEvents.size + novel.bible.worldRules.size + novel.bible.themes.size
}
