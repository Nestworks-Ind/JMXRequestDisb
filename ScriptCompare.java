import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * ScriptCompare - a Notepad++ "Compare"-style side-by-side diff viewer.
 *
 * Paste-first workflow: paste your two LoadRunner scripts into the left and
 * right panes, press Compare, and see the aligned diff. Single-file Swing app.
 *
 *   Compile:  javac ScriptCompare.java
 *   Run:      java ScriptCompare
 *
 * NOTE: the public class name must match the file name. If you rename the file
 * to e.g. Compare.java, also rename "class ScriptCompare" to "class Compare".
 */
public class ScriptCompare extends JFrame {

    enum RowType { EQUAL, DELETED, ADDED, CHANGED }

    static final class Row {
        String left, right;
        int leftNum, rightNum;
        RowType type;
        int[] leftHi, rightHi;
    }

    enum EType { EQL, INS, DEL }
    static final class Edit {
        EType t; int ai, bi;
        Edit(EType t, int ai, int bi) { this.t = t; this.ai = ai; this.bi = bi; }
    }

    private String[] leftLines = new String[0];
    private String[] rightLines = new String[0];

    private boolean ignoreWs = false;
    private boolean ignoreCase = false;
    private int fontSize = 13;
    private boolean diffShowing = false;

    private List<Row> rows = new ArrayList<>();
    private List<int[]> blocks = new ArrayList<>();
    private int currentBlock = -1;

    private final JTextArea leftArea = new JTextArea();
    private final JTextArea rightArea = new JTextArea();
    private final SidePanel leftPanel = new SidePanel(true);
    private final SidePanel rightPanel = new SidePanel(false);
    private JScrollPane leftDiffScroll, rightDiffScroll;
    private final JLabel leftDiffHeader = new JLabel();
    private final JLabel rightDiffHeader = new JLabel();
    private final JLabel statsLabel = new JLabel(" ");
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private boolean syncing = false;

    public ScriptCompare() {
        super("ScriptCompare  \u2013  LoadRunner Script Diff");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 800);
        setLocationRelativeTo(null);

        setJMenuBar(buildMenuBar());
        add(buildToolBar(), BorderLayout.NORTH);

        center.add(buildEditCard(), "edit");
        center.add(buildDiffCard(), "diff");
        add(center, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(new EmptyBorder(3, 8, 3, 8));
        statsLabel.setFont(statsLabel.getFont().deriveFont(Font.PLAIN, 12f));
        south.add(statsLabel, BorderLayout.WEST);
        JLabel legend = new JLabel("red = deleted   green = added   yellow = changed  ");
        legend.setForeground(new Color(110, 110, 110));
        south.add(legend, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);

        installKeyBindings();
        applyEditFont();
        showEdit();
    }

    private JComponent buildEditCard() {
        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                editSide("Left  \u2013  paste your first script here", leftArea),
                editSide("Right  \u2013  paste your second script here", rightArea));
        sp.setResizeWeight(0.5);
        sp.setDividerSize(6);
        SwingUtilities.invokeLater(() -> sp.setDividerLocation(0.5));
        return sp;
    }

    private JComponent editSide(String title, JTextArea area) {
        area.setLineWrap(false);
        area.setTabSize(4);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize));

        JLabel header = new JLabel(title);
        header.setOpaque(true);
        header.setBackground(new Color(238, 240, 244));
        header.setBorder(new EmptyBorder(5, 8, 5, 8));
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton load = new JButton("Load file\u2026");
        load.setFocusable(false);
        load.addActionListener(e -> loadFileIntoArea(area));
        JButton clear = new JButton("Clear");
        clear.setFocusable(false);
        clear.addActionListener(e -> area.setText(""));
        buttons.add(load);
        buttons.add(clear);

        JPanel p = new JPanel(new BorderLayout());
        p.add(header, BorderLayout.NORTH);
        p.add(new JScrollPane(area), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);

        new FileDrop(area, f -> loadFileIntoArea(area, f));
        return p;
    }

    private JComponent buildDiffCard() {
        leftDiffScroll = new JScrollPane(leftPanel);
        rightDiffScroll = new JScrollPane(rightPanel);
        leftDiffScroll.getVerticalScrollBar().setUnitIncrement(16);
        rightDiffScroll.getVerticalScrollBar().setUnitIncrement(16);

        styleHeader(leftDiffHeader);
        styleHeader(rightDiffHeader);
        leftDiffScroll.setColumnHeaderView(leftDiffHeader);
        rightDiffScroll.setColumnHeaderView(rightDiffHeader);

        leftDiffScroll.getVerticalScrollBar().addAdjustmentListener(e -> syncScroll(true));
        rightDiffScroll.getVerticalScrollBar().addAdjustmentListener(e -> syncScroll(false));

        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftDiffScroll, rightDiffScroll);
        sp.setResizeWeight(0.5);
        sp.setDividerSize(6);
        SwingUtilities.invokeLater(() -> sp.setDividerLocation(0.5));
        return sp;
    }

    private void styleHeader(JLabel l) {
        l.setOpaque(true);
        l.setBackground(new Color(238, 240, 244));
        l.setBorder(new EmptyBorder(4, 8, 4, 8));
        l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
    }

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(menuItem("Load file into Left\u2026", e -> loadFileIntoArea(leftArea)));
        file.add(menuItem("Load file into Right\u2026", e -> loadFileIntoArea(rightArea)));
        file.addSeparator();
        file.add(menuItem("Clear both", e -> { leftArea.setText(""); rightArea.setText(""); }));
        file.add(menuItem("Swap sides", e -> swapSides()));
        file.addSeparator();
        file.add(menuItem("Exit", e -> dispose()));
        mb.add(file);

        JMenu nav = new JMenu("Navigate");
        nav.add(menuItem("Compare   (F5)", e -> compareFromText()));
        nav.add(menuItem("Back to edit   (Esc)", e -> showEdit()));
        nav.addSeparator();
        nav.add(menuItem("Next difference   (F7)", e -> gotoBlock(+1)));
        nav.add(menuItem("Previous difference   (Shift+F7)", e -> gotoBlock(-1)));
        mb.add(nav);

        JMenu view = new JMenu("View");
        view.add(menuItem("Increase font   (Ctrl +)", e -> changeFont(+1)));
        view.add(menuItem("Decrease font   (Ctrl -)", e -> changeFont(-1)));
        mb.add(view);

        return mb;
    }

    private JToolBar buildToolBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(new EmptyBorder(4, 6, 4, 6));

        tb.add(button("Compare \u25B6", e -> compareFromText()));
        tb.add(button("\u270E Edit", e -> showEdit()));
        tb.addSeparator();
        tb.add(button("Swap", e -> swapSides()));
        tb.addSeparator();
        tb.add(button("\u25B2 Prev", e -> gotoBlock(-1)));
        tb.add(button("\u25BC Next", e -> gotoBlock(+1)));
        tb.addSeparator();
        tb.add(button("A\u2212", e -> changeFont(-1)));
        tb.add(button("A+", e -> changeFont(+1)));
        tb.addSeparator();

        JCheckBox cbWs = new JCheckBox("Ignore whitespace");
        cbWs.setFocusable(false);
        cbWs.addActionListener(e -> { ignoreWs = cbWs.isSelected(); if (diffShowing) compareFromText(); });
        tb.add(cbWs);
        JCheckBox cbCase = new JCheckBox("Ignore case");
        cbCase.setFocusable(false);
        cbCase.addActionListener(e -> { ignoreCase = cbCase.isSelected(); if (diffShowing) compareFromText(); });
        tb.add(cbCase);

        return tb;
    }

    private JButton button(String text, ActionListener a) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.addActionListener(a);
        return b;
    }

    private JMenuItem menuItem(String text, ActionListener a) {
        JMenuItem mi = new JMenuItem(text);
        mi.addActionListener(a);
        return mi;
    }

    private void installKeyBindings() {
        JRootPane rp = getRootPane();
        InputMap im = rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = rp.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "compare");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "edit");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), "next");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.SHIFT_DOWN_MASK), "prev");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK), "fontup");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, InputEvent.CTRL_DOWN_MASK), "fontup");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK), "fontdown");
        am.put("compare", action(e -> compareFromText()));
        am.put("edit", action(e -> showEdit()));
        am.put("next", action(e -> gotoBlock(+1)));
        am.put("prev", action(e -> gotoBlock(-1)));
        am.put("fontup", action(e -> changeFont(+1)));
        am.put("fontdown", action(e -> changeFont(-1)));
    }

    private AbstractAction action(ActionListener a) {
        return new AbstractAction() {
            public void actionPerformed(ActionEvent e) { a.actionPerformed(e); }
        };
    }

    private void showEdit() {
        diffShowing = false;
        cards.show(center, "edit");
        statsLabel.setText("Paste a script into each pane, then press Compare (F5).");
    }

    private void showDiff() {
        diffShowing = true;
        cards.show(center, "diff");
    }

    private void loadFileIntoArea(JTextArea area) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Load file");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadFileIntoArea(area, fc.getSelectedFile());
        }
    }

    private void loadFileIntoArea(JTextArea area, File f) {
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            area.setText(text);
            area.setCaretPosition(0);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not read file:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void swapSides() {
        if (diffShowing) return;
        String t = leftArea.getText();
        leftArea.setText(rightArea.getText());
        rightArea.setText(t);
        leftArea.setCaretPosition(0);
        rightArea.setCaretPosition(0);
    }

    private static String[] splitLines(String text) {
        String norm = text.replace("\r\n", "\n").replace('\r', '\n');
        if (norm.isEmpty()) return new String[0];
        return norm.split("\n", -1);
    }

    private static String[] expandAll(String[] in) {
        String[] out = new String[in.length];
        for (int i = 0; i < in.length; i++) out[i] = expandTabs(in[i]);
        return out;
    }

    private static String expandTabs(String s) {
        if (s.indexOf('\t') < 0) return s;
        StringBuilder sb = new StringBuilder();
        int col = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t') {
                int add = 4 - (col % 4);
                for (int k = 0; k < add; k++) sb.append(' ');
                col += add;
            } else { sb.append(c); col++; }
        }
        return sb.toString();
    }

    private String norm(String s) {
        String r = s;
        if (ignoreWs) r = r.trim().replaceAll("\\s+", " ");
        if (ignoreCase) r = r.toLowerCase();
        return r;
    }

    private void compareFromText() {
        leftLines = expandAll(splitLines(leftArea.getText()));
        rightLines = expandAll(splitLines(rightArea.getText()));

        String[] na = new String[leftLines.length];
        String[] nb = new String[rightLines.length];
        for (int i = 0; i < na.length; i++) na[i] = norm(leftLines[i]);
        for (int i = 0; i < nb.length; i++) nb[i] = norm(rightLines[i]);

        List<Edit> edits = buildEdits(na, nb);
        rows = buildRows(edits, leftLines, rightLines);
        blocks = computeBlocks(rows);
        currentBlock = -1;

        leftDiffHeader.setText("Left   (" + leftLines.length + " lines)");
        rightDiffHeader.setText("Right   (" + rightLines.length + " lines)");
        leftPanel.setRows(rows);
        rightPanel.setRows(rows);
        updateStats();
        showDiff();
        SwingUtilities.invokeLater(() -> leftDiffScroll.getVerticalScrollBar().setValue(0));
    }

    private void updateStats() {
        int add = 0, del = 0, chg = 0;
        for (Row r : rows) {
            if (r.type == RowType.ADDED) add++;
            else if (r.type == RowType.DELETED) del++;
            else if (r.type == RowType.CHANGED) chg++;
        }
        if (leftLines.length == 0 && rightLines.length == 0) {
            statsLabel.setText("Both panes are empty.");
        } else if (blocks.isEmpty()) {
            statsLabel.setText("Scripts are identical.");
        } else {
            statsLabel.setText(String.format(
                    "Differences: %d block%s   \u2022   %d added, %d deleted, %d changed",
                    blocks.size(), blocks.size() == 1 ? "" : "s", add, del, chg));
        }
    }

    private static List<int[]> computeBlocks(List<Row> rows) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < rows.size(); i++) {
            boolean diff = rows.get(i).type != RowType.EQUAL;
            if (diff && start < 0) start = i;
            else if (!diff && start >= 0) { out.add(new int[]{start, i - 1}); start = -1; }
        }
        if (start >= 0) out.add(new int[]{start, rows.size() - 1});
        return out;
    }

    private void gotoBlock(int dir) {
        if (!diffShowing || blocks.isEmpty()) return;
        if (currentBlock < 0) currentBlock = dir > 0 ? 0 : blocks.size() - 1;
        else currentBlock = (currentBlock + dir + blocks.size()) % blocks.size();
        int[] b = blocks.get(currentBlock);
        int lh = leftPanel.lineHeight;
        int target = Math.max(0, b[0] * lh - lh * 2);
        leftDiffScroll.getVerticalScrollBar().setValue(target);
        leftPanel.setCurrentBlock(b);
        rightPanel.setCurrentBlock(b);
        statsLabel.setText(String.format("Difference %d of %d   \u2022   lines %d\u2013%d",
                currentBlock + 1, blocks.size(), b[0] + 1, b[1] + 1));
    }

    private void syncScroll(boolean fromLeft) {
        if (syncing) return;
        syncing = true;
        try {
            JScrollBar src = (fromLeft ? leftDiffScroll : rightDiffScroll).getVerticalScrollBar();
            JScrollBar dst = (fromLeft ? rightDiffScroll : leftDiffScroll).getVerticalScrollBar();
            if (dst.getValue() != src.getValue()) dst.setValue(src.getValue());
        } finally { syncing = false; }
    }

    private void changeFont(int delta) {
        fontSize = Math.max(8, Math.min(30, fontSize + delta));
        applyEditFont();
        leftPanel.setFontSize(fontSize);
        rightPanel.setFontSize(fontSize);
    }

    private void applyEditFont() {
        Font f = new Font(Font.MONOSPACED, Font.PLAIN, fontSize);
        leftArea.setFont(f);
        rightArea.setFont(f);
    }

    private static List<int[]> shortestEdit(String[] a, String[] b) {
        int n = a.length, m = b.length, max = n + m, off = max;
        int[] v = new int[2 * max + 2];
        List<int[]> trace = new ArrayList<>();
        for (int d = 0; d <= max; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[k - 1 + off] < v[k + 1 + off])) x = v[k + 1 + off];
                else x = v[k - 1 + off] + 1;
                int y = x - k;
                while (x < n && y < m && a[x].equals(b[y])) { x++; y++; }
                v[k + off] = x;
                if (x >= n && y >= m) return trace;
            }
        }
        return trace;
    }

    private static List<Edit> buildEdits(String[] a, String[] b) {
        List<int[]> trace = shortestEdit(a, b);
        int off = a.length + b.length;
        int x = a.length, y = b.length;
        List<Edit> edits = new ArrayList<>();
        for (int d = trace.size() - 1; d >= 0; d--) {
            int[] v = trace.get(d);
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && v[k - 1 + off] < v[k + 1 + off])) prevK = k + 1;
            else prevK = k - 1;
            int prevX = v[prevK + off];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                edits.add(new Edit(EType.EQL, x - 1, y - 1));
                x--; y--;
            }
            if (d > 0) {
                if (x == prevX) edits.add(new Edit(EType.INS, -1, y - 1));
                else edits.add(new Edit(EType.DEL, x - 1, -1));
            }
            x = prevX; y = prevY;
        }
        Collections.reverse(edits);
        return edits;
    }

    private static List<Row> buildRows(List<Edit> edits, String[] a, String[] b) {
        List<Row> rows = new ArrayList<>();
        List<Edit> dels = new ArrayList<>(), ins = new ArrayList<>();
        for (Edit e : edits) {
            if (e.t == EType.DEL) dels.add(e);
            else if (e.t == EType.INS) ins.add(e);
            else {
                flush(dels, ins, rows, a, b);
                dels.clear(); ins.clear();
                Row r = new Row();
                r.type = RowType.EQUAL;
                r.left = a[e.ai]; r.right = b[e.bi];
                r.leftNum = e.ai + 1; r.rightNum = e.bi + 1;
                rows.add(r);
            }
        }
        flush(dels, ins, rows, a, b);
        return rows;
    }

    private static void flush(List<Edit> dels, List<Edit> ins, List<Row> rows,
                              String[] a, String[] b) {
        int pc = Math.min(dels.size(), ins.size());
        for (int i = 0; i < pc; i++) {
            Row r = new Row();
            r.type = RowType.CHANGED;
            r.left = a[dels.get(i).ai];
            r.right = b[ins.get(i).bi];
            r.leftNum = dels.get(i).ai + 1;
            r.rightNum = ins.get(i).bi + 1;
            inlineDiff(r);
            rows.add(r);
        }
        for (int i = pc; i < dels.size(); i++) {
            Row r = new Row();
            r.type = RowType.DELETED;
            r.left = a[dels.get(i).ai];
            r.right = null;
            r.leftNum = dels.get(i).ai + 1;
            r.rightNum = -1;
            rows.add(r);
        }
        for (int i = pc; i < ins.size(); i++) {
            Row r = new Row();
            r.type = RowType.ADDED;
            r.left = null;
            r.right = b[ins.get(i).bi];
            r.leftNum = -1;
            r.rightNum = ins.get(i).bi + 1;
            rows.add(r);
        }
    }

    private static void inlineDiff(Row r) {
        String L = r.left, R = r.right;
        int la = L.length(), lb = R.length();
        int p = 0;
        while (p < la && p < lb && L.charAt(p) == R.charAt(p)) p++;
        int s = 0;
        while (s < la - p && s < lb - p && L.charAt(la - 1 - s) == R.charAt(lb - 1 - s)) s++;
        int le = la - s, re = lb - s;
        r.leftHi = (p < le) ? new int[]{p, le} : null;
        r.rightHi = (p < re) ? new int[]{p, re} : null;
    }

    static final Color BG_DELETED = new Color(255, 224, 224);
    static final Color BG_ADDED   = new Color(223, 246, 221);
    static final Color BG_CHANGED = new Color(255, 246, 204);
    static final Color BG_FILLER  = new Color(240, 240, 240);
    static final Color IN_DELETED = new Color(255, 176, 176);
    static final Color IN_ADDED   = new Color(160, 214, 155);
    static final Color GUTTER_BG  = new Color(245, 245, 245);
    static final Color GUTTER_LN  = new Color(215, 215, 215);
    static final Color GUTTER_FG  = new Color(130, 130, 130);
    static final Color MARKER     = new Color(40, 110, 210);

    final class SidePanel extends JComponent implements Scrollable {
        final boolean isLeft;
        List<Row> rows = new ArrayList<>();
        int[] currentBlock = null;
        Font font;
        int lineHeight = 18, charWidth = 8, ascent = 14, gutterW = 46;
        int maxCols = 40;
        final int pad = 6;

        SidePanel(boolean isLeft) {
            this.isLeft = isLeft;
            setBackground(Color.WHITE);
            setOpaque(true);
            applyFont(fontSize);
        }

        void setRows(List<Row> rows) {
            this.rows = rows;
            this.currentBlock = null;
            recomputeMetrics();
            revalidate();
            repaint();
        }

        void setCurrentBlock(int[] b) { this.currentBlock = b; repaint(); }

        void setFontSize(int size) {
            applyFont(size);
            recomputeMetrics();
            revalidate();
            repaint();
        }

        private void applyFont(int size) {
            font = new Font(Font.MONOSPACED, Font.PLAIN, size);
            FontMetrics fm = getFontMetrics(font);
            lineHeight = fm.getHeight();
            ascent = fm.getAscent();
            charWidth = Math.max(1, fm.charWidth('m'));
        }

        private void recomputeMetrics() {
            int digits = Math.max(2, String.valueOf(Math.max(1, rows.size())).length());
            gutterW = digits * charWidth + 2 * pad;
            int m = 10;
            for (Row r : rows) {
                String t = isLeft ? r.left : r.right;
                if (t != null && t.length() > m) m = t.length();
            }
            maxCols = m;
        }

        @Override public Dimension getPreferredSize() {
            int w = gutterW + pad + maxCols * charWidth + pad;
            int h = Math.max(1, rows.size()) * lineHeight;
            return new Dimension(w, h);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();

            if (rows.isEmpty()) return;

            Rectangle clip = g.getClipBounds();
            Rectangle vis = getVisibleRect();
            int gx = vis.x;
            int first = Math.max(0, clip.y / lineHeight);
            int last = Math.min(rows.size() - 1, (clip.y + clip.height) / lineHeight);

            for (int i = first; i <= last; i++) {
                Row r = rows.get(i);
                int y = i * lineHeight;
                String text = isLeft ? r.left : r.right;

                g.setColor(bgFor(r));
                g.fillRect(0, y, getWidth(), lineHeight);

                if (r.type == RowType.CHANGED && text != null) {
                    int[] hi = isLeft ? r.leftHi : r.rightHi;
                    if (hi != null) {
                        int hx = gutterW + pad + hi[0] * charWidth;
                        int hw = (hi[1] - hi[0]) * charWidth;
                        g.setColor(isLeft ? IN_DELETED : IN_ADDED);
                        g.fillRect(hx, y, hw, lineHeight);
                    }
                }

                if (text != null) {
                    g.setColor(Color.BLACK);
                    g.drawString(text, gutterW + pad, y + ascent);
                }

                g.setColor(GUTTER_BG);
                g.fillRect(gx, y, gutterW, lineHeight);
                g.setColor(GUTTER_LN);
                g.drawLine(gx + gutterW - 1, y, gx + gutterW - 1, y + lineHeight);
                int num = isLeft ? r.leftNum : r.rightNum;
                if (num > 0) {
                    String ns = String.valueOf(num);
                    int nx = gx + gutterW - pad - fm.stringWidth(ns);
                    g.setColor(GUTTER_FG);
                    g.drawString(ns, nx, y + ascent);
                }

                if (currentBlock != null && i >= currentBlock[0] && i <= currentBlock[1]) {
                    g.setColor(MARKER);
                    g.fillRect(gx, y, 3, lineHeight);
                }
            }
        }

        private Color bgFor(Row r) {
            switch (r.type) {
                case CHANGED: return BG_CHANGED;
                case DELETED: return isLeft ? BG_DELETED : BG_FILLER;
                case ADDED:   return isLeft ? BG_FILLER : BG_ADDED;
                default:      return Color.WHITE;
            }
        }

        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return lineHeight; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return o == SwingConstants.VERTICAL ? r.height : r.width;
        }
        public boolean getScrollableTracksViewportWidth() { return false; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // File drop via a DropTarget so it does NOT replace the text area's default
    // TransferHandler -- clipboard paste (Ctrl+V) keeps working normally.
    static final class FileDrop {
        interface Handler { void onFile(File f); }
        FileDrop(JComponent c, Handler h) {
            c.setDropTarget(new DropTarget(c, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
                @SuppressWarnings("unchecked")
                public void drop(DropTargetDropEvent e) {
                    try {
                        if (e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            e.acceptDrop(DnDConstants.ACTION_COPY);
                            List<File> files = (List<File>) e.getTransferable()
                                    .getTransferData(DataFlavor.javaFileListFlavor);
                            if (files != null && !files.isEmpty()) h.onFile(files.get(0));
                            e.dropComplete(true);
                        } else {
                            e.rejectDrop();
                        }
                    } catch (Exception ex) {
                        e.dropComplete(false);
                    }
                }
            }, true));
        }
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignore) { }
        SwingUtilities.invokeLater(() -> new ScriptCompare().setVisible(true));
    }
}
