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

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.CharacterIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.swing.KeyStroke;
import javax.swing.text.Keymap;

import org.openide.util.lookup.ServiceProvider;

import com.raelity.jvi.ViCaretStyle;
import com.raelity.jvi.ViCmdEntry;
import com.raelity.jvi.ViFPOS;
import com.raelity.jvi.ViInitialization;
import com.raelity.jvi.ViMark;
import com.raelity.jvi.ViOutputStream;
import com.raelity.jvi.ViTextView;
import com.raelity.jvi.ViTextView.DIR;
import com.raelity.jvi.ViXlateKey;
import com.raelity.jvi.core.lib.KeyDefs;
import com.raelity.jvi.core.lib.Messages;
import com.raelity.jvi.core.lib.NotSupportedException;
import com.raelity.jvi.core.lib.PreferencesChangeMonitor;
import com.raelity.jvi.lib.MutableBoolean;
import com.raelity.jvi.lib.MutableInt;
import com.raelity.jvi.lib.Wrap;
import com.raelity.jvi.manager.ViManager;
import com.raelity.text.TextUtil;
import com.raelity.text.TextUtil.MySegment;

import static com.raelity.jvi.core.ColonCommands.*;
import static com.raelity.jvi.core.Edit.*;
import static com.raelity.jvi.core.GetChar.*;
import static com.raelity.jvi.core.Normal.*;
import static com.raelity.jvi.core.Search.*;
import static com.raelity.jvi.core.Util.*;
import static com.raelity.jvi.core.lib.Constants.*;
import static com.raelity.jvi.core.lib.Constants.NF.*;
import static com.raelity.jvi.core.lib.KeyDefs.*;

public class Misc implements ClipboardOwner {
    private static final Logger LOG = Logger.getLogger(Misc.class.getName());
    private static final ClipboardOwner clipOwner = new Misc();
    private static final String PREF_REGISTERS = "registers";
    private static final String PREF_SEARCH = "search";
    private static final String PREF_COMMANDS = "commands";
    private static PreferencesChangeMonitor registersImportCheck;
    private static PreferencesChangeMonitor searchImportCheck;
    private static PreferencesChangeMonitor commandsImportCheck;

    private Misc() {}

    //////////////////////////////////////////////////////////////////
    //
    // "misc1.c"
    //

    public static class Init implements ViInitialization
    {
        @Override
        public void init()
        {
            Misc.init();
        }
    }

    private static void init() {
        ColonCommands.register("reg", "registers", new DoRegisters(), null);

        PropertyChangeListener pcl = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                String pname = evt.getPropertyName();
                if(pname.equals(ViManager.P_BOOT)) {
                    read_viminfo_registers();
                    read_viminfo_search();
                    read_viminfo_command();
                    startImportCheck();
                } else if(pname.equals(ViManager.P_LATE_INIT)) {
                    javaKeyMap = initJavaKeyMap();
                } else if(pname.equals(ViManager.P_SHUTDOWN)) {
                    registersImportCheck.stopAll();
                    searchImportCheck.stopAll();
                    commandsImportCheck.stopAll();

                    if(!registersImportCheck.isChange()) {
                        write_viminfo_registers();
                    } else {
                        LOG.info("jVi registers imported");
                    }
                    if(!searchImportCheck.isChange()) {
                        write_viminfo_search();
                    } else {
                        LOG.info("jVi search history imported");
                    }
                    if(!commandsImportCheck.isChange()) {
                        write_viminfo_command();
                    } else {
                        LOG.info("jVi commmand history imported");
                    }
                }
            }
        };
        ViManager.addPropertyChangeListener(ViManager.P_BOOT, pcl);
        ViManager.addPropertyChangeListener(ViManager.P_LATE_INIT, pcl);
        ViManager.addPropertyChangeListener(ViManager.P_SHUTDOWN, pcl);
    }

    private static void startImportCheck()
    {
        commandsImportCheck = PreferencesChangeMonitor.getMonitor(
                ViManager.getFactory().getPreferences(), PREF_COMMANDS);
        searchImportCheck = PreferencesChangeMonitor.getMonitor(
                ViManager.getFactory().getPreferences(), PREF_SEARCH);
        registersImportCheck = PreferencesChangeMonitor.getMonitor(
                ViManager.getFactory().getPreferences(), PREF_REGISTERS);
    }

  /**
   * count the size of the indent in the current line
   */
  static int get_indent() {
    return get_indent(G.curwin.w_cursor);
  }
  static int get_indent(ViFPOS fpos) {
      MySegment seg = G.curbuf.getLineSegment(fpos.getLine());
      return get_indent_str(seg);
  }

  /**
   * count the size of the indent in line "lnum"
   */
  static int get_indent_lnum(int lnum) {
      MySegment seg = G.curbuf.getLineSegment(lnum);
      return get_indent_str(seg);
  }

  /**
   * count the size of the indent in line "ptr"
   */
  private static int get_indent_str(MySegment seg) {
      int	    count = 0;

      int ptr = seg.offset;
      for ( ; ptr < seg.offset + seg.count; ++ptr) {
	  if (seg.array[ptr] == TAB)    // count a tab for what it is worth
	      count += G.curbuf.b_p_ts - (count % G.curbuf.b_p_ts);
	  else if (seg.array[ptr] == ' ')
	      ++count;		// count a space for one
	  else
	      break;
      }
      return count;
  }

  /**
   * Return column position of parenthesis on argument line.
   * If no paren then return -1.
   * <br>
   * At some point in the future may want a version that find
   * the paren (any specified character) before/after some given
   * column positon.
   */
  static int findParen(int lnum, int fromIndent, int dir) {
    if(lnum < 1 || lnum > G.curbuf.getLineCount()) {
      return -1;
    }
    MySegment seg = G.curbuf.getLineSegment(lnum);
    return findParen(seg, fromIndent, dir);
  }

  /**
   * @param seg
   * @param fromIndent start looking from this position
   * @param dir FORWARD or BACKWARD
   */
  static int findParen(MySegment seg, int fromIndent, int dir) {
    boolean found = false;
    int	    count = 0;
    int     prev_paren = -1;

    if(dir == BACKWARD) {
      fromIndent -= 1;
    }

    // go forward until count is at fromIndent
    int ptr = seg.offset;
    for ( ; ptr < seg.offset + seg.count; ++ptr) {
      if(count >= fromIndent) {
	break;
      }
      if (seg.array[ptr] == '(') {
	// keep track of last paren seen
	prev_paren = count;
      }
      if (seg.array[ptr] == TAB) {  // count a tab for what it is worth
	count += G.curbuf.b_p_ts - (count % G.curbuf.b_p_ts);
      } else {
	++count;
      }
    }

    // if looking backward, then we're done. prev_paren is position
    if(dir == BACKWARD) {
      return prev_paren;
    }

    // ptr = seg.offset;
    //ptr++;
    for ( ; ptr < seg.offset + seg.count; ++ptr) {
      if (seg.array[ptr] == '(') {
	found = true;
	break;
      } else if (seg.array[ptr] == TAB)    // count a tab for what it is worth
	count += G.curbuf.b_p_ts - (count % G.curbuf.b_p_ts);
      else
	++count;
    }
    return found ? count : -1;
  }

  static int findFirstNonBlank(int lnum, int fromIndent, int dir) {
    if(lnum < 1 || lnum > G.curbuf.getLineCount()) {
      return -1;
    }
    MySegment seg = G.curbuf.getLineSegment(lnum);
    return findFirstNonBlank(seg, fromIndent, dir);
  }

  /**
   * @param seg
   * @param fromIndent start looking from this position
   * @param dir FORWARD or BACKWARD
   */
  static int findFirstNonBlank(MySegment seg, int fromIndent, int dir) {
    boolean found = false;
    int	    count = 0;
    int     non_blank = -1;

    if(dir == BACKWARD) {
      fromIndent -= 1;
    }

    // go forward until count is at fromIndent
    int ptr = seg.offset;
    for ( ; ptr < seg.offset + seg.count; ++ptr) {
      if(count >= fromIndent) {
	break;
      }
      if(seg.array[ptr] != ' ' && seg.array[ptr] != TAB) {
          non_blank = count;
          break;
      }
      if (seg.array[ptr] == TAB) {  // count a tab for what it is worth
	count += G.curbuf.b_p_ts - (count % G.curbuf.b_p_ts);
      } else {
	++count;
      }
    }

    // if looking backward, then we're done.
    if(dir == BACKWARD) {
      return non_blank;
    }

    for ( ; ptr < seg.offset + seg.count; ++ptr) {
      if(seg.array[ptr] != ' ' && seg.array[ptr] != TAB) {
	found = true;
	break;
      }
      if (seg.array[ptr] == TAB)    // count a tab for what it is worth
	count += G.curbuf.b_p_ts - (count % G.curbuf.b_p_ts);
      else
	++count;
    }
    return found ? count : -1;

    /*
    if(non_blank < fromIndent)
        return -1;
    return non_blank; */
  }

  /**
   * set the indent of the current line
   * leaves the cursor on the first non-blank in the line
   */
  static void set_indent(int size, boolean del_first) {
    set_indent(size, del_first, G.curwin.w_cursor);
  }
  /** NOTE: fpos must be in curwin */
  static void set_indent(int size, boolean del_first, ViFPOS fpos) {
    int		oldstate = G.State;
    int		c;

    fpos.verify(G.curbuf);
    G.State = INSERT;		    // don't want REPLACE for State
    int col = 0;
    // NEEDSWORK: note use of curbuf. but fpos not curwin
    MySegment seg = G.curbuf.getLineSegment(fpos.getLine());
    if (del_first) {		    // delete old indent
      // vim_iswhite() is a define!
      while(vim_iswhite(seg.array[col + seg.offset])) {
	col++;
      }
      // col is char past last whitespace
    }
    StringBuilder sb = new StringBuilder();
    if (!G.curbuf.b_p_et) {	    // if 'expandtab' is set, don't use TABs
      while (size >= G.curbuf.b_p_ts) {
	// NEEDSWORK:  how did b_p_et get set, dont expand tabs for now
	sb.append(TAB);
	size -= G.curbuf.b_p_ts;
      }
    }
    while (size > 0) {
      sb.append(' ');
      --size;
    }
    int offset = G.curbuf.getLineStartOffset(fpos.getLine());
    G.curbuf.replaceString(offset, offset + col, sb.toString());
    G.State = oldstate;
  }

  /**
   * open_line: Add a new line within, below or above the current line.
   * <p>
   * VREPLACE mode not supported.
   * </p><p>
   * For insert/replace put the newline wherever the cursor is. Otherwise,
   * create an empty line either before or after the current line, according
   * to dir.
   * </p>
   *
   * @return TRUE for success, FALSE for failure
   */
  static boolean open_line(int dir, boolean redraw, boolean del_spaces,
		    int old_indent)
  {
    boolean ok = true;
    if(G.State == INSERT || G.State == REPLACE) {
      // in input/insert mode, put the new line wherever we are
      G.curwin.insertNewLine();
    } else {
      ok = G.curwin.openNewLine(dir == FORWARD
                                ? DIR.FORWARD : DIR.BACKWARD);
    }
    if(ok)
      G.did_ai = true;
    return ok;
  }

  /**
   * @param nlines number of lines to delete
   * @param dowindow if true, update the window
   * @param undow if true, prepare for undo
   */
  static void del_lines(int nlines, boolean dowindow, boolean undo) {
    if(nlines <= 0) {
      return;
    }
    final ViFPOS cursor = G.curwin.w_cursor;
    int stopline = cursor.getLine() + nlines - 1;
    int start = G.curbuf.getLineStartOffsetFromOffset(cursor.getOffset());
    int end = G.curbuf.getLineEndOffset(stopline);
    if(stopline >= G.curbuf.getLineCount()) {
      // deleting the last line is tricky
      --end; // can not delete the final new line
      if(start > 0) {
        --start; // delete the newline before the starting line to componsate
      }
    }
    G.curwin.deleteChar(start, end);
  }

  /** @return character at the position */
  static char gchar_pos(ViFPOS pos) {
    return Util.getCharAt(pos.getOffset());
  }

  /** @return character at the position */
  static char gchar_cursor() {
    ViFPOS pos = G.curwin.w_cursor;
    return gchar_pos(pos);
  }

  /**
   * Write a character at the current cursor position.
   * It is directly written into the block.
   */
  static void pchar_cursor(char c) {
    G.curwin.replaceChar(c, false);
  }

  static void pchar(ViFPOS fpos, char c) {
    int offset = fpos.getOffset();
    G.curwin.replaceString(offset, offset+1, String.valueOf(c));
    // do not change cursor position
    G.curwin.w_cursor.set(offset);
  }

  /**
   * If the file is readonly, give a warning message with the first change.
   * Don't do this for autocommands.
   * Don't use emsg(), because it flushes the macro buffer.
   * If we have undone all changes b_changed will be FALSE, but b_did_warn
   * will be TRUE.
   */
  static void change_warning(int col) {
    // Normal.do_op("change_warning");	// NEEDSWORK: implement something
  }

  /**
   * When extra == 0: Return TRUE if the cursor is before or on the first
   *		    non-blank in the line.
   * <br>
   * When extra == 1: Return TRUE if the cursor is before the first non-blank in
   *		    the line.
   */
    @SuppressWarnings("empty-statement")
  static boolean inindent(int extra) {
      int	col;

      MySegment seg = G.curbuf.getLineSegment(G.curwin.w_cursor.getLine());
      for(col = 0; vim_iswhite(seg.array[seg.offset + col]); ++col);

      if (col >= G.curwin.w_cursor.getColumn() + extra)
          return true;
      else
          return false;
  }

  public static String plural(int n) {
    return n == 1 ? "" : "s";
  }

  static void ins_char(char c) {
    ins_char(c, false);
  }
  static void ins_char(char c, boolean ctrlv) {
    int extra;
    char curChar = 0;

    if (G.State != REPLACE || (curChar = Util.getChar()) == '\n')
	extra = 1;
    else
	extra = 0;

    // A character has to be put on the replace stack if there is a
    // character that is replaced, so it can be put back when BS is used.
    if (G.State == REPLACE) {
      Edit.replace_push(NUL);
      if (extra == 0)
	Edit.replace_push(curChar);
    }

    if(extra == 0) {
      // overwrite current character, no extra chars put into line
      G.curwin.replaceChar(c, true);
    } else {
      if(ctrlv) {
        String s = mapCtrlv(c);
        // following does doc.insert
        G.curwin.insertText(G.curwin.w_cursor.getOffset(), s);
      } else
        // following goes through key typed event, but some special tab handling
        G.curwin.insertChar(c);
    }
  }

  private static String mapCtrlv(char c)
  {
    // should also do stuff like
    // change an input F1 key to "<F1>"
    // Doesn't look like you can put a '\r' into a document?
    if(c == K_TAB || c == K_S_TAB)
      c = '\t';
    else if(c == K_KENTER || c == CR)
      c = 13;  // carriage return
    else if(c == NL)
      c = 10; // newline

    return String.valueOf(c);
  }

  static int del_char(boolean fixpos) {

    // return del_chars(1, fixpos);
    // just implement delete a single character

    ViFPOS fpos = G.curwin.w_cursor;

    // Can't do anything when the cursor is on the NUL after the line.
    if(Util.getChar() == '\n') {
      return FAIL;
    }

    // NEEDSWORK: could use a deleteNextChar
    int offset = fpos.getOffset();
    G.curwin.deleteChar(offset, offset+1);
    return OK;
  }

  static int del_chars(int count, boolean fixpos) {
    final ViFPOS cursor = G.curwin.w_cursor;
    int col = cursor.getColumn();
    MySegment oldp = Util.ml_get(cursor.getLine());
    int oldlen = oldp.count -1; // exclude the newline

    // Can't do anything when the cursor is on the NUL after the line.
    if(col >= oldlen)
      return FAIL;

    int oldOffset = cursor.getOffset();

    // When count is too big, reduce it.
    int movelen = oldlen - col - count + 1; // includes trailing NUL
    if (movelen <= 1) {
      //
      // If we just took off the last character of a non-blank line, and
      // fixpos is TRUE, we don't want to end up positioned at the NUL.
      //
      if (col > 0 && fixpos)
        G.curwin.w_cursor.set(oldOffset -1); //--curwin->w_cursor.col;
      count = oldlen - col;
      //movelen = 1;
    }
    G.curwin.deleteChar(oldOffset, oldOffset + count);

    return OK;
  }

  static void msgmore(int n) {
    int pn;

    if(G.global_busy) {
      // vim has a few more conditions for skipping this
      return;
    }

    if(n > 0) { pn = n; }
    else { pn = -n; }

    if(pn >= G.p_report) {
      String msg = "" + pn + " " + (n > 0 ? "more" : "fewer")
		+ " line" + plural(pn);
      // NEEDSWORK: msgmore: conditionally append "(interrupted)" to msg

      G.curwin.getStatusDisplay().displayStatusMessage(msg);
    }
  }

  private static int	line_breakcheck_count = 0;
  /**
   * Check for CTRL-C pressed, but only once in a while.
   * Should be used instead of ui_breakcheck() for functions that check for
   * each line in the file.  Calling ui_breakcheck() each time takes too much
   * time, because it can be a system call.
   */

  static void line_breakcheck() {
    /*
	#ifndef BREAKCHECK_SKIP
	# ifdef USE_GUI	   // assume the GUI only runs on fast computers
	#  define BREAKCHECK_SKIP 200
	# else
	#  define BREAKCHECK_SKIP 32
	# endif
	#endif
     */


    if (++line_breakcheck_count == 200) {
      line_breakcheck_count = 0;
      // ui_breakcheck(); // NEEDSWORK: check for interrupt
    }
  }

  //////////////////////////////////////////////////////////////////
  //
  // "misc2.c"
  //

  /**
   * coladvance(col).
   *
   * Using cursWant as the target column, advance the cursor on
   * the line specified by argument segment.
   *
   * @return the index of where to set the cursor on the line
   */
  public static int coladvanceColumnIndex(int wcol,
					  MySegment txt,
					  MutableBoolean reached) {
    int		idx = -1;
    int		col = 0;

    // Try to advance to the specified column.
    // There are txt.count chars in the line possibly including a '\n'; and
    // idx starts at -1.
    int ptr = txt.offset;
    char c;
    while (col <= wcol
	   && idx < txt.count - 1
	   && (c = txt.array[ptr]) != '\n')
    {
      ++idx;
      /* Count a tab for what it's worth (if list mode not on) */
      col += lbr_chartabsize(c, col);
      ++ptr;
    }
    /*
     * In Insert mode it is allowed to be one char beyond the end of the line.
     * Also in Visual mode, when 'selection' is not "old".
     */
    if (((G.State & INSERT) != 0
	 	|| (G.VIsual_active && G.p_sel.charAt(0) != 'o'))
	&& col <= wcol) {
      ++idx;
    }

    // no hidden thing anymore, cursor can be inside a fold
    // idx = G.curwin.getFirstHiddenColumn(txt.docOffset, idx);

    if(reached != null) {
      // indicate if the column was reached or not
      if (col <= wcol) { reached.setValue(false); } /* Couldn't reach column */
      else {reached.setValue(true); }    /* Reached column */
    }

    if (idx < 0) { idx = 0; }
    return idx;
  }

  public static int coladvanceColumnIndex(int wcol, MySegment txt) {
    return coladvanceColumnIndex(wcol, txt, null);
  }

  public static int coladvanceColumnIndex(MySegment txt) {
    return coladvanceColumnIndex(G.curwin.w_curswant, txt, null);
  }

  public static ViFPOS coladvance(int wcol) {
    return coladvance(G.curwin.w_cursor, wcol);
  }

  public static ViFPOS coladvance(ViFPOS fpos, int wcol)
  {
    return coladvance(fpos, wcol, null);
  }

  /** advance the fpos, note if fpos is w_cursor, may move the cursor */
  public static ViFPOS coladvance(ViFPOS fpos, int wcol,
                                  MutableBoolean hitTarget)
  {
    MySegment txt = G.curbuf.getLineSegment(fpos.getLine());
    int startColumn = 0;
    // NEEDSWORK: the following assert fires when used
    //           used for block mode stuff like "I" command.
    //           Doesn't seem to assert standalone
    //assert fpos.getColumn() == 0;
    if(G.False) {
      // WRAP issue. (OR IS IT...)
      // NOTE that if fpos.col is ever not zero
      //      then the txt segment starts *before* the col
      //      and coladvance starts at a screwy place.
      startColumn = fpos.getColumn();
      if(startColumn != 0) {
        // This is needed because of line wrap,
        // it may actually be needed in general.
        // coladvanceColumnIndex start from "txt.offset"
        txt.offset += startColumn;
        txt.count -= startColumn;
      }
    }
    int idx = coladvanceColumnIndex(wcol, txt, null);
    fpos.setColumn(startColumn + idx);
    if(hitTarget != null)
      hitTarget.setValue(idx == wcol);
    return fpos;
  }

  /**
   * inc_cursor()
   *<p>
   * Increment the current window's cursor position crossing line boundaries
   * as necessary.  Return 1 when crossing a line, -1 when at end of file, 0
   * otherwise.
   * </p>
   */
  static int inc_cursor() {
    ViFPOS fpos = G.curwin.w_cursor.copy();
    int rc = inc(fpos);
    G.curwin.w_cursor.set(fpos.getOffset()); // KEEP fpos.getOffset()
    return rc;
  }

  /**
   * inc(lp)
   *<p>
   * Increment the line pointer 'lp' crossing line boundaries as necessary.
   * Return 1 when crossing a line, -1 when at end of file, 0 otherwise.
   * </p>
   */
   static int inc(ViFPOS lp) {
    int rc = incV7(lp);
    return rc != 2 ? rc : 1;
  }

  /**
   * incV7(lp)
   *<p>
   * Increment the line pointer 'lp' crossing line boundaries as necessary.
   * Return 1 when going to the next line.<br/>
   * Return 2 when moving forward onto a newline at the end of the line).<br/>
   * Return -1 when at the end of file.<br/>
   * Return 0 otherwise.
   * </p>
   */
  static int incV7(ViFPOS lp) {
    int currLine = lp.getLine();
    char c0 = Misc.gchar_pos(lp);

    if(c0 != '\n')    // still within line, move to next char (may be newline)
    {
      // #ifdef FEAT_MBYTE ... #endif

      lp.set(currLine, lp.getColumn() + 1);

      /* #ifdef FEAT_VIRTUALEDIT
	         lp->coladd = 0;
         #endif */

      return Misc.gchar_pos(lp) != '\n' ? 0 : 2;
    }
    if(currLine != G.curbuf.getLineCount())  // there is a next line
    {
      lp.set(currLine + 1, 0);

      /* #ifdef FEAT_VIRTUALEDIT
	         lp->coladd = 0;
         #endif */

      return 1;
    }
    return -1;
  }

  static int inc_cursorV7() {
    return incV7(G.curwin.w_cursor);
  }

  /**
   * inclV7(fpos)
   *<p>
   * Same as incV7(), but skip the newline at the end of non-empty lines.
   * </p>
   */
  static int inclV7(ViFPOS fpos) {
    int rc = incV7(fpos);
    if (rc >= 1 && fpos.getColumn() > 0) {
      rc = incV7(fpos);
    }
    return rc;
  }

  /**
   * dec_cursor()
   *<p>
   * Decrement the current window's cursor position crossing line boundaries
   * as necessary.  Return 1 when crossing a line, -1 when at start of file, 0
   * otherwise.
   * </p>
   */
  static int dec_cursor() {
    ViFPOS fpos = G.curwin.w_cursor.copy();
    int rc = dec(fpos);
    G.curwin.w_cursor.set(fpos.getOffset()); // KEEP fpos.getOffset()
    return rc;
  }

  /**
   * dec(lp)
   *<p>
   * Decrement the line pointer 'lp' crossing line boundaries as necessary.
   * Return 1 when crossing a line, -1 when at start of file, 0 otherwise.
   * Code is taken from vim 7.0 (but also behaves as 5.6)
   * </p>
   */
  static int dec(ViFPOS lp) {
    int currLine = lp.getLine();
    int currCol = lp.getColumn();

    // #ifdef FEAT_VIRTUALEDIT ... #endif

    if (currCol > 0) {                               // still within line
      lp.set(currLine, currCol - 1);

      // #ifdef FEAT_MBYTE ... #endif

      return 0;
    }
    if (currLine > 1) {                              // there is a prior line
      int newLine = currLine - 1;
      lp.set(newLine, Util.lineLength(newLine));

      // #ifdef FEAT_MBYTE ... #endif

      return 1;
    }
    return -1;                                      // at start of file
  }

  /**
   * decl(lp)
   *<p>
   * Same as dec(), but skip the newline at the end of non-empty lines.
   * Code is taken from vim 7.0 (but also behaves a 5.6)
   * </p>
   */
  static int decl(ViFPOS lp) {
    int rc = dec(lp);
    if (rc == 1 && lp.getColumn() > 0) {
      rc = dec(lp);
    }
    return rc;
  }

  /**
   * inclDeclV7(lp,dir)
   *<p>
   * Increment or decrement the line pointer 'lp' base on direction 'dir'
   * crossing line boundaries as necessary and skipping over newline char
   * ('\n').  Return 1 when crossing a line, -1 when at end or start of file,
   * 0 otherwise.  Code is vim7.0 biased.
   * </p>
   */
  static int inclDeclV7(ViFPOS lp, int dir) {
    if (dir == BACKWARD)
      return decl(lp);
    else
      return inclV7(lp);
  }

  /**
   * Make sure current line number is valid and do begin line according
   * to flags.
   */
  static void check_cursor_lnumBeginline(int flags) {
    final ViFPOS cursor = G.curwin.w_cursor;
    int lnum = check_cursor_lnum(cursor.getLine());
    if(lnum > 0) {
      G.curwin.w_cursor.set(lnum, 0);
    }
    Edit.beginline(flags);
  }

  /**
   * Make sure curwin->w_cursor.lnum is valid.
   * @return correct line number.
   */
  static int check_cursor_lnum(int lnum) {
    if(lnum > G.curbuf.getLineCount()) {
      lnum = G.curbuf.getLineCount();
    }
    if(lnum <= 0) {
      lnum = 1;
    }
    return lnum;
  }

  /**
   * Make sure curwin->w_cursor.col is valid.
   * <p>Kind of screwy, using swing component cursor is always valid,
   * but this may backup the cursor off of a newline.
   */
  public static void check_cursor_col() {
    // just call adjust cursor, lnum must be valid.
    adjust_cursor();
  }

  /**
   * Make sure curwin->w_cursor.col is valid.
   * <p>Kind of screwy, using swing component cursor is always valid,
   * but this may backup the cursor off of a newline.
   * @return correct column number.
   */
  static int check_cursor_col(int lnum, int col) {
    int len;
    MySegment seg = G.curbuf.getLineSegment(lnum);
    len = seg.count - 1; // don't count trailing newline

    if (len == 0) {
      col = 0;
    } else if (col >= len) {
      // adjust len to ignore trailing newline
      // MUST_HAVE_NL
      // if(seg.array[seg.offset + len - 1] == '\n') { len--; }
      // Allow cursor past end-of-line in Insert mode, restarting Insert
      // mode or when in Visual mode and 'selection' isn't "old"
      if ((G.State & INSERT) != 0 || G.restart_edit != 0
	 	|| (G.VIsual_active && G.p_sel.charAt(0) != 'o')) {
	col = len;
      } else {
	col = len - 1;
      }
    }
    return col;
  }

  /**
   * make sure curwin->w_cursor in on a valid character.
   * <p>Kind of screwy, using swing component cursor is always valid,
   * but this may backup the cursor off of a newline.
   */
  static void adjust_cursor() {
    final ViFPOS cursor = G.curwin.w_cursor;
    int lnum = check_cursor_lnum(cursor.getLine());
    int col = check_cursor_col(lnum, cursor.getColumn());
    if(lnum != cursor.getLine() || col != cursor.getColumn()) {
      cursor.set(lnum, col);
    }
  }


  private static ViCaretStyle[] cursor_table;

  /**
   * Parse options for cursor shapes.
   * <br><b>NEEDSWORK:</b><ul>
   * <li>actually parse something rather than hardcode shapes
   * </ul>
   */
  private static void parse_guicursor() {
    // Set them up according to the defaults.
    Cursor block = new Cursor(SHAPE_BLOCK, 0, 0);
    Cursor ver25_input = new Cursor(SHAPE_VER, 25, -1);
    Cursor ver35_visual = new Cursor(SHAPE_VER, 35, 0);
    Cursor hor20 = new Cursor(SHAPE_HOR, 20, 0);
    Cursor hor50 = new Cursor(SHAPE_HOR, 50, 0);

    cursor_table = new ViCaretStyle[SHAPE_COUNT];
    cursor_table[SHAPE_N] = block;
    cursor_table[SHAPE_V] = block;
    cursor_table[SHAPE_I] = ver25_input;
    cursor_table[SHAPE_R] = hor20;
    cursor_table[SHAPE_C] = block;
    cursor_table[SHAPE_CI] = ver25_input;
    cursor_table[SHAPE_CR] = hor20;
    cursor_table[SHAPE_SM] = block;	// plus different blink rates
    cursor_table[SHAPE_O] = hor50;
    cursor_table[SHAPE_VE] = ver35_visual;
  }

  /**
   * @return the description of the cursor to draw
   */
  private static ViCaretStyle getCursor() {
    if(cursor_table == null) {
      parse_guicursor();
    }
    return cursor_table[get_cursor_idx()];
  }

  /**
   * Return the index into cursor_table[] for the current mode.
   */
  static private int get_cursor_idx() {
      if (G.State == SHOWMATCH)	{ return SHAPE_SM; }
      if (G.State == INSERT)	{ return SHAPE_I; }
      if (G.State == REPLACE)	{ return SHAPE_R; }
      if (G.State == VREPLACE)	{ return SHAPE_R; }
      if (G.State == CMDLINE) {
	  if (Misc.cmdline_at_end())	 { return SHAPE_C; }
	  if (Misc.cmdline_overstrike()) { return SHAPE_CR; }
	  return SHAPE_CI;
      }
      if (G.finish_op)		{ return SHAPE_O; }
      if (G.VIsual_active) {
	  if (G.p_sel.charAt(0) == 'e') {
              return SHAPE_VE;
          } else {
              return SHAPE_V;
          }
      }

      // If there's a java selection
      if(G.curwin.hasSelection()) {
        return SHAPE_VE;
      }

      return SHAPE_N;
  }

  //////////////////////////////////////////////////////////////////
  //
  // "ops.c"
  //


  /**
   * op_shift - handle a shift operation
   */
  static void op_shift(OPARG oap, boolean curs_top, int amount) {
    int	    i;
    char    first_char;
    int	    block_col = 0;

    int	    line;

    /*
       if (u_save((linenr_t)(curwin.w_cursor.lnum - 1),
       (linenr_t)(curwin.w_cursor.lnum + oap.line_count)) == FAIL)
       return;
     */


    if (oap.block_mode) {
    }

    line = G.curwin.w_cursor.getLine();
    // NOTE: the optimization of using copy() is around 12%
    //       need to do single insert to get a big win
    ViMark mStart = G.curbuf.createMark(oap.start);
    ViMark mEnd = G.curbuf.createMark(oap.end);
    ViFPOS fpos = G.curwin.w_cursor.copy();
    for (i = oap.line_count; --i >= 0; ) {
      fpos.set(line, 0);
      first_char = gchar_pos(fpos);
      if (first_char != '\n') {	// empty line
	shift_line(oap.op_type == OP_LSHIFT, G.p_sr, amount, fpos);
      }
      line++;
    }

    if (oap.block_mode) {
    } else if (curs_top) {    /* put cursor on first line, for ">>" */
      G.curwin.w_cursor.set(line - oap.line_count, 0);
      Edit.beginline(BL_SOL | BL_FIX); // shift_line() may have set cursor.col
    } else {
      G.curwin.w_cursor.set(line - 1, 0);
    }
    // update_topline();
    // update_screen(NOT_VALID);

    if (oap.line_count >= G.p_report) {
      Msg.smsg("" + oap.line_count + " line" + plural(oap.line_count)
	       + " " + ((oap.op_type == OP_RSHIFT) ? ">" : "<") + "ed "
	       + amount + " time" +  plural(amount));
    }

    /*
     * Set "'[" and "']" marks.
     */
    G.curbuf.b_op_start.setMark(mStart);
    G.curbuf.b_op_end.setMark(mEnd);
  }

  /**
   * shift the current line one shiftwidth left (if left != 0) or right
   * leaves cursor on first blank in the line
   */
  static void shift_line(boolean left, boolean round, int amount) {
    shift_line(left, round, amount, G.curwin.w_cursor);
  }
  static void shift_line(boolean left, boolean round, int amount, ViFPOS fpos) {
    int		count;
    int		i, j;
    int		p_sw = G.curbuf.b_p_sw;

    count = get_indent(fpos);	// get current indent

    if (round) {		// round off indent
      i = count / p_sw;	// number of p_sw rounded down
      j = count % p_sw;	// extra spaces
      if (j != 0 && left)	// first remove extra spaces
	--amount;
      if (left) {
	i -= amount;
	if (i < 0)
	  i = 0;
      } else {
	i += amount;
      }
      count = i * p_sw;
    } else {		// original vi indent
      if (left) {
	count -= p_sw * amount;
	if (count < 0)
	  count = 0;
      } else {
	count += p_sw * amount;
      }
    }

    /* Set new indent */
    if (G.State == VREPLACE) {
      // change_indent(INDENT_SET, count, false, NUL);
    } else {
      set_indent(count, true, fpos);
    }
  }

  //
  // YANK STUFF
  //

  /**
   * Map yankreg index to regname string, this is like an inverse mapping
   * @param i yankreg index
   * @return the regname as a string
   */
  private static String getPersistableYankreg(int i) {
    if(i == -1)
      return "\"";
    else if(i <= 9)
      return String.valueOf((char)('0'+i));
    else if(i <= 35)
      return String.valueOf((char)('a' + i - 10));
    else if(i == DELETION_REGISTER)
      return "-";
    else if(i == CLIPBOARD_REGISTER)
      return "*";
    else
      return null;
  }

  private static final String DATA = "data";
  private static final String TYPE = "type";

  private static void read_viminfo_registers() {
    Preferences prefsRegs = ViManager.getFactory()
            .getPreferences().node(PREF_REGISTERS);
    for(int i = 0; i < y_regs.length; i++) {
      try {
        String regname = getPersistableYankreg(i);
        if (prefsRegs.nodeExists(regname)) {
          Preferences prefs = prefsRegs.node(regname);
          String regval = prefs.get(DATA, null);
          if (regval != null) {
            int type = prefs.getInt(TYPE, MCHAR);
            get_yank_register(regname.charAt(0), false);
            Yankreg reg = y_current;
            reg.setData(regval, type);
            //System.err.println("\t" + type);
            //System.err.println("\t" + type);
          }
        }
      } catch (BackingStoreException ex) {
        //Logger.getLogger(Misc.class.getName()).log(Level.SEVERE, null, ex);
      }
    }
  }

  private static void write_viminfo_registers() {
    Preferences prefsRegs = ViManager.getFactory()
            .getPreferences().node(PREF_REGISTERS);
    for(int i = 0; i < y_regs.length; i++) {
      String regname = getPersistableYankreg(i);
      if(regname.equals("-") || regname.equals("*"))
        continue;
      Yankreg reg = y_regs[i];
      String regval = null;
      if(reg != null) {
        regval = reg.getAll();
      }
      try {
        if(regval == null || regval.length() == 0 || regval.length() > 1024) {
            if (prefsRegs.nodeExists(regname)) {
              prefsRegs.node(regname).removeNode();
            }
        } else {
          Preferences prefs = prefsRegs.node(regname);
          prefs.put(DATA, regval);
          prefs.putInt(TYPE, reg.y_type);
        }
        prefsRegs.flush();
      } catch (BackingStoreException ex) {
        //Logger.getLogger(Misc.class.getName()).log(Level.SEVERE, null, ex);
      }
    }
  }

  private static void read_viminfo_search() {
    List<String> l = readList(PREF_SEARCH);
    getSearchCommandEntry().setHistory(l);
  }

  private static void read_viminfo_command() {
    List<String> l = readList(PREF_COMMANDS);
    getColonCommandEntry().setHistory(l);
  }

  private static void write_viminfo_search() {
    ViCmdEntry ce = getSearchCommandEntry();
    writeList(PREF_SEARCH, ce.getHistory());
  }

  private static void write_viminfo_command() {
    ViCmdEntry ce = getColonCommandEntry();
    writeList(PREF_COMMANDS, ce.getHistory());
  }

  private static List<String> readList(String nodeName)
  {
    List<String> l = new ArrayList<String>();
    try {
      Preferences prefs = ViManager.getFactory().getPreferences().node(nodeName);
      int nKey = prefs.keys().length;
      for(int i = 1; i <= nKey; i++) {
        String s = prefs.get("" + i, "");
        if(!s.isEmpty())
          l.add(s);
      }
    } catch(BackingStoreException ex) {
      LOG.log(Level.SEVERE, null, ex);
    }
    return l;
  }

  private static void writeList(String nodeName, List<String> l)
  {
    try {
      Preferences prefs = ViManager.getFactory().getPreferences().node(nodeName);
      prefs.removeNode();
      prefs = ViManager.getFactory().getPreferences().node(nodeName);
      int i = 1;
      for(String s : l) {
        prefs.put("" + i, s);
        i++;
      }
      prefs.flush();
    } catch(IllegalStateException ex) {
    } catch(BackingStoreException ex) {
      LOG.log(Level.SEVERE, null, ex);
    }
  }

  /**
   * Sadly the yankbuf's are not code compatible
   * except for MBLOCK.
   * <br/>For MLINE and MCHAR there is a single
   * string, even if multiple lines, and the string
   * has embedded '\n'. The y_size is the number of
   * lines, but y_array is only one element. The
   * difference between MLINE and MCHAR is the
   * terminating '\n'.
   * <br/> Although not code compatible, the code for
   * put operations is pretty simple.
   */
  static class Yankreg implements Cloneable {
    // NEEDSWORK: init to null when private
    StringBuilder[] y_array = new StringBuilder[1];

    // NOTE: if a field is added, make sure to fixup this.set(Yankreg)
    int y_size;
    int y_type;
    int y_width;

    /** @return the indexed line, index starts at 0 */
    String get(int i) {
      return y_array[i].toString();
    }

    /** @deprectated
     * @return all the contents as a single string
     */
    String getAll() {
      //assert false;
      //assert y_type != MBLOCK;
      //assert y_size == 1 && y_size == y_array.length;
      if(y_type == MBLOCK) {
        StringBuilder sb = new StringBuilder();
        for(StringBuilder y_array1 : y_array) {
          sb.append(y_array1);
          sb.append('\n');
        }
        return sb.toString();
      }
      else return y_array[0].toString();
    }

    void clear() {
      y_array = null;
    }

    /**
     * Return a yankreg with the same contents as this yankreg. If fCopy is
     * set then create a copy of the string data; if clear then move the data
     * and clear the data in the original.
     */
    Yankreg copy(boolean fCopy) {
      Yankreg reg = null;
      if(fCopy) {
        try {
          reg = (Yankreg)this.clone();
        } catch (CloneNotSupportedException ex) {
          LOG.log(Level.SEVERE, null, ex);
        }
      } else {
        reg = new Yankreg();
        reg.set(this);
      }
      return reg;
    }

    /**
     * Move the contents of the argument Yankreg into this and clear the
     * argument reg's data.
     */
    void set(Yankreg reg) {
      y_size = reg.y_size;
      y_type = reg.y_type;
      y_width = reg.y_width;
      y_array = reg.y_array;
      reg.y_array = new StringBuilder[1]; // NEEDSWORK: init to null when private
    }

        @Override
    protected Object clone() throws CloneNotSupportedException {
      Yankreg reg;
      reg = (Yankreg) super.clone();
      if(y_array != null) {
        reg.y_array = y_array.clone();
        for(int i = 0; i < y_array.length; i++)
          reg.y_array[i] = y_array[i] == null
                            ? null : new StringBuilder(y_array[i]);
      }
      return reg;
    }

    void setData(String s, Integer type) {
      if(type != null && type == MBLOCK) {
        y_width = 0;
        int startOffset = 0;
        int endOffset;
        int lines = 0;
        List<StringBuilder> l = new ArrayList<StringBuilder>();
        while((endOffset = s.indexOf('\n', startOffset)) >= 0) {
          StringBuilder sb
                  = new StringBuilder(s.subSequence(startOffset, endOffset));
          l.add(sb);
          if(sb.length() > y_width)
            y_width = sb.length();
          startOffset = endOffset + 1;
          lines++;
        }
        y_array = new StringBuilder[0];
        y_array = l.toArray(y_array);
        y_type = MBLOCK;
        y_size = lines;

        y_width--;  // WISH I NEW WHY THIS IS NEEDED, see vim's str_to_reg

        //y_array[0] = new StringBuffer(s);
      } else {
        y_width = 0;
        int offset = 0;
        int lines = 0;
        while((offset = s.indexOf('\n', offset)) >= 0) {
          offset++;	// for next time through the loop
          lines++;
        }
        if(lines > 0) {
          y_type = MLINE;
          y_size = lines;
        } else {
          y_type = MCHAR;
          y_size = 1;
        }

        y_array[0] = new StringBuilder(s);
      }
    }

    StringSelection getStringSelection() {
      StringBuilder sb = new StringBuilder();
      for(StringBuilder y_array1 : y_array) {
        sb.append(y_array1);
        if(y_type == MBLOCK)
          sb.append('\n');
      }
      return new StringSelection(sb.toString());
    }
  }

  //
  // Number of registers.
  //	0 = unnamed register, for normal yanks and puts
  //   1..9 = number registers, for deletes
  // 10..35 = named registers
  //     36 = delete register (-)
  //     37 = Clipboard register (*).
  //
  static final int DELETION_REGISTER = 36;   // array index to yankreg
  static final int CLIPBOARD_REGISTER = 37;  // array index to yankreg
  static Yankreg y_current = null;
  static Yankreg y_previous = null;
  static boolean y_append;
  static Yankreg[] y_regs = new Yankreg[38];

  /**
   * print the registers
   */
  private static class DoRegisters extends AbstractColonAction
  {
    @Override
    public void actionPerformed(ActionEvent ev) {
      ColonEvent cev = (ColonEvent)ev;

      String arg = null;
      if(cev.getNArg() > 0)
          arg = cev.getArgString();
      Yankreg yb;

      ViOutputStream vios = ViManager.createOutputStream(
              null, ViOutputStream.OUTPUT, "\n--- Registers ---");
      int columns = 120;
      StringBuilder sb = new StringBuilder();
      for (int i = -1; i < y_regs.length; ++i)
      {
        String name = getPersistableYankreg(i);
        if (arg != null && vim_strchr(arg, name.charAt(0)) == null)
          continue;	    /* did not ask for this register */
        if (i == -1)
        {
          if (y_previous != null)
            yb = y_previous;
          else
            yb = y_regs[0];
        }
        else
          yb = y_regs[i];
        String regval = null;
        if(yb != null)
          regval = yb.getAll();
        if (regval != null)
        {
          sb.setLength(0);
          sb.append("\"").append(name).append("   ");

          int n = columns - 6;

          String escaped = TextUtil.debugString(regval);
          if(escaped.length() > n)
            escaped = escaped.substring(0, n-1);
          sb.append(escaped);
          vios.println(sb.toString());
        }
      }

      /*
       * display last inserted text
       */
      do_dis(vios, sb, ".", arg, true);

      /*
       * display last command line
       */
      do_dis(vios, sb, ":", arg, false);

      /*
       * display current file name
       */
      do_dis(vios, sb, "%", arg, false);

      /*
       * display alternate file name
       */
      do_dis(vios, sb, "#", arg, false);

      /*
       * display last search pattern
       */
      do_dis(vios, sb, "/", arg, false);

      // #ifdef FEAT_EVAL..
      /*
       * display last used expression
       */
      // #endif

      vios.close();
    }
  }

  private static void do_dis(ViOutputStream vios, StringBuilder sb,
                             String regname, String arg, boolean skip_esc) {
    if (arg != null && vim_strchr(arg, regname.charAt(0)) == null)
      return;	    /* did not ask for this register */
    Wrap<String> argp = new Wrap<String>();
    boolean displayit = get_spec_reg(regname.charAt(0), argp, false);
    if(displayit && argp.getValue() != null) {
      sb.setLength(0);
      sb.append("\"").append(regname).append("   ");

      int columns = 120;
      int n = columns - 6;
      String escaped = TextUtil.debugString(argp.getValue());
      if(escaped.length() > n)
        escaped = escaped.substring(0, n-1);
      sb.append(escaped);
      vios.println(sb.toString());
    }
  }

  /**
   * Check if 'regname' is a valid name of a yank register.
   * Note: There is no check for 0 (default register), caller should do this
   * @param writing if true check for writable registers
   */
  public static boolean valid_yank_reg(char regname, boolean writing) {
    if (regname > '~')
      return false;
    return Util.isalnum(regname)
            || (!writing && vim_strchr("/.%#:", 0, regname) >= 0)
            || regname == '"'
            || regname == '-'
            || regname == '_'
            || regname == '*';
  }

  /**
   * Set y_current and y_append, according to the value of "regname".
   * Cannot handle the '_' register.
   *
   * If regname is 0 and writing, use register 0
   * If regname is 0 and reading, use previous register
   */
  static void get_yank_register(char regname, boolean writing) {
    char	    i;

    y_append = false;
    if (((regname == 0 && !writing) || regname == '"') && y_previous != null) {
      y_current = y_previous;
      return;
    }
    i = regname;
    if (Util.isdigit(i)) {
      i -= '0';
    } else if (Util.islower(i)) {
      i -= 'a' - 10;
    } else if (Util.isupper(i)) {
      i -= 'A' - 10;
      y_append = true;
    } else if (regname == '-') {
      i = DELETION_REGISTER;
      /* When clipboard is not available, use register 0 instead of '*' */
    } else if (clipboard_available && regname == '*') {
      i = CLIPBOARD_REGISTER;
    } else {		/* not 0-9, a-z, A-Z or '-': use register 0 */
      i = 0;
    }
    y_current = y_regs[i];
    if(y_current == null) {
      y_current = new Yankreg();
      y_regs[i] = y_current;
    }
    if (writing)	/* remember the register we write into for do_put() */
      y_previous = y_current;
  }

