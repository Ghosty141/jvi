/**
 * Title:        jVi<p>
 * Description:  A VI-VIM clone.
 * Use VIM as a model where applicable.<p>
 * Copyright:    Copyright (c) Ernie Rael<p>
 * Company:      Raelity Engineering<p>
 * @author Ernie Rael
 * @version 1.0
 */

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
package com.raelity.jvi;

import java.awt.Toolkit;
import javax.swing.text.Segment;
import javax.swing.text.BadLocationException;

public class Util {
  // static final int TERMCAP2KEY(int a, int b) { return a + (b << 8); }
  static final int ctrl(int x) { return x & 0x1f; }
  // static final int shift(int c) { return c | (0x1 << 24); }
  // static void stuffcharReadbuff(int c) {}

  /** position to end of line. */
  static void endLine() {
    ViFPOS fpos = G.curwin.getWCursor();
    int offset = G.curwin
	      		.getLineEndOffsetFromOffset(fpos.getOffset());
    // assumes there is at least one char in line, could be a '\n'
    offset--;	// point at last char of line
    if(Util.getCharAt(offset) != '\n') {
      offset++; // unlikely
    }
    G.curwin.setCaretPosition(offset);
  }

  public static void vim_beep() {
    Toolkit.getDefaultToolkit().beep();
  }

  /** ptr to found char */
  public static String vim_strchr(String s, int c) {
    int index = s.indexOf(c);
    if(index < 0) {
      return null;
    }
    return s.substring(index, index);
  }

  public static final boolean isalnum(int regname) {
    return	regname >= '0' && regname <= '9'
    		|| regname >= 'a' && regname <= 'z'
    		|| regname >= 'A' && regname <= 'Z';
  }

  public static final boolean isalpha(int c) {
    return	   c >= 'a' && c <= 'z'
    		|| c >= 'A' && c <= 'Z';
  }

  public static boolean islower(int c) {
    return 'a' <= c && c <= 'z';
  }

 public static int tolower(int c) {
   if(isupper(c)) {
     c |= 0x20;
   }
   return c;
 }

  static boolean isupper(int c) {
    return 'A' <= c && c <= 'Z';
  }

 static int toupper(int c) {
   if(islower(c)) {
     c &= ~0x20;
   }
   return c;
 }

  public static boolean isdigit(int c) {
    return '0' <= c && c <= '9';
  }

  static boolean vim_isprintc(int c) { return false; }

  /**
   * get a pointer to a (read-only copy of a) line.
   *
   * On failure an error message is given and IObuff is returned (to avoid
   * having to check for error everywhere).
   */
  static String ml_get(int lnum) {
    // return ml_get_buf(curbuf, lnum, FALSE);
    Segment seg = G.curwin.getLineSegment(lnum);
    return new String(seg.array, seg.offset, seg.count - 1);
  }

  /**
   * Get the length of a line, not incuding the newline
   */
  static int lineLength(int line) {
    return G.curwin.getLineEndOffset(line)
                     - G.curwin.getLineStartOffset(line) - 1;
  }

  /** is the indicated line empty? */
  static boolean lineempty(int lnum) {
    Segment seg = G.curwin.getLineSegment(lnum);
    return seg.count == 0
	      || seg.count == 1 && seg.array[seg.offset] == '\n';
  }

  static int getChar() {
    return getCharAt(G.curwin.getCaretPosition());
  }

  static int getCharAt(int offset) {
    int c;
    try {
      c = G.curwin.getText(offset, 1).charAt(0);
    } catch(BadLocationException e) {
      c = 0;
    }
    return c;
  }

  /** flush map and typeahead buffers and vige a warning for an error */
  static void beep_flush() {
    GetChar.flush_buffers(false);
    vim_beep();
  }
}
