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
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package com.raelity.jvi.swing.ui.options;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.beans.BeanInfo;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;

import com.l2fprod.common.propertysheet.PropertySheetPanel;

import com.raelity.jvi.core.Options;
import com.raelity.jvi.options.KeyBindingBean;
import com.raelity.jvi.options.KeypadBindingBean;
import com.raelity.jvi.options.OptionsBean;

/**
 * The module requires com.l2fprod.common...
 * see http://common.l2fprod.com/ Only the jar l2fprod-common-sheet.jar is
 * needed.
 *
 * NOTE: this file can simply be excluded from compilation and everything
 * will work fine since it is invoked through reflection.
 *
 * @author erra
 */
public class OptionsPanel extends JPanel {

    ChangeNotify changeNotify;
    private List<Options.EditControl> optionSheets
            = new ArrayList<Options.EditControl>();
    private MapCommands mapCommands;

    public OptionsPanel() {
        this(() -> {});
    }

    public OptionsPanel(ChangeNotify changeNotify) {
        this.changeNotify = changeNotify;
        setLayout(new BorderLayout());

        add(getTabbedOptions(), BorderLayout.CENTER);
    }

    public void load()
    {
        // read property values from backing store
        // and prepare for a new property edit op
        for (Options.EditControl optionSheet : optionSheets) {
            optionSheet.start();
        }
    }

    public void ok()
    {
        // accept the edits
        for (Options.EditControl optionSheet : optionSheets) {
            optionSheet.ok();
        }
    }

    public void cancel()
    {
        // back out the changes
        for (Options.EditControl optionSheet : optionSheets) {
            optionSheet.cancel();
        }
    }

    public boolean valid() {
        return true;
    }

    public static interface ChangeNotify {
        public void change();
    }

    // NEEDSWORK: 3rd param "OptionsControl with init, cancel methods"
    private void addTab(JTabbedPane tabs, String name, BeanInfo bi) {
        OptionSheet optionSheet = new OptionSheet(bi, this);
        optionSheets.add(optionSheet);
        tabs.add(name, optionSheet);
    }

    private void addMapCommand(JTabbedPane tabs) {
        mapCommands = new MapCommands(Options.mapCommands, this);
        optionSheets.add(mapCommands);
        tabs.add("Map Commands", mapCommands);
    }

    public BeanInfo createDebugBean() {
        return new OptionsBean.Debug();
    }

    private JComponent getTabbedOptions() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setTabPlacement(JTabbedPane.LEFT);
        addTab(tabs, "Platform", new OptionsBean.Platform());
        addTab(tabs, "General", new OptionsBean.General());
        addTab(tabs, "Search", new OptionsBean.Search());
        addTab(tabs, "Buffer Modifications", new OptionsBean.Modify());
        addTab(tabs, "Cursor Line Wrap", new OptionsBean.CursorWrap());
        addTab(tabs, "External Process", new OptionsBean.ExternalProcess());

        addMapCommand(tabs);

        addTab(tabs, "Ctrl-Key Bindings", new KeyBindingBean());
        addTab(tabs, "KeyPad Bindings", new KeypadBindingBean());
        addTab(tabs, "Debug", createDebugBean());

        // lay things out to get sizes so we can adjust splitter
        //tabs.validate();

        //
        // Adjust the splitter to give more room
        // to the property description
        //
        for(int i = tabs.getComponentCount() -1; i >= 0; i--) {
            Component c = tabs.getComponentAt(i);
            if(!(c instanceof OptionSheet))
                continue;
            PropertySheetPanel sheet = ((OptionSheet) c).sheet;
            JSplitPane sp = null;
            for(int j = sheet.getComponentCount() -1; j > 0; j--) {
                if(sheet.getComponent(j) instanceof JSplitPane) {
                    sp = (JSplitPane) sheet.getComponent(j);
                    break;
                }
            }
            if(sp != null) {
                //int h = sp.getHeight();
                int h = sp.getPreferredSize().height;
                //int loc = sp.getDividerLocation();
                int newLoc = h - 120;
                sp.setDividerLocation(newLoc);
                double d = sp.getResizeWeight();
                sp.setResizeWeight(.65);
                //sp.invalidate();

                // There is layout problem with long lines in the description.
                // it tries to widen the property sheet to contain the
                // description in a single line. Give it a small prefered size
                // and the other sizing algorithms work better.
                Component comp = sp.getBottomComponent();
                if(comp != null) {
                    comp.setPreferredSize(new Dimension(100,100));
                }
                if(comp != null)
                    comp.invalidate();
            }
            // Need to set false, so the setDividerLocation is remembered
            sheet.setDescriptionVisible(false);
            sheet.setDescriptionVisible(true);
        }
        //propertyEditors = null;
        return tabs;
    }
}
