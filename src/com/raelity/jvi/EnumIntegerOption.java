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

public class EnumIntegerOption extends IntegerOption implements EnumOption {
    Integer [] availableValues;
  
  EnumIntegerOption(String key, int defaultValue, Integer[] availableValues) {
    this(key, defaultValue, null, availableValues);
  }
  
  EnumIntegerOption(String key, int defaultValue,
          IntegerOption.Validator validator,
          Integer[] availableValues) {
    super(key, defaultValue, validator);
    this.availableValues = availableValues;
  }

    public Integer[] getAvailableValues() {
        return availableValues;
    }

}