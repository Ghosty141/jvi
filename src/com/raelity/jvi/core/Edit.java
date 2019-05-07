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

import java.util.Stack;

import com.raelity.jvi.ViBuffer;
import com.raelity.jvi.ViFPOS;
import com.raelity.jvi.ViMark;
import com.raelity.jvi.ViTextView;
import com.raelity.jvi.core.lib.Messages;
import com.raelity.jvi.core.lib.NotSupportedException;
import com.raelity.jvi.lib.MutableBoolean;
import com.raelity.jvi.lib.MutableInt;
import com.raelity.jvi.manager.ViManager;
import com.raelity.text.TextUtil.MySegment;

import static com.raelity.jvi.core.GetChar.*;
import static com.raelity.jvi.core.Misc.*;
import static com.raelity.jvi.core.Misc01.*;
import static com.raelity.jvi.core.Normal.*;
import static com.raelity.jvi.core.Options.*;
import static com.raelity.jvi.core.Util.*;
import static com.raelity.jvi.core.lib.Constants.*;
import static com.raelity.jvi.core.lib.Constants.FDO.*;
import static com.raelity.jvi.core.lib.KeyDefs.*;

public class Edit {

  public static final String VI_MODE_COMMAND = "";
  public static final String VI_MODE_INSERT = "INSERT";
  public static final String VI_MODE_REPLACE = "REPLACE";
  public static final String VI_MODE_VREPLACE = "VREPLACE";
  public static final String VI_MODE_RESTART_I = "(insert)";
  public static final String VI_MODE_RESTART_R = "(replace)";
  public static final String VI_MODE_RESTART_V = "(vreplace)";

  public static final String VI_MODE_SELECT = "SELECT";
  public static final String VI_MODE_VISUAL = "VISUAL";
  public static final String VI_MODE_BLOCK = "BLOCK";
  public static final String VI_MODE_LINE = "LINE";

  private static DynamicMark Insstart;
  static int Insstart_textlen;	// length of line when insert started

  static final int BACKSPACE_CHAR		= 1;
  static final int BACKSPACE_WORD		= 2;
  static final int BACKSPACE_WORD_NOT_SPACE	= 3;
  static final int BACKSPACE_LINE		= 4;

  //
  // Edit state information
  //

  static boolean need_redraw; // GET RID OF
  static int did_restart_edit;
  static MutableInt count;
  static boolean doingBackspace; // HACK to assist magic redo
  private static MutableBoolean inserted_space = new MutableBoolean(false);

  private static String last_insert;
  private static int last_insert_skip;
  private static int new_insert_skip;
  private static int old_indent;
  private static boolean doESCkey;

  static int Insstart_blank_vcol;

  static final boolean p_sta = false; // At least for now...

  private Edit() { }

  /**
   * There are commands in edit mode, such as ^V and ^R, that
   * require one or more arugment chars.
   */
  static interface HandleNextChar {
    // Invoked to handle the next char of a multichar command,
    // if zero returned then return, else
    // do regular processing on returned char.
    char go(char c) throws NotSupportedException;
  }
  /**
   * Mutli char processings is contained in handleNextChar.
   */
  static HandleNextChar handleNextChar;

  /**
   * reset is invoked when edit mode is exitted, may be abrupt.
   */
  static void reset() {
    Insstart = null;
    handleNextChar = null;
    G.editPutchar = 0;
  }

  /**
   * @return copy of the current start of insertion
   */
  static ViFPOS getInsstart() {
    return Insstart == null
            ? null
            : G.curbuf.createFPOS(Insstart.getOffset());
  }

  static int getInsstartOriginalOffset() {
    return Insstart.getOffset() + Insstart.getOriginalColumnDelta();
  }

  static boolean canEdit() {
    return canEdit(G.curwin, G.curbuf, G.curwin.getCaretPosition());
  }

  public static boolean canEdit(final ViTextView tv, ViBuffer buf, int offset) {
    if(buf.isGuarded(offset) || !tv.isEditable()) {
      buf.readOnlyError(tv);
      return false;
    }
    return true;
  }

