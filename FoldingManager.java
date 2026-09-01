package ui.editor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.fxmisc.richtext.CodeArea;

import language.folding.FoldingRangeFinder;
import language.folding.FoldingRangeFinder.FoldRange;

/**
 * Finds NC block-keyword fold ranges and drives CodeArea's own native
 * paragraph folding to collapse/expand them: {@code foldParagraphs}/
 * {@code unfoldParagraphs}/{@code isFolded} are inherited from
 * {@code StyleClassedTextArea} (confirmed present in richtextfx 0.11.7 by
 * decompiling the jar — RichTextFX marks folded paragraphs with a "collapse"
 * style class, which {@code ParagraphBox} already renders at exactly zero
 * height, and {@code GenericStyledArea} already skips the caret over folded
 * paragraphs internally).
 *
 * <p>
 * Folding never touches the real text, only paragraph style, so Save/Run/
 * error-checking/highlighting (which all read {@code editor.getText()}) are
 * unaffected by fold state. No custom CSS or caret handling is needed here —
 * both are native to the library.
 */
public class FoldingManager {

    private Map<Integer, FoldRange> rangesByStartLine = new HashMap<>();

    public void recompute(String text) {
        List<FoldRange> ranges = FoldingRangeFinder.find(text);
        Map<Integer, FoldRange> byStart = new HashMap<>();
        for (FoldRange r : ranges) {
            byStart.put(r.startLine, r);
        }
        rangesByStartLine = byStart;
    }

    public boolean isFoldStart(int line) {
        return rangesByStartLine.containsKey(line);
    }

    /** A range is collapsed iff its first body line (startLine + 1) carries the native "collapse" style. */
    public boolean isCollapsed(CodeArea editor, int line) {
        FoldRange range = rangesByStartLine.get(line);
        return range != null
                && range.startLine + 1 < editor.getParagraphs().size()
                && editor.isFolded(range.startLine + 1);
    }

    /** Flips the collapse state of the range starting at {@code startLine}. */
    public void toggleFold(CodeArea editor, int startLine) {
        FoldRange range = rangesByStartLine.get(startLine);
        if (range == null) {
            return;
        }
        if (editor.isFolded(range.startLine + 1)) {
            editor.unfoldParagraphs(range.startLine);
        } else {
            editor.foldParagraphs(range.startLine, range.endLine);
        }
    }
}
