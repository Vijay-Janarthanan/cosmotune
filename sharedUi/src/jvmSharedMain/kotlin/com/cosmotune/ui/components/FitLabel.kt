package com.cosmotune.ui.components

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.cosmotune.design.LabelFit

/**
 * A label that shrinks to fit its box: the largest size from [maxSp] down to [minSp], [stepSp] at a time ([LabelFit.sizes]), at which
 * nothing overflows (no line past the text's `maxLines`, nothing wider than the box) and, with [wholeWords], no line breaks inside a
 * word ([LabelFit.breaksOnlyAtSpaces]); [minSp] when none does, and the text's overflow (an ellipsis) cuts what is left. One layout
 * per size tried, at measure time only: a press that scales a control's layer measures nothing again.
 *
 * Compose's own `TextAutoSize.StepBased` asks whether the last line was ellipsised, which the desktop's text layout does not report
 * for a word broken past the last line, so a label too wide for its box kept its largest size there (seen in a headless render);
 * the overflow of the layout is what this asks.
 */
internal class FitLabel(
    private val maxSp: Float,
    private val minSp: Float,
    private val stepSp: Float,
    private val wholeWords: Boolean,
) : TextAutoSize {
    private val sizes = LabelFit.sizes(maxSp, minSp, stepSp)

    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        for (sp in sizes) {
            val layout = performLayout(constraints, text, sp.sp)
            if (layout.hasVisualOverflow) continue
            if (wholeWords && !LabelFit.breaksOnlyAtSpaces(text.text, IntArray(layout.lineCount) { layout.getLineEnd(it) })) continue
            return sp.sp
        }
        return sizes.last().sp
    }

    override fun equals(other: Any?): Boolean =
        other is FitLabel && other.maxSp == maxSp && other.minSp == minSp && other.stepSp == stepSp && other.wholeWords == wholeWords

    override fun hashCode(): Int = ((maxSp.hashCode() * 31 + minSp.hashCode()) * 31 + stepSp.hashCode()) * 31 + wholeWords.hashCode()
}
