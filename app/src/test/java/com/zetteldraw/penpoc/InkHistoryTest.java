package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.data.TestStrokes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class InkHistoryTest {
    private final InkHistory history = new InkHistory();
    private final Board page = new Board("p", 1L);

    @Test
    public void strokesUndoAndRedoInOrder() {
        InkRenderer.InkStroke a = draw(10f);
        InkRenderer.InkStroke b = draw(20f);
        assertTrue(history.canUndo());
        assertFalse(history.canRedo());

        assertEquals(Set.of(page), history.undo());
        assertEquals(List.of(a), page.strokes);
        history.undo();
        assertTrue(page.strokes.isEmpty());
        assertFalse(history.canUndo());
        assertTrue(history.undo().isEmpty());

        history.redo();
        history.redo();
        assertEquals(List.of(a, b), page.strokes);
        assertFalse(history.canRedo());
    }

    @Test
    public void eraseUndoPutsStrokesBackWhereTheyWere() {
        InkRenderer.InkStroke a = draw(10f);
        InkRenderer.InkStroke b = draw(20f);
        InkRenderer.InkStroke c = draw(30f);
        InkRenderer.InkStroke d = draw(40f);
        List<InkHistory.Placed> removed = new ArrayList<>();
        removed.add(new InkHistory.Placed(b, 1));
        removed.add(new InkHistory.Placed(d, 3));
        page.strokes.remove(d);
        page.strokes.remove(b);
        history.record(InkHistory.Edit.of(InkHistory.Part.removed(page, removed)));

        history.undo();
        assertEquals(List.of(a, b, c, d), page.strokes);
        history.redo();
        assertEquals(List.of(a, c), page.strokes);
    }

    @Test
    public void eraseAcrossTwoPagesIsOneStep() {
        Board other = new Board("q", 2L);
        InkRenderer.InkStroke a = draw(10f);
        InkRenderer.InkStroke x = TestStrokes.stroke(50f, 50f);
        other.strokes.add(x);
        InkHistory.Edit erase = new InkHistory.Edit();
        page.strokes.clear();
        other.strokes.clear();
        erase.add(InkHistory.Part.removed(page, List.of(new InkHistory.Placed(a, 0))));
        erase.add(InkHistory.Part.removed(other, List.of(new InkHistory.Placed(x, 0))));
        history.record(erase);

        assertEquals(Set.of(page, other), history.undo());
        assertEquals(List.of(a), page.strokes);
        assertEquals(List.of(x), other.strokes);
    }

    @Test
    public void lassoMoveUndoRestoresPointsAndRedoMovesAgain() {
        InkRenderer.InkStroke a = draw(10f);
        InkRenderer.InkStroke b = draw(200f);
        Lasso.Move move = Lasso.move(page, Set.of(a.id), 30f, 40f);
        InkRenderer.InkStroke moved = page.strokes.get(0);
        history.record(InkHistory.Edit.of(move.historyPart()));
        assertEquals(a.id, moved.id);

        history.undo();
        assertSame(a, page.strokes.get(0));
        assertSame(b, page.strokes.get(1));
        history.redo();
        assertSame(moved, page.strokes.get(0));
    }

    @Test
    public void editsApplyByIdAfterOtherChanges() {
        InkRenderer.InkStroke a = draw(10f);
        InkRenderer.InkStroke b = draw(20f);
        page.strokes.remove(a);
        assertEquals(Set.of(page), history.undo());
        assertTrue("undoing b's stroke still removes b", page.strokes.isEmpty());
        assertTrue("a is already gone: nothing to change", history.undo().isEmpty());
        history.redo();
        assertEquals(List.of(a), page.strokes);
        assertFalse(page.strokes.contains(b));
    }

    @Test
    public void newEditClearsRedoAndTheStackIsCapped() {
        draw(10f);
        history.undo();
        assertTrue(history.canRedo());
        draw(20f);
        assertFalse("a new edit drops the redo branch", history.canRedo());

        for (int i = 0; i < InkHistory.LIMIT + 10; i++) {
            draw(30f + i);
        }
        assertEquals(InkHistory.LIMIT, history.undoSize());
    }

    @Test
    public void editsOnPagesThatLeftTheListAreDropped() {
        draw(10f);
        Board other = new Board("q", 2L);
        InkRenderer.InkStroke x = TestStrokes.stroke(50f, 50f);
        other.strokes.add(x);
        history.record(InkHistory.Edit.of(InkHistory.Part.added(other, x)));
        history.retainPages(Set.of(page.id));
        assertEquals(1, history.undoSize());
        history.clear();
        assertFalse(history.canUndo());
    }

    private InkRenderer.InkStroke draw(float x) {
        InkRenderer.InkStroke stroke = TestStrokes.stroke(x, x);
        page.strokes.add(stroke);
        history.record(InkHistory.Edit.of(InkHistory.Part.added(page, stroke)));
        return stroke;
    }
}
