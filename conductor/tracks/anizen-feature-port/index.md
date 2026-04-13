# Track: Anizen Feature Port

## Summary
Literal and logic port of advanced features from the AniZen project to Mihon, adapted for manga context.

## Ported Features
- **Revamped Statistics Page:** Modern UI with Radar (Genre), Pie (Status), and Bar (Score) charts.
- **Extension Health (Command Center):** Real-time endpoint monitoring and diagnostic telemetry.
- **AI Diagnosis OS (Assistant Chat):** High-reliability conversational interface using `gemini-3-flash-preview`.
- **Behavioral Analytics:** AI-powered library summarization integrated into the Statistics page.
- **Advanced AI Settings:** Centralized configuration for LLM engines, API keys, and Analytics Persona.
- **Anizen-Style Workflows:** Automated signed preview and release pipelines with AI changelogs.

## Fixes & Polish
- Keyboard alignment using `adjustResize` and direct `WindowInsets.ime` padding.
- High-fidelity Markdown rendering for AI summaries.
- Synchronized personal identity (Persona/Avatar) across Stats and AI screens.
- Correct section ordering on the Statistics screen.
- Improved AI grounding using real system, extension, and library metadata.
