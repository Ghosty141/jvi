/*
 * Buffer.java
 *
 * Created on March 5, 2007, 11:23 AM
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

package com.raelity.jvi;

import com.raelity.text.RegExp;
import com.raelity.text.RegExpJava;
import com.raelity.text.TextUtil.MySegment;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.event.DocumentEvent;

import static com.raelity.jvi.Constants.*;

/**
 * Buffer: structure that holds information about one file, primarily
 * per file options.
 *
 * Several windows can share a single Buffer.
 *
 * @author erra
 */
public abstract class Buffer implements ViBuffer, ViOptionBag {
    private boolean didCheckModelines;
    
    private int share; // the number of text views sharing this buffer
    public int getShare() { return share; }
    public void addShare() { share++; }
    public void removeShare() {
        share--;
    }
    
    /** Creates a new instance of Buffer, initialize values from Options.
     * NOTE: tv is not completely "constructed".
     */
    public Buffer(ViTextView tv) {
        b_visual_start = createMark();
        b_visual_end = createMark();
        b_op_start = createMark();
        b_op_end = createMark();

        initOptions();
    }
    
    protected void initOptions() {
        b_p_ts = Options.getOption(Options.tabStop).getInteger();
        b_p_sw = Options.getOption(Options.shiftWidth).getInteger();
        b_p_et = Options.getOption(Options.expandTabs).getBoolean();
        b_p_tw = Options.getOption(Options.textWidth).getInteger();
    }

    public void viOptionSet(ViTextView tv, String name) {
    }
    
    /** from switchto */
    public void activateOptions(ViTextView tv) {
    }
    
    /** from switchto, everything else has been setup */
    public void checkModeline() {
        if(didCheckModelines)
            return;
        didCheckModelines = true;
        Options.processModelines();
    }
    
    //////////////////////////////////////////////////////////////////////
    //
    // Declare the variables referenced as part of a ViOptionBag
    //
    
    public int b_p_ts;     // tab stop
    public int b_p_sw;     // shiftw width
    public boolean b_p_et;     // expand tabs
    public int b_p_tw;     // text width
    
    //////////////////////////////////////////////////////////////////////
    //
    // Other per buffer variables
    //

    public ViMark getMark(int i) {
        if(marks[i] == null)
            marks[i] = createMark();
        return marks[i];
    }
    // The lower case marks
    private ViMark marks[] = new ViMark[26];
    
    // Save the current VIsual area for '< and '> marks, and "gv"
    public final ViMark b_visual_start;
    public final ViMark b_visual_end;
    public char b_visual_mode;
    public String b_p_mps; // used in nv_object

    // start and end of an operator, also used for '[ and ']
    public final ViMark b_op_start;
    public final ViMark b_op_end;

    //////////////////////////////////////////////////////////////////////
    //
    //

    protected String getRemovedText(DocumentEvent e) {
        return null;
    }

    protected boolean isInsertMode() {
        return (G.State & BASE_STATE_MASK) == INSERT;
    }

    protected void docInsert(int offset, String s) {
        GetChar.docInsert(offset, s);
    }

    protected void docRemove(int offset, int length, String s) {
        GetChar.docRemove(offset, length, s);

    }

    //////////////////////////////////////////////////////////////////////
    //
    // Visual Mode and Highlight Search document algorithms
    //

    ////////////////////
    //
    // Visual Mode
    //


    private VisualBounds vb = new VisualBounds();

    public VisualBounds getVisualBounds() {
        return vb;
    }
    
    /** Calculate the 4 boundary points for a visual selection.
     * NOTE: in block mode, startOffset or endOffset may be off by one,
     *       but they should not be used, left/right are correct.
     * <p>
     * NEEDSWORK: cache this by listening to all document/caret changes OR
     *            if only called when update is needed, then no problem
     * </p><p>
     * NEEDSWORK: revisit to include TAB logic (screen.c:768 wish found sooner)
     */
    public class VisualBounds {
        private char visMode;
        private int startOffset, endOffset;
        // following are line and column information
        private int startLine, endLine;
        private int left, right; // column numbers (not line offset, consider TAB)
        private int wantRight; // either MAXCOL or same as right
        private boolean valid; // the class may not hold valid info
        
        private MutableInt from1 = new MutableInt();
        private MutableInt to1 = new MutableInt();
        private MutableInt from2 = new MutableInt();
        private MutableInt to2 = new MutableInt();
        

        public char getVisMode() {
            return visMode;
        }

        public int getStartLine() {
            return startLine;
        }

        public int getEndLine() {
            return endLine;
        }

        public int getLeft() {
            return left;
        }

        public int getRight() {
            return right;
        }

        /*VisualBounds(boolean init) {
            if(init && G.VIsual_active) {
                init(G.VIsual_mode, G.VIsual, getWCursor().copy());
            }
        }
        
        /*VisualBounds(int mode, ViFPOS startPos, ViFPOS cursorPos) {
            init(mode, startPos, cursorPos);
        }
        
        void init() {
            valid = false;
            if(G.VIsual_active) {
                init(G.VIsual_mode, G.VIsual, getWCursor().copy());
            }
        }*/

        public void clear() {
            valid = false;
        }

        //void init(char visMode, ViFPOS startPos, ViFPOS cursorPos) {
        public void init(char visMode, ViFPOS startPos, ViFPOS cursorPos,
                         boolean wantMax) {
            ViFPOS start, end; // start.offset less than end.offset
            
            this.visMode = visMode;
            
            if(startPos.compareTo(cursorPos) < 0) {
                start = startPos;
                end = cursorPos;
            } else {
                start = cursorPos;
                end = startPos;
            }
            startOffset = start.getOffset();
            endOffset = end.getOffset();
            
            startLine = start.getLine();
            endLine = end.getLine();
            
            //
            // set left/right columns
            //
            if(visMode == (0x1f & (int)('V'))) { // block mode
                // comparing this to screen.c,
                // this.start is from1,to1
                // this.end   is from2,to2
                // this is pretty much verbatim from screen.c:782
                
                int from1,to1,from2,to2;
                Misc.getvcol(Buffer.this, start, this.from1, null, this.to1);
                from1 = this.from1.getValue();
                to1 = this.to1.getValue();
                Misc.getvcol(Buffer.this, end, this.from2, null, this.to2);
                from2 = this.from2.getValue();
                to2 = this.to2.getValue();
                
                if(from2 < from1)
                    from1 = from2;
                if(to2 > to1) {
                    if(G.p_sel.charAt(0) == 'e' && from2 - 1 >= to1)
                        to1 = from2 - 1;
                    else
                        to1 = to2;
                }
                to1++;
                left = from1;
                right = to1;
                wantRight = wantMax ? MAXCOL : right;
            } else {
                left = start.getColumn();
                right = end.getColumn();
                if(left > right) {
                    int t = left;
                    left = right;
                    right = t;
                }
                
                // if inclusive, then include the end
                if(G.p_sel.charAt(0) == 'i') {
                    endOffset++;
                    right++;
                }
                wantRight = right;
            }
            
            this.valid = true;
        }
    }
    
    // NEEDSWORK: OPTIMIZE: re-use blocks array
    public int[] calculateVisualBlocks(VisualBounds vb,
            int startOffset,
            int endOffset) {
        if(!vb.valid)
            return new int[] { -1, -1};
        
        int[] newHighlight = null;
        if (vb.visMode == 'V') { // line selection mode
            // make sure the entire lines are selected
            newHighlight = new int[] { getLineStartOffset(vb.startLine),
            getLineEndOffset(vb.endLine),
            -1, -1};
        } else if (vb.visMode == 'v') {
            newHighlight = new int[] { vb.startOffset,
            vb.endOffset,
            -1, -1};
        } else if (vb.visMode == (0x1f & 'V')) { // visual block mode
            int startLine = getLineNumber(startOffset);
            int endLine = getLineNumber(endOffset -1);
            
            if(vb.startLine > endLine || vb.endLine < startLine)
                newHighlight = new int[] { -1, -1};
            else {
                startLine = Math.max(startLine, vb.startLine);
                endLine = Math.min(endLine, vb.endLine);
                newHighlight = new int[(((endLine - startLine)+1)*2) + 2];
                
                MutableInt left = new MutableInt();
                MutableInt right = new MutableInt();
                int i = 0;
                for (int line = startLine; line <= endLine; line++) {
                    int offset = getLineStartOffset(line);
                    int len = getLineEndOffset(line) - offset;
                    if(getcols(line, vb.left, vb.wantRight, left, right)) {
                        newHighlight[i++] = offset + Math.min(len, left.getValue());
                        newHighlight[i++] = offset + Math.min(len, right.getValue());
                    } else {
                        newHighlight[i++] = offset + Math.min(len, vb.left);
                        newHighlight[i++] = offset + Math.min(len, vb.wantRight);
                    }
                }
                newHighlight[i++] = -1;
                newHighlight[i++] = -1;
            }
        } else {
            throw new IllegalStateException("Visual mode: "+ G.VIsual_mode +" is not supported");
        }
        return newHighlight;
    }
    
