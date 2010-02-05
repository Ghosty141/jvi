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

/**
 * A Mark represents a position within a document. A Mark is tracked
 * by the document.
 */
public interface ViMark extends ViFPOS {
    
    /** Set mark to position */
    public void setMark(ViFPOS fpos);
    
    /** Invalidate the mark. */
    public void invalidate();
    
    public class MarkException extends RuntimeException {
        public MarkException(String msg) {
            super(msg);
        }
    }
    
    public class MarkOrphanException extends MarkException {
        public MarkOrphanException(String msg) {
            super(msg);
        }
    }
}
