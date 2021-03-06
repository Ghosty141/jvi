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

import com.raelity.jvi.ViTextView;
import com.raelity.jvi.lib.OutputStreamAdaptor;

public class PlayOutputStream extends OutputStreamAdaptor {
  String type;
  String info;
  ViTextView tv;

  public PlayOutputStream(ViTextView tv, String type, String info) {
    this.type = type;
    this.info = info;
    this.tv = tv;
    
    String fName = tv != null ? tv.getBuffer().getDisplayFileName() : "no-file";
    System.err.println("ViOutputStream: type: " + type
                       + ", file: " + fName
                       + ", info: \n"
                       + "                " + info);
  }

    @Override
  public void println(int line, int offset, int length) {
    System.err.println("ViOutputStream: " + type + ", " + info + ": "
                       + "line: " + line + ", "
                       + "offset: " + offset + ", "
                       + "length: " + length
		       );
  }

    @Override
  public void println(String s) {
    System.err.println("ViOutputStream: " + s);
  }

  @Override
  public void printlnLink(String link, String text) {
    System.err.format("ViOutputStream: %s, %s: link: %s, text: %s",
                      type, info, link, text);
  }

    @Override
  public void close() {
  }
}

// vi:set sw=2 ts=8: