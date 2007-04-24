package com.raelity.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.text.Segment;

public class RegExpJava extends RegExp
{
    public RegExpJava() {
    }
    public static String getDisplayName() {
        return "Java 1.4+ Regular Expression";
    }
    public static int patternType() {
        return RegExp.PATTERN_PERL5;
    }
    public static boolean canInstantiate() {
        return true;
    }
    public static String getAdaptedName() {
        return "java.util.regex.Pattern";
    }
    
    /** Pick up the underlying java.util.regex.Pattern */
    public Pattern getPattern() {
        return pat;
    }


    /**
     * Prepare this regular expression to use <i>pattern</i>
     * for matching. The string can begin with "(e?=x)" to
     * specify an escape character.
     */
    public void compile(String patternString, int compileFlags) 
    throws RegExpPatternError {

	int flags = Pattern.MULTILINE; // also treats end of string as newline
	flags |= ((compileFlags & RegExp.IGNORE_CASE) != 0)
	          ? Pattern.CASE_INSENSITIVE : 0;
	char fixupBuffer[];
	if(patternString.startsWith("(?e=")
		&& patternString.length() >= 6
		&& patternString.charAt(5) == ')') {
	    // change the escape character
	    fixupBuffer = patternString.toCharArray();
	    patternString = fixupEscape(fixupBuffer, 6, fixupBuffer[4]);
	} else if(escape != '\\') {
	    // change the escape character
	    patternString = fixupEscape(patternString.toCharArray(), 0, escape);
	}
	try {
	    pat = Pattern.compile(patternString, flags);
	} catch (PatternSyntaxException e) {
            throw new RegExpPatternError(e.getDescription(), e.getIndex(), e);
        }
    }

    public boolean search(String input) {
        return search(input, 0);
    }

    public boolean search(String input, int start) {
        Matcher m = pat.matcher(input);
        m.useAnchoringBounds(false); //so /^ only matches beginning of line
        matched = m.find();
        result = new RegExpResultJava(matched ? m : null);
        if(start(0) >= start + len) {
            matched = false; // see comment in next method
        }
        return matched;
    }

    public boolean search(char[] input, int start, int len) {
	MySegment s = new MySegment(input, 0, input.length);
        //System.err.print("\tstart: " + start + ", len: " + len);
        Matcher m = pat.matcher(s).region(start, start+len);
        m.useAnchoringBounds(false); //so /^ only matches beginning of line
        //m.useTransparentBounds(true);
        matched = m.find();
        result = new RegExpResultJava(matched ? m : null);
        //System.err.println(matched ? " FOUND: " + start(0) + "," + stop(0):"");
        if(start(0) >= start + len) {
            //System.err.println("OUCH");
            // Given a line 'xy\n', with start: 1, len: 3
            // search the entire line
            //     search(input, 1, 3) returns true with start(0) == 1 (CORRECT)
            // search after the previous match, starting at the 'y'
            //     search(input, 2, 2) return true with start(0) == 4 (WRONG)
            // note that the start of the match is after the end of the input
            // pattern (end is "exclusive")
            matched = false;
        }
        return matched;
    }

    public RegExpResult getResult() {
        return result;
    }
    public int nGroup() {
        return result.nGroup();
    }
    public String group(int i) {
        return result.group(i);
    }
    public int length(int i) {
        return result.length(i);
    }
    public int start(int i) {
        return result.start(i);
    }
    public int stop(int i) {
        return result.stop(i);
    }
    private RegExpResultJava result;
    private Pattern pat;
    
    /** To support jdk1.5, need a segment that isa CharSequence */
    public static class MySegment extends Segment implements CharSequence {
        public MySegment() {
            super();
        }
        
        public MySegment(char[] array, int offset, int count) {
            super(array, offset, count);
        }
        
        public char charAt(int index) {
            if (index < 0 
                || index >= count) {
                throw new StringIndexOutOfBoundsException(index);
            }
            return array[offset + index];
        }

        public int length() {
            return count;
        }

        public CharSequence subSequence(int start, int end) {
            if (start < 0) {
                throw new StringIndexOutOfBoundsException(start);
            }
            if (end > count) {
                throw new StringIndexOutOfBoundsException(end);
            }
            if (start > end) {
                throw new StringIndexOutOfBoundsException(end - start);
            }
            MySegment segment = new MySegment();
            segment.array = this.array;
            segment.offset = this.offset + start;
            segment.count = end - start;
            return segment;
        }
    }
}
