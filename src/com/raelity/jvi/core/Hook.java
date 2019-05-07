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

package com.raelity.jvi.core;

import org.openide.util.lookup.ServiceProvider;

import com.raelity.jvi.ViInitialization;
import com.raelity.jvi.ViTextView;
import com.raelity.jvi.manager.ViManager;

/**
 * This class facilitates communications between packages jvi and jvi.core.
 * The static local methods bounce from core to ViManager using a class
 * initialized from ViManager.
 *
 * The class is instantiated and given to ViMan. ViMan uses the instance
 * methods to send messages to core.
 *
 * @author Ernie Rael <err at raelity.com>
 */
public class Hook
{
    private static Hook INSTANCE;
    private static ViManager.ViManHook manHook;
    private Hook(){}

    public static class Init implements ViInitialization
    {
      @Override
      public void init()
      {
        Hook.init();
      }
    }

    private static void init()
    {
        if(INSTANCE != null)
            return;
        INSTANCE = new Hook();
        manHook = ViManager.setCoreHook(INSTANCE);
    }

    //////////////////////////////////////////////////////////////////////
    //
    // the instance methods
    //

    public void abortVisualMode()
    {
        Normal.abortVisualMode();
    }

    public void switchTo(ViTextView tv, Buffer buf)
    {
        G.switchTo(tv, buf);
    }

    public void uiCursorAndModeAdjust()
    {
        Misc.ui_cursor_shape();
        Misc.showmode();
    }

    public void gotc(char key, int modifier)
    {
        GetChar.gotc(key, modifier);
    }

    public void flush_buffers(boolean typeahead)
    {
        GetChar.flush_buffers(typeahead);
    }

    public boolean getRecordedLine(StringBuffer sb)
    {
        return GetChar.getRecordedLine(sb);
    }

    public void resetCommand(boolean flush)
    {
        Normal.resetCommand(flush);
    }

    public void requestCharBreakPauseRunEventQueue(int nLoop)
    {
        GetChar.requestCharBreakPauseRunEventQueue(nLoop);
    }

    //////////////////////////////////////////////////////////////////////
    //
    // Here are the static methods that core package uses to get to ViManager
    //

    static void setJViBusy(boolean f) {
        manHook.setJViBusy(f);
    }
}
