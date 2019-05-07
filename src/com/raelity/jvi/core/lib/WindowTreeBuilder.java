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

package com.raelity.jvi.core.lib;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JViewport;
import javax.swing.SwingUtilities;

import org.openide.util.lookup.ServiceProvider;

import com.raelity.jvi.ViAppView;
import com.raelity.jvi.ViInitialization;
import com.raelity.jvi.ViOutputStream;
import com.raelity.jvi.ViTextView.Direction;
import com.raelity.jvi.ViTextView.Orientation;
import com.raelity.jvi.ViWindowNavigator;
import com.raelity.jvi.core.ColonCommands;
import com.raelity.jvi.core.ColonCommands.AbstractColonAction;
import com.raelity.jvi.core.ColonCommands.ColonEvent;
import com.raelity.jvi.core.G;
import com.raelity.jvi.core.TextView;
import com.raelity.jvi.manager.ViManager;

import static com.raelity.jvi.manager.ViManager.cid;

/**
 * The vim algorithm for traversing windows with the ^W_^W style commands is
 * based on the way that the windows are split. Here we build a tree that
 * reflects how the windows are split. A GUI component tree is traversed to
 * build a simple tree that only has splitter and editor nodes.
 *
 * A complication is that there may be multiple independent trees, where each
 * tree is rooted in its own java Window/Frame.
 *
 * The resulting tree's must be binary trees. For example a JPanel with
 * a grid of 4 editors would be a node with four children, the platform
 * specific code will have to break this down in to some binary tree.
 *
 * @author Ernie Rael <err at raelity.com>
 */
public abstract class WindowTreeBuilder implements ViWindowNavigator {
    private Set<ViAppView> toDo = new HashSet<ViAppView>();
    private List<ViAppView> sorted = new ArrayList<ViAppView>();
    private List<Node> roots = new ArrayList<Node>();
    private boolean didTreeInit;

    public WindowTreeBuilder(List<ViAppView> avs)
    {
        toDo.addAll(avs);
    }

    protected void initTree()
    {
        if(didTreeInit)
            return;
        didTreeInit = true;

        while(!toDo.isEmpty()) {
            Component c = windowForAppView(toDo.iterator().next());
            //allComps(c);
            Node root = buildTree(c);
            if(G.dbgWindowTreeBuilder().getBoolean()) G.dbgWindowTreeBuilder().println(dumpTree(root).toString());
            assert root != null;
            if(root == null)
                break;
            roots.add(root);
        }

        assert toDo.isEmpty();

        sortRoots(); // top-to-bottom then left-to-right

        for (Node root : roots) {
            addToSorted(root);
        }
    }

    private static boolean didInit;

    public static class Init implements ViInitialization
    {
        @Override
        public void init()
        {
            if(didInit)
                return;
            ColonCommands.register("dumpWin", "dumpWindowHierarchy",
                                   new DumpWin(),
                                   EnumSet.of(CcFlag.DBG));
            didInit = true;
        }
    }

    /**
     * This only does something the first timeit is called. Subsequent
     * calls return the same stuff.
     * @return app views in order for sequential Ctrl-W traversal
     */
    @Override
    final public List<ViAppView> getList()
    {
        initTree();

        return Collections.unmodifiableList(sorted);
    }

    // public static class Siblings
    // {
    //     public Orientation orientation;
    //     public int targetIndex;
    //     public List<ViAppView> siblings = new ArrayList<ViAppView>();
    // }

    // final public Siblings Siblings(ViAppView targetAv)
    // {
    //     initTree();

    //     Node n = findNode(targetAv);
    //     Node parent = n.getParent();
    //     Siblings s = new Siblings();
    //     s.orientation = parent.getOrientation();
    //     s.targetIndex = -1;
    //     for(int i = 0; i < parent.getChildren().size(); i++) {
    //         Node child = parent.getChildren().get(i);
    //         s.siblings.add(getAppView(child.getPeer()));
    //         if(n.equals(child))
    //             s.targetIndex = i;
    //     }
    //     assert parent.isSplitter();
    //     assert s.targetIndex >= 0;

    //     return s;
    // }



    @Override
    final public ViAppView getTarget(Direction dir, ViAppView fromAv, int n)
    {
        return getTarget(dir, fromAv, n, false);
    }

    @Override
    final public ViAppView getTarget(
            Direction dir, ViAppView fromAv, int n, boolean mustTouch)
    {
        initTree();

        if(n <= 0)
            n = 1;

        Node currentNode = findNode(fromAv);
        if(currentNode == null)
            return null;

        Node targetNode = null;
        boolean touches;
        do
        {
            touches = false;
            List<Node> nodes = jump(dir, currentNode);
            if(nodes == null || nodes.isEmpty())
                break;

            Rectangle cursorProjection = getProjectedCursorRectangle(
                                    dir.getOrientation(), currentNode);
            if(G.dbgWindowTreeBuilder().getBoolean()) {
                G.dbgWindowTreeBuilder().println("\ncurrentNode:" + dbgName(currentNode) + " "
                                  + getProjectedRectangle(dir.getOrientation(), currentNode));
                G.dbgWindowTreeBuilder().println("cursor: " + cursorProjection);
                G.dbgWindowTreeBuilder().println("jump Targets");
                for(Node n1 : nodes) {
                    System.err.println(dbgName(n1) + " "
                            + getProjectedRectangle(dir.getOrientation(), n1));
                }
            }

            Node nextNode = null;
            int fuzzyDistance = Integer.MAX_VALUE;
            Node fuzzyNode = null;
            for(Node n1 : nodes) {
                Rectangle n1Projection
                        = getProjectedRectangle(dir.getOrientation(), n1);
                if(cursorProjection.intersects(n1Projection)) {
                    nextNode = n1;
                    break;
                }
                int d = distance(dir.getOrientation(), cursorProjection, n1Projection);
                if(d < fuzzyDistance) {
                    fuzzyDistance = d;
                    fuzzyNode = n1;
                }
            }
            if(null == nextNode)
                nextNode = fuzzyNode;

            Rectangle currentNodeProjection
                    = getProjectedRectangle(dir.getOrientation(), currentNode);
            Rectangle nextNodeProjection
                    = getProjectedRectangle(dir.getOrientation(), nextNode);
            if(currentNodeProjection.intersects(nextNodeProjection))
                touches = true;

            currentNode = nextNode;
            targetNode = currentNode;
            // if(dbg)System.err.println("windowJump: " + dbgName(currentNode));
        } while(--n > 0);

        if(targetNode == null || mustTouch && ! touches)
            return null;
        return getAppView(targetNode.getPeer());
    }

    @Override
    public SplitterNode getParentSplitter(ViAppView av, Orientation orientation)
    {
        SplitterNode sn = getParentSplitter(av);
        if(sn == null || sn.getOrientation() != orientation) {
            // construct a splitter node that looks like one child
            Node currentNode = findNode(av);
            if(currentNode == null)
                return null;
            sn = new DummySplitterNode(currentNode, orientation);
        }
        return sn;
    }

    @Override
    public SplitterNode getParentSplitter(ViAppView av)
    {
        initTree();
        Node currentNode = findNode(av);
        if(currentNode == null)
            return null;
        Node n = currentNode.getParent();
        if(n == null)
            return null;
        assert n.isSplitter();
        return new MySplitterNode(n, n.getChildren().indexOf(currentNode));
    }

    @Override
    public SplitterNode getAncestorSplitter(ViAppView av,
                                            Orientation orientation)
    {
        return getAncestorSplitter(av, orientation, false);
    }

    /** this is only useful, for "outer" */
    @Override
    public SplitterNode getRootSplitter(ViAppView av, Orientation orientation)
    {
        return getAncestorSplitter(av, orientation, true);
    }

    private SplitterNode getAncestorSplitter(ViAppView av,
                                             Orientation orientation,
                                             boolean fRoot)
    {
        initTree();
        Node node = findNode(av);
        if(node == null)
            return null;
        Node splitterNode = node;
        int targetIndex = 0;
        Node parentNode;
        while((parentNode = splitterNode.getParent()) != null) {
            targetIndex = parentNode.getChildren().indexOf(splitterNode);
            splitterNode = parentNode;
            if(!fRoot && splitterNode.getOrientation() == orientation)
                break;
        }
        SplitterNode sp;
        if(splitterNode.getOrientation() != orientation) {
            // Notice the rootNode is both child and the container
            sp = new DummySplitterNode(splitterNode, orientation,
                                       splitterNode.getPeer());
        } else {
            sp = new MySplitterNode(splitterNode, targetIndex);
        }
        return sp;
    }

    private Node findNode(final ViAppView targetAv)
    {
        Visitor v = new Visitor()
        {
            @Override
            void visit(Node node)
            {
                if(node.isEditor() && targetAv == getAppView(node.getPeer())) {
                    foundNode = node;
                    finished = true;
                }
            }
        };

        for (Node root : roots) {
            traverse(root, v);
            if(v.finished())
                break;
        }
        return v.foundNode;
    }

    // protected boolean touches(Node n, Direction dir, Node nOther,
    //                           MutableInt distance)
    // {
    //     Rectangle r = getNodeRectangle(n);
    //     Rectangle rOther = getNodeRectangle(nOther);

    //     int d;
    //     switch(dir) {
    //         case LEFT:
    //             d = r.x - (rOther.x + rOther.width);
    //             break;
    //         case RIGHT:
    //             d = rOther.x - (r.x + r.width);
    //             break;
    //         case UP:
    //             d = r.y - (rOther.y + rOther.height);
    //             break;
    //         case DOWN:
    //         default:
    //             d = rOther.y - (r.y + r.height);
    //             break;
    //     }
    //     if(distance != null)
    //         distance.setValue(d);
    //     if(d < 0)
    //         return false;

    //     return d < 100; // NEEDSWORK: WindowTreeBuilder.touches
    // }

    /** minimal distance along the projection */
    private int distance(Orientation orientation, Rectangle r1, Rectangle r2)
    {
        // assume no intersect, if they do then this calc doesn't matter
        if(orientation == Orientation.LEFT_RIGHT) {
            return r1.y > r2.y
                    ? r1.y - (r2.y + r2.height)
                    : r2.y - (r1.y + r1.height);
        } else {
            return r1.x > r2.x
                    ? r1.x - (r2.x + r2.width)
                    : r2.x - (r1.x + r1.width);
        }
    }

    /**
     * This method projects the param rectangle to a line. If orientation
     * is LEFT_RIGHT then projected onto the y axis, otherwise onto x axis.
     * <p/>
     * The result is actually a rectangle with a width/height of one.
     * @param orientation
     * @param r
     * @return
     */
    public static Rectangle getProjectedRectangle(Orientation orientation,
                                            Rectangle2D r)
    {
        Rectangle r1 = round(r);
        if(orientation == Orientation.LEFT_RIGHT) {
            r1.x = 0;
            r1.width = 1;
        } else {
            r1.y = 0;
            r1.height = 1;
        }
        return r1;
    }

    private static Rectangle round(Rectangle2D r)
    {
        return new Rectangle((int)Math.round(r.getX()),
                             (int)Math.round(r.getY()),
                             (int)Math.round(r.getWidth()),
                             (int)Math.round(r.getHeight()));
    }

    /**
     * Rectangle is editor in Window's coordinates.
     * @param orientation
     * @param n
     * @return
     */
    private Rectangle getProjectedRectangle(Orientation orientation, Node n)
    {
        return getProjectedRectangle(orientation, getNodeRectangle(n));
    }

    /**
     * The bounds in Window (Frame/Dialog) coord space.
     * Override to return platform specific.
     * e.g. return "Mode" boundaries on NetBeans
     */
    protected Rectangle getNodeRectangle(Node n)
    {
        Component c = n.getPeer();
        if(c.getParent() instanceof JViewport)
            c = c.getParent();
        Rectangle r = SwingUtilities.getLocalBounds(c);
        r = SwingUtilities.convertRectangle(c, r, null);
        return r;
    }