  /**
   * edit(): Start inserting text.
   *<ul>
   *<li> "cmdchar" can be:</li>
   *<li> 'i'	normal insert command</li>
   *<li> 'a'	normal append command</li>
   *<li> 'R'	replace command</li>
   *<li> 'r'	"r<CR>" command: insert one <CR>.
   *		Note: count can be > 1, for redo,
   *		but still only one <CR> is inserted.
   *		The <Esc> is not used for redo.</li>
   *<li> 'g'	"gI" command.</li>
   *<li> 'V'	"gR" command for Virtual Replace mode.</li>
   *<li> 'v'	"gr" command for single character Virtual Replace mode.</li>
   *</ul>
   *
   * This function is not called recursively.  For CTRL-O commands, it returns
   * and lets the caller handle the Normal-mode command.
   *
   * @return true if a CTRL-O command caused the return (insert mode pending).
   */
  static void edit(char cmdchar, boolean startln, int count_arg) {
    char c;
    boolean did_backspace;

    if( ! Misc.isInAnyUndo()) {
      ViManager.dumpStack("In edit with no undo pending");
    }
    if( ! Normal.editBusy) {
      if(!canEdit())
        return;
      Normal.editBusy = true;
      count = new MutableInt(count_arg);

      // clear any selection so a character insert doesn't do more than wanted
      G.curwin.clearSelection();

      need_redraw = false; // GET RID OF
      did_restart_edit = G.restart_edit;
      c = 0;
      did_backspace = false;
      inserted_space.setValue(false);

      //Insstart = G.curwin.w_cursor.copy();
      Insstart = new DynamicMark(G.curbuf, G.curwin.w_cursor);
      if(startln)
        Insstart.setColumn(0);

      Insstart_textlen = Misc.linetabsize(Util.ml_get_curline());
      Insstart_blank_vcol = MAXCOL;

      if (!G.did_ai)
        G.ai_col = 0;

      if (cmdchar != NUL && G.restart_edit == 0) {
        GetChar.ResetRedobuff();
        GetChar.AppendNumberToRedobuff(count.getValue());
        if (cmdchar == 'V' || cmdchar == 'v') {
          /* "gR" or "gr" command */
          GetChar.AppendCharToRedobuff('g');
          GetChar.AppendCharToRedobuff((cmdchar == 'v') ? 'r' : 'R');
        } else {
          GetChar.AppendCharToRedobuff(cmdchar);
          if (cmdchar == 'g')		    /* "gI" command */
            GetChar.AppendCharToRedobuff('I');
          else if (cmdchar == 'r')	    /* "r<CR>" command */
            count.setValue(1);	    /* insert only one <CR> */
        }
      }

      if(cmdchar == 'R') {
        G.State = REPLACE;
      }
//      NEVER ENTER VREPLACE
//      else if (cmdchar == 'V' || cmdchar == 'v') {
//        G.State = VREPLACE;
//      }
      else {
        G.State = INSERT;
      }

      GetChar.startInputModeRedobuff();

      Normal.clear_showcmd();

      //
      // Handle restarting Insert mode.
      // Don't do this for CTRL-O . (repeat an insert): we get here with
      // restart_edit non-zero, and something in the stuff buffer.
      //
      if (G.restart_edit != 0 && GetChar.stuff_empty()) {
        // stuff...
        throw new RuntimeException("jVi: restart_edit non-zero");
      } else {
        arrow_used = false;
        // o_eol = FALSE;
      }

      // The cursor line is not in a closed fold, unless 'insertmode' is set or
      // restarting.
      if (!p_im && did_restart_edit == 0)
        foldOpenCursor();

      int i = 0;
      if(   G.p_smd) {
        i = Misc.showmode();
      }

      if (did_restart_edit == 0) {
        Misc.change_warning(i + 1);
      }

      Misc.ui_cursor_shape();

      //
      // Get the current length of the redo buffer, those characters have to be
      // skipped if we want to get to the inserted characters.
      //
      String ptr = GetChar.get_inserted();
      if (ptr == null)
        new_insert_skip = 0;
      else
      {
        new_insert_skip = ptr.length();
      }

      old_indent = 0;

      return;
    }

    doESCkey = false;

    c = cmdchar;        // when actually doing editting cmdchar is the input.
    if(handleNextChar != null) {
      try {
        c = handleNextChar.go(c);
      } catch (NotSupportedException ex) {
        c = NUL;
      }
      if (c == NUL) {
        return;
      }
    }

    // whole lot of stuff deleted
    //	if (c == Ctrl('V') || c == Ctrl('Q'))

normal_char:	// do "break normal_char" to insert a character
    {
      try {

        G.curwin.w_set_curswant = true;

        // if (need_redraw && curwin.w_p_wrap && !did_backspace
        // && curwin.w_topline == old_topline)

        // ....
        update_curswant();
        // .....

        if(G.fdo_flags().contains(FDO_INSERT))
          Normal.foldOpenCursor();

        char lastc = c; // NEEDSWORK: use of lastc not supported
        // c = GetChar.safe_vgetc();

        // skip ctrl-\ ctrlN to normal mode
        // NEEDSWORK: ctrl-V ctrl-Q
	if (c == ctrl('V') || c == ctrl('Q')) {
          ins_ctrl_v();
          return;
        }
        if(c == IM_LITERAL) {
          ins_literal();
          return;
        }
        // skip some indent stuff
        // skip if(has_startsel)

        int dir = FORWARD;
        c = Misc.xlateViKey(ViManager.getInsertModeKeymap(), c, G.getModMask());
        switch(c) {
          case IM_INS_REP:
            //case K_INS:	    // toggle insert/replace mode
            if (G.State == REPLACE) { G.State = INSERT; }
            else { G.State = REPLACE; }
            GetChar.AppendCharToRedobuff(c);
            Misc.showmode();
            Misc.ui_cursor_shape();	// may show different cursor shape
            break;

            // case Ctrl('X'):	    // Enter ctrl-x mode
            // case K_SELECT:	    // end of Select mode mapping - ignore
            // case Ctrl('Z'):	    // suspend when 'insertmode' set
            // case Ctrl('O'):	    // execute one command
            // case K_HELP:
            // case K_F1:
            // case K_XF1:

            //
            // Insert the previously inserted text.
            // For ^@ the trailing ESC will end the insert, unless there
            // is an error.
            //
            // case K_ZERO:
            // case NUL:
            // case Ctrl('A'):

          case NUL:                     // Ctrl-@
          case 0x1f & (int)('A'):	// Ctrl
	    if (stuff_inserted(NUL, 1, (c == Util.ctrl('A'))) == FAIL
                    && c != Util.ctrl('A') /*&& !G.p_im*/ )
            {
              doESCkey = true; //goto doESCkey;		/* quit insert mode */
              break;
            }
            inserted_space.setValue(false);
            break;

            // insert the contents of a register
          case 0x1f & (int)('R'):	// Ctrl
            ins_reg();
            inserted_space.setValue(false);
            break;

          // commands starting with CTRL-G
          case 0x1f & (int)('G'):       // Ctrl
            ins_ctrl_g();
            break;

            // Make indent one shiftwidth smaller.
          case IM_SHIFT_LEFT:
            //case 0x1f & (int)('D'):	// Ctrl
            dir = BACKWARD;
            // FALLTHROUGH

            // Make indent one shiftwidth greater.
          case IM_SHIFT_RIGHT:
            //case 0x1f & (int)('T'):	// Ctrl
            ins_shift(c, lastc, dir);
            // need_redraw = TRUE;
            inserted_space.setValue(false);
            break;

            // shift line to be under char after next
            // parenthesis in previous line
          case IM_SHIFT_RIGHT_TO_PAREN:
            //case K_X_PERIOD:
            if( c == IM_SHIFT_RIGHT_TO_PAREN
                    || c == K_X_PERIOD && G.getModMask() == CTRL) {
              ins_shift_paren(c, FORWARD);
              inserted_space.setValue(false);
            }
            break;

            // shift line to be under char after previous
            // parenthesis in previous line
          case IM_SHIFT_LEFT_TO_PAREN:
            //case K_X_COMMA:
            if( c == IM_SHIFT_LEFT_TO_PAREN
                    || c == K_X_COMMA && G.getModMask() == CTRL) {
              ins_shift_paren(c, BACKWARD);
              inserted_space.setValue(false);
            }
            break;

            // delete character under the cursor
          case K_DEL:
            ins_del();
            // need_redraw = TRUE;
            break;

            // delete character before the cursor
            //case K_S_BS:
            //c = K_BS;
            // FALLTHROUGH

          case K_BS:
          case 0x1f & (int)('H'):	// Ctrl
            did_backspace = ins_bs(c, BACKSPACE_CHAR, inserted_space);
            // need_redraw = TRUE;
            break;

            // delete word before the cursor
          case 0x1f & (int)('W'):	// Ctrl
            did_backspace = ins_bs(c, BACKSPACE_WORD, inserted_space);
            // need_redraw = TRUE;
            break;

            // delete all inserted text in current line
          case 0x1f & (int)('U'):	// Ctrl
            did_backspace = ins_bs(c, BACKSPACE_LINE, inserted_space);
            // need_redraw = TRUE;
            inserted_space.setValue(false);
            break;

          case K_HOME:
            // case K_KHOME:
            // case K_XHOME:
          case K_S_HOME:
            ins_home();
            break;

          case K_END:
            // case K_KEND:
            // case K_XEND:
          case K_S_END:
            ins_end();
            break;

          case K_LEFT:
            if ((G.mod_mask & MOD_MASK_CTRL) != 0)
              ins_s_left();
            else
              ins_left();
            break;

          case K_S_LEFT:
            ins_s_left();
            break;

          case K_RIGHT:
            if ((G.mod_mask & MOD_MASK_CTRL) != 0)
              ins_s_right();
            else
              ins_right();
            break;

          case K_S_RIGHT:
            ins_s_right();
            break;

          case K_UP:
            ins_up();
            break;

          /*
          case K_S_UP:
          case K_PAGEUP:
            // case K_KPAGEUP:
            ins_pageup();
            break;
          */

          case K_DOWN:
            ins_down();
            break;

          /*
          case K_S_DOWN:
          case K_PAGEDOWN:
            // case K_KPAGEDOWN:
            ins_pagedown();
            break;
          */

            // keypad keys: When not mapped they produce a normal char
          case K_KPLUS:
            c = '+'; break normal_char;
          case K_KMINUS:
            c = '-'; break normal_char;
          case K_KDIVIDE:
            c = '/'; break normal_char;
          case K_KMULTIPLY:
            c = '*'; break normal_char;

            // When <S-Tab> isn't mapped, use it like a normal TAB
          case K_S_TAB:
            // FALLTHROUGH

          case K_TAB:
            c = TAB;

            // TAB or Complete patterns along path
          case TAB:
            inserted_space.setValue(false);
            // bypass the ins_tab method if always use the platform tab handling
            if( G.usePlatformInsertTab || ins_tab())
              break normal_char;
            break;

          case K_KENTER:
            c = CR;
            // FALLTHROUGH
          case CR:
          case NL:
            //  p_im! wow. Out of memory should be a throw...
            // if (ins_eol(c) && !p_im) goto doESCkey;    // out of memory
            ins_eol(c);
            inserted_space.setValue(false);
            break;

            // case Ctrl('K'): // Enter digraph
            // case Ctrl(']'): // Tag name completion after ^X
            // case Ctrl('F'): // File name completion after ^X
            // case Ctrl('L'): // Whole line completion after ^X

          case 0x1f & 'P': // Do previous pattern completion
          case 0x1f & 'N': // Do next pattern completion
            ins_complete(c);
            break normal_char;

          case 0x1f & 'Y': // copy from previous line or scroll down
          case 0x1f & 'E': // copy from next line	   or scroll up
            c = ins_copychar(G.curwin.w_cursor.getLine()
                             + (c == (0x1f & (int)'Y') ? -1 : 1));
            break normal_char;

            //case K_S_SPACE:
            //c = ' ';
            // FALTHROUGH TO "default" ignore the ESC case....

          case ESC:	    // an escape ends input mode
            //
            // NOTE: FALLTHROUGH TO "default", check for escape there
            //
            // if (echeck_abbr(ESC + ABBR_OFF))
            // break;
            //FALLTHROUGH

            // case 0x1f & (int)('C'):	// Ctrl
            // when 'insertmode' set, and not halfway a mapping, don't leave
            // Insert mode.
            // REMOVE insertmode stuff

            //
            // This is the ONLY return from edit() (WHERE editBusy SET false)
            //
            /* **** this is the code moved to default case ****
            if (ins_esc(count, need_redraw, cmdchar)) {
              Normal.editBusy = false;
              // return (c == (0x1f & (int)('O')));
            }
            return;
             */
            //continue;

          default:
            // Virtual keys should not be put into the file.
            // Exit input mode if they are seen
            if(isExitInputMode(cmdchar)) {
              //  // This is the identical code as <ESC>
              //  if (ins_esc(count, need_redraw, cmdchar)) {
              //    Normal.editBusy = false;
              //    // return (c == (0x1f & (int)('O')));
              //  }
              //  return;
              doESCkey = true;
              break;
            }
            break normal_char;
        } // switch
        if(doESCkey) {
          if (ins_esc(count, need_redraw, cmdchar)) {
            Normal.editBusy = false;
          }
        }
        // break out of switch, process another character (unless doESCkey)
        // continue edit_loop;
        return;

      } catch(NotSupportedException e) {
        // brutal exit from input mode
        ins_esc(count, need_redraw, cmdchar);
        Normal.editBusy = false;
        return; // ignore the character
      }

    }
// normal_char:         LABEL   "break normal_char" GOES HERE

    // just do something simple for now
    // if(vim_iswordc(c) || !echeck_abbr(c))

    // NEEDSWORK: better filter to prevent funny chars from input
    // Oh hell, caveat programmer
    // if(c != '\t' && (c < ' ' || c == 7f)) {// WAS: c > 0x7e)) {
    if(c == 0) {
      // filter out most control chars and del
      // continue edit_loop;
      return;
    }

    if(c == ' ') {
      inserted_space.setValue(true);
      if(Insstart_blank_vcol == MAXCOL
              && G.curwin.w_cursor.getLine() == Insstart.getLine())
        Insstart_blank_vcol = get_nolist_virtcol();
    }

    insert_special(c, false, false);

    if(arrow_used)
      inserted_space.setValue(false);

    //continue edit_loop;
    return;
  }

  private static boolean isExitInputMode(int c) {
    if((c & 0xF000) == VIRT
        || c > 0xFFFF)
      return true;

    switch(c) {
      case ESC:
      case 0x1f & (int)('C'):	// Ctrl
      case 0x1f & (int)('O'):	// Ctrl
      case 0x1f & (int)('L'):	// Ctrl
      case 0x1f & (int)('Q'):	// Ctrl
      case 0x1f & (int)(']'):	// Ctrl
        return true;
      default:
        return false;
    }
  }
/**
 * Handle a CTRL-V or CTRL-Q typed in Insert mode.
 */
private static void ins_ctrl_v()
{
    int		c;

    /* may need to redraw when no more chars available now */
    //ins_redraw(FALSE);

    if(true) //if (redrawing() && !char_avail())
	edit_putchar('^', true);
    //AppendToRedobuff((char_u *)CTRL_V_STR);	/* CTRL-V */
    AppendCharToRedobuff(ctrl('V'));	/* CTRL-V */

    add_to_showcmd(ctrl('V'));

    //c = get_literal();
    handleNextChar = new GetCtrlV();

    // GetCtrlV will finish up
    //clear_showcmd();
    //insert_special(c, FALSE, TRUE);
//#ifdef FEAT_RIGHTLEFT...
}

private static void ins_literal() // based on ins_ctrl_v
{
    edit_putchar('^', true);
    AppendCharToRedobuff(IM_LITERAL);

    add_to_showcmd(ctrl('V')); // close enough

    handleNextChar = new GetLiteral();
}

/**
 * Put a character directly onto the screen.  It's not stored in a buffer.
 * Used while handling CTRL-K, CTRL-V, etc. in Insert mode.
 */
private static void edit_putchar(char c, boolean highlight)
{
  G.editPutchar = c;
}

private static void edit_clearPutchar()
{
  G.editPutchar = NUL;
}

  private static void removeUnusedWhiteSpace() {
    if (Util.ml_get_curline().toString().trim().isEmpty()) {
      int startOffset =
        G.curbuf.getLineStartOffset(G.curwin.w_cursor.getLine());
      int endOffset = G.curbuf.getLineEndOffsetFromOffset(startOffset) - 1;
      G.curwin.deleteChar(startOffset, endOffset);
    }
  }

  /**
   * Call this function before moving the cursor from the normal insert position
   * in insert mode.
   */
  static void undisplay_dollar() {
    /*if (dollar_vcol)
    {
      dollar_vcol = 0;
      update_screenline();
    }*/
  }

