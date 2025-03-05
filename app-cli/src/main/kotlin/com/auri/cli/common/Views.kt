package com.auri.cli.common

import com.auri.core.common.util.atLeastEvery
import com.github.ajalt.colormath.model.Oklab
import com.github.ajalt.colormath.model.SRGB
import com.github.ajalt.colormath.transform.interpolator
import com.github.ajalt.colormath.transform.sequence
import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.table.VerticalLayoutBuilder
import com.github.ajalt.mordant.table.horizontalLayout
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Spinner
import com.github.ajalt.mordant.widgets.Text
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource


suspend fun <T> Terminal.baseAuriTui(
    phaseTitle: String,
    phaseDescription: String,
    phaseData: Flow<T>,
    phaseTui: VerticalLayoutBuilder.(T) -> Unit
) {
    val startMark = TimeSource.Monotonic.markNow()

    println(appTitle())
    println(phaseTitle.titleStyle())
    println(TextStyles.dim(phaseDescription))
    println()
    val mainAnimation = animation<T> { data ->
        verticalLayout {
            cell(horizontalLayout {
                style(italic = true, dim = false)
                cell(Spinner.Dots(initial = ((startMark.elapsedNow().inWholeMilliseconds / 100) % 10).toInt()))
                cell(Text("Running for ${startMark.elapsedNow().inWholeSeconds.seconds}"))
            })
            cell(Text(""))
            phaseTui(data)
            cell(Text(""))
        }
    }

    phaseData
        .atLeastEvery(1.seconds / 30) // Ensure at least 30 fps
        .collect(mainAnimation::update)
}

private fun appTitle(): String =
    """
       d8888 888     888 8888888b.  8888888 
      d88888 888     888 888   Y88b   888   
     d88P888 888     888 888    888   888   
    d88P 888 888     888 888   d88P   888   
   d88P  888 888     888 8888888P"    888   
  d88P   888 888     888 888 T88b     888   
 d8888888888 Y88b. .d88P 888  T88b    888   
d88P     888  "Y88888P"  888   T88b 8888888
""".titleStyle()


private fun String.titleStyle() = buildString {
    for (line in this@titleStyle.trim('\n').lineSequence()) {
        val lerp = Oklab.interpolator {
            stop(SRGB("#e74856"))
            stop(SRGB("#9648e7"))
        }.sequence(line.length)
        line.asSequence().zip(lerp).forEach { (c, color) ->
            append(TextColors.color(color)(c.toString()))
        }
        append("\n")
    }
}
