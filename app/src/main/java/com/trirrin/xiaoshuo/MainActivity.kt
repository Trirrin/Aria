package com.trirrin.xiaoshuo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trirrin.xiaoshuo.data.GenerationSettings
import com.trirrin.xiaoshuo.model.Genre
import com.trirrin.xiaoshuo.model.Novel

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
                NovelWorkspaceScreen(
                    state = state,
                    onCreateNovel = viewModel::createNovel,
                    onSelectNovel = viewModel::selectNovel,
                    onSaveSettings = viewModel::saveSettings,
                    onGenerateOutline = viewModel::generateOutline,
                    onGenerateSynopsis = viewModel::generateFirstSynopsis,
                    onGenerateScene = viewModel::generateFirstScene,
                )
            }
        }
    }
}

@Composable
private fun XiaoShuoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1F6F5F),
            secondary = Color(0xFF8B5E34),
            tertiary = Color(0xFF355C7D),
            background = Color(0xFFF7F8F3),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF20231F),
        ),
        content = content,
    )
}

@Composable
private fun NovelWorkspaceScreen(
    state: NovelWorkspaceUiState,
    onCreateNovel: (String, String, Genre, String) -> Unit,
    onSelectNovel: (String) -> Unit,
    onSaveSettings: (GenerationSettings) -> Unit,
    onGenerateOutline: () -> Unit,
    onGenerateSynopsis: () -> Unit,
    onGenerateScene: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Header()
            SettingsPanel(state.settings, onSaveSettings)
            CreateNovelPanel(onCreateNovel)
            CurrentNovelPanel(state, onSelectNovel, onGenerateOutline, onGenerateSynopsis, onGenerateScene)
            EventLog(state.workflow)
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Xiao Shuo",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Persisted AI novel pipeline",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
        )
    }
}

@Composable
private fun SettingsPanel(settings: GenerationSettings, onSave: (GenerationSettings) -> Unit) {
    var provider by remember(settings) { mutableStateOf(settings.provider) }
    var apiKey by remember(settings) { mutableStateOf(settings.apiKey) }
    var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl) }
    var outlineModel by remember(settings) { mutableStateOf(settings.outlineModel) }
    var synopsisModel by remember(settings) { mutableStateOf(settings.synopsisModel) }
    var textModel by remember(settings) { mutableStateOf(settings.textModel) }
    var reviewModel by remember(settings) { mutableStateOf(settings.reviewModel) }
    var continuityModel by remember(settings) { mutableStateOf(settings.continuityModel) }

    Section(title = "Provider Settings") {
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
            Text("Save settings")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderMenu(provider: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = provider,
            onValueChange = {},
            readOnly = true,
            label = { Text("Provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
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
private fun CreateNovelPanel(onCreateNovel: (String, String, Genre, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var concept by remember { mutableStateOf("") }
    var themes by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf(Genre.FANTASY) }

    Section(title = "New Novel") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            GenreMenu(genre = genre, onChange = { genre = it }, modifier = Modifier.width(240.dp))
        }
        OutlinedTextField(
            value = concept,
            onValueChange = { concept = it },
            label = { Text("Concept") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = themes,
            onValueChange = { themes = it },
            label = { Text("Themes") },
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
        ) {
            Text("Create novel")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreMenu(genre: Genre, onChange: (Genre) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = genre.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Genre") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
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
private fun CurrentNovelPanel(
    state: NovelWorkspaceUiState,
    onSelectNovel: (String) -> Unit,
    onGenerateOutline: () -> Unit,
    onGenerateSynopsis: () -> Unit,
    onGenerateScene: () -> Unit,
) {
    val novel = state.selectedNovel
    Section(title = "Workspace") {
        if (state.novels.isEmpty()) {
            Text(
                text = "No novels yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
            )
            return@Section
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.novels.forEach { item ->
                val selected = item.id == novel?.id
                if (selected) {
                    Button(onClick = { onSelectNovel(item.id) }) { Text(item.title) }
                } else {
                    OutlinedButton(onClick = { onSelectNovel(item.id) }) { Text(item.title) }
                }
            }
        }

        novel?.let {
            NovelSummary(it)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onGenerateOutline, enabled = !state.workflow.isBusy) { Text("Generate outline") }
                Button(onClick = onGenerateSynopsis, enabled = !state.workflow.isBusy && it.outline != null) { Text("Generate first synopsis") }
                Button(onClick = onGenerateScene, enabled = !state.workflow.isBusy) { Text("Generate first scene") }
            }
        }
    }
}

@Composable
private fun NovelSummary(novel: Novel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFD8DDD3), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = novel.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = novel.status.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(text = novel.concept, style = MaterialTheme.typography.bodyMedium)
        MetricRow(
            "Chapters",
            novel.outline?.chapterBriefs?.size?.toString() ?: "0",
            "Bible entries",
            (novel.bible.characters.size + novel.bible.locations.size + novel.bible.timelineEvents.size + novel.bible.worldRules.size).toString(),
        )
    }
}

@Composable
private fun EventLog(workflow: WorkflowState) {
    Section(title = "Run Log") {
        if (workflow.error != null) {
            Text(
                text = workflow.error,
                color = Color(0xFF9A2E2E),
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (workflow.events.isEmpty()) {
            Text(
                text = "Idle",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            )
        } else {
            workflow.events.asReversed().forEach { event ->
                Text(text = event, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun MetricRow(firstLabel: String, firstValue: String, secondLabel: String, secondValue: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Metric(firstLabel, firstValue, Modifier.weight(1f))
        Spacer(modifier = Modifier.width(12.dp))
        Metric(secondLabel, secondValue, Modifier.weight(1f))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFFE8EFE5), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        )
    }
}