/**
 * Obtain the contents of a "normal" register. The register is made empty.
 * The returned pointer has allocated memory, use put_register() later.
 * @param name the register of which to make a copy
 * @param copy make a copy, if FALSE make register empty.
 * @return the register copy
 */
  static Yankreg get_register(char name, boolean copy) {
    get_yank_register(name, false);
    Yankreg reg = y_current.copy(copy);
    return reg;
  }

/**
 * Put "reg" into register "name".  Free any previous contents.
 */
  static void put_register(char name, Yankreg reg) {
    get_yank_register(name, false);
    y_current.set(reg);
  }

  static char	do_record_regname;
  /**
   * start or stop recording into a yank register
   *
   * return FAIL for failure, OK otherwise
   */
  static int do_record(char c) {
    Yankreg old_y_previous, old_y_current;
    int		retval;

    if ( ! G.Recording) {	    // start recording
                        // registers 0-9, a-z and " are allowed
      if (c > '~' || (!Util.isalnum(c) && c != '"'))
        retval = FAIL;
      else {
        G.Recording = true;
        showmode();
        do_record_regname = c;
        retval = OK;
      }
    } else {			    /* stop recording */
      String s;
      G.Recording = false;
      showmode(); // Msg.smsg("");
      s = GetChar.get_recorded();
      if (s.length() == 0)
        retval = FAIL;
      else {
        //
        // We don't want to change the default register here, so save and
        // restore the current register name.
        //
        old_y_previous = y_previous;
        old_y_current = y_current;

        retval = stuff_yank(do_record_regname, s);
        // System.err.println("Recorded: '" + s + "'");

        y_previous = old_y_previous;
        y_current = old_y_current;
      }
    }
    return retval;
  }

  /**
   * Stuff string 'p' into yank register 'regname' as a single line (append if
   * uppercase).	'p' must have been alloced.
   *
   * @return FAIL for failure, OK otherwise
   */
  static private int stuff_yank(char regname, String s) {
    // check for read-only register
    if (regname != 0 && !valid_yank_reg(regname, true)) {
      return FAIL;
    }
    if (regname == '_') {		    // black hole: don't do anything
      return OK;
    }
    get_yank_register(regname, true);
    if (y_append) {
      y_current.y_array[0].append(s);
    } else {
      free_yank_all();
      y_current.y_array[0].append(s);
      y_current.y_size = 1;
      y_current.y_type = MCHAR;  // (orig comment)used to be MLINE, why?
    }
    return OK;
  }

  static private char	lastc_do_execreg = NUL;
  /**
   * execute a yank register: copy it into the stuff buffer
   * @return FAIL for failure, OK otherwise
   * @param regname     get commands from this register
   * @param colon	insert ':' before each line
   * @param addcr	always add '\n' to end of line
   */
  static int do_execreg(char regname, boolean colon, boolean addcr) {
    int		retval = OK;

    // NEEDSWORK: do_execreg: colon always false
    if (regname == '@')			// repeat previous one
      regname = lastc_do_execreg;
    // check for valid regname
    if (regname == '%' || regname == '#' || !valid_yank_reg(regname, false))
      return FAIL;
    lastc_do_execreg = regname;

    if (regname == '_')			// black hole: don't stuff anything
      return OK;

    if (regname == ':')			// use last command line
    {
      return FAIL; // NEEDSWORK: do_execreg ':'
      /* ****************************************************************
      !!! s = ColonCommands.lastCommand; // s = last_cmdline;
      if (last_cmdline == NULL)
      {
        EMSG(e_nolastcmd);
        return FAIL;
      }
      vim_free(new_last_cmdline); // don't keep the cmdline containing @:
      new_last_cmdline = NULL;
      retval = put_in_typebuf(last_cmdline, TRUE);
      ****************************************************************/
    }
    else if (regname == '.')		// use last inserted text
    {
      String s = get_last_insert_save();
      if (s == null || s.length() == 0)
      {
        Msg.emsg(Messages.e_noinstext);
        return FAIL;
      }
      retval = put_in_typebuf(s, colon);
    }
    else
    {
      int remap;
      get_yank_register(regname, false);
      if (y_current.y_size == 0 || y_current.y_array == null
            || y_current.y_array.length == 0 || y_current.y_array[0].length() == 0)
        return FAIL;

      /* Disallow remaping for ":@r". */
      remap = colon ? -1 : 0;

      //
      // Insert lines into typeahead buffer, from last one to first one.
      //
      /* ****************************************************************
      for (i = y_current.y_size; --i >= 0; )
      {
        // insert newline between lines and after last line if type is MLINE
        if (y_current.y_type == MLINE || i < y_current.y_size - 1
            || addcr)
        {
          if (ins_typebuf("\n", remap, 0, true) == FAIL)
            return FAIL;
        }
        if (ins_typebuf(y_current.y_array[i], remap, 0, true) == FAIL)
          return FAIL;
        if (colon && ins_typebuf(":", remap, 0, true) == FAIL)
          return FAIL;
      }
      ****************************************************************/
      // Just roll our own for jvi
      StringBuilder sb = new StringBuilder(y_current.y_array[0]);
      if(y_current.y_type == MLINE || addcr) {
        if(sb.length() == 0 || sb.charAt(sb.length()-1) != '\n') {
          sb.append('\n');
        }
      }
      //
      // NEEDSWORK: if(colon) put ":" begin of each line
      //
      if(ins_typebuf_redo(sb, remap, 0, true) == FAIL) {
        return FAIL;
      }
      G.Exec_reg = true;	// disable the 'q' command
    }
    return retval;
  }

