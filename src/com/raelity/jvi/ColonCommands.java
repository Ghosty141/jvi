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

import java.awt.EventQueue;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.text.CharacterIterator;
import java.util.List;
import java.util.ArrayList;
import java.util.TooManyListenersException;
import java.util.StringTokenizer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.swing.AbstractAction;
import javax.swing.Action;
import com.raelity.jvi.ViTextView.TAGOP;

import static com.raelity.jvi.Constants.*;
import static com.raelity.jvi.ColonCommandFlags.*;

import java.io.*;
import java.lang.Thread.UncaughtExceptionHandler;

/**
 * This class handles registration, command input, parsing, dispatching
 * and in some instances execution of ":" commands. Some internal vi
 * commands, e.g. set, are executed here. Colon commands are added to
 * the list of avaialable commands through the
 * {@link #register} method.
 * <p>
 * A command is represented by an {@link java.awt.event.ActionListener}.
 * A {@link javax.swing.Action} could be used. Only commands that subclass
 * {@link ColonCommands.ColonAction} can have arguments. These commands
 * are passed a {@link ColonCommands.ColonEvent}. Actions that subclass
 * {@link javax.swing.Action} are not invoked if they are disabled. The source
 * of the {@link java.awt.event.ActionEvent}
 * is a {@link javax.swing.JEditorPane}.
 */
public class ColonCommands {
  private static AbbrevLookup commands = new AbbrevLookup();
  
  private static String lastCommand;
  private static String lastBangCommand = null;

  public ColonCommands() {
  }

  private static ViCmdEntry colonCommandEntry;
  private static ViCmdEntry getColonCommandEntry() {
    if(colonCommandEntry == null) {
      try {
        colonCommandEntry = ViManager.getViFactory()
                              .createCmdEntry(ViCmdEntry.COLON_ENTRY);
        colonCommandEntry.addActionListener(
          new ActionListener() {
            public void actionPerformed(ActionEvent ev) {
              colonEntryComplete(ev);
            }});
      }
      catch (TooManyListenersException ex) {
        ex.printStackTrace();
        throw new RuntimeException();
      }
    }
    return colonCommandEntry;
  }

  static private void colonEntryComplete(ActionEvent ev) {
    ViManager.stopCommandEntry();
    String commandLine = colonCommandEntry.getCommand();
    String cmd = ev.getActionCommand();
    // if not <CR> must be an escape, just ignore it
    if(cmd.charAt(0) == '\n') {
      if( ! commandLine.equals("")) {
	lastCommand = commandLine;
	executeCommand(parseCommand(commandLine));
      }
    }
    closePrint();
  }

  static void doColonCommand(StringBuffer range) {
    ViManager.startCommandEntry(getColonCommandEntry(),
                                ":", G.curwin,
                                range);
  }
  
  static void executeCommand(ColonEvent cev) {
    if(cev != null) {
      ((ActionListener)cev.commandElement.getValue()).actionPerformed(cev);
    }
  }

  /**
   * Parse (partially) the command.
   * Return null if parse failure or otherwise can't execute,
   * otherwise return the colonEvent for the command.
   *
   * Following is the stock vim comment
   * <pre>
   * Execute one Ex command.
   *
   * If 'sourcing' is TRUE, the command will be included in the error message.
   *
   * 2. skip comment lines and leading space
   * 3. parse range
   * 4. parse command
   * 5. parse arguments
   * 6. switch on command name
   *
   * Note: "getline" can be NULL.
   *
   * This function may be called recursively!
   * </pre>
   */
  static ColonEvent parseCommand(String commandLine) {
    int sidx = 0; // index into string
    int sidx01;
    int sidx02;
    // Use the TextView as source
    ColonEvent cev = new ColonEvent(G.curwin);
    boolean bang = false;
    MutableInt lnum = new MutableInt(0);
    boolean skip = false; // NEEDSWORK: executCommmand how else is this set

    //
    // 3. parse a range specifier of the form: addr [,addr] [;addr] ..
    //
    // where 'addr' is:
    //
    // %	      (entire file)
    // $  [+-NUM]
    // 'x [+-NUM] (where x denotes a currently defined mark)
    // .  [+-NUM]
    // [+-NUM]..
    // NUM
    //
    // The ea.cmd pointer is updated to point to the first character following
    // the range spec. If an initial address is found, but no second, the upper
    // bound is equal to the lower.
    //
    // cev.line1, cev.line2 are set to the range values

    // NEEDSWORK: parse a range!
    // while(parsing line number) {
    //   if(range number) {
    //     cev.line2 = range number
    //     ++cev.addr_count;
    //   }
    // }
    
    // repeat for all ',' or ';' separated addresses
    for (;;)
    {
	cev.line1 = cev.line2;
        // default is current line number
	cev.line2 = G.curwin.getWCursor().getLine();
        sidx = Misc.skipwhite(commandLine, sidx);
	sidx = get_address(commandLine, sidx, skip, lnum);
	if (sidx < 0)		    // error detected
	    return null; // NEEDSWORK: goto doend;
	// if(lnum.getValue() == 0) {
	//   lnum.setValue(1);	// NEEDSWORK: is this right?
	// }
	if (lnum.getValue() == MAXLNUM)
	{
	    if (commandLine.charAt(sidx) == '%')   // '%' - all lines
	    {
                ++sidx;
		cev.line1 = 1;
                cev.line2 = G.curwin.getLineCount();
		++cev.addr_count;
	    }
            /* ************************************************************
            NOT SURE WHAT THIS ADDRESS IS
					    // '*' - visual area
	    else if (*ea.cmd == '*' && vim_strchr(p_cpo, CPO_STAR) == NULL)
	    {
		FPOS	    *fp;

		++ea.cmd;
		if (!ea.skip)
		{
		    fp = getmark('<', FALSE);
		    if (check_mark(fp) == FAIL)
			goto doend;
		    ea.line1 = fp->lnum;
		    fp = getmark('>', FALSE);
		    if (check_mark(fp) == FAIL)
			goto doend;
		    ea.line2 = fp->lnum;
		    ++ea.addr_count;
		}
	    }
            ************************************************************/
	}
	else
	    cev.line2 = lnum.getValue();
	cev.addr_count++;

        /* *****************************************************************
	if (*ea.cmd == ';')
	{
	    if (!ea.skip)
		curwin->w_cursor.lnum = ea.line2;
	} else
        ********************************************************************/
        if (sidx >= commandLine.length() || commandLine.charAt(sidx) != ',')
	    break;
	++sidx;
    }
    
    // If one address given: set start and end lines
    if (cev.addr_count == 1) {
	cev.line1 = cev.line2;
	    // ... but only implicit: really no address given
	if (lnum.getValue() == MAXLNUM)
	    cev.addr_count = 0;
    }

    // Find the command

    sidx = Misc.skipwhite(commandLine, sidx);
    if(sidx >= commandLine.length()) {
      // no command, but if a number was entered then goto that line
      if(cev.getAddrCount() > 0) {
	int l = cev.getLine1();
	if(l == 0) {
	  l = 1;
	}
        Misc.gotoLine(l, BL_SOL | BL_FIX);
        return null;
      }
    }
    
    // if(sidx >= commandLine.length()) { return null; }
    sidx01 = sidx;
    // skip alpha characters
    for(; sidx < commandLine.length(); sidx++) {
      if( ! Util.isalpha(commandLine.charAt(sidx))) {
        break;
      }
    }
    sidx02 = sidx;
    if(sidx < commandLine.length() && commandLine.charAt(sidx) == '!') {
      if (sidx > sidx01) bang = true;
      else ++sidx02;
      ++sidx;
    }
    sidx = Misc.skipwhite(commandLine, sidx);

    String command = commandLine.substring(sidx01, sidx02);
    AbbrevLookup.CommandElement ce = commands.lookupCommand(command);
    if(ce == null) {
      Msg.emsg("Not an editor command: " + command);
      return null;
    }

    //
    // Invoke the command
    //

    if(ce.getValue() instanceof Action) {
      if( ! ((Action)ce.getValue()).isEnabled()) {
        Msg.emsg(ce.getName() + " is not enabled");
        return null;
      }
    }
    if( ! (ce.getValue() instanceof ColonAction)) {
      // no arguments allowed
      if(sidx < commandLine.length()) {
        Msg.emsg(Messages.e_trailing);
        return null;
      }
      if(cev.getAddrCount() > 0) {
        Msg.emsg(Messages.e_norange);
        return null;
      }
    }
    if(bang
        && ( ! (ce.getValue() instanceof ColonAction)
             || (((ColonAction)ce.getValue()).getFlags() & BANG) == 0)) {
      Msg.emsg("No ! allowed");
      return null;
    }
    cev.command = ce.getName();
    cev.bang = bang;
             
    if(sidx < commandLine.length()) {
      cev.args = new ArrayList();
      if((((ColonAction)ce.getValue()).getFlags() & NOPARSE) != 0) {
        // put the line (without command name) as the argument
        cev.args.add(commandLine.substring(sidx));
      } else {
        // parse the command into tokens
        StringTokenizer toks = new StringTokenizer(commandLine.substring(sidx));
        while(toks.hasMoreTokens()) {
          cev.args.add(toks.nextToken());
        }
      }
    }

    cev.commandElement = ce;
    return cev;
    // ((ActionListener)ce.getValue()).actionPerformed(cev);
  }
  
  /**
   * get a single EX address
   *
   * Set ptr to the next character after the part that was interpreted.
   * Set ptr to NULL when an error is encountered.
   *
   * Return MAXLNUM when no Ex address was found.
   * 
   * @param skip only skip the address, don't use it
   * @return the index of next character after the part that was interpreted
   * else minus one if there was an error;
   */
  private static int get_address(String s, int sidx, boolean skip,
                                 MutableInt lnum) {
    // note that the return sidx is set to minus one if there is an error
    
    sidx = Misc.skipwhite(s, sidx);
    lnum.setValue(MAXLNUM);
    do {
      char c = s.charAt(sidx);
      switch(c) {
        case '.':         // '.' - Cursor position
          ++sidx;
          lnum.setValue(G.curwin.getWCursor().getLine());
          break;
        case '$':         // '$' - last line
          ++sidx;
          lnum.setValue(G.curwin.getLineCount());
          break;
        case '\'':        // ''' - mark
          ++sidx;
          c = s.charAt(sidx);
          if(sidx >= s.length()) {
            return -1;
          }
          if(skip) {
            ++sidx;
          } else {
            ViMark fp = (ViMark)MarkOps.getmark(c, false);
            ++sidx;
            if(MarkOps.check_mark(fp) == FAIL) {
              return -1;
            }
            lnum.setValue(fp.getLine());
          }
          break;
        
        /* ******************************
        // NEEDSWORK: get_address: These are big cases....
        case '/':
        case '?':         // '/' or '?' - search
        
        case '\\':	  // "\?", "\/" or "\&", repeat search
        *******************************/
        default:
          if(Util.isdigit(c)) {
            sidx = Misc.getdigits(s, sidx, lnum);
          }
      }

      int i = 0;
      int n = 0;
      for (;;)
      {
        sidx = Misc.skipwhite(s, sidx);
        if(sidx >= s.length()) {
          break;
        }
        c = s.charAt(sidx);
        if (c != '-' && c != '+' && !Util.isdigit(c))
          break;

        if (lnum.getValue() == MAXLNUM) {
          // "+1" is same as ".+1"
          lnum.setValue(G.curwin.getWCursor().getLine());
        }
        if (Util.isdigit(c)) {
          i = '+';		// "number" is same as "+number"
        } else {
          i = c;
          ++sidx;
          c = s.charAt(sidx);
        }
        
        if (!Util.isdigit(c))	// '+' is '+1', but '+0' is not '+1'
          n = 1;
        else {
          MutableInt mi = new MutableInt(0);
          sidx = Misc.getdigits(s, sidx, mi);
          n = mi.getValue();
        }
          
        if (i == '-')
          lnum.setValue(lnum.getValue() - n);
        else
          lnum.setValue(lnum.getValue() + n);
      }
    } while(false); //NEEDSWORK:  while (*cmd == '/' || *cmd == '?');
    
    return sidx;
  }

  /**
   * This method returns a read-only list of the registered commands.
   * The elements of the list are {@link AbbrevLookup.CommandElement}.
   */
  static public List getCommandList() {
    return commands.getList();
  }

  /**
   * Register a command; the abbrev must be unique.  The abbrev is the "key"
   * for the command, for example since "s" is the abbreviation for "substitue"
   * and "se" is the abbreviation for "set" then "substitute" sorts earlier
   * than "set" because "s" sorts earlier than "se". Consider this when
   * adding commands, since unique prefix has nothing to do with how commands
   * are recognized.
   * @exception IllegalArgumentException this is thrown if the abbreviation
   * and/or the name already exist in the list or there's a null argument.
   */
  static public void register(String abbrev, String name, ActionListener l) {
    commands.add(abbrev, name, l);
  }

  /**
   * Commands that take arguments subclass this. Some treatment of the
   * command arguments can be controled through the {@link ColonAction#getFlags}
   * method.
   */

  static abstract public class ColonAction extends AbstractAction
                                           implements ColonCommandFlags
  {
    /**
     * Specify some of the commands argument handling. This default
     * implementation returns zero.
     * @see ColonCommandFlags
     */
    public int getFlags() {
      return 0;
    }
  }

  /**
   * The event passed to {@link ColonCommands.ColonAction}. It is used
   * to pass argument information. The arguments to the command are
   * white space separated. The command word finishes with the first
   * non-alpha character. So "e#" and "e #" are parsed the same.
   */
  static public class ColonEvent extends ActionEvent {
    // NEEDSWORK: ColonEvent Add stuff for getting at command arguments
    /** The expanded command word */
    String command;
    /** The command associated with this event */
    AbbrevLookup.CommandElement commandElement;
    /** indicates that the command word has a trailing "!" */
    boolean bang;
    /** command line as entered */
    String commandLine;
    /** the command arguments */
    List args;
    /** the text view for this event */
    ViTextView viTextView;
    /** the first line number */
    int line1;
    /** the second line number or count */
    int line2;
    /** the number of addresses given */
    int addr_count;

    ColonEvent(ViTextView c) {
      super(c.getEditorComponent(), ActionEvent.ACTION_PERFORMED, "");
      viTextView = c;
    }

    /**
     * @return the first line number
     */
    public int getLine1() {
      return line1;
    }

    /**
     * @return the second line number or count
     */
    public int getLine2() {
      return line2;
    }
    
    /**
     * @return the number of addresses given
     */
    public int getAddrCount() {
      return addr_count;
    }
    
    public ActionListener getAction() {
      return (ActionListener)commandElement.getValue();
    }

    /**
     * @return true if the command has a "!" appended.
     */
    public boolean isBang() {
      return bang;
    }

    /**
     * @return the textView for this command
     */
    public ViTextView getViTextView() {
      return viTextView;
    }

    /**
     * @return the number of arguments, not including command name.
     */
    public int getNArg() {
      if(args == null) {
        return 0;
      }
      return args.size();
    }

    /**
     * Fetch an argument.
     * @return the nth argument, n == 0 is the expanded command name.
     */
    public String getArg(int n) {
      if(n == 0) {
        return command;
      }
      return (String)args.get(n-1);
    }
    
    /**
     * Fetch the list of command arguments.
     */
    public List<String> getArgs() {
        return args;
    }

    /**
     * Fetch the command line, including commmand name
     */
    public String XXXgetCommandLine() {
      return commandLine;
    }
  }

  public static class BangAction extends ColonAction {
    FilterThreadCoordinator coord = null;
    BooleanOption dbg = (BooleanOption)Options.getOption(Options.dbgBang); 
    String origCommandString;

    public static String getShell() {
      return "sh";
    }

    public static String getShellcmdflags() {
      return "-c";
    }

    public void actionPerformed(ActionEvent ev) {
      if(coord != null) {
        Msg.emsg("Only one command at a time.");
        return;
      }
      ColonEvent evt = (ColonEvent)ev;
      int nArgs = evt.getNArg();
      List<String> commandLineCopy = new ArrayList<String>();
      boolean isFilter = (evt.getAddrCount() > 0);

      try {
        if (dbg.value)
          System.err.println("!: Getting command line arguments");
        if (nArgs > 0)
          commandLineCopy.addAll(evt.getArgs());
        if (dbg.value)
          System.err.println("!: Converting original command line and saving" +
            " it as a string");
        origCommandString = commandLineToString(commandLineCopy);
        if (dbg.value)
          System.err.println("!: Original command line string is '" +
            origCommandString + "'");

        for (int i = 1; i <= nArgs; i++) {
          StringBuilder arg = new StringBuilder(commandLineCopy.get(i - 1));
          if (dbg.value)
            System.err.println("!: Parsing bangs in argument #" + i + ": '" +
              arg + "'");
          if (parseBang(arg) == null) {
            Msg.emsg("No previous command");
            return;
          }
          if (dbg.value)
            System.err.println("!: Parsing backslashes in argument #" + i +
              ": '" + arg + "'");
          parseBackslash(arg);
          if (dbg.value)
            System.err.println("!: Replacing command line argument #" + i +
              ": '" + commandLineCopy.get(i - 1) + "' with '" + arg + "'");
          commandLineCopy.set(i - 1, arg.toString());
        }

        if (nArgs >= 1) {
          if (dbg.value)
            System.err.println("!: Converting parsed command line to single" +
              " string");
          String commandLineString = commandLineToString(commandLineCopy);
          if (dbg.value)
            System.err.println("!: Parsed command line string is '" +
              commandLineString + "'");
          if (dbg.value)
            System.err.println("!: Executing parsed command line:");
          
          doBangCommand(evt, commandLineString);
          if(coord == null) {
            if (dbg.value)
              System.err.println("!: Event thread experienced error " +
                "executing command.");
            return;
          }

          // NEEDSWORK: put this where it can be repeated
          coord.statusMessage = "Enter 'Ctrl-C' to ABORT";
          Msg.smsg(coord.statusMessage);
          
          ViManager.getViFactory().startModalKeyCatch(new KeyAdapter() {
            public void keyTyped(KeyEvent e) {
              e.consume();
              if(e.getKeyChar() == (KeyEvent.VK_C & 0x1f)
                 && (e.getModifiers() & KeyEvent.CTRL_MASK) != 0) {
                finishBangCommand(false);
              } else 
                Util.vim_beep();
            }
          });

          if(true)
            return;
      
      
        }
        else {
          lastBangCommand = new String(""); 
          if (dbg.value)
            System.err.println("!: Last bang command is saved as '" +
              lastBangCommand + "'");
        }
      }
      finally {
      }
    }
    
    private void joinThread(Thread t) {
      if(t.isAlive()) {
        t.interrupt();
        try {
          t.join(50);
        } catch (InterruptedException ex) {
        }
        if(t.isAlive()) {
          System.err.println("Thread " + t.getName() + " won't die");
        }
      }
    }
    
    void finishBangCommand(boolean fOK) {
      assert(EventQueue.isDispatchThread());
      if(coord == null)
        return;
      System.err.println("!: DONE :!" + (!fOK ? " ABORT" : ""));
      ViManager.getViFactory().stopModal();
      Msg.clearMsg();
      
      if(!fOK) {
          // NEEDSWORK: set write to process thread to not bother cleaning up
          // set flag in coord so we get a rollback undo
        try {
          int exit = coord.process.exitValue();
        } catch(IllegalThreadStateException ex) {
          if(dbg.value)
            System.err.println("!: destroying process");
          coord.process.destroy();
        }
        if(coord.simpleExecuteThread != null) {
          coord.simpleExecuteThread.interrupt();
        } else {
          coord.documentThread.interrupt();
          coord.readFromProcessThread.interrupt();
          coord.writeToProcessThread.interrupt();
        }
      }
      
      if(coord.simpleExecuteThread != null) {
        joinThread(coord.simpleExecuteThread);
      } else {
        joinThread(coord.documentThread);
        joinThread(coord.readFromProcessThread);
        joinThread(coord.writeToProcessThread);
      }
      
      /* THESE CALLS WILL PROBABLY DEADLOCK.
       * IN ONE EXAMPLE, WE ARE IN A STUCK IN BUFFERED READER
       * READ, CALLING CANCEL ATTEMPTS TO CLOSE THE READER (FROM A 
       * DIFFERENT THREAD NOLESS) THE CLOSE HANGS.
       *
       * WE'VE DONE LOTS TO TRY AND KILL THE THREAD CLEANLY, 
       * IF IT WONT DIE, THEN TOO BAD. 
        // These calls to cleanup don't make much sense,
        // we're in the wrong thread context.
        // But then again, they should be no-ops
        if(coord.simpleExecuteThread != null) {
          coord.simpleExecuteThread.cleanup();
        } else {
          coord.documentThread.cleanup();
          coord.readFromProcessThread.cleanup();
          coord.writeToProcessThread.cleanup();
        }
      */
      
      coord = null;
      if(!fOK)
        return;
      
      
      //
      // Following taken from actionPerformed
      //
      if (dbg.value)
        System.err.println("!: Done executing parsed command line");
      if (dbg.value)
        System.err.println("!: Parsing bangs in original command line again" +
          " to save as last bang command");
      origCommandString =
          parseBang(new StringBuilder(origCommandString)).toString();
      lastBangCommand = origCommandString;
      if (dbg.value)
        System.err.println("!: Last bang command is saved as '" +
          lastBangCommand + "'");
      
    }

    private StringBuilder parseBang(StringBuilder sb) {
      int index = sb.indexOf("!");

      for ( ; index >= 0; index = sb.indexOf("!", index)) {
        if (index == 0 || sb.charAt(index - 1) != '\\') {
          if (lastBangCommand != null) {
            sb.replace(index, index + 1, lastBangCommand);
            index += (lastBangCommand.length() - 1);
          } else {
            return null;
          }
        }
        index++;
      }
      return sb;
    }

    private void parseBackslash(StringBuilder sb) {
      int index = sb.indexOf("\\");

      for ( ; index >= 0; index = sb.indexOf("\\", index)) {
        sb.deleteCharAt(index);
        int argLength = sb.length();
        while (index < argLength && sb.charAt(index) == '\\')
            index++;
      }
    }

    private String commandLineToString(List<String> cl) {
      int nArgs = cl.size();
      StringBuilder result = new StringBuilder();

      if (nArgs > 0)
        result.append(cl.get(0));

      for (int i = 1; i < nArgs; i++) {
        result.append(' ' + cl.get(i));
      }

      return result.toString();
    }

    private boolean doBangCommand(ColonEvent evt, String commandLine) {
      BooleanOption dbg = (BooleanOption)Options.getOption(Options.dbgBang); 
      boolean isFilter = (evt.getAddrCount() > 0);

      if (dbg.value)
        System.err.println("!: Constructing ArrayList for ProcessBuilder");

      ArrayList<String> shellCommandLine = new ArrayList<String>(3);
      shellCommandLine.add(getShell());
      shellCommandLine.add(getShellcmdflags());
      shellCommandLine.add(commandLine);

      if (dbg.value)
        System.err.println("!: Creating ProcessBuilder class with ArrayList '" +
          shellCommandLine + "'");

      if (dbg.value)
        System.err.println("!: Redirecting ProcessBuilder Process error " +
          "stream to stdout");

      ProcessBuilder pb = new ProcessBuilder(shellCommandLine);
      pb.redirectErrorStream(true);

      if (dbg.value)
        System.err.println("!: Starting ProcessBuilder Process");
      Process p;
      try {
        p = pb.start();
      } catch (IOException ex) {
        String s = ex.getMessage();
        Msg.emsg(s != null  || s.equals("")? s : "exec failed");
        return false;
      }

      if (isFilter) {
        outputFromProcessToFile(evt, p); 
      }
      else {
        outputToViOutputStream(evt, p);
      }
      return true;
    }

    private void outputToViOutputStream(ColonEvent evt,
                                        Process p) {
      OPARG oa = setupExop(evt);

      coord = new FilterThreadCoordinator(this, oa, p);
      
      BufferedReader br;
      ViOutputStream vos;
      br = new BufferedReader(new InputStreamReader(p.getInputStream()));
      vos = ViManager.createOutputStream(null, ViOutputStream.OUTPUT, null);
      
      coord.simpleExecuteThread = new SimpleExecuteThread(coord, vos, br);
      
      coord.simpleExecuteThread.start();
    }

    private void outputFromProcessToFile(ColonEvent evt,
                                         Process p) {
      OPARG oa = setupExop(evt);

      coord = new FilterThreadCoordinator(this, oa, p);
      
      // NEEDSWORK: set up a thread group???
      
      BufferedReader br;
      BufferedWriter bw;
      br = new BufferedReader(new InputStreamReader(p.getInputStream()));
      bw = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));

      coord.writeToProcessThread = new ProcessWriterThread(coord, bw);
      coord.readFromProcessThread = new ProcessReaderThread(coord, br);
      coord.documentThread = new DocumentThread(coord, evt.getViTextView());

      coord.documentThread.start();
      coord.writeToProcessThread.start();
      coord.readFromProcessThread.start();
    }
  }
  
  /** This is used for both filtered bang commands and to execute a command.
   * There should really be two coordinator, one for each, but I'm feeling
   * lazy right now. Any of the Thread variables, eg simpleExecuteThread, can
   * be used to test for how this is being used.
   */
  static private class FilterThreadCoordinator {
    int startLine;
    int lastLine;
    
    BlockingQueue<String> fromDoc;
    BlockingQueue<String> toDoc;
    
    DocumentThread documentThread;
    ProcessWriterThread writeToProcessThread;
    ProcessReaderThread readFromProcessThread;
    SimpleExecuteThread simpleExecuteThread;
    
    OPARG oa;
    Process process;
    final BangAction ba;
    
    String statusMessage = "";
    
    public FilterThreadCoordinator(BangAction ba, OPARG oa, Process p) {
      this.oa = oa;
      this.process = p;
      this.ba = ba;
      
      startLine = oa.start.getLine();
      lastLine = oa.end.getLine();
      
     fromDoc = new ArrayBlockingQueue<String>(50);
     toDoc = new ArrayBlockingQueue<String>(50);
    }     
    
    void finish(final boolean fOK) {
      if(EventQueue.isDispatchThread())
        ba.finishBangCommand(fOK);
      else
        EventQueue.invokeLater(new Runnable() {
          public void run() {
            ba.finishBangCommand(fOK);
          }
        });
    }
    
    public void dumpState() {
      System.err.println("startLine " + startLine
                         + ", lastLine = " + lastLine );
    }
  }
  
  static private class SimpleExecuteThread extends FilterThread {
    ViOutputStream vos;
    BufferedReader reader;
    boolean didCleanup;
    
    SimpleExecuteThread(FilterThreadCoordinator coord,
                        ViOutputStream vos,
                        BufferedReader reader) {
      super(SIMPLE_EXECUTE, coord);
      this.vos = vos;
      this.reader = reader;
    }
      
    public void run() {
      super.run();
      coord.finish(true);
    }
    
    public void dumpState() {
      super.dumpState();
    }
    
    void cleanup() {
      if(didCleanup)
        return;
      didCleanup = true;
      if (dbg.value) {
        dumpState();
      }
      if(vos != null) {
        vos.close();
        vos = null;
      }
      if(reader != null) {
        try {
          reader.close();
        } catch (IOException ex) {
          ex.printStackTrace();
        }
        reader = null;
      }
    }
    
    void doTask() {
      String line;
      try {
        while(!isProblem()) {
          line = reader.readLine();
          if(line == null)
            break;
          if (dbg.value)
            System.err.println("!: Writing '" + line + "' to ViOutputStream");
          vos.println(line);
        }
        reader.close();
        reader = null;
        vos.close();
        vos = null;
      } catch (IOException ex) {
        ex.printStackTrace();
        exception = ex;
      }
    }
  }
  
  static private class ProcessWriterThread extends FilterThread {
    BufferedWriter writer;
    int currWriterLine;
    boolean wroteFirstLineToProcess;
    boolean reachedEndOfLines;
    boolean didCleanup;
    
    ProcessWriterThread(FilterThreadCoordinator coord,
                        BufferedWriter writer) {
      super(WRITE_PROCESS, coord);
      this.writer = writer;
    }
    
    public void dumpState() {
      System.err.println("currWriterLine " + currWriterLine
                         + ", wroteFirstLineToProcess " + wroteFirstLineToProcess
                         + ", reachedEndOfLines " + reachedEndOfLines );
      super.dumpState();
    }
    
    void cleanup() {
      if(didCleanup)
        return;
      didCleanup = true;
      if (dbg.value) {
        dumpState();
      }
      if(writer != null) {
        try {
          writer.close();
        } catch (IOException ex) {
          ex.printStackTrace();
        }
        writer = null;
      }
    }
    
    void doTask() {
      writeToProcess();
    }

    public void writeToProcess() {
      currWriterLine = coord.startLine;
      String data = null;
      try {
        while(!isProblem()) {
            data = coord.fromDoc.take();
            if(DONE.equals(data))
              break;
            writer.write(data);
            if (dbg.value) // NEEDSWORK: why trim(), use Text.formDebugDString
              System.err.println("!: Writer #" + currWriterLine + ": '"
                                + data.trim() + "'");
            currWriterLine++;
            wroteFirstLineToProcess = true;
        }
        writer.close();
        writer = null;
      } catch (InterruptedException ex) {
        exception = ex;
        ex.printStackTrace();
      } catch (IOException ex) {
        exception = ex;
        ex.printStackTrace();
      }
      if (dbg.value)
        System.err.println("!: Wrote all lines needed to process");
      if(!isProblem())
        reachedEndOfLines = true;
    }
  }
  
  static private class ProcessReaderThread extends FilterThread {
    BufferedReader reader;
    int currReaderLine;
    boolean wroteFirstLineToFile;
    boolean reachedEndOfProcessOutput;
    boolean didCleanup;
    
    ProcessReaderThread(FilterThreadCoordinator coord,
                        BufferedReader reader) {
      super(READ_PROCESS, coord);
      this.reader = reader;
    }
    public void dumpState() {
      System.err.println("currReaderLine " + currReaderLine
                         + ", wroteFirstLineToFile " + wroteFirstLineToFile
                         + ", reachedEndOfProcessOutput "
                         + reachedEndOfProcessOutput );
      super.dumpState();
    }
    
    void cleanup() {
      if(didCleanup)
        return;
      didCleanup = true;
      if (dbg.value) {
        dumpState();
      }
      if(reader != null) {
        try {
          reader.close();
        } catch (IOException ex) {
          ex.printStackTrace();
        }
        reader = null;
      }
    }
    
    void doTask() {
      readFromProcess();
    }

    private void readFromProcess() {
      currReaderLine = coord.startLine;
      String data = null;
      try {
        while(!isProblem()) {
          data = reader.readLine();
          if (data == null) {
            if (wroteFirstLineToFile && !isInterrupted())
              reachedEndOfProcessOutput = true;
            if (dbg.value)
              System.err.println("!: end of process read data");
            break;
          }
          if (dbg.value) // NEEDSWORK: why trim(), use Text.formDebugString
            System.err.println("!: Reader #" + currReaderLine + ": '"
                              + data.trim() + "'");
          
          coord.toDoc.put(data);
        }
        coord.toDoc.put(DONE);
        reader.close();
        reader = null;
      } catch (InterruptedException ex) {
        exception = ex;
        ex.printStackTrace();
      } catch (IOException ex) {
        exception = ex;
        ex.printStackTrace();
      }
    }
  }
  
  static private class DocumentThread extends FilterThread {
    int docReadLine;
    boolean docReadDone;
    
    int docWriteLine;
    boolean docWriteDone;
    
    int linesDelta;
    ViTextView tv;
    
    protected boolean didCleanup;
    
    DocumentThread(FilterThreadCoordinator coord, ViTextView tv) {
      super(RW_DOC, coord);
      this.tv = tv;
    }
      
    public void run() {
      super.run();
      coord.finish(true);
    }
    
    void doTask() {
      doFilterDocument();
    }
    
    private void doFilterDocument() {
      
      Misc.beginUndo();
      
      try {
        
        readWriteDocument();
        
      } catch(Throwable t) {
        exception = t;
      } finally {
        if(exception != null || error) {
          cleanup();
          //NEEDSWORK: rollback change, DO NOT DO END UNDO
          //Misc.forgetUndo();
          Misc.endUndo();
        } else {
          if (dbg.value)
            System.err.println("!: Ending Undo recording for command ");
          Misc.endUndo();
        }
      
      }
    }
    
    private void deleteLine(int line) {
      int startOffset = tv.getLineStartOffset(line);
      int endOffset = tv.getLineEndOffset(line);
      int docLength = tv.getEditorComponent().getDocument().getLength();
      if(endOffset > docLength)
        endOffset = docLength;
      tv.deleteChar(startOffset, endOffset);
    }
    
    public void readWriteDocument() {
      docReadLine = coord.startLine;
      docWriteLine = coord.startLine;
      
      do {
        try {
          String data = null;
          if(docReadLine <= coord.lastLine) {
            if (dbg.value)
              System.err.println("!: rwDoc: try read doc");
            while(!isInterrupted() && docReadLine <= coord.lastLine) {
              int docLine = docReadLine + linesDelta;
              data = tv.getLineSegment(docLine).toString();
              deleteLine(docLine);
              if(!coord.fromDoc.offer(data, 50, TimeUnit.MILLISECONDS))
                break;
              if (dbg.value) // NEEDSWORK: why trim(), use Text.formDebugDString
                System.err.println("!: fromDoc #" + docReadLine + "," + docLine
                                   + ": '" + data.trim() + "'");
              docReadLine++;
              linesDelta--;
            }
          } else {
            if(!docReadDone
               && coord.fromDoc.offer(DONE, 50, TimeUnit.MILLISECONDS)) {
              docReadDone = true;
              if (dbg.value)
                System.err.println("!: rwDoc: docReadDONE");
            }
          }
          
          if(!docWriteDone) {
            if (dbg.value)
              System.err.println("!: rwDoc: try write doc");
            while(!isInterrupted()) {
              data = coord.toDoc.poll(50, TimeUnit.MILLISECONDS);
              if(data == null)
                break;
              if(DONE.equals(data)) {
                docWriteDone = true;
                if (dbg.value)
                  System.err.println("!: rwDoc: docWriteDONE");
                break;
              }
              int offset = tv.getLineStartOffset(docWriteLine);
              tv.insertText(offset, data);
              offset += data.length();
              tv.insertText(offset, "\n");
              if (dbg.value) // NEEDSWORK: why trim(), use Text.formDebugDString
                System.err.println("!: toDoc #" + docWriteLine + ": '"
                                  + data.trim() + "'");
              docWriteLine++;
              linesDelta++;
            }
          }
        } catch (InterruptedException ex) {
          exception = ex;
          ex.printStackTrace();
        }
      } while(!isProblem() && !docWriteDone);
      if(!docReadDone)
        cleanup(); // like "10000!!date"
    }
    
    public void dumpState() {
      System.err.println("docReadDone " + docReadDone
                          + ", docReadLine " + docReadLine
                          + ", docWriteDone " + docWriteDone
                          + ", docWriteLine " + docWriteLine
                          + ", linesDelta " + linesDelta);
      super.dumpState();
    }
    
    void cleanup() {
      if(didCleanup)
        return;
      didCleanup = true;
      if (dbg.value) {
        dumpState();
      }
      
      if(docReadDone && docReadLine <= coord.lastLine)
        throw new IllegalStateException();
      if (dbg.value)
        System.err.println("!: checking doc CLEANUP");
      while(docReadLine <= coord.lastLine) {
        int docLine = docReadLine + linesDelta;
        deleteLine(docLine);
        if (dbg.value)
          System.err.println("!: CLEANUP: fromDoc #"
                              + docReadLine + "," + docLine);
        docReadLine++;
        linesDelta--;
      }
    }
  }

  /** Base class for Bang command threads.
   * <p>
   * The thread that signals completion
   * of the task should override run() and signal success if there was no
   * problem. If an problem occurs, failure will be signaled automatically,
   * see coord.finish()
   */
  static private abstract class FilterThread extends Thread {
    public static final String READ_PROCESS = "FilterReadProcess";
    public static final String WRITE_PROCESS = "FilterWriteProcess";
    public static final String RW_DOC = "FilterDocument";
    public static final String SIMPLE_EXECUTE = "SimpleCommandExecute";
    
    public static final String DONE
            = new String(new char[] {CharacterIterator.DONE});
    
    protected FilterThreadCoordinator coord;
    
    protected Throwable uncaughtException;
    protected Throwable exception;
    protected boolean error;
    protected boolean interrupted;
    
    protected BooleanOption dbg
                    = (BooleanOption)Options.getOption(Options.dbgBang); 

    public FilterThread(String filterType,
                        FilterThreadCoordinator coord) {
      super(filterType);
      this.coord = coord;
    }
    
    protected void dumpState() {
      if(exception != null)
        System.err.println("exception: " + exception.getMessage());
      if(uncaughtException != null)
        System.err.println("uncaughtException: " + uncaughtException.getMessage());
      if(error)
        System.err.println("error: " + error);
      if(interrupted)
        System.err.println("interrupted: " + interrupted);
      coord.dumpState();
    }
    
    /** Its possible that cleanup is called multiple times */
    abstract void cleanup();
    
    abstract void doTask();
    
    boolean isProblem() {
      return isInterrupted() || error || exception != null;
    }

    public void run() {
      setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
        public void uncaughtException(Thread t, Throwable e) {
          FilterThread thisThread = (FilterThread) t;
          if(thisThread.uncaughtException != null)
            return;
          thisThread.uncaughtException = e;
          e.printStackTrace();
          cleanup();
        }
      });
      
      doTask();
      
      if(exception != null) {
        if (dbg.value) {
          System.err.println("!: Exception in " + getName());
          exception.printStackTrace();
        }
        cleanup();
      }
      
      if(error) {
        if (dbg.value)
          System.err.println("!: error in " + getName());
        cleanup();
      }
      
      if(isInterrupted()) {
        interrupted = true;
        if (dbg.value)
          System.err.println("!: cleanup in " + getName());
        cleanup();
      }
      
      if(isProblem()) {
        coord.finish(false);
      }
    }
  }

  static ActionListener ACTION_quit = new ActionListener() {
    public void actionPerformed(ActionEvent ev) {
      ColonEvent cev = (ColonEvent)ev;
      cev.getViTextView().win_quit();
    }};

  static ActionListener ACTION_close = new ActionListener() {
    public void actionPerformed(ActionEvent ev) {
      ColonEvent cev = (ColonEvent)ev;
      // NEEDSWORK: win_close: hidden, need_hide....
      cev.getViTextView().win_close(false);
    }};

  static ActionListener ACTION_only = new ActionListener() {
    public void actionPerformed(ActionEvent ev) {
      ColonEvent cev = (ColonEvent)ev;
      // NEEDSWORK: win_close_others: forceit....
      cev.getViTextView().win_close_others(false);
    }};

  static ActionListener ACTION_wall = new ActionListener() {
    public void actionPerformed(ActionEvent ev) {
      ViManager.getFS().writeAll(false);
    }};

  static ActionListener ACTION_file = new ActionListener() {
    public void actionPerformed(ActionEvent ev) {
      ColonEvent cev = (ColonEvent)ev;
      cev.getViTextView().displayFileInfo();
    }};

  /**
   * Write command.
   * <br>
   * If there is an argument, it must be '*'. ':w *' should invoke
   * a save as dialog.
   */
  static ColonAction ACTION_write = new ColonAction() {
    public int getFlags() {
      return BANG;
    }

    public void actionPerformed(ActionEvent ev) {
      ColonEvent cev = (ColonEvent)ev;
      if(cev.getNArg() == 0) {
        ViManager.getFS().write(cev.getViTextView(), cev.isBang());
      } else if(cev.getNArg() == 1 && cev.getArg(1).equals("*")) {
        ViManager.getFS().write(cev.getViTextView(), null, cev.isBang());
      } else {
        Msg.emsg("Only single argument '*' allowed");
      }
    }
  };
  
  static ColonAction ACTION_wq = new ColonAction() {
    public int getFlags() {
      // any "!" is ignored
      return BANG;
    }

    public void actionPerformed(ActionEvent ev) {
      ColonEvent cev = (ColonEvent)ev;
      
      if(cev.getNArg() == 0) {
        ViManager.getFS().write(cev.getViTextView(), cev.isBang());
        cev.getViTextView().win_close(false);
      } else
        Msg.emsg(Messages.e_trailing);
    }
  };

  /**
   * Edit command. Only ':e#[number]' is supported.
   */
  static ColonAction ACTION_edit = new ColonAction() {
    public int getFlags() {
      return BANG;
    }

    public void actionPerformed(ActionEvent ev) {
      ColonEvent cev = (ColonEvent)ev;
      String arg;
      boolean error = false;
      /*
      if(cev.getNArg() == 0) {
        ViManager.getFS().write(cev.getViTextView(), cev.isBang());
      } else
      */
      if(cev.getNArg() == 1 && cev.getArg(1).charAt(0) == '#') {
        int i = -1;
        arg = cev.getArg(1);
        if(arg.length() > 1) {
          arg = arg.substring(1);
          try {
            i = Integer.parseInt(arg);
          } catch(NumberFormatException ex) {
            error = true;
          }
        }
        if( ! error) {
          ViManager.getFS().edit(cev.getViTextView(), i, cev.isBang());
        }
      } else {
        error = true;
      }

      if(error) {
        Msg.emsg("Only 'e#[<number>]' allowed");
      }
    }
  };
  
  static ColonAction ACTION_substitute = new ColonAction() {
    public int getFlags() {
      return NOPARSE;
    }
    
    public void actionPerformed(ActionEvent ev) {
      Misc.beginUndo();
      try {
        Search.substitute((ColonEvent)ev);
      }
      finally {
        Misc.endUndo();
      }
      
    }
  };
  
  static ColonAction ACTION_global = new ColonAction() {
    public int getFlags() {
      return NOPARSE;
    }
    
    public void actionPerformed(ActionEvent ev) {
      Misc.beginUndo();
      try {
        Search.global((ColonEvent)ev);
      }
      finally {
        Misc.endUndo();
      }
    }
  };
  
  /* This is the default buffers,files,ls command. The platform specific code
   * may chose to register this, or implement their own, possibly using
   * popup gui components.
   */
    public static ActionListener ACTION_BUFFERS = new ActionListener() {
        public void actionPerformed(ActionEvent e) {
            ViOutputStream osa = ViManager.createOutputStream(
                null, ViOutputStream.OUTPUT,
                "=== MRU (:n :N :e#-<digit>) ===                "
                + "=== activation (:e#<digit> ===");
            int i = 0;
            Object cur = ViManager.relativeMruBuffer(0);
            Object prev = ViManager.getMruBuffer(1);
            
            List<String> l = new ArrayList<String>();
            while(true) {
                Object o1 = ViManager.getMruBuffer(i);
                Object o2 = ViManager.getTextBuffer(i+1);
                if(o1 == null && o2 == null)
                    break;
                StringBuilder s = new StringBuilder();
                String name1 = "";
                String name2 = "";
                if(o1 != null)
                    name1 = ViManager.getViFactory().getDisplayFilename(o1);
                if(o2 != null)
                    name2 = ViManager.getViFactory().getDisplayFilename(o2);
                l.add(String.format(" %2d %c %-40s %3d %c %s",
                                    i,
                                    cur == o1 ? '%' : prev == o1 ? '#' : ' ',
                                    name1,
                                    i+1,
                                    cur == o2 ? '%' : prev == o2 ? '#' : ' ',
                                    name2));
                i++;
            }
            // print in reverse order, MRU visible if scrolling
            for(int i01 = l.size() -1; i01 >= 0; i01--) {
                osa.println(l.get(i01));
            }
            osa.close();
        }
    };
  
  /**
   * :print command. If not busy global then output to "print" stream.
   * For now, its just a no-op so the word "print" can be found.
   */
  static ColonAction ACTION_print = new ColonAction() {
    public void actionPerformed(ActionEvent ev) {
    }
  };
  
  /**
   * :tag command.
   */
  static ColonAction ACTION_tag = new ColonAction() {
    public void actionPerformed(ActionEvent ev) {
        ColonEvent evt = (ColonEvent)ev;
        if(evt.getNArg() > 1) {
            Msg.emsg(Messages.e_trailing);
            return;
        }
        if(evt.getNArg() == 1) {
            ViManager.getViFactory().tagDialog(evt);
        } else {
          int count = 1;
          if(evt.getAddrCount() == 1)
              count = evt.getLine2();
          ViManager.getViFactory().tagStack(TAGOP.NEWER, count);
        }
    }
  };
  
  /**
   * :pop command.
   */
  static ColonAction ACTION_pop = new ColonAction() {
    public void actionPerformed(ActionEvent ev) {
        ColonEvent evt = (ColonEvent)ev;
        if(evt.getNArg() > 0) {
            Msg.emsg(Messages.e_trailing);
            return;
        }
        int count = 1;
        if(evt.getAddrCount() == 1)
            count = evt.getLine2();
        ViManager.getViFactory().tagStack(TAGOP.OLDER, count);
    }
  };
  
  static ColonAction ACTION_tselect = new ColonAction() {
    public void actionPerformed(ActionEvent ev) {
        ColonEvent ce = (ColonEvent)ev;
        if(ce.getNArg() > 1) {
            Msg.emsg(Messages.e_trailing);
            return;
        }
        int count = 1;
        if(ce.getAddrCount() == 1)
            count = ce.getLine2();
        ViManager.getViFactory().tagDialog(ce);
    }
  };

  static ActionListener ACTION_tags = new ActionListener() {
    public void actionPerformed(ActionEvent ev) {
      ViManager.getViFactory().displayTags();
    }
  };

  static ActionListener ACTION_nohlsearch = new ActionListener() {
    public void actionPerformed(ActionEvent ev) {
      Options.nohCommand();
    }
  };

  static ColonAction ACTION_bang = new BangAction();

  /**
   * This is used for several of the colon commands to translate arguments
   * into OPARG.
   */
   
  static OPARG setupExop(ColonEvent cev) {
    OPARG oa = new OPARG();
    // oa.regname = ; NEEDSWORK:
    oa.start = new FPOS();
    oa.start.setPosition(cev.getLine1(), 0);
    oa.end = new FPOS();
    oa.end.setPosition(cev.getLine2(), 0);
    oa.line_count = cev.getLine2() - cev.getLine1() + 1;
    oa.motion_type = MLINE;
    if(cev.getAction() != ACTION_yank) {
      MarkOps.setpcmark();
      G.curwin.getWindow().setWCursor(oa.start);
      Edit.beginline(BL_SOL|BL_FIX);
    }
    return oa;
  }
  
  /**
   * :delete command.
   */
  static ColonAction ACTION_delete = new ColonAction() {
    public void actionPerformed(ActionEvent ev) {
      ColonEvent cev = (ColonEvent)ev;
      OPARG oa = setupExop(cev);
      oa.op_type = OP_DELETE;
      if(cev.getNArg() == 1) {
	try {
	  oa.line_count = Integer.parseInt(cev.getArg(1));
	}
	catch (Exception ex) {
	  Msg.emsg(Messages.e_trailing);
	  return;
	}
      } else if(cev.getNArg() > 1) {
	Msg.emsg(Messages.e_trailing);
	return;
      }
      
      Misc.op_delete(oa);
    }
  };
  
  /**
   * :yank command.
   */
  static ColonAction ACTION_yank = new ColonAction() {
    public void actionPerformed(ActionEvent ev) {
      OPARG oa = setupExop((ColonEvent)ev);
      oa.op_type = OP_YANK;
    }
  };
  
  static ActionListener ACTION_testKeys = new ActionListener() {
    public void actionPerformed(ActionEvent ev) {
      ViManager.getViFactory().startModalKeyCatch(new KeyAdapter() {
        public void keyPressed(KeyEvent e) {
          e.consume();
          if(e.getKeyCode() == KeyEvent.VK_Y) {
            ViManager.getViFactory().stopModal();
            Msg.clearMsg();
          } else 
            Util.vim_beep();
        }
      });
      Msg.smsg("Enter 'y' to proceed");
    }
  };

  static void registerBuiltinCommands() {
    register("clo", "close", ACTION_close);
    register("on", "only", ACTION_only);

    register("q", "quit", ACTION_quit);
    register("w", "write", ACTION_write);
    register("wq", "wq", ACTION_wq);
    register("wa", "wall", ACTION_wall);

    register("f", "file", ACTION_file);
    register("e", "edit", ACTION_edit);
    register("s", "substitute", ACTION_substitute);
    register("g", "global", ACTION_global);
    register("d", "delete", ACTION_delete);
    register("p", "print", ACTION_print);

    register("se", "set", new Options.SetCommand());

    register("ta", "tag", ACTION_tag);
    register("tags", "tags", ACTION_tags);
    register("ts", "tselect", ACTION_tselect);
    register("po", "pop", ACTION_pop);
    
    register("noh", "nohlsearch", ACTION_nohlsearch);
    
    register("testKeys", "testKeys", ACTION_testKeys);
    
    ColonCommands.register("jviDump", "jviDump", new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        ViManager.dump(System.err);
      }
    });
    
    // register("y", "yank", ACTION_yank);
    
    // register("n", "next", ACTION_next);
    // register("N", "Next", ACTION_Next);

    register("!", "!", ACTION_bang);
  }

  static {
    registerBuiltinCommands();
  }
  
  //
  // handle the print stream
  //
  
  /**
   * The current print stream (text window) is automatically created if there
   * is no current one.
   *
   * It is expected that it will be closed at the end of
   * the current command.
   */
  private static ViOutputStream printStream;
  
  private static void makePrintStream() {
    if(printStream != null) {
      return;
    }
    printStream = ViManager.createOutputStream(G.curwin, ViOutputStream.LINES,
                                               lastCommand);
  }
   
  /**
   * Output a string to the current text window.
   */
  static void outputPrint(String s) {
    makePrintStream();
    printStream.println(s);
  }
  
  /**
   * Output the specified document line to the current text window.
   */
  static void outputPrint(int line, int offset, int length) {
    makePrintStream();
    printStream.println(line, offset, length);
  }
  
  /**
   * Close the current print output stream.
   */
  static void closePrint() {
    if(printStream == null) {
      return;
    }
    printStream.close();
    printStream = null;
  }
}