    private Rectangle getProjectedCursorRectangle(Orientation orientation,
                                                  Node n)
    {
        Component c = n.getPeer();
        TextView tv = (TextView)ViManager.getFactory().getTextView(c);
        Rectangle r = round(tv.getVpLocation(tv.w_cursor));
        if(c.getParent() instanceof JViewport)
            c = c.getParent();
        r = SwingUtilities.convertRectangle(c, r, null);
        return getProjectedRectangle(orientation, r);
    }

    private StringBuilder dumpTree()
    {
        StringBuilder sb = new StringBuilder();
        dumpTree(sb);
        return sb;
    }

    private void dumpTree(StringBuilder sb)
    {
        for (Node node : roots) {
            sb.append("WindowTree for ").append(cid(node)).append('\n');
            dumpTree(sb, node);
        }
    }

    private StringBuilder dumpTree(Node n)
    {
        StringBuilder sb = new StringBuilder();
        dumpTree(sb, n);
        return sb;
    }

    private void dumpTree(final StringBuilder sb, Node n)
    {
        traverse(n,
                 new Visitor()
                 {
                     @Override
                     void visit(Node node)
                     {
                         String shift = String.format("%"+((depth+1)*4)+"s", "");
                         sb.append(shift).append(node).append('\n');
                     }
                 });
    }

    private void addToSorted(Node n)
    {
        traverse(n, new Visitor()
        {
            @Override
            void visit(Node node)
            {
                if(node.isEditor())
                    sorted.add(getAppView(node.getPeer()));
            }
        });
    }

    private Node buildTree(Component c)
    {
        if(isEditor(c)) {
            removeFromToDo(c);
            return createEditorNode(c);
        }

        Node child = null;
        List<Node> ns = null; // only create if more than one child
        if(c instanceof Container) {
            Component components[] = ((Container)c).getComponents();
            for (int i = 0; i < components.length; i++) {
                Node n01 = buildTree(components[i]);
                if(n01 != null) {
                    // avoid creating the array if possible
                    if(child == null && ns == null)
                        child = n01;
                    else {
                        // There's more than one child.
                        // Note order in component is maintained
                        if(ns == null)
                            ns = new ArrayList<Node>(2);
                        if(child != null) {
                            ns.add(child);
                            child = null;
                        }
                        ns.add(n01);
                    }
                }
            }
        }

        assert child == null && ns == null || child == null || ns == null;
        return child != null ? child
                : ns != null ? createSplitNode(c, ns) : null;
    }

    protected Point getLocation(Node n)
    {
        Component c = n.getPeer();
        if(c.getParent() instanceof JViewport)
            c = c.getParent();
        return c.getLocationOnScreen();
    }

    /**
     * sort the roots top-to-bottom then left-to-right.
     *
     * Notice that with multiple screens this sorting finishes a screen,
     * top-to-bottom, before moving to the next screen on the right.
     */
    private void sortRoots()
    {
        //Collections.sort(roots, new CompareNodeLocations());
        Collections.sort(roots, new Comparator<Node>()
        {
            @Override
            public int compare(Node n1, Node n2)
            {
                // NEEDSWORK: use TOP LEVEL WINDOW location
                Point w1 = getWindowLocation(n1.getPeer());
                Point w2 = getWindowLocation(n2.getPeer());

                int rv;
                rv = w1.x != w2.x ? w1.x - w2.x : w1.y - w2.y;
                // System.err.format("Comp rv %d\n    %s%s\n    %s%s\n",
                //         rv, this, w1, o, w2);
                return rv;
            }
        });
    }

    private Point getWindowLocation(Component descendant)
    {
        return SwingUtilities.getRoot(descendant).getLocationOnScreen();
    }

    private class CompareNodeLocations implements Comparator<Node>
    {
        @Override
        public int compare(Node n1, Node n2)
        {
            Point w1 = getLocation(n1);
            Point w2 = getLocation(n2);

            int rv;
            rv = w1.x != w2.x ? w1.x - w2.x : w1.y - w2.y;
            // System.err.format("Comp rv %d\n    %s%s\n    %s%s\n",
            //         rv, this, w1, o, w2);
            return rv;
        }
    }

    private Component allComps(Component c)
    {
        ViManager.println("findNode check: " + c.getClass().getSimpleName()
            +" "+ (c.isShowing() ? c.getLocationOnScreen() : "not showing"));
        if(c.getClass().getSimpleName().equals("MultiSplitPane"))
            ViManager.println("multisplitpane");
        if(isEditor(c))
            ViManager.println("FOUND ONE");//return c;
        if(c instanceof Container) {
            Component components[] = ((Container)c).getComponents();
            for (int i = 0; i < components.length; i++) {
                Component c01 = allComps(components[i]);
                if(c01 != null)
                    return c01;
            }
        }
        return null;
    }

    protected abstract Component windowForAppView(ViAppView av);

    protected boolean removeFromToDo(ViAppView av)
    {
        boolean f = toDo.remove(av);
        assert f;
        return f;
    }

    protected boolean isToDo(ViAppView av)
    {
        return toDo.contains(av);
    }

    /**
     * Take something out of the todo list
     * @param c
     */
    protected abstract boolean removeFromToDo(Component c);

    /**
     * Used on the input component tree elements.
     * @param c the component to test
     * @return true if the node is an editor and in the toDo list
     */
    protected abstract boolean isEditor(Component c);

    /**
     * Determine the AppView associated with the node. If the node is not
     * an editor then this will return null.
     * @param n the node of interest
     * @return the app view associated with the node
     */
    protected abstract ViAppView getAppView(Component c);

    protected Node createEditorNode(Component peer)
    {
        return new Node(peer);
    }

    /** assumes children are in order */
    protected Node createSplitNode(Component peer, List<Node> children)
    {
        Orientation orientation = Orientation.LEFT_RIGHT; // any default
        if(children != null && children.size() >= 2)
            orientation = calcSplitterOrientation(
                    peer, children.get(0), children.get(1));
        return new Node(orientation, peer, children);
    }

    /**
     * Determine if splitter is left-right or up-down.
     *
     * The peers of the Nodes should have the splitter as an ancestor.
     * @param splitter
     * @param n01
     * @param n02
     * @return
     */
    protected Orientation calcSplitterOrientation(
            Component splitter,Node n01, Node n02)
    {
        Point p1 = getLocationInSplitter(splitter, n01.getPeer());
        Point p2 = getLocationInSplitter(splitter, n02.getPeer());

        int dX = Math.abs(p1.x - p2.x);
        int dY = Math.abs(p1.y - p2.y);
        return dX > dY ? Orientation.LEFT_RIGHT : Orientation.UP_DOWN;
    }

    private Point getLocationInSplitter(Component splitter, Component descendant)
    {
        Point p = null;
        while(descendant.getParent() != splitter)
        {
            descendant = descendant.getParent();
        }

        return descendant.getLocationOnScreen();
    }

    /** basically a preorder like traversal */
    private void traverse(Node n, Visitor v)
    {
        v.visit(n);
        if(v.finished())
            return;
        ++v.depth;
        for (Node child : n.getChildren()) {
            traverse(child, v);
            if(v.finished())
                return;
        }
        --v.depth;
    }

    private static abstract class Visitor
    {
        int depth;
        boolean finished;
        Node foundNode; // hack

        abstract void visit(Node node);

        boolean finished() // to signal early termination
        {
            return finished;
        }
    }

    /**
     * A splitter node packaged for public consumption.
     */
    private static class MySplitterNode implements SplitterNode
    {
        final private Node node;
        final private int targetIndex;
        private SplitterChildNode[] children;

        public MySplitterNode(Node node, int targetIndex)
        {
            this.node = node;
            this.targetIndex = targetIndex;
        }

        @Override
        public boolean isEditor()
        {
            return false;
        }

        @Override
        public Component getComponent()
        {
            return node.getPeer();
        }

        @Override
        public SplitterChildNode[] getChildren()
        {
            if(children == null) {
                children = new SplitterChildNode[getChildCount()];
                for(int i = 0; i < children.length; i++) {
                    children[i] = new MyChildNode(i);
                }
            }
            return children;
        }

        @Override
        public int getChildCount()
        {
            return node.children.size();
        }

        @Override
        public int getTargetIndex()
        {
            return targetIndex;
        }

        @Override
        public Orientation getOrientation()
        {
            return node.getOrientation();
        }

        private class MyChildNode implements SplitterChildNode
        {
            private int childIndex;

            public MyChildNode(int i)
            {
                this.childIndex = i;
            }

            @Override
            public boolean isEditor()
            {
                return node.children.get(childIndex).isEditor();
            }

            @Override
            public Component getComponent()
            {
                return node.children.get(childIndex).getPeer();
            }

            private MySplitterNode outer()
            {
                return MySplitterNode.this;
            }
        }
    }

    /** typically this should only be used for an initial weight calculation */
    private class DummySplitterNode implements SplitterNode
    {
        final private Node child;
        final private Orientation orientation;
        final private Component splitterComponent;

        DummySplitterNode(Node child, Orientation orientation)
        {
            this(child, orientation, getDummySplitterComponent(child.getPeer()));
        }

        public DummySplitterNode(Node child, Orientation orientation,
                                 Component splitterComponent)
        {
            this.child = child;
            this.orientation = orientation;
            this.splitterComponent = splitterComponent;
        }

        @Override
        public boolean isEditor()
        {
            return false;
        }

        @Override
        public int getTargetIndex()
        {
            return 0;
        }

        @Override
        public int getChildCount()
        {
            return 1;
        }

        @Override
        public SplitterChildNode[] getChildren()
        {
            return new SplitterChildNode[] { new DummyChildNode() };
        }

        @Override
        public Component getComponent()
        {
            return splitterComponent;
        }

        @Override
        public Orientation getOrientation()
        {
            return orientation;
        }

        private class DummyChildNode implements SplitterChildNode
        {

            @Override
            public boolean isEditor()
            {
                return child.isEditor();
            }

            @Override
            public Component getComponent()
            {
                return child.getPeer();
            }
        }
    }

    /** look for a container for the component */
    protected Component getDummySplitterComponent(Component c)
    {
        return c;
    }

    protected class Node
    {
        private boolean isEditor;
        private Orientation orientation;
        private Component peer;
        Node parent;
        private List<Node> children;

        protected Node(Component peer)
        {
            this.peer = peer;
            isEditor = true;
        }

        @SuppressWarnings("LeakingThisInConstructor")
        protected Node(Orientation orient, Component peer, List<Node> children)
        {
            this.orientation = orient;
            this.peer = peer;
            this.children = children;
            for (Node child : children) {
                child.parent = this;
            }
        }

        public boolean isEditor()
        {
            return isEditor;
        }

        public boolean isSplitter()
        {
            return !isEditor;
        }

        public Orientation getOrientation()
        {
            return orientation;
        }

        public Component getPeer()
        {
            return peer;
        }

        public Node getParent()
        {
            return parent;
        }

        public List<Node> getChildren()
        {
            return children == null ? Collections.<Node>emptyList()
                : Collections.unmodifiableList(children);
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder(40);
            sb.append(isEditor() ? "editor: " : "split: ");
            sb.append(peer.getClass().getSimpleName());

            Rectangle r = getNodeRectangle(this);
            sb.append(" [").append(r.x)
              .append(',').append(r.y)
              .append(',').append(r.width)
              .append(',').append(r.height).append(']');
            if(!isEditor())
                sb.append(getOrientation() == Orientation.LEFT_RIGHT
                                    ? " LeftRight" : " TopBottom");
            return sb.toString();
        }

    }

    private String dbgName(Node n)
    {
        String s;
        if(n == null)
            s = null;
        else if(n.isEditor()) {
            s = getAppView(n.getPeer()).toString();
        } else {
            s = n.getPeer().getClass().getSimpleName();
        }
        return s;
    }


    /**
     * Find the windows in the given direction along my edge.
     * @param dir
     * @param from
     * @return
     */
    private List<Node> jump(Direction dir, Node from)
    {
        Node to = treeUpFindSiblingNodeForJump(dir, from);
        if(to == null)
            return null;

        // Notice that the target window is kind of the "opposite" direction
        // of the jump within the window targetNode we are jumping into.
        // For example, if jump UP then
        // in the group above pick the DOWN window.
        List<Node> jumpTargets = new ArrayList<Node>();
        treeDownFindJumpTarget(dir.getOpposite(), to, jumpTargets);
        return jumpTargets;
    }

    /**
     * Move up the tree, following parent link, looking for a targetNode where
     * we can start going down the tree to get the target window in the
     * direction.
     * @param dir
     * @param targetNode
     * @return
     */
    private Node treeUpFindSiblingNodeForJump(Direction dir, Node node)
    {
        Node found = null;
        while(true) {
            Node parent = node.getParent();
            if(parent == null)
                break;
            if(G.dbgWindowTreeBuilder().getBoolean())G.dbgWindowTreeBuilder().println("treeUp: " + dbgName(node));
            if((found = pickSiblingForJump(dir, node)) != null)
                break;
            node = parent;
        }
        if(G.dbgWindowTreeBuilder().getBoolean())G.dbgWindowTreeBuilder().println("treeUp found: " + dbgName(found));
        return found;
    }

    /**
     * Used while moving up the tree to get a sibling that can start the
     * way down the tree to the target window. The parent targetNode must be
     * oriented in the goal direction and there must be a sibling in the
     * goal direction.
     * @param dir
     * @param targetNode
     * @return sibling that satisfies the directional requirement or null
     */
    private Node pickSiblingForJump(Direction dir, Node child)
    {
        Node parent = child.getParent();
        if(parent == null)
            return null;
        if(parent.getOrientation() != dir.getOrientation())
            return null;
        List<Node> children = parent.getChildren();
        // select neighboring node for child
        int idx = children.indexOf(child);
        idx += (towardsFirst(dir) ? -1 : 1);
        if(idx < 0 || idx >= children.size())
            return null;
        return children.get(idx);
    }

    /**
     * Traverse the tree down to find target windows.
     * @param dir
     * @param targetNode
     * @return
     */
    private void treeDownFindJumpTarget(Direction dir, Node node,
                                        List<Node> jumpTargets)
    {
        if(node.isEditor()) {
            jumpTargets.add(node);
        }

        // looking at a splitter node
        if(!dir.getOrientation().equals(node.getOrientation())) {
            for(Node child : node.getChildren()) {
                treeDownFindJumpTarget(dir, child, jumpTargets);
            }
        } else {
            // this splitter node has the orientation of interest
            List<Node> children = node.getChildren();
            int nextNode = towardsFirst(dir) ? 0 : children.size() - 1;
            Node next = children.get(towardsFirst(dir) ? 0:children.size() - 1);
            // NEEDSWORK: make sure didn't vist this on the way up
            treeDownFindJumpTarget(dir, next, jumpTargets);
        }
    }

    /**
     * Return if target is towards the front or back of an ordered array.
     * @param dir
     * @return true if dir is LEFT or UP, false if RIGHT, DOWN
     */
    private boolean towardsFirst(Direction dir)
    {
        return dir == Direction.LEFT || dir == Direction.UP ? true : false;
    }

    protected void dumpWinAction(ActionEvent e, StringBuilder sb, boolean verbose)
    {
        if(sb == null) {
            sb = new StringBuilder();
        }
        initTree();
        dumpTree(sb);
        ViOutputStream vios
                = ViManager.createOutputStream(null, ViOutputStream.OUTPUT,
                                               "Dump Window Hierarchy");
        vios.println(sb.toString());
        vios.close();
    }

    private static class DumpWin extends AbstractColonAction {
        @Override
        public void actionPerformed(ActionEvent e)
        {
            ColonEvent cev = (ColonEvent)e;

            WindowTreeBuilder nav = (WindowTreeBuilder)
                    ViManager.getFactory().getWindowNavigator();
            nav.dumpWinAction(e, null, cev.getNArg() != 0);
        }
    }
}