private static int put_in_typebuf(String s, boolean colon)
{
    int		retval = OK;

    if (colon)
	retval = ins_typebuf_redo("\n", FALSE, 0, true);
    if (retval == OK)
	retval = ins_typebuf_redo(s, FALSE, 0, true);
    if (colon && retval == OK)
	retval = ins_typebuf_redo(":", FALSE, 0, true);
    return retval;
}

  /**
   * Free up storage associated with current yank buffer.
   */
  static void free_yank_all() {
    if(y_current.y_array.length != 0) {
      y_current.y_array = new StringBuilder[1];
      y_current.y_array[0] = new StringBuilder();
    }
  }

  /**
   * Shift yank registers for a delete; make register 1 current.
   */
  static void shiftYank() {
    int n;
    // GC: y_current = y_regs[9];
    // GC: free_yank_all();			// free register nine
    for (n = 9; n > 1; --n)
      y_regs[n] = y_regs[n - 1];

    //y_regs[1].y_array = NULL;		// set register one to empty
    y_regs[1] = null;
    // y_previous = y_current = y_regs[1];
    get_yank_register('1', true);
  }

  /**
   * Put a string into a yank register. First used from clip_get_selection.
   */

  /**
   * Adjust the register name pointed to with "rp" for the clipboard being
   * used always and the clipboard being available.
   */
  static char adjust_clip_reg(char rp)
  {
    // If no reg. specified, and "unnamed" is in 'clipboard', use '*' reg.
    if (rp == 0 && G.p_cb)
      rp = '*';
    if(rp == '+')
      rp = '*';
    if (!clipboard_available && rp == '*')
      rp = 0;
    return rp;
  }

  /**
   * Insert a yank register: copy it into the Read buffer.
   * Used by CTRL-R command and middle mouse button in insert mode.
   *
   * return FAIL for failure, OK otherwise
   */
  static int insert_reg(char regname, boolean literally)
  throws NotSupportedException
  {
    int         i;
    int		retval = OK;

/////    /*
/////     * It is possible to get into an endless loop by having CTRL-R a in
/////     * register a and then, in insert mode, doing CTRL-R a.
/////     * If you hit CTRL-C, the loop will be broken here.
/////     */
/////    ui_breakcheck();
/////    if (got_int)
/////	return FAIL;

    /* check for valid regname */
    if (regname != NUL && !valid_yank_reg(regname, false))
	return FAIL;

    Wrap<String> pArg = new Wrap<String>();
    if (regname == '*')
    {
	if (!clipboard_available)
	    regname = 0;
	else
	    clip_get_selection();	/* may fill * register */
    }

    if (regname == '.')	{	// insert last inserted text
	retval = stuff_inserted(NUL, 1, true);
    }
    else if (get_spec_reg(regname, pArg, true))
    {
        String arg = pArg.getValue();
	if (arg == null)
	    return FAIL;
	if (literally)
	    stuffescaped(arg);
	else {
            Edit.checkForStuffReadbuf(arg, "i_CTRL-R");
	    stuffReadbuff(arg);
        }
    }
    else				/* name or number register */
    {
	get_yank_register(regname, false);
	//if (y_current->y_array == NULL)
        if (y_current.y_array.length == 0
                || y_current.y_array[0] == null
                || y_current.y_array[0].length() == 0)
	    retval = FAIL;
	else
	{
            // Sadly the yankbuf's are not code compatible
            // except for MBLOCK
            if(y_current.y_type == MBLOCK) {
              // THIS LOOP IS THE ORIGINAL CODE
              for (i = 0; i < y_current.y_size; ++i)
              {
                  String s = y_current.get(i);
                  if (literally)
                      stuffescaped(s);
                  else {
                      Edit.checkForStuffReadbuf(s, "i_CTRL-R");
                      stuffReadbuff(s);
                  }
                  //
                  // Insert a newline between lines and after last line if
                  // y_type is MLINE.
                  //
                  if (y_current.y_type == MLINE || i < y_current.y_size - 1)
                      stuffcharReadbuff('\n');
              }
            } else {
              String s = y_current.get(0);
              if (literally)
                  stuffescaped(s);
              else {
                  Edit.checkForStuffReadbuf(s, "i_CTRL-R");
                  stuffReadbuff(s);
              }
            }
	}
    }

    return retval;
  }

  /*
   * Stuff a string into the typeahead buffer, such that edit() will insert it
   * literally. Note that IM_LITERAL is used instead of Ctrl-V. That is because
   * Ctrl-V only allows a few different characters.
   */
  private static void stuffescaped(String arg)
  {
      int offset = 0;
      while (offset < arg.length())
      {
          char c = arg.charAt(offset);
          stuffcharReadbuff(IM_LITERAL);
          stuffcharReadbuff(c);
          offset++;
      }
  }

  static int check_fname()
  {
    File f = G.curbuf.getFile();
    if(f == null || !f.isFile()) {
      Msg.emsg(Messages.e_noname);
      return FAIL;
    }
    return OK;
  }

  /*
   * If "regname" is a special register, return a pointer to its value.
   */
  static boolean get_spec_reg(char regname, Wrap<String> argp, boolean errmsg)
  {
      int		cnt;
      String            s;

      argp.setValue(null);
      switch (regname)
      {
          case '%':		// file name
              if (errmsg)
                  check_fname();	// will give emsg if not set
              argp.setValue(G.curbuf.getFile().getPath());
              return true;

//          case '#':		/* alternate file name */
//              argp.setValue(getaltfname(errmsg));	/* may give emsg if not set */
//              return true;

//  #ifdef WANT_EVAL...

          case ':':		/* last command line */
              s = ColonCommands.lastCommand; // s = last_cmdline;
              if (s == null && errmsg)
                  Msg.emsg(Messages.e_nolastcmd);
              argp.setValue(s);
              return true;

          case '/':		/* last search-pattern */
              s = last_search_pat();
              if (s == null && errmsg)
                  Msg.emsg(Messages.e_noprevre);
              argp.setValue(s);
              return true;

          case '.':		/* last inserted text */
              argp.setValue(get_last_insert_save());
              if (argp.getValue() == null && errmsg)
                  Msg.emsg(Messages.e_noinstext);
              return true;

//  #ifdef FILE_IN_PATH...

          case 0x1f & 'W':  // ctrl      // word under cursor
          case 0x1f & 'A':  // ctrl      // WORD (mnemonic All) under cursor
              if (!errmsg)
                  return false;
              MutableInt mi = new MutableInt();
              CharacterIterator ci
                      = find_ident_under_cursor(mi, regname == ctrl('W')
                                     ?  (FIND_IDENT|FIND_STRING) : FIND_STRING);
              cnt = mi.getValue();
              argp.setValue(cnt > 0 ? ci.toString() : null);
              return true;

          case '_':		/* black hole: always empty */
              argp.setValue("");
              return true;
      }

      return false;
  }

  /**
   * op_delete - handle a delete operation
   */
  static boolean op_delete(OPARG oap) {

    boolean		did_yank = true;
    int			old_lcount = G.curbuf.getLineCount();
    ViFPOS              opStartPos = null;
    ViFPOS              opEndPos = null;

    /*
    if (curbuf.b_ml.ml_flags & ML_EMPTY)	    // nothing to do
      return OK;
    */

    // Nothing to delete, return here.	Do prepare undo, for op_change().
    if (oap.empty)
    {
      Normal.u_save_cursor();
      return true;
    }

    // If no register specified, and "unnamed" in 'clipboard', use * register
    if (oap.regname == 0 && G.p_cb)
      oap.regname = '*';
    if (!clipboard_available && oap.regname == '*')
      oap.regname = 0;

    //
    // NEEDSWORK: op_delete: Imitate strange Vi: If the delete spans ....
    // Imitate the strange Vi behaviour: If the delete spans more than one line
    // and motion_type == MCHAR and the result is a blank line, make the delete
    // linewise.  Don't do this for the change command or Visual mode.
    //
    /*
    if (       oap.motion_type == MCHAR
	       && !oap.is_VIsual
	       && !oap.block_mode
	       && oap.line_count > 1
	       && oap.op_type == OP_DELETE)
    {
      ptr = ml_get(oap.end.lnum) + oap.end.col + oap.inclusive;
      ptr = skipwhite(ptr);
      if (*ptr == NUL && inindent(0))
	oap.motion_type = MLINE;
    }
    */

    //
    // Check for trying to delete (e.g. "D") in an empty line.
    // Note: For the change operator it is ok.
    //
    if (       oap.motion_type == MCHAR
	       && oap.line_count == 1
	       && oap.op_type == OP_DELETE
			 //&& *ml_get(oap.start.lnum) == NUL
	       && Util.getCharAt(G.curbuf.getLineStartOffset(
                                            oap.start.getLine())) == '\n')
    {
      //
      // It's an error to operate on an empty region, when 'E' inclucded in
      // 'cpoptions' (Vi compatible).
      //
      if (vim_strchr(G.p_cpo, 0, CPO_EMPTYREGION) >= 0) {
	Util.beep_flush();
        return false;
      }
      return true;
    }

    if(!valid_op_range(oap))
      return false;

    //
    // Do a yank of whatever we're about to delete.
    // If a yank register was specified, put deleted text into that register.
    // For the black hole register '_' don't yank anything.
    //
    if (oap.regname != '_') {
      if (oap.regname != 0) {
	// check for read-only register
	if (!valid_yank_reg(oap.regname, true)) {
	  Util.beep_flush();
	  return false;
	}
	get_yank_register(oap.regname, true); // yank into specif'd reg.
	if (op_yank(oap, true, false) == OK)   // yank without message
	  did_yank = true;
      }

      //
      // Put deleted text into register 1 and shift number registers if the
      // delete contains a line break, or when a regname has been specified!
      //
      if (oap.regname != 0 || oap.motion_type == MLINE
	  || oap.line_count > 1)
      {
	shiftYank();
	oap.regname = 0;
      } else if (oap.regname == 0) {		// yank into unnamed register
	oap.regname = '-';		// use special delete register
	get_yank_register(oap.regname, true);
	oap.regname = 0;
      }

      if (oap.regname == 0 && op_yank(oap, true, false) == OK) {
	did_yank = true;
      }

      /* **********************************
       NEEDSWORK: op_delete, yank failed, ask continue anyway
      //
      // If there's too much stuff to fit in the yank register, then get a
      // confirmation before doing the delete. This is crude, but simple.
      // And it avoids doing a delete of something we can't put back if we
      // want.
      //
      if (!did_yank) {
	if (ask_yesno((char_u *)"cannot yank; delete anyway", TRUE) != 'y') {
	  emsg(e_abort);
	  return FAIL;
	}
      }
      *****************************************/
    }

    //
    // block mode delete
    //
    if (oap.block_mode) {
      block_delete(oap);
    } else if (oap.motion_type == MLINE) {
      // OP_CHANGE stuff moved to op_change
      if(oap.op_type == OP_CHANGE) {
	boolean delete_last = false;
	int line =  G.curwin.w_cursor.getLine();
	if(line + oap.line_count - 1 >= G.curbuf.getLineCount()) {
	  delete_last = true;
	}
	del_lines(oap.line_count, true, true);
	open_line(delete_last ? FORWARD : BACKWARD, true, true, 0);
      } else {
	del_lines(oap.line_count, true, true);
	Edit.beginline(BL_WHITE | BL_FIX);
      }
      // full lines are deleted, set op end/start to current pos.
      opStartPos = G.curwin.w_cursor.copy();
      opEndPos = opStartPos;
      // u_clearline();	// "U" command should not be possible after "dd"
    }
    else if(oap.line_count == 1) {
      // delete characters within one line
      int start = oap.start.getOffset();
      int end = oap.end.getOffset();
      int n = end - start + 1 - (oap.inclusive ? 0 : 1);
      del_chars(n, G.restart_edit == 0);
    }
    else {
      // delete characters between lines
      //if (Normal.u_save_cursor() == FAIL)	// save first line for undo
      //  return FAIL;

      int start = oap.start.getOffset();
      int end = oap.end.getOffset();
      // NEEDSWORK: inclusive: instead of decrementing, increment
      if(!oap.inclusive) {
	// NEEDSWORK: op_delete: handle oap.inclusive.
	// end--;
      } else {
	// System.err.println("*****\n***** delete oap.inclusive\n*****");
	end++;
      }
      G.curwin.deleteChar(start, end);
      if(G.restart_edit == 0) {
	Misc.check_cursor_col();
      }
    }

    // remove code for different screen update cases

    msgmore(G.curbuf.getLineCount() - old_lcount);
    //
    // set "'[" and "']" marks.
    //
    if(opEndPos == null) {
      if(oap.block_mode) {
        opEndPos = oap.end.copy();
        opEndPos.setColumn(oap.start.getColumn());
      } else
        opEndPos = oap.start;
    }

    if(opStartPos == null)
      opStartPos = oap.start;

    G.curbuf.b_op_end.setMark(opEndPos);
    G.curbuf.b_op_start.setMark(opStartPos);
    return true;
  }

  /**
   * block mode delete
   *
   * This was inside an if block in op_delete.
   * Pull it out to clarify swap algorithm
   */
  private static void block_delete(OPARG oap) {
    if(!blockOpSwapText || oap.line_count < 5) {
      block_deleteInplace(oap);
      return;
    }
    //
    // Use block_deleteInplace for the first line and last line
    // so that they are separate undoable operation.
    // Then the undo/redo pointer are better placed (I hope).
    //

    // there are at least 3 lines to do

    int lnum = oap.start.getLine();
    int finishPositionColumn = block_deleteInplace(oap, lnum, lnum);
    ++lnum; // did the first line
    int endLine = oap.end.getLine() - 1; // don't do last line in big chunk

    // startOffset/endOffset of single multi-line chunk replaced in the document
    int startOffset = G.curbuf.getLineStartOffset(lnum);
    int endOffset = G.curbuf.getLineEndOffset(endLine);
    StringBuilder sb = new StringBuilder(endOffset - startOffset);

    block_def bd = new block_def();
    for (; lnum <= endLine; lnum++) {
      MySegment oldp;
      block_prep(oap, bd, lnum, true);

      oldp = Util.ml_get(lnum);
      if (bd.textlen == 0) {       /* nothing to delete */
        sb.append(oldp);
        continue;
      }

      // If we delete a TAB, it may be replaced by several characters.
      // Thus the number of characters may increase!
      //
      sb.append(oldp, 0, bd.textcol);
      // insert spaces
      append_spaces(sb, bd.startspaces + bd.endspaces);
      // copy the part after the deleted part
      int oldp_idx = bd.textcol + bd.textlen;
      sb.append(oldp, oldp_idx, oldp.length());
    }

    // delete the trailing '\n'
    sb.deleteCharAt(sb.length()-1);
    G.curbuf.replaceString(startOffset, endOffset-1, sb.toString());

    // do the final line
    ++endLine;
    block_deleteInplace(oap, endLine, endLine);

    G.curwin.w_cursor.set(oap.start.getLine(), finishPositionColumn);
    adjust_cursor();

    update_screen(VALID_TO_CURSCHAR);
    oap.line_count = 0; /* no lines deleted */
  }

  /** the original block_delete */
  private static int block_deleteInplace(OPARG oap) {
//      if (u_save((oap.start.getLine() - 1), (oap.end.getLine() + 1)) == FAIL)
//            return FAIL;

    ViFPOS fpos = oap.start;
    int finishPositionColumn = block_deleteInplace(oap,
                                                       oap.start.getLine(),
                                                       oap.end.getLine());

    G.curwin.w_cursor.set(fpos.getLine(), finishPositionColumn);
    //changed_cline_bef_curs();/* recompute cursor pos. on screen */
    //approximate_botline();/* w_botline may be wrong now */
    adjust_cursor();

    //changed();
    update_screen(VALID_TO_CURSCHAR);
    oap.line_count = 0; /* no lines deleted */
    return finishPositionColumn;
  }

  private static int block_deleteInplace(OPARG oap,
                                             int startLine,
                                             int endLine) {
    //ViFPOS fpos = G.curwin.w_cursor.copy();
    ViFPOS fpos = oap.start;
    int finishPositionColumn = fpos.getColumn();
    //int lnum = fpos.getLine();
    int lnum = startLine;
    //for (; lnum <= oap.end.getLine(); lnum++)
    for (; lnum <= endLine; lnum++) {
      MySegment oldp;
      StringBuilder newp;
      block_def bd = new block_def();
      block_prep(oap, bd, lnum, true);
      if (bd.textlen == 0)       /* nothing to delete */
        continue;

      /* Adjust cursor position for tab replaced by spaces and 'lbr'. */
      if (lnum == fpos.getLine()) {
        finishPositionColumn = bd.textcol + bd.startspaces;
      }

      // n == number of chars deleted
      // If we delete a TAB, it may be replaced by several characters.
      // Thus the number of characters may increase!
      //
      int n = bd.textlen - bd.startspaces - bd.endspaces;
      if(bd.startspaces + bd.endspaces == 0) {
        // No TAB is split, simplify
        int lineStart = G.curbuf.getLineStartOffset(lnum);
        G.curwin.deleteChar(lineStart + bd.textcol,
                            lineStart + bd.textcol + bd.textlen);
      } else {
        oldp = Util.ml_get(lnum);
        newp = new StringBuilder();
        //newp = alloc_check((unsigned)STRLEN(oldp) + 1 - n);
        newp.setLength(oldp.length() - 1 - n); // -1 '\n', no +1 for '\n'
        // copy up to deleted part
        mch_memmove(newp, 0, oldp, 0, bd.textcol);
        // insert spaces
        copy_spaces(newp, bd.textcol, (bd.startspaces + bd.endspaces));
        // copy the part after the deleted part
        int oldp_idx = bd.textcol + bd.textlen;
        mch_memmove(newp, bd.textcol + bd.startspaces + bd.endspaces,
                    oldp, oldp_idx,
                    (oldp.length()-1) - oldp_idx); // STRLEN(oldp) + 1)
        // replace the line
        newp.setLength(STRLEN(newp));
        Util.ml_replace(lnum, newp);
      }
    }
    return finishPositionColumn;
  }

  /**
   * Verify the delete can be performed. In particular,
   * check for guarded areas.
   * @param oap specifies the region to delete
   * @return true if the everything can be deleted
   */
  static boolean valid_op_range(OPARG oap) {
    ViFPOS start = oap.start;
    ViFPOS end = oap.end;
    boolean isValid = true;

    if(start.getOffset() >= end.getOffset()) {
      // don't believe this is needed
      start = oap.end;
      end = oap.start;
      // System.err.println("SWITCH END<-->START");
    }

    // check for guarded regions,
    // just check the first char position of each line
    for(int line = start.getLine(), endLine = Math.min(end.getLine(),
                                                       G.curbuf.getLineCount());
            line <= endLine; line++) {
      //System.err.println("CHECK LINE: " + line);
      if(!Edit.canEdit(G.curwin, G.curbuf, G.curbuf.getLineStartOffset(line))) {
        isValid = false;
        //System.err.println("INVLID LINE: " + line);
        break;
      }
    }

    return isValid;
  }

  public static int op_replace(final OPARG oap, final char c) {
    final MutableInt rval = new MutableInt();
    Misc.runUndoable(new Runnable() {
        @Override
        public void run() {
          rval.setValue(op_replace7(oap, c)); // from vim7
        }
    });
    return rval.getValue();
  }

  public static int op_replace7(OPARG oap, char c)
  {
    // Note use an fpos instead of the cursor,
    // This should avoid jitter on the screen

    if(/* (curbuf.b_ml.ml_flags & ML_EMPTY ) || */ oap.empty)
      return OK;	    // nothing to do

    //#ifdef MULTI_BYTE ... #endif

    if (oap.block_mode) {
      block_replace(oap, c);
    } else {
      ViFPOS fpos = G.curwin.w_cursor.copy();

      //
      // MCHAR and MLINE motion replace.
      //
      if (oap.motion_type == MLINE) {
        oap.start.setColumn(0);
        fpos = oap.start.copy();
        int col = Util.lineLength(oap.end.getLine());
        if (col != 0)
          --col;
        oap.end.setColumn(col);
      } else if (!oap.inclusive)
        dec(oap.end);

      while(fpos.compareTo(oap.end) <= 0) {
        if (gchar_pos(fpos) != '\n') {
          // #ifdef FEAT_MBYTE ... #endif
          pchar(fpos, c);
        }

        // Advance to next character, stop at the end of the file.
        if (inc(fpos) == -1)
          break;
      }
    }

    G.curwin.w_cursor.set(oap.start);
    adjust_cursor();

    oap.line_count = 0;	    // no lines deleted

    // Set "'[" and "']" marks.
    G.curbuf.b_op_start.setMark(oap.start);
    G.curbuf.b_op_end.setMark(oap.end);

    return OK;
  }

  private static void block_replace(OPARG oap, char c)
  {
    if(!blockOpSwapText || oap.line_count < 5) {
      block_replaceInplace(oap, c);
      return;
    }

    int         numc;
    MySegment   oldBuf;
    block_def bd = new block_def();

    bd.is_MAX = G.curwin.w_curswant == MAXCOL;

    int lnum = oap.start.getLine();
    block_replaceInplace(oap, c, lnum, lnum);
    ++lnum; // first line done by "inplace"
    int endLine = oap.end.getLine() - 1; // don't do last line in this loop

    // startOffset/endOffset of single multi-line chunk replaced in the document
    int startOffset = G.curbuf.getLineStartOffset(lnum);
    int endOffset = G.curbuf.getLineEndOffset(endLine);
    StringBuilder sb = new StringBuilder(endOffset - startOffset);

    for (; lnum <= endLine; lnum++) {
      //fpos.set(lnum, 0);
      block_prep(oap, bd, lnum, true);

      oldBuf = Util.ml_get(lnum);

      if (bd.textlen == 0 /* && (!virtual_op || bd.is_MAX)*/)
      {
        sb.append(oldBuf);
        continue;                     // nothing to replace
      }

      // If we split a TAB, it may be replaced by several characters.
      // Thus the number of characters may increase!
      //
      // allow for pre spaces
      // allow for post spp
      numc = oap.end_vcol - oap.start_vcol + 1;
      if (bd.is_short /*&& (!virtual_op || bd.is_MAX)*/)
        numc -= (oap.end_vcol - bd.end_vcol) + 1;
      // oldlen includes textlen, so don't double count

      // copy up to deleted part
      sb.append(oldBuf, 0, bd.textcol);
      // insert pre-spaces
      append_spaces(sb, bd.startspaces);
      /* insert replacement chars CHECK FOR ALLOCATED SPACE */
      append_chars(sb, numc, c);
      if (!bd.is_short) {
        // insert post-spaces
        //copy_spaces(newBuf, STRLEN(newBuf), bd.endspaces);
        append_spaces(sb, bd.endspaces);
        // copy the part after the changed part
        sb.append(oldBuf, bd.textcol + bd.textlen, oldBuf.length());
      } else {
        sb.append('\n');
      }
    }

    // All done with the big chunk.
    // delete the trailing '\n'
    sb.deleteCharAt(sb.length()-1);
    G.curbuf.replaceString(startOffset, endOffset-1, sb.toString());

    ++endLine;
    block_replaceInplace(oap, c, endLine, endLine);
  }

  private static void block_replaceInplace(OPARG oap, char c)
  {
    block_replaceInplace(oap, c, oap.start.getLine(), oap.end.getLine());
  }

  private static void block_replaceInplace(OPARG oap, char c,
                                           int startLine, int endLine)
  {
    int		n;
    int         numc;
    MySegment   oldBuf;
    int         oldlen;
    int		lnum;
    StringBuilder newBuf;
    block_def bd = new block_def();
    int oldp;

    // Note use an fpos instead of the cursor,
    // This should avoid jitter on the screen
    //ViFPOS fpos = G.curwin.w_cursor.copy();

    bd.is_MAX = G.curwin.w_curswant == MAXCOL;
    String fullReplaceString = null;

    for (lnum = startLine; lnum <= endLine; lnum++) {
      //fpos.set(lnum, 0);
      block_prep(oap, bd, lnum, true);
      if (bd.textlen == 0 /* && (!virtual_op || bd.is_MAX)*/)
        continue;                     // nothing to replace

      // n == number of extra chars required
      // If we split a TAB, it may be replaced by several characters.
      // Thus the number of characters may increase!
      //
      // allow for pre spaces
      n = (bd.startspaces != 0 ? bd.start_char_vcols - 1 : 0 );
      // allow for post spp
      n += (bd.endspaces != 0 && !bd.is_oneChar && bd.end_char_vcols > 0
              ? bd.end_char_vcols - 1 : 0 );
      numc = oap.end_vcol - oap.start_vcol + 1;
      if (bd.is_short /*&& (!virtual_op || bd.is_MAX)*/)
        numc -= (oap.end_vcol - bd.end_vcol) + 1;
      // oldlen includes textlen, so don't double count
      n += numc - bd.textlen;

      if(bd.textlen == numc
            && !bd.is_short && bd.startspaces == 0 && bd.endspaces == 0) {
        // replace inplace, there may be a better test but this must work
        if(fullReplaceString == null) {
          StringBuilder sb = new StringBuilder();
          for(int i = 0; i < numc; i++)
            sb.append(c);
          fullReplaceString = sb.toString();
        }
        int offset = G.curbuf.getLineStartOffset(lnum) + bd.textcol;
        G.curbuf.replaceString(offset, offset + numc, fullReplaceString);
      } else {
        //oldp = ml_get_curline();
        //oldBuf = Util.ml_get(fpos.getLine());
        oldBuf = Util.ml_get(lnum);
        oldp = 0;
        oldlen = oldBuf.length() - 1; //excluce the \n
        //newp = alloc_check((unsigned)STRLEN(oldp) + 1 + n);
        // -1 in setlength to ignore \n, don't need +1 since no null at end
        newBuf = new StringBuilder();
        newBuf.setLength(oldlen + n);

        // too much sometimes gets allocated with the setLength,
        // but the correct number of chars gets copied, keep track of that.
        // BTW, a few extra nulls at the end wouldn't hurt vim

        // copy up to deleted part
        mch_memmove(newBuf, 0, oldBuf, oldp, bd.textcol);
        oldp += bd.textcol + bd.textlen;
        // insert pre-spaces
        copy_spaces(newBuf, bd.textcol, bd.startspaces);
        /* insert replacement chars CHECK FOR ALLOCATED SPACE */
        copy_chars(newBuf, STRLEN(newBuf), numc, c);
        if (!bd.is_short) {
          // insert post-spaces
          copy_spaces(newBuf, STRLEN(newBuf), bd.endspaces);
          // copy the part after the changed part, -1 to exclude \n
          int tCount = (oldBuf.length() - 1) - oldp; // STRLEN(oldp) +1
          mch_memmove(newBuf, STRLEN(newBuf), oldBuf, oldp, tCount);
        }
        // delete trailing nulls, vim alloc extra when tabs (seen with gdb)
        newBuf.setLength(STRLEN(newBuf));
        // replace the line
        Util.ml_replace(lnum, newBuf);
      }
    }
  }

  /**
   * op_tilde - handle the (non-standard vi) tilde operator
   */
  static void op_tilde(OPARG oap) {
    ViFPOS		pos;
    ViFPOS		finalPosition;

    /* ***********************************************
       if (u_save((linenr_t)(oap->start.lnum - 1),
       (linenr_t)(oap->end.lnum + 1)) == FAIL)
       return;
     *************************************************************/

    pos = oap.start.copy();

    if (oap.block_mode)		    // Visual block mode
    {
      finalPosition = pos.copy();
      block_def bd = new block_def();
      for (; pos.getLine() <= oap.end.getLine(); pos.set(pos.getLine()+1, 0)) {
        block_prep(oap, bd, pos.getLine(), false);
        pos.setColumn(bd.textcol);
        while (--bd.textlen >= 0) {
          swapchar(oap.op_type, pos);
          if (inc(pos) == -1) // at end of file
            break;
        }
      }

    } else {				    // not block mode
      if (oap.motion_type == MLINE) {
        pos.setColumn(0);      // this is start
        int col = Util.lineLength(oap.end.getLine());
        if (col != 0) {
          --col;
        }
        oap.end.setColumn(col);
      } else if (!oap.inclusive) {
        dec(oap.end);
      }

      finalPosition = pos.copy();
      while (pos.compareTo(oap.end) <= 0) {
        swapchar(oap.op_type, pos);
        if (inc(pos) == -1)    // at end of file
          break;
      }
    }
    G.curwin.w_cursor.set(finalPosition);

    /* **********************************************************
    if (oap.motion_type == MCHAR && oap.line_count == 1 && !oap.block_mode)
      update_screenline();
    else
    {
      update_topline();
      update_screen(NOT_VALID);
    }
    *********************************************************/

    //
    // Set '[ and '] marks.
    //
    G.curbuf.b_op_start.setMark(oap.start);
    G.curbuf.b_op_end.setMark(oap.end);

    if (oap.line_count > G.p_report) {
      Msg.smsg("" + oap.line_count + " line" + plural(oap.line_count) + " ~ed");
    }
  }

  /**
   * If op_type == OP_UPPER: make uppercase,
   * <br>if op_type == OP_LOWER: make lowercase,
   * <br>else swap case of character at 'pos'
   */
  static void swapchar(int op_type, ViFPOS fpos) {
    char    c;
    char    nc;

    c = gchar_pos(fpos);
    nc = swapchar(op_type, c);
    if(c != nc) {
      pchar(fpos, nc);
    }
  }

  static char swapchar(int op_type, char c) {
    char    nc;

    nc = c;
    if (Util.islower(c)) {
      if (op_type != OP_LOWER)
        nc = Util.toupper(c);
    }
    else if (Util.isupper(c))
    {
      if (op_type != OP_UPPER)
        nc = Util.tolower(c);
    }
    return nc;
  }


  /** op_change is "split", save state across calls here. */
  private static class StateOpSplit {
    private final StateSplitOwner owner;
    OPARG       oap;
    block_def	bd;
    int         pre_textlen;

    /** Do not use directly, use acquire/release */
    private StateOpSplit(StateSplitOwner owner) {
      StateOpSplit prev = stateOpSplit;
      stateOpSplit = null;
      assert(prev == null);  // not asserting on stateOpSplit so it will clear

      this.owner = owner;
    }

    static void acquire(StateSplitOwner owner) {
      stateOpSplit = new StateOpSplit(owner);
    }

    static void release() {
      stateOpSplit = null;
    }
  }
  private enum StateSplitOwner {SPLIT_CHANGE, SPLIT_INSERT }

  private static StateOpSplit stateOpSplit;

  static void finishOpSplit() {
    if(stateOpSplit != null) {
      switch(stateOpSplit.owner) {
        case SPLIT_CHANGE: finishOpChange(); break;
        case SPLIT_INSERT: finishOpInsert(); break;
      }
    }
  }

  /**
   * op_change - handle a change operation
   */
  static void op_change(OPARG oap) {
    int		l;

    l = oap.start.getColumn();
    if (oap.motion_type == MLINE) {
      l = 0;
    }
    if (!op_delete(oap))
      return;

    final ViFPOS cursor = G.curwin.w_cursor;
    if ((l > cursor.getColumn()) && !Util.lineempty(cursor.getLine())) //&& !virtual_op
      inc_cursor();

    // check for still on same line (<CR> in inserted text meaningless)
    ///skip blank lines too
    if (oap.block_mode) {
      StateOpSplit.acquire(StateSplitOwner.SPLIT_CHANGE);
      stateOpSplit.oap = oap.copy();
      stateOpSplit.bd = new block_def();
                                    //(long)STRLEN(ml_get(oap.start.lnum));
      stateOpSplit.pre_textlen = Util.lineLength(oap.start.getLine());
      stateOpSplit.bd.textcol = cursor.getColumn();
    }

    Edit.edit(NUL, false, 1);
    if(!Normal.editBusy)
      StateOpSplit.release();
  }

  static void finishOpChange() {
    OPARG       oap = stateOpSplit.oap;
    block_def	bd = stateOpSplit.bd;
    int         pre_textlen = stateOpSplit.pre_textlen;
    StateOpSplit.release();

    int		offset;
    int		linenr;
    int		ins_len;
    MySegment	firstline;
    String      ins_text;
    StringBuilder newp;
    MySegment   oldp;

    //
    // In Visual block mode, handle copying the new text to all lines of the
    // block.
    //
    if (oap.block_mode && oap.start.getLine() != oap.end.getLine()) {
      firstline = Util.ml_get(oap.start.getLine());
        //
        // take a copy of the required bit.
        //
      if ((ins_len = Util.lineLength(oap.start.getLine()) - pre_textlen) > 0) {
        //vim_strncpy(ins_text, firstline + bd.textcol, ins_len);
        ins_text = firstline.subSequence(bd.textcol,
                                         bd.textcol + ins_len).toString();
        for (linenr = oap.start.getLine() + 1; linenr <= oap.end.getLine();
                                                                    linenr++) {
          block_prep(oap, bd, linenr, true);
          if (!bd.is_short /*|| virtual_op*/) {
            oldp = Util.ml_get(linenr);
            int oldp_idx = 0;
            // newp = alloc_check((unsigned)(STRLEN(oldp) + ins_len + 1));
            newp = new StringBuilder();
            newp.setLength(oldp.length() - 1 + ins_len); // -1 for '\n'
            // copy up to block start
            mch_memmove(newp, 0, oldp, oldp_idx, bd.textcol);
            offset = bd.textcol;
            mch_memmove(newp, offset, ins_text, 0, ins_len);
            offset += ins_len;
            oldp_idx += bd.textcol;
            mch_memmove(newp, offset, oldp, oldp_idx,
                        oldp.length() - 1 - oldp_idx); // STRLEN(oldp) + 1);
            Util.ml_replace(linenr, newp);
          }
        }
        adjust_cursor();
      }
    }
  }

  /**
   * Yank the text between curwin->w_cursor and startpos into a yank register.
   * If we are to append (uppercase register), we first yank into a new yank
   * register and then concatenate the old and the new one (so we keep the old
   * one in case of out-of-memory).
   * <br><b>NEEDSWORK:</b><ul>
   * <li>An unfaithful and lazy port of yanking off.
   * <li>No block mode.
   * </ul>
   *
   * @return FAIL for failure, OK otherwise
   */
  public static int op_yank(OPARG oap, boolean deleting, boolean mess) {
    int		y_idx = 0;		// index in y_array[]
    Yankreg		curr;		// copy of y_current
    // char_u		**new_ptr;
    int			lnum;		// current line number
    // int			j;
    // int			len;
    int			yanktype = oap.motion_type;
    int			yanklines = oap.line_count;
    int			yankendlnum = oap.end.getLine();
    // char_u		*p;
    StringBuilder        pnew;
    block_def		bd;

				    // check for read-only register
    if (oap.regname != 0 && !valid_yank_reg(oap.regname, true))
    {
	Util.beep_flush();
	return FAIL;
    }
    if (oap.regname == '_')	    // black hole: nothing to do
	return OK;

    // If no register specified, and "unnamed" in 'clipboard', use * register
    if (!deleting && oap.regname == 0 && G.p_cb)
        oap.regname = '*';
    if (!clipboard_available && oap.regname == '*')
	oap.regname = 0;

    if (!deleting)		    // op_delete() already set y_current
	get_yank_register(oap.regname, true);

    curr = y_current;
				    // append to existing contents
    if (y_append && y_current.y_array != null) {
	// y_current = new Yankreg(); // NEEDSWORK: just append to y_current
    } else {
	free_yank_all();	    // free previously yanked lines
    }

    //
    // If the cursor was in column 1 before and after the movement, and the
    // operator is not inclusive, the yank is always linewise.
    //
    if (       oap.motion_type == MCHAR
	    && oap.start.getColumn() == 0
	    && !oap.inclusive
	    && (!oap.is_VIsual || G.p_sel.charAt(0) == 'o')
            && !oap.block_mode  // from vim7
	    && oap.end.getColumn() == 0
	    && yanklines > 1)
    {
	yanktype = MLINE;
	--yankendlnum;
	--yanklines;
    }

    y_current.y_size = yanklines;
    y_current.y_type = yanktype;   // set the yank register type
    y_current.y_width = 0;
    // y_current.y_array = (char_u **)lalloc_clear.......
    // if (y_current.y_array == NULL) { y_current = curr; return FAIL; }

    lnum = oap.start.getLine();

    //
    // Visual block mode
    //
    if (oap.block_mode) {
      // block mode deleted
      y_current.y_type = MBLOCK;        /* set the yank register type */
      y_current.y_width = oap.end_vcol - oap.start_vcol;

      if (  G.curwin.w_curswant == MAXCOL && y_current.y_width > 0)
          y_current.y_width--;
      y_current.y_array = new StringBuilder[yanklines];
      bd = new block_def();
      for ( ; lnum <= yankendlnum; ++lnum)
      {
          block_prep(oap, bd, lnum, false);

          pnew = new StringBuilder();
          pnew.setLength(bd.startspaces + bd.endspaces + bd.textlen);
          int pnew_idx = 0;
          y_current.y_array[y_idx++] = pnew;

          copy_spaces(pnew, pnew_idx, bd.startspaces);
          pnew_idx += bd.startspaces;

          mch_memmove(pnew, pnew_idx, Util.ml_get(lnum), bd.textstart,
                      bd.textlen);
          pnew_idx += bd.textlen;

          copy_spaces(pnew, pnew_idx, bd.endspaces);
      }
    } else {
      int start;
      int end;
      if(yanktype == MLINE) {
        start = G.curbuf.getLineStartOffset(lnum);
        end = G.curbuf.getLineEndOffset(yankendlnum);
      } else {
        start = oap.start.getOffset();
        end = oap.end.getOffset() + (oap.inclusive ? 1 : 0);
      }
      int length = end - start;
      StringBuilder reg = y_current.y_array[0];
      MySegment seg = G.curbuf.getSegment(start, length, null);
      reg.append(seg.array, seg.offset, seg.count);
      // bug #1724053 visual mode not capture \n after '$'
      // I guess the oap.inclusive should be trusted.
      // if(yanktype == MCHAR && length > 0
      //    	&& reg.charAt(reg.length()-1) == '\n') {
      //   reg.deleteCharAt(reg.length()-1);
      // }
    }

    // NEEDSWORK: if lines are made an array in the yank buffer
    //            then in some cases must append the current
    //            yank to the previous contents of yank buffer

    if (mess)			// Display message about yank?
    {
	if (yanktype == MCHAR && !oap.block_mode && yanklines == 1)
	    yanklines = 0;
	// Some versions of Vi use ">=" here, some don't...
	if (yanklines >= G.p_report)
	{
	    // redisplay now, so message is not deleted
	    // NEEDSWORK: update_topline_redraw();
	    Msg.smsg("" + yanklines + " line" + plural(yanklines) + " yanked");
	}
    }

    //
    //  set "'[" and "']" marks.
    //
    ViFPOS op_start = oap.start.copy();
    ViFPOS op_end = oap.end.copy();
    if(yanktype == MLINE && !oap.block_mode) {
      op_start.setColumn(0);
      // op_end.setColumn(MAXCOL); NEEDSWORK: need way to set ViMark to MAXCOL
      // put it on the newline
      op_end.setColumn(Util.lineLength(op_end.getLine()));
    }
    G.curbuf.b_op_start.setMark(op_start);
    G.curbuf.b_op_end.setMark(op_end);

    //
    // If we were yanking to the clipboard register, send result to clipboard.
    //
    if (curr == y_regs[CLIPBOARD_REGISTER] && clipboard_available)
    {
	// clip_own_selection();
	clip_gen_set_selection();
    }

    return OK;
  }

  /**
   * put contents of register "regname" into the text.
   * For ":put" command count == -1.
   * flags: PUT_FIXINDENT	make indent look nice
   *	  PUT_CURSEND	leave cursor after end of new text
   *
   * FIX_INDENT not supported, used by mouse and bracket print, [p
   */
  public static void do_put(int regname_, int dir, int count, int flags)
  {
    //StringBuffer        ptr;
    int                 ptr_idx;
    MySegment           oldp;
    StringBuilder        newp;
    int                 yanklen;
    int                 totlen = 0;
    int                 lnum;
    int                 col;
    int                 y_type;
    int                 y_size;

    int                 oldlen;
    int                 y_width;
    int                 vcol;
    int                 delcount;
    int                 incr = 0;
    int                 j;
    block_def           bd;

    char regname = (char)regname_;

    StringBuilder[] y_array;

    final ViFPOS cursor = G.curwin.w_cursor;

    // extra ?
    int			old_lcount = G.curbuf.getLineCount();

    // Adjust register name for "unnamed" in 'clipboard'.
    regname = adjust_clip_reg(regname);
    if (regname == '*')
      clip_get_selection();

    // NEEDSWORK: do_put: there are special registers like: '%', '#', ':',...
    // if (get_spec_reg(regname, &insert_string, &allocated, TRUE))

    if(G.False) {
      // This is the case where insert_string from get_spec_reg is non null
    } else {
	get_yank_register(regname, false);

	y_type = y_current.y_type;
        y_width = y_current.y_width;
	y_size = y_current.y_size;
	y_array = y_current.y_array;
    }

    if (y_type == MLINE) {
      if ((flags & PUT_LINE_SPLIT) != 0) {
        // "p" or "P" in Visual mode: split the lines to put the text in
        // between.
        // Lots of code was replaced by the following.
        // G.curwin.insertChar('\n');
        int currOffset = cursor.getOffset();
        G.curwin.insertNewLine();
        //G.curwin.insertText(cursor.getOffset(), "\n");
        // back up the cursor so it is on the newline
        //cursor.set(tpos);
        G.curwin.w_cursor.set(currOffset);
        dir = FORWARD;
      }
      if ((flags & PUT_LINE_FORWARD) != 0) {
        /* Must be "p" for a Visual block, put lines below the block. */
        cursor.set(G.curbuf.b_visual_end);
        dir = FORWARD;
      }
      // b_op_start/end handle later
      //curbuf->b_op_start = curwin->w_cursor;  /* default for '[ mark */
      //curbuf->b_op_end = curwin->w_cursor;	/* default for '] mark */
    }

    if ((flags & PUT_LINE) != 0) // :put command or "p" in Visual line mode.
      y_type = MLINE;

    if (y_size == 0 || y_array == null) {
      Msg.emsg("Nothing in register "
              + (regname == 0 ? "\"" : transchar(regname)));
      return;
    }


    ViFPOS fpos = cursor.copy();
    int offset = fpos.getOffset();
    int length;
    int new_offset;
    if(y_type == MCHAR) {
      if(dir == FORWARD) {
	// increment position, unless pointing at new line
	if(Util.getCharAt(offset) != '\n') {
	  offset++;
	}
      }
    } else if(y_type == MLINE) {
      // adjust for folding
      lnum = fpos.getLine();
      MutableInt mi = new MutableInt(lnum);
      if(dir == BACKWARD)
        G.curwin.hasFolding(lnum, mi, null);
      else
        G.curwin.hasFolding(lnum, null, mi);
      lnum = mi.getValue();

      // vim: if(dir == FORWARD)
      // vim:    ++lnum;

      // vim for folding does: if(FORW) cursor.setLine(lnum-1) else setLine(lnum)
      fpos.setLine(lnum);

      // jVi uses offsets for the put, offset may have changed if folding
      offset = fpos.getOffset();
      if(dir == FORWARD) {
	offset = G.curbuf.getLineEndOffsetFromOffset(offset);
      } else {
	offset = G.curbuf.getLineStartOffsetFromOffset(offset);
      }
    }

    lnum = fpos.getLine();

    // block mode
    if (y_type == MBLOCK) {
      cursor.set(fpos); // NEEDSWORK: do the blockmode with shadowCursor
      int finishPositionColumn = cursor.getColumn();

      char c = gchar_pos(cursor);
      int endcol2 = 0;

      MutableInt mi1 = new MutableInt();
      MutableInt mi2 = new MutableInt();
      if (dir == FORWARD && c != '\n') {
        getvcol(G.curwin, cursor, null, null, mi1);
        col = mi1.getValue();

        cursor.incColumn();
        ++col;
      } else {
        getvcol(G.curwin, cursor, mi1, null, mi2);
        col = mi1.getValue();
        endcol2 = mi2.getValue();
      }

      bd = new block_def();
      bd.textcol = 0;
      int line = cursor.getLine();
      for (int i = 0; i < y_size; ++i, ++line) {
        int spaces;
        boolean shortline;

        bd.startspaces = 0;
        bd.endspaces = 0;
        vcol = 0;
        delcount = 0;

        // add a new line
        if(line > G.curbuf.getLineCount()) {
          G.curwin.insertText(
                  G.curbuf.getLineEndOffset(G.curbuf.getLineCount()), "\n");
        }
        // get the old line and advance to the position to insert at
        oldp = Util.ml_get(line);
        oldlen = oldp.length() - 1; // -1 to ignore '\n'

        for (ptr_idx = 0; vcol < col && (c = oldp.charAt(ptr_idx)) != '\n'; ) {
          // Count a tab for what it's worth (if list mode not on)
          incr = lbr_chartabsize(c, vcol);
          vcol += incr;
          ptr_idx++;
        }
        bd.textcol = ptr_idx;

        shortline = vcol < col || (vcol == col && c == '\n');

        if (vcol < col) // line too short, padd with spaces
        {
          bd.startspaces = col - vcol;
        } else if (vcol > col) {
          bd.endspaces = vcol - col;
          bd.startspaces = incr - bd.endspaces;
          --bd.textcol;
          delcount = 1;
          if (oldp.charAt(bd.textcol) != TAB) {
                // Only a Tab can be split into spaces.  Other
                // characters will have to be moved to after the
                // block, causing misalignment.
            delcount = 0;
            bd.endspaces = 0;
          }
        }

        yanklen = y_array[i].length();//STRLEN(y_array[i]);

        // calculate number of spaces required to fill right side of block
        spaces = y_width + 1;
        for (j = 0; j < yanklen; j++)
          spaces -= lbr_chartabsize(y_array[i].charAt(j), 0);
        if (spaces < 0)
          spaces = 0;

        // insert the new text
        totlen = count * (yanklen + spaces) + bd.startspaces + bd.endspaces;
        newp = new StringBuilder(); //newp = alloc_check(totlen + oldlen + 1);
        newp.setLength(totlen + oldlen + 1);

        // copy part up to cursor to new line
        ptr_idx = 0;

        mch_memmove(newp, ptr_idx, oldp, 0, bd.textcol);
        ptr_idx += bd.textcol;
        ///may insert some spaces before the new text
        copy_spaces(newp, ptr_idx, bd.startspaces);
        ptr_idx += bd.startspaces;
        // insert the new text
        for (j = 0; j < count; ++j) {
          mch_memmove(newp, ptr_idx, y_array[i], 0, yanklen);
          ptr_idx += yanklen;

          // insert block's trailing spaces only if there's text behind
          if ((j < count - 1 || !shortline) && spaces != 0) {
            copy_spaces(newp, ptr_idx, spaces);
            ptr_idx += spaces;
          }
        }
        // may insert some spaces after the new text
        copy_spaces(newp, ptr_idx, bd.endspaces);
        ptr_idx += bd.endspaces;
        // move the text after the cursor to the end of the line.
        // '- 1' in following because don't want newline
        mch_memmove(newp, ptr_idx, oldp, bd.textcol + delcount,
                (oldlen - bd.textcol - delcount + 1 - 1));
        newp.setLength(STRLEN(newp));
        Util.ml_replace(line, newp);

        //++curwin.w_cursor.lnum;
        //if (i == 0)
        //  curwin.w_cursor.col += bd.startspaces;
        if(i == 0)
          finishPositionColumn += bd.startspaces;
      }

      // Set '[ mark.
      //curbuf->b_op_start = curwin->w_cursor;
      //curbuf->b_op_start.lnum = lnum;
      ViFPOS op_start = cursor.copy(); // to get something to work with
      op_start.set(lnum, finishPositionColumn);
      G.curbuf.b_op_start.setMark(op_start);

      // adjust '] mark
      //curbuf->b_op_end.lnum = curwin->w_cursor.lnum - 1;
      //curbuf->b_op_end.col = bd.textcol + totlen - 1;
      ViFPOS op_end = cursor.copy(); // to get something to work with
      {
        int len;
        int li = line - 1;
        int co = bd.textcol + totlen - 1;

        /* in Insert mode we might be after the NUL, correct for that */
        //len = (colnr_T)STRLEN(ml_get_curline());
        //if (curwin.w_cursor.col > len)
        //  curwin.w_cursor.col = len;
        len = Util.ml_get(li).length() - 1;
        if(co > len)
          co = len;
        op_end.set(li, co);
      }
      G.curbuf.b_op_end.setMark(op_end);

      if ((flags & PUT_CURSEND) != 0) {
        cursor.set(G.curbuf.b_op_end.getLine(),
                   G.curbuf.b_op_end.getColumn() + 1);
      } else {
        //curwin.w_cursor.lnum = lnum;
        cursor.set(lnum, finishPositionColumn);
      }


//        update_topline();
//        if (flags & PUT_CURSEND)
//            update_screen(NOT_VALID);
//        else
//            update_screen(VALID_TO_CURSCHAR);

    } else { // not block mode, fpos still in efect
      String s = y_array[0].toString();
      // NEEDSWORK: HACK for PUT_LINE flag, NOTE: should not need to do
      // (flags&PUT_LINE)!=0 since all MLINE should be terminated by \n
      if(y_type == MLINE // && (flags & PUT_LINE) != 0
         && s.length() != 0 && s.charAt(s.length()-1) != '\n')
        s += '\n';
      if(count > 1) {
        StringBuilder sb = new StringBuilder(s);
        do {
          sb.append(s);
        } while(--count > 1);
        s = sb.toString();
      }
      length = s.length();
      G.curwin.insertText(offset, s);

      ViFPOS startFpos = G.curbuf.createFPOS(offset);
      ViFPOS endFpos = G.curbuf.createFPOS(offset + length - 1);
      G.curbuf.b_op_start.setMark(startFpos);
      G.curbuf.b_op_end.setMark(endFpos);

      // now figure out cursor position
      if(y_type == MCHAR && y_size == 1) {
        G.curwin.w_cursor.set(endFpos);
      } else {
        if((flags & PUT_CURSEND) != 0) {
          endFpos.set(endFpos.getOffset()+1);
          G.curwin.w_cursor.set(endFpos);
          if(y_type == MLINE) {
          } else {
          }
        } else if (y_type == MLINE) {
          beginline(startFpos, BL_WHITE | BL_FIX).copyTo(G.curwin.w_cursor);
        } else {
          G.curwin.w_cursor.set(startFpos);
        }
      }
    }

    msgmore(G.curbuf.getLineCount() - old_lcount);
    G.curwin.w_set_curswant = true;

    /* NEEDSWORK: if ((flags & PUT_CURSEND)
			&& gchar_cursor() == NUL
			&& curwin->w_cursor.col
			&& !(restart_edit || (State & INSERT))) {
      --curwin->w_cursor.col;
    }
    */
  }

  /**
   * join 'count' lines (minimal 2), including u_save()
   */
  static void do_do_join(int count, boolean insert_space, boolean redraw) {
    // if (u_save((linenr_t)(curwin->w_cursor.lnum - 1),
    // (linenr_t)(curwin->w_cursor.lnum + count)) == FAIL)
    // return;

    if (count > 10)
      redraw = false;		    /* don't redraw each small change */
    while (--count > 0) {
      line_breakcheck();
      if (/*got_int ||*/ do_join(insert_space, redraw) == FAIL) {
	Util.beep_flush();
	break;
      }
    }
    // redraw_later(VALID_TO_CURSCHAR);

    /*
     * Need to update the screen if the line where the cursor is became too
     * long to fit on the screen.
     */
    // update_topline_redraw();
  }

  /*
   * Join two lines at the cursor position.
   * "redraw" is TRUE when the screen should be updated.
   *
   * return FAIL for failure, OK ohterwise
   */
  static int do_join(boolean insert_space, boolean redraw) {

    if(G.curwin.w_cursor.getLine()
                              == G.curbuf.getLineCount()) {
	return FAIL;	// can't join on last line
      }
      StringBuilder spaces = new StringBuilder();
      int nextline = G.curwin.w_cursor.getLine() + 1;
      int lastc;

      int offset00 = G.curbuf.getLineStartOffset(nextline);
      int offset01 = offset00 - 1; // points to the '\n' of current line

      MySegment seg = G.curbuf.getLineSegment(nextline);
      int offset02 = offset00 + skipwhite(seg);
      int nextc = Util.getCharAt(offset02);

      if(insert_space) {
	if(offset01 > 0) {
	  // there's a char before first line's '\n'
	  lastc = Util.getCharAt(offset01 - 1); // last char of line
	} else {
	  // at begin file, say lastc is a newline
	  lastc = '\n';
	}
	if(nextc != ')' && lastc != ' ' && lastc != TAB && lastc != '\n') {
	  spaces.append(' ');
	  if(   G.p_js && (lastc == '.' || lastc == '?'
                                     || lastc =='!')) {
	    spaces.append(' ');
	  }
	}
      }
      G.curwin.deleteChar(offset01, offset02);
      if(spaces.length() > 0) {
	G.curwin.insertText(offset01, spaces.toString());
      }
      G.curwin.w_cursor.set(offset01);

      return OK;
  }

  //////////////////////////////////////////////////////////////////
  //
  // "ui.c"
  //

  static void ui_cursor_shape() {
    G.curwin.updateCursor(getCursor());
  }

  //
  // Clipboard stuff
  //

  /** When true, allow the clipboard to be used. */
  private static final boolean clipboard_available = true;
  private static boolean clipboard_owned = false;

  static void clip_gen_set_selection() {
    StringSelection ss = y_regs[CLIPBOARD_REGISTER].getStringSelection();
    Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
    synchronized(clipOwner) {
      clipboard_owned = true;
      LOG.fine("clipboard: clip_gen_set_selection");
      cb.setContents(ss, clipOwner);
    }
  }

  private static final boolean debugClip = false;
  static void clip_get_selection() {
    if(clipboard_owned) {
      // whatever is in the clipboard, we put there. So just return.
      // NEEDSWORK: clip_get_selection, code about clipboard.start/end...
      return;
    }
    Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
    if(debugClip) {
      if(cb.isDataFlavorAvailable(ViManager.VimClipboard2))
          ViManager.println("VimClip available");
      DataFlavor dfa[] = cb.getAvailableDataFlavors();
      Arrays.sort(dfa, new Comparator<DataFlavor>() {
          @Override
          public int compare(DataFlavor df1, DataFlavor df2) {
            return df1.getMimeType().compareTo(df2.getMimeType());
          }
        }

      );
      for (DataFlavor df : dfa) {
        ViManager.println(df.getMimeType());
      }
    }

    Transferable trans = cb.getContents(null);
    if(debugClip && trans.isDataFlavorSupported(ViManager.VimClipboard2)) {
      ViManager.println("VimClip supported");
    }
    String s = "";
    try {
      s = (String)trans.getTransferData(DataFlavor.stringFlavor);
    } catch(IOException e) {
      Util.beep_flush();
    } catch(UnsupportedFlavorException e) {
      Util.beep_flush();
    }
    // NEEDSWORK: use a string reader and transfer to StringBuffer
    get_yank_register('*', false);
    // y_regs[CLIPBOARD_REGISTER].y_array = new StringBuffer(s);
    y_regs[CLIPBOARD_REGISTER].setData(s, getClipboardType(cb));
  }

  private static Integer getClipboardType(Clipboard cb) {
    cb = Toolkit.getDefaultToolkit().getSystemClipboard();
    Transferable t;
    if(cb.isDataFlavorAvailable(ViManager.VimClipboard2)) {
      try {
        InputStream is = (InputStream) cb.getContents(null)
                                      .getTransferData(ViManager.VimClipboard2);

        byte[] data;
        data = new byte[is.available()];
        is.read(data);
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int type = bb.getInt();
        int txtlen = bb.getInt();
        int ucslen = bb.getInt();
        int rawlen = bb.getInt();
        return type;
      } catch (UnsupportedFlavorException ex) { LOG.log(Level.SEVERE, null, ex);
      } catch (IOException ex) { LOG.log(Level.SEVERE, null, ex); }
    }
    return null;
  }

    /**
     * Lost clipboard ownership, implementation of ClibboardOwner.
     */
    @Override
    public void lostOwnership(Clipboard clipboard, Transferable contents) {
      synchronized(clipOwner) {
        clipboard_owned = false;
        LOG.fine("clipboard: lostOwnership");
      }
    }

    /**
     * Get virtual column number of pos.
     *  start: on the first position of this character (TAB, ctrl)
     * cursor: where the cursor is on this character (first char, except for TAB)
     *    end: on the last position of this character (TAB, ctrl)
     *
     * This is used very often, keep it fast!
     *
     * From vim's charset.c
     *
     * I think cursor is wrong for swing.
     *
     * Determine the virtual column positions of the begin and end
     * of the character at the given position. The begin and end may
     * be different when the character is a TAB. The values are returned
     * through the start, end parameters.
     *
     * @param tv the textview to use for fpos.
     * @param fpos the row and column/index of char to determin vcol for
     * @param start store the start vcol here, may be null
     * @param cursor store the cursor vcol here, may be null
     * @param end store the end vcol here, may be null
     */
    public static void getvcol(ViTextView tv,
            ViFPOS fpos,
            MutableInt start,
            MutableInt cursor,
            MutableInt end) {
      getvcol(((TextView)tv).w_buffer, fpos, start, cursor, end);
    }

    public static void getvcol(Buffer buf,
            ViFPOS fpos,
            MutableInt start,
            MutableInt cursor,
            MutableInt end) {
      int incr = 0;
      int vcol = 0;
      char c = 0;

      int ts = buf.b_p_ts;
      MySegment seg = buf.getLineSegment(fpos.getLine());
      for (int col = fpos.getColumn(), ptr = seg.offset; ; --col, ++ptr) {
        c = seg.array[ptr];
        // make sure we don't go past the end of the line
        if (c == '\n') {
          incr = 1;	// NUL at end of line only takes one column
          break;
        }
        // A tab gets expanded, depending on the current column
        if (c == TAB)
          incr = ts - (vcol % ts);
        else {
          //incr = CHARSIZE(c);
          incr = 1; // assuming all chars take up one space.
        }
// #ifdef MULTI_BYTE ... #endif

        if (col == 0)	// character at pos.col
          break;

        vcol += incr;
      }
      if(start != null)
        start.setValue(vcol);
      if(end != null)
        end.setValue(vcol + incr - 1);
      if (cursor != null) {
        if (c == TAB && ((G.State & NORMAL) != 0) // && !wp->w_p_list
        && !(G.VIsual_active && G.p_sel.charAt(0) == 'e'))
          cursor.setValue(vcol + incr - 1);	    // cursor at end
        else
          cursor.setValue(vcol);	    // cursor at start
      }
    }

    private static final MutableInt l1 = new MutableInt();
    private static final MutableInt l2 = new MutableInt();
    private static final MutableInt r1 = new MutableInt();
    private static final MutableInt r2 = new MutableInt();
    /**
     * Get the most left and most right virtual column of pos1 and pos2.
     * Used for Visual block mode.
     */
    static void getvcols(ViFPOS pos1,
            ViFPOS pos2,
            MutableInt left,
            MutableInt right) {
      getvcol(G.curwin, pos1, l1, null, r1);
      getvcol(G.curwin, pos2, l2, null, r2);

      left.setValue(l1.compareTo(l2) < 0 ? l1.getValue() : l2.getValue());
      right.setValue(r1.compareTo(r2) > 0 ? r1.getValue() : r2.getValue());
    }

    /**
     * show the current mode and ruler.
     * <br>
     * If clear_cmdline is TRUE, clear the rest of the cmdline.
     * If clear_cmdline is FALSE there may be a message there that needs to be
     * cleared only if a mode is shown.
     * @return the length of the message (0 if no message).
     */
    static int showmode()       {
      String mode = Edit.VI_MODE_COMMAND;
      int length = 0;
      boolean do_mode = (true/*G.p_smd*/
              && ((G.State & INSERT) != 0 || G.restart_edit != 0
              || G.VIsual_active));
      if(do_mode /*|| Recording*/) {
        // wait a bit before overwriting an important message
        check_for_delay(false);

        if(do_mode) {
          if (G.State == INSERT) mode = Edit.VI_MODE_INSERT;
          else if (G.State == REPLACE) mode = Edit.VI_MODE_REPLACE;
          else if (G.State == VREPLACE) mode = Edit.VI_MODE_VREPLACE;
          else if (G.restart_edit == 'I') mode = Edit.VI_MODE_RESTART_I;
          else if (G.restart_edit == 'R') mode = Edit.VI_MODE_RESTART_R;
          else if (G.restart_edit == 'V') mode = Edit.VI_MODE_RESTART_V;

          if (G.VIsual_active) {
            if (G.VIsual_select) mode = Edit.VI_MODE_SELECT;
            else mode = Edit.VI_MODE_VISUAL;

            // It may be "VISUAL BLOCK" or "VISUAl LINE"
            if (G.VIsual_mode == Util.ctrl('V'))
              mode += " " + Edit.VI_MODE_BLOCK;
            else if (G.VIsual_mode == 'V')
              mode += " " + Edit.VI_MODE_LINE;
          }
        }

      }
      else {
        if(G.curwin.hasSelection())
          mode = ViManager.getFactory().getPlatformSelectionDisplayName();
      }

      // Any "recording" string is handled by the disply function
      // if(G.Recording) {
      //   mode += "recording";
      // }

      G.curwin.getStatusDisplay().displayMode(mode);
      G.clear_cmdline = false;
      return 0;
    }

    static void update_screen(int type) {
      // NEEDSWORK: update_screen: think this is a nop
    }

    static void check_for_delay(boolean check_msg_scroll) {
    }

    //////////////////////////////////////////////////////////////////
    //
    // "undo.c"
    //

    static void u_undo(int count) {
      int			old_lcount = G.curbuf.getLineCount();

      while(count-- > 0) {
        G.curbuf.undo();
      }

      msgmore(G.curbuf.getLineCount() - old_lcount);
    }

    static void u_redo(int count) {
      int			old_lcount = G.curbuf.getLineCount();

      while(count-- > 0) {
        G.curbuf.redo();
      }

      msgmore(G.curbuf.getLineCount() - old_lcount);
    }

    //////////////////////////////////////////////////////////////////
    //
    // "charset.c"
    //


    /**
     * return TRUE if 'c' is a keyword character: Letters and characters from
     * 'iskeyword' option for current buffer.
     */
    static boolean vim_iswordc(char c) {
      return G.curbuf.b_chartab.iswordc(c);
    }

    /**
     * Catch 22: chartab[] can't be initialized before the options are
     * initialized, and initializing options may cause transchar() to be called.
     * When chartab_initialized == FALSE don't use chartab[].
     * <br>
     * NOTE: NEEDSWORK: jbvi modified to never use chartab
     */
    static String transchar(char c) {
      StringBuilder buf = new StringBuilder();

    /* ***************************************************************
    i = 0;
    if (IS_SPECIAL(c))	    // special key code, display as ~@ char
    {
      buf[0] = '~';
      buf[1] = '@';
      i = 2;
      c = K_SECOND(c);
    }
     ******************************************************************/

      if ((! false /*chartab_initialized*/ && ((c >= ' ' && c <= '~')))
      /*|| (chartab[c] & CHAR_IP)*/)    // printable character
      {
        buf.append(c);
      } else {
        transchar_nonprint(buf, c);
      }
      return buf.toString();
    }

    static void transchar_nonprint(StringBuilder buf, char c) {
      if (c <= 0x7f) {				    // 0x00 - 0x1f and 0x7f
      /* *****************************************************************
      if (c == NL) {
        c = NUL;			// we use newline in place of a NUL
      } else if (c == CR && get_fileformat(curbuf) == EOL_MAC) {
        c = NL;		// we use CR in place of  NL in this case
      }
       ********************************************************************/
        buf.append('^').append((char)(c ^ 0x40)); // DEL displayed as ^?
      } else if (c >= ' ' + 0x80 && c <= '~' + 0x80) {    // 0xa0 - 0xfe
        buf.append('|').append((char)(c - 0x80));
      } else {					    // 0x80 - 0x9f and 0xff
        buf.append('~').append((char)((c-0x80) ^ 0x40)); // 0xff displayed as ~?
      }
    }

    /**
     * Return the number of characters 'c' will take on the screen, taking
     * into account the size of a tab.
     * Use a define to make it fast, this is used very often!!!
     * Also see getvcol() below.
     */

    static int lbr_chartabsize(char c, int col) {
      if (c == TAB && (!G.curwin.w_p_list /*|| lcs_tab1*/)) {
        int ts = G.curbuf.b_p_ts;
        return (ts - (col % ts));
      } else {
        // return charsize(c);
        return 1;
      }
    }

