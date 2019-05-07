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

import java.awt.event.ActionEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import org.openide.util.lookup.ServiceProvider;

import com.raelity.jvi.ViFPOS;
import com.raelity.jvi.ViInitialization;
import com.raelity.jvi.ViMark;
import com.raelity.jvi.ViOutputStream;
import com.raelity.jvi.ViTextView;
import com.raelity.jvi.ViTextView.MARKOP;
import com.raelity.jvi.core.ColonCommands.AbstractColonAction;
import com.raelity.jvi.core.ColonCommands.ColonEvent;
import com.raelity.jvi.core.lib.CcFlag;
import com.raelity.jvi.core.lib.PreferencesChangeMonitor;
import com.raelity.jvi.manager.ViManager;
import com.raelity.text.TextUtil.MySegment;

import static com.raelity.jvi.ViTextView.MARKOP.NEXT;
import static com.raelity.jvi.ViTextView.MARKOP.PREV;
import static com.raelity.jvi.ViTextView.MARKOP.TOGGLE;
import static com.raelity.jvi.core.Util.*;
import static com.raelity.jvi.core.lib.Constants.*;
import static com.raelity.jvi.core.lib.Messages.*;

/**
 * Keep track of vi marks.
 */
class MarkOps
{
    private static final Logger LOG = Logger.getLogger(MarkOps.class.getName());

    private static final String PREF_MARKS = "marks";
    private static final String PREF_FILEMARKS = "filemarks";

    private static Filemark namedfm[] = new Filemark[26];

    private static List<String> oldPersistedBufferMarks = new ArrayList<String>();

    /** This constant indicates mark is in other file. */
    final static FPOS otherFile = new FPOS();

    private static PreferencesChangeMonitor marksImportCheck;
    private static PreferencesChangeMonitor filemarksImportCheck;

    private MarkOps()
    {
    }

    public static class Init implements ViInitialization
    {
        @Override
        public void init()
        {
            MarkOps.init();
        }
    }

