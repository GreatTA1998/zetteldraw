package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.data.NotebookPaper;
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
    private final NotebookPaper paper = NotebookPaper.empty(1000);
    private final Board page = paperBoard(paper);

    private static Board paperBoard(NotebookPaper paper) {
        Board board = Board.blank();
        board.paper = paper;
        board.sliceIndex = 0;
        board.slicePx = 1000;
        return board;
    }

    private static List<String> ids(NotebookPaper paper) {
        ArrayList<String> ids = new ArrayList<>();
        for (InkRenderer.InkStroke stroke : paper.strokes()) {
            ids.add(stroke.id);
        }
        return ids;
    }

    @Test
    public void strokesUndoAndRedoInOrder() {
        InkRenderer.InkStroke a = draw(10f);
        InkRenderer.InkStroke b = draw(20f);
        assertTrue(history.canUndo());
        assertFalse(history.canRedo());

        assertEquals(Set.of(page), history.undo());
        assertEquals(List.of(a.id), ids(paper));
        history.undo();
        assertTrue(paper.strokes().isEmpty());
        assertFalse(history.canUndo());
        assertTrue(history.undo().isEmpty());

        history.redo();
        history.redo();
        assertEquals(List.of(a.id, b.id), ids(paper));
        assertFalse(history.canRedo());
    }

    @Test
    public void eraseUndoPutsStrokesBack() {
        InkRenderer.InkStroke a = draw(10f);
        InkRenderer.InkStroke b = draw(20f);
        InkRenderer.InkStroke c = draw(30f);
        paper.deleteIds(List.of(b.id, c.id));
        history.record(InkHistory.Edit.of(InkHistory.Part.paperRemoved(page, List.of(b, c))));
        assertEquals(List.of(a.id), ids(paper));

        history.undo();
        assertEquals(Set.of(a.id, b.id, c.id), Set.copyOf(ids(paper)));
        history.redo();
        assertEquals(List.of(a.id), ids(paper));
    }

    @Test
    public void eraseAcrossTwoPapersIsOneStep() {
        NotebookPaper otherPaper = NotebookPaper.empty(1000);
        Board other = paperBoard(otherPaper);
        InkRenderer.InkStroke a = draw(10f);
        InkRenderer.InkStroke x = TestStrokes.stroke(50f, 50f);
        otherPaper.appendStroke(x);
        paper.deleteIds(List.of(a.id));
        otherPaper.deleteIds(List.of(x.id));
        InkHistory.Edit erase = new InkHistory.Edit();
        erase.add(InkHistory.Part.paperRemoved(page, List.of(a)));
        erase.add(InkHistory.Part.paperRemoved(other, List.of(x)));
        history.record(erase);

        assertEquals(Set.of(page, other), history.undo());
        assertEquals(List.of(a.id), ids(paper));
        assertEquals(List.of(x.id), ids(otherPaper));
    }

    @Test
    public void lassoShiftUndoRestoresPointsAndRedoMovesAgain() {
        InkRenderer.InkStroke a = draw(10f);
        draw(200f);
        paper.translate(List.of(a.id), 30f, 40f);
        history.record(InkHistory.Edit.of(InkHistory.Part.paperShifted(page, List.of(a.id), 30f, 40f)));
        assertEquals(a.points.get(0).x + 30f, paper.stroke(a.id).points.get(0).x, 0.001f);

        history.undo();
        assertEquals(a.points.get(0).x, paper.stroke(a.id).points.get(0).x, 0.001f);
        assertEquals(a.points.get(0).y, paper.stroke(a.id).points.get(0).y, 0.001f);
        history.redo();
        assertEquals(a.points.get(0).y + 40f, paper.stroke(a.id).points.get(0).y, 0.001f);
    }

    @Test
    public void editsApplyByIdAfterOtherChanges() {
        InkRenderer.InkStroke a = draw(10f);
        draw(20f);
        paper.deleteIds(List.of(a.id));
        assertEquals(Set.of(page), history.undo());
        assertTrue("undoing b's stroke still removes b", paper.strokes().isEmpty());
        history.redo();
        assertEquals(1, paper.strokes().size());
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
        NotebookPaper otherPaper = NotebookPaper.empty(1000);
        Board other = paperBoard(otherPaper);
        InkRenderer.InkStroke x = TestStrokes.stroke(50f, 50f);
        otherPaper.appendStroke(x);
        history.record(InkHistory.Edit.of(InkHistory.Part.paperAdded(other, x)));
        history.retainPages(Set.of(page.id));
        assertEquals(1, history.undoSize());
        history.clear();
        assertFalse(history.canUndo());
    }

    private InkRenderer.InkStroke draw(float x) {
        InkRenderer.InkStroke stroke = TestStrokes.stroke(x, x);
        paper.appendStroke(stroke);
        history.record(InkHistory.Edit.of(InkHistory.Part.paperAdded(page, stroke)));
        return stroke;
    }
}
