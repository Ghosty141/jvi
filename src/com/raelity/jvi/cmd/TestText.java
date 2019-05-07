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

import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.Action;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;
import javax.swing.text.Keymap;
import javax.swing.text.TextAction;

import com.raelity.jvi.ViCmdEntry;
import com.raelity.jvi.swing.WindowCmdEntry;

public class TestText {
  boolean packFrame = false;
  static TestTextFrame frame;

  //Construct the application
  public TestText() {
    frame = new TestTextFrame();
    //Validate frames that have preset sizes
    //Pack frames that have useful preferred size info, e.g. from their layout
    if (packFrame) {
      frame.pack();
    }
    else {
      frame.validate();
    }
    //Center the window
    Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
    Dimension frameSize = frame.getSize();
    if (frameSize.height > screenSize.height) {
      frameSize.height = screenSize.height;
    }
    if (frameSize.width > screenSize.width) {
      frameSize.width = screenSize.width;
    }
    frame.setLocation((screenSize.width - frameSize.width) / 2, (screenSize.height - frameSize.height) / 2);
    frame.setVisible(true);
  }

  //Main method
  public static void main(String[] args) {
    try {
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    }
    catch(Exception e) {
      e.printStackTrace();
    }
    new TestText();
    setKeymap(frame.jEditorPane1);

    /*
    final Document d = frame.jEditorPane1.getDocument();
    try {
      ViManager.runInDispatch(true, new Runnable() {
        public void run() {
          System.out.println("Length: " + d.getLength());
        }});
    } catch(Exception e){}
    ((AbstractDocument.AbstractElement)d.getDefaultRootElement())
          .dump(System.err, 4);
    */
  }

  static WindowCmdEntry commandDialog
          = new WindowCmdEntry(ViCmdEntry.Type.COLON);

  /** Bounce the event, modified, to this class's user. */
  static class SimpleEvent extends TextAction {
    SimpleEvent(String name) {
      super(name);
    }
        @Override
    public void actionPerformed(ActionEvent e) {
      commandDialog.activate(":", null/*frame.jEditorPane1*/);
    }
  };

  static Action createSimpleEvent(String name) {
    return new SimpleEvent(name);
  }

  static class ReportEvent extends TextAction {
    String msg;
    ReportEvent(String name) {
      super(name);
      msg = name;
    }
        @Override
    public void actionPerformed(ActionEvent e) {
      System.err.println("action: " + msg);
    }
  };

  static Action createReportEvent(String name) {
    return new ReportEvent(name);
  }

  /**
   * Return and <ESC> fire events and update the history list.
   */
  static void setKeymap(JTextComponent c) {
    Keymap keymap = JTextComponent.addKeymap("Command",
                                            c.getKeymap());
    JTextComponent.loadKeymap(keymap, getBindings(), getActions());
    c.setKeymap(keymap);
  }

  static JTextComponent.KeyBinding[] getBindings() {
    JTextComponent.KeyBinding[] bindings = {
	new JTextComponent.KeyBinding(KeyStroke.getKeyStroke(
		       (char)KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK),
		       "popupcommand"),
	new JTextComponent.KeyBinding(KeyStroke.getKeyStroke(
		       '.'/*KeyEvent.VK_PERIOD*/, InputEvent.CTRL_DOWN_MASK),
		       "re_ctrl_period"),
	new JTextComponent.KeyBinding(KeyStroke.getKeyStroke(
		       ':'/*(char)KeyEvent.VK_COLON*/),
		       "popupcommand"/*"re_colon"*/)
    };
    return bindings;
  }

  static Action[] getActions() {
    Action[] localActions = null;
    try {
      localActions = new Action[] {
	  createSimpleEvent("popupcommand"),
	  createReportEvent("re_ctrl_period"),
	  createReportEvent("re_colon")
      };
    } catch(Throwable e) {
      e.printStackTrace();
    }
    return localActions;
  }
}
