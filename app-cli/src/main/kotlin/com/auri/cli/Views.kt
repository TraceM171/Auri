package com.auri.cli

import com.github.ajalt.colormath.model.Oklab
import com.github.ajalt.colormath.model.SRGB
import com.github.ajalt.colormath.transform.interpolator
import com.github.ajalt.colormath.transform.sequence
import com.github.ajalt.mordant.rendering.TextColors


fun appTitle(): String =
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

fun collectionPhaseTitle() =
    """
____ ____ _    _    ____ ____ ___ _ ____ _  _ 
|    |  | |    |    |___ |     |  | |  | |\ | 
|___ |__| |___ |___ |___ |___  |  | |__| | \|
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