  /**
   * Insert an indent (for <Tab> or CTRL-T) or delete an indent (for CTRL-D).
   * Keep the cursor on the same character.
   * type == INDENT_INC	increase indent (for CTRL-T or <Tab>)
   * type == INDENT_DEC	decrease indent (for CTRL-D)
   * type == INDENT_SET	set indent to "amount"
   * if round is TRUE, round indent to 'shiftwidth' (only with _INC and _Dec).
   * @param replaced replaced character, put on replace stack
   */
  static void change_indent(int type, int amount, boolean round, int replaced) {
    /*
       int		vcol;
       int		last_vcol;
       int		i;
       char_u	*ptr;
       int		save_p_list;
       colnr_t	vc;
       colnr_t	orig_col = 0;		// init for GCC
       char_u	*new_line, *orig_line = NULL;	// init for GCC
     */
    int		new_cursor_col;
    int		start_col;
    int		insstart_less;		// reduction for Insstart.col
    boolean	save_p_list;

    /*
    // VREPLACE mode needs to know what the line was like before changing
    if (State == VREPLACE)
    {
      orig_line = vim_strsave(ml_get_curline());  // Deal with NULL below
      orig_col = curwin->w_cursor.col;
    }

    // for the following tricks we don't want list mode
    save_p_list = G.curwin.getWPList();
    G.curwin.setWPList(false);
    getvcol(curwin, &curwin->w_cursor, NULL, &vc, NULL);
    vcol = vc;
     */

    //
    // For Replace mode we need to fix the replace stack later, which is only
    // possible when the cursor is in the indent.  Remember the number of
    // characters before the cursor if it's possible.
    //
    start_col = G.curwin.w_cursor.getColumn();

    // determine offset from first non-blank
    new_cursor_col = G.curwin.w_cursor.getColumn();
    beginline(BL_WHITE);
    new_cursor_col -= G.curwin.w_cursor.getColumn();

    insstart_less = G.curwin.w_cursor.getColumn();

    /*
    //
    // If the cursor is in the indent, compute how many screen columns the
    // cursor is to the left of the first non-blank.
    //
    if (new_cursor_col < 0)
      vcol = get_indent() - vcol;

    if (new_cursor_col > 0)	    // can't fix replace stack
     */          // following == -1 means can't fix replace stack
    start_col = -1;

    //
    // Set the new indent.  The cursor will be put on the first non-blank.
    //
    if (type == INDENT_SET)
      Misc.set_indent(amount, true);
    else
      Misc.shift_line(type == INDENT_DEC, round, 1);
    insstart_less -= G.curwin.w_cursor.getColumn();

    //
    // Try to put cursor on same character.
    // If the cursor is at or after the first non-blank in the line,
    // compute the cursor column relative to the column of the first
    // non-blank character.
    // If we are not in insert mode, leave the cursor on the first non-blank.
    // If the cursor is before the first non-blank, position it relative
    // to the first non-blank, counted in screen columns.
    //
    // NEEDSWORK: change_indent: if cursor before non-blank, leave on first
    if (new_cursor_col >= 0) {
      //
      // When changing the indent while the cursor is touching it, reset
      // Insstart_col to 0.
      //
      if (new_cursor_col == 0)
        insstart_less = MAXCOL;
      new_cursor_col += G.curwin.w_cursor.getColumn();
    } else
      new_cursor_col = G.curwin.w_cursor.getColumn();
    /* ***************************************************************
    else if (!(State & INSERT))
      new_cursor_col = G.curwin.getWCursor().getColumn();
    else
    {
      //
      // Compute the screen column where the cursor should be.
      //
      vcol = get_indent() - vcol;
      curwin->w_virtcol = (vcol < 0) ? 0 : vcol;

      //
      // Advance the cursor until we reach the right screen column.
      //
      vcol = last_vcol = 0;
      new_cursor_col = -1;
      ptr = ml_get_curline();
      while (vcol <= (int)curwin->w_virtcol)
      {
        last_vcol = vcol;
        ++new_cursor_col;
        vcol += lbr_chartabsize(ptr + new_cursor_col, (colnr_t)vcol);
      }
      vcol = last_vcol;

      //
      // May need to insert spaces to be able to position the cursor on
      // the right screen column.
      //
      if (vcol != (int)curwin->w_virtcol)
      {
        curwin->w_cursor.col = new_cursor_col;
        i = (int)curwin->w_virtcol - vcol;
        ptr = alloc(i + 1);
        if (ptr != NULL)
        {
          new_cursor_col += i;
          ptr[i] = NUL;
          while (--i >= 0)
            ptr[i] = ' ';
          ins_str(ptr);
          vim_free(ptr);
        }
      }

      //
      // When changing the indent while the cursor is in it, reset
      // Insstart_col to 0.
      //
      insstart_less = MAXCOL;
    }

    G.curwin.setWPList(save_p_list);
     ********************************************************************/

    int col;
    if (new_cursor_col <= 0)
      col = 0;
    else
      col = new_cursor_col;
    G.curwin.w_cursor.set(G.curwin.w_cursor.getLine(), col);
    G.curwin.w_set_curswant = true;

    // changed_cline_bef_curs();

    /* ********************************************************************
    //
    // May have to adjust the start of the insert.
    //
    if (State & INSERT)
    {
      if (curwin->w_cursor.lnum == Insstart.lnum && Insstart.col != 0)
      {
        if ((int)Insstart.col <= insstart_less)
          Insstart.col = 0;
        else
          Insstart.col -= insstart_less;
      }
      if ((int)ai_col <= insstart_less)
        ai_col = 0;
      else
        ai_col -= insstart_less;
    }

    //
    // For REPLACE mode, may have to fix the replace stack, if it's possible.
    // If the number of characters before the cursor decreased, need to pop a
    // few characters from the replace stack.
    // If the number of characters before the cursor increased, need to push a
    // few NULs onto the replace stack.
    //
    if (State == REPLACE && start_col >= 0)
    {
      while (start_col > (int)curwin->w_cursor.col)
      {
        replace_join(0);	    // remove a NUL from the replace stack
        --start_col;
      }
      while (start_col < (int)curwin->w_cursor.col || replaced)
      {
        replace_push(NUL);
        if (replaced)
        {
          replace_push(replaced);
          replaced = NUL;
        }
        ++start_col;
      }
    }

    //
    // For VREPLACE mode, we also have to fix the replace stack.  In this case
    // it is always possible because we backspace over the whole line and then
    // put it back again the way we wanted it.
    //
    if (State == VREPLACE)
    {
      // If orig_line didn't allocate, just return.  At least we did the job,
      // even if you can't backspace.
      //
      if (orig_line == NULL)
        return;

      // Save new line
      new_line = vim_strsave(ml_get_curline());
      if (new_line == NULL)
        return;

      // We only put back the new line up to the cursor
      new_line[curwin->w_cursor.col] = NUL;

      // Put back original line
      ml_replace(curwin->w_cursor.lnum, orig_line, FALSE);
      curwin->w_cursor.col = orig_col;

      // Backspace from cursor to start of line
      backspace_until_column(0);

      // Insert new stuff into line again
      vr_virtcol = MAXCOL;
      ptr = new_line;
      while (*ptr != NUL)
        ins_char(*ptr++);

      vim_free(new_line);
    }
     ************************************************************/
  }

  /**
   * Ctrl-N, Ctrl-P handling
   * @param c which one.
   */
  private static void ins_complete(char c) {
    ViTextView.WMOP op = null;
    if(c == (0x1f & 'N'))
      op = ViTextView.WMOP.NEXT_WORD_MATCH;
    else if(c == (0x1f & 'P'))
      op = ViTextView.WMOP.PREV_WORD_MATCH;
    if(op != null)
      G.curwin.wordMatchOperation(op);
  }

/**
 * Next character is interpreted literally.
 * A one, two or three digit decimal number is interpreted as its byte value.
 * If one or two digits are entered, the next character is given to vungetc().
 * For Unicode a character > 255 may be returned.
 */
  private static class GetCtrlV implements HandleNextChar
  {
    private int		cc = 0;
    private int		i = 0;
    private boolean	hex = false;
    private boolean	octal = false;
    private int		unicode = 0;
    private boolean	first_char = true;

    public GetCtrlV()
    {
    }

    @Override
    public char go(char nc)
    {
//    if (got_int)
//	return Ctrl_C;

//#ifdef FEAT_GUI
//    /*
//     * In GUI there is no point inserting the internal code for a special key.
//     * It is more useful to insert the string "<KEY>" instead.	This would
//     * probably be useful in a text window too, but it would not be
//     * vi-compatible (maybe there should be an option for it?) -- webb
//     */
//    if (gui.in_use)
//	++allow_keys;
//#endif
//#ifdef USE_ON_FLY_SCROLL
//    dont_scroll = TRUE;		/* disallow scrolling here */
//#endif
//    ++no_mapping;		/* don't map the next key hits */
      //for (;;)
one_char: {
//        do
//            nc = safe_vgetc();
//        while (nc == K_IGNORE || nc == K_VER_SCROLLBAR
//                                                    || nc == K_HOR_SCROLLBAR);
//        if (!(State & CMDLINE)
//# ifdef FEAT_MBYTE
//		&& MB_BYTE2LEN_CHECK(nc) == 1
//# endif
//           )
              add_to_showcmd(nc);
          if(first_char) {
            first_char = false;
            boolean firstCharIsMode = true;
            if (nc == 'x' || nc == 'X')
                hex = true;
            else if (nc == 'o' || nc == 'O')
                octal = true;
            else if (nc == 'u' /*|| nc == 'U'*/) // at most 16 bits, no 'U'
              unicode = nc;
            else
              firstCharIsMode = false;
            if(firstCharIsMode)
              return NUL;
          }

          //else
          {
              if (hex
                      || unicode != 0
                      )
              {
                  if (!isxdigit(nc))
                      break one_char;
                  cc = cc * 16 + hex2nr(nc);
              }
              else if (octal)
              {
                  if (nc < '0' || nc > '7')
                      break one_char;
                  cc = cc * 8 + nc - '0';
              }
              else
              {
                  if (!isdigit(nc))
                      break one_char;
                  cc = cc * 10 + nc - '0';
              }

              ++i;
          }

          if (cc > 255
                  && unicode == 0
                  )
              cc = 255;		/* limit range to 0-255 */
          nc = 0;

          if (hex)		/* hex: up to two chars */
          {
              if (i >= 2)
                  break one_char;
          }
          else if (unicode != 0)  /* Unicode: up to four or eight chars */
          {
              if ((unicode == 'u' && i >= 4) || (unicode == 'U' && i >= 8))
                  break one_char;
          }
          else if (i >= 3)	/* decimal or octal: up to three chars */
              break one_char;

          // Major HACK because can't do getc inline
          // at this point would repeat the 'for(;;)',
          // so just return and we'll get another char
          return NUL;
      }
// "break onechar" comes here
      //
      // Finish up, after "for(;;)" in the original code
      //
      if (i == 0)	    /* no number entered */
      {
          if (nc == K_ZERO)   /* NUL is stored as NL */
          {
              cc = '\n';
              nc = 0;
          }
          else
          {
              cc = nc;
              nc = 0;
          }
      }

      if (cc == 0)	/* NUL is stored as NL */
          cc = '\n';
//#ifdef FEAT_MBYTE
//    if (enc_dbcs && (cc & 0xff) == 0)
//        cc = '?';	/* don't accept an illegal DBCS char, the NUL in the
//                           second byte will cause trouble! */
//#endif

//    --no_mapping;
//#ifdef FEAT_GUI
//    if (gui.in_use)
//	--allow_keys;
//#endif
      if (nc != NUL)
          vungetc(nc | (G.getModMask() << 16));
//    got_int = FALSE;	    /* CTRL-C typed after CTRL-V is not an interrupt */
      handleNextChar = null; // all done.

      clear_showcmd();
      insert_special((char)cc, false, true);
      edit_clearPutchar();

      return NUL;
    }

}

