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

package com.raelity.jvi.swing;

/**
 * This interface is used to translate document line numbers to view line
 * numbers and visa-versa. View positions refers to the lines seen on
 * the screen; for example with code folding a group of lines may only
 * occupy a single line on the view/screen. There are also inquiries about
 * whether or not the font is fixed width and/or height.
 * 
 * {@link SwingTextView} delegates to one of these.
 *
 * The translation is quite simple with fixed width/height
 * fonts and no code folding.
 *
 * @author Ernie Rael <err at raelity.com>
 */
public interface ViewMap
{
    /**
     *
     * @return true if both fixed width and height
     */
    public boolean isFontFixed();
    public boolean isFontFixedHeight();
    public boolean isFontFixedWidth();

    /**
     * Check if folding is supported. Any feature that may hide lines,
     * or parts of lines, is considered folding.
     * @return true if folding is supported
     */
    public boolean isFolding();

    /**
     * Map a document line number to a view line number.
     * 
     * @param docLine line number of something in the document
     * @return line number taking folding into account
     * @throws RuntimeException if docLine is not in the file
     */
    public int viewLine(int docLine) throws RuntimeException;

    public int docLine(int viewLine);

    public int docLineOffset(int viewLine);
}
