package eu.kanade.presentation.more.settings.screen

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsAiScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_advanced // Fallback to avoid error

    @Composable
    fun getTitle() = "Advanced Analytics"

    @Composable
    override fun getPreferences(): List<Preference> {
        val aiPreferences = remember { Injekt.get<AiPreferences>() }
        val enableAi by aiPreferences.enableAi().collectAsState()

        return if (enableAi) {
            listOf(
                getMainGroup(aiPreferences),
                getIdentityGroup(aiPreferences),
                getAssistantGroup(aiPreferences),
                getStatisticsGroup(aiPreferences),
            )
        } else {
            listOf(
                getMainGroup(aiPreferences),
                getIdentityGroup(aiPreferences),
            )
        }
    }

    @Composable
    private fun getIdentityGroup(aiPreferences: AiPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        
        val pickImage = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {}
                aiPreferences.profilePhotoUri().set(uri.toString())
            }
        }

        return Preference.PreferenceGroup(
            title = "Personalization",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.EditTextPreference(
                    preference = aiPreferences.displayName(),
                    title = "Analytics Persona",
                    subtitle = "Your identifier in system reports",
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Analytics Avatar",
                    subtitle = "Set your reporting identifier image",
                    onClick = { pickImage.launch("image/*") }
                ),
            ),
        )
    }

    @Composable
    private fun getMainGroup(aiPreferences: AiPreferences): Preference.PreferenceGroup {
        val enableAiPref = aiPreferences.enableAi()
        val enableAi by enableAiPref.collectAsState()
        val aiEngine by aiPreferences.aiEngine().collectAsState()

        return Preference.PreferenceGroup(
            title = "Processing Engine",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = enableAiPref,
                    title = "Enable Processing Core",
                    subtitle = "Activates the analytical engine for data processing",
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = aiPreferences.aiEngine(),
                    title = "LLM Processor",
                    subtitle = "Select the computational backend: %s",
                    entries = persistentMapOf(
                        "gemini" to "Google Gemini (Analytical)",
                        "groq" to "Groq (High-Speed Inference)",
                    ),
                    enabled = enableAi,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = aiPreferences.geminiApiKey(),
                    title = "Gemini API Key",
                    subtitle = "Used for Gemini processing",
                    enabled = enableAi && aiEngine == "gemini",
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = aiPreferences.groqApiKey(),
                    title = "Groq API Key",
                    subtitle = "Used for high-speed inference",
                    enabled = enableAi && aiEngine == "groq",
                )
            ),
        )
    }

    @Composable
    private fun getAssistantGroup(aiPreferences: AiPreferences): Preference.PreferenceGroup {
        val enableAi by aiPreferences.enableAi().collectAsState()

        return Preference.PreferenceGroup(
            title = "Diagnostic Assistant",
            enabled = enableAi,
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = aiPreferences.enableAiAssistant(),
                    title = "Enable Assistant",
                    subtitle = "Enables conversational diagnostics",
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = aiPreferences.aiAssistantLogs(),
                    title = "Ingest Error Logs",
                    subtitle = "Allows the assistant to analyze stack traces",
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = aiPreferences.aiAssistantLibrary(),
                    title = "Ingest Library Context",
                    subtitle = "Allows the assistant to analyze your collection",
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = aiPreferences.aiSystemPrompt(),
                    title = "Custom System Prompt",
                    subtitle = "Override the default behavioral instructions",
                ),
            ),
        )
    }

    @Composable
    private fun getStatisticsGroup(aiPreferences: AiPreferences): Preference.PreferenceGroup {
        val enableAi by aiPreferences.enableAi().collectAsState()

        return Preference.PreferenceGroup(
            title = "Advanced Analytics",
            enabled = enableAi,
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = aiPreferences.enableAiStatistics(),
                    title = "Data Summarization",
                    subtitle = "Generates technical summaries in the Statistics module",
                ),
            ),
        )
    }
}