  /** Grab one character. Like Ctrl-V but no special processing */
private static class GetLiteral implements HandleNextChar
{
    @Override
    public char go(char c)
    {
        handleNextChar = null; // all done.
        // just put in the next character
        clear_showcmd();
        insert_special(c, false, true);
        edit_clearPutchar();

        return NUL;

    }
  }

  /**
   * Insert character, taking care of special keys and mod_mask.
   */

  private static void insert_special(char c,
                                     boolean allow_modmask,
                                     boolean ctrlv) {

    // NEEDSWORK: edit: insert_special
    // Special function key, translate into "<Key>". Up to the last '>' is
    // inserted with ins_str(), so as not to replace characters in replace
    // mode.
    // Only use mod_mask for special keys, to avoid things like <S-Space>,
    // unless 'allow_modmask' is TRUE.
    //
    /* *********************************************************************
    if (IS_SPECIAL(c) || (mod_mask && allow_modmask)) {
      p = get_special_key_name(c, mod_mask);
      len = STRLEN(p);
      c = p[len - 1];
      if (len > 2) {
        p[len - 1] = NUL;
        ins_str(p);
        AppendToRedobuff(p);
        ctrlv = FALSE;
      }
    }
     ***********************************************************************/
    insertchar(c, false, -1, ctrlv);
  }

  static void insertchar(char c, boolean force_formatting,
                         int second_indent, boolean ctrlv) {

    // NEEDSWORK: edit: insertchar
    // If there's any pending input, grab up to INPUT_BUFLEN at once.
    // This speeds up normal text input considerably.
    // Don't do this when 'cindent' is set, because we might need to re-indent
    // at a ':', or any other character.
    //

    stop_arrow();
    //if(ctrlv)
    //  redo_literal
    GetChar.AppendCharToRedobuff(c);
    // should do doc.insert rather than keytyped...
    Misc.ins_char(c, ctrlv);
    G.did_ai = false;
  }

  static boolean arrow_used;
  /**
   * start_arrow() is called when an arrow key is used in insert mode.
   * It resembles hitting the <ESC> key.
   */
  static void start_arrow(ViFPOS end_insert_pos) {
    if (!arrow_used)	    /* something has been inserted */
    {
      GetChar.AppendToRedobuff(ESC_STR);
      arrow_used = true;	// this means we stopped the current insert
      stop_insert(end_insert_pos);
    }
  }

  /**
   * stop_arrow() is called before a change is made in insert mode.
   * If an arrow key has been used, start a new insertion.
   */
  static void stop_arrow() {
    if (arrow_used) {
      Misc.endInsertUndo();
      try {
        Normal.u_save_cursor();    // errors are ignored!
        //Insstart = G.curwin.w_cursor.copy();    // new insertion starts here
        Insstart.set(G.curwin.w_cursor);
        Insstart_textlen = Misc.linetabsize(Util.ml_get_curline());
       //ai_col = 0;
        assert(G.State != VREPLACE);
        if (G.State == VREPLACE) {
          // G.orig_line_count = curbuf->b_ml.ml_line_count;
          // vr_lines_changed = 1;
          // vr_virtcol = MAXCOL;
        }
        GetChar.ResetRedobuff();
        GetChar.AppendToRedobuff("1i");   // pretend we start an insertion
        new_insert_skip = 2;
        arrow_used = false;
      } finally {
        Misc.beginInsertUndo();
        GetChar.startInputModeRedobuff();
      }
    }
  }

  private static void stop_insert(ViFPOS end_insert_pos) {
    GetChar.stop_redo_ins();
    replace_flush();		// abandon replace stack

    //
    // save the inserted text for later redo with ^@
    //
    last_insert = GetChar.get_inserted();
    last_insert_skip = new_insert_skip;

    if (G.did_ai && !arrow_used)
      removeUnusedWhiteSpace();
    G.did_ai = false;

    if(end_insert_pos != null) {
      G.curbuf.b_op_start.setMark(Insstart);
      G.curbuf.b_op_end.setMark(end_insert_pos);
    }
    //NEEDSWORK: Insstart = null; do it elsewhere
  }

  /**
   * Move cursor to start of line.
   *<ul>
   *<li> if flags & BL_WHITE	move to first non-white</li>
   *<li> if flags & BL_SOL	move to first non-white if startofline is set,
   *			        otherwise keep "curswant" column</li>
   *<li> if flags & BL_FIX	don't leave the cursor on a NUL.</li>
   *</ul>
   * <br/><b>NEEDSWORK:</b><ul>
   * <li>This belongs as part of the document since the Content and
   * hence the segment can not be accessed publicly, only protected.</li>
   * </ul>
   */
  public static int beginlineColumnIndex(MySegment txt, int flags) {
    int index;
    if ((flags & BL_SOL) != 0 && G.p_notsol) {
      index = Misc.coladvanceColumnIndex(G.curwin.w_curswant, txt);
    } else {
      index = 0;
      if((flags & (BL_WHITE | BL_SOL)) != 0) {
        for(int ptr = txt.offset
                ; Misc.vim_iswhite(txt.array[ptr])
                && !(((flags & BL_FIX) != 0) && txt.array[ptr+1] == '\n')
                ; ++ptr) {
          ++index;
        }
      }
      G.curwin.w_set_curswant = true;
    }
    return index;
  }

  /**
   * <b>NEEDSWORK:</b><ul>
   * <li>This often follows an operation that get the segment,
   * and here we are getting it again in BEGIN_LINE.
   * </ul>
   */
  public static ViFPOS beginline(ViFPOS fpos, int flags) {
    int line = fpos.getLine();
    MySegment seg = fpos.getBuffer().getLineSegment(line);
    int offset = seg.docOffset + beginlineColumnIndex(seg, flags);
    fpos.set(offset);
    return fpos;
  }

  public static void beginline(int flags) {
    beginline(G.curwin.w_cursor, flags);
  }


  /**
   * oneright oneleft cursor_down cursor_up
   *
   * Move one char {right,left,down,up}.
   * Return OK when successful, FAIL when we hit a line of file boundary.
   */

  public static int oneright()
  {
    ViFPOS fpos = G.curwin.w_cursor;
    if(oneright(fpos) == FAIL)
      return FAIL;
    G.curwin.w_cursor.set(fpos);
    return OK;
  }

  public static int oneright(ViFPOS fpos) {
    int lnum = fpos.getLine();
    int col = fpos.getColumn();
    MySegment seg = G.curbuf.getLineSegment(lnum);
    if(seg.array[seg.offset + col++] == '\n'	// not possible, maybe if input?
            || seg.array[seg.offset + col] == '\n'
            || col >= seg.count - 1) {
      return FAIL;
    }

    G.curwin.w_set_curswant = true;
    fpos.incColumn();
    return OK;
  }

  public static int oneleft() {
    ViFPOS fpos = G.curwin.w_cursor;
    int col = fpos.getColumn();
    if(col == 0) {
      return FAIL;
    }

    G.curwin.w_set_curswant = true;
    G.curwin.w_cursor.set(fpos.getOffset() - 1);
    return OK;
  }

  /**
   * When TRUE: update topline.
   */
  public static int cursor_up(int n, boolean upd_topline) {
    int lnum = G.curwin.w_cursor.getLine();
    if (n > 0) {
      if (lnum <= 1)
        return FAIL;
      if (n >= lnum)
        lnum = 1;
      else if(G.curwin.hasAnyFolding()) {
        //
        // Count each sequence of folded lines as one logical line.
        //
        // go to the start of the current fold
        MutableInt mi = new MutableInt();
        if(G.curwin.hasFolding(lnum, mi, null))
          lnum = mi.getValue();
        while(n-- > 0) {
          // move up one line
          --lnum;
          if (lnum <= 1)
            break;
          // If we entered a fold, move to the beginning, unless in
          // Insert mode or when 'foldopen' contains "all": it will open
          // in a moment.
          if (n > 0 || !((G.State & INSERT) != 0
                          || (G.fdo_flags().contains(FDO_ALL))))
          if(G.curwin.hasFolding(lnum, mi, null))
            lnum = mi.getValue();
        }
        if(lnum < 1)
          lnum = 1;
      } else
        lnum -= n;
    }

    ViFPOS fpos = fpos();
    fpos.set(lnum, 0);
    coladvance(fpos, G.curwin.w_curswant).copyTo(G.curwin.w_cursor);
    return OK;
  }

  /**
   * @param n the number of lines to move
   * @param upd_topline When TRUE: update topline.
   */
  public static int cursor_down(int n, boolean upd_topline) {
    ViFPOS fpos = fpos();
    int lnum = fpos.getLine();
    if(n > 0)
    {
      MutableInt last = new MutableInt();
      // Move to last line of fold, will fail if it's the end-of-file.
      if(G.curwin.hasFolding(lnum, null, last))
        lnum = last.getValue();
        // This fails if the cursor is already in the last line or would move
        // beyond the last line and '-' is in 'cpoptions'
        if (lnum >= G.curbuf.getLineCount()
                || (lnum + n > G.curbuf.getLineCount()
                    && vim_strchr(G.p_cpo, CPO_MINUS) != null))
            return FAIL;
        if (lnum + n >= G.curbuf.getLineCount())
            lnum = G.curbuf.getLineCount();
        else
        if (G.curwin.hasAnyFolding())
        {
            // count each sequence of folded lines as one logical line
            while (n-- > 0)
            {
                if (G.curwin.hasFolding(lnum, null, last))
                    lnum = last.getValue() + 1;
                else
                    ++lnum;
                if (lnum >= G.curbuf.getLineCount())
                    break;
            }
            if (lnum > G.curbuf.getLineCount())
                lnum = G.curbuf.getLineCount();
        }
        else
            lnum += n;
        fpos.set(lnum, 0);
    }
    /* try to advance to the column we want to be at */
    coladvance(fpos, G.curwin.w_curswant).copyTo(G.curwin.w_cursor);

    // if (upd_topline)
    //     update_topline();	/* make sure curwin->w_topline is valid */

    return OK;
  }

