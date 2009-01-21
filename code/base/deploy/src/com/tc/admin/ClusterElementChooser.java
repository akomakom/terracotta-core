/*
 * All content copyright (c) 2003-2009 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin;

import com.tc.admin.common.BasicToggleArrowButton;
import com.tc.admin.common.XContainer;
import com.tc.admin.common.XRootNode;
import com.tc.admin.common.XScrollPane;
import com.tc.admin.common.XTree;
import com.tc.admin.common.XTreeModel;
import com.tc.admin.common.XTreeNode;
import com.tc.admin.model.IClusterModel;
import com.tc.admin.model.IClusterModelElement;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.util.Enumeration;

import javax.swing.AbstractButton;
import javax.swing.CellRendererPane;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.EventListenerList;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeSelectionModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;

public abstract class ClusterElementChooser extends XContainer implements TreeWillExpandListener, TreeModelListener {
  private IClusterModel     clusterModel;
  private ClusterListener   clusterListener;
  private AbstractButton    triggerButton;
  private TriggerHandler    triggerHandler;
  private XTree             tree;
  private XTreeModel        treeModel;
  private TreePath          selectionPath;
  private TreeMouseHandler  treeMouseHandler;
  private SelectionRenderer selectionRenderer;
  private CellRendererPane  cellRendererPane;
  private XScrollPane       scroller;
  private JPopupMenu        popup;
  private boolean           inited;
  private EventListenerList listenerList;

  public ClusterElementChooser(IClusterModel clusterModel, ActionListener listener) {
    super(new GridBagLayout());

    triggerHandler = new TriggerHandler();
    treeMouseHandler = new TreeMouseHandler();

    setBackground(UIManager.getColor("TextField.background"));
    setBorder(UIManager.getBorder("TextField.border"));

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridy = gbc.gridx = 0;
    gbc.insets = new Insets(0, 1, 0, 0);

    cellRendererPane = new CellRendererPane();
    add(cellRendererPane);

    add(selectionRenderer = new SelectionRenderer(), gbc);
    gbc.gridx++;
    selectionRenderer.addMouseListener(triggerHandler);

    add(triggerButton = createArrowButton(), gbc);
    triggerButton.setRequestFocusEnabled(false);
    triggerButton.resetKeyboardActions();
    triggerButton.setInheritsPopupMenu(true);
    triggerButton.addItemListener(triggerHandler);

    popup = new JPopupMenu();
    popup.addHierarchyListener(triggerHandler);
    popup.setLayout(new BorderLayout());
    tree = new XTree();
    tree.addTreeWillExpandListener(this);
    tree.setModel(treeModel = new XTreeModel());
    treeModel.addTreeModelListener(this);
    popup.add(scroller = new XScrollPane(tree));
    tree.addMouseListener(treeMouseHandler);
    tree.addMouseMotionListener(treeMouseHandler);
    tree.setSelectionModel(new TreeSelectionModel());
    
    this.clusterModel = clusterModel;
    this.listenerList = new EventListenerList();
    if (listener != null) {
      this.listenerList.add(ActionListener.class, listener);
    }

    clusterModel.addPropertyChangeListener(clusterListener = new ClusterListener(clusterModel));
    if (clusterModel.isReady()) {
      setupTreeModel();
    }
  }

  private class TreeSelectionModel extends DefaultTreeSelectionModel {
    private TreePath[] filterPaths(TreePath[] paths) {
      if (paths != null) {
        TreePath rootPath = new TreePath(treeModel.getRoot());
        for (int i = 0; i < paths.length; i++) {
          if (!acceptPath(paths[i])) {
            paths[i] = rootPath;
          }
        }
      }
      return paths;
    }

    public void setSelectionPaths(TreePath[] paths) {
      super.setSelectionPaths(filterPaths(paths));
    }

    public void addSelectionPaths(TreePath[] paths) {
      super.addSelectionPaths(filterPaths(paths));
    }
  }

  private class TreeMouseHandler extends MouseAdapter implements MouseMotionListener {
    public void mouseReleased(MouseEvent e) {
      Point p = e.getPoint();
      int selRow = tree.getRowForLocation(p.x, p.y);
      if (selRow != -1) {
        TreePath path = tree.getPathForRow(selRow);
        if (path != null) {
          if (acceptPath(path)) {
            setSelectedPath(path);
            popup.setVisible(false);
          }
        }
      }
    }

    // MouseMotionListener implementation

    private void handleMouseMotion(MouseEvent e) {
      Point p = e.getPoint();
      int selRow = tree.getRowForLocation(p.x, p.y);
      if (selRow != -1) {
        TreePath path = tree.getPathForRow(selRow);
        if (path != null) {
          tree.setSelectionPath(path);
          tree.scrollPathToVisible(path);
        }
      }
    }

    public void mouseMoved(MouseEvent e) {
      handleMouseMotion(e);
    }

    public void mouseDragged(MouseEvent e) {
      handleMouseMotion(e);
    }
  }

  public void addActionListener(ActionListener l) {
    listenerList.add(ActionListener.class, l);
  }

  public void removeActionListener(ActionListener l) {
    listenerList.remove(ActionListener.class, l);
  }

  protected void fireActionPerformed(ActionEvent event) {
    Object[] listeners = listenerList.getListenerList();
    ActionEvent e = null;
    for (int i = listeners.length - 2; i >= 0; i -= 2) {
      if (listeners[i] == ActionListener.class) {
        if (e == null) {
          e = new ActionEvent(ClusterElementChooser.this, ActionEvent.ACTION_PERFORMED, "ClusterElementSelected",
                              System.currentTimeMillis(), 0);
        }
        ((ActionListener) listeners[i + 1]).actionPerformed(e);
      }
    }
  }

  private class TriggerHandler extends MouseAdapter implements ItemListener, HierarchyListener {
    public void itemStateChanged(ItemEvent e) {
      togglePopup();
    }

    public void mousePressed(MouseEvent e) {
      triggerButton.doClick();
    }

    public void hierarchyChanged(HierarchyEvent e) {
      long flags = e.getChangeFlags();
      if ((flags & HierarchyEvent.SHOWING_CHANGED) != 0) {
        boolean isPopupShowing = popup.isShowing();
        if (triggerButton.isSelected() != isPopupShowing) {
          triggerButton.setSelected(isPopupShowing);
        }
      }
    }
  }

  protected void togglePopup() {
    boolean isTriggerSelected = triggerButton.isSelected();
    if (isTriggerSelected && !popup.isShowing()) {
      showPopup();
    } else if (!isTriggerSelected && popup.isShowing()) {
      hidePopup();
    }
  }

  protected void showPopup() {
    Point p = getPopupLocation();
    popup.show(this, p.x, p.y);
    tree.requestFocusInWindow();
  }

  protected void hidePopup() {
    popup.setVisible(false);
  }

  protected boolean acceptPath(TreePath path) {
    Object o = path.getLastPathComponent();
    return (o instanceof ClusterElementNode);
  }

  public TreePath getSelectedPath() {
    return selectionPath;
  }

  public void setSelectedPath(TreePath path) {
    selectionPath = path;
    fireActionPerformed(null);
    revalidate();
    repaint();
  }

  public void setSelectedPath(String nodeName) {
    XTreeNode node = ((XRootNode) treeModel.getRoot()).findNodeByName(nodeName);
    if (node != null) {
      setSelectedPath(new TreePath(node.getPath()));
    }
  }

  public Object getSelectedObject() {
    return selectionPath != null ? selectionPath.getLastPathComponent() : null;
  }

  private class SelectionRenderer extends JComponent {
    protected void paintComponent(Graphics g) {
      if (selectionPath != null) {
        Component c = tree.getRendererComponent(selectionPath);
        if (c != null) {
          cellRendererPane.paintComponent(g, c, this, 0, 0, getWidth(), getHeight(), true);
        }
      }
    }

    public boolean isShowing() {
      return true;
    }
  }

  private Point getPopupLocation() {
    Dimension popupSize = popup.getPreferredSize();
    popupSize.height = 200;

    int x = getBounds().width - popupSize.width;
    Rectangle popupBounds = computePopupBounds(x, getBounds().height, popupSize.width, popupSize.height);
    Dimension scrollSize = popupBounds.getSize();
    Point popupLocation = popupBounds.getLocation();

    scroller.setMaximumSize(scrollSize);
    scroller.setPreferredSize(scrollSize);
    scroller.setMinimumSize(scrollSize);

    tree.revalidate();

    return popupLocation;
  }

  protected Rectangle computePopupBounds(int px, int py, int pw, int ph) {
    Toolkit toolkit = Toolkit.getDefaultToolkit();
    Rectangle screenBounds;

    GraphicsConfiguration gc = getGraphicsConfiguration();
    Point p = new Point();
    SwingUtilities.convertPointFromScreen(p, this);
    if (gc != null) {
      Insets screenInsets = toolkit.getScreenInsets(gc);
      screenBounds = gc.getBounds();
      screenBounds.width -= (screenInsets.left + screenInsets.right);
      screenBounds.height -= (screenInsets.top + screenInsets.bottom);
      screenBounds.x += (p.x + screenInsets.left);
      screenBounds.y += (p.y + screenInsets.top);
    } else {
      screenBounds = new Rectangle(p, toolkit.getScreenSize());
    }

    Rectangle rect = new Rectangle(px, py, pw, ph);
    if (py + ph > screenBounds.y + screenBounds.height && ph < screenBounds.height) {
      rect.y = -rect.height;
    }
    return rect;
  }

  private class ClusterListener extends AbstractClusterListener {
    private ClusterListener(IClusterModel clusterModel) {
      super(clusterModel);
    }

    protected void handleReady() {
      if (!inited && clusterModel.isReady()) {
        setupTreeModel();
      }
    }
  }

  protected abstract XTreeNode[] createTopLevelNodes();

  private void setupTreeModel() {
    XRootNode root = (XRootNode) treeModel.getRoot();
    for (XTreeNode child : createTopLevelNodes()) {
      root.add(child);
    }
    root.nodeStructureChanged();
    inited = true;
  }

  public TreePath getPath(IClusterModelElement clusterElement) {
    DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
    Enumeration e = root.preorderEnumeration();
    while (e.hasMoreElements()) {
      DefaultMutableTreeNode node = (DefaultMutableTreeNode) e.nextElement();
      if (node instanceof ClusterElementNode) {
        IClusterModelElement cme = ((ClusterElementNode) node).getClusterElement();
        if (clusterElement.equals(cme)) { return new TreePath(node.getPath()); }
      }
    }
    return null;
  }

  public void treeWillExpand(TreeExpansionEvent event) {
    /**/
  }

  public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
    throw new ExpandVetoException(event);
  }

  protected AbstractButton createArrowButton() {
    AbstractButton button = new BasicToggleArrowButton(SwingConstants.SOUTH, UIManager
        .getColor("ComboBox.buttonBackground"), UIManager.getColor("ComboBox.buttonShadow"), UIManager
        .getColor("ComboBox.buttonDarkShadow"), UIManager.getColor("ComboBox.buttonHighlight"));
    return button;
  }

  private void treeModelChanged() {
    tree.expandAll();
    selectionRenderer.setPreferredSize(tree.getMaxItemSize());
    revalidate();
    repaint();
  }

  public void treeNodesChanged(TreeModelEvent e) {
    treeModelChanged();
  }

  public void treeNodesInserted(TreeModelEvent e) {
    treeModelChanged();
  }

  public void treeNodesRemoved(TreeModelEvent e) {
    treeModelChanged();
  }

  public void treeStructureChanged(TreeModelEvent e) {
    treeModelChanged();
  }

  public void tearDown() {
    clusterModel.removePropertyChangeListener(clusterListener);
    treeModel.removeTreeModelListener(this);
    tree.removeMouseListener(treeMouseHandler);
    tree.removeMouseMotionListener(treeMouseHandler);

    synchronized (this) {
      clusterModel = null;
      clusterListener = null;
      triggerButton = null;
      tree = null;
      treeModel = null;
      selectionRenderer = null;
      treeMouseHandler = null;
      popup = null;
    }
  }
}
