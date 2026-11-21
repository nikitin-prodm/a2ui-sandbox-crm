package dev.a2uicrm.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.a2uicrm.agentic.action.ActionDispatcher
import dev.a2uicrm.agentic.engine.AgenticSurfaceHost
import dev.a2uicrm.agentic.render.A2uiRenderContext
import dev.a2uicrm.agentic.render.A2uiRendererRegistry
import dev.a2uicrm.agentic.render.LoadingSurface

/**
 * The agent-composed screen: rendered by the A2UI renderer from the registry snapshot,
 * with a banner that makes the hybrid explicit — the user can always go back to the
 * standard native view.
 */
@Composable
fun A2UIScreen(
    host: AgenticSurfaceHost,
    dispatcher: ActionDispatcher,
    onExit: () -> Unit,
    preferredSurfaceId: String? = null,
    /** Full reset of the agent view for this tab (the VIEW only — tasks/deals live in
     *  the repository and are NOT touched; a new screen can be composed again). */
    onDelete: (() -> Unit)? = null,
    /** Section title identical to the native screens ("My day" / "Team" / "Analytics"):
     *  the agent view looks like the CURRENT section extended, not a foreign screen. */
    title: String? = null,
) {
    val components = host.registry.components
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // Same title style as the native screens (NativeScreens.ScreenTitle).
        if (title != null) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = "Screen composed by the A2UI agent",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onExit) { Text("Back", fontSize = 12.sp) }
                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete) { Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (components.isEmpty()) {
            LoadingSurface()
        } else {
            val byId = remember(components) { components.associateBy { it.id } }
            val root = byId["root"]
            if (root != null) {
                val surfaceId = preferredSurfaceId
                    ?: host.activeSurfaces().lastOrNull()?.id
                    ?: "surface"
                val ctx = remember(byId, surfaceId) {
                    A2uiRenderContext(surfaceId, byId, host.dataModel, dispatcher)
                }
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                ) {
                    A2uiRendererRegistry.render(root, ctx)
                    // FAB clearance: the last CTA / card must not hide under the A2UI button.
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 88.dp))
                }
            }
        }
    }
}