    private static void init() {
        ColonCommands.register("marks", "marks", new DoMarks(), null);
        ColonCommands.register("delm", "delmarks", new ExDelmarks(), null);

        PropertyChangeListener pcl = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                String pname = evt.getPropertyName();
                if(pname.equals(ViManager.P_OPEN_WIN)) {
                    openWin((ViTextView)evt.getNewValue());
                } else if(pname.equals(ViManager.P_CLOSE_WIN)) {
                    closeWin((ViTextView)evt.getOldValue());
                } else if(pname.equals(ViManager.P_OPEN_BUF)) {
                    BufferMarksPersist.restore((Buffer)evt.getNewValue());
                } else if(pname.equals(ViManager.P_CLOSE_BUF)) {
                    if(!marksImportCheck.isChange()) {
                        marksImportCheck.setFreeze(true);
                        try {
                            BufferMarksPersist.persist((Buffer)evt.getOldValue());
                        } finally {
                            marksImportCheck.setFreeze(false);
                        }
                    } else {
                        LOG.info("jVi marks imported (buffer close)");
                    }
                } else if(pname.equals(ViManager.P_BOOT)) {
                    BufferMarksPersist.read_viminfo();
                    read_viminfo_filemarks();
                    startImportCheck();
                } else if(pname.equals(ViManager.P_SHUTDOWN)) {
                    filemarksImportCheck.stopAll();
                    marksImportCheck.stopAll();

                    if(!filemarksImportCheck.isChange()) {
                        write_viminfo_filemarks();
                    } else {
                        LOG.info("jVi filemarks imported");
                    }
                    if(!marksImportCheck.isChange()) {
                        BufferMarksPersist.write_viminfo();
                    } else {
                        LOG.info("jVi marks imported");
                    }
                }
            }
        };
        ViManager.addPropertyChangeListener(ViManager.P_BOOT, pcl);
        ViManager.addPropertyChangeListener(ViManager.P_SHUTDOWN, pcl);
        ViManager.addPropertyChangeListener(ViManager.P_OPEN_WIN, pcl);
        ViManager.addPropertyChangeListener(ViManager.P_CLOSE_WIN, pcl);
        ViManager.addPropertyChangeListener(ViManager.P_OPEN_BUF, pcl);
        ViManager.addPropertyChangeListener(ViManager.P_CLOSE_BUF, pcl);

        // if(ViManager.isDebugAtHome())
        //     LOG.setLevel(Level.FINE);
    }

    private static void startImportCheck()
    {
        marksImportCheck = PreferencesChangeMonitor.getMonitor(
                ViManager.getFactory().getPreferences(), PREF_MARKS);
        filemarksImportCheck = PreferencesChangeMonitor.getMonitor(
                ViManager.getFactory().getPreferences(), PREF_FILEMARKS);
    }

    /** Set the indicated mark to the current cursor position;
     * if anonymous mark characters, then handle that.
     * <b>NEEDSWORK:</b><ul>
     * <li>Only handle lower case marks.
     * </ul>
     */
    static int setmark(char c, int count)
    {
        {
            // Handle anonymous mark operations here

            MARKOP op = null;
            switch (c) {
                case '.': op = TOGGLE; break;
                case '<': op = PREV;   break; // Nothing to do with visual mode
                case '>': op = NEXT;   break; // Nothing to do with visual mode
            }
            if (op != null) {
                if (op != TOGGLE)
                    setpcmark();
                G.curwin.anonymousMark(op, count);

                return OK;
            }
        }

        if (c == '\'' || c == '`') {
            setpcmark();
            /* keep it even when the cursor doesn't move */
            G.curwin.w_prev_pcmark.setMark(G.curwin.w_pcmark);
            return OK;
        }

        if (Util.islower(c)) {
            int i;
            i = c - 'a';
            G.curbuf.b_namedm[i].setMark(G.curwin.w_cursor);
            return OK;
        }

        if (Util.isupper(c)) {	// NEEDSWORK: upper case marks
            if(G.curwin.getAppView().isNomad()) {
                Msg.emsg("Can not 'mark' a nomadic editor");
                return FAIL;
            }
            int i = c - 'A';
            ViMark mark = G.curbuf.createMark(null);
            mark.setMark(G.curwin.w_cursor);
            namedfm[i] = new Filemark(mark, G.curwin);
            return OK;
        }
        return FAIL;
    }

    private static boolean JUMPLIST_ROTATE = false;
    private static final int JUMPLISTSIZE = 50;

    static void setpcmark()
    {
        setpcmark(G.curwin.w_cursor);
    }

    static void setpcmark(ViFPOS fpos)
    {
        setpcmark(G.curwin, fpos);
    }

    static void setpcmark(ViTextView tv, ViFPOS fpos)
    {
        if (G.global_busy) {
            return;
        }

        TextView win = (TextView)tv;

        win.w_prev_pcmark.setMark(win.w_pcmark);
        win.w_pcmark.setMark(fpos);

        //
        // If last used entry is not at the top, put it at the top by rotating
        // the stack until it is (the newer entries will be at the bottom).
        // Keep one entry (the last used one) at the top.
        //
        // NOTE: the jdk1.6 Deque would be nice for this.
        //
        if(JUMPLIST_ROTATE) {
            if (win.w_jumplistidx < win.w_jumplist.size()) {
                ++win.w_jumplistidx;
            }
            while (win.w_jumplistidx < win.w_jumplist.size()) {
                // rotate. take the one at the end and put it at the beginning
                ViMark tempmark = win.w_jumplist
                                .remove(win.w_jumplist.size() - 1);
                win.w_jumplist.add(0, tempmark);
                ++win.w_jumplistidx;
            }
        }

        // Put the pcmark at the end of the list.
        // (switch around the order of things from vim, since we're working
        // with a more flexible data structure)
        win.w_jumplist.add((ViMark)win.w_pcmark.copy());
        if (win.w_jumplist.size() > JUMPLISTSIZE) {
            win.w_jumplist.remove(0);
        }
        // The idx points past the end
        win.w_jumplistidx = win.w_jumplist.size();
    }

    /**
     * checkpcmark()
     *             To change context, call setpcmark(), then move the current
     *		   position to where ever, then call checkpcmark().  This
     *		   ensures that the previous context will only be changed if
     *		   the cursor moved to a different line. -- webb.
     *		   If pcmark was deleted (with "dG") the previous mark is
     *		   restored.
     */
    static void checkpcmark()
    {
        if (check_mark(G.curwin.w_prev_pcmark, false) == OK
                && (G.curwin.w_pcmark.equals(G.curwin.w_cursor)
                    || check_mark(G.curwin.w_pcmark, false) == FAIL)) {
            G.curwin.w_pcmark.setMark(G.curwin.w_prev_pcmark);
            G.curwin.w_prev_pcmark.invalidate();
        }
    }

    /**
     * move "count" positions in the jump list (count may be negative)
     */
    static ViMark movemark(int count)
    {
        cleanup_jumplist();
        // NOTE: return otherFile if...
        if(G.curwin.w_jumplist.isEmpty())     // nothing to jump to
            return null;
        for(;;) {
            if(G.curwin.w_jumplistidx + count < 0
                    || G.curwin.w_jumplistidx + count
                                >= G.curwin.w_jumplist.size())
                return null;
            if(G.curwin.w_jumplistidx == G.curwin.w_jumplist.size()) {
                MarkOps.setpcmark();
                G.curwin.w_jumplistidx--;
                if(G.curwin.w_jumplistidx + count < 0)
                    return null;
            }

            G.curwin.w_jumplistidx += count;

            ViMark mark = G.curwin.w_jumplist.get(G.curwin.w_jumplistidx);
            if(false /*mark.isSomeOtherFile()*/) {
                // ...
                return null; // return otherFile;
            }
            return mark;
        }
    }

    /*
     * getmark(c) - find mark for char 'c'
     *
     * Return pointer to FPOS if found (caller needs to check lnum!)
     *	  NULL if there is no mark called 'c'.
     *	  -1 if mark is in other file (only if changefile is TRUE)
     */
    static ViMark getmark(char c, boolean changefile)
    {
        ViMark m = null;
        if (c == '\'' || c == '`') {
            // make a copy since it might change soon
            m = (ViMark) G.curwin.w_pcmark.copy();
        } else if (c == '[') {
            m = G.curbuf.b_op_start;
        } else if (c == ']') {
            m = G.curbuf.b_op_end;
        } else if (c == '<' || c == '>') {
            ViMark startp = G.curbuf.b_visual_start;
            ViMark endp = G.curbuf.b_visual_end;
            // If either mark's no good, return it and let error handling report it
            if (check_mark(startp, false) == FAIL)
                return startp;
            if (check_mark(endp, false) == FAIL)
                return endp;
            boolean isLtName = c == '<' ? true : false;
            boolean isLtValue = startp.compareTo(endp) < 0 ? true : false;
            m = isLtName == isLtValue ? startp : endp;
            /*
             * For Visual line mode, set mark at begin or end of line
             */
            if (G.curbuf.b_visual_mode == 'V') {
                // the questions is whether column 0 or last column
                int col = 0;
                if (c == '>')
                    col = Misc.check_cursor_col(m.getLine(), MAXCOL);
                int lineoff = G.curbuf
                               .getLineStartOffsetFromOffset(m.getOffset());
                m = G.curbuf.createMark(null);
                ViFPOS fpos = G.curbuf.createFPOS(lineoff + col);
                m.setMark(fpos);
            }
        } else if (Util.islower(c)) {
            int i = c - 'a';
            m = G.curbuf.b_namedm[i];
        } else if(Util.isupper(c) /* || Util.isdigit(c) */) { // named file mark
            Filemark fm = namedfm[c - 'A'];
            if(fm != null) {
                File f = G.curbuf.getFile();
                if(changefile || f != null && f.equals(fm.getFile()))
                    // set force to true so non exist files are opened (as vim)
                    ViManager.getFactory().getFS().edit(fm.getFile(), true, fm);
                else
                    fm = null;
            }
            m = fm;
        }
        return m;
    }

    /**
     * Check a if a position from a mark is valid.
     * Give and error message and return FAIL if not.
     */
    static int check_mark(ViMark mark)
    {
        return check_mark(mark, true);
    }

    static int check_mark(ViMark mark, boolean messageOK)
    {
        //  if(!mesasgeOK) return mark.isValid() ? OK : FAIL;
        String msg = null;
        if (mark == null) {
            msg = e_umark;
        } else if(!mark.isValid()) {
            msg = e_marknotset;
        }else {
            try {
                if (mark.getLine() > G.curbuf.getLineCount()) {
                    msg = e_markinval;
                }
            } catch (ViMark.MarkOrphanException e) {
                msg = e_marknotset;
            } catch (ViMark.MarkException e) {
                msg = e_marknotset;
            }
        }
        if (msg != null) {
            if (messageOK) {
                Msg.emsg(msg);
            }
            return FAIL;
        }
        return OK;
    }

    static void do_jumps()
    {
        cleanup_jumplist();
        ViOutputStream vios = ViManager.createOutputStream(
                null, ViOutputStream.OUTPUT, "Jump List");

        vios.println(" jump line  col file/text");
        for(int i = 0; i < G.curwin.w_jumplist.size(); i++) {
            ViMark mark = G.curwin.w_jumplist.get(i);
            if(check_mark(mark, false) == OK) {
                String name;
                // name = fm_getname(&curwin->w_jumplist[i], 16);
                name = "filename";
                if(name == null)
                    continue;
                MySegment seg = G.curbuf.getLineSegment(mark.getLine());
                String lineText = seg.subSequence(0, seg.length()-1).toString();
                vios.println(String.format("%c %2d %5d %4d %s",
                      i == G.curwin.w_jumplistidx ? '>' : ' ',
                      i > G.curwin.w_jumplistidx ? i - G.curwin.w_jumplistidx
                                                 :  G.curwin.w_jumplistidx -i,
                      mark.getLine(),
                      mark.getColumn(),
                      lineText.trim()));
            }
        }
        if(G.curwin.w_jumplistidx == G.curwin.w_jumplist.size())
            vios.println(">");
        vios.close();
    }

    /**
     * When deleting lines, this may create duplicate marks in the
     * jumplist. They will be removed here for the current window.
     * <p>
     * Remove an element if an element later in the list is on the same line.
     * </p>
     */
    private static void cleanup_jumplist()
    {
        TextView win = G.curwin;

        ViMark indexedMark = null;
        if(win.w_jumplistidx < win.w_jumplist.size())
            indexedMark = win.w_jumplist.get(win.w_jumplistidx);
        boolean grabNextIndexedMark = false;

        ListIterator<ViMark> it = win.w_jumplist.listIterator();
        while(it.hasNext()) {
            ViMark mark = it.next();
            if(grabNextIndexedMark) {
                indexedMark = mark;
                grabNextIndexedMark = false;
            }
            if(!it.hasNext())
                break;
            int checkLine = mark.getLine(); // fnum = mark.getFnum();
            for(ViMark i : win.w_jumplist.subList(it.nextIndex(),
                                                  win.w_jumplist.size())) {
                if(checkLine == i.getLine() /* fnum == i.fnum */) {
                    // found the same line later in the list
                    if(mark == indexedMark) {
                        // removing indexed item, index the item following it
                        grabNextIndexedMark = true;
                    }
                    it.remove();
                    break;
                }
            }
        }

        win.w_jumplistidx = indexedMark == null
                ? win.w_jumplist.size()
                : win.w_jumplist.indexOf(indexedMark);
    }

    /**
     * print the marks
     */
    private static class DoMarks extends AbstractColonAction
    {
        @Override
        public void actionPerformed(ActionEvent ev) {
            ColonEvent cev = (ColonEvent)ev;

            String arg = null;
            if(cev.getNArg() > 0)
                arg = cev.getArgString();
            show_one_mark('\'', arg, G.curwin.w_pcmark, null, true);
            for(int i = 0; i < G.curbuf.b_namedm.length; i++)
                show_one_mark((char)(i+'a'), arg, G.curbuf.b_namedm[i],
                              null, true);
            for (int i = 0; i < namedfm.length; i++) {
                Filemark fm = namedfm[i];
                String name;
                if(fm == null) {
                    name = null;
                } else if(fm.getBuffer() != null)
                    name = fm_getname(fm, 15);
                else
                    name = fm.getFile().getPath();
                if(name != null)
                    show_one_mark((char)(i+'A'), arg, fm, name,
                            fm.getBuffer() == G.curbuf);
            }
            //show_one_mark('"', arg, G.curbuf.b_last_cursor, null, true);
            show_one_mark('[', arg, G.curbuf.b_op_start, null, true);
            show_one_mark(']', arg, G.curbuf.b_op_end, null, true);
            show_one_mark('^', arg, G.curbuf.b_last_insert, null, true);
            //show_one_mark('.', arg, G.curbuf.b_last_change, null, true);
            show_one_mark('<', arg, G.curbuf.b_visual_start, null, true);
            show_one_mark('>', arg, G.curbuf.b_visual_end, null, true);
            show_one_mark(MySegment.DONE, arg, null, null, false);
        }
    }

    /**
     * Get name of file from a filemark.
     * When it's in the current buffer, return the text at the mark.
     * Returns an allocated string.
     */
    private static String fm_getname(Filemark fm, int lead_len) {
        if(fm.getBuffer() == G.curbuf)
            return mark_line(fm, lead_len);
        return fm.getFile().getPath();
    }

    /**
     * Return the line at mark "mp".  Truncate to fit in window.
     * The returned string has been allocated.
     */
    private static String mark_line(ViFPOS mp, int lead_len) {
        String s;
        if(mp instanceof Filemark && mp.getBuffer() == null
                || mp.getOffset() > mp.getBuffer().getLength())
            return "-invalid-";

        // Forget the lead_len stuff

        s = mp.getBuffer().getLineSegment(mp.getLine()).toString();
        s = s.substring(Misc.skipwhite(s, 0));
        // get rid of the newline
        // and arbitrarily truncate to 60 chars
        final int truncateTo = 60;
        if(s.length() > truncateTo)
            s = s.substring(0, truncateTo);
        else if(s.length() > 0)
            s = s.substring(0, s.length() -1);
        return s;
    }
    private static boolean did_title;
    private static ViOutputStream svios;
    private static void show_one_mark(
            char c, String arg, ViMark p, String name, boolean current) {
        if(c == MySegment.DONE) {
            if(did_title) {
                did_title = false;
                svios.close();
                svios = null;
            } else {
                if(arg == null)
                    Msg.smsg("No marks set");
                else
                    Msg.emsg("No marks mastching \"" + arg + "\"");
            }
        }
        else if((arg == null || vim_strchr(arg, 0, c) >= 0)
                && p != null
                        // filemark always usable for this simple case
                && (p instanceof Filemark || p.isValid())) {
            if(!did_title) {
                /* Highlight title */
                String heading = "\nmark line  col file/text";
                svios = ViManager.createOutputStream(
                        null, ViOutputStream.OUTPUT, heading);
                did_title = true;
            }
            if (true /*!got_int*/)
            {
                String s = String.format(" %c %6d %4d ",
                        c, p.getLine(), p.getColumn());
                if (name == null && current)
                {
                    name = mark_line(p, 15);
                }
                if (name != null)
                {
                    s += name;
                }
                svios.println(s);
            }
        }
    }

    /**
     * clrallmarks() - clear all marks in the buffer 'buf'
     *
     * Used mainly when trashing the entire buffer during ":e" type commands
     */
    static void clrallmarks(Buffer buf) {
        for (int i = 0; i < buf.b_namedm.length; i++) {
            buf.b_namedm[i].invalidate();
        }
        buf.b_op_start.invalidate();		/* start/end op mark cleared */
        buf.b_op_end.invalidate();
        // buf.b_last_cursor.lnum = 1;	/* '" mark cleared */
        // buf.b_last_cursor.col = 0;
        buf.b_last_insert.invalidate();	/* '^ mark cleared */
        // buf.b_last_change.lnum = 0;	/* '. mark cleared */
    // #ifdef FEAT_JUMPLIST
    //     buf->b_changelistlen = 0;
    // #endif
    }

    private static class ExDelmarks extends AbstractColonAction
    {
        @Override public EnumSet<CcFlag> getFlags() {
            return EnumSet.of(CcFlag.BANG);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            ex_delmarks((ColonEvent)e);
        }
    }

    /**
     * ":delmarks[!] [marks]"
     */
    private static void ex_delmarks(ColonEvent cev) {

        if (cev.getNArg() == 0 && cev.isBang())
            // clear all marks
            clrallmarks(G.curbuf);
        else if (cev.isBang())
            Msg.emsg(e_invarg);
        else if (cev.getNArg() == 0)
            Msg.emsg(e_argreq);
        else
        {
            // clear specified marks only
            String arg = cev.getArgString();
            for (int p = 0; p < arg.length(); p++) {
                boolean lower = Util.islower(arg.charAt(p));
                boolean digit = Util.isdigit(arg.charAt(p));
                if (lower || digit || Util.isupper(arg.charAt(p)))
                {
                    char from = 0, to = 0;
                    if (p + 1 < arg.length() && arg.charAt(p+1) == '-')
                    {
                        // clear range of marks
                        from = arg.charAt(p);
                        if(p + 2 < arg.length())
                            to = arg.charAt(p + 2);
                        if (!(lower ? Util.islower(to)
                                    : (digit ? Util.isdigit(to)
                                        : Util.isupper(to)))
                                || to < from)
                        {
                            Msg.emsg(e_invarg2 + arg.substring(p));
                            return;
                        }
                        p += 2;
                    }
                    else
                        // clear one lower case mark
                        from = to = arg.charAt(p);

                    for (int i = from; i <= to; ++i)
                    {
                        if (lower)
                            G.curbuf.b_namedm[i - 'a'].invalidate();
                        else
                        {
                            int n;
                            //if (digit)
                            //    n = i - '0' + NMARKS;
                            //else
                                n = i - 'A';
                            namedfm[n] = null;
                        }
                    }
                }
                else
                    switch (arg.charAt(p))
                    {
                        //case '"': G.curbuf.b_last_cursor.invalidate(); break;
                        case '^': G.curbuf.b_last_insert.invalidate(); break;
                        //case '.': G.curbuf.b_last_change.invalidate(); break;
                        case '[': G.curbuf.b_op_start.invalidate(); break;
                        case ']': G.curbuf.b_op_end.invalidate(); break;
                        case '<': G.curbuf.b_visual_start.invalidate(); break;
                        case '>': G.curbuf.b_visual_end.invalidate(); break;
                        case ' ': break;
                        default:  Msg.emsg(e_invarg2 + arg.substring(p));
                                  return;
                    }
            }
        }
    }

    private static void openWin(ViTextView tv) {
        File f = tv.getBuffer().getFile();
        // Check if any filemarks match the file and/or window being opened.
        // Create a real mark for the file.
        for (Filemark fm : namedfm) {
            if (fm != null) {
                fm.startup(f, tv);
            }
        }
    }

    private static void closeWin(ViTextView tv) {
        // capture line/col type info for a filemark
        for (Filemark fm : namedfm) {
            if (fm != null) {
                fm.shutdown(tv);
            }
        }
    }

    private static String markName(int i) {
        return String.valueOf((char)('A'+i));
    }

    static final String FNAME = "fName";
    //static final String BUFTAG = "buftag";
    static final String BUF = "buf";
    static final String OFFSET = "offset";
    static final String LINE = "line";
    static final String COL = "col";

    private static void read_viminfo_filemarks()
    {
        Preferences prefs = ViManager.getFactory()
                .getPreferences().node(PREF_FILEMARKS);
        for (int i = 0; i < namedfm.length; i++) {
            Filemark fm = Filemark.read_viminfo_filemark(prefs, markName(i));
            if(fm != null)
                namedfm[i] = fm;
        }
    }

    private static void write_viminfo_filemarks()
    {
        Preferences prefs = ViManager.getFactory()
                .getPreferences().node(PREF_FILEMARKS);
        for (int i = 0; i < namedfm.length; i++) {
            Filemark.write_viminfo_filemark(prefs, markName(i), namedfm[i]);
        }
        try {
            prefs.flush();
        } catch (BackingStoreException ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
    }

    /**
     * The named marks, a-z plus a few, for a buffer are persisted between
     * sessions. There is an option for how many buffers to perist, but in
     * all cases any buffers open in a session are persisted, so there may
     * be more than specified by the option. When the number of buffers
     * in a session is less that the option, then marks from previous sessions
     * are persisted in an MRU fashion.
     */
    private static class BufferMarksPersist {
        // NEEDSWORK: BufferMarksPersist easier using google collections
        // Data structure, on/off disk, notes
        // The pref node for a buffer is an arbitrary name starting with "BUF"
        // and the MRU index is a property of that node.

        private static final String INDEX = "index";

        /** All the BufferMarks being tracked. Key'd by file name. */
        static Map<String, BufferMarks> all = new HashMap<String, BufferMarks>();
        /** The BufferMarks read during startup in MRU order */
        static List<String> prev = new LinkedList<String>();
        /** The BufferMarks persisted from this session in MRU order */
        static List<String> next = new LinkedList<String>();
        static List<String> cleanup = new ArrayList<String>();

        static Preferences prefs
                = ViManager.getFactory().getPreferences().node(PREF_MARKS);

        private BufferMarksPersist()
        {
        }

        static Random random = new Random();

        static class MarkInfo {
            int offset, line, col;

            MarkInfo(int offset, int line, int col) {
                this.offset = offset;
                this.line = line;
                this.col = col;
            }
        }

        /** info about each buffer with persisted marks */
        static class BufferMarks {
            /** file name */
            private String name;
            /** index, starting at 1, for MRU list. 0 means none assigned */
            private int index;
            /** the random name of the persisted set of marks */
            private String tag;

            public BufferMarks(String tag, String name, int index) {
                this.tag = tag;
                this.name = name;
                this.index = index;
            }

            public BufferMarks(String tag, String name) {
                this(tag, name, 0);
            }

            public String getName() {
                return name;
            }

            public void setIndex(int index) {
                this.index = index;
            }

            public int getIndex() {
                return index;
            }

            public String getBufTag() {
                return tag;
            }
        }

        /** persist the marks for the buffer */
        static void persist(Buffer buf)
        {
            if(buf.getFile() == null)
                return;
            LOG.log(Level.FINE, "persist {0}", buf.getFile().getAbsolutePath());
            try {
                String name = buf.getFile().getAbsolutePath();
                BufferMarks bm;
                hasMarks: {
                    for(char c = 'a'; c <= 'z'; c++) {
                        if(buf.getMark(c).isValid())
                            break hasMarks;
                    }
                    // no marks to persist
                    bm = all.get(name);
                    if(bm != null) {
                        Preferences bufData = prefs.node(bm.getBufTag());
                        bufData.removeNode();
                    }
                    return;
                } // hasMarks
                bm = all.get(name);
                if(bm == null) {
                    String bt = createBufTag();
                    bm = new BufferMarks(bt, name);
                    all.put(name, bm);
                }
                // make this buf the most recently used
                next.remove(name);
                next.add(0, name);
                prev.remove(name); // shouldn't be in prev list anymore
                writeBufferMarks(bm, buf);
            } catch(BackingStoreException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
            try {
                prefs.flush();
            } catch(BackingStoreException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        }

        /** put the previously saved marks into the buffer */
        static void restore(Buffer buf)
        {
            if(buf.getFile() == null)
                return;
            try {
                String name = buf.getFile().getAbsolutePath();
                BufferMarks bm = all.get(name);
                LOG.log(Level.FINE, "restore {0}{1}", new Object[]{bm == null
                        ? "NOT " : "", buf.getFile().getAbsolutePath()});
                if (bm == null) {
                    return;
                }
                Preferences bufData = prefs.node(bm.getBufTag());
                String[] marks = bufData.childrenNames();
                LOG.log(Level.FINER, "restoring {0} marks from {1}",
                        new Object[]{marks.length, bufData.absolutePath()});
                for (String mName : marks) {
                    MarkInfo mi = readMark(bufData.node(mName));
                    if (mi == null) {
                        // following was warning, but it fires too much
                        // wonder why...., but its igored.
                        LOG.config(String.format("restore: "
                                + "bad mark: %s, name: %s", mName, name));
                        continue;
                    }
                    char c = mName.charAt(0);
                    if (!Util.islower(c)) {
                        continue;
                    }
                    // NEEDSWORK: use line/col for persisted marks?
                    ViFPOS fpos = buf.createFPOS(mi.offset);
                    buf.getMark(c).setMark(fpos);
                }
            } catch (BackingStoreException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        }

        /** read all the buffer mark stuff */
        static void read_viminfo()
        {
            if(prev.size() > 0)
                return; // only do it once
            // If all is correct, then the indexes for the bm are in the
            // range 1-n. If not then MRU info is lost.
            // Simply toss out any bufs that cause issues.
            try {
                String[] bufTags = prefs.childrenNames(); // existing bufs
                Set<String> names = new HashSet<String>();
                // following holds bufs in MRU order
                BufferMarks[] bms = new BufferMarks[bufTags.length];
                LOG.log(Level.FINER, "persisted file count: {0}",
                        bufTags.length);
                for (String bt : bufTags) {
                    if (!bt.startsWith(BUF)) {
                        LOG.log(Level.WARNING, "read_viminfo: "
                                + "invalid buffer tag ''{0}''", bt);
                        cleanup.add(bt);
                        continue;
                    }
                    Preferences bufData = prefs.node(bt);
                    String name = bufData.get(FNAME, null);
                    int index = bufData.getInt(INDEX, -1);
                    if (name == null || !names.add(name)) {
                        bufData.removeNode();
                        LOG.log(Level.WARNING, "read_viminfo: "
                                + "ignoring duplicate ''{0}''", name);
                        cleanup.add(bt);
                        continue;
                    }
                    LOG.log(Level.FINER, "Node: {0} name: {1} index: {2}",
                            new Object[]{bt, name, index});
                    if (index <= 0 || index > bms.length) {
                        LOG.warning(String.format("read_viminfo: "
                                + "(expect 1-%d) bad index: %d, name: %s",
                                bms.length, index, name));
                        cleanup.add(bt);
                        continue;
                    }
                    if (bms[index - 1] != null) {
                        LOG.warning(String.format("read_viminfo: "
                                + "duplicate index: %d, name: %s", index, name));
                        cleanup.add(bt);
                        continue;
                    }
                    BufferMarks bm = new BufferMarks(bt, name, index);
                    bms[index - 1] = bm;
                    all.put(name, bm);
                }
                for (BufferMarks bm : bms) {
                    if (bm == null) {
                        continue;
                    }
                    prev.add(bm.getName());
                }
            } catch (BackingStoreException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        }

        /**
         * Update all the indexes for any buffers that have been persisted.
         * First persist buffers that are still be open.
         */
        static void write_viminfo()
        {
            try {
                LOG.fine("write_viminfo entry");
                for (Buffer buf : ViManager.getFactory().getBufferSet()) {
                    persist(buf);
                }
                int max = G.viminfoMaxBuf;
                int index = 1;
                // first handle all buffers that were open this session.
                LOG.log(Level.FINE, "write_viminfo next count {0}", next.size());
                for (String name : next) {
                    writeIndex(name, index);
                    index++;
                }
                // now put out buffers from previous sessions
                LOG.log(Level.FINE, "write_viminfo prev count {0}", prev.size());
                for (String name : prev) {
                    // 2nd arg of 0 will cause removal
                    writeIndex(name, index <= max ? index : 0);
                    index++;
                }
                for (String x : cleanup) {
                    Preferences p = prefs.node(x);
                    p.removeNode();
                    LOG.log(Level.FINE, "cleanup {0}", x);
                }

                prefs.flush();
            } catch(BackingStoreException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
        }

        // NOTE: index <= 0 means remove the associated data.
        private static void writeIndex(String name, int index)
                throws BackingStoreException
        {
                LOG.log(Level.FINER, "write next {0} index {1}",
                        new Object[]{name, index});
                BufferMarks bm = all.get(name);
                if(bm == null) {
                    LOG.warning(String.format(
                            "writeIndex: "
                            + "prev name: %s but no Buffermarks", index, name));
                } else {
                    Preferences bufData = prefs.node(bm.getBufTag());
                    if(index > 0) {
                        bufData.putInt(INDEX, index);
                    } else {
                        bufData.removeNode();
                    }
                }
        }

        private static MarkInfo readMark(Preferences p)
        {
            int o, l, c;
            o = p.getInt(OFFSET, -1);
            l = p.getInt(LINE, -1);
            c = p.getInt(COL, -1);
            LOG.log(Level.FINER, "{3}: offset: {0} line: {1} col: {2}",
                    new Object[]{o, l, c, p.name()});
            if(o < 0 || l < 0 || c < 0) {
                return null;
            }

            return new MarkInfo(o, l, c);
        }

        private static void writeMark(Preferences p, ViMark m) {
            if(!m.isValid())
                return;
            p.putInt(OFFSET, m.getOffset());
            p.putInt(LINE, m.getLine());
            p.putInt(COL, m.getColumn());
        }

        private static void writeBufferMarks(BufferMarks bm, Buffer buf)
                throws BackingStoreException
        {
            String bt = bm.getBufTag();
            // clear out any existing node
            if(prefs.nodeExists(bt))
                prefs.node(bt).removeNode();
            Preferences bufData = prefs.node(bt);
            bufData.put(FNAME, bm.getName());
            bufData.putInt(INDEX, bm.getIndex());
            for(char c = 'a'; c <= 'z'; c++) {
                ViMark m = buf.getMark(c);
                writeMark(bufData.node(String.valueOf(c)), m);
            }
        }

        /** Create a buf name that does not currently exist. */
        private static String createBufTag() {

            // So I guess 400 is the max persistable files
            // assuming tryId does not repeat (which is not the case)
            for(int i = 0; i < 400; i++) {
                int tryId = random.nextInt(1000000);
                String tag = BUF + tryId;
                try {
                    if (!prefs.nodeExists(tag)) {
                        return tag;
                    }
                } catch (BackingStoreException ex) {
                    LOG.log(Level.SEVERE, null, ex);
                    break;
                }
            }

            return null;
        }
    }

}

// vi:set sw=4:
