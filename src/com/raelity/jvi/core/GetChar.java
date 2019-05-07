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

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.openide.util.lookup.ServiceProvider;

import com.raelity.jvi.ViInitialization;
import com.raelity.jvi.core.lib.BufferQueue;
import com.raelity.jvi.core.lib.KeyDefs;
import com.raelity.jvi.core.lib.Mappings;
import com.raelity.jvi.core.lib.TypeBufMultiCharMapping;
import com.raelity.jvi.manager.ViManager;
import com.raelity.jvi.options.Option;
import com.raelity.text.TextUtil;

import static com.raelity.jvi.core.Util.*;
import static com.raelity.jvi.core.lib.Constants.*;
import static com.raelity.jvi.core.lib.Constants.FDO.*;
import static com.raelity.jvi.core.lib.KeyDefs.*;

public class GetChar {
    private static boolean block_redo = false;
    private static boolean handle_redo = false;
    private static Option magicRedoAlgo
            = Options.getOption(Options.magicRedoAlgorithm);
    private static String currentMagicRedoAlgo = "anal";

    private static Mappings mappings;
    private static TypeBufMultiCharMapping typebuf;

    private GetChar()
    {
    }

    public static class Init implements ViInitialization
    {
        @Override
        public void init()
        {
            mappings = new Mappings();
            typebuf = new TypeBufMultiCharMapping(mappings);
        }
    }

    static public void printMappings(String lhs, int mode)
    {
        mappings.printMappings(lhs, mode);
    }

    static void reinitMappings()
    {
        mappings.reinitMappings();
    }

    static void reset(boolean flush)
    {
        ViManager.getFactory().stopTimeout(mappingTimeout);
        // NEEDSWORK: flush_buffers has a typeahead flag which is ignored
        //            could use that as part of this handling
        if(flush)
            flush_buffers(true);
    }

    private static ActionListener mappingTimeout = new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e)
        {
            typebuf.mappingTimeout();
            gotc(NO_CHAR, 0);
        }
    };

    /** An input char from the user has been received.
     * Implement the key into 16 bit mapping defined in KeyDefs.
     * <p>
     * There is still a hole. This doesn't handle stuff where screwy
     * modifiers are applied to normal keys, e.g. Ctrl-Shft-Alt-A.
     * There is room in the reseved
     * area to map the regular keys, probably want to put them in the
     * E000-E07f range, and put the K_UP type keys in the E080 range.
     * </p>
     */
    static void gotc(char key, int modifier)
    {
        assert stuffbuff.length() == 0;
        //assert typebuf.length();// NO MORE, WITH mutli-char mappings

        if(typebuf.getPartialMatch() != null) {
            Normal.pop_showcmd();
        }
        ViManager.getFactory().stopTimeout(mappingTimeout); // just in case

        if(key != NO_CHAR) { // might be NO_CHAR if input wait timeout
            if(isVIRT(key)) {
                key |= ((modifier & MOD_MASK) << MODIFIER_POSITION_SHIFT);
            }

            last_recorded_len = 0;      // for the one in vgetc()
            userInput(key);

            user_ins_typebuf(key);
        }

        handle_redo = false;
        try {

            ///// if(doc != null)               // NEED API
            /////     doc.readLock();

            startPump(NO_CHAR, handle_redo);

        } finally {
            handle_redo = false;
            G.Exec_reg = false;
            ///// if(doc != null)
            /////     doc.readUnlock();
        }

        if(typebuf.getPartialMatch() != null) {
            Normal.push_add_to_showcmd(typebuf.getPartialMatch());
            if(G.p_to) {
                ViManager.getFactory().startTimeout(
                        G.p_tm,
                        mappingTimeout);
            }
        }

        // returning from event
        // only do this if no pending characters
        // but can't test for pending characters, so ....

        if (!G.curwin.isShutdown()
                && G.curwin.hasAnyFolding() && !char_avail())
        {
            // foldCheckClose(); NICE IDEA
            if (G.p_fdo.contains(FDO_ALL))
                Normal.foldOpenCursor();
        }

        Misc.out_flush();
    }

    //////////////////////////////////////////////////////////////////////////

    /** This is a special case for the two part search */
    static void fakeGotcPickupExtraChar(char key) {
        Normal.pickupExtraChar = true;
        ++G.no_mapping;
        ++G.allow_keys;
        pumpChar(key);
        Misc.out_flush();   // returning from event
    }

    // interactions with redo/collectingGroupUndo
    //
    // NOTE:    The confusing handle_redo flag might be extraneous
    //          since we know how to automatically enter collectingGroupUndo.
    //          To do this I think all we need to do is check
    //          stuffbuff.length() in the following
    //              if(!collectingGroupUndo
    //                  && typebuf.length() > 0 && isInsertMode()) {
    //          The current check basically is for catching mappings that
    //          might have multiple inserts.
    //          handle_redo is set by
    //                  start_redo()       - the '.' commands
    //                  start_redo_ins()   - like: '77a/<esc>'
    //                  ins_typebuf_redo() - the '@' commands
    //          If we get rid of handle_redo, then we must
    //          get rid fo the pumpChar in gotc (I think).
    //
    // HERE ARE THE OLD COMMENTS ON handle_redo doing runUndoable
    // Handle some type of redo command: start_redo or
    // start_redo_ins.Execute these commands atomically, with the
    //  file locked ifsupported by the platform. We need these
    // special brackets since while executing the command, other
    // begin/endUndo's may occur.
    //
    // During the above processInputChar, a redo
    // command was setup by // stuffing the buffer. pumpVi()
    // delivers stuffbuf and typebuf // to processInputChar.
    // It's tempting to always bracket pumpVi with
    // begin/endRedoUndo, but macro execution might end
    // in input mode.
    //
    // NEEDSWORK: INPUT MODE FROM MACRO ????????

    private static int runEventQueue;
    /**
     * BE VERY CAREFULL
     * Don't do this if you don't have to.
     * This will stop an undo group, which is a problem
     * for the "AutoUndo" situation.
     */
    static void requestCharBreakPauseRunEventQueue(int nLoop)
    {
        runEventQueue = nLoop;
    }

    /**
     * Process every thing that's currently in the eventQ
     * <p/>
     * THIS CASE DOESN'T WORK: collectingGroupUndo must be false.
     *                         collectingGroupUndo is turned on if
     *                         typebuf has characters in it (independent of insert)
     *                         saving state about this would be weird
     * Must be some characters to process.
     * Must be in command mode.
     * @param collectingGroupUndo true if in undo group
     * @return true if posting gotc
     */
    private static boolean runEventQueue(final boolean collectingGroupUndo)
    {
        if(runEventQueue > 0)
            --runEventQueue;
        if(/*collectingGroupUndo ||*/ !isAnyChar() || (G.State & NORMAL) == 0) {
            if(Options.kd().getBoolean())
                Options.kd().println("runEventQueue:" + runEventQueue + " SKIP");
            runEventQueue = 0;
            return false;
        }
        EventQueue.invokeLater(new Runnable() {

            @Override
            public void run()
            {
                if(Options.kd().getBoolean())
                    Options.kd().println("runEventQueue:" + runEventQueue);
                if(runEventQueue > 0)
                    runEventQueue(collectingGroupUndo);
                else
                    gotc(NO_CHAR, 0);
            }
        });
        return true;
    }

    /**
     * NOTE: if collectingGroupUndo is false, then 'c' must be NO_CHAR
     * @param c
     * @param collectingGroupUndo
     */
    private static void startPump(final int c,
                                  final boolean collectingGroupUndo)
    {
        if(!collectingGroupUndo)
            pumpAllChars(collectingGroupUndo);
        else {
            Misc.runUndoable(new Runnable() {
                @Override
                public void run() {
                    if(c != NO_CHAR)
                        pumpChar(c);
                    pumpAllChars(collectingGroupUndo);
                }
            });
        }
    }

    // NEEDSWORK: old_mod_mask
    private static int old_char;

    private static boolean isAnyChar()
    {
        return old_char != NUL || stuffbuff.hasNext() || !typebuf.isEmpty();
    }

    /**
     * Pass queued up characters to vi for processing.
     * First from stuffbuf, then typebuf.
     */
    private static void pumpAllChars(boolean collectingGroupUndo) {
        // NEEDSWORK: pumpVi: check for interupt?

        try {

            while(true) {
                if(runEventQueue > 0 && runEventQueue(collectingGroupUndo))
                    return;
                if(!collectingGroupUndo
                        && (typebuf.length() > 1 // && isInsertMode()
                            || handle_redo)) {
                    G.dbgUndo.println("pumpAllChars: switch 1 to group undo");
                    startPump(NO_CHAR, true);
                    return;
                }
                if(old_char != NUL) {
                    int c = old_char;
                    old_char = NUL;
                    pumpChar(c);
                    continue;
                }
                if(stuffbuff.hasNext()) {
                    pumpChar(stuffbuff.removeFirst());
                    continue;
                }
                int c = typebuf.getChar();
                // Do this again since typebuf length may have just changed.
                if(!collectingGroupUndo
                        && (typebuf.length() > 1 // && isInsertMode()
                            || handle_redo)) {
                    G.dbgUndo.println("pumpAllChars: switch 2 to group undo");
                    startPump(c, true);
                    return;
                }
                if( c != NO_CHAR) { //if(typebuf.hasNext()) {
                    pumpChar(c);
                } else {
                    break;
                }
            }
        } catch(Exception ex) {
            vim_beep();
            Normal.resetCommand(true);
            Logger.getLogger(GetChar.class.getName()).
                    log(Level.SEVERE, null, ex);
        }

    }

    private static void pumpChar(int c) {
        if(c == NO_CHAR)
            return;
        /*
        if(isVIRT(c)) {
            modifiers = c & (MOD_MASK << MODIFIER_POSITION_SHIFT);
            if(modifiers != 0) {
                c &= ~modifiers;
                modifiers = modifiers >> MODIFIER_POSITION_SHIFT;

                // if modifiers are only/exactly the shift key
                if(modifiers == SHFT
                        && c >= VIRT && c <= VIRT + 0x0f) {
                    // only the shift key is pressed and its one of "those".
                    c += SHIFTED_VIRT_OFFSET;
                }
            }
        }
        */
        if(Options.kd().getBoolean(Level.FINEST))
            Options.kd().println(Level.FINEST, "pumpChar: "
                    + TextUtil.debugString(String.valueOf(c))
                    + " (" +  (c >> 16) + ")");
        G.setModMask(c >> 16);
        Normal.processInputChar((char)c, true);
    }

    /**
     * @return a queued character from input stream.
     * @exception RuntimeException if no characters are available
     */
    private static int getOneChar() {
        if(stuffbuff.hasNext()) {
            return stuffbuff.removeFirst();
        }
        if(typebuf.hasNext()) {
            return typebuf.getChar();
        }
        throw new RuntimeException("No character available");
    }

    /**
     * read from the typebuf until a CR, return what was read, discarding
     * the CR. If the input stream empties, just return what was available.
     * @return true when a CR was encountered, else false
     */
    static boolean getRecordedLine(StringBuffer sb) {
        boolean hasCR = false;
        while(true) {
            if( ! char_avail()) {
                break;
            }
            char c = (char)getOneChar();
            if(c == NO_CHAR) // NEEDSWORK: added to workaround
                break;       //            TypeBufMultiChar issues

            if(c == '\n') {
                hasCR = true;
                break;
            }
            sb.append(c);
        }
        return hasCR;
    }

    /**
     * Call vpeekc() without causing anything to be mapped.
     * @return true if a character is available, false otherwise.
     */
    static boolean char_avail() {
        // NOTE: Look at description for vgetorpeek; there's more to add

        if(stuffbuff.hasNext() || typebuf.hasNext()) {
            return true;
        }

        return false; // would really like to check...

        // return keyInput.hasChar();

    }

    static void vungetc(int c) /* unget one character (can only be done once!) */
    {
        old_char = c;
        //old_mod_mask = mod_mask;
    }

    /**
     * Write typed characters to script file.
     * If recording is on put the character in the recordbuffer.
     * Put the current modifies into the character if needed,
     * {@see KeyDefs} for info on character layout with modifiers.
     */
    private static void userInput(char c /*, int len*/) {

        /* remember how many chars were last recorded */
        if (G.Recording) {
            last_recorded_len += 1;
            if(isVIRT(c)) {
                c |= (G.getModMask() << MODIFIER_POSITION_SHIFT);
            }
            recordbuff.append(c);
        }

        // ******************************************************************
        // buf[1] = NUL;
        // while (len--)
        // {
        // c = *s++;
        // updatescript(c);
        //
        // if (Recording)
        // {
        // buf[0] = c;
        // add_buff(&recordbuff, buf);
        // }
        // }
        // may_sync_undo();
        //******************************************************************/
    }

    /**
     * Write a stuff to the script and/or record buffer.
     * Used to stash command entry input.
     */
    public static void userInput(String s) { //NEEDSWORK: public
        /* remember how many chars were last recorded */
        if (G.Recording) {
            last_recorded_len += s.length();
            recordbuff.append(s);
        }
    }

    /*
     * return the contents of a buffer as a single string
     */
    static String get_bufcont(BufferQueue buffer, boolean dozero)
    {
        if(buffer.length() == 0 && !dozero)
            return null;
        return buffer.toString();
    }

    /**
     * Return the contents of the record buffer as a single string
     *  and clear the record buffer.
     */
    static String get_recorded() {
        //
        // Remove the characters that were added the last time, these must be the
        // (possibly mapped) characters that stopped recording.
        //
        int len = recordbuff.length();
        if (len >= last_recorded_len) {
            len -= last_recorded_len;
            recordbuff.setLength(len);
        }

        // *******************************************************************
        // //
        // // When stopping recording from Insert mode with CTRL-O q,
        // // also remove the CTRL-O.
        // //
        // if (len > 0 && restart_edit && p[len - 1] == Ctrl('O'))
        // p[len - 1] = NUL;
        //************************************************************/

        String s = recordbuff.toString();
        recordbuff.setLength(0);

        return s;
    }

    static String get_inserted()
    {
        return get_bufcont(redobuff, false);
    }

    /**
     * get a character: 1. from a previously ungotten character
     *		      2. from the stuffbuffer
     *		      3. from the typeahead buffer
     *		      4. from the user
     * <br/>
     * if "advance" is TRUE (vgetc()):
     *	really get the character.
     *	KeyTyped is set to TRUE in the case the user typed the key.
     *	KeyStuffed is TRUE if the character comes from the stuff buffer.
     * <br/>
     * if "advance" is FALSE (vpeekc()):
     *	just look whether there is a character available.
     */

    static boolean vgetorpeek(boolean advance) {
        // Just a place holder for the above comment for now
        return false;
    }

    /**
     * prepare stuff buffer for reading (if it contains something).
     */
    private static void start_stuff() {
        // NEEDSWORK: start_stuff: should this this is a nop
        // stuffbuff = new BufferQueue();
    }

    static boolean stuff_empty() {
        return ! stuffbuff.hasNext();
    }

    private static boolean block_redo()
    {
        return handle_redo || block_redo;
    }

    /**
     * The previous contents of the redo buffer is kept in old_redobuffer.
     * This is used for the CTRL-O <.> command in insert mode.
     */
    static void ResetRedobuff() {
        if(!magicRedoAlgo.getString().equals(currentMagicRedoAlgo)) {
            currentMagicRedoAlgo = magicRedoAlgo.getString();
            G.dbgRedo.printf("Switching redo to %s\n", currentMagicRedoAlgo);
            if(currentMagicRedoAlgo.equals("anal"))
                magicRedo = new MagicRedoOriginal(redobuff);
            else if(currentMagicRedoAlgo.equals("guard"))
                magicRedo = new MagicRedo(redobuff);
            else
                ViManager.warning("WHAT?! magic redo algo: " + currentMagicRedoAlgo);
        }
        magicRedo.charTyped(NUL);
        if (!block_redo()) {
            // old_redobuff = redobuff;
            redobuff.setLength(0);
        }
    }

    static void AppendToRedobuff(String s) {
        if(s.length() == 1) {
            AppendCharToRedobuff(s.charAt(0));
            return;
        }
        magicRedo.charTyped(NUL);
        if (!block_redo()) {
            redobuff.append(s);
        }
    }

    static void AppendCharToRedobuff(char c) {
        if (!block_redo()) {
            if(c == BS) {
                magicRedo.markRedoBackspace();
            } else {
                magicRedo.charTyped(c);
            }
            redobuff.append(c);
        } else
            magicRedo.charTyped(NUL);
    }

    static void AppendNumberToRedobuff(int n) {
        magicRedo.charTyped(NUL);
        if (!block_redo()) {
            redobuff.append(n);
        }
    }

    static void changeInsstart() {
        magicRedo.changeInsstart();
    }

    //
    // A few methods to bounce into the MagicRedo handling
    //
    static void startInputModeRedobuff() {
        // NEEDSWORK: markRedoTrackPosition(START_INPUT_MODE);
        startInsertCommand = redobuff.toString();
        magicRedo.initRedoTrackingPosition();
    }

    static void disableRedoTrackingOneEdit() {
        magicRedo.disableRedoTrackingOneEdit();
    }

    static void editComplete() {
        String s = magicRedo.editComplete();
        if(s != null && !block_redo()) {
            // replaced the redobuf with the return value
            redobuff.setLength(0);
            redobuff.append(startInsertCommand).append(s).append(ESC);
        }
    }

    static void docInsert(int pos, String s) {
        magicRedo.docInsert(pos, s);
    }

    static void docRemove(int pos, int len, String removedText) {
        magicRedo.docRemove(pos, len, removedText);
    }

    /**
     * Remove the contents of the stuff buffer and the mapped characters in the
     * typeahead buffer (used in case of an error). If 'typeahead' is true,
     * flush all typeahead characters (used when interrupted by a CTRL-C).
     */
    static void flush_buffers(boolean typeahead) {
        init_typebuf();
        start_stuff();
        stuffbuff.setLength(0); // while(read_stuff(true) != NUL);

        // *******************************************************************
        // // NEEDSWORK: finish flush_buffer
        // if (typeahead) {	    // remove all typeahead
        // //
        // // We have to get all characters, because we may delete the first part
        // // of an escape sequence.
        // // In an xterm we get one char at a time and we have to get them all.
        // //
        // while (inchar(typebuf, typebuflen - 1, 10L))
        // ;
        // typeoff = MAXMAPLEN;
        // typelen = 0;
        // } else {		    // remove mapped characters only
        // typeoff += typemaplen;
        // typelen -= typemaplen;
        // }
        // typemaplen = 0;
        // no_abbr_cnt = 0;
        //********************************************************************/
    }

    //
    // The stuff buff used from externally by the "r" and "*" (nv_ident) commands.
    //

    static void stuffcharReadbuff(char n) {
        stuffbuff.append(n);
    }

    static void stuffnumReadbuff(int n) {
        stuffbuff.append(n);
    }

    static void stuffReadbuff(String s) {
        stuffbuff.append(s);
    }

    /**
     * Read a character from the redo buffer.
     * The redo buffer is left as it is.
     * <p>NOTE: old_redo ignored</p>
     * @param init if TRUE, prepare for redo, return FAIL if nothing to redo, OK
     * otherwise
     * @param old_redo if TRUE, use old_redobuff instead of redobuff
     */
    static private char read_redo(boolean init, boolean old_redo) {
        if(init) {
            if(redobuff.hasNext() == false) {
                return FAIL;
            } else {
                redobuff_idx = 0;
                return OK;
            }
        }
        char c = NUL; // assume none left
        if(redobuff.hasCharAt(redobuff_idx)) {
            c = redobuff.getCharAt(redobuff_idx);
            redobuff_idx++;
        }
        return c;
    }

    /**
     * Copy the rest of the redo buffer into the stuff buffer (could do faster).
     * if old_redo is TRUE, use old_redobuff instead of redobuff
     * <p>NOTE: old_redo ignored</p>
     */
    static private void copy_redo(boolean old_redo) {
        stuffReadbuff(redobuff.substring(redobuff_idx));
    }

    /**
     * Stuff the redo buffer into the stuffbuff.
     * Insert the redo count into the command.
     * If 'old_redo' is TRUE, the last but one command is repeated
     * instead of the last command (inserting text). This is used for
     * CTRL-O <.> in insert mode
     * <p>NOTE: old_redo true causes and exception</p>
     *
     * @return FAIL for failure, OK otherwise
     */
    static int start_redo(int count, boolean old_redo) {
        if(old_redo) {
            throw new RuntimeException("old_redo not false");
        }
        if(read_redo(true, old_redo) == FAIL) {
            return FAIL;
        }

        char c = read_redo(false, old_redo);

        // copy the buffer name if present
        if (c == '"') {
            stuffbuff.append(c);
            c = read_redo(false, old_redo);

            // if a numbered buffer is used, increment the number
            if (c >= '1' && c < '9')
                ++c;
            stuffbuff.append(c);
            c = read_redo(false, old_redo);
        }

        /* ********************************************************/
        if (c == 'v') {   // redo Visual	// VISUAL
            G.VIsual = G.curwin.w_cursor.copy();
            G.VIsual_active = true;
            G.VIsual_reselect = true;
            G.redo_VIsual_busy = true;
            c = read_redo(false, old_redo);
        }
        /*******************************************************/

        // try to enter the count (in place of a previous count)
        if (count != 0) {
            while (Util.isdigit(c)) {	// skip "old" count
                c = read_redo(false, old_redo);
            }
            stuffbuff.append(count);
        }

        // copy from the redo buffer into the stuff buffer
        stuffbuff.append(c);
        copy_redo(old_redo);
        handle_redo = true;
        if(G.dbgRedo.getBoolean())
            G.dbgRedo.println("stuffbuff = '"
                    + TextUtil.debugString(stuffbuff)+ "'");
        return OK;
    }

    /**
     * Repeat the last insert (R, o, O, a, A, i or I command) by stuffing
     * the redo buffer into the stuffbuff.
     * return FAIL for failure, OK otherwise
     */
    static int start_redo_ins() {
        char    c;

        if (read_redo(true, false) == FAIL)
            return FAIL;
        start_stuff();

        /* skip the count and the command character */
        while ((c = read_redo(false, false)) != NUL) {
            if (vim_strchr("AaIiRrOo", 0, c) >= 0) {
                if (c == 'O' || c == 'o')
                    stuffReadbuff(NL_STR);
                break;
            }
        }

        /* copy the typed text from the redo buffer into the stuff buffer */
        copy_redo(false);
        block_redo = true;
        handle_redo = true;
        return OK;
    }

    static void stop_redo_ins() {
        block_redo = false;
    }

    //
    // typebuf stuff
    //

    static int typemaplen;

    static int typebuf_maplen() {
        return typemaplen;
    }

    static private void init_typebuf() {
        typebuf.clear();
    }

    /**
     * insert a string in position 'offset' in the typeahead buffer (for "@r"
     * and ":normal" command, vgetorpeek() and check_termcode())
     * <pre>
     * If noremap is 0, new string can be mapped again.
     * If noremap is -1, new string cannot be mapped again.
     * If noremap is &gt; 0, that many characters of the
     *                       new string cannot be mapped.
     * If nottyped is TRUE, the string does not return KeyTyped (don't use when
     * offset is non-zero!).
     * </pre>
     *
     * @return FAIL for failure, OK otherwise
     */
    private static int ins_typebuf(CharSequence str, int noremap,
                                   int offset, boolean nottyped)
    {
        return typebuf.insert(str, noremap, offset, nottyped) ? OK : FAIL;
    }

    // introduce _redo version to make it clear that this
    // controls undo chunking
    static int ins_typebuf_redo(CharSequence str, int noremap,
                                int offset, boolean nottyped)
    {
        handle_redo = true;
        return typebuf.insert(str, noremap, offset, nottyped) ? OK : FAIL;
    }

    static int user_ins_typebuf(char c)
    {
        // If there are too many mappings between user input keys
        // then we'll do a flush_buf. This detects internal errors,
        // and avoids a hang.
        typebuf.clearNMappings(); // to detect/recover internal errors

        // add at end of typebuf with remap ok
        return ins_typebuf(String.valueOf(c), 0, typebuf.length(), false);
    }

    static void del_typebuf(int len, int offset)
    {
        typebuf.delete(offset, offset + len);
    }

  //
  // The various character queues
  //

    private static final BufferQueue stuffbuff = new BufferQueue();
    private static final BufferQueue redobuff = new BufferQueue();
    private static final BufferQueue recordbuff = new BufferQueue();
    private static ViMagicRedo magicRedo = new MagicRedoOriginal(redobuff);
    private static String startInsertCommand;

    private static int last_recorded_len = 0;  // number of last recorded chars
    private static int redobuff_idx = 0;
    // static BufferQueue old_redobuff;

    interface ViMagicRedo {
        void charTyped(char c);
        void markRedoBackspace();
        void changeInsstart();
        void initRedoTrackingPosition();
        void disableRedoTrackingOneEdit();
        String editComplete();
        void docInsert(int pos, String s);
        void docRemove(int pos, int len, String removedText);
    }
}

// vi:set sw=4 ts=8:
