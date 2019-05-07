/*
 * The contents of this file are subject to the Mozilla Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * The Original Code is jvi - vi editor clone.
 *
 * The Initial Developer of the Original Code is Ernie Rael.
 * Portions created by Ernie Rael are
 * Copyright (C) 2000 Ernie Rael.  All Rights Reserved.
 *
 * Contributor(s): Ernie Rael <err@raelity.com>
 */

package com.raelity.jvi.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.Action;
import javax.swing.JEditorPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.Caret;
import javax.swing.text.Document;
import javax.swing.text.EditorKit;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import javax.swing.text.Utilities;
import javax.swing.text.View;

import org.openide.util.lookup.ServiceProvider;

import com.raelity.jvi.ViAppView;
import com.raelity.jvi.ViBuffer;
import com.raelity.jvi.ViCaret;
import com.raelity.jvi.ViCaretStyle;
import com.raelity.jvi.ViFPOS;
import com.raelity.jvi.ViInitialization;
import com.raelity.jvi.ViMark;
import com.raelity.jvi.ViStatusDisplay;
import com.raelity.jvi.ViTextView;
import com.raelity.jvi.core.Buffer;
import com.raelity.jvi.core.ColonCommands;
import com.raelity.jvi.core.ColonCommands.ColonEvent;
import com.raelity.jvi.core.Edit;
import com.raelity.jvi.core.G;
import com.raelity.jvi.core.Misc;
import com.raelity.jvi.core.Msg;
import com.raelity.jvi.core.Options;
import com.raelity.jvi.core.TextView;
import com.raelity.jvi.core.Util;
import com.raelity.jvi.core.lib.CcFlag;
import com.raelity.jvi.lib.MutableInt;
import com.raelity.jvi.manager.Scheduler;
import com.raelity.jvi.manager.ViManager;
import com.raelity.jvi.options.DebugOption;
import com.raelity.text.TextUtil.MySegment;

import static java.lang.Math.abs;
import static java.lang.Math.round;

import static com.raelity.jvi.core.lib.Constants.*;

/**
 *  Presents a swing editor interface for use with vi. There is
 *  one of these for each JTextComponent.
 *  <p>
 *  Notice the listeners for caret changes. If the caret changes
 *  to a location that is unexpected, i.e. it came from some external
 *  source, then an externalChange message is sent to vi.
 *  </p>
 */
public class SwingTextView extends TextView
        implements ViTextView, PropertyChangeListener
{
    protected static final
            Logger LOG = Logger.getLogger(SwingTextView.class.getName());
    private static int genNum; // unique/invariant window id;

    protected int w_num;

    private LineMap lm;
    private ViewMap vm;

    protected JTextComponent editorPane;
    protected TextOps ops;
    protected TextView window;

    protected ViStatusDisplay statusDisplay;

    private final CaretListener cursorSaveListener;
    private int lastDot;
    private static int gen;

    private static boolean didInit;

    public static class Init implements ViInitialization
    {
        @Override
        public void init()
        {
            if(didInit)
                return;
            ColonCommands.register("dumpLineMap", "dumpLineMap",
                                   new DumpLineMap(),
                                   EnumSet.of(CcFlag.DBG, CcFlag.NO_ARGS));
            didInit = true;
        }
    }

    // ............

    public SwingTextView( final JTextComponent editorPane)
    {
        super();
        this.editorPane = editorPane;
        w_num = ++genNum;

        cursorSaveListener = new CaretListener() {
            @Override
            public void caretUpdate(CaretEvent ce) {
                if(!ViManager.getFactory().isEnabled())
                    return;
                cursorMoveDetected(lastDot, ce.getDot(), ce.getMark());
                lastDot = ce.getDot();
            }
        };
    }

    public void setMaps(LineMap lm, ViewMap vm)
    {
        assert this.lm == null;
        this.lm = lm;
        assert this.vm == null;
        this.vm = vm;
    }

    public LineMap getLineMap()
    {
        return this.lm;
    }

    public ViewMap getViewMap()
    {
        return this.vm;
    }

    private static class DumpLineMap extends ColonCommands.AbstractColonAction
    {
        @Override
        public void actionPerformed(ActionEvent ev)
        {
            ColonEvent cev = (ColonEvent) ev;
            SwingTextView tv = (SwingTextView)cev.getViTextView();
            ViManager.println(tv.lm.toString());
        }

    }

    //////////////////////////////////////////////////////////////////////
    //
    // Some swing specific things that an implementation may want to override
    //

    public Action[] getActions() {
        if(ViManager.isDebugAtHome() && editorPane instanceof JEditorPane) {
            List<Action> l1 = Arrays.asList(((
                    JEditorPane)editorPane).getEditorKit().getActions());
            List<Action> l2 = Arrays.asList((editorPane).getActions());
            if(!l1.equals(l2))
                LOG.log(Level.SEVERE, null, new Throwable(
                        "different actions: " + getBuffer().getDisplayFileName()));
        }

        return editorPane.getActions();
    }

    public EditorKit getEditorKit() {
        if(ViManager.isDebugAtHome() && editorPane instanceof JEditorPane) {
            EditorKit kit1 = ((JEditorPane)editorPane).getEditorKit();
            EditorKit kit2 = editorPane.getUI().getEditorKit(editorPane);
            if(!kit1.equals(kit2))
                LOG.log(Level.SEVERE, null, new Throwable(
                        "different kit" + getBuffer().getDisplayFileName()));
        }

        return editorPane.getUI().getEditorKit(editorPane);
    }


    @Override
    public ViFPOS createWCursor()
    {
        return w_cursor == null ? new WCursor() : null;
    }

    @Override
    public ViAppView getAppView()
    {
        return ViManager.getFactory().getAppView(editorPane);
    }

    private void enableCursorSave()
    {
        lastDot = editorPane.getCaret().getDot();
        editorPane.removeCaretListener(cursorSaveListener);
        editorPane.addCaretListener(cursorSaveListener);
    }

    private void disableCursorSave()
    {
        editorPane.removeCaretListener(cursorSaveListener);
    }


    @Override
    public void startup()
    {
        enableCursorSave();
    }


    @Override
    public void shutdown()
    {
        disableCursorSave();

        super.shutdown();

        shutdownMore();
        Scheduler.detached(editorPane);
        editorPane = null;
    }


    @Override
    public boolean isShutdown()
    {
        return editorPane == null;
    }


    //////////////////////////////////////////////////////////////////////
    //
    // ViOptionBag
    //


    @Override
    public void viOptionSet( ViTextView tv, String name )
    {
        super.viOptionSet(tv, name);
    }


    @Override
    public void activateOptions( ViTextView tv )
    {
        super.activateOptions(tv);
        updateHighlightSearchState();
    }

    //////////////////////////////////////////////////////////////////////
    //

    @Override
    public final SwingBuffer getBuffer()
    {
        return (SwingBuffer) w_buffer;
    }


    /**
     *  Override this class to provide a different implementations
     *  of status display.
     * @return
     */

    @Override
    public void attach()
    {
        if ( ops == null ) {
            createOps();
        }
        if ( G.dbgEditorActivation().getBoolean() ) {
            G.dbgEditorActivation().println("TV.attach: " + editorPane.hashCode());
        }
        attachMore();
    }


    @Override
    public void detach()
    {
        detachMore();

        Scheduler.detached(editorPane);
    }


    /**
     *  Create methods to invoke and interact with editor pane actions.
     *  May override for custom editor panes.
     */
    protected void createOps()
    {
        ops = new Ops(this);
    }


    @Override
    public JTextComponent getEditor()
    {
        return editorPane;
    }

    private Timer bellTimer;
    @Override
    public void bell()
    {
        if(!G.p_vb()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }

        // visual bell and time is zero means do nothing.
        // Also don't start up another bell, if one is in progress.
        if(G.p_vbt() == 0 || bellTimer != null)
            return;

        class BellAction implements ActionListener {
            final Color bg;
            final boolean wasBackgroundSet;
            public BellAction()
            {
                wasBackgroundSet = editorPane.isBackgroundSet();
                bg = editorPane.getBackground();
                editorPane.setBackground(G.p_vbc() == null
                                         ? new Color(~bg.getRGB())
                                         : G.p_vbc());
                Dimension size = editorPane.getSize();
                //editorPane.paintImmediately(0, 0, size.width, size.height);
                startVisualBell();
            }

            @Override public void actionPerformed(ActionEvent e)
            {
                bellTimer.stop();
                bellTimer = null;

                // Could check to see if the background has been changed,
                // but too paranoid? If go down that rat hole,
                // then how about if the "getBackground" isn't different
                // but the background of a parent was changed and we're
                // about to set the background back to null to inherit.
                if(isShutdown()
                        /*|| !bgFlip.equals(editorPane.getBackground())*/)
                    return;
                editorPane.setBackground(wasBackgroundSet ? bg : null);
                endVisualBell();
            }
        }

        // invert the background, in the action constructor
        bellTimer = new Timer(G.p_vbt(), new BellAction());
        bellTimer.setRepeats(false);
        // schedule timer to flip it back
        bellTimer.start();
    }

    protected void startVisualBell() {
    }

    protected void endVisualBell() {
    }

    /**
     *  @return true if the text can be changed.
     */
    @Override
    public boolean isEditable()
    {
        return editorPane.isEditable();
    }


    ////////////////////////////////////////////////////////////////////////
    //
    // Text modification methods.
    //
    // The text modifications are a bit jumbled. Some use actions and some go
    // to the buffer. Some use the cursor position and some use offsets.
    // All check isEditable.
    //
    // NEEDSWORK: consistent text modification methods.
    //            It will be difficult to clean this up paricularly because of the
    //            because of the dependency on actions, for example insertNewline
    //            must be used to get proper autoindent handling.
    //

    @Override
    public void insertNewLine()
    {
        if ( ! isEditable() ) {
            Util.beep_flush();
            return;
        }
        ops.xop(TextOps.INSERT_NEW_LINE); // NEEDSWORK: xop throws no exception
    }

    @Override
    public void insertTab()
    {
        if ( ! isEditable() ) {
            Util.beep_flush();
            return;
        }
        ops.xop(TextOps.INSERT_TAB); // NEEDSWORK: xop throws no exception
    }


    @Override
    public void replaceChar( char c, boolean advanceCursor )
    {
        if ( !isEditable() ) {
            Util.beep_flush();
            return;
        }
        ViMark pos = getBuffer().createMark(w_cursor.getOffset(),
                                            ViBuffer.BIAS.BACK);
        getBuffer().replaceChar(pos, c);
        // need to get the cursor again because the platform may have done
        // more than you expect...
        int offset = pos.getOffset();
        if ( advanceCursor ) {
            offset++;
        }
        w_cursor.set(offset); // also clears the selection if needed
    }

    @Override
    public void deletePreviousChar()
    {
        if ( !isEditable() ) {
            Util.beep_flush();
            return;
        }
        ops.xop(TextOps.DELETE_PREVIOUS_CHAR); // NEEDSWORK: xop throws no exception
    }


    /**
     *  Insert character at cursor position. For some characters
     *  special actions may be taken.
     * @param c
     */
    @Override
    public void insertChar( char c )
    {
        if ( !isEditable() ) {
            Util.beep_flush();
            return;
        }
        if ( c == '\t' ) {
            if(G.usePlatformInsertTab())
                insertTab();
            else
                insertText(w_cursor.getOffset(), "\t");
            return;
        }
        insertTypedChar(c);
    }


    /**
     *  Add a character verbatim to the window.
     * @param c
     */
    @Override
    public void insertTypedChar( char c )
    {
        if ( !isEditable() ) {
            Util.vim_beep();
            return;
        }
        ops.xop(TextOps.KEY_TYPED, String.valueOf(c)); // NEEDSWORK: xop throws no exception
    }


    //
    // NEEDSWORK: These three text modification methods take document offsets
    // unlike the rest of the modification methods which use the caret.
    // They should probably not be here. Currently they wrap some methods
    // in Buffer.
    //

    @Override
    public void replaceString( int start, int end, String s )
    {
        if ( !isEditable() ) {
            Util.beep_flush();
            return;
        }
        getBuffer().replaceString(start, end, s);
    }


    @Override
    public void deleteChar( int start, int end )
    {
        if ( !isEditable() ) {
            Util.beep_flush();
            return;
        }
        getBuffer().deleteChar(start, end);
    }


    @Override
    public void insertText( int offset, String s )
    {
        if ( !isEditable() ) {
            Util.beep_flush();
            return;
        }
        getBuffer().insertText(offset, s);
    }


    /**
     *  Create an empty line, autoindented, either before or after current line.
     *  This is simple algorithm; but it ignores guarded text issues
     *  and code folding subtlties.
     *  <p>
     *  In this example, either the cursor was on line1 and do a NL_FORWARD,
     *  or cursor on line2 and NL_BACKWARD.
     *  The cursor is shown as '|' positioned where the newLine action will
     *  open up a clean line with proper autoindent.
     *  <pre>
     *      line1|\n
     *      line2\n
     *  </pre>
     *  This has problems if line1 is guarded text (write protected), since
     *  it modifies that line. And issues with folding in that it will open
     *  the fold since a folded line is modified.
     *  </p>
     * @param op
     * @return
     */
    @Override
    public boolean openNewLine( DIR op )
    {
        if ( !isEditable() ) {
            Util.beep_flush();
            return false;
        }
        if ( op == DIR.BACKWARD && w_cursor.getLine() == 1 ) {
            // Special case if BACKWARD and at position zero of document.
            // set the caret position to 0 so that insert line on first line
            // works as well, set position just before new line of first line
            if(!Edit.canEdit(this, getBuffer(), 0))
                return false;
            w_cursor.set(0);
            insertNewLine();

            MySegment seg = getBuffer().getLineSegment(1);
            w_cursor.set(0 + Misc.coladvanceColumnIndex(MAXCOL, seg));
            return true;
        }

        int offset;
        if ( op == DIR.FORWARD ) {
            // after the current line,
            // handle current line might be a fold
            // int cmpOffset = getBuffer()
            //                .getLineEndOffsetFromOffset(w_cursor.getOffset());
            offset = getDocLineOffset(
                    getLogicalLine(w_cursor.getLine()) + 1);
        } else {
            // before the current line
            offset = getBuffer()
                    .getLineStartOffsetFromOffset(w_cursor.getOffset());
        }
        // offset is after the newline where insert happens, backup the caret.
        // After the insert newline, caret is ready for input on blank line
        offset--;
        if(!Edit.canEdit(this, getBuffer(), 0))
            return false;
        w_cursor.set(offset);
        insertNewLine();
        return true;
    }


    ///////////////////////////////////////////////////////////////////////



    @Override
    public int getCaretPosition()
    {
        // NEEDSWORK: what is using this?
        return w_cursor.getOffset();
    }


    @Override
    public int getMarkPosition()
    {
        return editorPane.getCaret().getMark();
    }


    @Override
    public void setSelection( int dot, int mark )
    {
        Caret c = editorPane.getCaret();
        c.setDot(mark);
        c.moveDot(dot);
    }

    @Override
    public boolean hasSelection() {
        return editorPane.getSelectionStart() != editorPane.getSelectionEnd();
    }


    @Override
    public void clearSelection()
    {
        w_cursor.set(editorPane.getCaretPosition());
    }


    @Override
    public void findMatch()
    {
        Util.beep_flush();
    }


    @Override
    public void jumpDefinition( String ident )
    {
        Util.beep_flush();
    }


    @Override
    public void anonymousMark( MARKOP op, int count )
    {
        Util.beep_flush();
    }


    @Override
    public void jumpList( JLOP op, int count )
    {
        Util.beep_flush();
    }


    @Override
    public void foldOperation( FOLDOP op, int start, int end, boolean isVisual )
    {
        Util.beep_flush();
    }

    @Override
    public boolean hasAnyFolding() {
        // TODO how can this be optimized? Worth it to scan folds?
        // could also pass in a line number, check up to there.

        int lastLine = getBuffer().getLineCount();
        return getLineMap().logicalLine(lastLine) != lastLine;
    }

    @Override
    public void foldOpenCursor(int line) {}


    @Override
    public void wordMatchOperation( WMOP op )
    {
        Util.beep_flush();
    }


    @Override
    public void tabOperation( TABOP op, int count )
    {
        Util.beep_flush();
    }


    @Override
    public int getVpTopLogicalLine()
    {
        int logicalLine = lm.logicalLine(getVpTopDocumentLine());
        if ( G.dbgCoordSkip().getBoolean(Level.FINEST) ) {
            G.dbgCoordSkip().println(Level.FINEST, "getVpTopLogicalLine: " + logicalLine);
        }
        return logicalLine;
    }


    @Override
    public void setVpTopLogicalLine( int logicalLine )
    {
        Point2D p;
        int docLine;
        int offset;
        docLine = lm.docLine(logicalLine);
        offset = getBuffer().getLineStartOffset(docLine);

        try {
            p = getLocation(modelToView(offset));
        } catch (BadLocationException ex) {
            LOG.log(Level.SEVERE, null, ex);
            p = new Point(0,0);
        }

        setVpTopPoint(p);
    }


    @Override
    public int getVpBottomLogicalLine()
    {
        int logicalLine = lm.logicalLine(getVpBottomDocumentLine());
        int ll01 = getLogicalLineCount();
        if(logicalLine > ll01)
            logicalLine = ll01 + 1; // past last line
        if(G.dbgCoordSkip().getBoolean(Level.FINEST)) {
            G.dbgCoordSkip().println(Level.FINEST, "getViewBottomLogicalLine: " + logicalLine);
        }
        return logicalLine; // NEEDSWORK: line past full line, see getViewBottomLine
    }


    @Override
    public int getLogicalLineCount()
    {
        int logicalLine = lm.logicalLine(getBuffer().getLineCount());
        if ( G.dbgCoordSkip().getBoolean(Level.FINEST) ) {
            G.dbgCoordSkip().println(Level.FINEST, "getLogicalLineCount: " + logicalLine);
        }
        return logicalLine;
    }


    @Override
    public int getLogicalLine(int docLine)
    {
        if(docLine > getBuffer().getLineCount()) {
            ViManager.dumpStack("line "+docLine
                                +" past "+getBuffer().getLineCount());
            docLine = getBuffer().getLineCount();
        }
        int logicalLine = lm.logicalLine(docLine);
        if ( G.dbgCoordSkip().getBoolean(Level.FINE) ) {
            G.dbgCoordSkip().println(Level.FINE, "getLogicalLine: " + logicalLine);
        }
        return logicalLine;
    }

    @Override
    public int getLogicalLineFromViewLine(int viewLine)
    {
        return vm.logicalLine(viewLine);
    }

    @Override
    public int getViewLineFromLogicalLine(int logicalLine)
    {
        return vm.viewLine(logicalLine);
    }

    @Override
    public boolean hasFolding(int docLine, MutableInt pDocFirst,
                              MutableInt pDocLast)
    {
        return getLineMap().hasFolding(docLine, pDocFirst, pDocLast);
    }

    @Override
    public boolean viewLineEdge(EDGE edge, ViFPOS fpos)
    {
        JTextComponent ep = getEditor();
        fpos.verify(getBuffer());
        boolean ok = true;
        try {
            int offset = fpos.getOffset();
            int lineOff;
            int col;
            Rectangle r;
            Point pt = null;
            if(!w_p_wrap) {
                // y position is the same for everything
                r = ep.modelToView2D(fpos.getOffset()).getBounds();
                // and put x at the viewport edge
                pt = new Point(viewport.getViewPosition().x, r.y + r.height/2);
            }
            switch (edge) {
                case LEFT:
                    if(w_p_wrap) {
                        offset = Utilities.getRowStart(ep, offset);
                    } else {
                        offset = ep.viewToModel2D(pt);
                    }
                    break;

                case RIGHT:
                    if(w_p_wrap) {
                        lineOff = Utilities.getRowStart(ep, offset);
                        offset = Utilities.getRowEnd(ep, offset);
                    } else {
                        lineOff = w_buffer.getLineStartOffsetFromOffset(offset);
                        assert pt != null; // to get rid of NP deref hint
                        pt.x += viewport.getExtentSize().width
                                        - (int)getMaxCharWidth();
                        offset = ep.viewToModel2D(pt);
                        r = ep.modelToView2D(offset).getBounds();
                        if(r.width == 0)
                            // these things usually come back as zero,
                            // I guess the offset is the position between chars
                            r.width = (int)getMaxCharWidth();
                        if(!viewport.getViewRect().contains(r)) {
                            --offset;
                        }
                    }
                    // stay out of fold
                    // NEEDSWORK: if line not on a fold, bypass; cheaper?
                    col = offset - lineOff; // col from begin of screen line
                    col = getFirstHiddenColumn(lineOff, col);
                    offset = lineOff + col;
                    break;

                case MIDDLE:
                    if(!viewLineEdge(EDGE.LEFT, fpos))
                        return false;

                    int curCol = fpos.getColumn();
                    // move half a screen width to the right
                    col = curCol + (int)((viewportExtent.width
                                                / getMaxCharWidth())/2);
                    if(col > w_buffer.getLineLength(fpos.getLine()))
                        col = w_buffer.getLineLength(fpos.getLine()) - 1;
                    if(col < 0)
                        col = 0;
                    // make col offset from current column
                    col = col - curCol;

                    int col00 = getFirstHiddenColumn(fpos.getOffset(), col);
                    if(col00 != col && col00 > 0) // into folded territory
                        --col00;
                    offset = fpos.getOffset() + col00;
                    break;

                default:
                    throw new AssertionError();
            }
            if(offset >= 0)
                fpos.set(offset);
            else
                ok = false;
        } catch (BadLocationException ex) {
            LOG.log(Level.SEVERE, null, ex);
            ok = false;
        }
        return ok;
    }

    @Override
    public void hscroll(HSCROLL op, HDIR hdir, int count)
    {
        try {
            if(w_p_wrap)
                return;

            JTextComponent ep = getEditor();
            Dimension bound = ep.getPreferredSize();

            ViFPOS fpos = w_cursor.copy();
            Rectangle cRect;
            if(HSCROLL.HALF == op) {
                count = (int)((viewportExtent.width / getMaxCharWidth())/2);
            } else if(HSCROLL.CURSOR == op) {
                cRect = ep.modelToView2D(fpos.getOffset()).getBounds();
                cRect.width = (int)getMaxCharWidth();
                if(HDIR.LEFT == hdir) {
                    count = (int)((viewportPosition.x + viewportExtent.width
                                    - cRect.getMaxX()) /getMaxCharWidth());
                } else {
                    count = (int)((cRect.getX() - viewportPosition.x)
                                        /getMaxCharWidth());
                }
            }

            // convert count to view coordinates
            count *= getMaxCharWidth();
            // calculate the move, make sure don't go past the editor's bounds
            Point pt = viewport.getViewPosition();
            if(HDIR.LEFT == hdir) {
                pt.x -= count;
                if(pt.x < 0)
                    pt.x = 0;
            } else {
                pt.x += count;
                if(pt.x + viewportExtent.width > bound.width)
                    pt.x = bound.width - viewportExtent.width;
            }

            viewport.setViewPosition(pt);
            // make sure the cursor is visible
            cRect = ep.modelToView2D(fpos.getOffset()).getBounds();
            cRect.width = (int)getMaxCharWidth();
            Rectangle vRect = viewport.getViewRect();
            if(!vRect.contains(cRect)) {
                if(HDIR.LEFT == hdir) {
                    viewLineEdge(EDGE.RIGHT, fpos);
                } else {
                    viewLineEdge(EDGE.LEFT, fpos);
                }
                w_cursor.set(fpos);
            }
        } catch(BadLocationException ex) {
            Logger.getLogger(SwingTextView.class.getName()).
                    log(Level.SEVERE, null, ex);
        }
    }

    final int roundint(double d)
    {
        long l = round(d);
        if(l > Integer.MAX_VALUE) {
            l = Integer.MAX_VALUE;
        }
        return (int)l;
    }

    //
    // c   - cursor -- fpos
    // w   - curswant -- column from line start
    // vw  - view curswant -- column from view line start
    // ls  - line start -- document offset of logical line start
    // vs  - view line start -- document offset of view line start
    // vsc - view line start column from ls, takes tabs into account
    //
    // Note: c is used to compute vls and ls.
    //
    //                                    vw = ls + w - vs
    //                                     w = vs - ls + vw
    //                                   vs ~= ls +vsc
    //                                  vsc ~= vs - ls
    // vw = w - vsc
    //  w = wv + vsc
    //

    // could run this after each updateCurswant, but only needed for gj,gk
    private int vwFromW(ViFPOS fpos, int w)
    {
        if(w == MAXCOL)
            return w;
        ViFPOS vs = fpos.copy();
        int vw = 0;
        if(viewLineEdge(EDGE.LEFT, vs)) {
            MutableInt vsc = new MutableInt();
            Misc.getvcol(w_buffer, vs, vsc, null, null);
            vw = w - vsc.getValue();
            if(LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE,
                        "vwFromW: c={0}, vsc={1}, vs={2}, w={3}, vw={4} ",
                        new Object[]{fpos, vsc.getValue(),
                                     vs.getOffset(), w, vw});
            }
            if(vw < 0) {
                LOG.log(Level.WARNING, "vwFromW: negative vw{0}", vw);
                vw = 0;
            }
        }
        return vw;
    }

    private int wFromVW(ViFPOS fpos, int vw)
    {
        if(vw == MAXCOL)
            return vw;
        ViFPOS vs = fpos.copy();
        int w = 0;
        if(viewLineEdge(EDGE.LEFT, vs)) {
            MutableInt vsc = new MutableInt();
            Misc.getvcol(w_buffer, vs, vsc, null, null);
            w = vw + vsc.getValue();
            if(LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE,
                        "wFromVW: c={0}, vsc={1}, vs={2}, w={3}, vw={4} ",
                        new Object[]{fpos, vsc.getValue(),
                                     vs.getOffset(), vw, w});
            }
        }
        return w;
    }

    @Override
    public boolean viewLineUpDown(DIR dir, int distance, ViFPOS fpos)
    {
        fpos.verify(getBuffer());
        boolean ok = true;

        int view_curswant = vwFromW(fpos, w_curswant);

        float x = roundint(view_curswant * getMaxCharWidth());
        int limit = -1;

        while(distance-- > 0) {
            int offset;
            try {
                if(dir == DIR.BACKWARD) {
                    offset = Utilities.getPositionAbove(
                            getEditor(), fpos.getOffset(), x);
                    limit = 1;
                } else { // DIR.FORWARD
                    offset = Utilities.getPositionBelow(
                            getEditor(), fpos.getOffset(), x);
                    limit = getBuffer().getLineCount();
                }
            } catch (BadLocationException ex) {
                LOG.log(Level.SEVERE, null, ex);
                offset = -1;
            }
            if(offset < 0) {
                ok = false;
                break;
            } else {
                if(offset == fpos.getOffset()) {
                    if(fpos.getLine() == limit) {
                        ok = false;
                        break;
                    } else {
                        // work around NB bug
                        int line = fpos.getLine()
                                + (dir == DIR.FORWARD ? +1 : -1);
                        fpos.set(line, 0);
                        continue;
                    }
                }
                fpos.set(offset);
            }
        }
        w_curswant = wFromVW(fpos, view_curswant);
        return ok;
    }

    @Override
    public int getDocLine(int logicalLine)
    {
        return lm.docLine(logicalLine);
    }

    @Override
    public int getDocLineOffset( int logicalLine )
    {
        int docLine = lm.docLine(logicalLine);
        if(docLine > getBuffer().getLineCount()) {
            return getBuffer().getLength() + 1; // just past EOF
        }
        return getBuffer().getLineStartOffset(docLine);
    }


    @Override
    public int getFirstHiddenColumn( int lineOffset, int colIdx )
    {
        if(!lm.isFolding())
            return colIdx;
        JTextComponent c = getEditor();
        int col = 0;
        {
            try {
                double x = modelToView(lineOffset).getX();
                for (col = 0; col <= colIdx - 1; col++) {
                    double xNext = modelToView(lineOffset + col + 1).getX();
                    if(x == xNext)
                        break; // no change, don't advance to next position
                    x = xNext;
                }
            } catch (BadLocationException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        }
        return col;
    }


    private Element getLineElement( int lnum )
    {
        return getBuffer().getLineElement(lnum);
    }


    @Override
    public void updateCursor( ViCaretStyle cursor )
    {
        if ( isShutdown() ) {
            return; // NEEDSWORK: was getting some null pointer stuff here
        }
        Caret caret = editorPane.getCaret();
        if ( caret instanceof ViCaret ) {
            ((ViCaret)caret).setCursor(cursor);
        }
    }

    //
    // NEEDSWORK: the win_* should really be somewhere else?
    // Maybe some kind of platform and/or window-manager interface?
    //

    /** Quit editing window. Can close last view.
     */
    @Override
    public void win_quit()
    {
        Msg.emsg("win_quit not implemented");
    }


    /** Split this window.
     * @param n the size of the new window.
     */
    @Override
    public void win_split( Direction dir, int n, ViAppView av)
    {
        Msg.emsg("win_split not implemented");
    }

    @Override
    public void win_move(Direction dir, int n)
    {
        Msg.emsg("win_move not implemented");
    }

    @Override
    public void win_clone()
    {
        Msg.emsg("win_clone not implemented");
    }

    @Override
    public void win_size(SIZOP op, Orientation orientation, int n)
    {
        Msg.emsg("win_size not implemented");
    }


    /** Close this window
     * @param freeBuf true if the related buffer may be freed
     */
    @Override
    public void win_close( boolean freeBuf )
    {
        Msg.emsg("win_close not implemented");
    }


    /** Close other windows
     * @param forceit true if always hide all other windows
     */
    @Override
    public void win_close_others(boolean forceit)
    {
        Msg.emsg("win_close_others not implemented");
    }

    @Override
    public ViStatusDisplay getStatusDisplay()
    {
        return statusDisplay;
    }

    public TextOps getOps()
    {
        return ops;
    }

    /**
     * Used by WCursor to decide on scrollToLine call.
     * Typically set in platform's text view constructor.
     * Test is (lineMovement &gt;= cursorLIneChangeCheck)
     * So this value is how much line movement must there be to
     * call scrollToLine. This default of 2 means that there must
     * be a movement of at least 2 lines to call scrollToLine,
     * a single line move will not invoke scrollToLine. However
     * there is an exception if ScrollOffset is more than 0.
     * <p>
     * NOTE: this should probably never be 0. That would mean that cursor
     * moves on the same line would also call scrollToLine
     * </p>
     */
    protected int cursorLineMoveCheck = 2;

    /**
     * Use set(fpos) and set(line, col) (and what calls them) to check
     * for scrolling. Use set(offset) to avoid scroll check.
     */
    protected class WCursor extends ViFPOS.abstractFPOS
    {

        @Override
        final public boolean isValid() {
            return true;
        }

        @Override
        final public int getLine()
        {
            return getBuffer().getElemCache(getOffset()).line;
        }

        @Override
        final public int getColumn()
        {
            int offset = getOffset();
            return offset - getBuffer().getElemCache(offset)
                                       .elem.getStartOffset();
        }

        @Override
        final public int getOffset()
        {
            return editorPane.getCaretPosition();
        }

        @Override
        public void set(int offset)
        {
            editorPane.setCaretPosition(offset);
        }

        @Override
        final public void set(int line, int column)
        {
            // If changing line by more than cursorLineMoveCheck,
            // make sure the jVi scroll algorithm is used.
            // So move the viewport before changing the cursor
            // position, so the cursor with be moved to a spot
            // within the viewport and shouldn't be moved by
            // any cursor update in swing/NB.
            int lineMove = Math.abs(getLine() - line);
            if(lineMove >= cursorLineMoveCheck
                    || G.p_so() > 0 && lineMove > 0) {
                G.scrollToLine(SwingTextView.this, line);
            }

            //System.err.println("setPosition("+line+","+column+")");
            Element elem = getBuffer().getLineElement(line);
            int startOffset = elem.getStartOffset();
            int endOffset = elem.getEndOffset();
            int adjustedColumn = -1;

            if (column < 0) {
                adjustedColumn = 0;
            } else if (column >= endOffset - startOffset) {
                column = endOffset - startOffset - 1;
            }

            if (adjustedColumn >= 0) {
                ViManager.dumpStack("line " + line
                        + ", column " + column
                        + ", length " + (endOffset - startOffset));
                column = adjustedColumn;
            }

            set(startOffset + column);
        }

        @Override
        final public ViFPOS copy()
        {
            return w_buffer.createFPOS(getOffset());
        }

        @Override
        final public SwingBuffer getBuffer() {
            return (SwingBuffer)w_buffer;
        }

        @Override
        public void verify(Buffer buf) {
            if(w_buffer != buf)
                throw new IllegalStateException("fpos buffer mis-match");
        }

    };

    //////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////
    //
    // Following is the historical "TextViewCache"
    //
    // It monitors the EditorPane's Viewport
    // along with other event changes.
    //
    // NEEDSWORK: There may not be a viewport, consider a single line.
    //            May want to represent the viewport with an interface,
    //            and provide a "null" viewport when no real viewport,
    //            rather than the special case code.
    // NEEDSWORK: Without a viewport, currently assuming one line.
    //

    protected void changeDocument(PropertyChangeEvent e) {
        if (cacheTrace.getBoolean()) {
            cacheTrace.println("doc switch: ");
        }
        super.detachBuffer();
        ViManager.changeBuffer(this, e.getOldValue());
    }

    private static final int FIND_AT_TOP = -1;
    private static final int FIND_AT_BOT = 1;
    enum FindLineInView { TOP, BOT };

    private static final DebugOption cacheTrace
            = (DebugOption)Options.getOption(Options.dbgCache);

    protected JViewport getViewport()
    {
        return viewport;
    }

    /** offset should be beginning of docLine */
    protected double getLineHeight(int docLine, int offset)
    {
        return getCharRect(docLine, offset).getHeight();
    }

    private Rectangle2D getCharRect(int docLine, int offset)
    {
        Rectangle2D r = new Rectangle2D.Double(0, 0, 8, 15); // arbitrary
        try {
            r = modelToView(offset);
        } catch (BadLocationException ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
        return r;
    }
    protected double getMaxCharWidth() {
        return getMaxCharBounds().getMaxX();
    }
    private Rectangle2D getMaxCharBounds() {
        Rectangle2D r = new Rectangle2D.Double(0, 0, 8, 15); // arbitrary
        Graphics g = getEditor().getGraphics();
        try {
            FontMetrics fm = g.getFontMetrics();
            //r = fm.getMaxCharBounds(g);
            r = fm.getStringBounds("W", g);
        } finally {
            g.dispose();
        }
        return r;
    }

    // private int fontHeight;
    // private Rectangle rect0;

    /**
     * Number of view lines that include the two rect.
     */
    public int countViewLines(Rectangle2D r1, Rectangle2D r2)
    {
          double yDiff = r1.getCenterY() - r2.getCenterY();
          int nLine = abs((int)round(yDiff / getFontHeight())) + 1;
          return nLine;
    }

    public double getFontHeight()
    {
        return getRect0().getHeight();
    }

    public Rectangle2D getRect0()
    {
        try {
          Rectangle2D r = modelToView(0);
            if ( r != null ) {
                return r;
            }
        } catch (BadLocationException ex) { }
        return new Rectangle2D.Double(0,0,8,15); // arbitrary
    }
    // private Rectangle2D rect0;
    // public Rectangle2D getRect0()
    // {
    //     if ( rect0 != null ) {
    //         return (Rectangle2D)rect0.clone();
    //     }
    //     try {
    //       Rectangle2D r = modelToView(0);
    //         if ( r != null ) {
    //             rect0 = r;
    //             return (Rectangle2D)rect0.clone();
    //         }
    //     } catch (BadLocationException ex) { }
    //     return new Rectangle(0,0,8,15);
    // }

    final public Point2D getPoint0()
    {
        return getLocation(getRect0());
    }

    public void translateY(Rectangle2D lrect, double adjY)
    {
        lrect.setRect(lrect.getX(), lrect.getY() + adjY,
                      lrect.getWidth(), lrect.getHeight());
    }

    // The visible part of the document, negative means not valid.
    // These values are updated whenever the viewport changes.
    private JViewport viewport;
    private Point viewportPosition;
    private Dimension viewportExtent;
    private int vpTopDocumentLine;
    private int vpBottomDocumentLine;
    private int vpTopViewLine;
    private int vpBottomViewLine;
    private int vpLines; //VIEW LINE

    @Override
    public Rectangle2D getVpLocation(ViFPOS fpos)
    {
        try {
            Rectangle2D r = modelToView(fpos.getOffset());
            Rectangle2D b = getMaxCharBounds();
            Point pView = viewport.getViewPosition();
            Dimension extView = viewport.getViewSize();
            double x = r.getX() - pView.x;
            double y = r.getY() - pView.y;
            if(x < 0)
                x = 0;
            if(x > extView.width)
                x = extView.width - b.getWidth();
            if(y < 0)
                y = 0;
            if(y > extView.height)
                y = extView.width - b.getHeight();
            r.setRect(x, y, b.getWidth(), b.getHeight());
            return r;
        } catch(BadLocationException ex) {
            return getRect0();
        }
    }

    @Override
    public int getVpTopViewLine()
    {
        if(viewport == null)
            return 1;
        return vpTopViewLine;
    }

    @Override
    public void setVpTopViewLine(int viewLine)
    {
        Rectangle2D lrect = getRect0();
        translateY(lrect, (viewLine - 1) * getFontHeight());
        Point2D p = getLocation(lrect);
        setVpTopPoint(p);
    }

    private void setVpTopPoint(Point2D p)
    {
        // NEEDSWORK: may want previous line??
        double height = getFontHeight();

        // If point is after viewport top && within one char of top
        // then just return, to avoid wiggle
        double topDiff = p.getY() - getViewport().getViewPosition().getY();
        if ( topDiff > 0 && topDiff < height ) {
            return;
        }
        Point p01 = makePointTruncY(p);
        // Only want vertical scroll, don't change x.
        p01.x = viewport.getViewPosition().x;
        // But, if x is at begining of line, the set it to zero
        if(p01.x <= getPoint0().getX())
            p01.x = 0;
        getViewport().setViewPosition(p01);
    }

    // following could be used for caching, but need doc listener
    // private int vpViewLineCount; // number of view lines in document
    @Override
    public int getVpBottomViewLine()
    {
        if(viewport == null)
            return 1;
        int l = vpBottomViewLine;
        int viewLineCount = getViewLineCount();
        if(l > viewLineCount)
            l = viewLineCount;
        return l + 1; // past the last line
    }

    // return the view line number of the last char
    protected int getViewLineCount()
    {
        int offset = getBuffer().getLength() - 1;
        if(offset < 0)
            return 1;
        Rectangle2D lrect;
        ViFPOS fpos = getBuffer().createFPOS(offset);
        return getViewLine(fpos);
    }

    /** @return the top line number */
    @Override
    public int getVpTopDocumentLine()
    {
        if(viewport == null)
            return 1;
        return vpTopDocumentLine;
    }

    /** @return the top line number */
    private int getVpBottomDocumentLine()
    {
        if(viewport == null)
            return 1;
        return vpBottomDocumentLine;
    }

    @Override
    public void setVpTopLine(int docLine)
    {
        if(viewport == null)
            return;
        if (docLine == getVpTopDocumentLine()) {
            return; // nothing to change
        }
        int offset = getBuffer().getLineStartOffset(docLine);
        Rectangle2D r;
        try {
            r = modelToView(offset);
        } catch (BadLocationException e) {
            Util.beep_flush();
            return;
        }
        setVpTopPoint(getLocation(r));
    }

    @Override
    public int getCountViewLines(int logicalLine)
    {
        // this is vim's plines function
        return vm.countViewLines(logicalLine);
    }

    @Override
    public int getViewLine(ViFPOS fpos)
    {
        return vm.viewLine(fpos);
    }

    @Override
    public int getVpBlankLines()
    {
        if(viewport == null)
            return 0;

        int n;
        n = vpLines - (vpBottomViewLine - vpTopViewLine + 1);
        return n;
    }

    /** @return number of lines on viewport */
    @Override
    public int getVpLines() // NEEDSWORK: variable font height
    {
        if(viewport == null)
            return 1;
        return vpLines;
    }

    /** standard swing requires all lines */
    @Override
    public int getRequiredVpLines()
    {
        return getVpLines();
    }

    private void fillLinePositions()
    {
        Point newViewportPosition;
        Dimension newViewportExtent;
        Rectangle r;
        int newVpLines;
        boolean topLineChange = false;

        if (viewport == null) {
            newViewportPosition = null;
            newViewportExtent = null;
        } else {
            newViewportPosition = viewport.getViewPosition();
            newViewportExtent = viewport.getExtentSize();
        }

        int newVpTopLine;
        if (newViewportPosition == null
                || newViewportExtent == null
                || (newVpTopLine = findFullDocumentLine(
                                        newViewportPosition, FIND_AT_TOP)) <= 0) {
            vpTopDocumentLine = -1;
            vpBottomDocumentLine = -1;
            newVpLines = -1;
        } else {
            if (vpTopDocumentLine != newVpTopLine) {
                topLineChange = true;
            }
            vpTopDocumentLine = newVpTopLine;
            vpTopViewLine = findFullViewLine(
                                    newViewportPosition, FindLineInView.TOP);
            Point pt = new Point(newViewportPosition); // top-left
            pt.translate(0, newViewportExtent.height - 1); // bottom-left
            vpBottomDocumentLine = findFullDocumentLine(pt, FIND_AT_BOT);
            vpBottomViewLine = findFullViewLine(pt, FindLineInView.BOT);
            //
            // Calculate number of lines on screen, some may be blank
            //
            // NEEDSWORK: WHAT TO DO????????????
            // ALLOW TO CHANGE WITH EACH SCREEN considering variable font

            // truncate intended
            newVpLines = (int)(newViewportExtent.height / getLineHeight(1, 0));
        }

        boolean sizeChange = false;
        if (newViewportExtent == null
                || !newViewportExtent.equals(viewportExtent)
                || newVpLines != vpLines) {
            sizeChange = true;
        }
        vpLines = newVpLines;
        viewportPosition = newViewportPosition;
        viewportExtent = newViewportExtent;

        // System.err.println(vpParams());

        if (sizeChange) {
            viewSizeChange();
        }
        if (sizeChange || topLineChange) {
            ViManager.viewMoveChange(this);
        }
    }
    private String vpParams()
    {
        String s;
        if(viewportExtent != null && viewportPosition != null) {
            s = String.format("vpTopDoc %d, vpBotDoc %d,"
                    + " vpTopView %d, vpBotView %d, vpLines %d\n"
                    + "        vpPos (%d,%d), vpExt (%d,%d)",
                    vpTopDocumentLine, vpBottomDocumentLine,
                    vpTopViewLine, vpBottomViewLine, vpLines,
                    viewportPosition.x, viewportPosition.y,
                    viewportExtent.width, viewportExtent.height);
        } else {
            s = "vpParams null";
        }
        return s;
    }

    public Point makePointTruncY(Point2D p)
    {
        Point p01 = new Point();
        p01.setLocation(p.getX(), (int)p.getY());
        return p01;
    }

    public Point2D getLocation(Rectangle2D r)
    {
        Point2D p = new Point2D.Double(r.getX(), r.getY());
        return p;
    }

    public Rectangle2D modelToView(int offset) throws BadLocationException
    {
        Shape s = modelToView(
                getEditor(), offset, Position.Bias.Forward);
        Rectangle2D r = s.getBounds2D();
        // (0,3,300,300).contains(3,3,0,17) because of the 0 width (jdk1.5 at least)
        // so... make sure there is some width
        if (r.getWidth() == 0) {
            r.setRect(r.getX(), r.getY(), 1, r.getHeight());
        }
        return r;
    }

    public static Shape modelToView(JTextComponent jtc, int pos)
            throws BadLocationException
    {
        return modelToView(jtc, pos, Position.Bias.Forward);
    }

    // adapted from BasicTextUI
    public static Shape modelToView(
            JTextComponent jtc, int pos, Position.Bias bias)
            throws BadLocationException
    {
	Document doc = jtc.getDocument();
	if (doc instanceof AbstractDocument) {
	    ((AbstractDocument)doc).readLock();
	}
	try {
            View rootView = jtc.getUI().getRootView(jtc);
	    Rectangle alloc = getVisibleEditorRect(jtc);
	    if (alloc != null) {
		//rootView.setSize(alloc.width, alloc.height);
		Shape s = rootView.modelToView(pos, alloc, bias);
		if (s != null) {
		  //return s.getBounds();
		  return s;
		}
	    }
	} finally {
	    if (doc instanceof AbstractDocument) {
		((AbstractDocument)doc).readUnlock();
	    }
	}
	return null;
    }
    static Position.Bias[] biasReturnBitBucket = new Position.Bias[1];
    public int viewToModel(Point2D pt)
    {
        return viewToModel(getEditor(), pt, biasReturnBitBucket);
    }
    public static int viewToModel(JTextComponent jtc, Point2D pt)
    {
        return viewToModel(jtc, pt, biasReturnBitBucket);
    }
    public static int viewToModel(JTextComponent jtc, Point2D pt,
			   Position.Bias[] biasReturn) {
	int offs = -1;
	Document doc = jtc.getDocument();
	if (doc instanceof AbstractDocument) {
	    ((AbstractDocument)doc).readLock();
	}
	try {
            View rootView = jtc.getUI().getRootView(jtc);
	    Rectangle alloc = getVisibleEditorRect(jtc);
	    if (alloc != null) {
		rootView.setSize(alloc.width, alloc.height);
		offs = rootView.viewToModel((float)pt.getX(), (float)pt.getY(),
                        alloc, biasReturn);
	    }
	} finally {
	    if (doc instanceof AbstractDocument) {
		((AbstractDocument)doc).readUnlock();
	    }
	}
        return offs;
    }
    private static Rectangle getVisibleEditorRect(JTextComponent jtc) {
	Rectangle alloc = jtc.getBounds();
	if ((alloc.width > 0) && (alloc.height > 0)) {
	    alloc.x = alloc.y = 0;
	    Insets insets = jtc.getInsets();
	    alloc.x += insets.left;
	    alloc.y += insets.top;
	    alloc.width -= insets.left + insets.right;
	    alloc.height -= insets.top + insets.bottom;
	    return alloc;
	}
	return null;
    }

    /**
     * Determine the view line number of the text that is fully displayed
     * (top or bottom not chopped off).
     * @return line number of text at point, -1 if can not be determined
     */
    private int findFullViewLine(Point pt, FindLineInView pos)
    {
        int viewLine = -1;
        try {
            Rectangle vrect = viewport.getViewRect();

            int offset = viewToModel(pt);
            if (offset < 0) {
                return -1;
            }
            if(offset >= getBuffer().getLength() && offset != 0) {
                offset = getBuffer().getLength() - 1;
            }

            // NOT USED, comment out
            //int line = getBuffer().getLineNumber(offset);
            Rectangle2D lrect = modelToView(offset);
            viewLine = countViewLines(lrect, getRect0());
            if (vrect.contains(lrect)) {
                return viewLine;
            }
            double adjY = getFontHeight(); // assume move down from top
            if(pos == FindLineInView.BOT) {
                adjY = -adjY; // move up from the bottom
            }
            translateY(lrect, adjY);
            return countViewLines(lrect, getRect0());
        } catch (BadLocationException ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
        // if here, then got some error.
        // if pos is BOT, then get the view line for the last line of the file

        return viewLine;
    }
    /**
     * Determine the document line number of the text that is fully displayed
     * (top or bottom not chopped off).
     * @return line number of text at point, -1 if can not be determined
     */
    private int findFullDocumentLine(Point pt, int dir)
    {
        // NEEDSWORK: currently docLine
        //            needs to be viewLine and/or maybe viewLineOffset
        try {
            // return findFullLineThrow(pt, dir);
            Rectangle vrect = viewport.getViewRect();

            int offset = viewToModel(pt);
            if (offset < 0) {
                return -1;
            }

            int line = getBuffer().getLineNumber(offset);
            Rectangle2D lrect = modelToView(offset);
            // make sure the line rect's x is within the viewport
            // all we care about is y
            lrect.setRect(vrect.x+1, lrect.getY(),
                          lrect.getWidth(), lrect.getHeight());
            if (vrect.contains(lrect)) {
                return line;
            }
            int oline = line;
            line -= dir; // move line away from top/bottom
            if (line < 1 || line > getBuffer().getLineCount()) {
                //System.err.println("findFullLine: line out of bounds " + line);
                return oline;
            }

            offset = getBuffer().getLineStartOffset(line);
            lrect = modelToView(offset);
            if (!vrect.contains(lrect)) {
                //System.err.println("findFullLine: adjusted line still out " + line);
            }
            return line;
        } catch (BadLocationException ex) {
            LOG.log(Level.SEVERE, null, ex);
            return -1;
        }
    }

    //
    // Track changes of interest.
    //
    // There are two types of cached things we're interested in,
    //	- data from the document, especially around the cursor
    //	  Caret movement and document changes affect this
    //
    //	- visible screen: top line, bottom line
    //	  resize, viewport affects this
    //

    private void changeFont(Font f)
    {
        //rect0 = null;

        fillLinePositions();
    }

    final private ChangeListener vpChangeListener = new ChangeListener() {
        @Override
        public void stateChanged(ChangeEvent e)
        {
            changeVp(false);
        }
    };

    /** The container for the editor has changed. */
    private void changeViewport(Component component)
    {
        if (null != viewport)
            viewport.removeChangeListener(vpChangeListener);
        viewport = (JViewport)(component instanceof JViewport
                ? component
                : SwingUtilities.getAncestorOfClass(JViewport.class, component));
        if (null != viewport)
            viewport.addChangeListener(vpChangeListener);
        changeVp(true);
    }

    /** The defining rectangle of the viewport has changed
     *  @param init true indicates that the position should be
     *  checked immediately, not potentially deferred with an invoke later.
     */
    protected void changeVp(boolean init)
    {
        //System.err.println("CHANGE VP");
        fillLinePositions();
    }

    /** This is called from the managing textview,
     * listen to things that affect the cache.
     */
    private void attachMore()
    {
        if (G.dbgEditorActivation().getBoolean()) {
            G.dbgEditorActivation().println("TVCache: attach: "
                               + (editorPane == null ? 0 : editorPane.hashCode()));
        }
        if (freezer != null) {
            freezer.stop();
            freezer = null;
        }

        if (hasListeners) {
            return;
        }

        hasListeners = true;
        editorPane.addPropertyChangeListener("font", this);
        editorPane.addPropertyChangeListener("document", this);
        editorPane.addPropertyChangeListener("ancestor", this);
        changeFont(editorPane.getFont());
        changeViewport(editorPane.getParent());
    }
    boolean hasListeners = false;

    /** Disassociate from the observed components. */
    private void detachMore()
    {
        if (G.dbgEditorActivation().getBoolean()) {
            G.dbgEditorActivation().println("TVCache: detach: "
                               + (editorPane == null ? "" : editorPane.hashCode()));
        }
        if (editorPane == null) {
            return;
        }
        freezer = new FreezeViewport(editorPane);

    //removeListeners();
    }

    private void shutdownMore()
    {
        if (freezer != null) {
            freezer.stop();
            freezer = null;
        }
        removeListeners();
    }

    private void removeListeners()
    {
        hasListeners = false;
        editorPane.removePropertyChangeListener("font", this);
        editorPane.removePropertyChangeListener("document", this);
        editorPane.removePropertyChangeListener("ancestor", this);
        changeViewport(null);
    }
    private FreezeViewport freezer;

    //
    // Listener events
    //

    // -- property change events --
    @Override
    public void propertyChange(PropertyChangeEvent e)
    {
        String p = e.getPropertyName();
        Object o = e.getNewValue();
        if ("font".equals(p)) {
            changeFont((Font) o);
        } else if ("document".equals(p)) {
            changeDocument(e); // this assert
        } else if ("ancestor".equals(p)) {
            changeViewport((Component)o);
        }
    }

    //////////////////////////////////////////////////////////////////////
    //
    // Visual Select
    //

    /**
     * Update the selection highlight.
     *
     * see PlayTextView for a simple implementation.
     */
    @Override
    public void updateVisualState() {}

    //////////////////////////////////////////////////////////////////////
    //
    // Highlight Search
    //

    /**
     * Update the highlight search.
     *
     * see PlayTextView for a simple implementation.
     */
    @Override
    public void updateHighlightSearchState() {}

} // end com.raelity.jvi.swing.SwingTextView
