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

package com.raelity.jvi.core;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.CharacterIterator;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.Timer;

import org.openide.util.lookup.ServiceProvider;

import com.raelity.jvi.ViInitialization;
import com.raelity.jvi.ViOutputStream;
import com.raelity.jvi.ViTextView;
import com.raelity.jvi.core.ColonCommands.AbstractColonAction;
import com.raelity.jvi.core.ColonCommands.ColonAction;
import com.raelity.jvi.core.ColonCommands.ColonEvent;
import com.raelity.jvi.core.lib.CcFlag;
import com.raelity.jvi.manager.ViManager;
import com.raelity.jvi.options.DebugOption;

/**
 *
 * @author Ernie Rael <err at raelity.com>
 */
public class CcBang
{
private static final Logger LOG = Logger.getLogger(CcBang.class.getName());

    public static class Init implements ViInitialization
    {
        @Override
      public void init()
      {
        CcBang.init();
      }
    }

    private static void init()
    {
        ColonCommands.register("!", "!", ACTION_bang, null);
    }

    static String lastBangCommand = null;

    private static ColonAction ACTION_bang = new BangAction();

    private CcBang()
    {
    }

public static class BangAction extends AbstractColonAction
{
    FilterThreadCoordinator coord = null;
    DebugOption dbg = (DebugOption)Options.getOption(Options.dbgBang);

        @Override
        public EnumSet<CcFlag> getFlags()
        {
            return EnumSet.of(CcFlag.RANGE);
        }

        @Override
    public void actionPerformed(ActionEvent ev)
    {
        if(coord != null) {
            Msg.emsg("Only one command at a time.");
            return;
        }
        ColonEvent evt = (ColonEvent)ev;
        int nArgs = evt.getNArg();
        boolean isFilter = (evt.getAddrCount() > 0);

        if (dbg.getBoolean()) {
                dbg.println("!: Original command: '" + evt.getArgString() + "'");
        }
        StringBuilder arg = new StringBuilder(evt.getArgString());
        arg = parseBang(arg);
        if (arg == null) {
            Msg.emsg("No previous command");
            return;
        }
        if (dbg.getBoolean()) {
                dbg.println("!: Substitution '" + arg + "'");
        }

        if (nArgs >= 1) {
            String cmd = arg.toString();
            doBangCommand(evt, cmd);
            if(coord == null) {
                return;
            }
            lastBangCommand = cmd;
        } else {
            lastBangCommand = "";
        }
        if (dbg.getBoolean()) {
                dbg.println("!: Last command saved '" + lastBangCommand + "'");
        }
    }

    private void joinThread(Thread t)
    {
        if(dbg.getBoolean()) {
                dbg.println("!: joining thread " + t.getName());
        }
        if(t.isAlive()) {
            t.interrupt();
            try {
                t.join(50);
            } catch (InterruptedException ex) {
            }
            if(t.isAlive()) {
                LOG.log(Level.WARNING, "Thread {0} won''t die", t.getName());
            }
        }
    }

    void finishBangCommand(boolean fOK)
    {
        assert(EventQueue.isDispatchThread());
        if(coord == null)
            return;
        //System.err.println("!: DONE :!" + (!fOK ? " ABORT" : ""));
        ViManager.getFactory().stopGlassKeyCatch();
        Msg.wmsg("");

        if(!fOK) {
            // NEEDSWORK: set write to process thread to not bother cleaning up
            // set flag in coord so we get a rollback undo
            try {
                int exit = coord.process.exitValue();
            } catch(IllegalThreadStateException ex) {
                if(dbg.getBoolean()) {
                        dbg.println("!: destroying process");
                }
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
        if(!fOK) {
            return;
        }
    }

    private StringBuilder parseBang(StringBuilder sb)
    {
        StringBuilder newsb = new StringBuilder();
        int index = 0;
        while(index < sb.length()) {
            char c = sb.charAt(index);
            switch(c) {

                case '!':
                    if (escaped(index, sb)) {
                        // looking at "!" following a "\"
                        // means we already appended a \ to newsb
                        // so we replace it by !
                        newsb.setCharAt(newsb.length() - 1, '!');
                    } else {
                        // replace this ! by last command, if it exists
                        if (lastBangCommand != null) {
                            newsb.append(lastBangCommand);
                        } else {
                            return null; // Error, no last command
                        }
                    }
                    index++;
                    break;

                case '%':
                    if(escaped(index, sb)){
                        // Again, replace the last \ with %
                        newsb.setCharAt(newsb.length() - 1, '%');
                        index++;
                    } else {
                        // replace % by the filename
                        // watch for the filename modifiers ':x'
                        //
                        // NEESDWORK: filename-modifiers not handled in vim compatible way
                        // Not all file modifiers are supported and only one is allowed,
                        // allowing multiple will require rework here and getFileName.
                        //
                        if(index+2 < sb.length() && sb.charAt(index+1) == ':'){
                            newsb.append(G.curbuf.modifyFilename(sb.charAt(index+2)));
                            index += 3;
                        } else {
                            newsb.append(G.curbuf.modifyFilename(' '));
                            index++;
                        }
                    }
                    break;

                default:
                    newsb.append(c);
                    index++;

            } // end switch
        }
        return newsb;
    }

    private boolean escaped(int index, StringBuilder sb)
    {
        if (index == 0 || sb.charAt(index - 1) != '\\') {
            return false;
        } else {
            return true;
        }
    }

    private String commandLineToString(List<String> cl)
    {
        int nArgs = cl.size();
        StringBuilder result = new StringBuilder();

        if (nArgs > 0) {
            result.append(cl.get(0));
        }

        for (int i = 1; i < nArgs; i++) {
            result.append(' ').append(cl.get(i));
        }

        return result.toString();
    }

    private boolean doBangCommand(ColonEvent evt, String commandLine)
    {
        boolean isFilter = (evt.getAddrCount() > 0);

        ArrayList<String> shellCommandLine = new ArrayList<String>(3);
        String shellXQuote = G.p_sxq;

        shellCommandLine.add(G.p_sh);
        shellCommandLine.add(G.p_shcf);
        shellCommandLine.add(shellXQuote + commandLine + shellXQuote);

        if (dbg.getBoolean()) {
                dbg.println(
                    "!: ProcessBuilder: '" + shellCommandLine + "'");
        }

        ProcessBuilder pb = new ProcessBuilder(shellCommandLine);
        pb.redirectErrorStream(true);

        Process p;
        try {
            p = pb.start();
        } catch (IOException ex) {
            String s = ex.getMessage();
            Msg.emsg(s == null || s.equals("") ? "exec failed" : s);
            return false;
        }

        // Got a process, setup coord, start modal operation

        coord = new FilterThreadCoordinator(
                this, ColonCommands.setupExop(evt, false), p);

        // NEEDSWORK: put this where it can be repeated
        coord.statusMessage = "Enter 'Ctrl-C' to ABORT";
        Msg.fmsg(coord.statusMessage);

        ViManager.getFactory().startGlassKeyCatch(new KeyAdapter() {
                @Override
                public void keyTyped(KeyEvent e) {
                    e.consume();
                    if(e.getKeyChar() == (KeyEvent.VK_C & 0x1f)
                        && (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0) {
                        finishBangCommand(false);
                    } else {
                        Util.beep_flush();
                    }
                }
            });

        // start the thread(s)

        if (dbg.getBoolean()) {
                dbg.println("!: starting threads");
        }

        String fullCommand = commandLineToString(shellCommandLine);
        if (isFilter) {
            outputFromProcessToFile(evt, p, fullCommand);
        } else {
            outputToViOutputStream(evt, p, fullCommand);
        }

        return true;
    }

    private void outputToViOutputStream(
            ColonEvent evt,
            Process p,
            String cmd )
    {

        BufferedReader br;
        ViOutputStream vos;
        br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        vos = ViManager.createOutputStream(null, ViOutputStream.OUTPUT, cmd);

        coord.simpleExecuteThread = new SimpleExecuteThread(coord, vos, br);

        coord.simpleExecuteThread.start();
    }

    /**
        * Set up the threads for a filter command, there are three threads.
        * <ul>
        *   <li>read/write from document</li>
        *   <li>write to process</li>
        *   <li>read from process</li>
        * </ul>
        * There are two BlockingQueue.
        * The document thread puts data from document to fromDoc BlockingQueue and
        * takes data from toDoc BlockingQueue and puts it into the document. Each
        * of the other threads passes data between a BlockingQueue and
        * the process.
        * <p>
        * This could be done with a single thread if the process streams had a
        * timeout interface.
        *<p/><p>
        * NEEDSWORK: work with larger chunks at a time.
        * In particular, insert/delete large chunks to/from document;
        * this might descrease the memory requirements for undo/redo.
        */
    private void outputFromProcessToFile(
            ColonEvent evt,
            Process p,
            String cmd )
    {
        // NEEDSWORK: set up a thread group???

        BufferedReader br;
        BufferedWriter bw;
        br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        bw = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));

        coord.writeToProcessThread = new ProcessWriterThread(coord, bw);
        coord.readFromProcessThread = new ProcessReaderThread(coord, br);
        coord.documentThread = new DocumentThread(coord, evt.getViTextView());

        //coord.documentThread.start();
        coord.documentThread.runUnderTimer();
        coord.writeToProcessThread.start();
        coord.readFromProcessThread.start();
    }

} // end inner class BangAction


/**
    * This is used for both filtered bang commands and to execute a command.
    * There should really be two coordinator, one for each, but I'm feeling
    * lazy right now. Any of the Thread variables, eg simpleExecuteThread, can
    * be used to test for how this is being used.
    */
private static class FilterThreadCoordinator
{
    public static final int WAIT = 50; // milliseconds to wait for q data.
    public static final int QLEN = 100; // size of to/from doc q's'
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

