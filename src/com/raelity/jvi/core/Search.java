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

import com.raelity.jvi.manager.ViManager;
import com.raelity.jvi.ViCmdEntry;
import com.raelity.jvi.ViFPOS;
import com.raelity.jvi.ViOutputStream;
import com.raelity.jvi.lib.MutableInt;
import com.raelity.jvi.manager.Scheduler;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import javax.swing.event.ChangeEvent;

import com.raelity.text.*;
import com.raelity.text.TextUtil.MySegment;

import java.awt.EventQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.event.ChangeListener;

import static com.raelity.jvi.core.Constants.*;
import static com.raelity.jvi.core.KeyDefs.*;
import static com.raelity.jvi.core.MarkOps.*;
import static com.raelity.jvi.core.Misc.*;
import static com.raelity.jvi.core.Misc01.*;
import static com.raelity.jvi.core.Search01.*;
import static com.raelity.jvi.core.Util.*;

/**
 * Searching, regexp and substitution.
 * Everything's static, can only do one thing at a time.
 */
public class Search {
  private static final Logger LOG = Logger.getLogger(Search.class.getName());

  ///////////////////////////////////////////////////////////////////////
  //
  // normal searching like "/" and "?"
  //
  private static ViCmdEntry searchCommandEntry;
  
  // parameters for the current search, they do not change during search
  private static int searchCount;
  private static int searchFlags;
  private static int lastDir = FORWARD;
  
  // state when incremental search started
  private static ViFPOS searchPos;
  private static int searchTopLine;
  private static boolean didIncrSearch;
  private static boolean setPCMarkAfterIncrSearch;
  private static boolean incrSearchSucceed;

  // for next command and such
  private static String lastPattern;
  private static String lastSubstitution;

  private Search() { }

  static ViCmdEntry getSearchCommandEntry() {
    if(searchCommandEntry == null) {
      searchCommandEntry = ViManager.getFactory()
                            .createCmdEntry(ViCmdEntry.Type.SEARCH);
      searchCommandEntry.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent ev) {
            searchEntryComplete(ev);
          }   });
    }
    return searchCommandEntry;
  }
  
  private static String fetchPattern() {
      return getSearchCommandEntry().getCommand();
  }

  static private void searchEntryComplete(ActionEvent ev) {
    try {
      Hook.setJViBusy(true);
      String cmd = ev.getActionCommand();
      boolean acceptIncr = false;
      boolean cancel = false;
      
      Scheduler.stopCommandEntry();
      
      if(cmd.charAt(0) == '\n') {
        if(G.p_is.getBoolean()
           && didIncrSearch
           && ! "".equals(fetchPattern()))
          acceptIncr = true;
      } else
        cancel = true;
      
      if(G.p_is.getBoolean()) {
        stopIncrementalSearch(acceptIncr);
      }
      
      if(acceptIncr)
        GetChar.fakeGotc(K_X_INCR_SEARCH_DONE);
      else if(cancel)
        GetChar.fakeGotc(K_X_SEARCH_CANCEL);
      else
        GetChar.fakeGotc(K_X_SEARCH_FINISH);
    } finally {
      Hook.setJViBusy(false);
    }
  }
  
  /** Start the entry dialog and stash the interesting info for later use
   *  int doSearch(). */
  static void inputSearchPattern(CMDARG cap, int count, int flags) {
    String mode = "";
    int cmdchar = cap.cmdchar;
    if (cmdchar == '/') {
      mode = "/";
      lastDir = FORWARD;
    } else if (cmdchar == '?') {
      mode = "?";
      lastDir = BACKWARD;
    }
    searchCount = count;
    searchFlags = flags;
    
    ViCmdEntry ce = getSearchCommandEntry();
    if(G.p_is.getBoolean())
        startIncrementalSearch();
    Scheduler.startCommandEntry(ce, mode, G.curwin, null);
  }

  static int getIncrSearchResultCode() {
      return incrSearchSucceed ? OK : FAIL;
  }

  // This is used to grab the pattern after search complete, for redoBuffer.
  static String last_search_pat() {
    return lastPattern;
  }
  
  /** doSearch() should only be called after inputSearchPattern() */
  static int doSearch() {
    String pattern = fetchPattern();
    G.curwin.w_set_curswant = true;
    if(pattern.isEmpty()) {
      if(lastPattern == null) {
        Msg.emsg(Messages.e_noprevre);
        return 0;
      }
      pattern = lastPattern;
    }
    lastPattern = pattern;
    // executeSearch(pattern, lastDir, G.p_ic.getBoolean());
    ViFPOS pos = G.curwin.w_cursor.copy();
    int rc = searchit(null, pos, lastDir, pattern,
                      searchCount, searchFlags, 0, G.p_ic.getBoolean());
    if(rc != FAIL) {
      Msg.smsg((lastDir == FORWARD ? "/" : "?") + pattern);
    }
    
    if(rc == FAIL) {
      return 0;
    } else {
      return 1; // NEEDSWORK: not returning 2 ever, so not line mode.
    }
    
    /* ***************************
    if(rc == FAIL) {
      Normal.clearopInstance();
    }
    ******************************/
  }
  
  private static void laterDoIncrementalSearch() {
    EventQueue.invokeLater(new Runnable() {
      @Override
      public void run()
      {
        doIncrementalSearch();
      }
    });
  }

  private static class SearchListener implements ChangeListener
  {
    @Override
    public void stateChanged(ChangeEvent e)
    {
      laterDoIncrementalSearch();
    }
  }

  private static SearchListener isListener;

  private static void startIncrementalSearch() {
      // funny aborts might leave one...
      // NEEDSWORK: Might want to use a weak listen as well
      getSearchCommandEntry().removeChangeListener(isListener);
      if(isListener == null)
        isListener = new SearchListener();
      searchPos = G.curwin.w_cursor.copy();
      searchTopLine = G.curwin.getVpTopDocumentLine();
      setPCMarkAfterIncrSearch = (searchFlags & SEARCH_MARK) != 0;
      searchFlags &= ~SEARCH_MARK;
      didIncrSearch = false;
      incrSearchSucceed = false;
      getSearchCommandEntry().addChangeListener(isListener);
  }
  
  private static void stopIncrementalSearch(boolean accept) {
      getSearchCommandEntry().removeChangeListener(isListener);

      G.curwin.clearSelection(); // since it is used by incr search
      
      if(accept) {
          lastPattern = fetchPattern();
      } else
        resetViewIncrementalSearch();

      // NEEDSWORK: setpcmark if accept == false ????????
      if(setPCMarkAfterIncrSearch && incrSearchSucceed) {
        setpcmark(searchPos);
      }
  }
  
  private static void resetViewIncrementalSearch() {
    G.curwin.setVpTopLine(searchTopLine);
    G.curwin.setCaretPosition(searchPos.getOffset());
  }
  
  private static void doIncrementalSearch() {
    try {
      Hook.setJViBusy(true);
      String pattern = getSearchCommandEntry().getCurrentEntry();
      
      if("".equals(pattern)) {
        resetViewIncrementalSearch();
        return;
      }
      ViFPOS pos = searchPos.copy();
      incrSearchSucceed = false;
      int rc = searchit(null, pos, lastDir, pattern,
                        searchCount, searchFlags /*& ~SEARCH_MSG*/,
                        0, G.p_ic.getBoolean());
      // for incr search, use java selection to show progress
      int new_pos = G.curwin.w_cursor.getOffset();
      G.curwin.setSelection(new_pos, new_pos + search_match_len);

      didIncrSearch = true;
      if(rc == FAIL) {
        resetViewIncrementalSearch();
        searchitErrorMessage(null);
      } else
        incrSearchSucceed = true;
    } catch(Exception ex) {
        LOG.log(Level.SEVERE, null, ex);
    } finally {
      Normal.v_updateVisualState();
      Hook.setJViBusy(false);
    }
  }

  static int doNext(CMDARG cap, int count, int flag) {
    G.curwin.w_set_curswant = true;
    int dir = ((flag & SEARCH_REV) != 0 ? - lastDir : lastDir);
    //G.curwin.repeatSearch(dir);
    int rc = FAIL;
    if(lastPattern == null) {
      Msg.emsg(Messages.e_noprevre);
    } else {
      Msg.smsg((dir == FORWARD ? "/" : "?") + lastPattern);
      // executeSearch(lastPattern, dir, G.p_ic.getBoolean());
      ViFPOS pos = G.curwin.w_cursor.copy();
      rc = searchit(null, pos, dir, lastPattern,
                        count, flag, 0, G.p_ic.getBoolean());
    }
    return rc;
  }

  ////////////////////////////////////////////////////////////////
  //
  // searchc
  //

  /** for searchc, last character searched for. */
  static int	    lastc = 0;
  /** for searchc, last direction of character search. */
  static int	    lastcdir;
  /** for searchc, last type of search ("find" or "to"). */
  static boolean    lastctypeT;

  /**
   * searchc(c, dir, typeT, count);
   *
   * Search for character 'c', in direction 'dir'. If 'typeT' is 0, move to the
   * position of the character, otherwise move to just before the char.
   * Repeat this 'count' times.
   */
  static boolean searchc(int c, int dir, boolean typeT, int count) {
    int		    col;
    int		    len;

    if (c != 0)	/* normal search: remember args for repeat */
    {
      // if (!KeyStuffed)    // don't remember when redoing
      {
	lastc = c;
	lastcdir = dir;
	lastctypeT = typeT;
      }
    } else {		// repeat previous search
      if (lastc == 0)
	return false;
      if (dir != 0)	// repeat in opposite direction
	dir = -lastcdir;
      else
	dir = lastcdir;
      typeT = lastctypeT;
      c = lastc;
    }

    ViFPOS fpos = G.curwin.w_cursor;
    MySegment seg = G.curbuf.getLineSegment(fpos.getLine());
    col = fpos.getColumn();
    len = seg.count - 1; // don't count the newline, MUST_HAVE_NL

    while (count-- != 0)
    {
      for (;;)
      {
	if ((col += dir) < 0 || col >= len)
	  return false;
	if (seg.array[seg.offset + col] == c)
	  break;
      }
    }
    if (typeT) {
      col -= dir;
    }
    // curwin->w_cursor.col = col;
    G.curwin.setCaretPosition(
		  col + G.curbuf
			  .getLineStartOffsetFromOffset(fpos.getOffset()));
    return true;
  }

  ////////////////////////////////////////////////////////////////////
  //
  // The following routines do the word searches performed by the 'w', 'W',
  // 'b', 'B', 'e', and 'E' commands.
  //

  //
  // To perform these searches, characters are placed into one of three
  // classes, and transitions between classes determine word boundaries.
  //
  // The classes are:
  //
  // 0 - white space
  // 1 - keyword charactes (letters, digits and underscore)
  // 2 - everything else
  //

  /* type of the word motion being performed,
   * true implies capital motion (W, E) vs (w, e).
   */
  static boolean funnyCharsAsWord;

  /**
   * cls() - returns the class of character at curwin->w_cursor
   *<br>
   * The 'type' of the current search modifies the classes of characters if a
   * 'W', 'B', or 'E' motion is being done. In this case, chars. from class 2
   * are reported as class 1 since only white space boundaries are of interest.
   */
  static int cls() {
    return cls(G.curwin.w_cursor);
  }
  private static int cls(ViFPOS fpos) {
    char    c;

    c = gchar_pos(fpos);
    if (c == ' ' || c == '\t' || c == '\n')
      return 0;

    if (Misc.vim_iswordc(c))
      return 1;

    //
    // If stype is non-zero, report these as class 1.
    //
    return ( ! funnyCharsAsWord) ? 2 : 1;
  }


  /**
   * fwd_word(count, type, eol) - move forward one word
   *<br>
   * Returns FAIL if the cursor was already at the end of the file.
   * If eol is TRUE, last word stops at end of line (for operators).
   */
  static int fwd_word(int count, boolean type, boolean eol) {
    ViFPOS fpos = G.curwin.w_cursor.copy();
    int rc = fwd_word(count, type, eol, fpos);
    G.curwin.setCaretPosition(fpos.getOffset());
    return rc;
  }
  static int fwd_word(int count, boolean type, boolean eol, ViFPOS fpos) {
    int		sclass;	    /* starting class */
    int		i;
    boolean	last_line;

    funnyCharsAsWord = type;
    while (--count >= 0) {
      sclass = cls(fpos);

      //
      // We always move at least one character, unless on the last character
      // in the buffer.
      //
      last_line = (fpos.getLine() == G.curbuf.getLineCount());
      i = inc(fpos);
      if (i == -1 || (i == 1 && last_line)) // started at last char in file
	return FAIL;
      if (i == 1 && eol && count == 0)      // started at last char in line
	return OK;

      //
      // Go one char past end of current word (if any)
      //
      if (sclass != 0)
	while (cls(fpos) == sclass) {
	  i = inc(fpos);
	  if (i == -1 || (i == 1 && eol && count == 0))
	    return OK;
	}

      //
      // go to next non-white
      //
      while (cls(fpos) == 0) {
	//
	// We'll stop if we land on a blank line
	//
	if(fpos.getColumn() == 0
	   	&& gchar_pos(fpos) == '\n') {
	  break;
	}

	i = inc(fpos);
	if (i == -1 || (i == 1 && eol && count == 0))
	  return OK;
      }
    }
    return OK;
  }

  /**
   * bck_word() - move backward 'count' words
   *<br>
   * If stop is TRUE and we are already on the start of a word, move one less.
   *
   * @return FAIL if top of the file was reached.
   */
  static int bck_word(int count, boolean type, boolean stop) {
    ViFPOS fpos = G.curwin.w_cursor.copy();
    int rc = bck_word(count, type, stop, fpos);
    G.curwin.setCaretPosition(fpos.getOffset());
    return rc;
  }
  static int bck_word(int count, boolean type, boolean stop, ViFPOS fpos) {
    int		sclass;	    /* starting class */

    funnyCharsAsWord = type;
    while (--count >= 0) {
      sclass = cls(fpos);
      if (dec(fpos) == -1)     /* started at start of file */
	return FAIL;

finished_block:
      do {
	if (!stop || sclass == cls(fpos) || sclass == 0) {
	  //
	  // Skip white space before the word.
	  // Stop on an empty line.
	  //
	  while (cls(fpos) == 0) {
	    if (fpos.getColumn() == 0 && lineempty(fpos.getLine()))
	      break finished_block;
	    if (dec(fpos) == -1)   // hit start of file, stop here
	      return OK;
	  }

	  //
	  // Move backward to start of this word.
	  //
	  if (skip_chars(cls(fpos), BACKWARD, fpos))
	    return OK;
	}

	inc(fpos);		 // overshot - forward one
      } while(false); // was a label here - finished:
      stop = false;
    }
    return OK;
  }

  /**
   * end_word() - move to the end of the word
   *<br>
   * There is an apparent bug in the 'e' motion of the real vi. At least on the
   * System V Release 3 version for the 80386. Unlike 'b' and 'w', the 'e'
   * motion crosses blank lines. When the real vi crosses a blank line in an
   * 'e' motion, the cursor is placed on the FIRST character of the next
   * non-blank line. The 'E' command, however, works correctly. Since this
   * appears to be a bug, I have not duplicated it here.
   *<br>
   * If stop is TRUE and we are already on the end of a word, move one less.
   * If empty is TRUE stop on an empty line.
   *
   * @return FAIL if end of the file was reached.
   */
  static int end_word(int count, boolean type, boolean stop, boolean empty) {
    ViFPOS fpos = G.curwin.w_cursor.copy();
    int rc = end_word(count, type, stop, empty, fpos);
    G.curwin.setCaretPosition(fpos.getOffset());
    return rc;
  }
  static int end_word(
          int count, boolean type, boolean stop, boolean empty, ViFPOS fpos)
  {
    int		sclass;	    /* starting class */

    funnyCharsAsWord = type;
    while (--count >= 0) {
      sclass = cls(fpos);
      if (inc(fpos) == -1)
	return FAIL;

finished_block:
      do {
	//
	// If we're in the middle of a word, we just have to move to the end
	// of it.
	//
	if (cls(fpos) == sclass && sclass != 0) {
	  //
	  // Move forward to end of the current word
	  //
	  if (skip_chars(sclass, FORWARD, fpos))
	    return FAIL;
	} else if (!stop || sclass == 0) {
	  //
	  // We were at the end of a word. Go to the end of the next word.
	  // First skip white space, if 'empty' is TRUE, stop at empty line.
	  //
	  while (cls(fpos) == 0) {
	    if (empty && fpos.getColumn() == 0
		&& lineempty(fpos.getLine()))
	      break finished_block;
	    if (inc(fpos) == -1)    // hit end of file, stop here
	      return FAIL;
	  }

	  //
	  // Move forward to the end of this word.
	  //
	  if (skip_chars(cls(fpos), FORWARD, fpos))
	    return FAIL;
	}
	dec(fpos);	// overshot - one char backward
      } while(false); // was a label here - finished:
finished:
      stop = false;		// we move only one word less
    }
    return OK;
  }

  /**
   * bckend_word(count, type) - move back to the end of the word
   *<br>
   * If 'eol' is TRUE, stop at end of line.
   *
   * @return FAIL if start of the file was reached.
   */
  static int bckend_word(int count, boolean type, boolean eol) {
    ViFPOS fpos = G.curwin.w_cursor.copy();
    int rc = bckend_word(count, type, eol, fpos);
    G.curwin.setCaretPosition(fpos.getOffset());
    return rc;
  }
  static int bckend_word(int count, boolean type, boolean eol, ViFPOS fpos) {
    int		sclass;	    // starting class
    int		i;

    funnyCharsAsWord = type;
    while (--count >= 0) {
      sclass = cls(fpos);
      if ((i = dec(fpos)) == -1)
	return FAIL;
      if (eol && i == 1)
	return OK;

      //
      // Move backward to before the start of this word.
      //
      if (sclass != 0) {
	while (cls(fpos) == sclass)
	  if ((i = dec(fpos)) == -1 || (eol && i == 1))
	    return OK;
      }

      //
      // Move backward to end of the previous word
      //
      while (cls(fpos) == 0) {
	if (fpos.getColumn() == 0 && lineempty(fpos.getLine()))
	  break;
	if ((i = dec(fpos)) == -1 || (eol && i == 1))
	  return OK;
      }
    }
    return OK;
  }

  /**
   * Skip a row of characters of the same class.
   * @return TRUE when end-of-file reached, FALSE otherwise.
   */
  private static boolean skip_chars(int cclass, int dir) {
    ViFPOS fpos = G.curwin.w_cursor.copy();
    boolean rc = skip_chars(cclass, dir, fpos);
    G.curwin.setCaretPosition(fpos.getOffset());
    return rc;
  }
  private static boolean skip_chars(int cclass, int dir, ViFPOS fpos) {
    while (cls(fpos) == cclass)
      if ((dir == FORWARD ? inc(fpos) : dec(fpos)) == -1)
	return true;
    return false;
  }

  /////////////////////////////////////////////////////////////////////
  //
  // regualr expression handling stuff
  //

  static public RegExp getLastRegExp() {
    if(lastPattern == null)
      return null;
    return getRegExp(lastPattern, G.p_ic.getBoolean());
  }

  //
  // NEEDSWORK: need to build a structure out of a pattern that
  // tracks things like lastRegExp* and forceCase
  //
  // simple cache. Helps "n" and "N" commands
  static RegExp lastRegExp;
  static String lastRegExpPattern = "";
  static boolean lastRegExpIC;

  static int forceCase_CleanupPatternHack; // for \c and \C
  static final int FORCE_CASE_IGNORE = 1;
  static final int FORCE_CASE_EXACT = -1;
  static final int FORCE_CASE_NONE = 0;

  /** Get a compiled regular expression. Clean up the escaping as needed. */
  static RegExp getRegExp(String pattern, boolean ignoreCase) {
    String cleanPattern = cleanupPattern(pattern);

    // If the pattern has an 'ignoreCase' flag built in,
    // then apply the override
    if(forceCase_CleanupPatternHack == FORCE_CASE_EXACT) {
      ignoreCase = false;
    } else if(forceCase_CleanupPatternHack == FORCE_CASE_IGNORE) {
      ignoreCase = true;
    } // else FORCE_CASE_NONE

    // can the last re be reused?
    // NEEDSWORK: getRegExp: cache re's, LRU?
    if(cleanPattern.equals(lastRegExpPattern) && lastRegExpIC == ignoreCase) {
      return lastRegExp;
    }
    RegExp re = null;
    try {
      int flags = ignoreCase ? RegExp.IGNORE_CASE : 0;
      re = RegExpFactory.create();
      // NEEDSWORK: compilePattern vs cleanPattern
      String compilePattern = re.patternType() == RegExp.PATTERN_SIMPLE
                                ? pattern : cleanPattern;
      re.compile(cleanPattern, flags);
      // cache the information
      lastRegExpPattern = cleanPattern;
      lastRegExpIC = ignoreCase;
      lastRegExp = re;
    } catch(RegExpPatternError ex) {
      Msg.emsg(ex.getMessage() + " [" + ex.getIndex() + "]" + pattern);
      //Msg.emsg("Invalid search string: \"" + pattern + "\" " + ex.getMessage());
      re = null;
    }
    return re;
  }

  private static final int ESCAPED_FLAG = 0x10000;
  /**
   * Change metacharacter escaping of input pattern to match
   * the perl5 requirements. Do this because non-standard metachars
   * may be be escaped in patterns according to metaEscape option.
   * <p> (This should be an html table:)
   * <br/>   'c' is escaped      |   'c' is not escaped
   * <br/>        c  --> \c      |         c  --> c
   * <br/>        \c --> c       |         \c --> \c
   * </p><p> Previously, vim did not allow ? to indicate optional atom, so
   * there was some very messy code. Now, since '?' is accepted by vim,
   * just need to deal with converting '=' to '?'. Still messy, but not
   * quite so bad.
   * <br/>   '=' is escaped      |   '=' is not escaped
   * <br/>        c  --> \c      |         c  --> c
   * <br/>        \c --> c       |         \c --> \c
   *
   * </p><p>
   * And finally, either \&lt; or \&gt; are replaced by \b. If there wasn't
   * this last rule, then could just return if p_meta_escape was empty and
   * p_meta_equals was false.
   * </p>
   */
  static String cleanupPattern(String s) {
    forceCase_CleanupPatternHack = FORCE_CASE_NONE;
    String metacharacterEscapes = G.p_meta_escape.getString();
    StringBuilder sb = new StringBuilder();
    boolean isEscaped = false;
    for(int in = 0; in < s.length(); in++) {
      char c = s.charAt(in);
      if( ! isEscaped && c == '\\') {
        isEscaped = true;
        continue;
      }
      
      if((c == '=') && G.p_meta_equals.getBoolean()) {
        // Have an '=' and that char is used to specify an optional atom.
        // Set useEscape if the '=' needs to be escaped to mean optional.
        boolean useEscape = metacharacterEscapes.indexOf('?') >= 0;
        if(isEscaped && useEscape
           || !isEscaped && !useEscape) {
          // the '=' is intened to indicated an optional atom,
          // convert it to a '?'
          c = '?';
        }
      }
      if(metacharacterEscapes.indexOf(c) >= 0) { // metachar gets escaped
        // reverse of what was seen
        if( ! isEscaped) {
          sb.append("\\");
        }
        sb.append(c);
      } else if(isEscaped && (c == '<' || c == '>')) {
        sb.append("\\b");
      } else if(isEscaped && c == 'c') {
        forceCase_CleanupPatternHack = FORCE_CASE_IGNORE;
      } else if(isEscaped && c == 'C') {
        forceCase_CleanupPatternHack = FORCE_CASE_EXACT;
      } else {
        // pass through what was seen
        if(isEscaped) {
          sb.append("\\");
        }
        sb.append(c);
      }
      isEscaped = false;
    }
    return sb.toString();
  }

  static String top_bot_msg = "search hit TOP, continuing at BOTTOM";
  static String bot_top_msg = "search hit BOTTOM, continuing at TOP";

  // HACK: who uses this?
  private static int search_match_len;

  /**
   * lowest level search function.
   * Search for 'count'th occurrence of 'str' in direction 'dir'.
   * Start at position 'pos' and return the found position in 'pos'.
   *
   * <br>if (options & SEARCH_MSG) == 0 don't give any messages
   * <br>if (options & SEARCH_MSG) == SEARCH_NFMSG dont give 'notfound' messages
   * <br>if (options & SEARCH_MSG) == SEARCH_MSG give all messages
   * <br>if (options & SEARCH_HIS) put search pattern in history
   * <br>if (options & SEARCH_END) return position at end of match
   * <br>if (options & SEARCH_START) accept match at pos itself
   * <br>if (options & SEARCH_KEEP) keep previous search pattern
   * <p>
   * Return FAIL (zero) for failure, non-zero for success.
   * When FEAT_EVAL is defined, returns the index of the first matching
   * subpattern plus one; one if there was none.
   * </p>
   * @return FAIL (zero) for failure. 1 no subpattern else subpattern + 1
   */
   //
   // Somehow anchoring at the beginning of the line seems to work fine.
   // Not sure how. The vim code has a "at start of line" flag it passes
   // to the reg exp matcher.
   //
  static int searchit(TextView win,      // BUF,    NOT USED
                      ViFPOS pos,      // FPOS,
                      int dir,
                      String pattern,
                      int count,
                      int options,
                      int pat_use,
                      boolean ignoreCase)
  {
    ViFPOS start_pos;
    boolean found;
    boolean at_first_line;
    boolean match_ok;

    int extra_col;
    int lnum;
    int match = 0;
    int matchend = 0;
    int submatch = 0;
    int p;
    String wmsg = null;


    RegExp prog = getRegExp(pattern, ignoreCase); // various arguments in vim
    if(prog == null) {
        //Msg.emsg("Invalid search string: " + pattern);
        return FAIL;
    }

    if ((options & SEARCH_START) != 0)
      extra_col = 0;
    else
      extra_col = 1;


    /*
     * find the string
     */
    do	// loop for count
    {
      start_pos = pos.copy(); // remember start pos for detecting no match
      found = false;		// default: not found
      at_first_line = true;	// default: start in first line
      // if (pos.getLine() == 0) // correct lnum for when starting in line 0

      //
      // Start searching in current line, unless searching backwards and
      // we're in column 0.
      //
      if (dir == BACKWARD && start_pos.getColumn() == 0) {
        lnum = pos.getLine() - 1;
        at_first_line = false;
      } else {
        lnum = pos.getLine();
      }

      for (int loop = 0; loop <= 1; ++loop) {   // loop twice if 'wrapscan' set
        for ( ; lnum > 0 && lnum <= G.curbuf.getLineCount();
              lnum += dir, at_first_line = false)
        {
          //
          // Look for a match somewhere in the line.
          //
          //////////ptr = ml_get_buf(buf, lnum, FALSE);
          MySegment seg = G.curbuf.getLineSegment(lnum);
                                                // NEEDSWORK: AT_BOL == TRUE
          //System.err.println("line: " + lnum);
          if(prog.search(seg.array, seg.offset, seg.count)) {
            match = prog.start(0) - seg.offset; // column index
            matchend = prog.stop(0) - seg.offset;
            submatch = first_submatch(prog);
            int eolColumn = G.curbuf.getLineEndOffset(lnum) -
                             G.curbuf.getLineStartOffset(lnum) - 1;
            //
            // Forward search in the first line: match should be after
            // the start position. If not, continue at the end of the
            // match (this is vi compatible).
            //
            if (dir == FORWARD && at_first_line) {
              match_ok = true;
              //
              // When *match == NUL the cursor will be put one back
              // afterwards, compare with that position, otherwise
              // "/$" will get stuck on end of line.
              //
              while (((options & SEARCH_END) != 0) ?
                     (matchend - 1  < start_pos.getColumn() + extra_col)
                     : (match - (match == eolColumn ? 1 : 0)
                                    < start_pos.getColumn() + extra_col))
              {
                //
                // If vi-compatible searching, continue at the end
                // of the match, otherwise continue one position
                // forward.
                //
                if(G.p_cpo_search.getBoolean()) {
                  p = matchend;
                  if (match == p && p != eolColumn)
                    ++p;
                } else {
                  p = match;
                  if (p != eolColumn)
                    ++p;
                }
                if (p != eolColumn
                                                // NEEDSWORK: AT_BOL == FALSE
                    && prog.search(seg.array, seg.offset + p, seg.count - p)) {
                  match = prog.start(0) - seg.offset; // column index
                  matchend = prog.stop(0) - seg.offset;
                  submatch = first_submatch(prog);
                } else {
                  match_ok = false;
                  break;
                }
              }
              if (!match_ok)
                continue;
            }
            if (dir == BACKWARD)
            {
              //
              // Now, if there are multiple matches on this line,
              // we have to get the last one. Or the last one before
              // the cursor, if we're on that line.
              // When putting the new cursor at the end, compare
              // relative to the end of the match.
              //
              match_ok = false;
              for (;;)
              {
                int colIdx = prog.start(0) - seg.offset;
                int matchend01 = prog.stop(0) - seg.offset;
                if (!at_first_line
                    || (((options & SEARCH_END) != 0)
                        ? (matchend01 - 1 + extra_col
                                               <= start_pos.getColumn())
                        : (colIdx + extra_col <= start_pos.getColumn())))
                {
                  match_ok = true;
                  match = colIdx;
                  matchend = matchend01;
                  submatch = first_submatch(prog);
                }
                else
                  break;
                //
                // If vi-compatible searching, continue at the end
                // of the match, otherwise continue one position
                // forward.
                //
                if(G.p_cpo_search.getBoolean()) {
                  p = matchend;
                  if (p == match && p != eolColumn)
                    ++p;
                } else {
                  p = match;
                  if (p != eolColumn)
                    ++p;
                }
                if (p == eolColumn
                                                // NEEDSWORK: AT_BOL == FALSE
                    || !prog.search(seg.array, seg.offset + p, seg.count - p)) {
                  break;
                }
              }

              //
              // If there is only a match after the cursor, skip
              // this match.
              //
              if (!match_ok)
                continue;
            }

            int tcol;
            if (((options & SEARCH_END) != 0) && (options & SEARCH_NOOF) == 0)
              tcol = matchend - 1;
            else
              tcol = match;
            pos.set(lnum, tcol);
            found = true;
            break;
          }
          Misc.line_breakcheck();	// stop if ctrl-C typed
          if (false/*got_int*/)
            break;

          if (loop != 0 && lnum == start_pos.getLine())
            break;	    // if second loop, stop where started
        }
        at_first_line = false;

        //
        // stop the search if wrapscan isn't set, after an interrupt and
        // after a match
        ///
        if (!G.p_ws.getBoolean() /*|| got_int*/ || found)
          break;

        //
        // If 'wrapscan' is set we continue at the other end of the file.
        // If 'shortmess' does not contain 's', we give a message.
        // This message is also remembered in keep_msg for when the screen
        // is redrawn. The keep_msg is cleared whenever another message is
        // written.
        //
        if (dir == BACKWARD) {    // start second loop at the other end
          lnum = G.curbuf.getLineCount();
          if ((options & SEARCH_MSG) != 0)
	    // defer message until after things are positioned.
            // Msg.wmsg(top_bot_msg/*, true*/);
	    wmsg = top_bot_msg;
        } else {
          lnum = 1;
          if ((options & SEARCH_MSG) != 0)
            // Msg.wmsg(bot_top_msg/*, true*/);
	    wmsg = bot_top_msg;
        }
      }
      if (false/*got_int*/)
        break;
    } while (--count > 0 && found);   // stop after count matches or no match

    if (!found) {	    // did not find it
      if (false/*got_int*/)
        searchitErrorMessage(Messages.e_interr);
      else if ((options & SEARCH_MSG) == SEARCH_MSG) {
        if (G.p_ws.getBoolean())
          searchitErrorMessage(Messages.e_patnotf2 + pattern);
        else if (lnum == 0)
          searchitErrorMessage("search hit TOP without match for: " + pattern);
        else
          searchitErrorMessage("search hit BOTTOM without match for: " + pattern);
      }
      search_match_len = 0;
      return FAIL;
    }
    search_match_len = matchend - match;

    if((options & SEARCH_MARK) != 0) {
      setpcmark();
    }
    gotoLine(G.curbuf.getLineNumber(pos.getOffset()), 0, true);
    int new_pos = pos.getOffset();
    if(search_match_len == 0) {
        // search /$ puts cursor on end of line
        new_pos = G.curwin.validateCursorPosition(new_pos);
    }
    G.curwin.w_cursor.set(new_pos);
    G.curwin.w_set_curswant = true;
    if(wmsg != null) {
      Msg.wmsg(wmsg/*, true*/);
    }
    return submatch + 1;
  }
  private static String lastSearchitErrorMessage;
  /** null means reprint the last one */
  private static void searchitErrorMessage(String s)
  {
    if(s != null) {
      lastSearchitErrorMessage = s;
    }
    if(lastSearchitErrorMessage != null)
      Msg.emsg(lastSearchitErrorMessage);
  }

/**
 * Return the number of the first subpat that matched.
 */
    static int
first_submatch(RegExp rp)
{
    int		submatch;
    int         n = rp.nGroup();

    for (submatch = 1; ; ++submatch)
    {
        if(submatch > n || submatch > 9)
        {
	    submatch = 0;
	    break;
	}
	if (rp.start(submatch) >= 0)
	    break;
    }
    return submatch;
}

  ////////////////////////////////////////////////////////////////
  //
  // Stuff from ex_cmds.c
  //
  
  private static int nSubMatch;
  private static int nSubChanges;
  private static int nSubLine = 0;
  
  private static String lastSubstituteArg;

  private static final int SUBST_ALL      = 0x0001;
  private static final int SUBST_PRINT    = 0x0002;
  private static final int SUBST_CONFIRM  = 0x0004;
  private static final int SUBST_ESCAPE   = 0x0008;
  private static final int SUBST_QUIT     = 0x0010;
  private static final int SUBST_DID_ACK  = 0x0020;

  private static MutableInt substFlags;

  /**
   * Substitute command
   * @param cev cev's first arg is /pattern/substitution/{flags}
   */
  static void substitute(ColonCommands.ColonEvent cev) {
    // The substitute command doesn't parse arguments,
    // so it has 0 or 1 argument.
    String cmd;
    if(cev.getNArg() == 0) {
      cmd = lastSubstituteArg;
      if(cmd == null) {
	Msg.emsg("No previous substitute argument");
	return;
      }
    } else {
      cmd = cev.getArg(1);
      lastSubstituteArg = cmd;
    }
    String pattern = null;
    RegExp prog = null;
    CharSequence substitution;
    char delimiter = cmd.charAt(0);
    MySegment line;
    int cursorLine = 0; // set to line number of last change
    int sidx = 1; // after delimiter

    boolean newFlags = false;
    if(!G.global_busy || substFlags == null) {
      substFlags = new MutableInt();
      newFlags = true;
    }
    
    //
    // pick up the pattern
    //

    int sidx01 = sidx;
    sidx = skip_regexp(cmd, sidx, delimiter, true);
    if(sidx01 == sidx) {
      pattern = lastPattern;
    } else {
      pattern = cmd.substring(sidx01, sidx);
      lastPattern = pattern;
    }
    Options.newSearch();
    
    //
    // pick up the substitution string
    //

    sidx++; // first char of substitution
    sidx01 = sidx;
    for( ; sidx < cmd.length(); sidx++) {
      char c = cmd.charAt(sidx);
      if(c == delimiter) {
        break;
      }
      if(c == '\\' && sidx+1 < cmd.length()) {
        ++sidx;
      }
    }
    if(sidx01 == sidx) {
      lastSubstitution = "";
    } else {
      lastSubstitution = cmd.substring(sidx01, sidx);
    }
    substitution = lastSubstitution;

    if(newFlags) {
      //
      // pick up the flags
      //
                // NEEDSWORK: || lastSubstitution.indexOf('~', sidx01) != -1;
      if(lastSubstitution.indexOf('\\') != -1
                  || lastSubstitution.indexOf('&') != -1)
        substFlags.setBits(SUBST_ESCAPE);
    
      
      ++sidx; // move past the delimiter
      for( ; sidx < cmd.length(); sidx++) {
        char c = cmd.charAt(sidx);
        switch(c) {
          case 'g':
            substFlags.setBits(SUBST_ALL);
            break;
          
          case 'p':
            substFlags.setBits(SUBST_PRINT);
            break;
          
          case 'c':
            substFlags.setBits(SUBST_CONFIRM);
            break;
          
          case ' ':
            // silently ignore blanks
            break;
            
          default:
            Msg.emsg("ignoring flag: '" + c + "'");
            break;
        }
      }
    }
    
    //
    // compile regex
    //
    
    prog = getRegExp(pattern, G.p_ic.getBoolean());
    if(prog == null) {
      return;
    }

    int line1 = cev.getLine1();
    int line2 = cev.getLine2();
    StringBuffer sb;
    if(! G.global_busy) {
      nSubLine = 0;
      nSubMatch = 0;
      nSubChanges = 0;
    }
    for(int i = line1;
          i <= line2 && !substFlags.testAnyBits(SUBST_QUIT);
          i++) {
      int nChange = substitute_line(prog, i, substFlags, substitution);
      if(nChange > 0) {
        nSubChanges += nChange;
        cursorLine = i;  // keep track of last line changed
        nSubLine++;
        if(substFlags.testAnyBits(SUBST_PRINT)) {
          ColonCommands.outputPrint(i, 0, 0);
        }
      }
    }
    
    if(! G.global_busy) {
      if(cursorLine > 0) {
	gotoLine(cursorLine, BL_WHITE | BL_FIX, true);
      }
      if(nSubMatch == 0) {
	Msg.emsg(Messages.e_patnotf2 + pattern);
      } else {
	do_sub_msg();
      }
    }
    
  }

  /**
   * Give message for number of substitutions.
   * Can also be used after a ":global" command.
   * Return TRUE if a message was given.
   */
  private static boolean do_sub_msg() {
    if(nSubChanges >= G.p_report.getInteger()) {
      String msg = "" + nSubChanges + " substitution" + Misc.plural(nSubChanges)
		   + " on " + nSubLine + " line" + Misc.plural(nSubLine);
      G.curwin.getStatusDisplay().displayStatusMessage(msg);
      return true;
    }
    return false;
  }

  private static char modalResponse;
  /**
   * This method preforms the substitution within one line of text.
   * This is not adapted vim code.
   * <br><b>NEEDSWORK:</b><ul>
   * <li>Handle more flags, not just doAll.
   * </ul>
   * @param prog the compiled regular expression
   * @param line text to check for match and substitue
   * @param doAll if true, substitute all occurences within the line
   * @param subs the substitution string
   * @param hasEscape if true, then escape processing is needed on <i>subs</i>.
   * @return number of changes on the line
   */
  static int substitute_line(RegExp prog,
                                      int lnum,
                                      MutableInt flags,
                                      CharSequence subs)
  {
    MySegment seg = G.curbuf.getLineSegment(lnum);

    StringBuffer sb = null;
    int lookColumnOffset = 0;
    int lastMatchColumn = -1;
    int countChanges = 0;

    while(prog.search(seg.array,
                      seg.offset + lookColumnOffset,
                      seg.count - lookColumnOffset)) {
      int matchOffsetColumn = prog.start(0) - seg.offset;
      if(lastMatchColumn == matchOffsetColumn) {
        // prevent infinite loops, can happen with match of zero characters
        ++lookColumnOffset;
        // The following statement is true if the lookColumn is on or after
        // the newline. Note that there has already been a successful match.
        // So get out of the loop when looking at newline and have had a match
        if(lookColumnOffset >= seg.count - 1)
          break;
        continue;
      }
      lastMatchColumn = matchOffsetColumn;

      nSubMatch++;
      int segOffsetToDoc = G.curbuf.getLineStartOffset(lnum) - seg.offset;

      modalResponse = 0;
      if(flags.testAnyBits(SUBST_CONFIRM)
              && ! flags.testAnyBits(SUBST_DID_ACK)) {
        G.curwin.setSelection(segOffsetToDoc + prog.start(0),
                           segOffsetToDoc + prog.stop(0)
                            + (prog.length(0) == 0 ? 1 : 0));

        Msg.wmsg("replace with '" + subs + "' (y/n/a/q/l)");
        ViManager.getFactory().startModalKeyCatch(new KeyAdapter() {
                    @Override
          public void keyPressed(KeyEvent e) {
            e.consume();
            char c = e.getKeyChar();
            switch(c) {
              case 'y': case 'n': case 'a': case 'q': case 'l':
                modalResponse = c;
                break;
              case KeyEvent.VK_ESCAPE:
                modalResponse = 'q';
                break;
              default:
                vim_beep();
                break;
            }
            if(modalResponse != 0) {
              ViManager.getFactory().stopModalKeyCatch();
            }
          }
        });
        Msg.clearMsg();
      }

      // advance past matched characters
      lookColumnOffset = prog.stop(0) - seg.offset;
      if(modalResponse != 'n' && modalResponse != 'q') {
        if(sb == null) { // match and do substitute, make sure there's a buffer
          sb = new StringBuffer();
        }
        CharSequence changedData = flags.testAnyBits(SUBST_ESCAPE)
                                ? translateSubstitution(prog, seg, sb, subs)
                                : subs;

        int sizeDelta = changedData.length() - prog.length(0);
        // the column may shifted, adjust by size diff of substitution
        lookColumnOffset += sizeDelta;
        if(prog.length(0) == 0)
          lastMatchColumn += sizeDelta;

        // apply the change to the document
        countChanges++;
        G.curwin.replaceString(segOffsetToDoc + prog.start(0),
                               segOffsetToDoc + prog.stop(0),
                               changedData.toString());

        // the line has changed, fetch changed line
        seg = G.curbuf.getLineSegment(lnum);
      }

      if(modalResponse == 'q' || modalResponse == 'l') {
        flags.setBits(SUBST_QUIT);
        break;
      } else if(modalResponse == 'a') {
        flags.setBits(SUBST_DID_ACK);
      }

      if( ! flags.testAnyBits(SUBST_ALL)) {
        // only do one substitute per line
        break;
      }
    }
    return countChanges;
  }

  /**
   * Handle substitution where there is escape handling.
   * Append the substitution to string buffer argument.
   * This is not adapted vim code.
   * <br><b>NEEDSWORK:</b><ul>
   * <li>append multiple non-escaped chars at once, see jpython's RegexObject
   * </ul>
   * @param prog the compiled regular expression with match result
   * @param line the line that has the match
   * @param sb append substitution to here
   * @param subs substitution string, contains escape characters
   */
  static CharSequence translateSubstitution(RegExp prog,
                                   MySegment line,
                                   StringBuffer sb,
                                   CharSequence subs)
  {
    int i = 0;
    char c;
    sb.setLength(0);

    for( ; i < subs.length(); i++) {
      c = subs.charAt(i);
      switch(c) {
        case '&':
          // copy chars that matched
          sb.append(line.array, prog.start(0), prog.length(0));
          break;

        case '\\':
          if(i+1 < subs.length()) {
            i++;
            c = subs.charAt(i);
            switch(c) {
              case '&':
                sb.append('&');
                break;

              case '0':
              case '1':
              case '2':
              case '3':
              case '4':
              case '5':
              case '6':
              case '7':
              case '8':
              case '9':
                int group = c - '0';
                sb.append(line.array, prog.start(group), prog.length(group));
                break;

              default:
                // escaped a regular character, just append it
                sb.append(c);
                break;
            }
            break; // after handling the escape character
          }

          // last char of string was backslash
          // fall through to append the '\\'

        default:
          sb.append(c);
          break;
      }
    }
    return sb;
  }

  /**
   * global command. first arg is /pattern/some_command_goes_here
   * <br>
   * Only do print for now.
   */

  static void global(ColonCommands.ColonEvent cev) {
    G.global_busy = true;
    try {
      doGlobal(cev);
    }
    finally {
      G.global_busy = false;
    }
  }
  
  static void doGlobal(ColonCommands.ColonEvent cev) {
    if(cev.getNArg() != 1) {
      Msg.emsg("global takes an argument (FOR NOW)");
      return;
    }
    int old_lcount = G.curbuf.getLineCount();
    nSubLine = 0;
    nSubMatch = 0;
    nSubChanges = 0;
    String cmd = cev.getArg(1);
    String cmdExec;
    String pattern = null;
    RegExp prog = null;
    char delimiter = cmd.charAt(0);
    MySegment line;
    int cursorLine = 0; // set to line number of last found line
    int sidx = 1; // after delimiter

    //
    // pick up the pattern
    //

    int sidx01 = sidx;
    sidx = skip_regexp(cmd, sidx, delimiter, true);
    if(sidx01 == sidx) {
      pattern = lastPattern;
    } else {
      pattern = cmd.substring(sidx01, sidx);
      lastPattern = pattern;
    }
    if(lastPattern == null) {
      Msg.emsg(Messages.e_noprevre);
      return;
    }

    //
    // pick up the command
    //

    sidx++; // first char of command
    if(sidx < cmd.length()) {
      cmdExec = cmd.substring(sidx);
    } else {
      cmdExec = "";
    }

    //
    // compile regex
    //

    prog = getRegExp(pattern, G.p_ic.getBoolean());
    if(prog == null) {
      return;
    }

    // figure out what command to execute for all the indicated lines
    
    ActionListener cmdAction = null;
    ColonCommands.ColonEvent cevAction = null;

    if(cmdExec.isEmpty()) {
      // if no command specified then "p" command
      cmdAction = Cc01.getActionPrint();
    } else {
      cevAction = ColonCommands.parseCommand(cmdExec);
      if(cevAction != null) {
	cmdAction = cevAction.getAction();
      }
    }

    int nLine = G.curbuf.getLineCount();
      
    // for now special case a few known commands that can be global'd
    // NEEDSWORK: make global two pass, check vim sources. There's no nice
    // way to keep track of the matched lines for the seconde pass. The only
    // generalized thing that would seem to work is to catch the document
    // events for delete and remove any lines from the global list that are
    // deleted.
    
    ViOutputStream result = null;
    if(cmdAction == Cc01.getActionPrint()) {
      result = ViManager.createOutputStream(G.curwin,
                                            ViOutputStream.SEARCH, pattern);
    } else {
      // Assume it will be handled by the command
      //result = ViManager.createOutputStream(G.curwin,
      //                                      ViOutputStream.LINES, pattern);
    }
    
    substFlags = null;

    for(int lnum = 1; lnum <= nLine; lnum++) {
      line = G.curbuf.getLineSegment(lnum);
      if(prog.search(line.array, line.offset, line.count)) {
	// if full parse each time command executed,
	// then should move cursor (or equivilent) but.....
	if(cevAction != null) {
	  cevAction.line1 = lnum;
	  cevAction.line2 = lnum;
	}
	if(cmdAction == Cc01.getActionPrint()) {
	    result.println(lnum, prog.start(0) - line.offset, prog.length(0));
	} else if(cmdAction == Cc01.getActionSubstitute()) {
	  ColonCommands.executeCommand(cevAction);
          if(substFlags != null && substFlags.testAnyBits(SUBST_QUIT))
            break;
	} else if(cmdAction == Cc01.getActionDelete()) {
	  OPARG oa = ColonCommands.setupExop(cevAction, false);
	  oa.op_type = OP_DELETE;
	  Misc.op_delete(oa);
	  // The troublesome command/situation
	  // A line has just been deleted
	  --nLine;
	  --lnum;
	} else if(cmdAction == Cc01.getActionGlobal()) {
	  Msg.emsg("Cannot do :global recursively");
	  return;
	} else {
	  // no command specified, but cursorLine is getting set above
	}
	cursorLine = lnum;  // keep track of last line matched
      }
    }

    if(cursorLine > 0) {
      gotoLine(cursorLine, BL_WHITE | BL_FIX, true);
    }
    if(result != null)
      result.close();
    
    if( ! do_sub_msg()) {
      Misc.msgmore(G.curbuf.getLineCount() - old_lcount);
    }
  }


  ////////////////////////////////////////////////////////////////
  //
  // Stuff from regexp.c
  //

  /**
   * REGEXP_INRANGE contains all characters which are always special in a []
   * range after '\'.
   */
  static private String REGEXP_INRANGE = "]^-\\";
  /**
   * REGEXP_ABBR contains all characters which act as abbreviations after '\'.
   * These are:
   *  <ul>
   * <li> \r	- New line (CR).
   * <li> \t	- Tab (TAB).
   * <li> \e	- Escape (ESC).
   * <li> \b	- Backspace (Ctrl('H')).
   */
  static private String REGEXP_ABBR = "rteb";

  /**
   * Skip past regular expression.
   * Stop at end of 'p' of where 'dirc' is found ('/', '?', etc).
   * Take care of characters with a backslash in front of it.
   * Skip strings inside [ and ].
   * @param s string containing regular expression
   * @param sidx first char of regular expression
   * @param dirc char that terminates (and started) regular expression
   * @param magic 
   * @return index of char that terminated regexp, may be past end of string
   */
  static int skip_regexp(String s, int sidx, char dirc, boolean magic) {
    char c = (char)0;
    while(sidx < s.length()) {
      c = s.charAt(sidx);
      if (c == dirc)	// found end of regexp
        break;
      if (c == '[' && magic
          || c == '\\'
             && sidx+1 < s.length()
             && s.charAt(sidx+1) == '['
             && ! magic) {
        sidx = skip_range(s, sidx + 1);
        if(sidx >= s.length()) {
          break;
        }
      }
      else if (c == '\\' && sidx+1 < s.length())
        ++sidx;    // skip next character
      ++sidx;
    }
    return sidx;
  }

  /**
   * Skip over a "[]" range.
   * "p" must point to the character after the '['.
   * The returned pointer is on the matching ']', or the terminating NUL.
   */
  static private int skip_range(String s, int sidx) {
    boolean    cpo_lit;	    /* 'cpoptions' contains 'l' flag */
    char c;

    if(sidx >= s.length()) {
      return sidx;
    }

    //cpo_lit = (!reg_syn && vim_strchr(p_cpo, CPO_LITERAL) != NULL);
    cpo_lit = false;

    c = s.charAt(sidx);

    if (c == '^')	/* Complement of range. */
      ++sidx;
    if (c == ']' || c == '-')
      ++sidx;
    while( sidx < s.length()) {
      c = s.charAt(sidx);
      if(c == ']') {
        break;
      }
      if (c == '-') {
        ++sidx;
        if(sidx < s.length() && (c = s.charAt(sidx)) != ']') {
          ++sidx;
        }
      }
      else if (c == '\\') {
        if(sidx+1 < s.length()) {
          char c2 = s.charAt(sidx+1);
          if(vim_strchr(REGEXP_INRANGE, c2) != null
             || (!cpo_lit && vim_strchr(REGEXP_ABBR, c2) != null)) {
            sidx += 2;
          }
        }
      }
      else if (c == '[')
      {
        MutableInt mi = new MutableInt(sidx);
        if (skip_class_name(s, mi) == null) {
          ++sidx; /* It was not a class name */
        } else {
          sidx = mi.getValue();
        }
      }
      else
        ++sidx;
    }

    return sidx;
  }

  static boolean findsent(int dir, int count) {
    ViFPOS pos, tpos;
    char c;
    int i;
    int startlnum;
    boolean noskip = false;
    boolean cpo_J;
    boolean found_dot;

    pos = G.curwin.w_cursor.copy();

    while (count-- > 0) {

found:
      do {
        if (Misc.gchar_pos(pos) == '\n') {
          do {
            if (Misc.inclDeclV7(pos, dir) == -1)
              break;
          } while (Misc.gchar_pos(pos) == '\n');

          if (dir == FORWARD)
            break found;
        }
        else if (dir == FORWARD && pos.getColumn() == 0 &&
          startPS(pos.getLine(), NUL, false)) {
          if (pos.getLine() == G.curbuf.getLineCount())
            return false;
          pos.set(pos.getLine() + 1, 0);
          break found;
        }
        else if (dir == BACKWARD)
          Misc.decl(pos);

        // go back to previous non-blank character
        found_dot = false;
        while ((c = Misc.gchar_pos(pos)) == ' ' || c == '\t' ||
          (dir == BACKWARD && vim_strchr(".!?)]\"'", c) != null)) {
          if (vim_strchr(".!?", c) != null) {
            // Only skip over a '.', '!' and '?' once.
            if (found_dot)
              break;
            found_dot = true;
          }
          if (Misc.decl(pos) == -1)
            break;
          // when going forward: Stop in front of empty line
          if (lineempty(pos.getLine()) && dir == FORWARD) {
            Misc.inclV7(pos);
            break found;
          }
        }

        // remember the line where the search started
        startlnum = pos.getLine();
        cpo_J = G.p_cpo_j.getBoolean();

        for (;;) {
          c = Misc.gchar_pos(pos);
          if (c == '\n' ||
            (pos.getColumn() == 0 && startPS(pos.getLine(), NUL, false))) {
            if (dir == BACKWARD && pos.getLine() != startlnum)
              pos.set(pos.getLine() + 1, 0);
            break;
          }
          if (c == '.' || c == '!' || c == '?') {
            tpos = pos.copy();
            do
              if ((i = Misc.inc(tpos)) == -1)
                break;
            while (vim_strchr(")]\"'", c = Misc.gchar_pos(tpos)) != null);

            if (i == -1 || (!cpo_J && (c == ' ' || c == '\t')) || c == '\n'
                || (cpo_J && (c == ' ' && Misc.inc(tpos) >= 0
                    && Misc.gchar_pos(tpos) == ' '))) {
              pos = tpos;
              if (Misc.gchar_pos(pos) == '\n') // skip '\n' at EOL
                Misc.inc(pos);
              break;
            }
          }
          if (Misc.inclDeclV7(pos, dir) == -1) {
            if (count > 0)
              return false;
            noskip = true;
            break;
          }
        }
      } while (false);

      while (!noskip && ((c = Misc.gchar_pos(pos)) == ' ' || c== '\t'))
        if (Misc.inclV7(pos) == -1) break;
    }
    setpcmark();
    G.curwin.setCaretPosition(pos.getOffset());
    return true;
  }

  static boolean findpar(CMDARG oap, int dir, int count, int what,
    boolean both) {

    int curr = G.curwin.w_cursor.getLine();

    while (count-- > 0) {
      boolean did_skip = false; //TRUE after separating lines have been skipped 
      boolean first = true;     // TRUE on first line 
      do {
        if (!lineempty(curr))
          did_skip = true;

        if (!first && did_skip && startPS(curr, what, both))
          break;

        if ((curr += dir) < 1 || curr > G.curbuf.getLineCount()) {
          if (count > 0)
            return false;
          curr -= dir;
          break;
        }
        first = false;
      } while (true);
    }

    setpcmark();

    if (both && !lineempty(curr) && ml_get(curr).charAt(0) == '}')
      ++curr;

    int offset = 0;

    if (curr == G.curbuf.getLineCount()) {
      offset = lineLength(curr);
      if (offset > 0) {
        offset--;
        oap.oap.inclusive = true;
      }
    }

    G.curwin.w_cursor.set(curr, offset);

    return true;
  }

  //
  // Character class stuff from vim's regexp.c
  //

  /**
   * character class namedata.
   */
  static class CCNameData {
    String name;
    CCCheck checkFunc;
    CCNameData(String name, CCCheck checkFunc) {
      this.name = name;
      this.checkFunc = checkFunc;
    }
  }

  /**
   * character class check function
   */
  interface CCCheck {
    boolean doCheck(int c);
  }

  /* *****************************************************************
#define t(n, func) { sizeof(n) - 1, func, n }
      static const namedata_t class_names[] =
      {
          t("alnum:]", isalnum),		t("alpha:]", isalpha),
          t("blank:]", my_isblank),     	t("cntrl:]", iscntrl),
          t("digit:]", isdigit),		t("graph:]", isgraph),
          t("lower:]", islower),		t("print:]", vim_isprintc),
          t("punct:]", ispunct),		t("space:]", vim_isspace),
          t("upper:]", isupper),		t("xdigit:]", isxdigit),
          t("tab:]",   my_istab),		t("return:]", my_isreturn),
          t("backspace:]", my_isbspace),	t("escape:]", my_isesc)
      };
#undef t
  ***********************************************************************/

  /**
   * Check for a character class name.  "pp" is at the '['.
   * If not: NULL is returned; If so, a function of the sort "is*"
   * is returned and
   * the name is skipped.
   * @param s
   * @param mi on entry hold start string index;
   * on return string index to skip name (if any)
   * @return function used to check, null if not a char class
   */
  static CCCheck skip_class_name(String s, MutableInt mi) {
    int sidx = mi.getValue();
    // Assume there is not a class name
    return null;

    /* ******************************************************************
    const namedata_t *np;

    if ((*pp)[1] != ':')
        return NULL;
    for (   np = class_names;
            np < class_names + sizeof(class_names) / sizeof(*class_names);
            np++)
        if (STRNCMP(*pp + 2, np.name, np.len) == 0)
        {
            *pp += np.len + 2;
            return np.func;
        }
    return NULL;
    *********************************************************************/
  }

  /*
   * startPS: return TRUE if line 'lnum' is the start of a section or paragraph.
   * If 'para' is '{' or '}' only check for sections.
   * If 'both' is TRUE also stop at '}'
   */
  static boolean startPS(int /*linenr_t*/lnum, int para, boolean both) {
    MySegment seg = ml_get(lnum);
    // if seg.count == 1, then only a \n, ie empty line
    char s = seg.count > 1 ? seg.array[seg.offset] : 0;
    // '\f' is formfeed, oh well, it doesn't hurt to be here
    if (s == para || s == '\f' || (both && s == '}'))
      return true;
//    if (s == '.' && (inmacro(p_sections, s + 1) ||
//            (!para && inmacro(p_para, s + 1))))
//        return true;
    return false;
  }
}

// vi:set sw=2 ts=8:
