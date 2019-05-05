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
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.beans.PropertyVetoException;
import java.util.EnumSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.PreferenceChangeEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openide.util.lookup.ServiceProvider;

import com.raelity.jvi.ViInitialization;
import com.raelity.jvi.ViOutputStream;
import com.raelity.jvi.core.lib.Mappings;
import com.raelity.jvi.lib.CharTab;
import com.raelity.jvi.lib.Wrap;
import com.raelity.jvi.manager.ViManager;
import com.raelity.jvi.options.DebugOption;
import com.raelity.jvi.options.OptUtil;
import com.raelity.jvi.options.Option;
import com.raelity.jvi.options.SetColonCommand;
import com.raelity.jvi.options.Validator;
import com.raelity.text.TextUtil.MySegment;

import static com.raelity.jvi.core.lib.Constants.*;
import static com.raelity.jvi.core.lib.Constants.FDO.*;
import static com.raelity.jvi.core.lib.Constants.NF.*;

/**
 * Option handling from external sources.
 * <br>
 * Should there be a vi command to set the options to persistent storage,
 * this is useful if want to save after several set commands.
 * <br>
 *
 */

public final class Options {
  private static final Logger LOG = Logger.getLogger(Options.class.getName());
  private Options() {
  }
  private static Options options;
  private static final PropertyChangeSupport pcs
                        = new PropertyChangeSupport(getOptions());

    public static interface EditControl {
        /** starting edit */
        public void start();
        /** accept an edit */
        public void ok();
        /** cancel an in progress edit */
        public void cancel();
    }

    public static class Init implements ViInitialization
    {
      @Override
      public void init()
      {
        Options.init();
      }
    }

    // Sigh, don't want public...
    // invoked from OptUtil
    public static void optionChangeFixup(Option opt, PreferenceChangeEvent evt)
    {
      if(evt.getKey().equals(mapCommands)) {
        GetChar.reinitMappings();
      }
    }

  static Options getOptions() {
      if(options == null) {
          options = new Options();
      }
      return options;
  }

  /**
   * Options are grouped into categories. Typically a UI groups the options
   * by category when presenting an options editor.
   */
  public enum Category { PLATFORM, GENERAL, MODIFY, SEARCH, CURSOR_WRAP,
                         PROCESS, DEBUG }

  public static final String commandEntryFrame = "viCommandEntryFrameOption";
  public static final String redoTrack = "viRedoTrack";
  public static final String pcmarkTrack = "viPCMarkTrack";
  public static final String autoPopupFN = "viAutoPopupFN";
  public static final String autoPopupCcName = "viAutoPopupCcName";
  public static final String coordSkip = "viCoordSkip";
  public static final String platformPreferences = "viPlatformPreferences";
  public static final String platformTab = "viPlatformTab";
  public static final String magicRedoAlgorithm = "viMagicRedoAlgorithm";
  public static final String caretBlinkRate = "viCaretBlinkRate";
  public static final String disableFontError = "viDisableFontError";
  public static final String disableFontCheckSpecial = "viDisableFontCheckSpecial";
  public static final String cursorXorBug = "viCursorXorBug";

  public static final String backspaceWrapPrevious = "viBackspaceWrapPrevious";
  public static final String hWrapPrevious = "viHWrapPrevious";
  public static final String leftWrapPrevious = "viLeftWrapPrevious";
  public static final String spaceWrapNext = "viSpaceWrapNext";
  public static final String lWrapNext = "viLWrapNext";
  public static final String rightWrapNext = "viRightWrapNext";
  public static final String tildeWrapNext = "viTildeWrapNext";
  public static final String insertLeftWrapPrevious = "viInsertLeftWrapPrevious";
  public static final String insertRightWrapNext = "viInsertRightWrapNext";

  public static final String unnamedClipboard = "viUnnamedClipboard";
  public static final String joinSpaces = "viJoinSpaces";
  public static final String shiftRound = "viShiftRound";
  public static final String notStartOfLine = "viNotStartOfLine";
  public static final String changeWordBlanks = "viChangeWordBlanks";
  public static final String tildeOperator = "viTildeOperator";
  public static final String searchFromEnd = "viSearchFromEnd";
  public static final String endOfSentence = "viEndOfSentence";
  public static final String wrapScan = "viWrapScan";
  public static final String isKeyWord = "viIsKeyWord";

  public static final String metaEquals = "viMetaEquals";
  public static final String metaEscape = "viMetaEscape";
  public static final String incrSearch = "viIncrSearch";
  public static final String highlightSearch = "viHighlightSearch";

  public static final String ignoreCase = "viIgnoreCase";
  public static final String smartCase = "viSmartCase";
  public static final String platformBraceMatch = "viPlatformBraceMatch";

  public static final String expandTabs = "viExpandTabs";

  public static final String report = "viReport";
  public static final String backspace = "viBackspace";
  public static final String scrollOff = "viScrollOff";
  public static final String sideScroll = "viSideScroll";
  public static final String sideScrollOff = "viSideScrollOff";
  public static final String shiftWidth = "viShiftWidth";
  public static final String tabStop = "viTabStop";
  public static final String softTabStop = "viSoftTabStop";
  public static final String textWidth = "viTextWidth";
  public static final String showMode = "viShowMode";
  public static final String showCommand = "viShowCommand";
  public static final String visualBell = "viVisualBell";
  public static final String visualBellTime = "viVisualBellTime";
  public static final String visualBellColor = "viVisualBellColor";

  public static final String wrap = "viWrap";
  public static final String list = "viList";
  public static final String lineBreak = "viLineBreak";
  public static final String number = "viNumber";
  public static final String scroll = "viScroll";
  public static final String timeout = "viTimeout";
  public static final String timeoutlen = "viTimeoutLen";
  public static final String equalAlways = "viEqualAlways";
  public static final String splitBelow = "viSplitBelow";
  public static final String splitRight = "viSplitRight";
  public static final String foldOpen = "viFoldOpen";

  public static final String nrFormats = "viNrFormats";
  public static final String matchPairs = "viMatchPairs";
  public static final String quoteEscape = "viQuoteEscape";

  public static final String modeline = "viModeline";
  public static final String modelines = "viModelines";

  public static final String selection = "viSelection";
  public static final String selectMode = "viSelectMode";
  public static final String selectColor = "viSelectColor";
  public static final String selectFgColor = "viSelectFgColor";
  public static final String searchColor = "viSearchColor";
  public static final String searchFgColor = "viSearchFgColor";
  public static final String roCursorColor = "viRoCursorColor";

  public static final String equalProgram = "viEqualProgram";
  public static final String formatProgram = "viFormatProgram";

  public static final String shell = "viShell";
  public static final String shellCmdFlag = "viShellCmdFlag";
  public static final String shellXQuote = "viShellXQuote";
  public static final String shellSlash = "viShellSlash";

  public static final String mapCommands = "viMapCommands";

  public static final String persistedBufMarks = "viPersistedBufMarks";

  public static final String readOnlyHack = "viReadOnlyHack";
  public static final String classicUndoOption = "viClassicUndo";
  public static final String hideVersionOption = "viHideVersion";

  public static final String dbgRedo = "viDbgRedo";
  public static final String dbgKeyStrokes = "viDbgKeyStrokes";
  public static final String dbgCache = "viDbgCache";
  public static final String dbgEditorActivation = "viDbgEditorActivation";
  public static final String dbgBang = "viDbgBang";
  public static final String dbgBangData = "viDbgBangData";
  public static final String dbgMouse = "viDbgMouse";
  public static final String dbgCompletion = "viDbgCompletion";
  public static final String dbgCoordSkip = "viDbgCoordSkip";
  public static final String dbgUndo = "viDbgUndo";
  public static final String dbgSearch = "viDbgSearch";
  public static final String dbgOptions = "viDbgOptions";
  public static final String dbgWindowTreeBuilder = "viDbgWindowTreeBuilder";
  public static final String dbgPrefChangeMonitor = "viDbgPrefChangeMonitor";
  public static final String dbgFonts = "viDbgFonts";

  public static final String twMagic = "#TEXT-WIDTH#";

  private static boolean didInit = false;
  private static void init() {
    if(didInit) {
      return;
    }
    didInit = true;

    OptUtil.init(pcs);

    // Since this is used to debug options, put it first

    G.dbgOptions = OptUtil.createDebugOption(dbgOptions);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgOptions, "debug options set", "");

    // Some options that do not appear in the dialog property sheets.

    OptUtil.createIntegerOption(scroll, 0);
    OptUtil.setupOptionDesc(null, scroll, "'scroll' 'scr'", "");

    //
    // Put this in GENERAL, but mark it hidden.
    // It is handled very specially.
    //
    OptUtil.createStringOption(mapCommands,
              ""
            + "\" These two mappings, which apply to PLATFORM-SELECT only,"
            + "\n\" get 'y' and 'p' to work with the mouse selection."
            + "\n\" pnoremap y vy"
            + "\n\" pnoremap p vp"
            + "\n"
            + "\n\" These mapping are handy if line wrap is turned on."
            + "\n\" With them, j,k,$,0,^ behave visually as usual."
            + "\n\" On wrapped lines the cursor will not change visual line."
            + "\n\" The arrow keys and <Home>, <End> still behave as usual,"
            + "\n\" they could be mapped as well."
            + "\n\" Uncomment them and try them out with wrapped lines."
            + "\n"
            + "\n\" map j gj"
            + "\n\" map k gk"
            + "\n\" map $ g$"
            + "\n\" map 0 g0"
            + "\n\" map ^ g^"
            + "",
            new Validator<String>() {
            @Override
              public void validate(String val) throws PropertyVetoException {

                Wrap<String> emsg = new Wrap<String>("");
                if(null == Mappings.parseMapCommands(val, emsg)) {
                  reportPropertyVetoException(emsg.getValue(), val);
                }
              }
            });
    OptUtil.setupOptionDesc(Category.GENERAL, mapCommands, "Map Commands",
            "map-cmd {lhs} {rhs}"
            + "\n[nvop]map, [nvop]noremap and [nvop]unmap commands supported"
            + " (only normal mode mappings)."
            + "\npmap is jVi only, when PLATFORM-SELECT."
            + "\nComments are on a line by themselves and start with \"."
            + "\nIn lhs or rhs a char is of the form:"
            + "\n\u00a0\u00a0\u00a0\u00a0\"c\"           - except \\ and < and space"
            + "\n\u00a0\u00a0\u00a0\u00a0\"<C-X>\"       - except Ctrl-\\"
            + "\n\u00a0\u00a0\u00a0\u00a0\"<special>\"   - see jVi doc for valid specials"
            + "\n\u00a0\u00a0\u00a0\u00a0\"<C-special>\" \"<S-special>\""
            + "\nSome specials: <lt>,<Space>,<Bslash>,<Up>,<Home>"
            );
    setExpertHidden(mapCommands, true, true);

    /////////////////////////////////////////////////////////////////////
    //
    //
    // Debug Options
    //
    //

    // debug options first

    G.dbgEditorActivation = OptUtil.createDebugOption(dbgEditorActivation);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgEditorActivation, "debug activation",
               "Output info about editor switching between files/windows");

    kd = OptUtil.createDebugOption(dbgKeyStrokes);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgKeyStrokes, "debug KeyStrokes",
               "Output info for each keystroke");

    G.dbgRedo = OptUtil.createDebugOption(dbgRedo);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgRedo, "debug redo buffer",
               "Output info on magic/tracking changes to redo buffer");

    OptUtil.createDebugOption(dbgCache);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgCache, "debug cache",
               "Output info on text/doc cache");

    OptUtil.createDebugOption(dbgBang);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgBang, "debug \"!\" cmds",
               "Output info about external processes");

    OptUtil.createDebugOption(dbgBangData);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgBangData, "debug \"!\" cmds data",
               "Output data tranfers external processes");

    OptUtil.createDebugOption(dbgCompletion);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgCompletion, "debug Completion",
               "Output info on completion, eg FileName.");

    G.dbgMouse = OptUtil.createDebugOption(dbgMouse);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgMouse, "debug mouse events",
               "Output info about mouse events");

    G.dbgCoordSkip = OptUtil.createDebugOption(dbgCoordSkip);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgCoordSkip,
                            "debug coordinate skip", "");

    G.dbgUndo = OptUtil.createDebugOption(dbgUndo);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgUndo, "debug undo begin/end", "");

    G.dbgSearch = OptUtil.createDebugOption(dbgSearch);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgSearch, "debug search", "");

    G.dbgWindowTreeBuilder = OptUtil.createDebugOption(dbgWindowTreeBuilder);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgWindowTreeBuilder, "debug window tree builder", "");

    G.dbgPrefChangeMonitor = OptUtil.createDebugOption(dbgPrefChangeMonitor);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgPrefChangeMonitor, "debug pref change monitor", "");

    G.dbgFonts = OptUtil.createDebugOption(dbgFonts);
    OptUtil.setupOptionDesc(Category.DEBUG, dbgFonts, "debug font issues", "");

    /////////////////////////////////////////////////////////////////////
    //
    //
    // Platform Options
    //
    //

    // platformList.add("jViVersion"); // hard coded in OptUtil.init

    OptUtil.createBooleanOption(redoTrack, true);
    OptUtil.setupOptionDesc(Category.PLATFORM, redoTrack, "\".\" magic redo tracking",
                    "Track magic document changes during input"
                    + " mode for the \".\" commnad. These"
                    + " changes are often the result of IDE code completion");

    OptUtil.createBooleanOption(pcmarkTrack, true);
    OptUtil.setupOptionDesc(Category.PLATFORM, pcmarkTrack,
                    "\"``\" magic pcmark tracking", "Track magic cursor "
                    + " movments for the \"``\" command. These movement are"
                    + " often the result of IDE actions invoked external"
                    + " to jVi.");

    OptUtil.createColorOption(searchColor, new Color(0xffb442), false); //a light orange
    OptUtil.setupOptionDesc(Category.PLATFORM, searchColor, "'hl-search' color",
            "The color used for search highlight.");

    //createColorOption(searchFgColor, new Color(0x000000)); // black
    OptUtil.createColorOption(searchFgColor, new Color(0x000000), true);
    OptUtil.setupOptionDesc(Category.PLATFORM, searchFgColor, "'hl-search' foreground color",
            "The color used for search highlight foreground."
                    + " If 'null' then use editor's foreground color");
    setExpertHidden(searchFgColor, false, false);

    OptUtil.createColorOption(selectColor, new Color(0xffe588), false); //a light orange
    OptUtil.setupOptionDesc(Category.PLATFORM, selectColor, "'hl-visual' color",
            "The color used for a visual mode selection.");

    //createColorOption(selectFgColor, new Color(0x000000)); // black
    OptUtil.createColorOption(selectFgColor, null, true); // default is no color
    OptUtil.setupOptionDesc(Category.PLATFORM, selectFgColor, "'hl-visual' foreground color",
            "The color used for a visual mode selection foreground."
                    + " If 'null' then use editor's foreground color");
    setExpertHidden(selectFgColor, false, false);

    OptUtil.createColorOption(roCursorColor, Color.red, true);
    OptUtil.setupOptionDesc(Category.PLATFORM, roCursorColor, "'rocursorcolor' 'rocc'",
            "The cursor color in a read only editor."
                    + " If 'null' then use editor's default cursor color");

    OptUtil.createBooleanOption(hideVersionOption, false);
    OptUtil.setupOptionDesc(Category.PLATFORM, hideVersionOption, "hide version",
                    "When true, display of initial version information"
                    + " does not bring up output window.");
    setExpertHidden(hideVersionOption, true, false);

    OptUtil.createBooleanOption(commandEntryFrame , true);
    OptUtil.setupOptionDesc(Category.PLATFORM, commandEntryFrame, "use modal frame",
               "Use modal frame for command/search entry."
               + " Change takes affect after restart.");
    setExpertHidden(commandEntryFrame, true, false);

    OptUtil.createBooleanOption(autoPopupFN, true);
    OptUtil.setupOptionDesc(Category.PLATFORM, autoPopupFN,
                            "\":e#\" Completion Auto Popup",
               "When doing \":\" command line entry, if \"e#\" is"
               + " entered then automatically popup a file"
               + " name completion window.");

    OptUtil.createBooleanOption(autoPopupCcName, true);
    OptUtil.setupOptionDesc(Category.PLATFORM, autoPopupCcName,
                            "\":\" Command Completion Auto Popup",
               "After doing \":\" for command line entry,"
               + " automatically popup command"
               + " name completion.");
    setExpertHidden(autoPopupCcName, false, false);

    OptUtil.createBooleanOption(coordSkip, true);
    OptUtil.setupOptionDesc(Category.PLATFORM, coordSkip, "Code Folding Compatible",
            "When false revert some navigation algorithms, e.g. ^F,"
            + " to pre code folding behavior. A just in case option;"
            + " if needed, please file a bug report.");
    setExpertHidden(coordSkip, true, false);

    OptUtil.createBooleanOption(platformPreferences, false);
    OptUtil.setupOptionDesc(Category.PLATFORM, platformPreferences,
                    "Store init (\"vimrc\") with Platform",
                    "Store user preferences/options in platform location."
                    + " Change occurs after next application startup."
                    + " For example, on NetBeans store in userdir."
                    + " NOTE: except for the first switch to platform,"
                    + " changes made in one area"
                    + " are not propogated to the other.");
    setExpertHidden(platformPreferences, true, true);

    OptUtil.createBooleanOption(platformTab, false);
    OptUtil.setupOptionDesc(Category.PLATFORM, platformTab,
            "Use the platform's TAB handling",
            "When false, jVi processes the TAB character according"
            + " to the expandtab and softtabstop options. Otherwise"
            + " the TAB is passed to the platform, e.g. IDE, for handling."
            + " The only reason to set this true is if a bug is discovered"
            + " in the jVi tab handling.");
    setExpertHidden(platformTab, true, false);

    OptUtil.createEnumStringOption(magicRedoAlgorithm, "anal",
            new String[] {"anal", "guard"});
    OptUtil.setupOptionDesc(Category.PLATFORM,
                            magicRedoAlgorithm, "magic redo algorithm",
            "Which algorithm to use to capture code completion"
            + " changes for use in a subsequent '.' (redo) command."
            + " None is perfect."
            + "\n\n"
            + "The 'anal' algorithm looks at each document change,"
            + " analizes it and adjusts the redo buffer."
            + "\n\n"
            + "The 'guard'"
            + " algorithm places marks around the insertion point and"
            + " captures that as the change;"
            + " this is currently experimental, but handles some single"
            + " line cases better; simpler algorithm.");
    setExpertHidden(magicRedoAlgorithm, true, false);

    OptUtil.createIntegerOption(caretBlinkRate, 300);
    OptUtil.setupOptionDesc(Category.PLATFORM,
                            caretBlinkRate, "caret blink rate",
            "This determines if and how fast the caret blinks."
            + " If this is zero the caret will not blink");

    OptUtil.createBooleanOption(disableFontError, false);
    OptUtil.setupOptionDesc(Category.PLATFORM,
                            disableFontError, "Font Check disable Problem Dialog",
            "If a font size problem is detected, don't bring up a dialog."
            + " No matter how this is set, the error is reported in"
            + " the output window");

    OptUtil.createBooleanOption(disableFontCheckSpecial, true);
    OptUtil.setupOptionDesc(Category.PLATFORM,
                            disableFontCheckSpecial,
                            "Font Check ignore special chars",
            "By default all characters are used to determine font width."
            + " Some fonts have special characters, unicode u0000 to u001f,"
            + " that are a different width from standard chars."
            + " Use this option to ignore the special chars when checking"
            + "for font size problems.");

    OptUtil.createBooleanOption(cursorXorBug, false);
    OptUtil.setupOptionDesc(Category.PLATFORM,
                            cursorXorBug,
                            "Mac Retina Cursor Xor Bug",
            "By default jVi uses graphics xor when drawing the cursor."
            + " On the Mac's Retina display there may be a problem with"
            + " xor draw mode; the symptom is that the cursor is not"
            + " visible. Set this option to avoid the problem.");

    /////////////////////////////////////////////////////////////////////
    //
    //
    // General Options
    //
    //

    OptUtil.createBooleanOption(visualBell, true);
    OptUtil.setupOptionDesc(Category.GENERAL, visualBell, "'visualbell' 'vb'",
	   "Use visual bell instead of beeping.  The editor window"
                   + " background is inverted for a period of time, "
                   + " see 'vbt' option."
                   + " When no beep or flash is wanted, set time to zero.");
    OptUtil.createIntegerOption(visualBellTime, 20);
    OptUtil.setupOptionDesc(Category.GENERAL, visualBellTime,
                            "'visualbelltime' 'vbt'",
	   "The duration, in milliseconds, of the 'visual bell'. If the"
                   + " visual bell is enabled, see 'vb', and the 'vbt'"
                   + " value is zero then there is no beep or flash.");

    OptUtil.createColorOption(visualBellColor, new Color(0x41e7e7), true);
    OptUtil.setupOptionDesc(Category.GENERAL, visualBellColor,
                            "'visualbellcolor' 'vbc'",
            "The color used for the visual bell, the editor's background"
                    + " is set to this color."
                    + " If null, then the editor's background color"
                    + " is inverted");

    OptUtil.createIntegerOption(scrollOff, 0);
    OptUtil.setupOptionDesc(Category.GENERAL, scrollOff, "'scrolloff' 'so'",
           "visible context around cursor (scrolloff)"
            + "	Minimal number of screen lines to keep above and below the"
            + " cursor. This will make some context visible around where you"
            + " are working.  If you set it to a very large value (999) the"
            + " cursor line will always be in the middle of the window"
            + " (except at the start or end of the file)");

    OptUtil.createIntegerOption(sideScroll, 0);
    OptUtil.setupOptionDesc(Category.GENERAL, sideScroll, "'sidescroll' 'ss'",
            "The minimal number of columns to scroll horizontally.  Used"
            + "only when the 'wrap' option is off and the cursor is moved"
            + "off of the screen. When it is zero the cursor will be put"
            + "in the middle of the screen."
            + "Not used for \"zh\" and \"zl\" commands.");
    setExpertHidden(sideScroll, false, true);

    OptUtil.createIntegerOption(sideScrollOff, 0);
    OptUtil.setupOptionDesc(Category.GENERAL, sideScrollOff, "'sidescrolloff' 'siso'",
            "The minimal number of screen columns to keep to the left and"
            + "to the right of the cursor if 'nowrap' is set.");
    setExpertHidden(sideScrollOff, false, true);

    OptUtil.createBooleanOption(showMode, true);
    OptUtil.setupOptionDesc(Category.GENERAL, showMode, "'showmode' 'smd'",
            "If in Insert or Replace mode display that information.");

    OptUtil.createBooleanOption(showCommand, true);
    OptUtil.setupOptionDesc(Category.GENERAL, showCommand, "'showcmd' 'sc'",
            "Show (partial) command in status line.");

    OptUtil.createIntegerOption(report, 2);
    OptUtil.setupOptionDesc(Category.GENERAL, report, "'report'",
            "Threshold for reporting number of lines changed.  When the"
            + " number of changed lines is more than 'report' a message will"
            + " be given for most \":\" commands.  If you want it always, set"
            + " 'report' to 0.  For the \":substitute\" command the number of"
            + " substitutions is used instead of the number of lines.");

    OptUtil.createBooleanOption(modeline, true);
    OptUtil.setupOptionDesc(Category.GENERAL, modeline, "'modeline' 'ml'",
            "Enable/disable modelines option."
            + "\n[text]{white}{vi:|vim:|ex:}[white]{options}"
            + "\n\u00a0\u00a0\u00a0\u00a0example: vi:noai:sw=3 ts=6"
            + "\n[text]{white}{vi:|vim:|ex:}[white]se[t] {options}:[text]"
            + "\n\u00a0\u00a0\u00a0\u00a0example: /* vim: set ai tw=75: */");

    OptUtil.createIntegerOption(modelines, 5);
    OptUtil.setupOptionDesc(Category.GENERAL, modelines, "'modelines' 'mls'",
	    " If 'modeline' is on 'modelines' gives the number of lines"
            + " that is checked for set commands.  If 'modeline' is off"
            + " or 'modelines' is zero no lines are checked.");

    OptUtil.createBooleanOption(unnamedClipboard, false);
    OptUtil.setupOptionDesc(Category.GENERAL, unnamedClipboard,
               "'clipboard' 'cb' (unnamed)",
               "use clipboard for unamed yank, delete and put");

    OptUtil.createBooleanOption(notStartOfLine, false);
    OptUtil.setupOptionDesc(Category.GENERAL, notStartOfLine, "(not)'startofline' (not)'sol'",
               "After motion try to keep column position."
            + " NOTE: state is opposite of vim.");

    OptUtil.createIntegerOption(
            persistedBufMarks, 25, new Validator<Integer>() {
              @Override
              public void validate(Integer val) throws PropertyVetoException {
                  if(val < 0 || val > 100) {
                    reportPropertyVetoException(
		         "Only 0 - 100 allowed. Not '" + val + "'.",
                         val);
                  }
              }
            });
    OptUtil.setupOptionDesc(Category.GENERAL, persistedBufMarks, "max persisted buf-marks",
            "Maximum number of previously edited files for which the marks"
	  + " are remembered. Set to 0 and no marks are persisted.");

    OptUtil.createEnumStringOption(selection, "inclusive",
            new String[] {"old", "inclusive", "exclusive"});
    OptUtil.setupOptionDesc(Category.GENERAL, selection, "'selection' 'sel'",
            "This option defines the behavior of the selection."
            + " It is only used in Visual and Select mode."
            + "Possible values: 'old', 'inclusive', 'exclusive'");
    setExpertHidden(selection, false, false);

    OptUtil.createEnumStringOption(selectMode, "",
            new String[] {"mouse", "key", "cmd"});
    OptUtil.setupOptionDesc(Category.GENERAL, selectMode, "'selectmode' 'slm'",
            "This is a comma separated list of words, which specifies when to"
            + " start Select mode instead of Visual mode, when a selection is"
            + " started. Possible values: 'mouse', key' or 'cmd'");
    setExpertHidden(selectMode, true, true);

    OptUtil.createBooleanOption(wrap, true);
    OptUtil.setupOptionDesc(Category.GENERAL, wrap, "'wrap'",
          "This option changes how text is displayed."
          + " When on, lines longer than the width of the window will"
          + " wrap and displaying continues on the next line.  When off"
          + " lines will not wrap and only part of long lines will"
          + " be displayed."
          + "\n\n"
          + "The line will be broken in the middle of a word if necessary."
          + " See 'linebreak' to get the break at a word boundary."
            );

    OptUtil.createBooleanOption(lineBreak, false);
    OptUtil.setupOptionDesc(Category.GENERAL, lineBreak, "'linebreak' 'lbr'",
          "If on Vim will wrap long lines at a word boundary rather"
          + " than at the last character that fits on the screen.");

    OptUtil.createBooleanOption(number, false);
    OptUtil.setupOptionDesc(Category.GENERAL, number, "'number' 'nu'",
          "Print the line number in front of each line.");

    OptUtil.createBooleanOption(list, false);
    OptUtil.setupOptionDesc(Category.GENERAL, list, "'list'",
          "List mode. Useful to see the difference between tabs"
            + " and spaces and for trailing blanks.");

    OptUtil.createBooleanOption(timeout, true);
    OptUtil.setupOptionDesc(Category.GENERAL, timeout, "'timeout' 'to'",
          "Enables timeout when part of a mapped key sequence has been"
            + " received. After that the already received"
            + " characters are interpreted as single characters. "
            + " The waiting time can be changed with the 'timeoutlen' option.");

    OptUtil.createIntegerOption(timeoutlen, 1000);
    OptUtil.setupOptionDesc(Category.GENERAL, timeoutlen, "'timeoutlen' 'tm'",
          "The time in milliseconds that is waited for a mapped"
            + " key sequence to complete.");

    OptUtil.createBooleanOption(equalAlways, true);
    OptUtil.setupOptionDesc(Category.GENERAL, equalAlways, "'equalalways' 'ea'",
        "When on, all the windows are automatically made the same size after"
	+ " splitting or closing a window.");

    OptUtil.createBooleanOption(splitBelow, false);
    OptUtil.setupOptionDesc(Category.GENERAL, splitBelow, "'splitbelow' 'sb'",
	"When on, splitting a window will put the new window below the current"
	+ " one.");

    OptUtil.createBooleanOption(splitRight, false);
    OptUtil.setupOptionDesc(Category.GENERAL, splitRight, "'splitright' 'spr'",
	"When on, splitting a window will put the new window right of the"
	+ " current one.");

    /*G.p_fdo = */OptUtil.createEnumSetOption(foldOpen,
            EnumSet.of(
                FDO_BLOCK, FDO_HOR, FDO_MARK, FDO_PERCENT, FDO_QUICKFIX,
                FDO_SEARCH, FDO_TAG, FDO_UNDO),
            FDO.class, null);

    OptUtil.setupOptionDesc(Category.GENERAL, foldOpen, "'foldopen' 'fdo'",
          "Specifies for which type of commands folds will be opened, if the"
        + " command moves the cursor into a closed fold.");

    /////////////////////////////////////////////////////////////////////
    //
    //
    // Vi Options that affect modifications to the document
    //
    //

    OptUtil.createBooleanOption(tildeOperator, false);
    OptUtil.setupOptionDesc(Category.MODIFY, tildeOperator , "'tildeop' 'top'",
               "tilde \"~\" acts like an operator, e.g. \"~w\" works");

    OptUtil.createBooleanOption(changeWordBlanks, true);
    OptUtil.setupOptionDesc(Category.MODIFY, changeWordBlanks, "'cpoptions' 'cpo' \"w\"",
               "\"cw\" affects sequential white space");

    OptUtil.createBooleanOption(joinSpaces, true);
    OptUtil.setupOptionDesc(Category.MODIFY, joinSpaces, "'joinspaces' 'js'",
               "\"J\" inserts two spaces after a \".\", \"?\" or \"!\"");

    OptUtil.createBooleanOption(shiftRound, false);
    OptUtil.setupOptionDesc(Category.MODIFY, shiftRound, "'shiftround' 'sr'",
               "\"<\" and \">\" round indent to multiple of shiftwidth");

    OptUtil.createEnumIntegerOption(backspace, 0, new Integer[] { 0, 1, 2});
    OptUtil.setupOptionDesc(Category.MODIFY, backspace, "'backspace' 'bs'",
            "Influences the working of <BS>, <Del> during insert."
            + "\n  0 - no special handling."
            + "\n  1 - allow backspace over <EOL>."
            + "\n  2 - allow backspace over start of insert.");

    /////////////////////////
    //
    // per buffer options are accessed through curbuf.
    //

    /*G.b_p_et = */OptUtil.createBooleanOption(expandTabs, false);
    OptUtil.setupOptionDesc(Category.MODIFY, expandTabs, "'expandtab' 'et'",
           "In Insert mode: Use the appropriate number of spaces to"
           + " insert a <Tab>. Spaces are used in indents with the '>' and"
           + " '<' commands.");

    /*G.b_p_sw = */OptUtil.createIntegerOption(shiftWidth, 8);
    OptUtil.setupOptionDesc(Category.MODIFY, shiftWidth, "'shiftwidth' 'sw'",
            "Number of spaces to use for each step of indent. Used for '>>',"
            + " '<<', etc.");

    /*G.b_p_ts = */OptUtil.createIntegerOption(tabStop, 8);
    OptUtil.setupOptionDesc(Category.MODIFY, tabStop, "'tabstop' 'ts'",
            "Number of spaces that a <Tab> in the file counts for.");

    /*G.b_p_sts = */OptUtil.createIntegerOption(softTabStop, 0);
    OptUtil.setupOptionDesc(Category.MODIFY, softTabStop, "'softtabstop' 'sts'",
            "Number of spaces that a <Tab> in the file counts for"
            + " while performing editing operations,"
            + " like inserting a <Tab> or using <BS>."
            + " It \"feels\" like <Tab>s are being inserted, while in fact"
            + " a mix of spaces and <Tab>s is used (<Tabs>s only if"
            + " 'expandtabs' is false).  When 'sts' is zero, this feature"
            + " is off. If 'softtabstop' is non-zero, a <BS> will try to"
            + " delete as much white space to move to the previous"
            + " 'softtabstop' position."
            );

    /*G.b_p_xx = */OptUtil.createIntegerOption(textWidth, 79);
    OptUtil.setupOptionDesc(Category.MODIFY, textWidth, "'textwidth' 'tw'",
            "This option currently only used in conjunction with the"
            + " 'gq' and 'Q' format command. This value is substituted"
            + " for " + twMagic + " in formatprg option string.");

    /*G.b_p_nf = */OptUtil.createEnumSetOption(nrFormats,
        EnumSet.of(NF_HEX, NF_OCTAL), NF.class, null);
    OptUtil.setupOptionDesc(Category.MODIFY, nrFormats, "'nrformats' 'nf'",
            "Defines bases considered for numbers with the"
            + " 'CTRL-A' and 'CTRL-X' commands for adding to and subtracting"
            + " from a number respectively. Value is comma separated list;"
            + " 'octal,hex,alpha' is all possible values.");

    /////////////////////////////////////////////////////////////////////
    //
    //
    // Vi searching options
    //
    //

    OptUtil.createBooleanOption(incrSearch, true);
    OptUtil.setupOptionDesc(Category.SEARCH, incrSearch, "'incsearch' 'is'",
            "While typing a search command, show where the pattern, as it was"
            + " typed so far, matches. If invalid pattern, no match"
            + " or abort then the screen returns to its original location."
            + " You still need to finish the search with"
            + " <ENTER> or abort it with <ESC>.");

    OptUtil.createBooleanOption(highlightSearch, true);
    OptUtil.setupOptionDesc(Category.SEARCH, highlightSearch, "'hlsearch' 'hls'",
                    "When there is a previous search pattern, highlight"
                    + " all its matches");

    OptUtil.createBooleanOption(ignoreCase, false);
    OptUtil.setupOptionDesc(Category.SEARCH, ignoreCase, "'ignorecase' 'ic'",
            "Ignore case in search patterns.");

    OptUtil.createBooleanOption(smartCase, false);
    OptUtil.setupOptionDesc(Category.SEARCH, smartCase, "'smartcase' 'scs'",
            "Override the 'ignorecase' option if the search pattern"
            + " contains upper case characters.");

    OptUtil.createBooleanOption(wrapScan, true);
    OptUtil.setupOptionDesc(Category.SEARCH, wrapScan, "'wrapscan' 'ws'",
               "Searches wrap around the end of the file.");

    OptUtil.createBooleanOption(searchFromEnd, true);
    OptUtil.setupOptionDesc(Category.SEARCH, searchFromEnd, "'cpoptions' 'cpo' \"c\"",
               "search continues at end of match");

    OptUtil.createBooleanOption(endOfSentence, false);
    OptUtil.setupOptionDesc(Category.SEARCH, endOfSentence, "'cpoptions' 'cpo' \"j\"",
		  "A sentence has to be followed by two spaces after"
                + " the '.', '!' or '?'.  A <Tab> is not recognized as"
                + " white space.");

    OptUtil.createBooleanOption(platformBraceMatch, true);
    OptUtil.setupOptionDesc(Category.SEARCH, platformBraceMatch, "Platform Brace Matching",
		  "Use the platform/IDE for brace matching"
                  + " and match highlighting. This may enable additional"
                  + " match characters, words and features.");

    OptUtil.createBooleanOption(metaEquals, true);
    OptUtil.setupOptionDesc(Category.SEARCH, metaEquals, "'reMetaEquals' 'req'",
            "In a regular expression allow"
            + " '=', in addition to '?', to indicate an optional atom.");
    setExpertHidden(metaEquals, true, false);

    OptUtil.createStringOption(metaEscape, G.metaEscapeDefault,
            new Validator<String>() {
            @Override
              public void validate(String val) throws PropertyVetoException {
		for(int i = 0; i < val.length(); i++) {
		  if(G.metaEscapeAll.indexOf(val.charAt(i)) < 0) {
                    reportPropertyVetoException(
                             "Only characters from '" + G.metaEscapeAll
                             + "' are RE metacharacters."
                             + " Not '" + val.substring(i,i+1) + "'.",
                             val);
		  }
		}
              }
            });
    OptUtil.setupOptionDesc(Category.SEARCH, metaEscape, "'reMetaEscape' 'rem'",
            "Regular expression metacharacters requiring escape;"
            + " Any of: '(', ')', '|', '+', '?', '{'."
            + " By default vim requires escape, '\\', for these characters."
            + "\njVi only.");
    setExpertHidden(metaEscape, true, false);

    OptUtil.createStringOption(isKeyWord, "@,48-57,_,192-255",
            new Validator<String>() {
            @Override
              public void validate(String val) throws PropertyVetoException {
                CharTab ct = new CharTab();
                if(!ct.init(val)) {
                  reportPropertyVetoException("parse of '" + val + "' failed.",
                                              val);
                }
              }
            });
    OptUtil.setupOptionDesc(Category.SEARCH, isKeyWord, "'iskeyword' 'isk'",
              "Keywords are used in searching and recognizing with many commands:"
            + " \"w\", \"*\", etc. See vim docs for more info."
            + " The \":set iskeyword=xxx\" command is per buffer"
            + " and this works with modelines.");

    /////////////////////////////////////////////////////////////////////
    //
    //
    // Vi cursor wrap options
    //
    //
    OptUtil.createBooleanOption(backspaceWrapPrevious, true);
    OptUtil.setupOptionDesc(Category.CURSOR_WRAP, backspaceWrapPrevious,
               "'whichwrap' 'ww'  b - <BS>",
               "<backspace> wraps to previous line");

    OptUtil.createBooleanOption(hWrapPrevious, false);
    OptUtil.setupOptionDesc(Category.CURSOR_WRAP, hWrapPrevious,
               "'whichwrap' 'ww'  h - \"h\"",
               "\"h\" wraps to previous line (not recommended, see vim doc)");

    OptUtil.createBooleanOption(leftWrapPrevious, false);
    OptUtil.setupOptionDesc(Category.CURSOR_WRAP, leftWrapPrevious,
               "'whichwrap' 'ww'  < - <Left>",
               "<left> wraps to previous line");

    OptUtil.createBooleanOption(spaceWrapNext, true);
    OptUtil.setupOptionDesc(Category.CURSOR_WRAP, spaceWrapNext,
               "'whichwrap' 'ww'  s - <Space>",
               "<space> wraps to next line");

    OptUtil.createBooleanOption(lWrapNext, false);
    OptUtil.setupOptionDesc(Category.CURSOR_WRAP, lWrapNext,
               "'whichwrap' 'ww'  l - \"l\"",
               "\"l\" wraps to next line (not recommended, see vim doc)");

    OptUtil.createBooleanOption(rightWrapNext, false);
    OptUtil.setupOptionDesc(Category.CURSOR_WRAP, rightWrapNext,
               "'whichwrap' 'ww'  > - <Right>",
               "<right> wraps to next line");

    OptUtil.createBooleanOption(tildeWrapNext, false);
    OptUtil.setupOptionDesc(Category.CURSOR_WRAP, tildeWrapNext,
               "'whichwrap' 'ww'  ~ - \"~\"",
               "\"~\" wraps to next line");

    OptUtil.createBooleanOption(insertLeftWrapPrevious, false);
    OptUtil.setupOptionDesc(Category.CURSOR_WRAP, insertLeftWrapPrevious,
               "'whichwrap' 'ww'  [ - <Left>",
               "in Insert Mode <Left> wraps to previous line");

    OptUtil.createBooleanOption(insertRightWrapNext, false);
    OptUtil.setupOptionDesc(Category.CURSOR_WRAP, insertRightWrapNext,
               "'whichwrap' 'ww'  ] - <Right>",
               "in Insert Mode <Right> wraps to next line");

    /////////////////////////////////////////////////////////////////////
    //
    //
    // Vi external process options
    //
    //
    boolean inWindows = ViManager.getOsVersion().isWindows();
    String defaultShell = System.getenv("SHELL");
    String defaultXQuote = "";
    String defaultFlag;

    if (defaultShell == null) {
      if (inWindows)
        defaultShell = "cmd.exe";
      else
        defaultShell = "sh";
    }

    if (defaultShell.contains("sh")) {
      defaultFlag = "-c";
      if (inWindows)
        defaultXQuote = "\"";
    }
    else
      defaultFlag = "/c";

    OptUtil.createStringOption(shell, defaultShell);
    OptUtil.setupOptionDesc(Category.PROCESS, shell, "'shell' 'sh'",
            "Name of shell to use for ! and :! commands.  (default $SHELL " +
            "or \"sh\", MS-DOS and Win32: \"command.com\" or \"cmd.exe\").  " +
            "When changing also check 'shellcmndflag'.");

    OptUtil.createStringOption(shellCmdFlag, defaultFlag);
    OptUtil.setupOptionDesc(Category.PROCESS, shellCmdFlag, "'shellcmdflag' 'shcf'",
            "Flag passed to shell to execute \"!\" and \":!\" commands; " +
            "e.g., \"bash.exe -c ls\" or \"command.com /c dir\" (default: " +
            "\"-c\", MS-DOS and Win32, when 'shell' does not contain \"sh\" " +
            "somewhere: \"/c\").");

    OptUtil.createStringOption(shellXQuote, defaultXQuote);
    OptUtil.setupOptionDesc(Category.PROCESS, shellXQuote, "'shellxquote' 'sxq'",
            "Quoting character(s), put around the commands passed to the " +
            "shell, for the \"!\" and \":!\" commands (default: \"\"; for " +
            "Win32, when 'shell' contains \"sh\" somewhere: \"\\\"\").");

    OptUtil.createBooleanOption(shellSlash, false);
    OptUtil.setupOptionDesc(Category.PROCESS, shellSlash, "'shellslash' 'ssl'",
            "When set, a forward slash is used when expanding file names." +
            "This is useful when a Unix-like shell is used instead of " +
            "command.com or cmd.exe.");

    OptUtil.createStringOption(equalProgram, "");
    OptUtil.setupOptionDesc(Category.PROCESS, equalProgram, "'equalprg' 'ep'",
            "External program to use for \"=\" command (default \"\").  " +
            "When this option is empty the internal formatting functions " +
            "are used.");

    OptUtil.createStringOption(formatProgram, "");
    OptUtil.setupOptionDesc(Category.PROCESS, formatProgram, "'formatprg' 'fp'",
            "External program to use for \"qq\" or \"Q\" command (default \"\")."
          + " When this option is empty the internal formatting functions"
          + " are used."
          + "\n\n When specified, the program must take input on stdin and"
          + " send output to stdout. In Unix, \"fmt\" is such a program."
          +  twMagic + " in the string is"
          + " substituted by the value of textwidth option. "
          + "\n\nTypically set to \"fmt -w #TEXT-WIDTH#\" to use external program."
            );

    /////////////////////////////////////////////////////////////////////
    //
    OptUtil.createBooleanOption(readOnlyHack, true);
    OptUtil.setupOptionDesc(Category.DEBUG, readOnlyHack, "enable read only hack",
            "A Java implementation issue, restricts the characters that jVi"
            + " recieves for a read only file. Enabling this, changes the file"
            + " editor mode to read/write so that the file can be viewed"
            + " using the Normal Mode vi commands.");
    setExpertHidden(readOnlyHack, true, true);

    OptUtil.verifyVimOptions();
    if(ViManager.isDebugAtHome())
      OptUtil.checkAllForSetCommand();
  }

  /**
   * Get the named option.
   * @param name option name
   * @return option
   */
  public static Option getOption(String name) {
    return OptUtil.getOption(name);
  }

  public static DebugOption getDebugOption(String name) {
    return (DebugOption)OptUtil.getOption(name);
  }

  /**
   * Get the option names in the specified option category
   * @param category of options
   * @return an immutable list of option names
   */
  public static List<String> getOptionList(Category category) {
    return OptUtil.getOptionList(category);
  }

  public static void setExpertHidden(String optionName,
                                      boolean fExpert, boolean fHidden) {
    OptUtil.setExpertHidden(optionName, fExpert, fHidden);
  }

  //
  // Look like a good bean
  // But they're static!
  //

  public static void addPropertyChangeListener( PropertyChangeListener listener )
  {
    pcs.addPropertyChangeListener( listener );
  }

  public static void removePropertyChangeListener( PropertyChangeListener listener )
  {
    pcs.removePropertyChangeListener( listener );
  }

  public static void addPropertyChangeListener(String p,
                                               PropertyChangeListener l)
  {
    pcs.addPropertyChangeListener(p, l);
  }

  public static void removePropertyChangeListener(String p,
                                                  PropertyChangeListener l)
  {
    pcs.removePropertyChangeListener(p, l);
  }

  private static Pattern mlPat1;
  private static Pattern mlPat2;
  public static void processModelines() {
    if(mlPat1 == null) {
      // vim modelines has two patterns
      mlPat1 = Pattern.compile("\\s+(?:vi:|vim:|ex:)\\s*(.*)");
      mlPat2 = Pattern.compile("\\s+(?:vi:|vim:|ex:)\\s*set? ([^:]*):");
    }
    int mls;
    if(!G.p_ml || (mls = G.p_mls) == 0)
      return;
    int lnum;
    int lcount = G.curbuf.getLineCount();
    for(lnum = 1; lnum < lcount && lnum <= mls; lnum++) {
      if(checkModeline(lnum))
        mls = 0;
    }

    for(lnum = lcount; lnum > 0 && lnum > mls && lnum > lcount - mls; lnum--) {
      if(checkModeline(lnum))
        mls = 0;
    }
  }

  /** @return true if parsed a modeline, there may have been errors */
  private static boolean checkModeline(int lnum) {
    // use MySegment for jdk1.5 compatibility
    MySegment seg = Util.ml_get(lnum);

    // must check pattern 2 first, since lines that match pattern 2
    // will also match pattern 1

    if(parseModeline(mlPat2, seg, lnum))
      return true;
    return parseModeline(mlPat1, seg, lnum);
  }

  /** @return true if found and parsed a modeline, there may have been errors */
  private static boolean parseModeline(Pattern p, CharSequence cs, int lnum) {
    Matcher m = p.matcher(cs);
    if(!m.find())
      return false;
    boolean parseError = false;
    String mline = m.group(1);
    StringBuilder sb = new StringBuilder();
    try {
      String[] args = mline.split("[:\\s]");
      for (String arg : args) {
        String msg = "";
        try {
          SetColonCommand.parseSetOption(arg);
        } catch (SetColonCommand.SetCommandException ex) {
          msg = ex.getMessage();
        } catch (IllegalAccessException ex) {
          LOG.log(Level.SEVERE, null, ex);
        }
        if(sb.length() != 0)
          sb.append('\n');
        if(!msg.isEmpty()) {
          sb.append("Error: ").append(msg);
          parseError = true;
        } else
          sb.append("   OK: ").append(arg);
      }
    } finally {
      String fn = G.curbuf.getDisplayFileName();
      ViOutputStream vos = ViManager.createOutputStream(G.curwin,
                    ViOutputStream.OUTPUT,
                    "In " + fn + ":" + lnum + " process modeline: " + mline,
                    parseError ? ViOutputStream.PRI_HIGH
                               : ViOutputStream.PRI_LOW);
      vos.println(sb.toString());
      vos.close();
    }
    return true;
  }

  //////////////////////////////////////////////////////////////////////
  //
  // can_bs is in option in vim
  //

  static boolean can_bs(char what) {
    switch(G.p_bs) {
      case 2:     return true;
      case 1:     return what != BS_START;
      case 0:     return false;
    }
    assert(false) : "can_bs: ; p_bs bad value";
    return false;
  }


  //////////////////////////////////////////////////////////////////////
  //
  // Whether or not to highlight depends on a mix of things.
  // Handle the logic here.

  static boolean nohDisableHighlight;

  static {
    addPropertyChangeListener(highlightSearch, new PropertyChangeListener() {
      @Override
      public void propertyChange(PropertyChangeEvent evt) {
        if(G.curwin != null) {
          nohDisableHighlight = false;
          ViManager.updateHighlightSearchState();
        }
      }
    });

    addPropertyChangeListener(ignoreCase, new PropertyChangeListener() {
      @Override
      public void propertyChange(PropertyChangeEvent evt) {
        if(G.curwin != null) {
          ViManager.updateHighlightSearchState();
        }
      }
    });
  }

  public static boolean doHighlightSearch() {
    return G.p_hls && !nohDisableHighlight;
  }

  static void nohCommand() {
    nohDisableHighlight = true;
    ViManager.updateHighlightSearchState();
  }

  static void newSearch() {
    nohDisableHighlight = false;
    ViManager.updateHighlightSearchState();
    Normal.v_updateVisualState();
  }

  //////////////////////////////////////////////////////////////////////
  //
  // This option is used a lot, make it fast
  //

  static DebugOption kd;
  public static DebugOption kd() {
    return kd;
  }
}

// vi:sw=2 et
