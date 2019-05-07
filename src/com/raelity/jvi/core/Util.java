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
import java.text.CharacterIterator;

import com.raelity.jvi.ViCmdEntry;
import com.raelity.jvi.ViFPOS;
import com.raelity.jvi.lib.MutableInt;
import com.raelity.text.TextUtil.MySegment;

import static com.raelity.jvi.core.lib.Constants.*;


public class Util {
  private Util() { }

  // static final int TERMCAP2KEY(int a, int b) { return a + (b << 8); }
  public static char ctrl(char x) { return (char)(x & 0x1f); }
  // static final int shift(int c) { return c | (0x1 << 24); }
  // static void stuffcharReadbuff(int c) {}

  /** position to end of line. */
  static void endLine() {
    ViFPOS fpos = G.curwin.w_cursor;
    int offset = G.curbuf.getLineEndOffsetFromOffset(fpos.getOffset());
    // assumes there is at least one char in line, could be a '\n'
    offset--;	// point at last char of line
    if(Util.getCharAt(offset) != '\n') {
      offset++; // unlikely
    }
    G.curwin.w_cursor.set(offset);
  }

  /**
   * "ring the bell", by default use the Toolkit's beep.
   * <p/>
   * Note: if there's an error and the typeahead buffer should be flushed
   * then use beep_flush().
   */
  public static void vim_beep() {
    if(Options.getOption(Options.auditoryBell).getBoolean()) {
        G.curwin.bell();
    }
  }


  /**
   * Returns the substring of c in s or null if c not part of s.
   * @param s the string to search in
   * @param c the character to search for
   * @return the substring of c in s or null if c not part of s.
   */
  public static String vim_strchr(String s, char c) {
    int index = s.indexOf(c);
    if(index < 0) {
      return null;
    }
    return s.substring(index);
  }

  /**
   * Returns the position of c in cs or -1 if not part of s.
   * vim_strchar(s, 0, c) < 0 can replace vim_strchr(s, c) == null
   * @param cs sequence to search
   * @param i start checking at this position
   * @param c look for this char
   * @return position if found, else -1
   */
  public static int vim_strchr(CharSequence cs, int i, char c) {
    int p = i;
    int length = cs.length();
    while(p < length) {
      if(cs.charAt(p) == c)
        return p;
      ++p;
    }
    return -1;
  }

  /**
   * VISUAL and OP_PENDING State are never set, they are equal to NORMAL State
   * with a condition.  This function returns the real State.
   * {jVi} PLATFORM.
   */
  public static int get_real_state()
  {
    if ((G.State & NORMAL) != 0)
    {
      if (G.VIsual_active)
        return VISUAL;
      else if (G.finish_op)
        return OP_PENDING;
      else if(G.curwin.hasSelection())
        return PLATFORM;
    }
    return G.State;
  }

  public static boolean isInsertMode() {
    return (G.State & BASE_STATE_MASK) == INSERT;
  }

  /**
   * Vim has its own isspace() function, because on some machines isspace()
   * can't handle characters above 128.
   */
  public static boolean vim_isspace(char x)
  {
      return ((x >= 9 && x <= 13) || x == ' ');
  }

  public static boolean isalnum(int regname) {
    return	regname >= '0' && regname <= '9'
    		|| regname >= 'a' && regname <= 'z'
    		|| regname >= 'A' && regname <= 'Z';
  }

  public static boolean isalpha(char c) {
    return	   c >= 'a' && c <= 'z'
    		|| c >= 'A' && c <= 'Z';
  }

  public static boolean ascii_isalpha(char c) {
    return	   c < 0x7f && isalpha(c);
  }

  public static boolean islower(char c) {
    return 'a' <= c && c <= 'z';
  }

 public static char tolower(char c) {
   if(isupper(c)) {
     c |= 0x20;
   }
   return c;
 }

  static boolean isupper(char c) {
    return 'A' <= c && c <= 'Z';
  }

 static char toupper(char c) {
   if(islower(c)) {
     c &= ~0x20;
   }
   return c;
 }

  public static boolean isdigit(char c) {
    return '0' <= c && c <= '9';
  }

  /**
   * Variant of isxdigit() that can handle characters > 0x100.
   * We don't use isxdigit() here, because on some systems it also considers
   * superscript 1 to be a digit.
   */
  public static boolean
  isxdigit(char c)
  {
      return (c >= '0' && c <= '9')
  	|| (c >= 'a' && c <= 'f')
  	|| (c >= 'A' && c <= 'F');
  }

  // #define CharOrd(x)	((x) < 'a' ? (x) - 'A' : (x) - 'a')
  // #define CharOrdLow(x)	((x) - 'a')
  // #define CharOrdUp(x)	((x) - 'A')
  // #define ROT13(c, a)	(((((c) - (a)) + 13) % 26) + (a))
  public static int CharOrd(char c) {
    return c < 'a' ? c - 'A' : c - 'a';
  }

  static boolean vim_isprintc(char c) { return false; }

  /**
   * get a pointer to a (read-only copy of a) line.
   *
   * On failure an error message is given and IObuff is returned (to avoid
   * having to check for error everywhere).
   */
  static MySegment ml_get(int lnum) {
    MySegment seg = new MySegment(G.curbuf.getLineSegment(lnum));
    return seg;
  }

  static MySegment ml_get_curline() {
    return ml_get(G.curwin.w_cursor.getLine());
  }

  /** get pointer to positin 'pos', the returned MySegment's CharacterIterator
   * is initialized to the character at pos.
   * <p>
   * NEEDSWORK: There are usages of CharacterIterator that depend on the
   *            the entire line being part of the iterator, in particular
   *            the column position is: ci.getIndex() - ci.getBeginIndex()
   *
   * @return MySegment for the line.
   */
  static CharacterIterator ml_get_pos(ViFPOS pos) {
    MySegment seg = G.curbuf.getLineSegment(pos.getLine());
    seg.setIndex(seg.offset + pos.getColumn());
    return seg;
  }

  static CharacterIterator ml_get_cursor() {
    return ml_get_pos(G.curwin.w_cursor);
  }

  static void ml_replace(int lnum, CharSequence line) {
    G.curwin.replaceString(G.curbuf.getLineStartOffset(lnum),
            G.curbuf.getLineEndOffset(lnum) -1,
            line.toString());
  }

  public static MySegment truncateNewline(MySegment seg) {
      assert(seg.array[seg.offset + seg.count - 1] == '\n');
      return new MySegment(seg.array, seg.offset, seg.count - 1, seg.docOffset);
  }

  /**
   * Get the length of a line, not including the newline
   */
  static int lineLength(int line) {
    return lineLength(G.curbuf, line);
  }

  public static int lineLength(Buffer buf, int line) {
    return buf.getLineLength(line);
  }

  public static int lineLength(MySegment seg) {
    return seg.count < 1 ? 0 : seg.count - 1;
  }

  /** is the indicated line empty? */
  static boolean lineempty(int lnum) {
    MySegment seg = G.curbuf.getLineSegment(lnum);
    return seg.count == 0 || seg.array[seg.offset] == '\n';
  }

  static boolean bufempty() {
      return G.curbuf.getLineCount() == 1
             && lineempty(1);
  }

  static char getChar() {
    return getCharAt(G.curwin.getCaretPosition());
  }

  static char getCharAt(int offset) {
    MySegment seg = new MySegment();
    G.curbuf.getSegment(offset, 1, seg);
    return seg.count > 0 ? seg.array[seg.offset] : 0;
  }

  /** flush map and typeahead buffers and give a warning for an error */
  public static void beep_flush() {
    GetChar.flush_buffers(false);
    vim_beep();
  }

  /**
   * @param s1
   * @param s2
   * @param n this is ignored
   * @return
   */
  static int strncmp(String s1, String s2, int n)
  {
    if(s1.length() > n)
      s1 = s1.substring(0, n);
    return s1.compareTo(s2);
  }

