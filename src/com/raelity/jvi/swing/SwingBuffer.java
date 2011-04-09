/*
 * SwingBuffer.java
 * 
 * Created on Jul 5, 2007, 5:32:05 PM
 * 
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package com.raelity.jvi.swing;

import com.raelity.jvi.ViBadLocationException;
import com.raelity.jvi.ViFPOS;
import com.raelity.jvi.core.Buffer;
import com.raelity.jvi.core.Options;
import com.raelity.jvi.core.Util;
import com.raelity.jvi.manager.ViManager;
import com.raelity.jvi.ViMark;
import com.raelity.jvi.ViTextView;
import com.raelity.jvi.options.Option;
import com.raelity.text.TextUtil.MySegment;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;
import javax.swing.text.DocumentFilter.FilterBypass;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;

/**
 * The swing implementation of jVi's ViBuffer.
 * <p/>
 * This class has a DocumentListener; as a convenience for subclasses,
 * the listener calls protected methods
 *      documentChangeUpdate, documentInsertUpdate, documentRemoveUpdate
 * after this class has finished its processing.
 * <p/>
 * There is also a DocumentFilter which calls protected methods
 *      filterInsertString, filterRemove, filterReplace
 * However, the argument DocumentFilter.FilterBypass is *not* provided.
 *
 * @author erra
 */
abstract public class SwingBuffer extends Buffer
{
    private static final
            Logger LOG = Logger.getLogger(SwingBuffer.class.getName());
    private Document doc;

    public SwingBuffer(ViTextView tv) {
        super(tv);
        doc = ((JTextComponent)tv.getEditorComponent()).getDocument();
        startDocumentEvents();
    }

    @Override
    public void removeShare() {
        super.removeShare();
        if(getShare() == 0) {
            stopDocumentEvents();
            doc = null;
        }
    }

    //////////////////////////////////////////////////////////////////////
    //
    // ViBuffer interface
    // 
    
    @Override
    public int getLineNumber(int offset) {
        return getElemIndex(offset) + 1;
    }
    
    @Override
    public int getColumnNumber(int offset) {
        Element elem = getElem(offset);
        return offset - elem.getStartOffset();
    }
    
    /** @return the start offset of the line */
    @Override
    public int getLineStartOffset(int line) {
        return getLineElement(line).getStartOffset();
    }
    
    /** @return the end offset of the line, past newline char */
    @Override
    public int getLineEndOffset(int line) {
        return getLineElement(line).getEndOffset();
    }

    /** @return the end offset of the line, past newline char (not past eof) */
    @Override
    public int getLineEndOffset2(int line) {
        int offset = getLineElement(line).getEndOffset();
        if(offset > getDocument().getLength())
            offset--; //offset = getDocument().getLength();
        return offset;
    }
    
    @Override
    public int getLineStartOffsetFromOffset(int offset) {
        Element elem = getElem(offset);
        return elem.getStartOffset();
    }
    
    @Override
    public int getLineEndOffsetFromOffset(int offset) {
        Element elem = getElem(offset);
        return elem.getEndOffset();
    }
    
    @Override
    public int getLineCount() {
        return getDocument().getDefaultRootElement().getElementCount();
    }

    @Override
    public int getLength() {
        return getDocument().getLength();
    }

    /*final public MySegment getLineSegment(int lnum) {
        return getLineSegment(lnum); // CACHE
    }*/
    
    /*final public Element getLineElement(int lnum) {
        return getLineElement(lnum); // CACHE
    }*/
    
    @Override
    final public MySegment getSegment(int offset, int length, MySegment seg) {
        if(seg == null)
            seg = new MySegment();
        try {
            getDocument().getText(offset, length, seg);
        } catch (BadLocationException ex) {
            seg.count = 0;
            LOG.log(Level.SEVERE, null, ex);
        }
        seg.docOffset = offset;
        seg.first();
        return seg;
    }

    @Override
    public boolean isGuarded(int offset) {
        return false;
    }



    
    private boolean isEditable() { return true; }

    /**
     * Use the document in default implementation.
     * @return Swing Document backing this EditorPane */
    @Override
    public Document getDocument() {
        return doc;
    }

    @Override
    public void replaceString(int start, int end, String s) {
        if( ! isEditable()) {
            Util.vim_beep();
            return;
        }
        try {
            if(start != end) {
                getDocument().remove(start, end - start);
            }
            getDocument().insertString(start, s, null);
        } catch(BadLocationException ex) {
            processTextException(ex);
        }
    }
    
    @Override
    public void deleteChar(int start, int end) {
        if( ! isEditable()) {
            Util.vim_beep();
            return;
        }
        try {
            getDocument().remove(start, end - start);
        } catch(BadLocationException ex) {
            processTextException(ex);
        }
    }

    @Override
    public void insertText(int offset, String s) {
        if( ! isEditable()) {
            Util.vim_beep();
            return;
        }
    /* ******************************************
    setCaretPosition(offset);
    ops.xop(TextOps.INSERT_TEXT, s);
     ******************************************/
        if(offset > getDocument().getLength()) {
            // Damn, trying to insert after the final magic newline.
            // 	(the one that gets counted in elem.getEndOffset() but
            // 	not in getLength() )
            // Take the new line from the end of the string and put it
            // at the beging, e.g. change "foo\n" to "\nfoo". Then set
            // offset to length. The adjusted string is inserted before
            // the magic newline, this gives the correct result.
            // If there is no newline at the end of the string being inserted,
            // then there will end up being a newline added to the file magically,
            // but this shouldn't really matter.
            StringBuilder new_s = new StringBuilder();
            new_s.append('\n');
            if(s.endsWith("\n")) {
                if(s.length() > 1) {
                    new_s.append(s.substring(0,s.length()-1));
                }
            } else {
                new_s.append(s);
            }
            offset = getDocument().getLength();
            s = new_s.toString();
        }
        try {
            getDocument().insertString(offset, s, null);
        } catch(BadLocationException ex) {
            processTextException(ex);
        }
    }
    
    @Override
    public void replaceChar(int offset, char c) {
        if( ! isEditable()) {
            Util.vim_beep();
            return;
        }
        String s = String.valueOf(c);
        
        try {
            // This order works better with the do-again, '.', buffer
            getDocument().insertString(offset, s, null);
            getDocument().remove(offset + 1, 1);
        } catch(BadLocationException ex) {
            processTextException(ex);
        }
    }

    @Override
    public String getText(int offset, int length) throws ViBadLocationException {
        try {
            return getDocument().getText(offset, length);
        } catch(BadLocationException ex) {
            throw new SwingBadLocationException(ex);
        }
    }
    
    protected void processTextException(BadLocationException ex) {
        Util.vim_beep();
    }


    @Override
    public void reindent(int line, int count) {
        System.err.format("reindent line %d, count %d", line, count);
        Util.vim_beep();
    }

    @Override
    public void reformat(int line, int count) {
        System.err.format("reformat line %d, count %d", line, count);
        Util.vim_beep();
    }


    
    //////////////////////////////////////////////////////////////////////
    //
    // Marks
    //
    
    @Override
    public ViMark createMark(ViFPOS fpos) {
        ViMark m = new Mark(this);
        if(fpos != null)
            m.setMark(fpos);
        return m;
    }

    @Override
    public ViMark createMark(int offset, BIAS bias)
    {
        return new BiasMark(offset, bias);
    }
    
    static final Position INVALID_MARK_LINE = new Position() {
        @Override
        public int getOffset() {
            return 0;
        }
    };
    
    //////////////////////////////////////////////////////////////////////
    //
    // The "cache"
    //
    
    final static private boolean cacheDisabled = false;
    
    public static Option cacheTrace
            = Options.getOption(Options.dbgCache);
    
    /** @return the element index from root which contains the offset */
    protected int getElemIndex(int offset) {
        Element root = getDocument().getDefaultRootElement();
        return root.getElementIndex(offset);
    }
    
    /** @return the element which contains the offset */
    protected Element getElem(int offset) {
        Element elem = getCurrentLineElement(); // CACHE
        if(elem != null
                && elem.getStartOffset() <= offset
                && offset < elem.getEndOffset()) {
            return elem;
        }
        //Element root = getDocument().getDefaultRootElement();
        //return root.getElement(root.getElementIndex(offset));
        int line = getDocument().getDefaultRootElement().getElementIndex(offset) + 1;
        return getLineElement(line);
    }

    ElemCache getElemCache(int offset) {
        getElem(offset);
        return elemCache;
    }
    
    private class PositionSegment extends MySegment {
        /** The line number of the segment. The first line is numbered at 1.
         * This is negative if the
         * segment does not correspond to a single line.
         */
        public int line;
    }
    
    /** the segment cache */
    private PositionSegment segment = new PositionSegment();
    // private Segment tempSegment = new Segment();
    
    /** @return the positionsegment for the indicated line */
    @Override
    final public MySegment getLineSegment(int line) {
        if(cacheDisabled || segment.count == 0 || segment.line != line) {
            if(cacheTrace.getBoolean())System.err.println("Miss seg: " + line);
            try {
                Element elem = getLineElement(line);
                getDocument().getText(elem.getStartOffset(),
                        elem.getEndOffset() - elem.getStartOffset(),
                        segment);
                segment.docOffset = elem.getStartOffset();
                segment.line = line;
        /* **************************************************
        int len = Math.max(80, tempSegment.count + 10);
        if(segment.array == null || segment.array.length < len) {
          segment.array = new char[len];
        }
        System.arraycopy(tempSegment.array, tempSegment.offset,
                         segment.array, 0, tempSegment.count);
        segment.count = tempSegment.count;
         **************************************************/
                // segment.offset is always zero
            } catch(BadLocationException ex) {
                segment.count = 0;
                LOG.log(Level.SEVERE, null, ex);
            }
        } else {
            if(cacheTrace.getBoolean())System.err.println("Hit seg: " + line);
        }
        //return segment;
        MySegment s = new MySegment(segment);
        s.first();
        return s;
    }
    
    private void invalidateLineSegment() {
        if(cacheTrace.getBoolean())System.err.println("Inval seg:");
        segment.count = 0;
    }

    static class ElemCache {
        int line;
        Element elem;
    }

    @Override
    public CharSequence getDocumentCharSequence(int start, int end)
    {
        return new DocumentCharSequence(getDocument(), start, end);
    }
    
    private ElemCache elemCache = new ElemCache();
    /** the element cache */
    private Element element;
    /** the line number corresponding to the element cache (0 based). */
    private int elementLine;
    /** @return the element for the indicated line */
    final public Element getLineElement(int line) {
        line--;
        if(cacheDisabled || element == null || elementLine != line) {
            if(cacheTrace.getBoolean())System.err.println("Miss elem: " + (line+1));
            element = getDocument().getDefaultRootElement().getElement(line);
            elementLine = line;
            elemCache.line = line+1;
            elemCache.elem = element;
        } else {
            if(cacheTrace.getBoolean())System.err.println("Hit elem: " + (line+1));
        }
        return element;
    }
    
    final public Element getCurrentLineElement() {
        return element;
    }
    
    private void invalidateElement() {
        if(cacheTrace.getBoolean())System.err.println("Inval elem:");
        element = null;
    }

    //////////////////////////////////////////////////////////////////////
    //
    // Document listeners
    // 
    // Track changes in document for possible inclusion into redo buf
    //

    private DocumentListener documentListener;

    // This listener is used for two purposes.
    //      1) Invalidate cached information about the document
    //      2) Provide information for magic redo tracking
    class DocListen implements DocumentListener {
        @Override
        public void changedUpdate(DocumentEvent e) {
            if(cacheTrace.getBoolean()) {
                System.err.println("doc changed: " + e.getOffset()
                                   + ":" + e.getLength() + " " + e);
                // System.err.println("element" + e.getChange());
            }
            // insertUpdate/removeUpdate fire as well so skip this one
            // invalidateData();
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            if(cacheTrace.getBoolean())
                System.err.println("doc insert: " + e.getOffset()
                                   + ":" + e.getLength() + " " + e);
            invalidateData();
            if(docChangeInfo != null) {
                docChangeInfo.isChange = true;
                docChangeInfo.offset = e.getOffset();
                docChangeInfo.length = e.getLength();
                docChangeInfo.isInsert = true;
            }
            
            // If not in insert mode, then no magic redo tracking
            if(!isInsertMode())
                return;
            String s = "";

            if(ViManager.getFactory().isEnabled()) {
                // magic redo tracking
                // things can get wierd in there...
                try {
                    s = getDocument().getText(e.getOffset(), e.getLength());
                    docInsert(e.getOffset(), s);
                } catch (Exception ex) {
                    LOG.log(Level.SEVERE, null, ex);
                }
            }
        }
        
        @Override
        public void removeUpdate(DocumentEvent e) {
            if(cacheTrace.getBoolean())
                System.err.println("doc remove: " + e.getOffset()
                                   + ":" + e.getLength() + " " + e);
            invalidateData();
            if(docChangeInfo != null) {
                docChangeInfo.isChange = true;
                docChangeInfo.offset = e.getOffset();
                docChangeInfo.length = e.getLength();
                docChangeInfo.isInsert = false;
            }

            // If not in insert mode, then no magic redo tracking
            if(!isInsertMode())
                return;

            if(ViManager.getFactory().isEnabled()) {
                // magic redo tracking
                // things can get wierd in there...
                try {
                    docRemove(e.getOffset(), e.getLength(), getRemovedText(e));
                } catch (Exception ex) {
                    LOG.log(Level.SEVERE, null, ex);
                }
            }
        }
    }

    protected void filterInsertString(int offset, String string,
                                      AttributeSet attr)
    {
    }

    protected void filterRemove(int offset, int length)
    {
    }

    protected void filterReplace(int offset, int length,
                           String text, AttributeSet attrs)
    {
    }

    //
    // CURRENTLY NOT USED
    //
    class FilterListen extends DocumentFilter {

        @Override
        public void insertString(FilterBypass fb, int offset, String string,
                                 AttributeSet attr)
        throws BadLocationException
        {
            filterInsertString(offset, string, attr);
            fb.insertString(offset, string, attr);
        }

        @Override
        public void remove(FilterBypass fb, int offset, int length)
        throws BadLocationException
        {
            filterRemove(offset, length);
            fb.remove(offset, length);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length,
                            String text, AttributeSet attrs)
        throws BadLocationException
        {
            filterReplace(offset, length, text, attrs);
            fb.replace(offset, length, text, attrs);
        }
    }

    private void stopDocumentEvents() {
        getDocument().removeDocumentListener(documentListener);
        documentListener = null;

        // NOT USED, see startDocumentEvents
        // if(getDocument() instanceof AbstractDocument) {
        //     ((AbstractDocument)getDocument()).setDocumentFilter(null);
        // }

    }
    
    private void startDocumentEvents() {
        documentListener = new DocListen();
        getDocument().addDocumentListener(documentListener);

        // WANTED THIS TO ANALIZE UNDO/REDO,
        // BUT THEY DO NOT TRIGGER FILTERABLE EVENTS
        // if(getDocument() instanceof AbstractDocument) {
        //     ((AbstractDocument)getDocument()).setDocumentFilter(
        //             new FilterListen());
        // }
    }
  
    private void invalidateData() {
        // NEEDSWORK: invalidateCursor(-1);
        invalidateLineSegment();
        invalidateElement();
    }
    
    /**
     * This method can be used to determine information about the
     * most recent document change. It clears the cached info, so it can
     * only be used once per change. It is typically use like:
     * <pre><code>
     *          createDocChangeInfo();
     *          undo(); // do an undo or redo action
     *          DocChangeInfo data = getDocChangeInfo(); // works once
     *          if(data.isChange) {...}
     * </code></pre>
     * The method itself has nothing to do with undo.
     * One use is to adjust the cursor position after undo/redo.
     *
     * @return info about last change, null if no change since
     *         last clearUndoChange.
     * was last called.
     */
    protected DocChangeInfo getDocChangeInfo()
    {
        DocChangeInfo t = docChangeInfo;
        docChangeInfo = null;
        return t;
    }

    protected void createDocChangeInfo()
    {
        docChangeInfo = new DocChangeInfo();
    }

    private DocChangeInfo docChangeInfo;
    
    // These variables track last insert/remove to document.
    // They are usually used for undo/redo issue by subclasses.
    protected class DocChangeInfo {
        /** did a change happen */
        public boolean isChange;
        /** last change was an insert */
        public boolean isInsert;
        /** doc offset of last change */
        public int offset;
        /** length of last change */
        public int length;
    }

    protected String getRemovedText(DocumentEvent e) {
        return null;
    }

    // getOffset is the only supported method
    private class BiasMark implements ViMark
    {
        Position p;
        BIAS bias;
        boolean atZero;

        public BiasMark(int offset, BIAS bias)
        {
            try {
                if(BIAS.BACK == bias) {
                    // swing is FORW bias so make a best effort
                    if(offset == 0)
                        atZero = true;
                    else
                        --offset;
                }
                p = getDocument().createPosition(offset);
            } catch(BadLocationException ex) {
                Logger.getLogger(SwingBuffer.class.getName()).
                        log(Level.SEVERE, null, ex);
                p = getDocument().getStartPosition();
            }
            this.bias = bias;
        }

        @Override
        public int getOffset()
        {
            return bias == BIAS.FORW ? p.getOffset()
                                     : atZero ? 0 : p.getOffset() + 1;
        }

        @Override
        public int getOriginalColumnDelta()
        {
            return 0;
        }

        @Override
        public Buffer getBuffer()
        {
            return SwingBuffer.this;
        }

        //
        // NOT SUPPORTED
        //

        @Override
        public void setMark(ViFPOS fpos)
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void invalidate()
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public boolean isValid()
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public int getLine()
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public int getColumn()
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void set(int line, int column)
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void set(int offset)
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void set(ViFPOS fpos)
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void setColumn(int col)
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void incColumn()
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void decColumn()
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void incLine()
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void decLine()
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void setLine(int line)
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public ViFPOS copy()
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void verify(Buffer buf)
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public int compareTo(ViFPOS o)
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public String toString()
        {
            ViFPOS p0 = getBuffer().createFPOS(getOffset()); // for toString
            return p0.toString();
        }

    }
}