/*
 * return the number of characters the string 's' will take on the screen,
 * taking into account the size of a tab
 */
    static int linetabsize(MySegment seg) {
      int col = 0;
      char ch;

      ch = seg.first();
      while(ch != MySegment.DONE) {
        col += lbr_chartabsize(ch, col);
        ch = seg.next();
      }

      return col;
    }

    public static boolean vim_iswhite(char c) {
      return c == ' ' || c == '\t';
    }

    /**
     * Skip over ' ' and '\t', return index, relative to
     * seg.offset, of next non-white.
     */
    static int skipwhite(MySegment seg) {
      return skipwhite(seg, 0);
    }
    static int skipwhite(MySegment seg, int idx) {
      for(; idx < seg.count; idx++) {
        if(!vim_iswhite(seg.array[seg.offset + idx]))
          return idx;
      }
      /*NOTREACHED*/
      throw new RuntimeException("no newline?");
    }

    /**
     * Skip over ' ' and '\t', return index next non-white.
     * This is only used for specialized parsing, not part of main vi engine.
     */
    static int skipwhite(String str, int idx) {
      for(; idx < str.length(); idx++) {
        if( ! vim_iswhite(str.charAt(idx))) {
          return idx;
        }
      }
      return idx;
    }

    /**
     * Skip over not ' ' and '\t', return index next non-white.
     */
    static int skiptowhite(String str, int idx) {
      for(; idx < str.length(); idx++) {
        if(vim_iswhite(str.charAt(idx))) {
          return idx;
        }
      }
      return idx;
    }

    /**
     * Getdigits: Get a number from a string and skip over it.
     * Note: the argument is a pointer to a char_u pointer!
     */

    static int getdigits(String s, int sidx, MutableInt mi) {
      int rval = 0;
      boolean isneg = false;

      char v = s.charAt(sidx);
      if(v == '-' || v == '+') {
        if(v == '-') {
          isneg = true;
        }
        ++sidx;
      }

      while(true) {
        if(sidx >= s.length()) {
          break;
        }
        v = s.charAt(sidx);
        if( ! Util.isdigit(v)) {
          break;
        }
        rval = 10 * rval + v - '0';
        ++sidx;
      }

      if(isneg) {
        rval = -rval;
      }
      mi.setValue(rval);
      return sidx;
    }

    //////////////////////////////////////////////////////////////////
    //
    // "ex_getln.c"
    //

    static boolean cmdline_at_end() {
      return false;
    }

    static boolean cmdline_overstrike() {
      return false;
    }

    //////////////////////////////////////////////////////////////////
    //
    // term.c
    //

    // NEEDSWORK: commandCharacters should be per edit window
    private static String commandCharacters;
    private static boolean ccDirty = false;

    /** update the command characters */
    static void setCommandCharacters(String s) {
      if(!s.equals(commandCharacters))
        ccDirty = true;
      commandCharacters = s;
    }

    /** This is used to do showcommand stuff. */
    public static void out_flush() {
      if(ccDirty) {
        G.curwin.getStatusDisplay().displayCommand(commandCharacters);
      }
      ccDirty = false;
    }

    //////////////////////////////////////////////////////////////////
    //
    // undo/redo stuff
    //

    private static int undoNesting;
    private static int insertUndoNesting;

    static boolean isInUndo() {
        return undoNesting != 0;
    }

    static boolean isInInsertUndo() {
      return insertUndoNesting != 0;
    }

    public static boolean isInAnyUndo() {
      return isInInsertUndo() || isInUndo();
    }

    private static void debugUndo(String tag) {
        if(!G.dbgUndo.getBoolean())
            return;
        G.dbgUndo.printf("%s: nesting: %d, inInsert: %d\n",
                         tag, undoNesting, insertUndoNesting);
    }

    public static void runUndoable(Runnable r) {
        beginUndo();
        try {
            G.curbuf.do_runUndoable(r);
        } finally {
            endUndo();
        }
    }

    //
    // beginUndo and endUndo are used only from runUndoable
    // these methods help control interactions with insertUndo
    //
    private static void beginUndo() {
        debugUndo("{Misc:beginUndo");
        checkUndoThreading();
        if(undoNesting == 0) {
            G.curbuf.do_beginUndo();
        }
        undoNesting++;

    }
    private static void endUndo() {
        undoNesting--;
        if(undoNesting == 0) {
            try {
              G.curbuf.do_endUndo();
            } finally {
              clearUndoThreading();
            }
        }
        debugUndo("}Misc:endUndo");
    }

    static void beginInsertUndo() {
      debugUndo("{Misc:beginInsertUndo");
      if(isInInsertUndo()) LOG.log(Level.SEVERE, "inInsertUndo", new Throwable());
      if(insertUndoNesting == 0) {
          G.curbuf.do_beginInsertUndo();
      }
      insertUndoNesting++;
    }

    /** Note: no guarantee that this is not called without a begin */
    static void endInsertUndo() {
      if(!isInInsertUndo()) LOG.log(
              Level.SEVERE, "!inInsertUndo", new Throwable());
      insertUndoNesting--;
      if(insertUndoNesting == 0) {
          G.curbuf.do_endInsertUndo();
      }
      debugUndo("}Misc:endInsertUndo");
    }

    private static Thread undoThread;
    private static synchronized void checkUndoThreading() {
      // NEEDSWORK: runUndoable check same thread and/or doc
      if(undoThread == null) {
        undoThread = Thread.currentThread();
      } else if(undoThread != Thread.currentThread()) {
        throw new IllegalStateException("undoThread is " + undoThread.getName());
      }
    }

    private static synchronized void clearUndoThreading() {
      // NEEDSWORK: clearUndoThreading verify in control thread?
      undoThread = null;
    }



    private static int[] javaKeyMap;

    /**
     * decode the vi key character embedded
     * in a unicode character and
     * return a kestroke.
     */
    static KeyStroke getKeyStroke(int vikey, int modifiers) {
      //int modifiers = (vikey >> MODIFIER_POSITION_SHIFT) & 0x0f;
      int vikeyIdx = vikey & 0xff;
      if(vikeyIdx < javaKeyMap.length) {
        int k = javaKeyMap[vikeyIdx];
        if(k != 0) {
          return KeyStroke.getKeyStroke(k, modifiers);
        }
      }
      return null;
    }

    /**
     * Translate the vi internal key, see {@link KeyDefs}, into a swing keystroke.
     * @param vikey
     */
    static char xlateViKey(Keymap map, char vikey, int modifiers) {
      if(map == null) {
        return vikey;
      }
      if((vikey & 0xf000) != VIRT && vikey >= 0x20) {
        return vikey;
      }
      KeyStroke ks;
      if(vikey < 0x20) {
        ks = KeyStroke.getKeyStroke(controlKey[vikey], CTRL);
      } else {
        ks = getKeyStroke(vikey, modifiers);
      }
      if(ks == null) {
        return vikey;
      }
      ActionListener al = map.getAction(ks);
      if(al == null) {
        return vikey;
      }
      ViXlateKey xk = (ViXlateKey)ViManager.xlateKeymapAction(al);
      return xk.getXlateKey();
    }

    final private static int[] controlKey = new int[] {
      KeyEvent.VK_AT,
      KeyEvent.VK_A,
      KeyEvent.VK_B,
      KeyEvent.VK_C,
      KeyEvent.VK_D,
      KeyEvent.VK_E,
      KeyEvent.VK_F,
      KeyEvent.VK_G,
      KeyEvent.VK_H,
      KeyEvent.VK_I,
      KeyEvent.VK_J,
      KeyEvent.VK_K,
      KeyEvent.VK_L,
      KeyEvent.VK_M,
      KeyEvent.VK_N,
      KeyEvent.VK_O,
      KeyEvent.VK_P,
      KeyEvent.VK_Q,
      KeyEvent.VK_R,
      KeyEvent.VK_S,
      KeyEvent.VK_T,
      KeyEvent.VK_U,
      KeyEvent.VK_V,
      KeyEvent.VK_W,
      KeyEvent.VK_X,
      KeyEvent.VK_Y,
      KeyEvent.VK_Z,
      KeyEvent.VK_OPEN_BRACKET,
      KeyEvent.VK_BACK_SLASH,
      KeyEvent.VK_CLOSE_BRACKET,
      KeyEvent.VK_CIRCUMFLEX,
      KeyEvent.VK_UNDERSCORE
    };

  /*
   * This method return an array which maps special vi keys, from
   * {@link KeyDefs}, to Java KeyEventt keys, which are turned into key strokes.
   */
  private static int[] initJavaKeyMap() {
    int[] jk = new int[MAX_JAVA_KEY_MAP + 1];

    for(int i = 0; i < jk.length; i++) {
      jk[i] = -1;
    }

    jk[MAP_K_UP] = KeyEvent.VK_UP;
    jk[MAP_K_DOWN] = KeyEvent.VK_DOWN;
    jk[MAP_K_LEFT] = KeyEvent.VK_LEFT;
    jk[MAP_K_RIGHT] = KeyEvent.VK_RIGHT;
    jk[MAP_K_TAB] = KeyEvent.VK_TAB;
    jk[MAP_K_HOME] = KeyEvent.VK_HOME;
    jk[MAP_K_END] = KeyEvent.VK_END;
    // jk[MAP_K_S_UP] = KeyEvent.VK_S_UP;
    // jk[MAP_K_S_DOWN] = KeyEvent.VK_S_DOWN;
    // jk[MAP_K_S_LEFT] = KeyEvent.VK_S_LEFT;
    // jk[MAP_K_S_RIGHT] = KeyEvent.VK_S_RIGHT;
    // jk[MAP_K_S_TAB] = KeyEvent.VK_S_TAB;
    // jk[MAP_K_S_HOME] = KeyEvent.VK_S_HOME;
    // jk[MAP_K_S_END] = KeyEvent.VK_S_END;
    jk[MAP_K_F1] = KeyEvent.VK_F1;
    jk[MAP_K_F2] = KeyEvent.VK_F2;
    jk[MAP_K_F3] = KeyEvent.VK_F3;
    jk[MAP_K_F4] = KeyEvent.VK_F4;
    jk[MAP_K_F5] = KeyEvent.VK_F5;
    jk[MAP_K_F6] = KeyEvent.VK_F6;
    jk[MAP_K_F7] = KeyEvent.VK_F7;
    jk[MAP_K_F8] = KeyEvent.VK_F8;
    jk[MAP_K_F9] = KeyEvent.VK_F9;
    jk[MAP_K_F10] = KeyEvent.VK_F10;
    jk[MAP_K_F11] = KeyEvent.VK_F11;
    jk[MAP_K_F12] = KeyEvent.VK_F12;
    jk[MAP_K_F13] = KeyEvent.VK_F13;
    jk[MAP_K_F14] = KeyEvent.VK_F14;
    jk[MAP_K_F15] = KeyEvent.VK_F15;
    jk[MAP_K_F16] = KeyEvent.VK_F16;
    jk[MAP_K_F17] = KeyEvent.VK_F17;
    jk[MAP_K_F18] = KeyEvent.VK_F18;
    jk[MAP_K_F19] = KeyEvent.VK_F19;
    jk[MAP_K_F20] = KeyEvent.VK_F20;
    jk[MAP_K_F21] = KeyEvent.VK_F21;
    jk[MAP_K_F22] = KeyEvent.VK_F22;
    jk[MAP_K_F23] = KeyEvent.VK_F23;
    jk[MAP_K_F24] = KeyEvent.VK_F24;
    jk[MAP_K_HELP] = KeyEvent.VK_HELP;
    jk[MAP_K_UNDO] = KeyEvent.VK_UNDO;
    jk[MAP_K_BS] = KeyEvent.VK_BACK_SPACE;
    jk[MAP_K_INS] = KeyEvent.VK_INSERT;
    jk[MAP_K_DEL] = KeyEvent.VK_DELETE;
    jk[MAP_K_PAGEUP] = KeyEvent.VK_PAGE_UP;
    jk[MAP_K_PAGEDOWN] = KeyEvent.VK_PAGE_DOWN;
    jk[MAP_K_KPLUS] = KeyEvent.VK_PLUS;
    jk[MAP_K_KMINUS] = KeyEvent.VK_MINUS;
    jk[MAP_K_KDIVIDE] = KeyEvent.VK_DIVIDE;
    jk[MAP_K_KMULTIPLY] = KeyEvent.VK_MULTIPLY;
    jk[MAP_K_KENTER] = KeyEvent.VK_ENTER;
    jk[MAP_K_X_PERIOD] = KeyEvent.VK_PERIOD;
    jk[MAP_K_X_COMMA] = KeyEvent.VK_COMMA;
    jk[MAP_K_SPACE] = KeyEvent.VK_SPACE;

    return jk;
  }

    static class block_def {
      int       startspaces;   /* 'extra' cols of first char */
      int       endspaces;     /* 'extra' cols of first char */
      int       textlen;       /* chars in block */
      int    textstart;    /* pointer to 1st char in block */
      int    textcol;       /* cols of chars (at least part.) in block */
      int    start_vcol;    /* start col of 1st char wholly inside block */
      int    end_vcol;      /* start col of 1st char wholly after block */
      boolean       is_short;      /* TRUE if line is too short to fit in block */
      boolean       is_MAX;       /* TRUE if curswant==MAXCOL when starting */
      boolean       is_EOL;       /* TRUE if cursor is on NUL when starting */
      boolean       is_oneChar;    /* TRUE if block within one character */
      int       pre_whitesp;   /* screen cols of ws before block */
      int       pre_whitesp_c; /* chars of ws before block */
      int    end_char_vcols;/* number of vcols of post-block char */
      int    start_char_vcols; /* number of vcols of pre-block char */
    };

/**
 * prepare a few things for block mode yank/delete/tilde
 *
 * for delete:
 * - textlen includes the first/last char to be (partly) deleted
 * - start/endspaces is the number of columns that are taken by the
 *   first/last deleted char minus the number of columns that have to be
 *   deleted.  for yank and tilde:
 * - textlen includes the first/last char to be wholly yanked
 * - start/endspaces is the number of columns of the first/last yanked char
 *   that are to be yanked.
 */
static void block_prep(OPARG oap, block_def bdp, int lnum, boolean is_del) {
  //System.out.println("block prep: "+ oap.end_vcol +" -> "
  //+ (G.curwin.getLineEndOffset(lnum) - G.curwin.getLineStartOffset(lnum)));
  int   incr = 0;
  MySegment pend;
  MySegment pstart;
  int pend_idx;
  int pstart_idx;
  int prev_pstart_idx;
  int prev_pend_idx;

  bdp.startspaces = 0;
  bdp.endspaces = 0;
  bdp.textlen = 0;
  bdp.textcol = 0;
  bdp.start_vcol = 0;
  bdp.end_vcol = 0;
  bdp.is_short = false;
  bdp.is_oneChar = false;
  bdp.pre_whitesp = 0;
  bdp.pre_whitesp_c = 0;
  bdp.end_char_vcols = 0;
  bdp.start_char_vcols = 0;

  pstart = Util.truncateNewline(Util.ml_get(lnum));

  pstart_idx = 0;
  prev_pstart_idx = 0;
  // pend_idx = 0;
  // prev_pend_idx = 0;

  while (bdp.start_vcol < oap.start_vcol && pstart_idx < pstart.length()) {
    /* Count a tab for what it's worth (if list mode not on) */
    incr = lbr_chartabsize(pstart.charAt(pstart_idx), bdp.start_vcol);
    bdp.start_vcol += incr;
    ++bdp.textcol;
    if (vim_iswhite(pstart.charAt(pstart_idx))) {
      bdp.pre_whitesp += incr;
      bdp.pre_whitesp_c++;
    } else {
      bdp.pre_whitesp = 0;
      bdp.pre_whitesp_c = 0;
    }
    prev_pstart_idx = pstart_idx;
    pstart_idx++;//++pstart;
  }
  bdp.start_char_vcols = incr;
  if (bdp.start_vcol < oap.start_vcol) // line too short
  {
    bdp.end_vcol = bdp.start_vcol;
    bdp.is_short = true;
    if (!is_del || oap.op_type == OP_APPEND)
      bdp.endspaces = oap.end_vcol - oap.start_vcol + 1;
  } else {
    bdp.startspaces = bdp.start_vcol - oap.start_vcol;
    if (is_del && bdp.startspaces != 0)
      bdp.startspaces = bdp.start_char_vcols - bdp.startspaces;
    pend = pstart;
    pend_idx = pstart_idx;
    bdp.end_vcol = bdp.start_vcol;
    if (bdp.end_vcol > oap.end_vcol) // it's all in one character
    {
      bdp.is_oneChar = true;
      if (oap.op_type == OP_INSERT)
        bdp.endspaces = bdp.start_char_vcols - bdp.startspaces;
      else if (oap.op_type == OP_APPEND) {
        bdp.startspaces += oap.end_vcol - oap.start_vcol + 1;
        bdp.endspaces = bdp.start_char_vcols - bdp.startspaces;
      } else {
        bdp.startspaces = oap.end_vcol - oap.start_vcol + 1;
        if (is_del && oap.op_type != OP_LSHIFT) {
          // just putting the sum of those two into
          // bdp->startspaces doesn't work for Visual replace,
          // so we have to split the tab in two
          bdp.startspaces = bdp.start_char_vcols
                  - (bdp.start_vcol - oap.start_vcol);
          bdp.endspaces = bdp.end_vcol - oap.end_vcol - 1;
        }
      }
    } else {
      prev_pend_idx = pend_idx;
      while (bdp.end_vcol <= oap.end_vcol && pend_idx < pend.length()) {
        /* Count a tab for what it's worth (if list mode not on) */
        prev_pend_idx = pend_idx;
        incr = lbr_chartabsize(pend.charAt(pend_idx), bdp.end_vcol);
        bdp.end_vcol += incr;
        pend_idx++;
      }
      if (bdp.end_vcol <= oap.end_vcol
              && (!is_del
              || oap.op_type == OP_APPEND
              || oap.op_type == OP_REPLACE)) // line too short
      {
        bdp.is_short = true;
        // Alternative: include spaces to fill up the block.
        // Disadvantage: can lead to trailing spaces when the line is
        // short where the text is put */
        // if (!is_del || oap->op_type == OP_APPEND) //
        if (oap.op_type == OP_APPEND /*|| virtual_op*/)
          bdp.endspaces = oap.end_vcol - bdp.end_vcol
                  + (oap.inclusive ? 1 : 0);
        else
          bdp.endspaces = 0; // replace doesn't add characters
      } else if (bdp.end_vcol > oap.end_vcol) {
        bdp.endspaces = bdp.end_vcol - oap.end_vcol - 1;
        if (!is_del && bdp.endspaces != 0) {
          bdp.endspaces = incr - bdp.endspaces;
          if (pend_idx != pstart_idx)
            pend_idx = prev_pend_idx;
        }
      }
    }
    bdp.end_char_vcols = incr;
    if (is_del && bdp.startspaces != 0)
      pstart_idx = prev_pstart_idx;
    bdp.textlen = pend_idx - pstart_idx;
  }
  bdp.textcol = pstart_idx;
  bdp.textstart = pstart_idx; // TODO_VIS textstart not pointer review usage carefully
}

static boolean	hexupper = false;	/* 0xABC */
/**
 * add or subtract 'Prenum1' from a number in a line
 * 'command' is CTRL-A for add, CTRL-X for subtract
 *
 * return FAIL for failure, OK otherwise
 *
 * from vim7 ops.c
 */
static int
do_addsub(final char command, final int Prenum1)
{
    final MutableInt rval = new MutableInt();
    Misc.runUndoable(new Runnable() {
        @Override
        public void run() {
          rval.setValue(op_do_addsub(command, Prenum1));
        }
    });
    return rval.getValue();
}

private static int
op_do_addsub(char command, int Prenum1)
{
    int		col;
    StringBuilder	buf1 = new StringBuilder();
    String	buf2;
    char	hex;		/* 'X' or 'x': hex; '0': octal */
    int         n;
    int		oldn;
    MySegment	ptr;
    char	c;
    int		length;		/* character length of the number */
    int		todel;
    boolean	dohex;
    boolean	dooct;
    boolean	doalp;
    char	firstdigit;
    boolean	negative;
    boolean	subtract;

    dohex = G.curbuf.b_p_nf.contains(NF_HEX);
    dooct = G.curbuf.b_p_nf.contains(NF_OCTAL);
    doalp = G.curbuf.b_p_nf.contains(NF_ALPHA);

    ptr = ml_get_curline();
    //RLADDSUBFIX(ptr);

    /*
     * First check if we are on a hexadecimal number, after the "0x".
     */
    col = G.curwin.w_cursor.getColumn();
    if (dohex)
	while (col > 0 && isxdigit(ptr.charAt(col)))
	    --col;
    if (       dohex
	    && col > 0
	    && (ptr.charAt(col) == 'X'
		|| ptr.charAt(col) == 'x')
	    && ptr.charAt(col - 1) == '0'
	    && isxdigit(ptr.charAt(col + 1)))
    {
	/*
	 * Found hexadecimal number, move to its start.
	 */
	--col;
    }
    else
    {
	/*
	 * Search forward and then backward to find the start of number.
	 */
	col = G.curwin.w_cursor.getColumn();

	while (col < ptr.length()
                && ptr.charAt(col) != '\n'
		&& !isdigit(ptr.charAt(col))
		&& !(doalp && ascii_isalpha(ptr.charAt(col))))
	    ++col;

	while (col > 0
		&& isdigit(ptr.charAt(col - 1))
		&& !(doalp && ascii_isalpha(ptr.charAt(col))))
	    --col;
    }

    /*
     * If a number was found, and saving for undo works, replace the number.
     */
    firstdigit = ptr.charAt(col);
    //RLADDSUBFIX(ptr);
    if ((!isdigit(firstdigit) && !(doalp && ascii_isalpha(firstdigit)))
	    || u_save_cursor() != OK)
    {
	beep_flush();
	return FAIL;
    }

    /* get ptr again, because u_save() may have changed it */
    ptr = ml_get_curline();
    //RLADDSUBFIX(ptr);

    if (doalp && ascii_isalpha(firstdigit))
    {
	/* decrement or increment alphabetic character */
	if (command == Util.ctrl('x'))
	{
	    if (CharOrd(firstdigit) < Prenum1)
	    {
		if (isupper(firstdigit))
		    firstdigit = 'A';
		else
		    firstdigit = 'a';
	    }
	    else
		firstdigit -= Prenum1;
	}
	else
	{
	    if (26 - CharOrd(firstdigit) - 1 < Prenum1)
	    {
		if (isupper(firstdigit))
		    firstdigit = 'Z';
		else
		    firstdigit = 'z';
	    }
	    else
		firstdigit += Prenum1;
	}
	G.curwin.w_cursor.setColumn(col);
	del_char(false);
	ins_char(firstdigit);
    }
    else
    {
	negative = false;
	if (col > 0 && ptr.charAt(col - 1) == '-')	    /* negative number */
	{
	    --col;
	    negative = true;
	}

	/* get the number value (unsigned) */
        MutableInt pHex = new MutableInt();
        MutableInt pLength = new MutableInt();
        MutableInt pN = new MutableInt();

	vim_str2nr(ptr, col, pHex, pLength,
                   dooct ? TRUE : FALSE, dohex ? TRUE : FALSE, null, pN);
        hex = (char)pHex.getValue();
        length = pLength.getValue();
        n = pN.getValue();

	/* ignore leading '-' for hex and octal numbers */
	if (hex != 0 && negative)
	{
	    ++col;
	    --length;
	    negative = false;
	}

	/* add or subtract */
	subtract = false;
	if (command == Util.ctrl('x'))
	    subtract ^= true;
	if (negative)
	    subtract ^= true;

	oldn = n;
	if (subtract)
	    n -= Prenum1;
	else
	    n += Prenum1;

	/* handle wraparound for decimal numbers */
	if (hex == 0)
	{
	    if (subtract)
	    {
		if (n > oldn)
		{
		    n = 1 + (n ^ -1);
		    negative ^= true;
		}
	    }
	    else /* add */
	    {
		if (n < oldn)
		{
		    n = (n ^ -1);
		    negative ^= true;
		}
	    }
	    if (n == 0)
		negative = false;
	}

	/*
	 * Delete the old number.
	 */
	G.curwin.w_cursor.setColumn(col);
	todel = length;
	c = gchar_cursor();
	/*
	 * Don't include the '-' in the length, only the length of the part
	 * after it is kept the same.
	 */
	if (c == '-')
	    --length;
	while (todel-- > 0)
	{
	    if (c < 0x100 && isalpha(c))
	    {
              hexupper = isupper(c);
	    }
	    /* del_char() will mark line needing displaying */
	    del_char(false);
	    c = gchar_cursor();
	}

	/*
	 * Prepare the leading characters in buf1[].
	 * When there are many leading zeros it could be very long.  Allocate
	 * a bit too much.
	 */
//	buf1 = alloc((unsigned)length + NUMBUFLEN);
//	if (buf1 == null)
//	    return FAIL;
//	ptr = buf1;
	if (negative)
	{
            buf1.append('-');
	}
	if (hex != 0)
	{
            buf1.append('0');
	    --length;
	}
	if (hex == 'x' || hex == 'X')
	{
            buf1.append(hex);
	    --length;
	}

	/*
	 * Put the number characters in buf2[].
	 */
	if (hex == 0)
            buf2 = String.format("%d", n); //sprintf(buf2, "%lu", n);
	else if (hex == '0')
	    buf2 = String.format("%o", n); //sprintf(buf2, "%lo", n);
	else if (hex != 0 && hexupper)
	    buf2 = String.format("%X", n); //sprintf(buf2, "%lX", n);
	else
	    buf2 = String.format("%x", n); //sprintf(buf2, "%lx", n);
	length -= buf2.length(); //STRLEN(buf2);

	/*
	 * Adjust number of zeros to the new number of digits, so the
	 * total length of the number remains the same.
	 * Don't do this when
	 * the result may look like an octal number.
	 */
	if (firstdigit == '0' && !(dooct && hex == 0))
	    while (length-- > 0)
		buf1.append('0'); //*ptr++ = '0';
	//*ptr = NUL;
	buf1.append(buf2); //STRCAT(buf1, buf2);
	//ins_str(buf1.toString());		/* insert the new number */
        G.curbuf.insertText(G.curwin.w_cursor.getOffset(), buf1.toString());
	//vim_free(buf1);
    }
    G.curwin.w_cursor.decColumn();
    G.curwin.w_set_curswant = true;
//#ifdef FEAT_RIGHTLEFT...
    return OK;
}

    static void op_insert(OPARG oap, int count1) {
      if(!valid_op_range(oap))
        return;
      int pre_textlen;
      block_def   bd = new block_def();
      final ViFPOS cursor = G.curwin.w_cursor;
      int i;

      /* edit() changes this - record it for OP_APPEND */
      bd.is_MAX = (G.curwin.w_curswant == MAXCOL);

      /* vis block is still marked. Get rid of it now. */
      cursor.setLine(oap.start.getLine());
      update_screen(INVERTED);

      if (oap.block_mode) {
        /* Get the info about the block before entering the text */
        block_prep(oap, bd, oap.start.getLine(), true);
        //(long)STRLEN(ml_get(oap.start.lnum));
        CharSequence firstline = Util.ml_get(oap.start.getLine())
                .subSequence(bd.textcol, Util.lineLength(oap.start.getLine()));

        if (oap.op_type == OP_APPEND)
          firstline = firstline.subSequence(bd.textlen, firstline.length());
        pre_textlen = firstline.length();

        StateOpSplit.acquire(StateSplitOwner.SPLIT_INSERT);
        stateOpSplit.oap = oap.copy();
        stateOpSplit.bd = bd;
        stateOpSplit.pre_textlen = pre_textlen;
      }

      if (oap.op_type == OP_APPEND) {
        if (oap.block_mode) {
          /* Move the cursor to the character right of the block. */
          G.curwin.w_set_curswant = true;
          int tcol = cursor.getColumn();
          MySegment seg = Util.ml_get_curline();
          while (seg.array[seg.offset + tcol] != '\n'
                  && (tcol < bd.textcol + bd.textlen))
            tcol++;
          cursor.setColumn(tcol);
          if (bd.is_short && !bd.is_MAX) {
                    /* First line was too short, make it longer and adjust the
                     * values in "bd". */
            if (Normal.u_save_cursor() == FAIL)
              return;
            for (i = 0; i < bd.endspaces; ++i)
              ins_char(' ');
            bd.textlen += bd.endspaces;
          }
        } else {
          cursor.set(oap.end);//.w_cursor = oap.end;
          check_cursor_col();
          /* Works just like an 'i'nsert on the next character. */
          if (!Util.lineempty(cursor.getLine())
                  && oap.start_vcol != oap.end_vcol)
            inc_cursor();
        }
      }

      Edit.edit(NUL, false, count1);
      if(!Normal.editBusy)
        StateOpSplit.release();
    }

    static void finishOpInsert() {
      OPARG       oap = stateOpSplit.oap;
      block_def   bd = stateOpSplit.bd;
      int         pre_textlen = stateOpSplit.pre_textlen;
      StateOpSplit.release();

      int		offset;
      int		linenr;
      int		ins_len;
      CharSequence	firstline;
      String      ins_text;
      StringBuilder newp;
      MySegment   oldp;


  /* if user has moved off this line, we don't know what to do, so do
  nothing */
      if (G.curwin.w_cursor.getLine() != oap.start.getLine())
        return;

      if (oap.block_mode) {
        block_def bd2 = new block_def();

    /*
     * Spaces and tabs in the indent may have changed to other spaces and
     * tabs.  Get the starting column again and correct the lenght.
     * Don't do this when "$" used, end-of-line will have changed.
     */
        block_prep(oap, bd2, oap.start.getLine(), true);
        if (!bd.is_MAX || bd2.textlen < bd.textlen) {
          if (oap.op_type == OP_APPEND) {
            pre_textlen += bd2.textlen - bd.textlen;
            if (bd2.endspaces != 0)
              --bd2.textlen;
          }
          bd.textcol = bd2.textcol;
          bd.textlen = bd2.textlen;
        }

    /*
     * Subsequent calls to ml_get() flush the firstline data - take a
     * copy of the required string.
     */
        firstline = Util.ml_get(oap.start.getLine())
                .subSequence(bd.textcol, Util.lineLength(oap.start.getLine()));

        if (oap.op_type == OP_APPEND)
          firstline = firstline.subSequence(bd.textlen, firstline.length());
        if ((ins_len = firstline.length() - pre_textlen) > 0) {
          //vim_strnsave(firstline, (int)ins_len);
          ins_text = firstline.subSequence(0, ins_len).toString();
          if (ins_text != null) {
            /* block handled here */
            //if (u_save(oap.start.getLine(), (oap.end.getLine() + 1)) == OK)
            block_insert(oap, ins_text, (oap.op_type == OP_INSERT), bd);

            G.curwin.w_cursor.setColumn(oap.start.getColumn());
            adjust_cursor();
            //check_cursor();
            //vim_free(ins_text);
          }
        }
      }
    }

    static void append_spaces(StringBuilder sb, int len)
    {
      while(len-- > 0)
        sb.append(' ');
    }

    static void append_chars(StringBuilder dst, int len, char c) {
      while(len-- > 0)
        dst.append(c);
    }

    static void mch_memmove(StringBuilder dst, int dstIndex,
            CharSequence src, int srcIndex,
            int len) {
      // overlap, copy backwards
      if(src == dst && dstIndex > srcIndex && dstIndex < srcIndex + len) {
        dstIndex += len;
        srcIndex += len;
        while(len-- > 0)
          dst.setCharAt(--dstIndex, src.charAt(--srcIndex));
      } else                // copy forwards
        while(len-- > 0)
          dst.setCharAt(dstIndex++, src.charAt(srcIndex++));
    }

    static void copy_spaces(StringBuilder dst, int index, int len) {
      while(len-- > 0)
        dst.setCharAt(index++, ' ');
    }

    static int STRLEN(StringBuilder sb) {
      int len = sb.indexOf("\0");
      return len >= 0 ? len : sb.length();
    }

    static void copy_chars(StringBuilder dst, int index, int len, char c) {
      while(len-- > 0)
        dst.setCharAt(index++, c);
    }

    static boolean blockOpSwapText = true;
/**
 * Insert string "s" (b_insert ? before : after) block :AKelly
 * Caller must prepare for undo.
 */
  static void block_insert(OPARG oap, String s, boolean b_insert, block_def bdp) {
    if(!blockOpSwapText || oap.line_count < 5) {
      block_insertInplace(oap, s, b_insert, bdp);
      return;
    }

    //
    // Profiling shows that almost all the time is spent in Document.<op>.
    // This modified algorithm build a new string result consisting of all
    // the modified lines and replaces them in a single document op.
    //
    // This cuts the time in half (still around 90% in Document),
    // still long but...
    //

    int		p_ts;
    int		count = 0;	// extra spaces to replace a cut TAB
    int		spaces = 0;	// non-zero if cutting a TAB
    int 	offset;		// pointer along new line
    int 	s_len;		// STRLEN(s)
    MySegment   oldp;           // new, old lines
    int		oldstate = G.State;

    int lnum = oap.start.getLine() + 1; // frist line done by edit
    int endLine = oap.end.getLine() - 1; // don't do last line in this loop

    G.State = INSERT;		// don't want REPLACE for State
    s_len = s.length();

    int startOffset = G.curbuf.getLineStartOffset(lnum);
    int endOffset = G.curbuf.getLineEndOffset(endLine);
    StringBuilder sb = new StringBuilder(endOffset - startOffset
            + s_len * (endLine - lnum + 1));

    for (; lnum <= endLine; lnum++) {
      block_prep(oap, bdp, lnum, true);

      oldp = Util.ml_get(lnum);

      if (bdp.is_short && b_insert) {
        sb.append(oldp);
        continue;	// OP_INSERT, line ends before block start
      }

      count = 0;
      if (b_insert) {
        p_ts = bdp.start_char_vcols;
        spaces = bdp.startspaces;
        if (spaces != 0)
          count = p_ts - 1; // we're cutting a TAB
        offset = bdp.textcol;
      } else // append
      {
        p_ts = bdp.end_char_vcols;
        if (!bdp.is_short) // spaces = padding after block
        {
          spaces = (bdp.endspaces != 0 ? p_ts - bdp.endspaces : 0);
          if (spaces != 0)
            count = p_ts - 1; // we're cutting a TAB
          offset = bdp.textcol + bdp.textlen - (spaces != 0 ? 1 : 0);
        } else // spaces = padding to block edge
        {
          // if $ used, just append to EOL (ie spaces==0)
          if (!bdp.is_MAX)
            spaces = (oap.end_vcol - bdp.end_vcol) + 1;
          count = spaces;
          offset = bdp.textcol + bdp.textlen;
        }
      }

      //if(spaces == 0) {
      //  // No splitting or padding going on, can simply insert the string.
      //  // Profiling shows that 45% of time spent in Document.remove
      //  // and 50% in Document.insertString, with this case don't need remove
      //  // Should almost double the performance
      //  G.curwin.insertText(G.curbuf.getLineStartOffset(lnum) + offset, s);
      //} else

      /* copy up to shifted part */
      sb.append(oldp, 0, offset);
      int oldp_idx = offset;

      // insert pre-padding
      append_spaces(sb, spaces);

      // copy the new text
      sb.append(s);

      if (spaces != 0 && !bdp.is_short) {
        // insert post-padding
        append_spaces(sb, p_ts - spaces);
        // We're splitting a TAB, don't copy it.
        oldp_idx++;
        // We allowed for that TAB, remember this now
      }

      // Copy the rest of the line
      sb.append(oldp, oldp_idx, oldp.length());

    } // for all lnum except first and last

    // All done with the big chunk.
    // delete the trailing '\n'
    sb.deleteCharAt(sb.length()-1);
    G.curbuf.replaceString(startOffset, endOffset-1, sb.toString());

    // do the last line
    ++endLine;
    block_insertInplace(oap, s, b_insert, bdp, endLine, endLine);

    // changed_lines(oap.start.lnum + 1, 0, oap.end.lnum + 1, 0L);

    G.State = oldstate;
  }

  private static void block_insertInplace(
          OPARG oap, String s, boolean b_insert, block_def bdp) {
    // The first line has already been handled by edit
    block_insertInplace(oap, s, b_insert, bdp,
                        oap.start.getLine() + 1, oap.end.getLine());
  }

  private static void block_insertInplace(
          OPARG oap, String s, boolean b_insert, block_def bdp,
          int startLine, int endLine) {
    int		p_ts;
    int		count;		// extra spaces to replace a cut TAB
    int		spaces = 0;	// non-zero if cutting a TAB
    int 	offset;		// pointer along new line
    int 	s_len;		// STRLEN(s)
    MySegment   oldp;           // new, old lines
    StringBuilder newp;
    int 	lnum;		// loop var
    int		oldstate = G.State;

    G.State = INSERT;		// don't want REPLACE for State
    s_len = s.length();

    for (lnum = startLine; lnum <= endLine; lnum++) {
      block_prep(oap, bdp, lnum, true);
      if (bdp.is_short && b_insert)
        continue;	// OP_INSERT, line ends before block start

      oldp = Util.ml_get(lnum);

      count = 0;
      if (b_insert) {
        p_ts = bdp.start_char_vcols;
        spaces = bdp.startspaces;
        if (spaces != 0)
          count = p_ts - 1; // we're cutting a TAB
        offset = bdp.textcol;
      } else // append
      {
        p_ts = bdp.end_char_vcols;
        if (!bdp.is_short) // spaces = padding after block
        {
          spaces = (bdp.endspaces != 0 ? p_ts - bdp.endspaces : 0);
          if (spaces != 0)
            count = p_ts - 1; // we're cutting a TAB
          offset = bdp.textcol + bdp.textlen - (spaces != 0 ? 1 : 0);
        } else // spaces = padding to block edge
        {
          // if $ used, just append to EOL (ie spaces==0)
          if (!bdp.is_MAX)
            spaces = (oap.end_vcol - bdp.end_vcol) + 1;
          count = spaces;
          offset = bdp.textcol + bdp.textlen;
        }
      }

      if(spaces == 0) {
        // No splitting or padding going on, can simply insert the string.
        // Profiling shows that 45% of time spent in Document.remove
        // and 50% in Document.insertString, with this case don't need remove
        // Should almost double the performance
        G.curwin.insertText(G.curbuf.getLineStartOffset(lnum) + offset, s);
        offset += s_len;
      } else {
        newp = new StringBuilder();
        //newp = alloc_check((unsigned)(STRLEN(oldp)) + s_len + count + 1);
        newp.setLength(oldp.length() - 1 + s_len + count);

        /* copy up to shifted part */
        mch_memmove(newp, 0, oldp, 0, offset);
        //oldp += offset;
        int oldp_idx = 0;
        oldp_idx += offset;

        //int newp_idx = 0;
        //newp_idx += offset;

        // insert pre-padding
        copy_spaces(newp, offset, spaces);

        // copy the new text
        mch_memmove(newp, offset + spaces, s, 0, s_len);
        offset += s_len;

        if (spaces != 0 && !bdp.is_short) {
          // insert post-padding
          copy_spaces(newp, offset + spaces, p_ts - spaces);
          // We're splitting a TAB, don't copy it.
          oldp_idx++;
          // We allowed for that TAB, remember this now
          count++;
        }

        if (spaces > 0)
          offset += count;
        mch_memmove(newp, offset,
                    oldp, oldp_idx,
                    (oldp.length() - 1) - oldp_idx);

        newp.setLength(STRLEN(newp));
        Util.ml_replace(lnum, newp);
      }

      if (lnum == oap.end.getLine()) {
          // Set "']" mark to the end of the block instead of the end of
          // the insert in the first line.
        ViFPOS op_end = oap.end.copy();
        op_end.setColumn(offset);
        G.curbuf.b_op_end.setMark(op_end);
      }
    } // for all lnum

    // changed_lines(oap.start.lnum + 1, 0, oap.end.lnum + 1, 0L);

    G.State = oldstate;
  }
}

// vi: sw=2 ts=8