  static int strncmp(MySegment seg, int i, String s2, int n)
  {
    String s1 = seg.subSequence(i, seg.count).toString();
    return strncmp(s1, s2, n);
  }

/*
 * Convert a string into a long and/or unsigned long, taking care of
 * hexadecimal and octal numbers.  Accepts a '-' sign.
 * If "hexp" is not null, returns a flag to indicate the type of the number:
 *  0	    decimal
 *  '0'	    octal
 *  'X'	    hex
 *  'x'	    hex
 * If "len" is not null, the length of the number in characters is returned.
 * If "nptr" is not null, the signed result is returned in it.
 * If "unptr" is not null, the unsigned result is returned in it.
 * If "unptr" is not null, the unsigned result is returned in it.
 * If "dooct" is non-zero recognize octal numbers, when > 1 always assume
 * octal number.
 * If "dohex" is non-zero recognize hex numbers, when > 1 always assume
 * hex number.
 */
//    void
//vim_str2nr(start, hexp, len, dooct, dohex, nptr, unptr)
static void
vim_str2nr(MySegment seg, int start,
           MutableInt hexp, MutableInt len,
           int dooct, int dohex,
           MutableInt nptr, MutableInt unptr)
//    char_u		*start;
//    int			*hexp;	    /* return: type of number 0 = decimal, 'x'
//				       or 'X' is hex, '0' = octal */
//    int			*len;	    /* return: detected length of number */
//    int			dooct;	    /* recognize octal number */
//    int			dohex;	    /* recognize hex number */
//    long		*nptr;	    /* return: signed result */
//    unsigned long	*unptr;	    /* return: unsigned result */
{
    int	    	    ptr = start;
    int		    hex = 0;		/* default is decimal */
    boolean	    negative = false;
    int             un = 0;
    int		    n;

    if (seg.charAt(ptr+0) == '-')
    {
	negative = true;
	++ptr;
    }

    /* Recognize hex and octal. */
    if (seg.charAt(ptr+0) == '0' && seg.charAt(ptr+1) != '8' && seg.charAt(ptr+1) != '9')
    {
	hex = seg.charAt(ptr+1);
	if (dohex != 0 && (hex == 'X' || hex == 'x') && isxdigit(seg.charAt(ptr+2)))
	    ptr += 2;			/* hexadecimal */
	else
	{
	    hex = 0;			/* default is decimal */
	    if (dooct != 0)
	    {
		/* Don't interpret "0", "08" or "0129" as octal. */
		for (n = 1; isdigit(seg.charAt(ptr+n)); ++n)
		{
		    if (seg.charAt(ptr+n) > '7')
		    {
			hex = 0;	/* can't be octal */
			break;
		    }
		    if (seg.charAt(ptr+n) > '0')
			hex = '0';	/* assume octal */
		}
	    }
	}
    }

    /*
     * Do the string-to-numeric conversion "manually" to avoid sscanf quirks.
     */
    if (hex == '0' || dooct > 1)
    {
	/* octal */
	while ('0' <= seg.charAt(ptr) && seg.charAt(ptr) <= '7')
	{
	    un = 8 * un + (seg.charAt(ptr) - '0');
	    ++ptr;
	}
    }
    else if (hex != 0 || dohex > 1)
    {
	/* hex */
	while (isxdigit(seg.charAt(ptr)))
	{
	    un = 16 * un + hex2nr(seg.charAt(ptr));
	    ++ptr;
	}
    }
    else
    {
	/* decimal */
	while (isdigit(seg.charAt(ptr)))
	{
	    un = 10 * un + (seg.charAt(ptr) - '0');
	    ++ptr;
	}
    }

    if (hexp != null)
	hexp.setValue(hex); //*hexp = hex;
    if (len != null)
	len.setValue(ptr - start); //*len = (int)(ptr - start);
    if (nptr != null)
    {
	if (negative)   /* account for leading '-' for decimal numbers */
	    nptr.setValue(-un); //*nptr = -(long)un;
	else
	    nptr.setValue(un);//*nptr = (long)un;
    }
    if (unptr != null)
	unptr.setValue(un);//*unptr = un;
}

/**
 * Return the value of a single hex character.
 * Only valid when the argument is '0' - '9', 'A' - 'F' or 'a' - 'f'.
 */
public static int
hex2nr(char c)
{
    if (c >= 'a' && c <= 'f')
	return c - 'a' + 10;
    if (c >= 'A' && c <= 'F')
	return c - 'A' + 10;
    return c - '0';
}

// cursor compare
static String cur() {
  String s = G.curwin.w_cursor.toString();
  // put the virtual position in their
  return s;
}

static boolean equalpos(ViFPOS p1, ViFPOS p2) {
  return p1 == null
          ? p2 == null
          : p1.equals(p2);
}
static boolean lt(ViFPOS p1, ViFPOS p2) {
  return p1.compareTo(p2) < 0;
}

static void add_to_history(int histype, String new_entry)
{
  ViCmdEntry ce = histype == HIST_SEARCH
          ? Search.getSearchCommandEntry()
          : ColonCommands.getColonCommandEntry();
  ce.makeTop(new_entry);
}

}

// vi:set sw=2 ts=8:
