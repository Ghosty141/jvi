package com.raelity.jvi.options;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.beans.PropertyVetoException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openide.util.lookup.ServiceProvider;

import com.raelity.jvi.ViBuffer;
import com.raelity.jvi.ViInitialization;
import com.raelity.jvi.ViOptionBag;
import com.raelity.jvi.ViOutputStream;
import com.raelity.jvi.ViTextView;
import com.raelity.jvi.core.ColonCommands;
import com.raelity.jvi.core.ColonCommands.ColonEvent;
import com.raelity.jvi.core.G;
import com.raelity.jvi.core.Msg;
import com.raelity.jvi.core.Options;
import com.raelity.jvi.core.TextView;
import com.raelity.jvi.core.Util;
import com.raelity.jvi.manager.ViManager;
import com.raelity.jvi.options.VimOption.F;

/**
 * Implement ":se[t]".
 *
 * Options are either global or indirect, see the P_ XXX below An option
 * must be one or the other. Global options are static, an indirect option
 * is an instance variable in either G.curwin or G.curbuf. When a P_IND
 * variable is set, introspection is used to do the set.
 * <p>
 * In some cases, due to platform limitation, the same variable must be
 * set in all the instances, syncAllInstances(var) does that.
 */
public class SetColonCommand extends ColonCommands.AbstractColonAction
{
    private static final
            Logger LOG = Logger.getLogger(SetColonCommand.class.getName());
    
    public static class Init implements ViInitialization
    {
        @Override
        public void init()
        {
            SetColonCommand.init();
        }
    }

    private static void init()
    {
        ColonCommands.register("se", "set", new SetColonCommand(), null);
    }

    private SetColonCommand()
    {
    }

    public static class SetCommandException extends Exception
    {

        public SetCommandException(String msg)
        {
            super(msg);
        }
    }

    private enum OP {
        NONE(""),       // if nothing specified, like ":set ic".
                        // Could be SHOW as in ":set isk" for non boolean.
        DFLT("&"),      // set to default, like ":set ic&".
        SHOW("?"),      // display option
        INV("inv"),     // ! invert boolean
        NO("no"),       // no<opt> set boolean false
        ASS("="),       // = assign
        ADD("+="),      // +=
        PRE("^="),      // ^=
        SUB("-="),      // -=
        ;

        private final String token;

        OP(String token) {
            this.token = token;
        }

        public String getToken()
        {
            return token;
        }

        boolean isBooleanOp() {
            return this == INV || this == NO;
        }

        boolean isAnyAssign() {
            return this == ASS || this == ADD || this == PRE || this == SUB;
        }

        boolean isAssignOp() {
            return this == ADD || this == PRE || this == SUB;
        }

        boolean isShow() { return this == SHOW; }
        boolean isInv()  { return this == INV; }
        boolean isNo()   { return this == NO; }
        boolean isDflt() { return this == DFLT; }
        boolean isAss()  { return this == ASS; }
        boolean isAdd()  { return this == ADD; }
        boolean isPre()  { return this == PRE; }
        boolean isSub()  { return this == SUB; }
    }

    @Override
    public void actionPerformed(ActionEvent e)
    {
        ColonEvent evt = (ColonEvent)e;
        parseSetOptions(evt.getArgs());
    }

    private static void setCommandError(String msg) throws SetCommandException
    {
        Msg.emsg(msg);
        Util.beep_flush();
        throw new SetCommandException(msg);
    }

    public static void parseSetOptions(List<String> eventArgs)
    {
        if (eventArgs.isEmpty() ||
                eventArgs.size() == 1 && "all".equals(eventArgs.get(0))) {
            displayOptions(eventArgs);
            return;
        }
        LinkedList<String> args = new LinkedList<String>();
        // copy eventArgs into args, with possible fixup
        // ":set sw =4" is allowed, so if something starts with "="
        // then append it to the previous element
        int j = 0;
        for (int i = 0; i < eventArgs.size(); i++) {
            String arg = eventArgs.get(i);
            if (arg.startsWith("=") && args.size() > 0) {
                arg = args.removeLast() + arg;
            }
            args.addLast(arg);
        }
        for (String arg : args) {
            try {
                parseSetOption(arg);
            } catch (SetCommandException ex) {
                // error message given
                return;
            } catch (IllegalAccessException ex) {
                LOG.log(Level.SEVERE, null, ex);
                return;
            } catch (IllegalArgumentException ex) {
                LOG.log(Level.SEVERE, null, ex);
                return;
            }
        }
    }

