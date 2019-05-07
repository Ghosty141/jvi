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
 * Copyright (C) 2000-2010 Ernie Rael.  All Rights Reserved.
 *
 * Contributor(s): Ernie Rael <err@raelity.com>
 */
package com.raelity.jvi.options;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.raelity.jvi.core.Options;

/**
 *
 * @author Ernie Rael <err at raelity.com>
 */
public final class VimOption {
    final String fullName; // option name
    final String shortName; // option name
    // name of field and/or option
    final String varName; // java variable name in G, curbuf or curwin
    final String optName; // the jVi Option name.
    final S scope;
    final Set<F> flags;

    public static VimOption get(String optionName)
    {
        return mapOptionName.get(optionName);
    }

    static VimOption lookupVarName(String varName)
    {
        return mapVarName.get(varName);
    }

    static VimOption lookupUserName(String userName)
    {
        VimOption vopt = mapUserName.get(userName);
        return vopt == null || vopt.isHidden() ? null : vopt;
    }

    static List<VimOption> getAll()
    {
        return Collections.unmodifiableList(Arrays.asList(vopts));
    }

    static List<VimOption> getAllUser()
    {
        List<VimOption> l = new ArrayList<VimOption>(vopts.length);
        for(VimOption vopt : vopts) {
            if(vopt.f(F.HIDE) || Options.getOption(vopt.getOptName()).isHidden())
                continue;
            l.add(vopt);
        }
        return l;
    }

    /**
     * NOTE: varName is constructed from shortName and scope;
     *       fullName is used if no shortName.
     */
    private VimOption(String fullName, String shortName,
                      String optName, S scope, Set<F> flags)
    {
        this.fullName = fullName;
        this.shortName = shortName;
        this.optName = optName;
        this.scope = scope;
        this.flags = flags;
        StringBuilder sb = new StringBuilder();
        // construct varName
        if(flags.contains(F.VERBATIM)) {
            sb.append(fullName);
        } else {
            if(isWin())
                sb.append("w_");
            else if(isBuf())
                sb.append("b_");
            sb.append("p_");
            sb.append(shortName.isEmpty() ? fullName : shortName);
        }
        this.varName = sb.toString();
    }

    boolean f(F f)
    {
        return flags.contains(f);
    }

    public String getFullName()
    {
        return fullName;
    }

    public String getShortName()
    {
        return shortName;
    }

    public String getOptName()
    {
        return optName;
    }

    public String getVarName()
    {
        return varName;
    }

    public boolean isGlobal()
    {
        return scope.isGlobal();
    }

    public boolean isLocal()
    {
        return scope.isLocal();
    }

    public boolean isWin()
    {
        return scope.isWin();
    }

    public boolean isBuf()
    {
        return scope.isBuf();
    }

    public boolean isHidden()
    {
        return f(F.HIDE) || Options.getOption(getOptName()).isHidden();
    }

    // Scope of the option
    private enum S {
        P_GBL, // a global option
        P_WIN, // a per window option
        P_BUF; // a per buffer option

        boolean isLocal() {
            return this == P_WIN || this == P_BUF;
        }

        boolean isGlobal() {
            return this == P_GBL;
        }

        boolean isWin() {
            return this == P_WIN;
        }

        boolean isBuf() {
            return this == P_BUF;
        }
    }

    // option flags
    enum F {
        COMMA,          // comma separated list
        NODUP,          // don't allow duplicate strings
        FLAGLIST,       // list of single-char flags
        HIDE,           // don't allow set command
        VERBATIM,       // use fullName verbatim as varName
    }

    private static final Set<F> nullF = EnumSet.noneOf(F.class);


final private static VimOption[] vopts = new VimOption[]{
new VimOption("auditoryBell","ab",   Options.auditoryBell,    S.P_GBL, nullF),
new VimOption("backspace",   "bs",   Options.backspace,       S.P_GBL, nullF),
new VimOption("clipboard",   "cb",   Options.unnamedClipboard,S.P_GBL, nullF),
new VimOption("cpo_j",       "",     Options.endOfSentence,   S.P_GBL, EnumSet.of(F.HIDE)),
new VimOption("cpo_search",  "",     Options.searchFromEnd,   S.P_GBL, EnumSet.of(F.HIDE)),
new VimOption("cpo_w",       "",     Options.changeWordBlanks,S.P_GBL, EnumSet.of(F.HIDE)),
new VimOption("equalalways", "ea",   Options.equalAlways,     S.P_GBL, nullF),
new VimOption("equalprg",    "ep",   Options.equalProgram,    S.P_GBL, nullF),
new VimOption("expandtab",   "et",   Options.expandTabs,      S.P_BUF, nullF),
new VimOption("foldopen",    "fdo",  Options.foldOpen,        S.P_GBL, EnumSet.of(F.COMMA, F.NODUP)),
new VimOption("formatprg",   "fp",   Options.formatProgram,   S.P_GBL, nullF),
new VimOption("hlsearch",    "hls",  Options.highlightSearch, S.P_GBL, nullF),
new VimOption("ignorecase",  "ic",   Options.ignoreCase,      S.P_GBL, nullF),
new VimOption("incsearch",   "is",   Options.incrSearch,      S.P_GBL, nullF),
new VimOption("iskeyword",   "isk",  Options.isKeyWord,       S.P_BUF, EnumSet.of(F.COMMA, F.NODUP)),
new VimOption("joinspaces",  "js",   Options.joinSpaces,      S.P_GBL, nullF),
new VimOption("linebreak",   "lbr",  Options.lineBreak,       S.P_WIN, nullF),
new VimOption("list",        "",     Options.list,            S.P_WIN, nullF),
new VimOption("modeline",    "ml",   Options.modeline,        S.P_GBL, nullF),
new VimOption("modelines",   "mls",  Options.modelines,       S.P_GBL, nullF),
new VimOption("number",      "nu",   Options.number,          S.P_WIN, nullF),
new VimOption("nrformats",   "nf",   Options.nrFormats,       S.P_BUF, EnumSet.of(F.COMMA, F.NODUP)),
new VimOption("platformbrace","pbm", Options.platformBraceMatch,S.P_GBL,EnumSet.of(F.HIDE)),
new VimOption("remetaescape","rem",  Options.metaEscape,      S.P_GBL, EnumSet.of(F.FLAGLIST)),
new VimOption("remetaequals","req",  Options.metaEquals,      S.P_GBL, EnumSet.of(F.HIDE)),
new VimOption("report",      "",     Options.report,          S.P_GBL, nullF),
new VimOption("rocursorcolor","rocc",Options.roCursorColor,   S.P_GBL, nullF),
new VimOption("scroll",      "scr",  Options.scroll,          S.P_WIN, nullF),
new VimOption("scrolloff",   "so",   Options.scrollOff,       S.P_GBL, nullF),
new VimOption("selection",   "sel",  Options.selection,       S.P_GBL, nullF),
new VimOption("selectmode",  "slm",  Options.selectMode,      S.P_GBL, nullF),
new VimOption("shell",       "sh",   Options.shell,           S.P_GBL, nullF),
new VimOption("shellcmdflag","shcf", Options.shellCmdFlag,    S.P_GBL, nullF),
new VimOption("shellslash",  "ssl",  Options.shellSlash,      S.P_GBL, nullF),
new VimOption("shellxquote", "sxq",  Options.shellXQuote,     S.P_GBL, nullF),
new VimOption("shiftround",  "sr",   Options.shiftRound,      S.P_GBL, nullF),
new VimOption("shiftwidth",  "sw",   Options.shiftWidth,      S.P_BUF, nullF),
new VimOption("showcmd",     "sc",   Options.showCommand,     S.P_GBL, nullF),
new VimOption("showmode",    "smd",  Options.showMode,        S.P_GBL, nullF),
new VimOption("sidescroll",  "ss",   Options.sideScroll,      S.P_GBL, nullF),
new VimOption("sidescrolloff","siso",Options.sideScrollOff,   S.P_GBL, nullF),
new VimOption("smartcase",   "scs",  Options.smartCase,       S.P_GBL, nullF),
new VimOption("softtabstop", "sts",  Options.softTabStop,     S.P_BUF, nullF),
new VimOption("splitbelow",  "sb",   Options.splitBelow,      S.P_GBL, nullF),
new VimOption("splitright",  "spr",  Options.splitRight,      S.P_GBL, nullF),
new VimOption("startofline", "notsol",Options.notStartOfLine, S.P_GBL, nullF),
new VimOption("tabstop",     "ts",   Options.tabStop,         S.P_BUF, nullF),
new VimOption("textwidth",   "tw",   Options.textWidth,       S.P_BUF, nullF),
new VimOption("tildeop",     "top",  Options.tildeOperator,   S.P_GBL, nullF),
new VimOption("timeout",     "to",   Options.timeout,         S.P_GBL, nullF),
new VimOption("timeoutlen",  "tm",   Options.timeoutlen,      S.P_GBL, nullF),
new VimOption("visualbell",  "vb",   Options.visualBell,      S.P_GBL, nullF),
new VimOption("visualbellcolor","vbc",Options.visualBellColor,S.P_GBL, nullF),
new VimOption("visualbelltime","vbt",Options.visualBellTime,  S.P_GBL, nullF),
new VimOption("wrap",        "",     Options.wrap,            S.P_WIN, nullF),
new VimOption("wrapscan",    "ws",   Options.wrapScan,        S.P_GBL, nullF),
new VimOption("ww_bs",       "",     Options.backspaceWrapPrevious,S.P_GBL, EnumSet.of(F.HIDE)),
new VimOption("ww_h",        "",     Options.hWrapPrevious,   S.P_GBL, EnumSet.of(F.HIDE)),
new VimOption("ww_i_left",   "",     Options.insertLeftWrapPrevious,S.P_GBL, EnumSet.of(F.HIDE)),
new VimOption("ww_i_right",  "",     Options.insertRightWrapNext,S.P_GBL, EnumSet.of(F.HIDE)),
new VimOption("ww_l",        "",     Options.lWrapNext,       S.P_GBL, EnumSet.of(F.HIDE)),
new VimOption("ww_larrow",   "",     Options.leftWrapPrevious,S.P_GBL, EnumSet.of(F.HIDE)),
new VimOption("ww_rarrow",   "",     Options.rightWrapNext,   S.P_GBL, EnumSet.of(F.HIDE)),
new VimOption("ww_sp",       "",     Options.spaceWrapNext,   S.P_GBL, EnumSet.of(F.HIDE)),
new VimOption("ww_tilde",    "",     Options.tildeWrapNext,   S.P_GBL, EnumSet.of(F.HIDE)),

new VimOption("disableFontError","", Options.disableFontError, S.P_GBL, EnumSet.of(F.HIDE, F.VERBATIM)),
new VimOption("disableFontCheckSpecial","", Options.disableFontCheckSpecial, S.P_GBL, EnumSet.of(F.HIDE, F.VERBATIM)),
new VimOption("isCursorXorBug","",   Options.cursorXorBug,     S.P_GBL, EnumSet.of(F.HIDE, F.VERBATIM)),
new VimOption("isHideVersion","",    Options.hideVersionOption,S.P_GBL, EnumSet.of(F.HIDE, F.VERBATIM)),
new VimOption("isCoordSkip", "",     Options.coordSkip,        S.P_GBL, EnumSet.of(F.HIDE, F.VERBATIM)),
new VimOption("pcmarkTrack", "",     Options.pcmarkTrack,      S.P_GBL, EnumSet.of(F.HIDE, F.VERBATIM)),
new VimOption("readOnlyHack","",     Options.readOnlyHack,     S.P_GBL, EnumSet.of(F.HIDE, F.VERBATIM)),
new VimOption("redoTrack",   "",     Options.redoTrack,        S.P_GBL, EnumSet.of(F.HIDE, F.VERBATIM)),
new VimOption("useFrame",    "",     Options.commandEntryFrame,S.P_GBL, EnumSet.of(F.HIDE, F.VERBATIM)),
new VimOption("viminfoMaxBuf","",    Options.persistedBufMarks,S.P_GBL, EnumSet.of(F.HIDE, F.VERBATIM)),
};


    private static int capacity(int c) { return (int)(c / .75) + 3; }
    final private static Map<String, VimOption> mapOptionName
            = new HashMap<String, VimOption>(capacity(vopts.length));
    final private static Map<String, VimOption> mapVarName
            = new HashMap<String, VimOption>(capacity(vopts.length));

    final private static Map<String, VimOption> mapUserName
            = new HashMap<String, VimOption>(capacity(2 * vopts.length));
    static {
        for(VimOption vopt : vopts) {
            assert !mapOptionName.containsKey(vopt.getOptName());
            mapOptionName.put(vopt.getOptName(), vopt);
            assert !mapVarName.containsKey(vopt.getVarName());
            mapVarName.put(vopt.getVarName(), vopt);

            assert !mapUserName.containsKey(vopt.getFullName());
            mapUserName.put(vopt.getFullName(), vopt);
            if(!vopt.getShortName().isEmpty()) {
                assert !mapUserName.containsKey(vopt.getShortName());
                mapUserName.put(vopt.getShortName(), vopt);
            }
        }
    }
}
