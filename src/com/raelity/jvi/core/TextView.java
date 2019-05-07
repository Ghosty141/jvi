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
package com.raelity.jvi.core;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;

import org.openide.util.lookup.ServiceProvider;

import com.raelity.jvi.ViFPOS;
import com.raelity.jvi.ViInitialization;
import com.raelity.jvi.ViMark;
import com.raelity.jvi.ViTextView;
import com.raelity.jvi.manager.Scheduler;
import com.raelity.jvi.manager.ViManager;

import static com.raelity.jvi.core.lib.Constants.*;

/**
 * This represents the core functionality of a vim window.
 * <p>
 * Vim references values in a structure, but we need to present a method
 * interface, so the vim code is not preserved as we'd like.
 * </p>
 */
public abstract class TextView implements ViTextView
{
    private static final int MagicMoveLineLimit = 15;

    public static class Init implements ViInitialization
    {
        @Override
        public void init()
        {
            TextView.init();
        }
    }
    private static void init() {
        PropertyChangeListener pcl = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                String pname = evt.getPropertyName();
                if(pname.equals(ViManager.P_OPEN_WIN)) {
                    if(checkOpen != null
                            && checkOpen.fname
                                .equals(G.curbuf.getDisplayFileName())) {
                        G.curwin.w_cursor.set(checkOpen.offset);
                    }
                } else if(pname.equals(ViManager.P_SWITCH_TO_WIN)) {
                    // There's only one chance
                    checkOpen = null;
                }
            }
        };
        ViManager.addPropertyChangeListener(ViManager.P_OPEN_WIN, pcl);
        ViManager.addPropertyChangeListener(ViManager.P_SWITCH_TO_WIN, pcl);
    }
    private static class ExpectedNewActivation {
        private String fname;
        private int offset;

        public ExpectedNewActivation(String fname, int offset)
        {
            this.fname = fname;
            this.offset = offset;
        }

    }
    private static ExpectedNewActivation checkOpen;
    /**
     * Use this to signal that the next activation should be for a
     * new text view. Sets the line number for the newly opened window.
     * If next activation doesn't meet the conditions, then nothing happens.
     */
    static void setExpectedNewActivation(String fname, int offset)
    {
        checkOpen = new ExpectedNewActivation(fname, offset);
    }



    protected Buffer w_buffer;

    //
    // Declare the variables that are a basic part of the window.
    //

    /**
     * This is a magic ViFPOS. It tracks the caret positon on the screen
     * and modifying w_cursor moves the caret positon.
     */
    final public ViFPOS w_cursor;

    private boolean didFirstInit;

    /**
     * The column we'd like to be at. Used for up/down cursor motions.
     * Should not be written to directly, except in special situations,
     * use updateCurswant().
     */
    protected int w_curswant;

    /**
     * flag that w_curswant should be set based on current cursor position
     */
    protected boolean w_set_curswant;

    //
    // Mark related stuff
    //
    protected ViMark w_pcmark;
    protected ViMark w_prev_pcmark;

    // need to be public until reflection code in setColonCommand is fixed

    public boolean w_p_nu;      // NOT USED
    public boolean w_p_list;    // NOT USED

    public boolean w_p_wrap;
    public boolean w_p_lbr;

    public int w_p_scr;

    //protected final int JUMPLISTSIZE = 50;
    protected List<ViMark> w_jumplist = new LinkedList<ViMark>();
    protected int w_jumplistidx;

    public TextView()
    {
        w_set_curswant = true;
        w_cursor = createWCursor();
        viewSizeChange();
    }

    /**
     * The default implementation simply updates w_curswant.
     * An implementation might save/compute a "w_view_curswant"
     * which takes wrapping into account.
     * @param fpos cursor on line to consider, if null then w_cursor
     */
    protected void updateCurswant(ViFPOS fpos, int curswant)
    {
        w_curswant = curswant;
    }

    @Override
    public void attachBuffer( Buffer buf )
    {
        if(this.w_buffer != null)
            ViManager.dumpStack();
        this.w_buffer = buf;

        w_pcmark = buf.createMark(null);
        w_prev_pcmark = buf.createMark(null);
    }

    public void detachBuffer()
    {
        w_pcmark = null;
        w_prev_pcmark = null;
        w_buffer = null;
        w_jumplist = new LinkedList<ViMark>();
        w_jumplistidx = 0;
    }

    @Override
    public void shutdown()
    {
        if ( G.dbgEditorActivation.getBoolean() ) {
            if(w_buffer.getShare() == 1) {
                G.dbgEditorActivation.println("TV.shutdown: LAST CLOSE");
            }
        }
    }

    @Override
    public void activateOptions(ViTextView tv) {
        if(G.dbgEditorActivation.getBoolean() && getAppView().isNomad())
            G.dbgEditorActivation.println("ACTIVATING OPTIONS FOR NOMAD");
        if(!didFirstInit) {
            firstGo();
            didFirstInit = true;
        }
    }

    @Override
    public void viOptionSet(ViTextView tv, String name)
    {
        if("w_p_scr".equals(name)) {
            if(w_p_scr == 0)
                viewSizeChange(); // set to half window size
        }
    }


    /**
     * Put stuff here that should run once
     * after after construction and every things is setup (curbuf, curwin).
     * <br/>initOptions
     */
    protected void firstGo()
    {
        w_p_wrap = Options.getOption(Options.wrap).getBoolean();
        w_p_lbr = Options.getOption(Options.lineBreak).getBoolean();
        w_p_list = Options.getOption(Options.list).getBoolean();
        w_p_nu = Options.getOption(Options.number).getBoolean();
    }

    /**
     * This is invoked by a subclass to indicate that the size of the
     * view has changed.
     * Like win_new_height....
     */
    public final void viewSizeChange()
    {
        // from win_comp_scroll
        int i = (getVpLines() >> 1);
        if (i <= 0) {
            i = 1;
        }
        w_p_scr = i;
    }

    /**
     * A mouse click, or some other situation, has occured in this window.
     * Check the position so it is not on a newline (unless in input mode)
     */
    @Override
    public int validateCursorPosition(int offset)
    {
        w_set_curswant = true; // NEEDSWORK: keep this?
        if (Util.getCharAt(offset) == '\n' && (G.State & INSERT) == 0) {
            // Sitting on a newline and not in insert mode
            // back the cursor up (unless previous char is a newline)
            if (offset > 0 && Util.getCharAt(offset - 1) != '\n') {
                --offset;
            }
        }
        return offset;
    }

    /**
     * Notification that the caret has moved in the TextView.
     * Do some bookkeeping and also adjust pcmark
     * if the caret is moved by an 'external agent' (e.g. an IDE)
     * but unfortunately, the external agent detection is difficult
     * since NB updates the cursor with invokeLater so jViBusy is always false.
     *
     * @param lastDot previous dot position
     * @param dot new dot position
     * @param mark new mark position
     */
    protected void cursorMoveDetected(int lastDot, int dot, int mark)
    {
        if (G.VIsual_active && this == G.curwin)
            Normal.v_updateVisualState(this);

        // since difficult to detect magic, check dot-lastDot for quick exit
        if (!G.pcmarkTrack || Math.abs(dot - lastDot) < 3)
            return;

        int currDot = dot;
        // With updates invokeLater, limit magic loosly
        // (around changeset 1218)
        int diff = Math.abs(w_buffer.getLineNumber(currDot)
                - w_buffer.getLineNumber(lastDot));
        boolean magicMove = !ViManager.jViBusy() && !Scheduler.isMouseDown()
                && diff > MagicMoveLineLimit;

        if (magicMove && G.dbgMouse.getBoolean(Level.INFO)
                || G.dbgMouse.getBoolean(Level.FINE))
            G.dbgMouse.println("CaretMark: " + lastDot + " --> " + currDot
                    + " " + w_buffer.getDisplayFileName());
        if (magicMove) {
            // The cursor was magcally moved and jVi had nothing to
            // do with it. (probably by an IDE or some such).
            // Record the previous location so that '' works (thanks Jose).

            if (G.dbgMouse.getBoolean(Level.INFO))
                G.dbgMouse.println("caretUpdate: setPCMark");
            ViFPOS fpos = w_buffer.createFPOS(lastDot);
            MarkOps.setpcmark(this, fpos);
        }
    }

    @Override
    public String toString()
    {
        String fn = getBuffer() != null ? getBuffer().getDisplayFileName() : null;
        return "TextView{" + fn + '}';
    }
}
