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

package com.raelity.jvi.lib;

import com.raelity.jvi.core.Msg;
import com.raelity.jvi.ViBuffer;
import com.raelity.jvi.ViFS;
import com.raelity.jvi.ViTextView;

/**
 *  A default implementation of the {@link com.raelity.jvi.ViFS}
 *  (vi file system support).
 */
public class DefaultViFS implements ViFS
{

    /**
     *  Default constructor.
     */
    public DefaultViFS()
    {
    }


    public String getDisplayFileName( ViBuffer buf )
    {
        return "xxx";
    }


    public boolean isReadOnly( ViBuffer buf )
    {
        return false;
    }


    public boolean isModified( ViBuffer buf )
    {
        return true;
    }


    public boolean write(ViTextView tv,
                         boolean force,
                         Object writeTarget,
                         Integer[] range)
    {
        // Compatibility method with old interface
        //
        // if(range.length == 0) {
        //     if(writeTarget == null) {
        //         return write(tv, force);
        //     } else if(writeTarget instanceof String) {
        //         return write(tv, fName, force);
        //     } else
        //         assert false : "unsupported writeTarget";
        // }
        // assert false : "range not supported";
        //

        String r = null;
        if(range.length > 0) {
            r = "" + range[0] + "," + range[1];
        }

        System.err.println(String.format(
                "write tv: %s%s, range: %s", (force?"! ":""), writeTarget, r));
        return true;
    }


    public boolean writeAll( boolean force )
    {
        Msg.emsg("writAll() not implemented");
        return false;
    }


    public void edit( ViTextView tv, boolean force, int n )
    {
        Msg.emsg("edit(tv, int{" + n + "}, force) not implemented");
    }


    public void edit( ViTextView tv, boolean force, Object fileThing )
    {
        Msg.emsg("edit(tv, int{" + fileThing + "}, force) not implemented");
    }


} // end com.raelity.jvi.DefaultViFS