    public FilterThreadCoordinator(BangAction ba, OPARG oa, Process p)
    {
        this.oa = oa;
        this.process = p;
        this.ba = ba;

        startLine = oa.start.getLine();
        lastLine = oa.end.getLine();

        fromDoc = new ArrayBlockingQueue<String>(QLEN);
        toDoc = new ArrayBlockingQueue<String>(QLEN);
    }

    void finish(final boolean fOK)
    {
        if(EventQueue.isDispatchThread()) {
            ba.finishBangCommand(fOK);
        } else {
            EventQueue.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        ba.finishBangCommand(fOK);
                    }
            });
        }
    }

    public void dumpState()
    {
        Options.getDebugOption(Options.dbgBang)
            .println("startLine " + startLine + ", lastLine = " + lastLine );
    }

} // end inner class


private static class SimpleExecuteThread extends FilterThread
{
    ViOutputStream vos;
    BufferedReader reader;
    boolean didCleanup;

    SimpleExecuteThread(
            FilterThreadCoordinator coord,
            ViOutputStream vos,
            BufferedReader reader)
    {
        super(SIMPLE_EXECUTE, coord);
        this.vos = vos;
        this.reader = reader;
    }

    @Override
    public void run()
    {
        super.run();
        coord.finish(true);
    }

    @Override
    public void dumpState()
    {
        super.dumpState();
    }

    @Override
    void cleanup()
    {
        if(didCleanup) {
            return;
        }
        didCleanup = true;
        if (dbg.getBoolean()) {
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
                LOG.log(Level.SEVERE, null, ex);
            }
            reader = null;
        }
    }

    @Override
    void doTask()
    {
        String line;
        try {
            while(!isProblem()) {
                line = reader.readLine();
                if(line == null) {
                    break;
                }
                if (dbgData.getBoolean()) {
                        dbgData.println("!: Writing '" + line + "' to ViOutputStream");
                }
                vos.println(line);
            }
            reader.close();
            reader = null;
            vos.close();
            vos = null;
        } catch (IOException ex) {
            exception = ex;
        }
    }

} // end inner class SimpleExecuteThread


private static class ProcessWriterThread extends FilterThread
{
    BufferedWriter writer;
    int currWriterLine;
    boolean wroteFirstLineToProcess;
    boolean reachedEndOfLines;
    boolean didCleanup;

    ProcessWriterThread(FilterThreadCoordinator coord,
                        BufferedWriter writer)
    {
        super(WRITE_PROCESS, coord);
        this.writer = writer;
    }

    @Override
    public void dumpState()
    {
        dbg.println("currWriterLine " + currWriterLine
                + ", wroteFirstLineToProcess " + wroteFirstLineToProcess
                + ", reachedEndOfLines " + reachedEndOfLines );
        super.dumpState();
    }

    @Override
    void cleanup()
    {
        if(didCleanup) {
            return;
        }
        didCleanup = true;
        if (dbg.getBoolean()) {
            dumpState();
        }
        if(writer != null) {
            try {
                writer.close();
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
            writer = null;
        }
    }

    @Override
    void doTask()
    {
        writeToProcess();
    }

    public void writeToProcess()
    {
        currWriterLine = coord.startLine;
        String data = null;
        try {
            while(!isProblem()) {
                data = coord.fromDoc.take();
                if(DONE.equals(data)) {
                    break;
                }
                writer.write(data);
                if (dbgData.getBoolean()) {
                    // NEEDSWORK: why trim(), use Text.formDebugDString
                        dbgData.println("!: Writer #" + currWriterLine
                            + ": '" + data.trim() + "'");
                }
                currWriterLine++;
                wroteFirstLineToProcess = true;
            }
            writer.close();
            writer = null;
        } catch (InterruptedException ex) {
            exception = ex;
        } catch (IOException ex) {
            exception = ex;
        }
        if (dbg.getBoolean()) {
                dbg.println("!: Wrote all lines needed to process");
        }
        if(!isProblem()) {
            reachedEndOfLines = true;
        }
    }

} // end inner class ProcessWriterThread


private static class ProcessReaderThread extends FilterThread
{
    BufferedReader reader;
    int currReaderLine;
    boolean wroteFirstLineToFile;
    boolean reachedEndOfProcessOutput;
    boolean didCleanup;

    ProcessReaderThread(
            FilterThreadCoordinator coord,
            BufferedReader reader )
    {
        super(READ_PROCESS, coord);
        this.reader = reader;
    }

    @Override
    public void dumpState()
    {
        dbg.println("currReaderLine " + currReaderLine
                + ", wroteFirstLineToFile " + wroteFirstLineToFile
                + ", reachedEndOfProcessOutput "
                + reachedEndOfProcessOutput );
        super.dumpState();
    }

    @Override
    void cleanup()
    {
        if(didCleanup) {
            return;
        }
        didCleanup = true;
        if (dbg.getBoolean()) {
            dumpState();
        }
        if(reader != null) {
            try {
                reader.close();
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, null, ex);
            }
            reader = null;
        }
    }

    @Override
    void doTask()
    {
        readFromProcess();
    }

    private void readFromProcess()
    {
        currReaderLine = coord.startLine;
        String data = null;
        try {
            while(!isProblem()) {
                data = reader.readLine();
                if (data == null) {
                    if (wroteFirstLineToFile && !isInterrupted()) {
                        reachedEndOfProcessOutput = true;
                    }
                    if (dbg.getBoolean()) {
                            dbg.println("!: end of process read data");
                    }
                    break;
                }
                if (dbgData.getBoolean()) {
                    // NEEDSWORK: why trim(), use Text.formDebugString
                        dbgData.println("!: Reader #" + currReaderLine
                            + ": '" + data.trim() + "'");
                }
                coord.toDoc.put(data);
                currReaderLine++;
            }
            coord.toDoc.put(DONE);
            reader.close();
            reader = null;
        } catch (InterruptedException ex) {
            exception = ex;
        } catch (IOException ex) {
            exception = ex;
        }
    }

} // end inner class ProcessReaderThread


/**
 *  This thread both reads and writes to the document.
 *  EXCEPT, IT IS NOT REALLY A THREAD anymore. It extends FilterThread
 *  because it is interfaced to like one. But now it runs under a timer as
 *  part of the event dispatch thread, this avoid locking issues
 *  between read/write the document and display.
 * <p>
 * Read one line at a time from the document and write it to a Queue for the
 * write to process q. Read a line at a time from the read from process q and
 * build up a large string with the process output. When the process completes
 * and all the data is gathered do a single op to the document.
 * </p><p>
 * Note that OriginalDocumentThread below does a line at a time into or out
 * of the document. But to allow all document mods to be done in a single
 * Runnable that strategy has problems with the current threading model,
 * where all mods are done in the event dispatch thread.
 * </p>
 */
private static class DocumentThread extends FilterThread
{
    int docReadLine;
    boolean docReadDone;

    int docWriteLine;
    boolean docWriteDone;
    StringBuilder sb = new StringBuilder();

    TextView win;

    int debugCounter;

    // timer is used as a flag, when null work is done
    Timer timer;
    boolean isThread;
    boolean interruptFlag;

    protected boolean didCleanup;

    DocumentThread(FilterThreadCoordinator coord, ViTextView tv)
    {
        super(RW_DOC, coord);
        win = (TextView)tv;
    }

    @Override
    public void run()
    {
        assert(false);
    }

    @Override
    public void interrupt()
    {
        interruptFlag = true;
    }

    @Override
    public boolean isInterrupted()
    {
        return interruptFlag;
    }

    public void runUnderTimer()
    {
        assert(!isThread);
        timer = new Timer(coord.WAIT, new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    assert(EventQueue.isDispatchThread());
                    if(timer != null) {
                        doServiceUnderTimer();
                    }
                }
        });
        timer.setRepeats(false);

        docReadLine = coord.startLine;
        docWriteLine = coord.startLine;

        doServiceUnderTimer();

        return;
    }

    private void finishUnderTimer()
    {
        if(!docReadDone) {
            cleanup(); // like "10000!!date"
        }

        doTaskCleanup();

        coord.finish(true);
    }

    private void doServiceUnderTimer()
    {
        boolean didSomething;

        try {
            do {
                didSomething = readDocument();
                didSomething |= writeDocument();
            } while(didSomething && !isProblem() && !docWriteDone);
        } catch(Throwable t) {
            exception = t;
        }

        if(!isProblem() && !docWriteDone) {
            timer.start();
        } else {
            finishUnderTimer();
            cleanup(); // write to the file
        }
    }

    @Override
    void doTask()
    {
    }

    public boolean readDocument()
    {
        boolean didSomething = false;
        String data = null;
        if(docReadLine <= coord.lastLine) {
            if (dbg.getBoolean()) {
                    dbg.println("!: rwDoc: try read doc");
            }
            while(!isProblem() && docReadLine <= coord.lastLine) {
                int docLine = docReadLine;
                data =    win.w_buffer.getLineSegment(docLine).toString();
                if(!coord.fromDoc.offer(data)) {
                    break;
                }
                if (dbgData.getBoolean()) {
                        dbgData.println("!: fromDoc #" + docReadLine + "," + docLine
                            + ": '" + data.trim() + "'");
                }
                docReadLine++;
                didSomething = true;
            }
        } else {
            if(!docReadDone && coord.fromDoc.offer(DONE)) {
                docReadDone = true;
                if (dbg.getBoolean()) {
                        dbg.println("!: rwDoc: docReadDONE");
                }
            }
        }
        return didSomething;
    }

    public boolean writeDocument()
    {
        boolean didSomething = false;
        String data = null;
        if(!docWriteDone) {
            if (dbg.getBoolean())
                dbg.println("!: rwDoc: try write doc " + debugCounter++);
            while(!isProblem()) {
                data = coord.toDoc.poll();
                if(data == null) {
                    break;
                }
                if(DONE.equals(data)) {
                    docWriteDone = true;
                    if (dbg.getBoolean()) {
                            dbg.println("!: rwDoc: docWriteDONE");
                    }
                    break;
                }
                sb.append(data);
                sb.append('\n');
                if (dbgData.getBoolean()) {
                        dbgData.println("!: toDoc #" + docWriteLine + ": '"
                            + data.trim() + "'");
                }
                didSomething = true;
            }
        }
        return didSomething;
    }

    @Override
    public void dumpState()
    {
        dbg.println("docReadDone " + docReadDone
                + ", docReadLine " + docReadLine
                + ", docWriteDone " + docWriteDone
                + ", docWriteLine " + docWriteLine);
        super.dumpState();
    }

    @Override
    void cleanup()
    {
        if(didCleanup) {
            return;
        }
        didCleanup = true;
        if (dbg.getBoolean()) {
            dumpState();
        }

        if(docReadDone && docReadLine <= coord.lastLine) {
            throw new IllegalStateException();
        }
        if (dbg.getBoolean()) {
                dbg.println("!: checking doc CLEANUP");
        }
        // don't run the document cleanup if there are issues
        if(isProblem()) {
            return;
        }

        Misc.runUndoable(new Runnable() {
                @Override
            public void run() {
                Buffer buf = win.w_buffer;
                int startOffset = buf.getLineStartOffset(coord.startLine);
                int endOffset = buf.getLineEndOffset(coord.lastLine);
                if(endOffset > buf.getLength()) {
                    // replacing last line, but the '\n' is not part of file
                    endOffset = buf.getLength();
                    // if replacement text end in '\n', then strip it.
                    if(sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n')
                        sb.setLength(sb.length()-1);
                }
                buf.replaceString(startOffset, endOffset, sb.toString());
            }
        });

    }

} // end

/////////////////////////////
//
// non-string builder version
//
//private static class OriginalDocumentThread extends FilterThread
//{
//    int docReadLine;
//    boolean docReadDone;
//
//    int docWriteLine;
//    boolean docWriteDone;
//
//    int linesDelta;
//    Window win;
//
//    int debugCounter;
//
//    // timer is used as a flag, when null work is done
//    Timer timer;
//    boolean isThread;
//    boolean interruptFlag;
//    boolean inUndo;
//
//    protected boolean didCleanup;
//
//    OriginalDocumentThread(FilterThreadCoordinator coord, ViTextView tv)
//    {
//        super(RW_DOC, coord);
//        win = (Window)tv;
//    }
//
//    @Override
//    public void run()
//    {
//        assert(false);
//    }
//
//    @Override
//    public void interrupt()
//    {
//        interruptFlag = true;
//    }
//
//    @Override
//    public boolean isInterrupted()
//    {
//        return interruptFlag;
//    }
//
//    public void runUnderTimer()
//    {
//        assert(!isThread);
//        timer = new Timer(coord.WAIT, new ActionListener() {
//                public void actionPerformed(ActionEvent e) {
//                    assert(EventQueue.isDispatchThread());
//                    if(timer != null) {
//                        doServiceUnderTimer();
//                    }
//                }
//        });
//        timer.setRepeats(false);
//
//        docReadLine = coord.startLine;
//        docWriteLine = coord.startLine;
//
//        inUndo = true;
//        Misc.beginUndo();
//
//        doServiceUnderTimer();
//
//        return;
//    }
//
//    private void finishUnderTimer()
//    {
//        if(!docReadDone) {
//            cleanup(); // like "10000!!date"
//        }
//
//        doTaskCleanup();
//
//        if(inUndo) {
//            Misc.endUndo();
//        }
//
//        coord.finish(true);
//    }
//
//    private void doServiceUnderTimer()
//    {
//        boolean didSomething;
//
//        try {
//            do {
//                didSomething = readDocument();
//                didSomething |= writeDocument();
//            } while(didSomething && !isProblem() && !docWriteDone);
//        } catch(Throwable t) {
//            exception = t;
//        }
//
//        if(!isProblem() && !docWriteDone) {
//            timer.start();
//        } else {
//            finishUnderTimer();
//        }
//    }
//
//    @Override
//    void doTask()
//    {
//    }
//
//    private void deleteLine(int line)
//    {
//        deleteLines(line, line);
//    }
//
//    private void deleteLines(int startLine, int endLine)
//    {
//        int startOffset = win.w_buffer.getLineStartOffset(startLine);
//        int endOffset = win.w_buffer.getLineEndOffset(endLine);
//        int docLength = win.getEditorComponent().getDocument().getLength();
//        if(endOffset > docLength) {
//            endOffset = docLength;
//        }
//        win.deleteChar(startOffset, endOffset);
//    }
//
//    public boolean readDocument()
//    {
//        boolean didSomething = false;
//        String data = null;
//        if(docReadLine <= coord.lastLine) {
//            if (dbg.value) {
//                System.err.println("!: rwDoc: try read doc");
//            }
//            while(!isProblem() && docReadLine <= coord.lastLine) {
//                int docLine = docReadLine + linesDelta;
//                data =    win.w_buffer.getLineSegment(docLine).toString();
//                if(!coord.fromDoc.offer(data)) {
//                    break;
//                }
//                deleteLine(docLine);
//                if (dbgData.value) {
//                    System.err.println("!: fromDoc #" + docReadLine + "," + docLine
//                            + ": '" + data.trim() + "'");
//                }
//                docReadLine++;
//                linesDelta--;
//                didSomething = true;
//            }
//        } else {
//            if(!docReadDone && coord.fromDoc.offer(DONE)) {
//                docReadDone = true;
//                if (dbg.value) {
//                    System.err.println("!: rwDoc: docReadDONE");
//                }
//            }
//        }
//        return didSomething;
//    }
//
//    public boolean writeDocument()
//    {
//        boolean didSomething = false;
//        String data = null;
//        if(!docWriteDone) {
//            if (dbg.value)
//                System.err.println("!: rwDoc: try write doc " + debugCounter++);
//            while(!isProblem()) {
//                data = coord.toDoc.poll();
//                if(data == null) {
//                    break;
//                }
//                if(DONE.equals(data)) {
//                    docWriteDone = true;
//                    if (dbg.value) {
//                        System.err.println("!: rwDoc: docWriteDONE");
//                    }
//                    break;
//                }
//                int offset = win.w_buffer.getLineStartOffset(docWriteLine);
//                win.insertText(offset, data);
//                offset += data.length();
//                win.insertText(offset, "\n");
//                if (dbgData.value) {
//                    System.err.println("!: toDoc #" + docWriteLine + ": '"
//                            + data.trim() + "'");
//                }
//                docWriteLine++;
//                linesDelta++;
//                didSomething = true;
//            }
//        }
//        return didSomething;
//    }
//
//    @Override
//    public void dumpState()
//    {
//        System.err.println("docReadDone " + docReadDone
//                + ", docReadLine " + docReadLine
//                + ", docWriteDone " + docWriteDone
//                + ", docWriteLine " + docWriteLine
//                + ", linesDelta " + linesDelta);
//        super.dumpState();
//    }
//
//    @Override
//    void cleanup()
//    {
//        if(didCleanup) {
//            return;
//        }
//        didCleanup = true;
//        if (dbg.value) {
//            dumpState();
//        }
//
//        if(docReadDone && docReadLine <= coord.lastLine) {
//            throw new IllegalStateException();
//        }
//        if (dbg.value) {
//            System.err.println("!: checking doc CLEANUP");
//        }
//        // don't run the document cleanup if there are issues
//        if(isProblem()) {
//            return;
//        }
//
//        // do one big delete
//        int line1, line2;
//        line1 = docReadLine + linesDelta;
//        line2 = coord.lastLine + linesDelta;
//        if (dbg.value) {
//            System.err.println("!: CLEANUP: fromDoc #"
//                    + docReadLine + ":" + coord.lastLine
//                    + ", " + line1 + ":" + line2);
//        }
//        deleteLines(line1, line2);
//    }
//
//} // end


/** Base class for Bang command threads.
    * <p>
    * The thread that signals completion
    * of the task should override run() and signal success if there was no
    * problem. If an problem occurs, failure will be signaled automatically,
    * see coord.finish()
    */
private static abstract class FilterThread extends Thread
{
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

    protected DebugOption dbg = (DebugOption)Options.getOption(Options.dbgBang);
    protected DebugOption dbgData
            = (DebugOption)Options.getOption(Options.dbgBangData);

    public FilterThread(String filterType, FilterThreadCoordinator coord)
    {
        super(filterType);
        this.coord = coord;
        if(false) {
            // low priority a bit
            int pri = getPriority() -1;
            if(pri >= MIN_PRIORITY) {
                setPriority(pri);
            }
        }
    }

    protected void dumpState()
    {
        if(exception != null) {
            dbg.println("exception: " + exception.getMessage());
        }
        if(uncaughtException != null) {
            dbg.println("uncaughtException: " + uncaughtException.getMessage());
        }
        if(error) {
            dbg.println("error: " + error);
        }
        if(interrupted) {
            dbg.println("interrupted: " + interrupted);
        }
        coord.dumpState();
    }

    /** Its possible that cleanup is called multiple times */
    abstract void cleanup();

    abstract void doTask();

    boolean isProblem()
    {
        return isInterrupted() || error || exception != null;
    }

    @Override
    public void run()
    {
        setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    FilterThread thisThread = (FilterThread) t;
                    if(thisThread.uncaughtException != null) {
                        return;
                    }
                    thisThread.uncaughtException = e;
                    LOG.log(Level.SEVERE, null, e);
                    cleanup();
                }
            });

        doTask();
        doTaskCleanup();
    }

    public void doTaskCleanup()
    {
        if(exception != null) {
            if (dbg.getBoolean()) {
                LOG.log(Level.SEVERE,
                        "!: Exception in " + getName() , exception);
            }
            cleanup();
        }

        if(error) {
            if (dbg.getBoolean()) {
                    dbg.println("!: error in " + getName());
            }
            cleanup();
        }

        if(isInterrupted()) {
            interrupted = true;
            if (dbg.getBoolean()) {
                    dbg.println("!: cleanup in " + getName());
            }
            cleanup();
        }

        if(isProblem()) {
            coord.finish(false);
        }
    }

} // end inner class FilterThread

}
