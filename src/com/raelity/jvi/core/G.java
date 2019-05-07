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

import java.awt.Color;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import com.raelity.jvi.ViFPOS;
import com.raelity.jvi.ViOptionBag;
import com.raelity.jvi.ViOutputStream;
import com.raelity.jvi.ViTextView;
import com.raelity.jvi.core.lib.Constants.FDO;
import com.raelity.jvi.manager.ViManager;
import com.raelity.jvi.options.AlwaysOnDebugOption;
import com.raelity.jvi.options.DebugOption;
import com.raelity.jvi.options.OptUtil;


/**
 *  A class of globals. Most taken directly from vim code.
 */
public class G implements ViOptionBag
{
    static public final String metaEscapeDefault = "()|+?{";
    static public final String metaEscapeAll = "()|+?{";

    // Make an instance for use in reflection by set command
    private static final G INSTANCE = new G();
    public static G get() { return INSTANCE; }
    private G() {}
    @Override public void viOptionSet(ViTextView tv, String name) { assert false; }
    @Override public void activateOptions(ViTextView tv) { assert false; }

    /**
     *  Set the current editor.
     */
    static void switchTo( ViTextView tv, Buffer buf )
    {
        if(curwin == tv && curbuf != buf
                && dbgEditorActivation.getBoolean()) {
            dbgEditorActivation.println("Activation: changeBuffer tv "
                    + ViManager.cid(tv) + " buf " + ViManager.cid(buf));
        }
        curwin = (TextView)tv;
        curbuf = buf;
    }

    public static void setModMask( int mod_mask ) { G.mod_mask = mod_mask; }
    public static int getModMask() { return mod_mask; }

    static int mod_mask;

    // NEEDSWORK: create curwin() for use outside of core (jackpot)
    public static TextView curwin() { return curwin; }
    static TextView curwin;

    static Buffer curbuf; // per file options. as int,boolean

    public static void scrollToLine(ViTextView tv, int line) {
        if(tv == curwin)
            Misc01.scrollToLine(line);
    }

    /* ******************************************************************
    static boolean asyncCaretMovement = false;

    public final static boolean getAsyncCaretMovement() {
      return asyncCaretMovement;
    }

    public final static void setAsyncCaretMovement(boolean flag) {
      asyncCaretMovement = flag;
    }
    ********************************************************************/

    public static int no_mapping() { return no_mapping; }
    public static int allow_keys() { return allow_keys; }
    public static int no_zero_mapping() { return no_zero_mapping; }
    public static boolean Recording() { return Recording; }
    //
    static int no_mapping;
    static int allow_keys;
    static int no_zero_mapping;
    static boolean finish_op;
    static int State;
    static int restart_edit;
    static boolean Recording;
    static boolean Exec_reg;      // true when executing a register

    // for the global command
    static boolean global_busy;
    ViOutputStream global_output;

    static boolean clear_cmdline = false;
    static int old_indent = 0;
    static int ai_col = 0;

    public static int p_mmd() { return p_mmd; }
    public static int p_mmd2() { return p_mmd2; }
    static int p_mmd  = 10000;
    static int p_mmd2 = 10000;  // detects an internal error

    static boolean no_smartcase;
    static String p_km = "";      // keymodel


    public static boolean VIsual_active() { return VIsual_active; }
    public static boolean VIsual_select() { return VIsual_select; }
    public static char VIsual_mode() {return VIsual_mode;}
    //
    static ViFPOS VIsual; // start position of active Visual selection
    static boolean VIsual_active; // whether Visual mode is active
    static boolean VIsual_select; // whether Select mode is active
    static boolean VIsual_reselect;
    // whether to restart the selection after a Select mode mapping or menu
    static char VIsual_mode; // type of Visual mode

    public static boolean drawSavedVisualBounds() { return drawSavedVisualBounds;}
    public static void drawSavedVisualBounds(boolean f) { drawSavedVisualBounds=f;}
    // VISUAL repaint hack when in colon command from visual mode
    static boolean drawSavedVisualBounds = false;
    // end VISUAL repaint hack when in colon command from visual mode

    /* The visual area is remembered for redo */
    static boolean redo_VIsual_busy = false;

    /**
     * editPutchar, when not zero, overwrites on screen the character
     * at the cursor.
     */
    static char editPutchar;

    /**
     * processing a character typed by the user
     */
    static boolean KeyTyped = true;
    public static boolean getKeyTyped() { return KeyTyped; }

   /**
    * This flag is used to make auto-indent work right on lines where only a
    * <RETURN> or <ESC> is typed. It is set when an auto-indent is done, and
    * reset when any other editing is done on the line. If an <ESC> or <RETURN>
    * is received, and did_ai is TRUE, the line is truncated.
    */
    static boolean did_ai = false;

    /**
     * There are also individual flags, p_cpo_w, p_cpo_search, p_cpo_j
     */
    static String p_cpo = "aABceFs"; // cpoptions

    //////////////////////////////////////////////////////////////////////
    //
    // Options that show up in the dialog
    //

    public static DebugOption dbgOptions()
        { return dbgOptions; }
    public static DebugOption dbgWindowTreeBuilder()
        { return dbgWindowTreeBuilder; }
    public static DebugOption dbgPrefChangeMonitor()
        { return dbgPrefChangeMonitor; }
    public static DebugOption dbgEditorActivation()
        { return dbgEditorActivation; }
    public static DebugOption dbgUndo()
        { return dbgUndo; }
    public static DebugOption dbgCoordSkip()
        { return dbgCoordSkip; }
    public static DebugOption dbgFonts()
        { return dbgFonts; }
    //
    static DebugOption dbgEditorActivation;
    static DebugOption dbgCoordSkip;
    static DebugOption dbgUndo;
    static DebugOption dbgSearch;
    static DebugOption dbgKeyStrokes;
    static DebugOption dbgRedo;
    static DebugOption dbgMouse;
    static DebugOption dbgWindowTreeBuilder;
    static DebugOption dbgPrefChangeMonitor;
    static DebugOption dbgOptions = OptUtil.createBootDebugOption(false);
    static DebugOption dbgFonts;

    // not really a "DebugOption" but has similar output API
    public static final AlwaysOnDebugOption dbg = new AlwaysOnDebugOption();

    //
    // some options are accessed from out of core
    //
    public static boolean p_pbm() { return p_pbm; }
    public static boolean p_ea() { return p_ea; }
    public static Color p_rocc() { return p_rocc; }
    public static int p_so() { return p_so; }
    public static boolean p_vb() { return p_vb; }
    public static int p_vbt() { return p_vbt; }
    public static Color p_vbc() { return p_vbc; }


    static void p_wsOption_set(boolean f) {
        p_ws = f;
    }

    static class SaveCpo {
        boolean w;
        boolean search;
        boolean j;

        SaveCpo() {
            w      = p_cpo_w;
            search = p_cpo_search;
            j      = p_cpo_j;
        }

        void restore() {
            p_cpo_w = w;
            p_cpo_search = search;
            p_cpo_j = j;
        }

        void clearCpo() {
            p_cpo_w = false;
            p_cpo_search = false;
            p_cpo_j = false;
        }
    }

    // auditory bell
    static boolean p_ab;
    // backspace over start of insert, and more
    static int p_bs;
    // clipboard, treat as boolean for 'unnamed'
    static boolean p_cb;
    // sentence search two spaces
    static boolean p_cpo_j;
    // continue search from end of match
    static boolean p_cpo_search;
    // change word blanks
    static boolean p_cpo_w;
    // 'equalalways'
    static boolean p_ea;
    // 'equalprg'
    static String p_ep;
    // formatprg
    static String p_fp;

    static boolean p_hls;
    // ignorecase
    static boolean p_ic;

    static boolean p_is;
    // joinspaces, 2 spaces after .?!
    static boolean p_js;
    // modeline
    static boolean p_ml;
    // modelines
    static int p_mls;
    // startofline
    static boolean p_notsol;
    // use PlatformBraceMatch
    static boolean p_pbm;
    // metacharacters escaped
    static String p_rem;

    static int p_report;
    // true use '=' instead of '?'
    static boolean p_req;
    // read only cursor color
    static Color p_rocc;
    // 'splitbelow'
    static boolean p_sb;
    // showcmd
    static boolean p_sc;
    // smartcase
    static boolean p_scs;
    // 'selection'
    static String p_sel;
    // shell - used for external commands
    static String p_sh;
    // shellcmdflag - flag for shell
    static String p_shcf;
    // 'sidescrolloff'
    static int p_siso;
    // 'selectionmode'
    static String p_slm;
    // showmode edit/command mode in display
    static boolean p_smd;
    // scrolloff, lines before/after current
    static int p_so;
    // 'splitright'
    static boolean p_spr;
    // shiftround
    static boolean p_sr;
    // 'sidescroll'
    static int p_ss;
    // shellSlash - flag for filename expansion
    static boolean p_ssl;
    // shellXQuote - flag for shell
    static String p_sxq;
    // timeoutlen
    static int p_tm;
    // timeout
    static boolean p_to;
    // tildeop
    static boolean p_top;
    // visualbell and it's timer length
    static boolean p_vb;
    static int p_vbt;
    static Color p_vbc;
    // wrapscan
    static boolean p_ws;

    // static String p_ww = "bshl<>[]"; // b,s
    // options for whichwrap: p_ww_bs, ...
    static boolean p_ww_bs;
    static boolean p_ww_h;
    static boolean p_ww_i_left;
    static boolean p_ww_i_right;
    static boolean p_ww_l;
    static boolean p_ww_larrow;
    static boolean p_ww_rarrow;
    static boolean p_ww_sp;
    static boolean p_ww_tilde;

    // static EnumSet<FDO> fdo_flags;
    static EnumSet<FDO> p_fdo;
    @SuppressWarnings("unchecked")
    public static Set<FDO> fdo_flags() {
        return Collections.unmodifiableSet(p_fdo);
    }



    public static boolean disableFontError() { return disableFontError; }
    public static boolean disableFontCheckSpecial()
                { return disableFontCheckSpecial; }
    public static boolean usePlatformInsertTab() { return usePlatformInsertTab; }
    public static boolean useFrame() { return useFrame; }
    public static boolean isHideVersion() { return isHideVersion; }
    public static boolean isCoordSkip() { return isCoordSkip; }
    public static boolean isCursorXorBug() { return isCursorXorBug; }

    static boolean disableFontError;
    static boolean disableFontCheckSpecial;
    static boolean useFrame;    // use JFrame for command entry
    static boolean usePlatformInsertTab;
    static boolean readOnlyHack;
    static boolean isHideVersion;
    static boolean isCoordSkip;
    static boolean redoTrack; // track redo for magic/code-completion
    static boolean pcmarkTrack; // track NB caret motions for pcmark
    static int viminfoMaxBuf; //viminfoMaxPersistedBuffersWithMarks
    static boolean isCursorXorBug;

    public static final boolean False = false;

} // end com.raelity.jvi.G