    /** This is the inverse of getvcols, given startVCol, endVCol determine
     * the cols of the corresponding chars so they can be highlighted. This means
     * that things can look screwy when there are tabs in lines between the first
     *and last lines, but that's the way it is in swing.
     * NEEDSWORK: come up with some fancy painting for half tab highlights.
     */
    private boolean getcols(int lnum,
            int vcol1, int vcol2,
            MutableInt start, MutableInt end) {
        int incr = 0;
        int vcol = 0;
        int c1 = -1, c2 = -1;
        
        int ts = b_p_ts;
        MySegment seg = getLineSegment(lnum);
        int col = 0;
        for (int ptr = seg.offset; ; ++ptr, ++col) {
            char c = seg.array[ptr];
            // A tab gets expanded, depending on the current column
            if (c == TAB)
                incr = ts - (vcol % ts);
            else {
                //incr = CHARSIZE(c);
                incr = 1; // assuming all chars take up one space except tab
            }
            vcol += incr;
            if(c1 < 0 && vcol1 < vcol)
                c1 = col;
            if(c2 < 0 && (vcol2 -1) < vcol)
                c2 = col + 1;
            if(c1 >= 0 && c2 >= 0 || c == '\n')
                break;
        }
        if(start != null)
            start.setValue(c1 >= 0 ? c1 : col);
        if(end != null)
            end.setValue(c2 >= 0 ? c2 : col);
        return true;
    }

    ////////////////////
    //
    // Highlight Search
    //
    
    Pattern highlightSearchPattern;
    // Use MySegment for 1.5 compatibility
    MySegment highlightSearchSegment = new MySegment();
    int[] highlightSearchBlocks = new int[2];
    MutableInt highlightSearchIndex = new MutableInt();
    
    //
    // NEEDSWORK: should/could hide following, eg in getHighlightSearchBlocks
    //            was protected when in TextView
    //
    public void updateHighlightSearchCommonState() {
        highlightSearchBlocks = new int[20];
        RegExp re = Search.getLastRegExp();
        if(re instanceof RegExpJava) {
            highlightSearchPattern = ((RegExpJava)re).getPattern();
        }
    }
    
    public int[] getHighlightSearchBlocks(int startOffset, int endOffset) {
        highlightSearchIndex.setValue(0);
        if(highlightSearchPattern != null) {
            getSegment(startOffset, endOffset - startOffset, highlightSearchSegment);
            Matcher m = highlightSearchPattern.matcher(highlightSearchSegment);
            while(m.find()) {
                highlightSearchBlocks = addBlock(highlightSearchIndex,
                        highlightSearchBlocks,
                        m.start() + startOffset,
                        m.end() + startOffset);
            }
        }
        return addBlock(highlightSearchIndex, highlightSearchBlocks, -1, -1);
    }
    
    protected final int[] addBlock(MutableInt idx, int[] blocks,
            int start, int end) {
        int i = idx.getValue();
        if(i + 2 > blocks.length) {
            // Arrays.copyOf introduced in 1.6
            // blocks = Arrays.copyOf(blocks, blocks.length +20);
            int[] t = new int[blocks.length + 20];
            System.arraycopy(blocks, 0, t, 0, blocks.length);
            blocks = t;
        }
        blocks[i] = start;
        blocks[i+1] = end;
        idx.setValue(i + 2);
        return blocks;
    }
    
    public static void dumpBlocks(String tag, int[] b) {
        System.err.print(tag + ":");
        for(int i = 0; i < b.length; i += 2)
            System.err.print(String.format(" {%d,%d}", b[i], b[i+1]));
        System.err.println("");
    }
    
}

// vi: set sw=4 ts=8:
