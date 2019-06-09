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
import com.raelity.jvi.swing.ui.options.OptionsPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;

public class OptionDialog extends JDialog {

    private OptionsPanel optionsPanel;

    public OptionDialog(Frame owner) {
        super(owner);
        setupDialog();
    }

    private void setupDialog() {
        setLocationRelativeTo(null);
        setSize(500,500);
        setLayout(new BorderLayout());

        optionsPanel = new OptionsPanel();
        add(optionsPanel, BorderLayout.CENTER);
        add(getButtonBar(), BorderLayout.SOUTH);
        setVisible(true);
    }

    private JPanel getButtonBar()
    {
        JPanel buttonBar = new JPanel();
        buttonBar.setLayout(new FlowLayout(FlowLayout.RIGHT));

        JButton okButton = new JButton("Ok");
        okButton.addActionListener(e -> {
            optionsPanel.ok();
            dispose();
        });

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            optionsPanel.cancel();
            dispose();
        });

        buttonBar.add(okButton);
        buttonBar.add(cancelButton);
        return buttonBar;
    }
}