    /**
     * This holds the results of parsing a set command
     */
    private static class VimOptionState
    {
        Class type;
        Object curValue;
        // used if type.isLocal()
        Field f;
        ViOptionBag bag;
        // used if regular option is provided
        Option opt;
        // Following not really option state, they are the
        // parse results when settigns options
        OP op;
        String inputValue; // new value
    }

    // This is train of thought
    public static void parseSetOption(String arg)
    throws IllegalAccessException, SetCommandException
    {
        VimOptionState voptState = new VimOptionState();
        String voptName = arg; // assume split does nothing
        Object[] split = splitAssignment(arg);
        if(split != null) {
            voptName = (String)split[0];
            voptState.op = (OP)split[1];
            voptState.inputValue = (String)split[2];
        }
        if (voptName.startsWith("no")) {
            voptState.op = OP.NO;
            voptName = voptName.substring(2);
        } else if (voptName.startsWith("inv")) {
            voptState.op = OP.INV;
            voptName = voptName.substring(3);
        } else if (voptName.endsWith("!")) {
            voptState.op = OP.INV;
            voptName = voptName.substring(0, voptName.length() - 1);
        } else if (voptName.endsWith("?")) {
            voptState.op = OP.SHOW;
            voptName = voptName.substring(0, voptName.length() - 1);
        } else if (voptName.endsWith("&")) {
            voptState.op = OP.DFLT;
            voptName = voptName.substring(0, voptName.length() - 1);
        }
        if(voptState.op == null)
            voptState.op = OP.NONE;
        VimOption vopt;
        vopt = VimOption.lookupUserName(voptName);
        if (vopt == null) {
            String msg = "Unknown option: " + voptName;
            setCommandError(msg);
            return; // get rid of "possible null pointer deref" hint
        }
        if (determineOptionState(vopt, voptState) == null) {
            String msg = "Internal error: " + arg;
            setCommandError(msg);
        }
        Object newValue = newOptionValue(arg, vopt, voptState);
        if (voptState.op.isShow()) {
            Msg.smsg(formatDisplayValue(vopt, voptState));
        } else {
            try {
                voptState.opt.validate(newValue);
                // HACK. Put this here, because we want to validate the string
                if(voptState.type == EnumSet.class) // HACK
                    newValue = ((EnumSetOption)voptState.opt)
                                        .decode((String)newValue);
            } catch (PropertyVetoException ex) {
                setCommandError(ex.getMessage());
            }

            if(G.dbgOptions().getBoolean()) {
                G.dbgOptions().printf("SET %s%s to '%s'\n",
                                  vopt.isGlobal() ? "G." : "",
                                  vopt.getVarName(), newValue);
            }
            voptState.f.set(voptState.bag, newValue);
            // NEEDSWORK: call some method if a global is set?
            if (vopt.isLocal()) {
                voptState.bag.viOptionSet(G.curwin(), vopt.varName);
            }
        }
    }

    // Split on '=', '+=', '-=', '^='
    private static Object[] splitAssignment(String arg)
    {
        Pattern p = Pattern.compile("^([a-z]+)(=|\\+=|\\^=|-=)(.*)$");
        Matcher m = p.matcher(arg);
        if(m.matches()) {
            Object[] split = new Object[3];
            split[0] = m.group(1);
            split[2] = m.group(3);
            OP op = null;
            String sop = m.group(2);
            if("=".equals(sop)) {
                op = OP.ASS;
            } else if("+=".equals(sop)) {
                op = OP.ADD;
            } else if("-=".equals(sop)) {
                op = OP.SUB;
            } else if("^=".equals(sop)) {
                op = OP.PRE;
            }
            split[1] = op;

            return split;
        }

        return null;
    }

    /**
     * Set voptState with information about the argument vopt.
     * The info about the option is taken from curwin/curbuf.
     */
    private static VimOptionState determineOptionState(VimOption vopt,
                                                       VimOptionState voptState)
    {
        if(voptState == null)
            voptState = new VimOptionState();
        if (vopt.optName != null) {
            voptState.opt = Options.getOption(vopt.optName);
        }

        voptState.bag = vopt.isGlobal()
                ? G.get()
                : vopt.isWin() ? G.curwin() : G.curwin().getBuffer();
        try {
            //
            // NEEDSWORK: Clean up getField/getDeclaredField.
            //            Using GetField for win/buffer
            //            and getDeclaredField for "G".
            //            The stuff in G does not need to be global.
            //            Also, the stuff in G is static.
            //
            voptState.f = vopt.isGlobal()
                    ? voptState.bag.getClass().getDeclaredField(vopt.varName)
                    : voptState.bag.getClass().getField(vopt.varName);
            voptState.f.setAccessible(true);
            voptState.type = voptState.f.getType();
            voptState.curValue = voptState.f.get(voptState.bag);
        } catch(NoSuchFieldException ex) {
            LOG.log(Level.SEVERE, null, ex);
        } catch(SecurityException ex) {
            LOG.log(Level.SEVERE, null, ex);
        } catch(IllegalArgumentException ex) {
            LOG.log(Level.SEVERE, null, ex);
        } catch(IllegalAccessException ex) {
            LOG.log(Level.SEVERE, null, ex);
        }

        return voptState;
    }

    // Most of the argument are class members
    private static Object newOptionValue(String arg, VimOption vopt,
                                         VimOptionState voptState)
    throws SetCommandException
    {
        Object newValue = null;
        if(voptState.op.isDflt()) {
            if((newValue = voptState.opt.getDefault()) != null)
                return newValue;
            setCommandError("Can not determine default value");
        }

        if (voptState.type == boolean.class) {
            if (voptState.inputValue != null) {
                // like: ":set ic=val"
                String msg = "Can't assign to boolean: " + arg;
                setCommandError(msg);
            }
            if (!voptState.op.isShow()) {
                newValue =
                        voptState.op.isInv()
                        ? !((Boolean)voptState.curValue).booleanValue()
                        : !voptState.op.isNo();
            }

        } else {
            // handle a number, string or a color
            if(voptState.op.isBooleanOp()) {
                String msg = "boolean op '" + voptState.op
                                + "' invalid for "
                                + voptState.type.getSimpleName()
                                + " option";
                setCommandError(msg);
            }
            if (voptState.inputValue == null) {
                voptState.op = OP.SHOW;
            }
            if (!voptState.op.isShow()) {
                if(!voptState.op.isAnyAssign()) {
                    String msg = "Operation '" + voptState.op
                                    + "' invalid in this context";
                    setCommandError(msg);
                }
                if (voptState.type == int.class) {
                    try {
                        int oldValue = (Integer)voptState.curValue;
                        int val = Integer.parseInt(voptState.inputValue);
                        switch(voptState.op) {
                            case ASS: break; // val = val
                            case ADD: val = oldValue + val; break;
                            case SUB: val = oldValue - val; break;
                            case PRE: val = oldValue * val; break;
                        }
                        newValue = (Integer)val;
                    } catch (NumberFormatException ex) {
                        String msg = "Number required after '=': " + arg;
                        setCommandError(msg);
                    }
                } else if (voptState.type == String.class) {
                    if(!voptState.op.isAssignOp())
                        newValue = voptState.inputValue;
                    else
                        newValue = doStringAssignOp(arg, vopt, voptState);
                } else if (voptState.type == Color.class) {
                    if(!voptState.inputValue.isEmpty()) {
                        boolean found = false;
                        try {
                            // Note, this may return null
                            newValue = ((ColorOption)voptState.opt)
                                    .decode(voptState.inputValue);
                            found = true;
                        } catch(NumberFormatException ex) { }
                        if(newValue == null) {
                            Field f;
                            try {
                                // maybe it's a known color name
                                f = Color.class.getField(voptState.inputValue);
                                if(f.getType().equals(Color.class)) {
                                    newValue = (Color)f.get(null);
                                    found = true;
                                }
                            } catch(IllegalArgumentException ex) {
                            } catch(IllegalAccessException ex) {
                            } catch(NoSuchFieldException ex) {
                            } catch(SecurityException ex) { }
                        }
                        if(!found)
                            setCommandError("Not a color: " + voptState.inputValue);
                    }
                } else if(voptState.type == EnumSet.class) {
                    if(!voptState.op.isAssignOp())
                        newValue = voptState.inputValue;
                    else {
                        // pretend the curValue is a string...
                        EnumSet oldCurValue = (EnumSet)voptState.curValue;
                        EnumSetOption esOpt = (EnumSetOption)voptState.opt;
                        voptState.curValue = esOpt.encode(oldCurValue);
                        newValue = doStringAssignOp(arg, vopt, voptState);
                        voptState.curValue = oldCurValue;
                    }
                } else {
                    assert false : "Type " + voptState.type.getSimpleName()
                                    + " not handled";
                }
            }
        }
        return newValue;
    }

    private static String doStringAssignOp(String arg, VimOption vopt,
                                           VimOptionState voptState)
            throws SetCommandException
    {
        // See vim's option.c...
        String origval = (String)voptState.curValue;
        String newval = voptState.inputValue;
        boolean adding = voptState.op.isAdd();
        boolean prepending = voptState.op.isPre();
        boolean removing = voptState.op.isSub();

        // System.err.printf("doStringAssignOp '%s'\n\t%s\n\t%s\n",
        //                   voptState.op.getToken(),origval, newval);

        // locate newval[] in origval[] when removing it
        // and when adding to avoid duplicates
        int i = 0;
        int s = 0;
        if(removing || vopt.f(F.NODUP)) {
            i = newval.length();
            int bs = 0;
            for(s = 0; s < origval.length(); s++)
            {
                if((!vopt.f(F.COMMA)
                            || s == 0
                            || origval.charAt(s - 1) == ',' && (bs & 1) == 0)
                        && origval.substring(s).startsWith(newval)
                        && (!vopt.f(F.COMMA)
                            || origval.length() == s + i
                            || origval.charAt(s + i) == ',')
                ) {
                    break;
                }
                // Count backslashes.  Only a comma with an
                // even number of backslashes before it is
                // recognized as a separator
                if(s > 0 && origval.charAt(s - 1) == '\\')
                    ++bs;
                else
                    bs = 0;
            }

            /* do not add if already there */
            if ((adding || prepending) && s < origval.length())
            {
                prepending = false;
                adding = false;
                newval = origval;
            }
        }

        // concatenate the two strings; add a ',' if
        // needed
        if (adding || prepending)
        {
            boolean comma = vopt.f(F.COMMA) && !origval.isEmpty()
                                             && !newval.isEmpty();
            if(adding) {
                newval = origval + (comma ? "," : "") + newval;
            } else {
                newval += (comma ? "," : "") + origval;
            }
        }

        // Remove newval[] from origval[]. (Note: "i" has
        // been set above and is used here).
        if (removing)
        {
            newval = origval;
            if(s < origval.length())
            {
                // may need to remove a comma
                if(vopt.f(F.COMMA)) {
                    if(s == 0) {
                        // include comma after string
                        if(s + i < origval.length()
                                && origval.charAt(s + i) == ',')
                            ++i;
                    } else {
                        // include comma before string
                        --s;
                        ++i;
                    }
                }
                newval = origval.substring(0, s)
                            + origval.substring(s + i);
            }
        }

        if(vopt.f(F.FLAGLIST))
        {
            // remove flags that appear twice
            StringBuilder sb = new StringBuilder(newval);
            for(s = 0; s < sb.length(); s++) {
                if((!vopt.f(F.COMMA) || sb.charAt(s) != ',')
                    && Util.vim_strchr(sb, s + 1, sb.charAt(s)) >= 0)
                {
                    sb.deleteCharAt(s);
                    --s; // since it is about to be incremented
                }
            }
            if(sb.length() != newval.length())
                newval = sb.toString();
        }

        ///// STRANGE, may need some day...
        ///// if (save_arg != NULL)   /* number for 'whichwrap'
        /////     arg = save_arg;

        // System.err.println("\t"+newval);

        return newval;
    }

    @SuppressWarnings({"unchecked", "unchecked"})
    private static String formatDisplayValue(VimOption vopt,
                                             VimOptionState voptState)
    {
        Object value = voptState.curValue;
        StringBuilder sb = new StringBuilder();
        if (boolean.class == voptState.type) {
            sb.append(((Boolean)value).booleanValue() ? "  " : "no")
              .append(vopt.fullName);
        } else {
            sb.append("  ").append(vopt.fullName).append('=');
            if (int.class == voptState.type
                || String.class == voptState.type) {
                sb.append(value);
            } else if(Color.class == voptState.type) {
                sb.append(voptState.opt.getValueAsString(value)); // unchecked
                if(value != null) {
                    Color c = (Color)value;
                    sb.append(String.format("[r=%x,g=%x,b=%x]",
                                        c.getRed(), c.getGreen(), c.getBlue()));
                }
            } else if(EnumSet.class == voptState.type) {
                sb.append(voptState.opt.getValueAsString(value)); // unchecked
            } else {
                assert false : value.getClass().getSimpleName() + " not handled";
            }
        }
        return sb.toString();
    }

    private static final int COL = 80;
    private static final int INC = 20;
    private static final int GAP = 3;

    private static void displayOptions(List<String> eventArgs)
    {
        boolean all = eventArgs.size() == 1 && "all".equals(eventArgs.get(0));
        List<String> l = new ArrayList<String>(50);
        List<String> l2 = new ArrayList<String>();
        for (VimOption vopt : VimOption.getAllUser()) {
            VimOptionState voptState = determineOptionState(vopt, null);
            boolean isDefaultValue = false;
            if(!all) {
                isDefaultValue = voptState.curValue == null
                     ? voptState.opt.getDefault() == null
                     : voptState.curValue.equals(voptState.opt.getDefault());
            }
            if(all || !isDefaultValue) {
                String s = formatDisplayValue(vopt, voptState);
                (s.length() < INC - GAP ? l : l2).add(s);
            }
        }
        ViOutputStream osa =
                ViManager.createOutputStream(null, ViOutputStream.OUTPUT, null);
        int cols = COL / INC;
        int rows = (l.size() + cols - 1) / cols;
        StringBuilder sb = new StringBuilder(85);
        osa.println("");

        for(int row = 0; row < rows; row++) {
            for(int i = row; i < l.size(); i += rows) {
                String s = l.get(i);
                sb.append(s);
                for(int j = s.length(); j < INC; j++) {
                    sb.append(' ');
                }
            }
            osa.println(sb.toString());
            sb.setLength(0);
        }

        for(String s : l2) {
            osa.println(s);
        }

        osa.close();
    }

    /**
     * Some options (for example w_p_wrap) are per window; however the platform
     * (NB) may support it only per buffer. So if the user changes it with set,
     * then the variable in any other window that shares the buffer must be
     * updated. The value to sync is taken from curwin.
     * @param varName the variable to sync
     * @param buf the buffer to check for
     */
    public static void syncTextViewInstances(String varName, TextView tv)
    {
        assert tv == G.curwin(); // NEEDSWORK: since determine option state assumes
        VimOption vopt = VimOption.lookupVarName(varName);
        // if var is not window then nothing to do
        if(!vopt.isWin())
            return;

        ViBuffer buf = tv.getBuffer();

        for(ViTextView tv01 : ViManager.getFactory().getViTextViewSet()) {
            if(tv01.getBuffer() != buf || tv01 == tv)
                continue;
            if(G.dbgOptions().getBoolean())
                G.dbgOptions().println("syncInstances: " + varName + " in " + tv01);
            setLocalOption(tv01, vopt);
        }
    }

    public static void syncAllInstances(String varName)
    {
        VimOption vopt = VimOption.lookupVarName(varName);
        if(vopt.isLocal()) {
            Set<? extends ViOptionBag> set =
                    vopt.isWin()
                    ? ViManager.getFactory().getViTextViewSet()
                    : ViManager.getFactory().getBufferSet();
            for(ViOptionBag bag : set) {
                if(G.dbgOptions().getBoolean()) {
                    G.dbgOptions().printf("syncInstances: %s in %s\n",
                                      varName, bag);
                }
                setLocalOption(bag, vopt);
            }
        }
    }

    private static void setLocalOption(ViOptionBag bag, VimOption vopt)
    {
        VimOptionState voptState = determineOptionState(vopt, null);
        try {
            voptState.f.set(bag, voptState.curValue);
        } catch (IllegalArgumentException ex) {
            LOG.log(Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
    }
}