  /**
   * Stuff the last inserted text in the read buffer.
   * Last_insert actually is a copy of the redo buffer, so we
   * first have to remove the command.
   */
  static int stuff_inserted(char c, int count, boolean no_esc)
  throws NotSupportedException {
    String ptr = get_last_insert();
    if(ptr == null)
    {
      Msg.emsg(Messages.e_noinstext);
      return FAIL;
    }
    if(c != NUL)
      GetChar.stuffcharReadbuff(c);
    int esc_ptr = ptr.indexOf(ESC);
    if(esc_ptr != 0)
      ptr = ptr.substring(0, esc_ptr); // remove the ESC

/*  WONDER WHAT THIS IS?
    // when the last char is either "0" or "^" it will be quoted if no ESC
    // comes after it OR if it will inserted more than once and "ptr"
    // starts with ^D.	-- Acevedo
    //
    last_ptr = (esc_ptr ? esc_ptr : ptr + STRLEN(ptr)) - 1;
    if (last_ptr >= ptr && (*last_ptr == '0' || *last_ptr == '^')
	    && (no_esc || (*ptr == Ctrl('D') && count > 1)))
    {
	last = *last_ptr;
	*last_ptr = NUL;
    }
*/

    do
    {
	checkForStuffReadbuf(ptr, "i_CTRL-A or i_CTRL-@");
	stuffReadbuff(ptr);
	///// // a trailing "0" is inserted as "<C-V>048", "^" as "<C-V>^"
	///// if (last)
	/////     stuffReadbuff((char_u *)(last == '0' ? "\026048" : "\026^"));
    }
    while (--count > 0);

    ///// if (last)
    /////     *last_ptr = last;

    // following not needed since working with copy
    ///// if (esc_ptr != NULL)
    /////     *esc_ptr = ESC;	    /* put the ESC back */

    // may want to stuff a trailing ESC, to get out of Insert mode
    if(!no_esc)
      GetChar.stuffcharReadbuff(ESC);

    return OK;
  }

  /**
   * Check for characters that might cause recursion if added to Radbuff,
   * like ^R, ^A, ^@.
   * Throws an error if problem character found.
   * @param s string to check.
   * @return the original string
   */
  static void checkForStuffReadbuf(String s, String op)
  throws NotSupportedException {
    String msg = null;
    if(false) {
      String pat = String.format(".*[\\x%02d\\x%02d\\x%02d].*",
                                 0x1f&'R', 0x1f&'A', 0);
      if(s.matches(pat)) {
        msg = "Potentially recursive operation";
      }
    } else {
      if(s.indexOf(0x1f&'R') >= 0
              || s.indexOf(0x1f&'A') >= 0
              || s.indexOf(0) >= 0)
        msg = op + ": potentially recursive operation";
    }
    if(msg != null) {
      Msg.emsg(msg);
      vim_beep();
      throw new NotSupportedException(op, msg);
    }
  }

  private static String get_last_insert()
  {
    if(last_insert == null)
      return null;
    return last_insert.substring(last_insert_skip);
  }

  /**
   * Get last inserted string, and remove trailing <Esc>.
   * Returns pointer to allocated memory (must be freed) or NULL.
   */
  static String get_last_insert_save()
  {
      int		len;

      if (last_insert == null)
          return null;
      len = last_insert.length();
      if(len > last_insert_skip + 1
              && last_insert.charAt(len-1) == ESC) { // remove trailing ESC
        --len;
      }
      if(last_insert_skip > len)
        return null;
      return last_insert.substring(last_insert_skip, len);
  }

  /**
   * replace-stack functions.
   * <p>
   * When replacing characters, the replaced characters are remembered for each
   * new character.  This is used to re-insert the old text when backspacing.
   * </p><p>
   * There is a NUL headed list of characters for each character that is
   * currently in the file after the insertion point.  When BS is used, one NUL
   * headed list is put back for the deleted character.
   * </p><p>
   * For a newline, there are two NUL headed lists.  One contains the characters
   * that the NL replaced.  The extra one stores the characters after the cursor
   * that were deleted (always white space).
   * </p><p>
   * Replace_offset is normally 0, in which case replace_push will add a new
   * character at the end of the stack.  If replace_offset is not 0, that many
   * characters will be left on the stack above the newly inserted character.
   * </p>
   * <br><b>NEEDSWORK:</b><ul>
   * <li> May need to keep row/col indication for each char pushed.
   * </ul>
   * @parm c character that is replaced (NUL is none)
   */

  static Stack<Character> replace_stack = new Stack<Character>();
  static int replace_offset = 0;
  private static boolean is_char(char c) {
    return c != 0 && c != '\uffff';
  }

  static void replace_push(char c) {
    if (replace_stack.size() < replace_offset)	/* nothing to do */
      return;
    // NEEDSWORK: maintain stack as an array of chars,
    // if (replace_stack_len <= replace_stack_nr).... extend stack..
    int idx = replace_stack.size() - replace_offset;
    replace_stack.add(idx, c);
  }

  /**
   * call replace_push(c) with replace_offset set to the first NUL.
   */
  private static void replace_push_off(char c) {
    int idx = replace_stack.size();
    for (replace_offset = 1;
         replace_offset < replace_stack.size();
         ++replace_offset) {

      --idx;
      char item = replace_stack.get(idx).charValue();
      if(item == NUL) {
        break;
      }
    }
    replace_push(c);
    replace_offset = 0;
  }

  /**
   * Pop one item from the replace stack.
   * return -1 if stack empty
   * return replaced character or NUL otherwise
   */
  private static char replace_pop() {
    // vr_virtcol = MAXCOL;
    if(replace_stack.size() == 0) {
      return '\uffff';
    }
    return replace_stack.pop().charValue();
  }

  /**
   * Join the top two items on the replace stack.  This removes to "off"'th NUL
   * encountered.
   * @param off offset for which NUL to remove
   */
  private static void replace_join(int off) {
    int	    i;

    for (i = replace_stack.size(); --i >= 0; ) {
      if (replace_stack.get(i).charValue() == NUL && off-- <= 0) {
        replace_stack.remove(i);
        return;
      }
    }
  }

  /**
   * Pop characters from the replace stack until a NUL is found, and insert them
   * before the cursor.  Can only be used in REPLACE or VREPLACE mode.
   */
  private static void replace_pop_ins() {
    char    cc;
    int	    oldState = G.State;

    G.State = NORMAL;		/* don't want REPLACE here */
    while (is_char(cc = replace_pop())) {
      Misc.ins_char(cc);
      Misc.dec_cursor();
    }
    G.State = oldState;
  }

  /**
   * make the replace stack empty
   * (called when exiting replace mode).
   */
  private static void replace_flush() {
    replace_stack.setSize(0);
  }

  /**
   * Handle doing a BS for one character.
   * cc &lt; 0: replace stack empty, just move cursor
   * cc == 0: character was inserted, delete it
   * cc &gt; 0: character was replace, put original back
   */
  private static void replace_do_bs() {
    char    cc;

    cc = replace_pop();
    if (is_char(cc)) {
      Misc.pchar_cursor(cc);
      replace_pop_ins();
    } else if (cc == 0) {
      Misc.del_char(false);
    }
  }


  /**
   * Insert the contents of a named buf into the text.
   * <br/>vim returns boolean whether to redraw or not.
   */
  private static void ins_reg()
  {

      /*
       * If we are going to wait for a character, show a '"'.
       */
      if(true) // if (redrawing() && !char_avail())
      {
          edit_putchar('"', true);
          //add_to_showcmd_c(ctrl('R'));
          add_to_showcmd(ctrl('R'));
      }

  //#ifdef USE_ON_FLY_SCROLL...
      // CallbackInsReg will finish up...
      handleNextChar = new CallbackInsReg();
  }

  private static class CallbackInsReg implements HandleNextChar
  {
    char literally = NUL;

    @Override
    public char go(char nc)
    throws NotSupportedException
    {
      //int	    need_redraw = FALSE;

      char	    regname = nc;

      //
      // Don't map the register name. This also prevents the mode message to be
      // deleted when ESC is hit.
      //

      ++G.no_mapping;
      if (literally == NUL
              && (regname == ctrl('R')
                  || regname == ctrl('O')
                  || regname == ctrl('P')))
      {
          /* Get a third key for literal register insertion */
          literally = regname;
          add_to_showcmd(literally);
          return NUL;
      }
      --G.no_mapping;

//  #ifdef HAVE_LANGMAP.....

//  #ifdef WANT_EVAL.....

     if (literally == ctrl('O') || literally == ctrl('P'))
     {
         /* Append the command to the redo buffer. */
         AppendCharToRedobuff(ctrl('R'));
         AppendCharToRedobuff(literally);
         AppendCharToRedobuff(regname);

         do_put(regname, BACKWARD, 1,
              (literally == ctrl('P') ? PUT_FIXINDENT : 0) | PUT_CURSEND);
     }
     else if (Misc.insert_reg(regname, literally != 0) == FAIL) {
        vim_beep();
        //need_redraw = TRUE;	/* remove the '"' */
      }
//  #ifdef WANT_EVAL

      clear_showcmd();

      // If the inserted register is empty, we need to remove the '"'
/////      if (stuff_empty())
/////          need_redraw = TRUE;
      edit_clearPutchar();

      //return need_redraw;
      handleNextChar = null; // all done.
      return NUL;
    }
  }

  private static void ins_ctrl_g()
  {
      if(true) // if (redrawing() && !char_avail())
      {
          //add_to_showcmd_c(ctrl('R'));
          add_to_showcmd(ctrl('G'));
      }

  //#ifdef USE_ON_FLY_SCROLL...
      handleNextChar = new CallbackInsCtrlG();

  }

  private static class CallbackInsCtrlG implements HandleNextChar
  {
    @Override
    public char go(char c) throws NotSupportedException
    {
      handleNextChar = null; // all done.

      switch(c) {
        case 'u':
          Misc.endInsertUndo();
          Misc.beginInsertUndo();
          break;

        default:
      }
      clear_showcmd();
      return NUL; // no more processing for this
    }

  }

  /**
   * Handle ESC in insert mode.
   * @return TRUE when leaving insert mode, FALSE when going to repeat the
   * insert.
   */
  private static boolean ins_esc(MutableInt count,
                                 boolean need_redraw,
                                 char cmdchar) {
    // .......

    if( ! arrow_used) {
      //
      // Don't append the ESC for "r<CR>".
      //
      if (cmdchar != 'r' && cmdchar != 'v') {
        GetChar.AppendCharToRedobuff(ESC);
        GetChar.editComplete();
      }

      // NEEDSWORK: handle count on an insert

      //
      // Repeating insert may take a long time.  Check for
      // interrupt now and then.
      //
      if(count.getValue() != 0) {
        Misc.line_breakcheck();
        if (G.False/*got_int*/)
          count.setValue(0);
      }

      count.setValue(count.getValue() - 1);
      if (count.getValue() > 0) {	// repeat what was typed
        GetChar.start_redo_ins();
        // ++RedrawingDisabled;
        // disabled_redraw = TRUE;
        //Insstart = null;
        return false;	// repeat the insert
      }
      stop_insert(G.curwin.w_cursor);	// pass stop insert cursor position

      /* ********************************************************
      if (dollar_vcol) {
        dollar_vcol = 0;
        // may have to redraw status line if this was the
        // first change, show "[+]"
        if (curwin->w_redr_status == TRUE)
          redraw_later(NOT_VALID);
        else
          need_redraw = TRUE;
      }
       ******************************************************/
    }

    // ........

    // When an autoindent was removed, curswant stays after the indent
    // NEEDSWORK: what about autoindent in above comment?
    if (G.restart_edit == 0 /*&& (colnr_t)temp == curwin->w_cursor.col*/) {
      G.curwin.w_set_curswant = true;
    }

    //if (!cmdmod.keepjumps)
        G.curbuf.b_last_insert.setMark(G.curwin.w_cursor);

    //
    // The cursor should end up on the last inserted character.
    //
    // NEEDSWORK: fixup cursor after insert
    ViFPOS fpos = G.curwin.w_cursor;
    if (fpos.getColumn() != 0
            && (G.restart_edit == 0 || Misc.gchar_pos(fpos) == '\n')) {
      G.curwin.w_cursor.set(fpos.getOffset() - 1);
    }

    G.State = NORMAL;
    Misc.ui_cursor_shape();
    //
    // When recording or for CTRL-O, need to display the new mode.
    // Otherwise remove the mode message.
    //
    // if (/*Recording ||*/ G.restart_edit != 0) {
    //   Misc.showmode();
    // } else if (true/*G.p_smd*/) {
    //   // MSG("");
    //   G.curwin.getStatusDisplay().displayMode(VI_MODE_COMMAND);
    // }
    // always use showmode, since it communicates mode to environment
    Misc.showmode();
    Insstart = null;
    return true;
  }

  /**
   * If the cursor is on an indent, ^T/^D insert/delete one
   * shiftwidth.	Otherwise ^T/^D behave like a "<<" or ">>".
   * Always round the indent to 'shiftwith', this is compatible
   * with vi.  But vi only supports ^T and ^D after an
   * autoindent, we support it everywhere.
   */
  private static void ins_shift(char c, int lastc, int dir) {
    stop_arrow();
    GetChar.AppendCharToRedobuff(c);

    // NEEDSWORK: ins_shift: lastc not supported
    lastc = 0;

    /* **************************************************************
    //
    // 0^D and ^^D: remove all indent.
    //
    if ((lastc == '0' || lastc == '^') && curwin->w_cursor.col)
    {
        --curwin->w_cursor.col;
        (void)del_char(FALSE);		// delete the '^' or '0'
        // In Replace mode, restore the characters that '^' or '0' replaced.
        if (State == REPLACE || State == VREPLACE)
            replace_pop_ins();
        if (lastc == '^')
            old_indent = get_indent();	// remember curr. indent
        change_indent(INDENT_SET, 0, TRUE, 0);
    }
    else
     ************************************************************/
    change_indent(dir == BACKWARD ? INDENT_DEC : INDENT_INC, 0, true, 0);
    G.did_ai = false;
  }

  private static void ins_shift_paren(char c, int dir) {
    GetChar.AppendCharToRedobuff(c);
    int curindent = Misc.get_indent();
    int amount = Misc.findParen(G.curwin.w_cursor.getLine()-1,
            curindent, dir);
    if(amount > 0) {
      ++amount; // position after paren
      change_indent(INDENT_SET, amount, false, 0);
    } else {
      amount = Misc.findFirstNonBlank(G.curwin.w_cursor.getLine()-1,
              curindent, dir);
      if(dir == BACKWARD && amount > 0
              || dir != BACKWARD && amount > curindent)
        change_indent(INDENT_SET, amount, false, 0);
    }

  }

  private static void ins_del() throws NotSupportedException {
    int	    temp;

    stop_arrow();
    if (Misc.gchar_cursor() == '\n')	// delete newline
    {
      temp = G.curwin.w_cursor.getOffset();
      if (!Options.can_bs(BS_EOL)		// only if "eol" included
              || Misc.do_join(false, true) == FAIL) {
        Util.vim_beep();
      } else {
        G.curwin.w_cursor.set(temp);
      }
    }
    else if (Misc.del_char(false) == FAIL)// delete char under cursor
	Util.vim_beep();
    G.did_ai = false;
    // #ifdef SMARTINDENT ... #endif
    GetChar.AppendCharToRedobuff(K_DEL);
  }

/**
 * Handle Backspace, delete-word and delete-line in Insert mode.
 * Return true when backspace was actually used.
 */
private static boolean
ins_bs(char c, int mode, MutableBoolean inserted_space_p)
{
    int   	lnum;
    char	cc;
    int		temp = 0;	    /* init for GCC */
    boolean     tBool = false;
    int 	mincol;
    boolean	did_backspace = false;
    boolean	in_indent;
    int		oldState;

    /*
     * can't delete anything in an empty file
     * can't backup past first character in buffer
     * can't backup past starting point unless 'backspace' > 1
     * can backup to a previous line if 'backspace' == 0
     */
    if (   bufempty()
        || (
//#ifdef RIGHTLEFT...
      ((G.curwin.w_cursor.getLine() == 1 && G.curwin.w_cursor.getColumn() <= 0)
	|| (!can_bs(BS_START)
	    && (arrow_used
	        || (G.curwin.w_cursor.getLine() == Insstart.getLine()
	    	&& G.curwin.w_cursor.getColumn() <= Insstart.getColumn())))
	|| (!can_bs(BS_INDENT) && !arrow_used && G.ai_col > 0
	    		 && G.curwin.w_cursor.getColumn() <= G.ai_col)
	|| (!can_bs(BS_EOL) && G.curwin.w_cursor.getColumn() == 0))))
    {
	vim_beep();
	return false;
    }

    doingBackspace = true; // Beware of adding an early return;
    stop_arrow();
    in_indent = false; //inindent(0); NEEDSWORK: how can this be handled
//#ifdef CINDENT
//    if (in_indent)
//	can_cindent = false;
//#endif
//#ifdef COMMENTS
//    end_comment_pending = NUL;	/* After BS, don't auto-end comment */
//#endif
//#ifdef RIGHTLEFT...

    /*
     * delete newline!
     */
    if (G.curwin.w_cursor.getColumn() <= 0)
    {
	lnum = Insstart.getLine();
	if (G.curwin.w_cursor.getLine() == Insstart.getLine()
//#ifdef RIGHTLEFT...
				    )
	{
//	    if (u_save((linenr_t)(G.curwin.w_cursor.getLine() - 2),
//			       (linenr_t)(G.curwin.w_cursor.getLine() + 1)) == FAIL)
//		return false;
	    Insstart.decLine();
	    Insstart.setColumn(MAXCOL);
            GetChar.changeInsstart();
	}
	/*
	 * In replace mode:
	 * cc < 0: NL was inserted, delete it
	 * cc >= 0: NL was replaced, put original characters back
	 */
	cc = '\uffff';
	if (G.State == REPLACE || G.State == VREPLACE)
	    cc = replace_pop();	    /* returns -1 if NL was inserted */
	/*
	 * In replace mode, in the line we started replacing, we only move the
	 * cursor.
	 */
	if ((G.State == REPLACE || G.State == VREPLACE)
			&& G.curwin.w_cursor.getLine() <= lnum)
	{
	    dec_cursor();
	}
	else
	{
	    if (G.State != VREPLACE
                 // SINCE NEVER VREPLACE, don't need following
		 //  || G.curwin.w_cursor.getLine() > G.orig_line_count
            )
	    {
		temp = gchar_cursor();	/* remember current char */
		G.curwin.w_cursor.decLine();
		do_join(false, true);
		//redraw_later(VALID_TO_CURSCHAR);
		if (temp == NUL && gchar_cursor() != NUL)
		    G.curwin.w_cursor.incColumn();
	    }
	    else
		dec_cursor();

	    /*
	     * In REPLACE mode we have to put back the text that was replace
	     * by the NL. On the replace stack is first a NUL-terminated
	     * sequence of characters that were deleted and then the
	     * characters that NL replaced.
	     */
	    if (G.State == REPLACE || G.State == VREPLACE)
	    {
		/*
		 * Do the next ins_char() in NORMAL state, to
		 * prevent ins_char() from replacing characters and
		 * avoiding showmatch().
		 */
		oldState = G.State;
		G.State = NORMAL;
		/*
		 * restore characters (blanks) deleted after cursor
		 */
		while (cc > 0 && cc != '\uffff')
		{
		    temp = G.curwin.w_cursor.getColumn();
		    ins_char(cc);
		    G.curwin.w_cursor.setColumn(temp);
		    cc = replace_pop();
		}
		/* restore the characters that NL replaced */
		replace_pop_ins();
		G.State = oldState;
	    }
	}
	G.did_ai = false;
    }
    else
    {
	/*
	 * Delete character(s) before the cursor.
	 */
//#ifdef RIGHTLEFT...
	mincol = 0;
						/* keep indent */
//      // NEEDSWORK: for now b_p_ai is always false, so don't need following
//	if (mode == BACKSPACE_LINE && G.curbuf.b_p_ai != 0
////#ifdef RIGHTLEFT...
//			    )
//	{
//	    temp = G.curwin.w_cursor.getColumn();
//	    beginline(BL_WHITE);
//	    if (G.curwin.w_cursor.getColumn() < temp)
//		mincol = G.curwin.w_cursor.getColumn();
//	    G.curwin.w_cursor.setColumn(temp);
//	}

  	/*
  	 * Handle deleting one 'shiftwidth' or 'softtabstop'.
  	 */
          //****** ml_get_cursor() is pointer to cursor position ********
  	if (	   mode == BACKSPACE_CHAR
  		&& ((p_sta && in_indent)
  		    || (G.curbuf.b_p_sts != 0
  			&& (ml_get_cursor().previous() == TAB
  			    || (ml_get_cursor().previous() == ' '
  				&& (!inserted_space_p.getValue()
  				    || arrow_used))))))
  	{
  	    int		ts;
  	    int		vcol;
  	    int		want_vcol;
  	    int		extra = 0;

  	    inserted_space_p.setValue(false);
  	    if (p_sta)
  		ts = G.curbuf.b_p_sw;
  	    else
  		ts = G.curbuf.b_p_sts;
  	    /* compute the virtual column where we want to be */
            MutableInt pVcol = new MutableInt();
  	    getvcol(G.curwin, G.curwin.w_cursor, pVcol, null, null);
            vcol = pVcol.getValue();
  	    want_vcol = ((vcol - 1) / ts) * ts;
  	    /* delete characters until we are at or before want_vcol */
  	    while (vcol > want_vcol
  		    && vim_iswhite(ml_get_cursor().previous()))
  		    //&& (cc = ml_get_cursor().previous(), vim_iswhite(cc)))
  	    {
  		dec_cursor();
  		/* TODO: calling getvcol() each time is slow */
  		getvcol(G.curwin, G.curwin.w_cursor, pVcol, null, null);
                vcol = pVcol.getValue();
  		if (G.State == REPLACE || G.State == VREPLACE)
  		{
  		    /* Don't delete characters before the insert point when in
  		     * Replace mode */
  		    if (G.curwin.w_cursor.getLine() != Insstart.getLine()
  			|| G.curwin.w_cursor.getColumn() >= Insstart.getColumn())
  		    {
  //#IF 0	/* what was this for?  It causes problems when sw != ts. */
  //			if (G.State == REPLACE && (int)vcol < want_vcol)
  //			{
  //			    (void)del_char(false);
  //			    extra = 2;	/* don't pop too much */
  //			}
  //			else
  //#ENDIF
  			    replace_do_bs();
  		    }
  		}
  		else
  		    del_char(false);
  	    }

  	    /* insert extra spaces until we are at want_vcol */
  	    while (vcol < want_vcol)
  	    {
  		/* Remember the first char we inserted */
  		if (G.curwin.w_cursor.getLine() == Insstart.getLine()
                       && G.curwin.w_cursor.getColumn() < Insstart.getColumn()) {
  		    Insstart.setColumn(G.curwin.w_cursor.getColumn());
                    GetChar.changeInsstart();
                }

  		if (G.State == VREPLACE)
  		    ins_char(' ');
  		else
  		{
  		    //ins_str((char_u *)" ");
                    G.curbuf.insertText(G.curwin.w_cursor.getOffset(), " ");
  		    if (G.State == REPLACE && extra <= 1)
  		    {
  			if (extra != 0)
  			    replace_push_off(NUL);
  			else
  			    replace_push(NUL);
  		    }
  		    if (extra == 2)
  			extra = 1;
  		}
  		vcol++;
  	    }
  	}

	/*
	 * Delete upto starting point, start of line or previous word.
	 */
  	else do
	{
//#ifdef RIGHTLEFT...
		dec_cursor();

	    /* start of word? */
	    if (mode == BACKSPACE_WORD && !vim_isspace(gchar_cursor()))
	    {
		mode = BACKSPACE_WORD_NOT_SPACE;
		tBool = vim_iswordc(gchar_cursor());
	    }
	    /* end of word? */
	    else if (mode == BACKSPACE_WORD_NOT_SPACE
		    && (vim_isspace(cc = gchar_cursor())
			    || vim_iswordc(cc) != tBool))
	    {
//#ifdef RIGHTLEFT...
		    inc_cursor();
//#ifdef RIGHTLEFT...
		break;
	    }
	    if (G.State == REPLACE || G.State == VREPLACE)
		replace_do_bs();
	    else  /* State != REPLACE && State != VREPLACE */
	    {
		del_char(false);
//#ifdef RIGHTLEFT...
	    }
	    /* Just a single backspace?: */
	    if (mode == BACKSPACE_CHAR)
		break;
	} while (
//#ifdef RIGHTLEFT...
		(G.curwin.w_cursor.getColumn() > mincol
		 && (G.curwin.w_cursor.getLine() != Insstart.getLine()
		     || G.curwin.w_cursor.getColumn() != Insstart.getColumn())));
	did_backspace = true;
    }
//#ifdef SMARTINDENT
//    did_si = false;
//    can_si = false;
//    can_si_back = false;
//#endif
    if (G.curwin.w_cursor.getColumn() <= 1)
	G.did_ai = false;

    /*
     * It's a little strange to put backspaces into the redo
     * buffer, but it makes auto-indent a lot easier to deal
     * with.
     */
    doingBackspace = false;
    AppendCharToRedobuff(c);

    /* If deleted before the insertion point, adjust it */
    if (G.curwin.w_cursor.getLine() == Insstart.getLine()
		   && G.curwin.w_cursor.getColumn() < Insstart.getColumn()) {
	Insstart.setColumn(G.curwin.w_cursor.getColumn());
        GetChar.changeInsstart();
    }

    return did_backspace;
}

  /*
   * Handle TAB in Insert or Replace mode.
   * Return TRUE when the TAB needs to be inserted like a normal character.
   */
  static boolean ins_tab()
  {
    boolean	ind;
    int		i;
    int		temp;

    if (Insstart_blank_vcol == MAXCOL
            && G.curwin.w_cursor.getLine() == Insstart.getLine())
	Insstart_blank_vcol = get_nolist_virtcol();
//    if (echeck_abbr(TAB + ABBR_OFF))
//	return false;

    ind = Misc.inindent(0);
//#ifdef CINDENT ...

    /*
     * When nothing special, insert TAB like a normal character
     */
    if (!G.curbuf.b_p_et
	    && !(p_sta && ind && G.curbuf.b_p_ts != G.curbuf.b_p_sw)
	    && G.curbuf.b_p_sts == 0)
	return true;

    stop_arrow();
    G.did_ai = false;
//#ifdef SMARTINDENT ...
    AppendCharToRedobuff('\t');

    if (p_sta && ind)		/* insert tab in indent, use 'shiftwidth' */
	temp = G.curbuf.b_p_sw;
    else if (G.curbuf.b_p_sts != 0)	/* use 'softtabstop' when set */
	temp = G.curbuf.b_p_sts;
    else			/* otherwise use 'tabstop' */
	temp = G.curbuf.b_p_ts;
    temp -= get_nolist_virtcol() % temp;

    /*
     * Insert the first space with ins_char().	It will delete one char in
     * replace mode.  Insert the rest with ins_str(); it will not delete any
     * chars.  For VREPLACE mode, we use ins_char() for all characters.
     */
    ins_char(' ');
    while (--temp > 0)
    {
	if (G.State == VREPLACE)
	    ins_char(' ');
	else
	{
	    //ins_str((char_u *)" ");
            G.curbuf.insertText(G.curwin.w_cursor.getOffset(), " ");
	    if (G.State == REPLACE)	    /* no char replaced */
		replace_push(NUL);
	}
    }

    /*
     * When 'expandtab' not set: Replace spaces by TABs where possible.
     */
    if (!G.curbuf.b_p_et && (G.curbuf.b_p_sts != 0 || (p_sta && ind)))
    {
	//char_u		seg.charAt(ptr), *saved_line = null;
        MySegment       seg;
        int             ptr = 0;
        //int             saved_line = 0;
	ViFPOS		fpos;
	ViFPOS		cursor;
	int		want_vcol, vcol, tab_vcol;
	int		change_col = -1;
	int		ts = G.curbuf.b_p_ts;

	/*
	 * Get the current line.  For VREPLACE mode, don't make real changes
	 * yet, just work on a copy of the line.
	 */
        // MUST USE COPY, SINCE MAY MODIFY DATA IN PLACE
        boolean useTempCopy = true;
	if (useTempCopy || G.State == VREPLACE)
	{
	    // pos = curwin->w_cursor;
	    // cursor = &pos;
	    cursor = G.curwin.w_cursor.copy();

	    //saved_line = vim_strsave(ml_get_curline());
	    //if (saved_line == null)
	    //    return false;

            // Copy line data to a new segment

	    //ptr = saved_line + pos.getColumn();
            char[] data = ml_get_curline().toString().toCharArray();
            seg = new MySegment(data, 0, data.length, -1);
            ptr = cursor.getColumn();
	}
	else
	{
	    //ptr = ml_get_cursor();
            seg = ml_get_curline();
            ptr = G.curwin.w_cursor.getColumn();
	    cursor = G.curwin.w_cursor;
	}

	/* Find first white before the cursor */
	fpos = G.curwin.w_cursor.copy();
	while (fpos.getColumn() > 0 && vim_iswhite(seg.charAt(ptr+-1)))
	{
	    fpos.decColumn();
	    --ptr;
	}

	/* In Replace mode, don't change characters before the insert point. */
	if ((G.State == REPLACE || G.State == VREPLACE)
		&& fpos.getLine() == Insstart.getLine()
		&& fpos.getColumn() < Insstart.getColumn())
	{
	    ptr += Insstart.getColumn() - fpos.getColumn();
	    fpos.setColumn(Insstart.getColumn());
	}

	/* compute virtual column numbers of first white and cursor */
        MutableInt pInt = new MutableInt();
	getvcol(G.curwin, fpos, pInt, null, null);
        vcol = pInt.getValue();
	getvcol(G.curwin, cursor, pInt, null, null);
        want_vcol = pInt.getValue();

	/* use as many TABs as possible */
	tab_vcol = (want_vcol / ts) * ts;
	while (vcol < tab_vcol)
	{
	    if (seg.charAt(ptr) != TAB)
	    {
		//seg.charAt(ptr) = TAB;
                seg.array[ptr] = TAB; // know seg is zero based
		if (change_col < 0)
		{
		    change_col = fpos.getColumn();  /* Column of first change */
		    /* May have to adjust Insstart */
		    if (fpos.getLine() == Insstart.getLine()
                            && fpos.getColumn() < Insstart.getColumn()) {
                      Insstart.setColumn(fpos.getColumn());
                      GetChar.changeInsstart();
                    }
		}
	    }
	    fpos.incColumn();
	    ++ptr;
	    vcol = ((vcol + ts) / ts) * ts;
	}

	if (change_col >= 0)
	{
	    /* may need to delete a number of the following spaces */
	    i = want_vcol - vcol;
	    ptr += i;
	    fpos.setColumn(fpos.getColumn() + i);
	    i = cursor.getColumn() - fpos.getColumn();
	    if (i > 0)
	    {
		//mch_memmove(ptr, ptr + i, STRLEN(ptr + i) + 1);
                for(int j = 0; ptr + i + j < seg.count; j++) {
                    seg.array[ptr + j] = seg.array[ptr + i + j];
                }
		/* correct replace stack. */
		if (G.State == REPLACE)
		    for (temp = i; --temp >= 0; )
			replace_join(want_vcol - vcol);
	    }
	    //cursor.getColumn() -= i;
	    cursor.setColumn(cursor.getColumn() - i);

	    /*
	     * In VREPLACE mode, we haven't changed anything yet.  Do it now by
	     * backspacing over the changed spacing and then inserting the new
	     * spacing.
	     */
	    if (useTempCopy || G.State == VREPLACE)
	    {
		/* Backspace from real cursor to change_col */
		backspace_until_column(change_col);

		/* Insert each char in saved_line from changed_col to
		 * ptr-cursor */
		while (change_col < cursor.getColumn())
		    ins_char(seg.array[change_col++]);
	    }
	}

	// if (G.State == VREPLACE)
	//     vim_free(saved_line);
    }

    return false;
  }

  /**
   * Handle CR or NL in insert mode.
   * Return TRUE when out of memory.
   */
  static void ins_eol(int c) {
    // if (echeck_abbr(c + ABBR_OFF)) return FALSE;

    stop_arrow();

    // Strange Vi behaviour: In Replace mode, typing a NL will not delete the
    // character under the cursor.  Only push a NUL on the replace stack,
    // nothing to put back when the NL is deleted.

    if (G.State == REPLACE)
      replace_push(NUL);

    // In VREPLACE mode, a NL replaces the rest of the line, and starts
    // replacing the next line, so we push all of the characters left on the
    // line onto the replace stack.  This is not done here though, it is done
    // in open_line().

    GetChar.AppendToRedobuff(NL_STR);

    if (G.did_ai && !arrow_used)
      removeUnusedWhiteSpace();

    Misc.open_line(FORWARD, true, false, G.old_indent);
    G.old_indent = 0;
  }

  private static void ins_left() throws NotSupportedException {
    ViFPOS	tpos;

    if (G.fdo_flags().contains(FDO_HOR) && G.KeyTyped)
      foldOpenCursor();
    undisplay_dollar();
    ViFPOS cursor = G.curwin.w_cursor;
    tpos = cursor.copy();
    if (oneleft() == OK)
    {
	start_arrow(tpos);
                              //#ifdef RIGHTLEFT............#endif
    }

    //
    // if 'whichwrap' set for cursor in insert mode may go to
    // previous line
    //
    //else if (vim_strchr(p_ww, '[') != NULL && curwin->w_cursor.lnum > 1)
    else if (G.p_ww_i_left && cursor.getLine() > 1)
    {
	start_arrow(tpos);
        G.curwin.w_cursor.set(cursor.getLine() - 1, 0);
	Misc.coladvance(MAXCOL);
        G.curwin.w_set_curswant = true;	// so we stay at the end
    }
    else
	Util.vim_beep();
  }

  private static void ins_home() throws NotSupportedException {
    ViFPOS	tpos;

    if (G.fdo_flags().contains(FDO_HOR) && G.KeyTyped)
      foldOpenCursor();
    undisplay_dollar();
    tpos = G.curwin.w_cursor.copy();

    int line = G.curwin.w_cursor.getLine();
    if ((G.mod_mask & MOD_MASK_CTRL) != 0)
      line = 1;
    int col = 0;
    G.curwin.w_cursor.set(line, col);
    G.curwin.updateCurswant(null, col);
    start_arrow(tpos);
  }

  private static void ins_end() throws NotSupportedException {
    ViFPOS	tpos;

    if (G.fdo_flags().contains(FDO_HOR) && G.KeyTyped)
      foldOpenCursor();
    undisplay_dollar();
    tpos = G.curwin.w_cursor.copy();
    if ((G.mod_mask & MOD_MASK_CTRL) != 0) {
      G.curwin.w_cursor.set(G.curbuf.getLineCount(), 0);
    }
    Misc.coladvance(MAXCOL);
    G.curwin.updateCurswant(null, MAXCOL);
    start_arrow(tpos);
  }

  private static void ins_s_left() throws NotSupportedException {
    if (G.fdo_flags().contains(FDO_HOR) && G.KeyTyped)
      foldOpenCursor();
    undisplay_dollar();

    ViFPOS cursor = G.curwin.w_cursor;
    if(cursor.getLine() > 1 || cursor.getColumn() > 0)
    {
        start_arrow(cursor);
	Search.bck_word(1, false, false);
        G.curwin.w_set_curswant = true;
    }
    else
	Util.vim_beep();
  }

  private static void ins_right() throws NotSupportedException {
    if (G.fdo_flags().contains(FDO_HOR) && G.KeyTyped)
      foldOpenCursor();
    undisplay_dollar();
    ViFPOS cursor = G.curwin.w_cursor;
    if (Misc.gchar_cursor() != '\n') {
      start_arrow(cursor);
                              //#ifdef MULTI_BYTE............#endif
                              //#ifdef MULTI_BYTE............#endif
      G.curwin.w_set_curswant = true;
      //++curwin->w_cursor.col;
      G.curwin.w_cursor.set(cursor.getOffset() +1);
                              //#ifdef RIGHTLEFT............#endif
    }
    // if 'whichwrap' set for cursor in insert mode, may move the
    // cursor to the next line
    else if (G.p_ww_i_right
             && cursor.getLine() < G.curbuf.getLineCount())
    {
      start_arrow(cursor);
      G.curwin.w_set_curswant = true;
      G.curwin.w_cursor.set(cursor.getLine() +1, 0);
    }
    else
      Util.vim_beep();
  }

  private static void ins_s_right() throws NotSupportedException {
    if (G.fdo_flags().contains(FDO_HOR) && G.KeyTyped)
      foldOpenCursor();
    undisplay_dollar();
    ViFPOS cursor = G.curwin.w_cursor;
    if (cursor.getLine() < G.curbuf.getLineCount()
        || Misc.gchar_cursor() != '\n')
    {
	start_arrow(cursor);
	Search.fwd_word(1, false, false);
        G.curwin.w_set_curswant = true;
    }
    else
	Util.vim_beep();
  }

  private static void ins_up() throws NotSupportedException {
    ViFPOS tpos;
                                    //int old_topline;

    undisplay_dollar();
    tpos = G.curwin.w_cursor.copy();
                                    //old_topline = curwin->w_topline;
    if (cursor_up(1, true) == OK)
    {
                                        //if (old_topline != curwin->w_topline)
                                            //update_screen(VALID);
	start_arrow(tpos);
                                //#ifdef CINDENT............#endif
    }
    else
	Util.vim_beep();
  }

  private static void ins_pageup() throws NotSupportedException {
    Normal.notImp("ins_pageup");
    /*
    FPOS	tpos;

    undisplay_dollar();
    tpos = curwin->w_cursor;
    if (onepage(BACKWARD, 1L) == OK)
    {
	start_arrow(&tpos);
                //#ifdef CINDENT............#endif
    }
    else
	vim_beep();
    */
  }

  private static void ins_down() throws NotSupportedException {
    ViFPOS tpos;
                                      //int old_topline = curwin->w_topline;

    undisplay_dollar();
    tpos = G.curwin.w_cursor.copy();
    if (cursor_down(1, true) == OK)
    {
                                          //if (old_topline != curwin->w_topline)
                                              //update_screen(VALID);
	start_arrow(tpos);
                                //#ifdef CINDENT............#endif
    }
    else
	Util.vim_beep();
  }

  private static void ins_pagedown() throws NotSupportedException {
    Normal.notImp("ins_pagedown");
    /*
    FPOS	tpos;

    undisplay_dollar();
    tpos = curwin->w_cursor;
    if (onepage(FORWARD, 1L) == OK)
    {
	start_arrow(&tpos);
              //#ifdef CINDENT...........#endif
    }
    else
	vim_beep();
    */
  }

  /**
   * Handle CTRL-E and CTRL-Y in Insert mode: copy char from other line.
   * Returns the char to be inserted, or NUL if none found.
   */
  private static char ins_copychar(int lnum) {
    char    c;
    int	    temp;
    MySegment  seg;
    int ptr;

    if (lnum < 1 || lnum > G.curbuf.getLineCount()) {
      Util.vim_beep();
      return NUL;
    }

    /* try to advance to the cursor column */
    temp = 0;
    seg = Util.ml_get(lnum);
    ptr = seg.offset;
    //validate_virtcol();
    MutableInt mi = new MutableInt();
    getvcol(G.curwin, G.curwin.w_cursor, null, mi, null);
    int virtcol = mi.getValue();
    while (temp < virtcol && seg.array[ptr] != '\n')
      temp += Misc.lbr_chartabsize(seg.array[ptr++], temp);

    if (temp > virtcol)
      --ptr;
    if ((c = seg.array[ptr]) == '\n') {
      Util.vim_beep();
      c = 0;
    }
    return c;
  }

    /*
     * Backspace the cursor until the given column.  Handles REPLACE and VREPLACE
     * modes correctly.  May also be used when not in insert mode at all.
     */
    static void backspace_until_column(int col)
    {
        while (G.curwin.w_cursor.getColumn() > col)
        {
            G.curwin.w_cursor.decColumn();
            if (G.State == REPLACE || G.State == VREPLACE)
                replace_do_bs();
            else
                del_char(false);
        }
    }

    /**
     * Get the value that w_virtcol would have when 'list' is off.
     * Unless 'cpo' contains the 'L' flag.
     */
    private static int get_nolist_virtcol() {
        // THIS IS A BIG SIMPLIFICATION FROM STOCK
        MutableInt	virtcol = new MutableInt();
        getvcol(G.curwin, G.curwin.w_cursor, null, virtcol, null);
        return virtcol.getValue();
    }

  /**
   * Add some behavior, rather than op not supported exception, to mark.
   * Initially used for Insstart.
   */
  private static class DynamicMark implements ViFPOS {
    ViMark mark;

    public DynamicMark(ViBuffer buf, ViFPOS fpos) {
      mark = buf.createMark(null);
      mark.setMark(fpos);
    }

    @Override
    public void set(ViFPOS fpos) {
      mark.setMark(fpos);
    }

    @Override
    public void setColumn(int col) {
      ViFPOS fpos = mark.getBuffer().createFPOS(mark.getOffset());
      fpos.setColumn(col);
      mark.setMark(fpos);
    }

    @Override
    public void decLine() {
      ViFPOS fpos = mark.getBuffer().createFPOS(mark.getOffset());
      fpos.decLine();
      mark.setMark(fpos);
    }

    ///////////////////////////////////////////////////////////////////////
    //
    // DELEGATE below this line
    //

    @Override
    public String toString() {
      return mark.toString();
    }

    @Override
    public ViFPOS copyTo(ViFPOS target)
    {
      return mark.copyTo(target);
    }

    @Override
    public int hashCode() {
      return mark.hashCode();
    }

    @Override
    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    public boolean equals(Object obj) {
      return mark.equals(obj);
    }

    @Override
    public int compareTo(ViFPOS o) {
      return mark.compareTo(o);
    }

    @Override
    public boolean isValid() {
      return mark.isValid();
    }

    @Override
    public void verify(Buffer buf) {
      mark.verify(buf);
    }

    @Override
    public void setLine(int line) {
      mark.setLine(line);
    }

    @Override
    public void set(int offset) {
      mark.set(offset);
    }

    @Override
    public void set(int line, int column) {
      mark.set(line, column);
    }

    @Override
    public void incLine() {
      mark.incLine();
    }

    @Override
    public void incColumn() {
      mark.incColumn();
    }

    @Override
    public int getOffset() {
      return mark.getOffset();
    }

    public int getOriginalColumnDelta() {
      return mark.getOriginalColumnDelta();
    }

    @Override
    public int getLine() {
      return mark.getLine();
    }

    @Override
    public int getColumn() {
      return mark.getColumn();
    }

    @Override
    public void decColumn() {
      mark.decColumn();
    }

    @Override
    public ViFPOS copy() {
      return mark.copy();
    }

    @Override
    public Buffer getBuffer() {
      return mark.getBuffer();
    }

  }

}

// vi:set sw=2 ts=8:
