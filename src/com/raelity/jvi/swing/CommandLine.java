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

package com.raelity.jvi.swing;

import java.awt.AWTEvent;
import java.awt.AWTKeyStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.ComboBoxEditor;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicComboBoxEditor;
import javax.swing.plaf.basic.BasicComboBoxEditor.UIResource;
import javax.swing.text.BadLocationException;
import javax.swing.text.Caret;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.Keymap;
import javax.swing.text.TextAction;

import com.raelity.jvi.ViCmdEntry;
import com.raelity.jvi.ViTextView;
import com.raelity.jvi.core.G;
import com.raelity.jvi.core.GetChar;
import com.raelity.jvi.core.Normal;
import com.raelity.jvi.core.Options;
import com.raelity.jvi.lib.MyEventListenerList;
import com.raelity.jvi.manager.ViManager;
import com.raelity.jvi.options.DebugOption;

/**
 * This class presents a editable combo box UI for picking up command entry
 * data. A mode, usually a single character like "?" or ":", can be
 * set for display. This UI supports the maintenance of a history of commands.
 * This component builds a keymap through which the action events are
 * delivered. When an event is delivered, the history list is updated.
 * The command line has a label that can be set with {@link #setMode}.
 * <p>Take steps to prevent this component from taking focus
 * through the focus manager. But ultimately need to subclass the
 * editor component to handle it.
 */
public class CommandLine extends JPanel
{
    private static final Logger LOG
            = Logger.getLogger(CommandLine.class.getName());
    static public final int DEFAULT_HISTORY_SIZE = 50;
    static public final String COMMAND_LINE_KEYMAP = "viCommandLine";
    JLabel modeLabel = new JLabel();
    JComboBox combo = new JComboBox();
    DefaultComboBoxModel model = (DefaultComboBoxModel)combo.getModel();
    GridBagLayout gridBagLayout1 = new GridBagLayout();
    private String mode;
    private int historySize;
    Border border1;
    boolean setKeymapActive;
    int dot;
    int mark;
    private static final String ACT_FINISH = "vi-command-finish";
    private static final String ACT_TAB = "vi-command-tab";
    private static final String ACT_BACK_SPACE = "vi-command-backspace";

    /**
     *  This is not intended to match an actual keystroke, it is used
     *  to register an action that can be used externally.
     */
    public static final KeyStroke EXECUTE_KEY
            = KeyStroke.getKeyStroke( KeyEvent.VK_ENTER,
                                      InputEvent.SHIFT_DOWN_MASK
                                      | InputEvent.ALT_DOWN_MASK
                                      | InputEvent.META_DOWN_MASK
                                      | InputEvent.CTRL_DOWN_MASK
                                      | InputEvent.BUTTON1_DOWN_MASK
                                      | InputEvent.BUTTON2_DOWN_MASK
                                      | InputEvent.BUTTON3_DOWN_MASK );


    // ............


    public CommandLine()
    {
        // see https://substance.dev.java.net/issues/show_bug.cgi?id=285
        //combo.putClientProperty(LafWidget.COMBO_BOX_NO_AUTOCOMPLETION, true);
        combo.putClientProperty("lafwidgets.comboboxNoAutoCompletion", true);
        combo.setEditor(new MyComboBoxEditor());
        combo.setEditable(true);
        JTextComponent text
                = (JTextComponent) combo.getEditor().getEditorComponent();
        text.addPropertyChangeListener("keymap", new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if(setKeymapActive)
                    return;
                Object newO = evt.getNewValue();
                Object oldO = evt.getOldValue();
                if(newO != null) {
                    EventQueue.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            setKeymap();
                        }
                    });
                }
            }
        });

        try {
            jbInit();
        } catch ( Exception ex ) {
            LOG.log(Level.SEVERE, null, ex);
        }
        Font font = modeLabel.getFont();
        modeLabel.setFont(new Font("Monospaced",
                                   Font.BOLD,
                                   font.getSize()));
        font = combo.getFont();
        combo.setFont(new Font("Monospaced",
                               font.getStyle(),
                               font.getSize()));
        setHistorySize(DEFAULT_HISTORY_SIZE);
        setMode(" ");
        setComboDoneListener();
        setKeymap();
        // allow tabs to be entered into text field
        Component c = getTextComponent();
        Set<AWTKeyStroke> set = Collections.emptySet();
        c.setFocusTraversalKeys(
                KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, set);
        c.setFocusTraversalKeys(
                KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, set);
    }

    void setupFont(Font srcFont)
    {
        modeLabel.setFont(srcFont.deriveFont(Font.BOLD));
        combo.setFont(srcFont);
    }


    /**
     *  This is used to initialize the text of the combo box, needed so that
     *  characters entered before the combo box gets focus are not lost.
     */
    public void init( String s )
    {
        dot = mark = 0;
        if ( s.length() == 0 ) {
            JTextComponent tc = getTextComponent();
            int len = tc.getText().length();
            if(len > 0) {
                tc.setCaretPosition(0);
                tc.moveCaretPosition(len);
                mark = 0;
                dot = len;
                //System.err.println("Selection length = " + len);
            }
        } else {
            try {
                Document doc = getTextComponent().getDocument();
                mark = dot = s.length();
                doc.remove(0,doc.getLength());
                doc.insertString(0, s, null);
            } catch ( BadLocationException ex ) { }
        }
    }


    /**
     *  This is used to append characters to the the combo box. It is
     *  needed so that characters entered before the combo box gets focus
     *  are not lost.
     *  <p>
     *  If there is a selection, then clear the selection. Do this because
     *  typically typed characters replace the selection.
     */
    public void append( char c )
    {
        String s = String.valueOf(c);
        JTextComponent tc = getTextComponent();
        if(tc.getSelectionStart() != tc.getSelectionEnd()) {
            // replace the selection
            tc.setText(s);
        } else {
            int offset = tc.getDocument().getLength();
            try {
                getTextComponent().getDocument().insertString(offset, s, null);
            } catch (BadLocationException ex) { }
        }
    }


    private void jbInit() throws Exception
    {
        modeLabel.setText("");
        this.setLayout(gridBagLayout1);
        this.add(modeLabel, new GridBagConstraints(0, 0, 1, 1, 0.0, 0.0,
                GridBagConstraints.CENTER, GridBagConstraints.NONE,
                new Insets(0, 0, 0, 0), 0, 0));
        this.add(combo, new GridBagConstraints(1, 0, 1, 1, 1.0, 0.0,
                GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL,
                new Insets(0, 0, 0, 0), 0, 0));
    }


    public void setupBorder()
    {
        if(border1 == null) {
            border1 = BorderFactory.createLineBorder(Color.black, 2);
        }
        this.setBorder(border1);
    }


    /**
     *  This is used when the combo box is going to be displayed.
     *  A blank line is put at the head of the list. This blank is
     *  automatically removed when the user completes the action.
     */
    public void clear()
    {
        if ( historySize == 0 ) {
            getTextComponent().setText("");
            return;
        }
        model.insertElementAt("", 0);

        // ??? prevent re-execute last command on <CR>
        getTextComponent().setText("");
    }


    public void takeFocus( boolean flag )
    {
        if ( flag ) {
            combo.setEnabled(true);
            // NEEDSWORK: FOCUS: use requestFocusInWindow()
            getTextComponent().requestFocus();
        } else {
            combo.setEnabled(false);
        }
    }


    /**
     *  Retrieve the contents of the command line. This is typically
     *  done in response to a action event.
     */
    public String getCommand()
    {
        return getTextComponent().getText();
    }


    /**
     *  This sets the mode of the command line, e.g. ":" or "?".
     */
    private void setMode( String newMode )
    {
        mode = newMode;
        modeLabel.setText(" " + mode + " ");
    }


    public String getMode()
    {
        return mode;
    }


    /**
     *  Returns the mode JLabel.
     *  This is intended for use in setting font or color to
     *  match a surrounding UI.
     */
    public JLabel getModeLabel()
    {
        return modeLabel;
    }


    final JTextComponent getTextComponent()
    {
        Component c = combo.getEditor().getEditorComponent();
        return (JTextComponent)c;
    }


    /**
     *  This installs the history list.
     */
    public void SetHistory(List<String> l)
    {
        model.removeAllElements();
        for(String s : l) {
            model.addElement(s);
        }
    }

    public List<String> getHistory()
    {
        List<String> l = new ArrayList<String>();
        for(int i = 0; i < model.getSize(); i++) {
            l.add((String)model.getElementAt(i));
        }
        return l;
    }

    /**
     *  Make the argument command the top of the list.
     *  If is already in the list then first remove it.
     */
    public void makeTop( String command )
    {
        if ( historySize == 0 ) {
            return;
        }
        // remove the empty-blank element
        model.removeElement("");
        // if the empty-blank string was selected we're done
        if(command.isEmpty()) {
            return;
        }
        // now move the selected string to the top of the list
        model.removeElement(command);
        model.insertElementAt(command, 0);
        combo.setSelectedIndex(0);
        trimList();
    }


    /** Use this to limit the size of the history list */
    private void setHistorySize( int newHistorySize )
    {
        if(newHistorySize < 0) {
            throw new IllegalArgumentException();
        }
        historySize = newHistorySize;
        trimList();
    }


    /** the max size of the history list */
    public int getHistorySize()
    {
        return historySize;
    }


    private void trimList()
    {
        while(model.getSize() > historySize) {
            model.removeElementAt(model.getSize() - 1);
        }
    }


    private void setComboDoneListener()
    {
        combo.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if("comboBoxEdited".equals(e.getActionCommand())) {
                    ActionEvent e01 = new ActionEvent(CommandLine.this,
                            e.getID(), "\n", e.getModifiers());
                    fireCommandLineActionPerformed(e01);
                }
            }
        });

    }


    /**
     * Return and <ESC> fire events and update the history list.
     */
    private void setKeymap()
    {
        if ( setKeymapActive ) {
            return;
        }
        setKeymapActive = true;
        Keymap keymap = JTextComponent.addKeymap(
                CommandLine.COMMAND_LINE_KEYMAP,
                getTextComponent().getKeymap());
        JTextComponent.loadKeymap(keymap, getBindings(), getActions());
        getTextComponent().setKeymap(keymap);
        setKeymapActive = false;
    }


    protected JTextComponent.KeyBinding[] getBindings()
    {
        JTextComponent.KeyBinding[] bindings = {
            new JTextComponent.KeyBinding(
                    EXECUTE_KEY,
                    ACT_FINISH),
            new JTextComponent.KeyBinding(KeyStroke.getKeyStroke(
                    KeyEvent.VK_ESCAPE, 0),
                    ACT_FINISH),
            new JTextComponent.KeyBinding(KeyStroke.getKeyStroke(
                    KeyEvent.VK_CLOSE_BRACKET, InputEvent.CTRL_DOWN_MASK),
                    ACT_FINISH),
            new JTextComponent.KeyBinding(KeyStroke.getKeyStroke(
                    '\t'),
                    ACT_TAB),
            new JTextComponent.KeyBinding(KeyStroke.getKeyStroke(
                    KeyEvent.VK_BACK_SPACE, 0, false),
                    ACT_BACK_SPACE),
            new JTextComponent.KeyBinding(KeyStroke.getKeyStroke(
                    KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK, false),
                    ACT_BACK_SPACE),
        };
        return bindings;
    }


    protected Action[] getActions()
    {
        Action[] localActions = null;
        try {
            localActions = new Action[] {
                new TextAction(ACT_FINISH) {
                    @Override
                    public void actionPerformed(ActionEvent e)
                    {
                        // Note that only '\n' as a command does anything,
                        // other things, like ESC and Ctrl-], are ignored
                        String actionCommand = e.getActionCommand();
                        int modifiers = e.getModifiers();
                        ActionEvent e01 = new ActionEvent(
                                CommandLine.this,
                                e.getID(),
                                actionCommand,
                                modifiers);
                        fireCommandLineActionPerformed(e01);
                    }
                },
                new TextAction(ACT_TAB) {
                    @Override
                    public void actionPerformed(ActionEvent e)
                    {
                        // input the tab (instead of focus traversal)
                        getTextComponent(e).replaceSelection("\t");
                    }
                },
                new TextAction(ACT_BACK_SPACE) {
                    @Override
                    public void actionPerformed(ActionEvent e)
                    {
                        // Treat a backspace on an empty line like <ESC>
                        JTextComponent jtc = getTextComponent(e);
                        if(jtc.getText().isEmpty()) {
                            Action act = jtc.getKeymap().getAction(
                                    KeyStroke.getKeyStroke(
                                        KeyEvent.VK_ESCAPE, 0));
                            act.actionPerformed(new ActionEvent(
                                    e.getSource(), e.getID(), "\u001b"));
                        } else {
                            Action bs= jtc.getActionMap()
                                .get(DefaultEditorKit.deletePrevCharAction);
                            if(bs != null) {
                                bs.actionPerformed(e);
                            }
                        }
                    }
                },
            };
        } catch(Throwable e) {
            LOG.log(Level.SEVERE, null, e);
        }
        return localActions;
    }


    /**
     * Deliver event to command line listener;
     * This is expected to complete the command
     * Do some maintenance on the LRU history.
     */
    protected void fireCommandLineActionPerformed( ActionEvent e )
    {
        String command = getTextComponent().getText();

        // Guaranteed to return a non-null array
        Object[] listeners = listenerList.getListenerList();

        // Process the listeners last to first, notifying
        // those that are interested in this event
        for (int i = listeners.length-2; i>=0; i-=2) {
            if (listeners[i]==ActionListener.class) {
                ((ActionListener)listeners[i+1]).actionPerformed(e);
            }
        }

        // Maintain the LRU history, do this after the notifying completion
        // to avoid document events relating to the following actions
        makeTop(command);
        combo.hidePopup();
    }


    /**
     * Adds the specified action listener to receive
     * action events from this textfield.
     *
     * @param l the action listener
     */
    public synchronized void addActionListener( ActionListener l )
    {
        listenerList.add(ActionListener.class, l);
    }


    /**
     * Removes the specified action listener so that it no longer
     * receives action events from this textfield.
     *
     * @param l the action listener
     */
    public synchronized void removeActionListener( ActionListener l )
    {
        listenerList.remove(ActionListener.class, l);
    }


    // inner classes ...........................................................


    /**
     * The CommandLine JPanel may be embedded in a Dialog or put on a glass
     * pane; CommandLineEntry is some common handling particularly for
     * the ViCmdEntry interface.
     */
    public static abstract class CommandLineEntry implements ViCmdEntry
    {
        /** result of last entry */
        protected String lastCommand;

        protected MyEventListenerList ell = new MyEventListenerList();

        protected ViCmdEntry.Type entryType;
        protected CommandLine commandLine;
        protected ViTextView tv;

        protected String initialText;

        CommandLineEntry(ViCmdEntry.Type type)
        {
            entryType=type;
            commandLine = new CommandLine();
            commandLine.setupBorder();

            commandLine.addActionListener(new ActionListener()
            {
                @Override
                public void actionPerformed(ActionEvent e)
                {
                    finishUpEntry(e);
                }
            });

            commandLine.setMode(entryType == ViCmdEntry.Type.COLON ? ":" : "/");

            if(ViManager.getOsVersion().isMac()) {
                // MAC needs a little TLC since the selection is changed
                // in JTextComponent after adding text and before focusGained
                //    init set: dot: 5 mark:5
                //    init: dot: 5 mark:5
                //    focusGained: dot: 5 mark:0
                //    focusGained set: dot: 5 mark:5
                // This class sets selection back as needed
                focusSetSelection = new FocusAdapter() {
                    @Override
                    public void focusGained(FocusEvent e) {
                        JTextComponent tc = (JTextComponent) e.getSource();
                        tc.removeFocusListener(focusSetSelection);
                        Caret c = tc.getCaret();
                        try {
                            tc.setCaretPosition(commandLine.mark);
                            tc.moveCaretPosition(commandLine.dot);
                        } catch (IllegalArgumentException ex) {
                            // sometimes get exception, see
                            // https://netbeans.org/bugzilla/show_bug.cgi?id=223853
                            String msg = String.format(
                                    "mark=%d, dot=%d, caret=%d, text=%s",
                                    commandLine.mark,
                                    commandLine.dot,
                                    c.getDot(),
                                    tc.getText());
                            new IllegalArgumentException(msg, ex)
                                    .printStackTrace();
                        }
                    }
                };
            }
        }

        /** ViCmdEntry interface */
        @Override
        final public void activate( String mode, ViTextView parent )
        {
            activate(mode, parent, "", false);
        }

        /**
         * Start taking input. As a side effect of invoking this,
         * the variable {@line #commandLineWindow} is set.
         */
        @Override
        final public void activate(
                String mode,
                ViTextView tv,
                String initialText,
                boolean passThru )
        {
            this.tv = tv;
            this.initialText = initialText;
            commandLine.getTextComponent()
                    .removeFocusListener(focusSetSelection);
            lastCommand = "";
            if(passThru) {
                Options.kd().println("cmdLine: activate PASSTHRU"); //REROUTE
                this.tv = null; // typically done as part of finish up
                lastCommand = initialText;
                fireEvent(new ActionEvent(tv.getEditor(),
                        ActionEvent.ACTION_PERFORMED,
                        "\n"));
                commandLine.makeTop(initialText);
                return;
            }
            Font f = tv.getEditor().getFont();
            commandLine.setupFont(f);
            commandLine.setMode(mode);
            commandLine.init(initialText);

            commandLine.getTextComponent().addFocusListener(focusSetSelection);

            finishActivate();
        }

        @Override
        public void setHistory(List<String> l)
        {
            commandLine.SetHistory(l);
        }

        @Override
        public void makeTop(String s)
        {
            commandLine.makeTop(s);
        }

        @Override
        public List<String> getHistory()
        {
            return commandLine.getHistory();
        }

        /**
         *  When the command line gets focus, there's some work to do
         *  to handle MAC bug.
         */
        private FocusListener focusSetSelection;

        @Override
        public void append( char c )
        {
            if (c == '\n') {
                fireEvent(new ActionEvent(tv.getEditor(),
                        ActionEvent.ACTION_PERFORMED,
                        "\n"));
            } else
                commandLine.append(c);
        };

        protected abstract void finishActivate();

        private void shutdownEntry(boolean isCancel)
        {
            commandLine.getTextComponent()
                    .removeFocusListener(focusSetSelection);
            prepareShutdown();

            // A cancel is the result of external changes, including focus,
            // so don't throw in additional focus stuff
            if(!isCancel)
                tv.getEditor().requestFocus();
            tv = null;
        }

        protected abstract void prepareShutdown();

        /**
         * Calculate the bounds for the entry component relative to the root
         * component when placed immediately under the tv's editor.
         * @param root the root, typically a JFrame
         * @param entry the command entry widget
         * @return bounds of the widget within the root
         */
        protected Rectangle positionCommandEntry(
                Component root, Component entry )
        {
            Container jc = SwingUtilities.getAncestorOfClass(
                    javax.swing.JScrollPane.class,
                    tv.getEditor());
            if(jc == null) {
                jc = (JTextComponent)tv.getEditor();
            }

            Dimension d00 = entry.getPreferredSize();
            Rectangle pos = jc.getBounds(); // become bounds for commmand entry
            pos.translate(0, jc.getHeight());  // just beneath component
            pos.height = d00.height;
            // trim the width
            pos.width = Math.min(500, jc.getWidth());
            // now translate bounds so relative to root pane
            Point p00 = SwingUtilities.convertPoint(jc.getParent(),
                    pos.x, pos.y, root);
            pos.setLocation(p00);
            // If past bottom of root pane, shift it up
            int offset = root.getHeight() - (pos.y + pos.height);
            if(offset < 0) {
                pos.translate(0, offset);
            }
            return pos;
        }

        @Override
        public String getCommand()
        {
            return lastCommand;
        };

        @Override
        public void cancel()
        {
            if(tv == null)
                return;
            lastCommand = "";
            shutdownEntry(true);
        };

        public JLabel getModeLabel()
        {
            return commandLine.getModeLabel();
        }

        @Override
        public JTextComponent getTextComponent()
        {
            return commandLine.getTextComponent();
        }

        public void finishUpEntry(ActionEvent e)
        {
            if(tv == null) {
                // There are cases where there are both
                //    CommandLine$SimpleEvent.actionPerformed
                // and
                //    CommandLine$2.actionPerformed (JComboBox.fireActionEvent)
                return;
            }

            try {
                // VISUAL REPAINT HACK
                // Repaint before executing commands..
                // so that I can be sure the visual area didn't change yet
                // and all has been repainted
                if (G.drawSavedVisualBounds()) {
                    G.drawSavedVisualBounds(false);
                    Normal.v_updateVisualState(tv);
                }
                // END VISUAL REPAINT HACK
                lastCommand = commandLine.getCommand();
                final DebugOption dbg =
                        Options.getDebugOption(Options.dbgKeyStrokes);
                if (dbg.getBoolean())
                    dbg.println("CommandAction: '" + lastCommand + "'");
                shutdownEntry(false);
                fireEvent(e);
            } catch (Exception ex) {
                LOG.log(Level.SEVERE, null, ex);
            } finally {
                commandLine.clear();
            }
        }

        /** Send the event If it is a successful entry, a CR, then
         * record the input. Note that initial Text is not part of
         * the recorded input.
         */
        protected void fireEvent( ActionEvent e )
        {
            // The action command must be present and have a character in it
            // This isn't always the case on a MAC when an ESC is entered
            String s = e.getActionCommand();
            if(s == null || s.isEmpty()) {
                // treat a poorly formed event as an ESC
                // see http://sourceforge.net/tracker/?func=detail&atid=103653&aid=3527153&group_id=3653
                //    ESC to exit search leaves editor in an unusable state - ID: 3527153
                e = new ActionEvent(e.getSource(), e.getID(),
                                    "\u001b", e.getModifiers());
            }
            if(e.getActionCommand().charAt(0) == '\n') {
                StringBuffer sb = new StringBuffer();
                if( ! initialText.isEmpty()
                        && lastCommand.startsWith(initialText)) {
                    sb.append(lastCommand.substring(initialText.length()));
                } else {
                    sb.append(lastCommand);
                }
                sb.append('\n');
                GetChar.userInput(new String(sb));
            }
            ell.fire(ActionListener.class, e);
        }

        @Override
        public void addActionListener(ActionListener l )
        {
            ell.add(ActionListener.class, l);
        }

        @Override
        public void removeActionListener(ActionListener l)
        {
            ell.remove(ActionListener.class, l);
        }

        private DocumentListener dl;
        private class DocListener implements DocumentListener
        {
            @Override public void insertUpdate(DocumentEvent e) {
                ell.fire(ChangeListener.class, new ChangeEvent(getTextComponent()));
            }
            @Override public void removeUpdate(DocumentEvent e) {
                ell.fire(ChangeListener.class, new ChangeEvent(getTextComponent()));
            }
            @Override public void changedUpdate(DocumentEvent e) { }
        }

        @Override
        public void addChangeListener(ChangeListener l)
        {
            if(ell.getListenerCount(ChangeListener.class) == 0) {
                dl = new DocListener();
                getTextComponent().getDocument().addDocumentListener(dl);
            }
            ell.add(ChangeListener.class, l);
        }

        @Override
        public void removeChangeListener(ChangeListener l)
        {
            ell.remove(ChangeListener.class, l);
            if(ell.getListenerCount(ChangeListener.class) == 0) {
                getTextComponent().getDocument().removeDocumentListener(dl);
                dl = null;
            }
        }

        @Override
        public String getCurrentEntry()
        {
            return getTextComponent().getText();
        }

    } // end inner CommandLineEntry

    //////////////////////////////////////////////////////////////////////
    //
    // In jdk1.8 in JComboBox::actionPerformed, the following was added
    //    ComboBoxEditor editor = getEditor();
    //    if ((editor != null) && (e != null) && (editor == e.getSource())) {
    // and
    //    editor -------- "BasicComboBoxEditor"
    //    e.getSource --- "BasicComboBoxEditor$BorderlessTextField"
    // so nothing happens on 1.8
    //
    // To work around this, override fireActionPerformed in the JTextField
    // and provide the combobox editor as the event source.
    // (I must be missing something, else how could you ever use this...)
    //
    //////////////////////////////////////////////////////////////////////
    private class MyComboBoxEditor extends BasicComboBoxEditor {
        @Override
        protected JTextField createEditorComponent() {
            // borderless text field has an issue, see below
            JTextField ed = new MyComboTextField("",9);
            ed.setBorder(null);
            return ed;
        }
    }

    // Copied from BasicComboBoxEditor::BorderlessTextField
    //          except for actionPerformed
    private class MyComboTextField extends JTextField {
        public MyComboTextField(String value,int n) {
            super(value,n);
        }

        // workaround for 4530952
        @Override
        public void setText(String s) {
            if (getText().equals(s)) {
                return;
            }
            super.setText(s);
        }

        @Override
        public void setBorder(Border b) {
            if (!(b instanceof UIResource)) {
                super.setBorder(b);
            }
        }

        // modified to set the combobox editor as the event source
        @Override
        protected void fireActionPerformed() {
            // Guaranteed to return a non-null array
            Object[] listeners = listenerList.getListenerList();
            int modifiers = 0;
            AWTEvent currentEvent = EventQueue.getCurrentEvent();
            if (currentEvent instanceof InputEvent) {
                modifiers = ((InputEvent)currentEvent).getModifiersEx();
            } else if (currentEvent instanceof ActionEvent) {
                modifiers = ((ActionEvent)currentEvent).getModifiers();
            }
            ActionEvent e =
                new ActionEvent(combo.getEditor(), ActionEvent.ACTION_PERFORMED,
                                (myCommand != null) ? myCommand : getText(),
                                EventQueue.getMostRecentEventTime(), modifiers);

            // Process the listeners last to first, notifying
            // those that are interested in this event
            for (int i = listeners.length-2; i>=0; i-=2) {
                if (listeners[i]==ActionListener.class) {
                    ((ActionListener)listeners[i+1]).actionPerformed(e);
                }
            }
        }

        private String myCommand;
        @Override
        public void setActionCommand(String command)
        {
            super.setActionCommand(command);
            myCommand = command;
        }

    }


    //////////////////////////////////////////////////////////////////////
    //                                                                  //
    //                    NOT USED BELOW THIS LINE                      //
    //                                                                  //
    //////////////////////////////////////////////////////////////////////

    //////////////////////////////////////////////////////////////////////
    //
    // This is for a combo box that contains a JEP.

    // Taken from following where I found a bug, issue 97130 in NB.
    // debuggerjpda/ui/src/org/netbeans/modules/debugger/jpda/ui/Evaluator.java
    // NEEDSWORK: It looks like this could go into jvi.swing, don't think
    // there's anything NB specific.
    private static final class ViCommandEditor implements ComboBoxEditor
    {
        private JEditorPane editor;
        //private Component component;
        private Object oldValue;

        public ViCommandEditor()
        {
            editor = new JEditorPane("text/x-vicommand", ""); //NOI18N
            editor.setBorder(null);
            new KeymapUpdater().run();
            /*component = new JScrollPane(editor,
                                        JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                                        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            editor.addFocusListener(new FocusListener() {
                public void focusGained(FocusEvent e) {
                    WatchPanel.setupContext(editor);
                }
                public void focusLost(FocusEvent e) {
                }
            });*/
            editor.addPropertyChangeListener("keymap",
                                             new PropertyChangeListener() {
                                                 @Override
                public void propertyChange(PropertyChangeEvent evt) {
                    if (!(evt.getNewValue() instanceof FilteredKeymap)) {
                        // We have to do this lazily, because the property change
                        // is fired *before* the keymap is actually changed!
                        EventQueue.invokeLater(new KeymapUpdater());
                    }
                }
            });
        }

        @Override
        public void addActionListener(ActionListener l)
        {
            // NB that setEditor calls addActionListener
            //editor.addActionListener(l);
        }

        @Override
        public void removeActionListener(ActionListener l)
        {
            //assert false;
            //editor.removeActionListener(l);
        }

        @Override
        public Component getEditorComponent()
        {
            return editor;
        }

        @Override
        public Object getItem()
        {
            Object newValue = editor.getText();

            if (oldValue != null && !(oldValue instanceof String))  {
                // The original value is not a string.
                // Should return the value in it's original type.
                if (newValue.equals(oldValue.toString()))  {
                    return oldValue;
                } else {
                    // Must take the value from the editor
                    // and get the value and cast it to the new type.
                    Class cls = oldValue.getClass();
                    try {
                        @SuppressWarnings("unchecked")
                        Method method = cls.getMethod("valueOf",
                                new Class[]{String.class});
                        newValue = method.invoke(oldValue,
                                new Object[] { editor.getText()});
                    } catch (Exception ex) {
                        // Fail silent and return the newValue (a String object)
                    }
                }
            }
            return newValue;
        }

        @Override
        public void setItem(Object obj)
        {
            if (obj != null)  {
                editor.setText(obj.toString());

                oldValue = obj;
            } else {
                editor.setText("");
            }
        }

        @Override
        public void selectAll()
        {
            editor.selectAll();
            editor.requestFocus();
        }

        private class KeymapUpdater implements Runnable
        {
            @Override
            public void run()
            {
                editor.setKeymap(new FilteredKeymap(editor.getKeymap()));
            }
        }

    } // end inner class ViCommandEditor


    // taken from same package as the above combo box editor
    public static class FilteredKeymap implements Keymap
    {
        private final KeyStroke enter
                = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
        private final KeyStroke esc
                = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        private final KeyStroke tab
                = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0);
        private final Keymap keyMap; // The original keymap

        /** Creates a new instance of FilteredKeymap */
        public FilteredKeymap(Keymap keyMap)
        {
            this.keyMap = keyMap;
        }

        @Override
        public void addActionForKeyStroke(KeyStroke key, Action a)
        {
            keyMap.addActionForKeyStroke(key, a);
        }

        @Override
        public Action getAction(KeyStroke key)
        {
            if (enter.equals(key) ||
                    esc.equals(key) ||
                    tab.equals(key)) {

                return null;
            } else {
                return keyMap.getAction(key);
            }
        }

        @Override
        public Action[] getBoundActions()
        {
            return keyMap.getBoundActions();
        }

        @Override
        public KeyStroke[] getBoundKeyStrokes()
        {
            return keyMap.getBoundKeyStrokes();
        }

        @Override
        public Action getDefaultAction()
        {
            return keyMap.getDefaultAction();
        }

        @Override
        public KeyStroke[] getKeyStrokesForAction(Action a)
        {
            return keyMap.getKeyStrokesForAction(a);
        }

        @Override
        public String getName()
        {
            return keyMap.getName()+"_Filtered"; //NOI18N
        }

        @Override
        public javax.swing.text.Keymap getResolveParent()
        {
            return keyMap.getResolveParent();
        }

        @Override
        public boolean isLocallyDefined(KeyStroke key)
        {
            if (enter.equals(key) ||
                    esc.equals(key) ||
                    tab.equals(key)) {

                return false;
            } else {
                return keyMap.isLocallyDefined(key);
            }
        }

        @Override
        public void removeBindings()
        {
            keyMap.removeBindings();
        }

        @Override
        public void removeKeyStrokeBinding(KeyStroke keys)
        {
            keyMap.removeKeyStrokeBinding(keys);
        }

        @Override
        public void setDefaultAction(Action a)
        {
            keyMap.setDefaultAction(a);
        }

        @Override
        public void setResolveParent(Keymap parent)
        {
            keyMap.setResolveParent(parent);
        }

    } // end inner class FilteredKeymap



} // end com.raelity.jvi.swing.CommandLine
