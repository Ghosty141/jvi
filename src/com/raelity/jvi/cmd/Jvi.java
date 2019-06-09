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

package com.raelity.jvi.cmd;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.HashMap;
import java.util.Map;

import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.TabSet;
import javax.swing.text.TabStop;

import com.raelity.jvi.manager.AppViews;
import com.raelity.jvi.manager.ViManager;

/**
 * The following are the hooks into jVi used in this class.
 * <br/>The AppViews one is optional.
 * <br/>The statusDisplay is for single line output.
 * <br/>Note that bulk output via PlayOutputStream goes to System.err.
 * <br/>Following are some key hookups.
 * <pre>
 *      ViManager.setViFactory(new PlayFactory(mapJepFrame));
 *      AppViews.open(new PlayAppView(f, editor), "Jvi.setupFrame");
 *      PlayFactory.installKeymap(m_frame1.getEditor());
 *      mapJepFrame.put(editor, frame);
 *      static private MyPropertySheetDialog dialog;
 * </pre>
 *
 * @author Ernie Rael <err at raelity.com>
 */
public class Jvi
{
    /** If true, pack resulting UI. */
    public static boolean packFrame   = true;

    /** If true, make two frames. */
    public static boolean make2Frames = false;

    private static int nFrame = 0;              // total frame count
    private static JviFrame m_frame1 = null;
    private static JviFrame m_frame2 = null;    // test two jVi on same document

    static Map<PlayEditorPane, JviFrame> mapJepFrame
            = new HashMap<PlayEditorPane, JviFrame>();


    /**
     *  Construct the frame-based application.
     */
    public static JviFrame makeFrame()
    {
        JviFrame frame = new JviFrame();
        nFrame++;

        PlayEditorPane editor = frame.getEditor();
        mapJepFrame.put(editor, frame);
        JScrollPane scrollPane = frame.getScrollPane();

        Font font = editor.getFont();
        editor.setFont(new Font("Monospaced",
                       font.getStyle(),
                       font.getSize()));
        font = editor.getFont();
        FontMetrics fm = editor.getFontMetrics(font);

        // Program the tabs, 8 chars per tab stop
        setTabs((JTextPane)editor, 8);

        int width = fm.charWidth(' ') * 81;
        int height = fm.getHeight() * 30;
        scrollPane.getViewport().setPreferredSize(new Dimension(width, height));

        // Validate frames that have preset sizes
        // Pack frames with useful preferred size info, e.g. from their layout
        if ( packFrame ) {
            frame.pack();
        } else {
            frame.validate();
        }

        final JLabel jl = frame.getCursorStatusBar();
        editor.addCaretListener(new CaretListener()
        {
            @Override
            public void caretUpdate(CaretEvent e)
            {
                JTextComponent jtc = (JTextComponent)e.getSource();
                Document doc = jtc.getDocument();
                int dot = e.getDot();
                Element root = doc.getDefaultRootElement();
                int l = root.getElementIndex(dot);
                Element elem = root.getElement(l);
                int col = dot - elem.getStartOffset();
                jl.setText("" + (l+1) + "-" + col + " <" + dot +">");
            }
        });
        // Center the window
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Dimension frameSize = frame.getSize();
        if (frameSize.height > screenSize.height) {
            frameSize.height = screenSize.height;
        }
        if (frameSize.width > screenSize.width) {
            frameSize.width = screenSize.width;
        }
        int offset = (nFrame -1) * 50;
        frame.setLocation((screenSize.width - frameSize.width) / 2 + offset,
                         (screenSize.height - frameSize.height) / 2 + offset);
        frame.setVisible(true);
        return frame;
    }


    private static void setTabs( JTextPane tp, int ts )
    {
        FontMetrics fm = tp.getFontMetrics( tp.getFont() );
        int charWidth = fm.charWidth( 'w' );
        int tabWidth = charWidth * ts;

        TabStop[] tabs = new TabStop[10];

        for (int j = 0; j < tabs.length; j++) {
            int tab = j + 1;
            tabs[j] = new TabStop( tab * tabWidth );
        }

        TabSet tabSet = new TabSet(tabs);
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setTabSet(attributes, tabSet);
        int length = tp.getDocument().getLength();
        tp.getStyledDocument()
                .setParagraphAttributes(0, length, attributes, false);
    }


    private static void setupFrame( final JviFrame f )
    {
        JEditorPane editor = f.getEditor();

        editor.setCaretColor(Color.black);

        //((BooleanOption)Options.getOption(Options.dbgKeyStrokes)).setBoolean(true);
        // Following is optional, some commands like ":ls" depend on it
        AppViews.open(new PlayAppView(f, editor), "Jvi.setupFrame");
    }


    /**
     *  Return frame 1.
     */
    public JFrame getFrame1()
    {
        return m_frame1;
    }


    /**
     *  Return frame 2, null if not created.
     */
    public JFrame getFrame2()
    {
        return m_frame2;
    }


    /**
     *  Main method.
     */
    @SuppressWarnings("CallToThreadDumpStack")
    public static void main( String[] args )
    {
        try {
          // UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
          // System.err.println("SETTING LOOK AND FEEL");
          // UIManager.setLookAndFeel("org.jvnet.substance.SubstanceLookAndFeel");
        } catch( Exception e ) {
            e.printStackTrace();
        }

        if ( args.length > 0 ) {
            make2Frames = true;
        }

        ViManager.setViFactory(new PlayFactory(mapJepFrame));

        try {
            ViManager.runInDispatch(true, new Runnable() {
                    @Override
                    public void run() {
                        Toolkit.getDefaultToolkit().setDynamicLayout(true);
                        m_frame1 = makeFrame();
                        m_frame1.optionsButton.addActionListener(
                        new ActionListener() {
                            @Override
                            public void actionPerformed(ActionEvent e) {
                                try {
                                    new OptionDialog(m_frame1);
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        });
                        setupFrame(m_frame1);
                        JEditorPane editor1 = m_frame1.getEditor();
                        editor1.getDocument().putProperty(
                                Document.TitleProperty, "DebugFileName");
                        if ( make2Frames ) {
                            m_frame2 = makeFrame();
                            JEditorPane editor2 = m_frame2.getEditor();
                            editor2.setDocument(editor1.getDocument());
                            // since same document, can't have different name
                            //editor2.getDocument().putProperty(
                            //        Document.TitleProperty, "FileInFrame2");
                            setupFrame(m_frame2);
                        }
                        editor1.requestFocusInWindow();
                    }
                });
        } catch( Exception e ) {
            e.printStackTrace();
            //System.err.println( e.getClass().getName()
            //        + " thrown by main() [2]:  " + e.getMessage() );
        }

        // invoke and wait to make sure widget is fully drawn.
        try {
            ViManager.runInDispatch(true, new Runnable() {
                    @Override
                    public void run() {
                        PlayFactory.installKeymap(m_frame1.getEditor());
                        if ( make2Frames ) {
                            PlayFactory.installKeymap(m_frame2.getEditor());
                        }
                    }
                });
        } catch( Exception e ) {
            e.printStackTrace();
            //System.err.println( e.getClass().getName()
            //        + " thrown by main() [3]:  " + e.getMessage() );
        }
        // wait for frame to exit, so JUnitTest won't kill it
    }

}
