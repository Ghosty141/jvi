package com.raelity.jvi.swing;

import java.awt.EventQueue;
import java.awt.Point;

import javax.swing.JViewport;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;

/**
 * Stabilize (do not allow scrolling) the JViewport displaying
 * the indicated JTextComponent.
 * This is typically used when the underlying document may change while
 * being edited in another view. The {@link #stop} method is used to release
 * the listeners and so unfreeze the viewport.
 * <p>This is a one shot class. The editor is expected to be good to go.
 * Only document changes are listened to. The first char of the top line is
 * pinned to the upper left corner. If needed, this could be extended
 * to pin the horizontal position as well.
 */
public class FreezeViewport implements DocumentListener, ChangeListener
{

    private JTextComponent ep;
    private JViewport vp;
    private AbstractDocument doc;
    private Position pos;
    private int topLine;
    private int nLine;

    public FreezeViewport(JTextComponent ep)
    {
        this.ep = ep;
        Object o = ep.getDocument();
        if (!(o instanceof AbstractDocument)) {
            return;
        }
        doc = (AbstractDocument)ep.getDocument();
        try {
            doc.readLock();
            vp = (JViewport)ep.getParent(); // may throw class cast, its ok
            Element root = doc.getDefaultRootElement();
            nLine = root.getElementCount();
            // Get the offset of the first displayed char in the top line
            Point pt = vp.getViewPosition();
            int offset = ep.viewToModel2D(pt);
            // Determine the line number of the top displayed line
            topLine = root.getElementIndex(offset);
            //System.err.format("FreezeViewport: top %d\n", topLine);
            // Note. offset may not be first char, due to horiz scroll
            // make offset the first char of the line
            offset = root.getElement(topLine).getStartOffset();
            // Get marker to offset in the document
            pos = doc.createPosition(offset);
            setupDocListener();
            //vp.addChangeListener(this); // debug info
        } catch (Exception ex) {
            // Note: did not start listener
        } finally {
            doc.readUnlock();
        }
    }

    private void setupDocListener()
    {
        doc.addDocumentListener(this);
    }

    public void stop()
    {
        if (doc != null) {
            doc.removeDocumentListener(this);
        }
        if (vp != null) {
            vp.removeChangeListener(this);
        }
    }

    private void adjustViewport(int offset)
    {
        // Might be able to use info from DocumentEvent to optimize
        try {
            Point pt = ep.modelToView2D(offset).getBounds().getLocation();
            pt.translate(-pt.x, 0); // x <-- 0, leave a few pixels to left
            vp.setViewPosition(pt);
        } catch (Exception ex) {
            stop();
        }
        return;
    }

    private void handleChange(DocumentEvent e)
    {
        // Note while in listener document can't change, no read lock
        Element root = doc.getDefaultRootElement();
        int newNumLine = root.getElementCount();
        // return if line count unchanged or changed after our mark
        if (nLine == newNumLine || e.getOffset() > pos.getOffset()) {
            return;
        }
        nLine = newNumLine;
        int newTopLine = root.getElementIndex(pos.getOffset());
        //System.err.format("handleChange: old %d new %d\n", topLine, newTopLine);
        if (topLine == newTopLine) {
            return;
        }
        topLine = newTopLine;
        // make a move
        final int offset = root.getElement(topLine).getStartOffset();
        if (false && EventQueue.isDispatchThread()) {
            // false needed NB6.8
            adjustViewport(offset);
            //System.err.println("handleChange: adjust in dispatch");
        } else {
            EventQueue.invokeLater(new Runnable() {

                @Override
                public void run()
                {
                    adjustViewport(offset);
                    //System.err.println("handleChange: adjust later");
                }
            });
        }
    }

    @Override
    public void insertUpdate(DocumentEvent e)
    {
        handleChange(e);
    }

    @Override
    public void removeUpdate(DocumentEvent e)
    {
        handleChange(e);
    }

    @Override
    public void changedUpdate(DocumentEvent e)
    {
    }

    @Override
    public void stateChanged(ChangeEvent e)
    {
        Point pt = vp.getViewPosition();
        int offset = ep.viewToModel2D(pt);
        Element root = doc.getDefaultRootElement();
        int topl = root.getElementIndex(offset);
        System.err.println("Viewport stateChanged: top line " + topl);
    }
}
