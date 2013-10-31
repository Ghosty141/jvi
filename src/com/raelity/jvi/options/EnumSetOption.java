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
package com.raelity.jvi.options;

import java.beans.PropertyVetoException;
import java.lang.ref.WeakReference;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.raelity.jvi.core.Options;

/**
 *
 * @author Ernie Rael <err at raelity.com>
 */
public class EnumSetOption extends Option<EnumSet> {
    private final Class<Enum> enumType;
    static final Logger LOG = Logger.getLogger(EnumSetOption.class.getName());
    WeakReference<Map<String,Enum>> refNameEnumMap;

    public EnumSetOption(Class<EnumSet> optionType, Class<Enum> enumType,
                         String key, EnumSet defaultValue,
                         Validator<EnumSet> valid)
    {
        super(optionType, key, defaultValue,
              valid != null ? valid : new DefaultEnumSetValidator<EnumSet>(key));
        this.enumType = enumType;
        super.initialize();
    }

    @Override
    void initialize()
    {
        // can't initialize until enumType is setup
    }



    public boolean contains(Enum e)
    {
        assert enumType == e.getDeclaringClass();
        return getValue().contains(e);
    }

    @SuppressWarnings("unchecked")
    public EnumSet getEmpty()
    {
        return EnumSet.noneOf(enumType);
    }

    @Override
    final EnumSet getValueFromString(String sVal)
    {
        return decode(sVal);
    }

    @Override
    final String getValueAsString(EnumSet val)
    {
        return encode(val);
    }

    @SuppressWarnings("unchecked")
    public EnumSet decode(String input)
    {
        Map<String, Enum> map = getNameEnumMap();
        @SuppressWarnings("unchecked")
        EnumSet set = EnumSet.noneOf(enumType);
        String[] vals = input.split("[,\\s]+");
        for(String s : vals) {
            Enum e = map.get(s);
            if(e != null)
                set.add(e);
            else
                LOG.log(Level.SEVERE, "EnumSet::decode {0} does not define {1}",
                        new Object[]{enumType.getName(), s});
        }

        return set;
    }

    public String encode(EnumSet set)
    {
        StringBuilder sb = new StringBuilder();

        for(Iterator it = set.iterator(); it.hasNext();) {
            Object e = it.next();
            sb.append(e.toString());
            if(it.hasNext())
                sb.append(',');
        }

        return sb.toString();
    }

    private String[] getAllStringValues()
    {
        Enum[] eVals = getAvailableValues();
        String[] sVals = new String[eVals.length];

        for(int i = 0; i < eVals.length; i++) {
            sVals[i] = eVals[i].toString();
        }
        return sVals;
    }

    public Enum[] getAvailableValues()
    {
        @SuppressWarnings("unchecked")
        EnumSet set = EnumSet.allOf(enumType);
        Enum[] vals = new Enum[set.size()];
        int i = 0;
        for(Object e : set) {
            vals[i++] = (Enum)e;
        }
        return vals;
    }

    private Map<String, Enum> getNameEnumMap() {
        Map<String, Enum> map = refNameEnumMap == null
                                ? null : refNameEnumMap.get();
        if(map == null) {
            @SuppressWarnings("unchecked")
            EnumSet set = EnumSet.allOf(enumType);
            map = new HashMap<String, Enum>(set.size());
            for(Object e : set) {
                map.put(e.toString(), (Enum)e);
            }
            refNameEnumMap = new WeakReference<Map<String, Enum>>(map);
        }
        return map;
    }

    @Override
    public void validate(Object val) throws PropertyVetoException {
        if(validator instanceof DefaultEnumSetValidator)
            ((DefaultEnumSetValidator)validator).validateAnyType(val);
        else
            super.validate(val);
    }

    /**
     * The default EnumSetValidator gives no error if the argument
     * is an EnumSet (unless it is the wrong type of EnumSet) since
     * by definition it only contains valid enums.
     *
     * If the argument is a string, then it parses it, and checks
     * that each value is in the enum.
     */
    public static class DefaultEnumSetValidator<T> extends Validator<T> {
        final String name;

        public DefaultEnumSetValidator(String name)
        {
            this.name = name;
        }
        @Override
        public void validate(T val) throws PropertyVetoException
        {
            validateAnyType(val);
        }

        @SuppressWarnings("unchecked")
        public void validateAnyType(Object val) throws PropertyVetoException
        {
            opt = Options.getOption(name);
            EnumSetOption esOpt = (EnumSetOption)opt;
            if(val instanceof EnumSet) {
                if(val.getClass() != opt.optionType)
                    reportPropertyVetoException(
                            "Invalid EnumSet class: "
                            + opt.getClass().getSimpleName(), val);
            } else if(val instanceof String) {
                Map<String, Enum> map = esOpt.getNameEnumMap();
                String[] vals = ((String)val).split("[,\\s]+");
                for(String s : vals) {
                    Enum e = map.get(s);
                    if(e == null) {
                        reportPropertyVetoException(
                                "Invalid value: " + s, val);
                    }
                }
            } else {
                reportPropertyVetoException(
                        "Must be either EnumSet or String not "
                        + val.getClass().getSimpleName(), val);
            }
        }

    }

}