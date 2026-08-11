import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;

/**
 * LR Correlator - NestWorks
 * Compile:  javac LRCorrelator.java
 * Run:      java  LRCorrelator
 */
public class LRCorrelator extends JFrame {

    static final Color BG_DEEP    = new Color(0x0d, 0x0d, 0x1a);
    static final Color BG_PANEL   = new Color(0x12, 0x12, 0x2a);
    static final Color BG_EDITOR  = new Color(0x0a, 0x0a, 0x16);
    static final Color BG_CORR    = new Color(0x0d, 0x0d, 0x1f);
    static final Color BG_META    = new Color(0x10, 0x10, 0x26);
    static final Color BG_SEARCH  = new Color(0x1a, 0x1a, 0x30);
    static final Color BG_TOOLBAR = new Color(0x09, 0x09, 0x1a);
    static final Color BORDER     = new Color(0x2a, 0x2a, 0x4c);
    static final Color FG_DEFAULT = new Color(0xc8, 0xc8, 0xe8);
    static final Color FG_MUTED   = new Color(0x60, 0x60, 0xa0);
    static final Color C_FUNCTION = new Color(0x7a, 0xb8, 0xf5);
    static final Color C_KEYWORD  = new Color(0xc5, 0x86, 0xc0);
    static final Color C_STRING   = new Color(0xce, 0x91, 0x78);
    static final Color C_CONSTANT = new Color(0x4e, 0xc9, 0xb0);
    static final Color C_COMMENT  = new Color(0x6a, 0x99, 0x55);
    static final Color C_PARAM    = new Color(0xf0, 0xc0, 0x60);
    static final Color C_NUMBER   = new Color(0xb5, 0xce, 0xa8);
    static final Color HL_SEARCH_BG   = new Color(0x4a, 0x4a, 0x00, 150);
    static final Color HL_CURRENT_BG  = new Color(0x80, 0x60, 0x00, 180);
    static final Color HL_DYNAMIC_BG  = new Color(0x7a, 0x3a, 0x00, 160);
    static final Color HL_DYNAMIC_FG  = new Color(0xff, 0xaa, 0x55);
    static final Color HL_BOUNDARY_BG = new Color(0x2a, 0x5a, 0x3a, 130);
    static final Color HL_BOUNDARY_FG = new Color(0x7a, 0xde, 0x7a);
    static final Color HL_CORR_BG     = new Color(0x2a, 0x4a, 0x60, 130);
    static final Color HL_CORR_FG     = new Color(0xf0, 0xc0, 0x60);
    static final Color BTN_BLUE   = new Color(0x5b, 0x8d, 0xd9);
    static final Color BTN_GREEN  = new Color(0x5d, 0xcc, 0x7a);
    static final Color BTN_YELLOW = new Color(0xf0, 0xc0, 0x60);
    static final Color BTN_PURPLE = new Color(0xa0, 0x70, 0xcc);
    static final Color STATUS_OK  = new Color(0x5d, 0xcc, 0x7a);
    static final Color STATUS_ERR = new Color(0xe0, 0x70, 0x70);
    static final Color STATUS_INF = new Color(0x7a, 0xb8, 0xf5);

    static final Font MONO_FONT  = new Font("Consolas", Font.PLAIN, 13);
    static final Font SMALL_FONT = new Font("Consolas", Font.PLAIN, 11);
    static final Font UI_FONT    = new Font("Segoe UI",  Font.PLAIN, 12);
    static final Font TINY_FONT  = new Font("Segoe UI",  Font.BOLD,   9);

    private final Set<String> usedParamNames = new LinkedHashSet<>();
    private CorrelationResult currentResult;

    // Skip per-token syntax colouring above this size - the regex + attribute
    // pass over a multi-MB document is what makes large Action files sluggish.
    static final int SYNTAX_LIMIT = 200_000;

    // Boundary/value highlights on the CodeGen JTextArea use the Highlighter API
    // (background only) instead of styled character attributes.
    private final java.util.List<Object> boundaryTags = new ArrayList<>();
    private final Highlighter.HighlightPainter dynPainter =
        new DefaultHighlighter.DefaultHighlightPainter(new Color(0x7a, 0x3a, 0x00));
    private final Highlighter.HighlightPainter bndPainter =
        new DefaultHighlighter.DefaultHighlightPainter(new Color(0x22, 0x55, 0x33));

    private CodeTextPane actionPane;
    private JTextArea  codeGenPane;
    private SearchPanel actionSearch;
    private SearchPanel codeGenSearch;
    // Raw file content - used for all searching/processing
    // JTextPane.getText() cannot be trusted for large styled documents
    private String rawActionText  = "";
    private String rawCodeGenText = "";
    private JTextArea  corrCodeArea;
    private JLabel     metaSnapshot, metaSource, metaOrdinal, metaScope;
    private JLabel     statusLabel;
    private JList<String> matchList;
    private DefaultListModel<String> matchModel;
    private JPanel     corrStudio;
    private JLabel     corrPlaceholder;

    // Panels + visibility flags for the show/hide toggles. The editor content,
    // highlights and raw-text mirrors live on these panels, so re-parenting them
    // when the layout is rebuilt preserves everything.
    private JPanel   actionEditorPanel;
    private JPanel   codeGenEditorPanel;
    private JPanel   studioWrapperPanel;
    private JPanel   mainContainer;
    private boolean  showActionEditor  = true;
    private boolean  showCodeGenEditor = true;
    private boolean  showStudioPanel   = true;
    private JToggleButton tglAction, tglCodeGen, tglStudio, tglWrap;

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new LRCorrelator().setVisible(true));
    }

    public LRCorrelator() {
        super("LR Correlator - NestWorks");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1440, 900);
        setMinimumSize(new Dimension(1000, 700));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DEEP);
        setLayout(new BorderLayout(0, 0));
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildMainLayout(), BorderLayout.CENTER);
    }

    // =========================================================================
    // TOOLBAR
    // =========================================================================
    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        bar.setBackground(BG_TOOLBAR);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        JLabel logo = new JLabel("LR Correlator");
        logo.setFont(new Font("Segoe UI", Font.BOLD, 14));
        logo.setForeground(C_FUNCTION);
        JLabel sub = new JLabel("  by NestWorks");
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        sub.setForeground(FG_MUTED);
        JButton loadAction  = toolButton("Load Action File",  BTN_BLUE);
        JButton loadCodeGen = toolButton("Load CodeGen File", BTN_GREEN);
        JButton newSession  = toolButton("New Session",       BTN_PURPLE);
        loadAction.addActionListener(e  -> loadFile(actionPane,  true));
        loadCodeGen.addActionListener(e -> loadFile(codeGenPane, false));
        newSession.addActionListener(e  -> newSession());

        // View toggles - hide/show each section
        tglAction  = toggleButton("Action",  BTN_BLUE);
        tglCodeGen = toggleButton("CodeGen", BTN_GREEN);
        tglStudio  = toggleButton("Studio",  C_FUNCTION);
        tglAction.addActionListener(e  -> { showActionEditor  = tglAction.isSelected();  restructureLayout(); });
        tglCodeGen.addActionListener(e -> { showCodeGenEditor = tglCodeGen.isSelected(); restructureLayout(); });
        tglStudio.addActionListener(e  -> { showStudioPanel   = tglStudio.isSelected();  restructureLayout(); });

        tglWrap = toggleButton("Wrap", C_PARAM);
        tglWrap.addActionListener(e -> {
            boolean on = tglWrap.isSelected();
            if (actionPane  != null) actionPane.setWrap(on);
            if (codeGenPane != null) { codeGenPane.setLineWrap(on); codeGenPane.setWrapStyleWord(on); }
        });

        JLabel viewLbl = new JLabel("View:");
        viewLbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        viewLbl.setForeground(FG_MUTED);

        bar.add(logo); bar.add(sub);
        bar.add(Box.createRigidArea(new Dimension(20, 0)));
        bar.add(loadAction); bar.add(loadCodeGen); bar.add(newSession);
        bar.add(Box.createRigidArea(new Dimension(18, 0)));
        bar.add(viewLbl);
        bar.add(tglAction); bar.add(tglCodeGen); bar.add(tglStudio);
        bar.add(Box.createRigidArea(new Dimension(10, 0)));
        bar.add(tglWrap);
        return bar;
    }

    // =========================================================================
    // MAIN LAYOUT  (Studio TOP, Editors BOTTOM)
    // =========================================================================
    private JPanel buildMainLayout() {
        mainContainer = new JPanel(new BorderLayout(0, 0));
        mainContainer.setBackground(BG_DEEP);
        studioWrapperPanel = buildCorrPanel();
        studioWrapperPanel.setPreferredSize(new Dimension(0, 240));
        studioWrapperPanel.setMinimumSize(new Dimension(0, 180));
        actionEditorPanel  = buildEditorPanel("ACTION FILE",          true,  BTN_BLUE);
        codeGenEditorPanel = buildEditorPanel("CODE GENERATION FILE", false, BTN_GREEN);
        restructureLayout();
        return mainContainer;
    }

    // Rebuild the split arrangement from the three visibility flags. Any panel
    // can be hidden; the persistent panels are simply re-parented into freshly
    // created split panes, which keeps all their text / highlights intact.
    private void restructureLayout() {
        Component editors = null;
        if (showActionEditor && showCodeGenEditor) {
            JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                    actionEditorPanel, codeGenEditorPanel);
            sp.setResizeWeight(0.5);
            sp.setDividerLocation(0.5);
            sp.setDividerSize(5);
            sp.setBackground(BG_DEEP);
            sp.setBorder(null);
            editors = sp;
        } else if (showActionEditor) {
            editors = actionEditorPanel;
        } else if (showCodeGenEditor) {
            editors = codeGenEditorPanel;
        }

        Component content;
        if (showStudioPanel && editors != null) {
            JSplitPane v = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                    studioWrapperPanel, editors);
            v.setDividerLocation(240);
            v.setResizeWeight(0.0);
            v.setDividerSize(5);
            v.setBackground(BG_DEEP);
            v.setBorder(null);
            content = v;
        } else if (showStudioPanel) {
            content = studioWrapperPanel;
        } else if (editors != null) {
            content = editors;
        } else {
            JLabel blank = new JLabel(
                "<html><center><font color='#3a3a6a'>All panels hidden - " +
                "use the View toggles in the toolbar to bring one back</font></center></html>",
                SwingConstants.CENTER);
            blank.setOpaque(true);
            blank.setBackground(BG_DEEP);
            content = blank;
        }

        mainContainer.removeAll();
        mainContainer.add(content, BorderLayout.CENTER);
        mainContainer.revalidate();
        mainContainer.repaint();
    }

    // =========================================================================
    // EDITOR PANEL
    // =========================================================================
    private JPanel buildEditorPanel(String title, boolean isAction, Color accent) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_PANEL);
        JLabel header = new JLabel("  " + title);
        header.setFont(TINY_FONT);
        header.setForeground(accent);
        header.setBackground(new Color(0x10, 0x10, 0x2a));
        header.setOpaque(true);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        header.setPreferredSize(new Dimension(0, 22));

        // Action pane stays a JTextPane so it can carry per-token syntax colours.
        // CodeGen uses a JTextArea (PlainDocument) - dramatically cheaper to load
        // or paste large Pega response dumps into, since it only needs background
        // highlights (drawn via the Highlighter), not styled character runs.
        final JTextComponent comp;
        CodeTextPane actionTp = null;
        if (isAction) {
            actionTp = new CodeTextPane();
            comp = actionTp;
        } else {
            JTextArea area = new JTextArea();
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setTabSize(4);
            comp = area;
        }
        comp.setBackground(BG_EDITOR);
        comp.setForeground(FG_DEFAULT);
        comp.setFont(MONO_FONT);
        comp.setCaretColor(FG_DEFAULT);
        comp.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JScrollPane scroll = new JScrollPane(comp);
        scroll.setBackground(BG_EDITOR);
        scroll.getViewport().setBackground(BG_EDITOR);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setRowHeaderView(new LineNumberGutter(comp));

        // Normalise line endings on input (keeps document offsets stable) with a
        // fast path when there is no CR. The raw-text mirrors are refreshed on
        // demand (refreshRawFromPanes) rather than re-copied on every keystroke,
        // so large pastes and edits stay responsive.
        ((AbstractDocument) comp.getDocument()).setDocumentFilter(new NewlineFilter());

        SearchPanel searchBar = new SearchPanel(comp);
        searchBar.setVisible(false);
        if (isAction) {
            actionPane   = actionTp;
            actionSearch = searchBar;
            attachActionContextMenu(actionTp);
        } else {
            codeGenPane   = (JTextArea) comp;
            codeGenSearch = searchBar;
        }
        comp.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_F) {
                    searchBar.setVisible(!searchBar.isVisible());
                    if (searchBar.isVisible()) searchBar.focusSearch();
                }
            }
        });
        panel.add(header,    BorderLayout.NORTH);
        panel.add(scroll,    BorderLayout.CENTER);
        panel.add(searchBar, BorderLayout.SOUTH);
        return panel;
    }

    // =========================================================================
    // CORRELATION STUDIO PANEL
    // =========================================================================
    private JPanel buildCorrPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(BG_PANEL);
        outer.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        JLabel header = new JLabel("  CORRELATION STUDIO");
        header.setFont(TINY_FONT);
        header.setForeground(C_FUNCTION);
        header.setBackground(new Color(0x10, 0x10, 0x2a));
        header.setOpaque(true);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        header.setPreferredSize(new Dimension(0, 22));
        corrPlaceholder = new JLabel(
            "<html><center><font color='#3a3a6a' size='3'>" +
            "Load or paste both files, select a dynamic value in the Action file, " +
            "right-click > Process Selection" +
            "</font></center></html>");
        corrPlaceholder.setHorizontalAlignment(SwingConstants.CENTER);
        corrPlaceholder.setBackground(BG_PANEL);
        corrPlaceholder.setOpaque(true);
        corrStudio = buildStudio();
        corrStudio.setVisible(false);
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(BG_PANEL);
        center.add(corrPlaceholder, BorderLayout.CENTER);
        center.add(corrStudio,      BorderLayout.CENTER);
        outer.add(header, BorderLayout.NORTH);
        outer.add(center, BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildStudio() {
        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBackground(BG_PANEL);

        // TOP ROW: meta fields + buttons
        JPanel topRow = new JPanel(new BorderLayout(0, 0));
        topRow.setBackground(BG_META);
        topRow.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
            BorderFactory.createEmptyBorder(4, 10, 4, 10)));

        JPanel metaFields = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        metaFields.setBackground(BG_META);
        metaSnapshot = metaVal(); metaSource = metaVal();
        metaOrdinal  = metaVal(); metaScope  = metaVal();
        metaFields.add(metaKey("Snapshot:"));  metaFields.add(metaSnapshot);
        metaFields.add(metaSep());
        metaFields.add(metaKey("Source:"));    metaFields.add(metaSource);
        metaFields.add(metaSep());
        metaFields.add(metaKey("Ordinal:"));   metaFields.add(metaOrdinal);
        metaFields.add(metaSep());
        metaFields.add(metaKey("Matches:"));   metaFields.add(metaScope);

        JButton editBtn  = studioButton("Edit",      BTN_BLUE);
        JButton testBtn  = studioButton("Test",      BTN_YELLOW);
        JButton corrBtn  = studioButton("Correlate", BTN_GREEN);
        JButton resetBtn = studioButton("Reset",     BTN_PURPLE);
        editBtn.addActionListener(e  -> enableEditing());
        testBtn.addActionListener(e  -> runTest());
        corrBtn.addActionListener(e  -> doCorrelate());
        resetBtn.addActionListener(e -> resetCorrelation());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnRow.setBackground(BG_META);
        btnRow.add(editBtn); btnRow.add(testBtn); btnRow.add(corrBtn); btnRow.add(resetBtn);
        topRow.add(metaFields, BorderLayout.CENTER);
        topRow.add(btnRow,     BorderLayout.EAST);

        // CODE PANEL (left 50%)
        JPanel codePanel = new JPanel(new BorderLayout(0, 0));
        codePanel.setBackground(BG_PANEL);
        codePanel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 3));
        JLabel codeLbl = sectionLabel("GENERATED CORRELATION CODE");
        corrCodeArea = new JTextArea();
        corrCodeArea.setBackground(BG_CORR);
        corrCodeArea.setForeground(new Color(0x7d, 0xe8, 0x7d));
        corrCodeArea.setFont(SMALL_FONT);
        corrCodeArea.setCaretColor(FG_DEFAULT);
        corrCodeArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        corrCodeArea.setEditable(false);
        corrCodeArea.setLineWrap(false);
        JScrollPane codeScroll = new JScrollPane(corrCodeArea);
        codeScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        codeScroll.setBackground(BG_CORR);
        codeScroll.getViewport().setBackground(BG_CORR);
        codePanel.add(codeLbl,    BorderLayout.NORTH);
        codePanel.add(codeScroll, BorderLayout.CENTER);

        // MATCH PANEL (right 50%)
        JPanel matchPanel = new JPanel(new BorderLayout(0, 0));
        matchPanel.setBackground(BG_PANEL);
        matchPanel.setBorder(BorderFactory.createEmptyBorder(2, 3, 2, 6));
        JLabel matchLbl = sectionLabel("BOUNDARY MATCHES");
        matchModel = new DefaultListModel<>();
        matchList  = new JList<>(matchModel);
        matchList.setBackground(BG_CORR);
        matchList.setForeground(FG_MUTED);
        matchList.setFont(SMALL_FONT);
        matchList.setSelectionBackground(new Color(0x2a, 0x2a, 0x4c));
        matchList.setSelectionForeground(C_PARAM);
        matchList.setFixedCellHeight(22);
        matchList.setCellRenderer(new MatchListRenderer());
        JScrollPane matchScroll = new JScrollPane(matchList);
        matchScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        matchScroll.setBackground(BG_CORR);
        matchScroll.getViewport().setBackground(BG_CORR);
        matchPanel.add(matchLbl,    BorderLayout.NORTH);
        matchPanel.add(matchScroll, BorderLayout.CENTER);

        JSplitPane mainSplit = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, codePanel, matchPanel);
        mainSplit.setResizeWeight(0.5);
        mainSplit.setDividerLocation(0.5);
        mainSplit.setDividerSize(4);
        mainSplit.setBackground(BG_PANEL);
        mainSplit.setBorder(null);

        // STATUS BAR
        statusLabel = new JLabel(" ");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        statusLabel.setForeground(FG_MUTED);
        statusLabel.setBackground(new Color(0x0d, 0x0d, 0x22));
        statusLabel.setOpaque(true);
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
            BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        statusLabel.setPreferredSize(new Dimension(0, 24));

        p.add(topRow,     BorderLayout.NORTH);
        p.add(mainSplit,  BorderLayout.CENTER);
        p.add(statusLabel, BorderLayout.SOUTH);
        return p;
    }

    // =========================================================================
    // CONTEXT MENU
    // =========================================================================
    private void attachActionContextMenu(JTextPane tp) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(new Color(0x1a, 0x1a, 0x2e));
        menu.setBorder(BorderFactory.createLineBorder(BORDER));
        JMenuItem processItem = menuItem(">> Process Selection",  C_FUNCTION);
        JMenuItem findInCg    = menuItem("   Find in CodeGen",    new Color(0xa0, 0xc0, 0xa0));
        JMenuItem copyItem    = menuItem("   Copy",               FG_MUTED);
        processItem.addActionListener(e -> processSelectedValue());
        findInCg.addActionListener(e -> {
            String sel = tp.getSelectedText();
            if (sel != null && !sel.trim().isEmpty()) {
                codeGenSearch.setVisible(true);
                codeGenSearch.searchFor(sel.trim());
            }
        });
        copyItem.addActionListener(e -> {
            String sel = tp.getSelectedText();
            if (sel != null)
                Toolkit.getDefaultToolkit().getSystemClipboard()
                       .setContents(new java.awt.datatransfer.StringSelection(sel), null);
        });
        menu.add(processItem); menu.add(findInCg);
        menu.addSeparator();   menu.add(copyItem);
        tp.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e)  { if (e.isPopupTrigger()) menu.show(tp, e.getX(), e.getY()); }
            public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) menu.show(tp, e.getX(), e.getY()); }
        });
    }

    // =========================================================================
    // PROCESS
    // =========================================================================
    // Pull current pane content into the raw mirrors on demand, instead of
    // copying the whole document on every keystroke. Called only on
    // Process / Correlate / Test - cheap and keeps typing/pasting responsive.
    private void refreshRawFromPanes() {
        if (actionPane  != null) rawActionText  = safeDocText(actionPane);
        if (codeGenPane != null) rawCodeGenText = safeDocText(codeGenPane);
    }

    private void processSelectedValue() {
        refreshRawFromPanes();
        String selected = actionPane.getSelectedText();
        if (selected == null || selected.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Please select a dynamic value in the Action file first.",
                "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String codeGen = rawCodeGenText;
        if (codeGen == null || codeGen.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Please load or paste the Code Generation file content first.",
                "No CodeGen", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String dyn = selected.trim();
        CorrelationResult result = runEngine(dyn, codeGen);
        if (result == null) {
            JOptionPane.showMessageDialog(this,
                "\"" + dyn + "\" was not found in the CodeGen file.\n" +
                "Tip: Make sure both files are from the same recording.",
                "Not Found", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        currentResult = result;
        highlightBoundariesInCodeGen(result.lb, result.responseValue, result.rb, result.sectionStart);
        showStudio(result);
    }

    // =========================================================================
    // CORRELATION ENGINE
    // =========================================================================
    private CorrelationResult runEngine(String dyn, String codeGen) {
        // ------------------------------------------------------------------
        // ENCODING-ROBUST ANCHORING
        //
        // The value we SELECTED in the Action file is the value AS SENT. For
        // Pega tokens that tail is often DOUBLE URL-encoded (...%253D%253D),
        // because base64 '==' padding -> '%3D%3D' in the response (one encode),
        // then the '%' itself is re-encoded to '%25' on the way out.
        //
        // The real SOURCE of the value is an EARLIER RESPONSE, where the same
        // token appears SINGLE-encoded (...%3D%3D) behind clean boundaries
        // (e.g. pzuiactionzzz="...").  An exact indexOf(dyn) can therefore only
        // ever match a request / usage echo - never the response - which is why
        // correlation kept anchoring on the SENDING snapshot itself.
        //
        // Fix: search on an ENCODING-INVARIANT KEY (the clean prefix before the
        // first '%'), prefer the first occurrence that sits inside a Response
        // section, and read the value back in whatever form actually lives there.
        // ------------------------------------------------------------------
        // The selected value may be wrapped by VuGen across adjacent "..." C
        // string literals when it is long, so the RAW selection can carry
        // internal quotes + newlines + indentation. Clean those seams out to get
        // the logical value we SEARCH for in the CodeGen. The raw form is kept
        // as r.dynamicValue for the Action-file replacement, because that split
        // text is what physically exists in the file.
        String cleanDyn  = cleanActionValue(dyn);
        String searchKey = buildSearchKey(cleanDyn);

        int dynIdx = findFirstResponseOccurrence(codeGen, searchKey);
        boolean fromResponse = dynIdx >= 0;
        if (dynIdx < 0) dynIdx = codeGen.indexOf(searchKey);  // no response hit -> first key hit
        if (dynIdx < 0) dynIdx = codeGen.indexOf(cleanDyn);   // last resort -> exact clean value
        if (dynIdx < 0) return null;

        // Value exactly as it appears at the anchor (the response form). All of
        // boundary extraction, ordinal counting, highlighting and Test run
        // against THIS; the original (possibly double-encoded) `dyn` is kept
        // only for the Action-file replacement.
        String responseValue = sliceResponseValue(codeGen, dynIdx);
        if (responseValue.isEmpty()) responseValue = dyn;

        // SNAPSHOT ID: forward search from the anchor for "Snapshot=tX.inf".
        String snapshotId = extractSnapshotId(codeGen, dynIdx);

        // SOURCE TYPE: nearest Response Body/Header marker above the anchor.
        String sourceType = extractSourceType(codeGen, dynIdx);

        // SECTION BOUNDARIES around the anchor.
        int sectionStart = findSectionStart(codeGen, dynIdx);
        int sectionEnd   = findSectionEnd(codeGen, dynIdx + responseValue.length());

        // Tight window for boundary extraction (the full section can be MBs).
        int ctxLeft  = Math.max(0, dynIdx - 500);
        int ctxRight = Math.min(codeGen.length(), dynIdx + responseValue.length() + 500);
        String tightContext = codeGen.substring(ctxLeft, ctxRight);
        int dynIdxInCtx = dynIdx - ctxLeft;

        String[] bounds = extractBoundaries(responseValue, tightContext, dynIdxInCtx);

        // Full section for ordinal matching.
        String snapshotContext = codeGen.substring(
            Math.max(0, sectionStart),
            Math.min(codeGen.length(), sectionEnd));
        if (snapshotContext.indexOf(responseValue) < 0) {
            snapshotContext = tightContext;
        }
        String lb = bounds[0];
        String rb = bounds[1];

        List<String> all = findAllMatchesInScope(snapshotContext, lb, rb);
        int ordinal      = findOrdinal(all, responseValue);
        String paramName = generateParamName(lb, snapshotId);

        CorrelationResult r = new CorrelationResult();
        r.paramName       = paramName;
        r.lb              = lb;
        r.rb              = rb;
        r.dynamicValue    = dyn;
        r.responseValue   = responseValue;
        r.fromResponse    = fromResponse;
        r.snapshotId      = snapshotId;
        r.sourceType      = sourceType;
        r.ordinal         = ordinal;
        r.allMatches      = all;
        r.snapshotContext = snapshotContext;
        r.sectionStart    = sectionStart;
        r.generatedCode   = buildCode(r);
        return r;
    }

    // =========================================================================
    // ENCODING-ROBUST ANCHORING HELPERS
    // =========================================================================
    // Response vs Request section markers. Response markers match the existing
    // engine; request markers let us reject occurrences where the value is only
    // being SENT. Tune these to whatever your generation log actually prints.
    private static final String[] RESP_MARKERS = {
        "Response Body for", "Response Body For",
        "Response Header for", "Response Header For"
    };
    private static final String[] REQ_MARKERS = {
        "Request Body for", "Request Body For",
        "Request Header for", "Request Header For",
        "Request Body After parameterization", "Request Data"
    };

    // Undo VuGen line-wrapping of long values. A long "Value=..." is emitted as
    // several adjacent C string literals -  "...part1"  "part2..."  - split
    // across lines, so a raw selection can contain internal quote + newline +
    // indentation seams. Collapse those so the logical value can be found in the
    // response. (Only used for SEARCHING; the raw selection is still used for the
    // Action-file replacement, since the wrapped text is what physically exists.)
    private String cleanActionValue(String s) {
        if (s == null) return "";
        String v = s;
        // Join adjacent-literal seams:  "  <whitespace>  "   ->  (nothing)
        v = v.replaceAll("\"\\s*\"", "");
        // Drop a stray leading / trailing quote from selecting across a boundary
        v = v.replaceAll("^\\s*\"", "").replaceAll("\"\\s*$", "");
        return v.trim();
    }

    // Encoding-invariant key: the clean prefix before the first '%'. Base64 /
    // Pega tokens keep a long unique head, so this stays selective while
    // ignoring however many URL-encoding layers sit on the tail. Values with no
    // encoding (or too short a prefix to trust) fall back to an exact match.
    private String buildSearchKey(String value) {
        if (value == null) return "";
        int pct = value.indexOf('%');
        if (pct >= 16) return value.substring(0, pct);
        return value;
    }

    // First occurrence of `key` that sits inside a Response section.
    private int findFirstResponseOccurrence(String codeGen, String key) {
        if (key.isEmpty()) return -1;
        int from = 0, idx;
        while ((idx = codeGen.indexOf(key, from)) >= 0) {
            if (isInsideResponse(codeGen, idx)) return idx;
            from = idx + 1;
        }
        return -1;
    }

    // True when the nearest section header above idx is a Response, not a Request.
    // If the log has no request markers at all, any occurrence under a Response
    // marker still counts (req stays -1).
    private boolean isInsideResponse(String codeGen, int idx) {
        int resp = lastIndexOfAny(codeGen, RESP_MARKERS, idx);
        int req  = lastIndexOfAny(codeGen, REQ_MARKERS, idx);
        return resp >= 0 && resp > req;
    }

    private int lastIndexOfAny(String s, String[] needles, int fromIdx) {
        int best = -1;
        for (String n : needles) {
            int p = s.lastIndexOf(n, fromIdx);
            if (p > best) best = p;
        }
        return best;
    }

    // Read the value token starting at `start`, stopping at a structural
    // delimiter. Whatever encoding form is present at the anchor is captured
    // verbatim - '%' is NOT a delimiter, so encoded tails stay intact.
    private String sliceResponseValue(String codeGen, int start) {
        int i = start;
        int max = Math.min(codeGen.length(), start + 6000);
        while (i < max) {
            char c = codeGen.charAt(i);
            if (c == '"' || c == '\'' || c == '<' || c == '>' || c == '&' ||
                c == ' ' || c == '\n' || c == '\r' || c == '\t') break;
            i++;
        }
        return codeGen.substring(start, i);
    }

    // =========================================================================
    // SNAPSHOT ID
    // Search FORWARD from dynIdx for "Snapshot=tX.inf" - first match wins.
    // Falls back to backward search if not found forward.
    // =========================================================================
    private String extractSnapshotId(String codeGen, int dynIdx) {
        // Forward search first (value appears before Snapshot= line in codegen)
        String after = codeGen.substring(dynIdx);
        Matcher m1 = Pattern.compile(
            "Snapshot=([a-zA-Z0-9_.]+)\\.inf",
            Pattern.CASE_INSENSITIVE).matcher(after);
        if (m1.find()) return m1.group(1) + ".inf";

        // Forward: bare tN.inf reference
        Matcher m2 = Pattern.compile(
            "\\b(t\\d+|snapshot\\d+)\\.inf\\b",
            Pattern.CASE_INSENSITIVE).matcher(after);
        if (m2.find()) return m2.group(1) + ".inf";

        // Backward fallback: last Snapshot=tX.inf before the value
        String before = codeGen.substring(0, dynIdx);
        Matcher m3 = Pattern.compile(
            "Snapshot=([a-zA-Z0-9_.]+)\\.inf",
            Pattern.CASE_INSENSITIVE).matcher(before);
        String last = null;
        while (m3.find()) last = m3.group(1) + ".inf";
        if (last != null) return last;

        // Backward: last bare tN.inf
        Matcher m4 = Pattern.compile(
            "\\b(t\\d+|snapshot\\d+)\\.inf\\b",
            Pattern.CASE_INSENSITIVE).matcher(before);
        last = null;
        while (m4.find()) last = m4.group(1) + ".inf";
        if (last != null) return last;

        return "Request";
    }

    // =========================================================================
    // SOURCE TYPE
    // Search BACKWARD from dynIdx. Find nearest occurrence of:
    //   "Response Body for"   -> Scope=Body
    //   "Response Header for" -> Scope=Headers
    // Higher index = closer to value = wins.
    // =========================================================================
    private String extractSourceType(String codeGen, int dynIdx) {
        String before = codeGen.substring(0, dynIdx);
        int bodyIdx = before.lastIndexOf("Response Body for");
        if (bodyIdx < 0) bodyIdx = before.lastIndexOf("Response Body For");

        int headerIdx = before.lastIndexOf("Response Header for");
        if (headerIdx < 0) headerIdx = before.lastIndexOf("Response Header For");
        if (headerIdx < 0) headerIdx = before.lastIndexOf("Header For");

        if (bodyIdx < 0 && headerIdx < 0) return "Body";
        if (bodyIdx >= headerIdx) return "Body";
        return "Headers";
    }

    // =========================================================================
    // SECTION START
    // Position of the nearest Response Body/Header marker above dynIdx.
    // =========================================================================
    private int findSectionStart(String codeGen, int dynIdx) {
        String before = codeGen.substring(0, dynIdx);
        int bodyIdx = before.lastIndexOf("Response Body for");
        if (bodyIdx < 0) bodyIdx = before.lastIndexOf("Response Body For");
        int headerIdx = before.lastIndexOf("Response Header for");
        if (headerIdx < 0) headerIdx = before.lastIndexOf("Response Header For");
        if (headerIdx < 0) headerIdx = before.lastIndexOf("Header For");
        int best = Math.max(bodyIdx, headerIdx);
        return best >= 0 ? best : 0;
    }

    // =========================================================================
    // SECTION END
    // Position of the next Response Body/Header marker after fromIdx.
    // =========================================================================
    private int findSectionEnd(String codeGen, int fromIdx) {
        if (fromIdx >= codeGen.length()) return codeGen.length();
        String after = codeGen.substring(fromIdx);
        Matcher m = Pattern.compile(
            "Response\\s+(?:Body|Header)\\s+[Ff]or",
            Pattern.CASE_INSENSITIVE).matcher(after);
        if (m.find() && m.start() > 0) return fromIdx + m.start();
        return codeGen.length();
    }

    // =========================================================================
    // BOUNDARY EXTRACTION
    // Always use a TIGHT window (200 chars) around the value position.
    // Pega responses use single quotes; support both ' and " variants.
    // =========================================================================
    private String[] extractBoundaries(String value, String context, int pos) {
        if (pos < 0) pos = context.indexOf(value);
        if (pos < 0) return new String[]{"", ""};
        // tight window: 200 chars left, 50 chars right
        int winL = Math.max(0, pos - 200);
        int winR = Math.min(context.length(), pos + value.length() + 50);
        String left  = context.substring(winL, pos);
        String right = context.substring(pos + value.length(), winR);
        return new String[]{ extractLB(left), extractRB(right) };
    }

    private String extractLB(String left) {
        // JSON double-quoted key: "key": "VALUE
        Matcher m1 = Pattern.compile("\"([^\"]{1,80})\"\\s*:\\s*\"\\s*$").matcher(left);
        if (m1.find()) return m1.group(1) + "\": \"";

        // JSON single-quoted key: 'key': 'VALUE  (Pega JSON)
        Matcher m1b = Pattern.compile("'([^']{1,80})'\\s*:\\s*'\\s*$").matcher(left);
        if (m1b.find()) return m1b.group(1) + "': '";

        // Pega HTML single-quoted: id='FIELD' ... value='VALUE
        Matcher m2 = Pattern.compile(
            "(?:name|id|pyID|pzID|ID)='([^']{1,60})'[^']*value='\\s*$",
            Pattern.CASE_INSENSITIVE).matcher(left);
        if (m2.find()) return m2.group(1) + "' value='";

        // Pega HTML double-quoted: id="FIELD" ... value="VALUE
        Matcher m2b = Pattern.compile(
            "(?:name|id|pyID|pzID|ID)=\"([^\"]{1,60})\"[^\"]*value=\"\\s*$",
            Pattern.CASE_INSENSITIVE).matcher(left);
        if (m2b.find()) return m2b.group(1) + "\" value=\"";

        // HTML/XML single-quoted attr: attr='VALUE
        Matcher m3 = Pattern.compile("([A-Za-z_][A-Za-z0-9_:-]*)='\\s*$").matcher(left);
        if (m3.find()) return m3.group(1) + "='";

        // HTML/XML double-quoted attr: attr="VALUE
        Matcher m3b = Pattern.compile("([A-Za-z_][A-Za-z0-9_:-]*)=\"\\s*$").matcher(left);
        if (m3b.find()) return m3b.group(1) + "=\"";

        // URL-encoded / plain form: field=VALUE
        Matcher m4 = Pattern.compile("([A-Za-z_][A-Za-z0-9_.%-]*)=\\s*$").matcher(left);
        if (m4.find()) return m4.group(1) + "=";

        // Last resort: last 20 non-whitespace chars
        String t = left.replaceAll("\\s+$", "");
        return t.length() > 20 ? t.substring(t.length() - 20) : t;
    }

    private String extractRB(String right) {
        if (right.isEmpty()) return "";
        char c = right.charAt(0);
        if (c == '\'')  return "'";
        if (c == '"')   return "\"";
        if (c == '&')   return "&";
        if (c == ';')   return ";";
        if (c == '\n') return "\n";
        if (c == '\r') return "\r\n";
        if (c == '<')   return "<";
        int i = 0;
        while (i < right.length() && i < 8
               && !Character.isLetterOrDigit(right.charAt(i))) i++;
        return i > 0 ? right.substring(0, i) : "'";
    }
    // =========================================================================
    // ALL MATCHES + ORDINAL
    // =========================================================================
    private List<String> findAllMatchesInScope(String scope, String lb, String rb) {
        List<String> result = new ArrayList<>();
        if (lb.isEmpty()) return result;
        try {
            Pattern p = Pattern.compile(
                Pattern.quote(lb) + "(.*?)" + (rb.isEmpty() ? "" : Pattern.quote(rb)),
                Pattern.DOTALL);
            Matcher m = p.matcher(scope);
            while (m.find()) result.add(m.group(1));
        } catch (Exception ignored) {}
        return result;
    }

    private int findOrdinal(List<String> all, String value) {
        for (int i = 0; i < all.size(); i++)
            if (all.get(i).equals(value)) return i + 1;
        return 1;
    }

    // =========================================================================
    // PARAM NAME
    // =========================================================================
    private int corrSequence = 0;

    // Meaningful, unique correlation names: C_<field>_<NN>, where <field> is the
    // identifier taken from the left boundary (e.g. activeCSRFToken, pzTransactionId)
    // and <NN> is a running two-digit sequence for the session.
    private String generateParamName(String lb, String snapshotId) {
        String field = fieldNameFromLB(lb);
        if (field.isEmpty())
            field = snapshotId.replaceAll("\\.inf$", "").replaceAll("[^A-Za-z0-9]", "_");
        if (field.length() > 24) field = field.substring(0, 24);

        corrSequence++;
        String name = String.format("C_%s_%02d", field, corrSequence);
        // Defensive uniqueness (the sequence already guarantees it)
        String base = name; int n = 1;
        while (usedParamNames.contains(name)) name = base + "_" + n++;
        usedParamNames.add(name);
        return name;
    }

    // Pull the key/field identifier out of a left boundary. Handles the shapes
    // extractLB produces: key": ",  key=,  key' value=',  key=".
    private String fieldNameFromLB(String lb) {
        if (lb == null) return "";
        Matcher m = Pattern.compile("[A-Za-z][A-Za-z0-9_]{1,40}").matcher(lb);
        return m.find() ? m.group() : "";
    }

    // =========================================================================
    // CODE BUILDER
    // =========================================================================
    static String buildCode(CorrelationResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("web_reg_save_param_ex(\n");
        sb.append("    \"ParamName=").append(r.paramName).append("\",\n");
        sb.append("    \"LB=").append(r.lb.replace("\"", "\\\"")).append("\",\n");
        sb.append("    \"RB=").append(r.rb.replace("\"", "\\\"")).append("\",\n");
        sb.append("    \"Ordinal=").append(r.ordinal).append("\",\n");
        sb.append("    SEARCH_FILTERS,\n");
        sb.append("    \"Scope=").append(r.sourceType).append("\",\n");
        sb.append("    LAST);");
        return sb.toString();
    }

    // =========================================================================
    // SHOW STUDIO
    // =========================================================================
    private void showStudio(CorrelationResult r) {
        metaSnapshot.setText(r.snapshotId);
        metaSource.setText(r.sourceType);
        metaSource.setForeground(r.sourceType.equals("Body") ?
            new Color(0x5d, 0xcc, 0x7a) : C_FUNCTION);
        metaOrdinal.setText(String.valueOf(r.ordinal));
        metaScope.setText(r.allMatches.size() + " match(es)");

        corrCodeArea.setText(r.generatedCode);
        corrCodeArea.setEditable(false);
        corrCodeArea.setForeground(new Color(0x7d, 0xe8, 0x7d));
        corrCodeArea.setBackground(BG_CORR);

        if (r.fromResponse)
            setStatus("Correlation generated from a Response section. Click Test to verify boundaries.", STATUS_INF);
        else
            setStatus("[WARN] Value not found inside any Response section - it may only appear where it is SENT. " +
                      "Check the source snapshot before correlating.", BTN_YELLOW);

        matchModel.clear();
        for (int i = 0; i < r.allMatches.size(); i++) {
            boolean isTarget = (i == r.ordinal - 1);
            matchModel.addElement((isTarget ? "* [ord " + (i+1) + "]  " :
                                              "  [ord " + (i+1) + "]  ") +
                                   truncate(r.allMatches.get(i), 38));
        }
        if (!r.allMatches.isEmpty()) matchList.setSelectedIndex(r.ordinal - 1);

        corrPlaceholder.setVisible(false);
        corrStudio.setVisible(true);
    }

    // =========================================================================
    // TEST
    // =========================================================================
    private void runTest() {
        if (currentResult == null) return;
        refreshRawFromPanes();
        syncEditsToResult();

        // Validate within the SAME response section that Process used - that is
        // the scope LR actually searches at runtime (the response of the request
        // the web_reg precedes). Searching the whole CodeGen also finds the token
        // in other responses and reports a false ordinal mismatch.
        String testScope = currentResult.snapshotContext;
        if (testScope == null || testScope.isEmpty()) testScope = rawCodeGenText;
        if (testScope == null || testScope.isEmpty()) testScope = codeGenPane.getText();
        if (testScope == null || testScope.isEmpty()) {
            setStatus("[FAIL] CodeGen not loaded. Please load or paste the CodeGen first.", STATUS_ERR);
            return;
        }

        String lb = currentResult.lb;
        String rb = currentResult.rb;

        if (lb == null || lb.trim().isEmpty()) {
            setStatus("[FAIL] LB is empty. Use Edit to set a left boundary.", STATUS_ERR);
            return;
        }

        // Show what we are testing with in the status
        String lbDisplay = lb.length() > 25 ? lb.substring(0, 25) + "..." : lb;

        // Compare against the RESPONSE form of the value - that is what lives in
        // the CodeGen and what LB/RB will actually extract. The double-encoded
        // `dynamicValue` only exists where the value is sent.
        String cgValue = currentResult.responseValue != null && !currentResult.responseValue.isEmpty()
            ? currentResult.responseValue : currentResult.dynamicValue;

        // Check if the value exists at all in codegen
        if (!testScope.contains(cgValue)) {
            setStatus("[FAIL] Value not found in CodeGen response. Wrong file loaded?", STATUS_ERR);
            return;
        }

        // Check if LB exists in codegen
        if (!testScope.contains(lb)) {
            setStatus("[FAIL] LB not found in CodeGen: [" + lbDisplay + "]  Use Edit to fix.", STATUS_ERR);
            return;
        }

        String extracted = extractWithBoundaries(testScope, lb, rb, currentResult.ordinal);
        boolean passed = cgValue.equals(extracted);
        currentResult.testPassed = passed;

        if (passed) {
            setStatus("[PASS] Test PASSED - value extracted correctly with ordinal " +
                currentResult.ordinal + ".", STATUS_OK);
        } else {
            List<String> allFound = findAllMatchesInScope(testScope, lb, rb);
            if (allFound.isEmpty()) {
                setStatus("[FAIL] LB found but LB+RB pattern matched nothing. " +
                    "RB=[" + rb + "] may be wrong. Use Edit.", STATUS_ERR);
            } else {
                String got = allFound.size() >= currentResult.ordinal
                    ? allFound.get(currentResult.ordinal - 1) : "(none at ordinal " + currentResult.ordinal + ")";
                setStatus("[FAIL] " + allFound.size() + " match(es) found. Ordinal " +
                    currentResult.ordinal + " got: [" + truncate(got, 30) + "]", STATUS_ERR);
                matchModel.clear();
                for (int i = 0; i < allFound.size(); i++) {
                    boolean isT = i == currentResult.ordinal - 1;
                    matchModel.addElement((isT ? "* " : "  ") + "[ord "+(i+1)+"]  " +
                        truncate(allFound.get(i), 38));
                }
            }
        }
    }

    private String extractWithBoundaries(String text, String lb, String rb, int ordinal) {
        if (lb.isEmpty()) return "";
        try {
            Pattern p = Pattern.compile(
                Pattern.quote(lb) + "(.*?)" + (rb.isEmpty() ? "" : Pattern.quote(rb)),
                Pattern.DOTALL);
            Matcher m = p.matcher(text);
            int count = 0;
            while (m.find()) { if (++count == ordinal) return m.group(1); }
        } catch (Exception ignored) {}
        return "";
    }

    // =========================================================================
    // EDIT / RESET
    // =========================================================================
    private void enableEditing() {
        corrCodeArea.setEditable(true);
        corrCodeArea.setBackground(new Color(0x12, 0x12, 0x28));
        setStatus("Edit mode ON. Modify LB/RB/Ordinal, then click Test.", BTN_YELLOW);
    }

    private void syncEditsToResult() {
        if (currentResult == null) return;
        String code = corrCodeArea.getText();
        currentResult.generatedCode = code;
        // Parse line by line: each parameter line is "KEY=VALUE",
        // VALUE may contain \\" (escaped quotes from buildCode)
        for (String line : code.split("\n")) {
            String t = line.trim();
            if (t.endsWith(",")) t = t.substring(0, t.length() - 1);
            if (!t.startsWith("\"") || !t.endsWith("\"")) continue;
            String inner = t.substring(1, t.length() - 1); // strip outer quotes
            int eq = inner.indexOf('=');
            if (eq < 0) continue;
            String key = inner.substring(0, eq);
            String val = inner.substring(eq + 1).replace("\\\"", "\"");
            switch (key) {
                case "ParamName": currentResult.paramName  = val; break;
                case "LB":        currentResult.lb          = val; break;
                case "RB":        currentResult.rb          = val; break;
                case "Scope":     currentResult.sourceType  = val; break;
                case "Ordinal":
                    try { currentResult.ordinal = Integer.parseInt(val); }
                    catch (NumberFormatException ignored) {}
                    break;
            }
        }
    }
    private void resetCorrelation() {
        if (currentResult != null) {
            corrCodeArea.setText(buildCode(currentResult));
            corrCodeArea.setEditable(false);
            corrCodeArea.setBackground(BG_CORR);
            corrCodeArea.setForeground(new Color(0x7d, 0xe8, 0x7d));
            setStatus("Reset to auto-generated code.", STATUS_INF);
        }
    }

    // =========================================================================
    // CORRELATE
    // =========================================================================
    private void doCorrelate() {
        if (currentResult == null) return;
        refreshRawFromPanes();
        syncEditsToResult();
        currentResult.generatedCode = buildCode(currentResult);
        corrCodeArea.setText(currentResult.generatedCode);

        String action = rawActionText;
        String modified = insertBeforeSnapshot(action,
            currentResult.snapshotId, currentResult.generatedCode);
        modified = exactReplace(modified,
            currentResult.dynamicValue, "{" + currentResult.paramName + "}");

        int caret = Math.min(actionPane.getCaretPosition(), modified.length() - 1);
        actionPane.setText(modified);
        rawActionText = modified;   // keep mirror in step (no live listener now)
        applySyntax(actionPane);
        try { actionPane.setCaretPosition(caret); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> highlightParam("{" + currentResult.paramName + "}"));
        setStatus("[DONE] Correlated! Inserted correlation + replaced dynamic value.", STATUS_OK);
    }

    private String insertBeforeSnapshot(String action, String snapshotId, String code) {
        String[] lines = action.split("\n", -1);
        // Strip .inf extension - action file uses "t2" not "t2.inf"
        String snapBase = snapshotId.replaceAll("\\.inf$", "");

        // Strategy: find the web_url/web_submit_data line whose REQUEST BLOCK
        // contains "Snapshot=tX" (matching our snapBase).
        // In LR action files the structure is:
        //   web_url("!STANDARD_2",         <- request start line
        //       "URL=...",
        //       "Snapshot=t2.inf",          <- snapshot param somewhere in block
        //       LAST);                      <- block end
        // We need to insert BEFORE the web_url line that owns this Snapshot=.

        // Step 1: find the line index of "Snapshot=snapBase"
        int snapshotLineIdx = -1;
        String snapPattern1 = "\"Snapshot=" + snapBase + ".inf\"";
        String snapPattern2 = "\"Snapshot=" + snapBase + "\"";
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i];
            if (t.contains(snapPattern1) || t.contains(snapPattern2) ||
                t.contains("Snapshot=" + snapBase + ".inf") ||
                t.contains("Snapshot=" + snapBase + ",")) {
                snapshotLineIdx = i;
                break;
            }
        }

        // Step 2: from snapshotLineIdx, walk BACKWARD to find the
        // web_url/web_submit_data/web_custom_request line that starts this block
        int insertBeforeLine = -1;
        if (snapshotLineIdx >= 0) {
            Pattern webFn = Pattern.compile(
                "^\\s*web_(?:url|submit_data|custom_request|submit_form|link)\\s*\\(",
                Pattern.CASE_INSENSITIVE);
            for (int i = snapshotLineIdx; i >= 0; i--) {
                if (webFn.matcher(lines[i]).find()) {
                    insertBeforeLine = i;
                    break;
                }
            }
        }

        // Step 3: fallback - try matching snapBase directly in the web_url first arg
        if (insertBeforeLine < 0) {
            Pattern p = Pattern.compile(
                "web_(?:url|submit_data|custom_request|submit_form|link)\\s*\\(\\s*\"[^\"]*" +
                Pattern.quote(snapBase) + "[^\"]*\"",
                Pattern.CASE_INSENSITIVE);
            for (int i = 0; i < lines.length; i++) {
                if (p.matcher(lines[i]).find()) {
                    insertBeforeLine = i;
                    break;
                }
            }
        }

        // Step 4: build output
        StringBuilder sb = new StringBuilder();
        if (insertBeforeLine >= 0) {
            for (int i = 0; i < lines.length; i++) {
                if (i == insertBeforeLine) {
                    sb.append(code).append("\n\n");
                }
                sb.append(lines[i]).append("\n");
            }
        } else {
            // Nothing matched - insert at top as last resort
            sb.append(code).append("\n\n");
            for (String line : lines) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private String exactReplace(String content, String target, String replacement) {
        StringBuilder sb = new StringBuilder();
        int start = 0, idx;
        while ((idx = content.indexOf(target, start)) >= 0) {
            sb.append(content, start, idx).append(replacement);
            start = idx + target.length();
        }
        sb.append(content.substring(start));
        return sb.toString();
    }

    // =========================================================================
    // SYNTAX HIGHLIGHTING
    // =========================================================================
    static final String[] LR_FUNCTIONS = {
        "web_url","web_submit_data","web_submit_form","web_custom_request",
        "web_reg_save_param_ex","web_reg_save_param","web_reg_find",
        "web_add_header","web_add_cookie","web_reg_header",
        "lr_start_transaction","lr_end_transaction","lr_think_time",
        "lr_eval_string","lr_param_sprintf","lr_log_message",
        "lr_error_message","web_concurrent_start","web_concurrent_end",
        "web_reg_save_param_regexp","web_reg_save_param_json",
        "web_reg_save_param_xpath","web_cleanup_cookies",
        "Action","vuser_init","vuser_end","return"
    };
    static final String[] KEYWORDS = {
        "int","char","void","if","else","for","while","do",
        "return","break","continue","switch","case","default",
        "struct","typedef","static","const"
    };
    static final String[] LR_CONSTANTS = {
        "LAST","SEARCH_FILTERS","EXTRARES","BEGIN_ARGUMENTS",
        "END_ARGUMENTS","ITEMDATA","URL","ENDITEM"
    };
    static final Pattern SYN_PATTERN;
    static {
        String fns = "\\b(" + String.join("|", LR_FUNCTIONS) + ")\\b";
        String kws = "\\b(" + String.join("|", KEYWORDS)     + ")\\b";
        String cts = "\\b(" + String.join("|", LR_CONSTANTS) + ")\\b";
        SYN_PATTERN = Pattern.compile(
            "(?<COMMENT>//[^\n]*|/\\*.*?\\*/)" +
            "|(?<FN>"    + fns + ")" +
            "|(?<KW>"    + kws + ")" +
            "|(?<CT>"    + cts + ")" +
            "|(?<PARAM>\\{[A-Za-z_][A-Za-z0-9_]*\\})" +
            "|(?<STR>\"([^\"\\\\]|\\\\.)*\")" +
            "|(?<NUM>\\b\\d+(\\.\\d+)?\\b)",
            Pattern.DOTALL);
    }

    void applySyntax(JTextPane tp) {
        String text = tp.getText();
        StyledDocument doc = tp.getStyledDocument();
        SimpleAttributeSet base = new SimpleAttributeSet();
        StyleConstants.setForeground(base, FG_DEFAULT);
        StyleConstants.setBackground(base, BG_EDITOR);
        StyleConstants.setFontFamily(base, "Consolas");
        StyleConstants.setFontSize(base, 13);
        doc.setCharacterAttributes(0, text.length(), base, true);
        if (text.length() > SYNTAX_LIMIT) return;   // skip heavy tokenising on very large files
        Matcher m = SYN_PATTERN.matcher(text);
        while (m.find()) {
            Color fg = null; boolean bold = false;
            if      (m.group("COMMENT") != null) { fg = C_COMMENT; }
            else if (m.group("FN")      != null) { fg = C_FUNCTION; bold = true; }
            else if (m.group("KW")      != null) { fg = C_KEYWORD; }
            else if (m.group("CT")      != null) { fg = C_CONSTANT; bold = true; }
            else if (m.group("PARAM")   != null) { fg = C_PARAM; bold = true; }
            else if (m.group("STR")     != null) { fg = C_STRING; }
            else if (m.group("NUM")     != null) { fg = C_NUMBER; }
            if (fg != null) {
                SimpleAttributeSet a = new SimpleAttributeSet();
                StyleConstants.setForeground(a, fg);
                StyleConstants.setBold(a, bold);
                doc.setCharacterAttributes(m.start(), m.end() - m.start(), a, false);
            }
        }
    }

    @SuppressWarnings("deprecation")
    void highlightBoundariesInCodeGen(String lb, String value, String rb, int sectionStart) {
        Highlighter hl = codeGenPane.getHighlighter();
        // Clear previous boundary highlights (search highlights are tracked
        // separately and are left untouched).
        for (Object t : boundaryTags) hl.removeHighlight(t);
        boundaryTags.clear();

        // Use rawCodeGenText for position calculations - it matches the document
        // content (line endings normalised), so offsets are stable.
        String text = rawCodeGenText;
        if (text == null || text.isEmpty()) text = codeGenPane.getText();

        // Step 1: locate the dynamic value itself - the anchor.
        int valueIdx = text.indexOf(value);
        if (valueIdx < 0) return;

        // Step 2: try to find LB immediately before the value.
        int lbIdx   = -1;
        int valStart = valueIdx;
        int valEnd   = valueIdx + value.length();

        if (!lb.isEmpty()) {
            int expectedLbPos = valueIdx - lb.length();
            if (expectedLbPos >= 0 && text.substring(expectedLbPos, valueIdx).equals(lb)) {
                lbIdx    = expectedLbPos;
                valStart = valueIdx;
            } else {
                int lineStart = text.lastIndexOf("\n", valueIdx - 1) + 1;
                int searchFrom = Math.max(0, lineStart);
                int candidate = text.indexOf(lb, searchFrom);
                if (candidate >= 0 && candidate < valueIdx &&
                    text.startsWith(value, candidate + lb.length())) {
                    lbIdx    = candidate;
                    valStart = candidate + lb.length();
                    valEnd   = valStart + value.length();
                }
            }
        }

        // Step 3: apply highlights via the Highlighter (background only)
        try {
            if (lbIdx >= 0)
                boundaryTags.add(hl.addHighlight(lbIdx, lbIdx + lb.length(), bndPainter));
            boundaryTags.add(hl.addHighlight(valStart, valStart + value.length(), dynPainter));
            if (!rb.isEmpty() && valEnd < text.length() && text.startsWith(rb, valEnd))
                boundaryTags.add(hl.addHighlight(valEnd, valEnd + rb.length(), bndPainter));
        } catch (BadLocationException ignored) {}

        // Step 4: scroll to the highlighted position
        int scrollTo = lbIdx >= 0 ? lbIdx : valStart;
        try {
            codeGenPane.setCaretPosition(scrollTo);
            codeGenPane.scrollRectToVisible(codeGenPane.modelToView(scrollTo));
        } catch (Exception ignored) {}
    }

    void highlightParam(String param) {
        StyledDocument doc = actionPane.getStyledDocument();
        String text = rawActionText.isEmpty() ? actionPane.getText() : rawActionText;
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setBackground(a, HL_CORR_BG);
        StyleConstants.setForeground(a, HL_CORR_FG);
        StyleConstants.setBold(a, true);
        int idx = 0;
        while ((idx = text.indexOf(param, idx)) >= 0) {
            doc.setCharacterAttributes(idx, param.length(), a, false);
            idx += param.length();
        }
    }

    // =========================================================================
    // FILE LOADING
    // =========================================================================
    private void loadFile(JTextComponent tp, boolean isAction) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(isAction ? "Load Action File" : "Load Code Generation File");
        fc.addChoosableFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "Text/C Files (*.txt, *.c, *.ls)", "txt", "c", "ls"));
        fc.setAcceptAllFileFilterUsed(true);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            String fileContent = new String(Files.readAllBytes(fc.getSelectedFile().toPath()));
            // Normalise line endings to \n before storing and displaying
            fileContent = fileContent.replace("\r\n", "\n").replace("\r", "\n");
            if (isAction) {
                rawActionText = fileContent;
            } else {
                rawCodeGenText = fileContent;
            }
            tp.setText(fileContent);
            if (isAction) applySyntax((JTextPane) tp);
            tp.setCaretPosition(0);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Could not load file:\n" + ex.getMessage(),
                "Load Error", JOptionPane.ERROR_MESSAGE);
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    // =========================================================================
    // NEW SESSION
    // =========================================================================
    private void newSession() {
        int r = JOptionPane.showConfirmDialog(this,
            "Clear both editors and start a new session?",
            "New Session", JOptionPane.YES_NO_OPTION);
        if (r == JOptionPane.YES_OPTION) {
            for (Object t : boundaryTags) codeGenPane.getHighlighter().removeHighlight(t);
            boundaryTags.clear();
            actionPane.setText("");
            codeGenPane.setText("");
            rawActionText  = "";
            rawCodeGenText = "";
            corrStudio.setVisible(false);
            corrPlaceholder.setVisible(true);
            currentResult = null;
            usedParamNames.clear();
            corrSequence = 0;
        }
    }

    // =========================================================================
    // UI HELPERS
    // =========================================================================
    private void setStatus(String msg, Color color) {
        statusLabel.setText("<html><body>" + msg + "</body></html>");
        statusLabel.setForeground(color);
    }
    private JLabel metaKey(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        l.setForeground(new Color(0x70, 0x70, 0xa8));
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 2));
        return l;
    }
    private JLabel metaVal() {
        JLabel l = new JLabel("-");
        l.setFont(new Font("Segoe UI", Font.BOLD, 11));
        l.setForeground(new Color(0xb0, 0xb0, 0xd8));
        l.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 8));
        return l;
    }
    private JLabel metaSep() {
        JLabel l = new JLabel("  |  ");
        l.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        l.setForeground(new Color(0x30, 0x30, 0x50));
        return l;
    }
    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel("  " + text);
        l.setFont(new Font("Segoe UI", Font.BOLD, 9));
        l.setForeground(new Color(0x4a, 0x4a, 0x7a));
        l.setBorder(BorderFactory.createEmptyBorder(5, 0, 2, 0));
        return l;
    }
    private JButton toolButton(String text, Color accent) {
        JButton b = new JButton(text);
        b.setFont(UI_FONT);
        b.setForeground(accent);
        b.setBackground(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 30));
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80), 1),
            BorderFactory.createEmptyBorder(3, 10, 3, 10)));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setOpaque(true);
        return b;
    }
    private JButton studioButton(String text, Color accent) { return toolButton(text, accent); }
    private JToggleButton toggleButton(String text, Color accent) {
        JToggleButton b = new JToggleButton(text, true);
        b.setFont(UI_FONT);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setOpaque(true);
        Runnable repaint = () -> {
            boolean on = b.isSelected();
            b.setForeground(on ? accent : FG_MUTED);
            b.setBackground(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), on ? 45 : 12));
            b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(
                    accent.getRed(), accent.getGreen(), accent.getBlue(), on ? 110 : 45), 1),
                BorderFactory.createEmptyBorder(3, 10, 3, 10)));
            b.setText((on ? "\u25CF " : "\u25CB ") + text);
        };
        b.addItemListener(e -> repaint.run());
        repaint.run();
        return b;
    }
    private JMenuItem menuItem(String text, Color fg) {
        JMenuItem item = new JMenuItem(text);
        item.setBackground(new Color(0x1a, 0x1a, 0x2e));
        item.setForeground(fg);
        item.setFont(UI_FONT);
        item.setBorderPainted(false);
        return item;
    }
    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }

    // Reliable text read straight from the document model. JTextComponent.getText()
    // is avoided elsewhere for large styled docs; Document.getText(0,len) is the
    // canonical, offset-stable way to mirror the pane content.
    private static String safeDocText(JTextComponent c) {
        try {
            Document d = c.getDocument();
            return d.getText(0, d.getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    // JTextPane that can switch between wrap (track viewport width) and no-wrap
    // (size to content, show horizontal scrollbar). A custom editor kit lets it
    // break VERY long unbroken strings (URLs, tokens, base64 - common in LR
    // scripts) at any character, so wrapped content is never clipped off the
    // right edge the way stock JTextPane word-wrapping would clip it.
    static class CodeTextPane extends JTextPane {
        private boolean wrap = true;
        CodeTextPane() { setEditorKit(new WrapEditorKit()); }
        @Override public boolean getScrollableTracksViewportWidth() { return wrap; }
        void setWrap(boolean w) { this.wrap = w; revalidate(); repaint(); }
    }

    static class WrapEditorKit extends StyledEditorKit {
        private final ViewFactory factory = new WrapColumnFactory();
        @Override public ViewFactory getViewFactory() { return factory; }
    }

    static class WrapColumnFactory implements ViewFactory {
        public View create(Element elem) {
            String kind = elem.getName();
            if (kind != null) {
                switch (kind) {
                    case AbstractDocument.ContentElementName:   return new WrapLabelView(elem);
                    case AbstractDocument.ParagraphElementName: return new ParagraphView(elem);
                    case AbstractDocument.SectionElementName:   return new BoxView(elem, View.Y_AXIS);
                    case StyleConstants.ComponentElementName:   return new ComponentView(elem);
                    case StyleConstants.IconElementName:        return new IconView(elem);
                }
            }
            return new LabelView(elem);
        }
    }

    // Allowing a zero minimum X span means a run can be broken at any character,
    // so a long token wraps instead of overflowing the viewport.
    static class WrapLabelView extends LabelView {
        WrapLabelView(Element elem) { super(elem); }
        @Override public float getMinimumSpan(int axis) {
            return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
        }
    }

    // =========================================================================
    // LINE NUMBER GUTTER
    // =========================================================================
    static class LineNumberGutter extends JPanel implements DocumentListener {
        private final JTextComponent tp;
        LineNumberGutter(JTextComponent tp) {
            this.tp = tp;
            setBackground(new Color(0x0a, 0x0a, 0x18));
            setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0x20, 0x20, 0x40)));
            setPreferredSize(new Dimension(36, 0));
            tp.getDocument().addDocumentListener(this);
        }
        @SuppressWarnings("deprecation")
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setFont(new Font("Consolas", Font.PLAIN, 11));
            g.setColor(new Color(0x35, 0x35, 0x65));
            try {
                Rectangle clip = g.getClipBounds();
                Element root = tp.getDocument().getDefaultRootElement();
                FontMetrics fm = g.getFontMetrics();
                // Derive the visible logical-line range from the view geometry so
                // numbers stay aligned even when lines wrap across several rows.
                int firstLine = root.getElementIndex(tp.viewToModel(new Point(0, clip.y)));
                int lastLine  = root.getElementIndex(tp.viewToModel(new Point(0, clip.y + clip.height)));
                if (firstLine < 0) firstLine = 0;
                if (lastLine < 0)  lastLine  = root.getElementCount() - 1;
                for (int i = firstLine; i <= lastLine; i++) {
                    Element el = root.getElement(i);
                    Rectangle r = tp.modelToView(el.getStartOffset());
                    if (r == null) continue;
                    String num = String.valueOf(i + 1);
                    int y = r.y + fm.getAscent();   // align to the line's first row
                    g.drawString(num, getWidth() - fm.stringWidth(num) - 4, y);
                }
            } catch (Exception ignored) {}
        }
        public void insertUpdate(DocumentEvent e)  { repaint(); }
        public void removeUpdate(DocumentEvent e)  { repaint(); }
        public void changedUpdate(DocumentEvent e) { repaint(); }
    }

    // =========================================================================
    // NEWLINE NORMALISING FILTER
    // Strips \r on the way into any editor pane so the document always uses \n.
    // Keeps pasted (Windows CRLF) content consistent with file-loaded content,
    // so the character offsets used for boundary/param highlighting stay stable.
    // =========================================================================
    static class NewlineFilter extends DocumentFilter {
        public void insertString(FilterBypass fb, int offset, String str, AttributeSet a)
                throws BadLocationException {
            super.insertString(fb, offset, norm(str), a);
        }
        public void replace(FilterBypass fb, int offset, int length, String str, AttributeSet a)
                throws BadLocationException {
            super.replace(fb, offset, length, norm(str), a);
        }
        private static String norm(String s) {
            if (s == null || s.indexOf('\r') < 0) return s;  // fast path: nothing to normalise
            return s.replace("\r\n", "\n").replace("\r", "\n");
        }
    }

    // =========================================================================
    // SEARCH PANEL
    // =========================================================================
    class SearchPanel extends JPanel {
        private final JTextComponent target;
        private final JTextField findField    = new JTextField(16);
        private final JTextField replaceField = new JTextField(16);
        private final JLabel     countLabel   = new JLabel("  ");
        private final JCheckBox  caseBox      = new JCheckBox("Case");
        private final JCheckBox  wordBox      = new JCheckBox("Word");
        private final JCheckBox  regexBox     = new JCheckBox("Regex");
        private List<int[]> matches    = new ArrayList<>();
        private int         currentIdx = -1;

        // Search highlighting via the Highlighter API (draws over the text using
        // real document offsets) rather than character attributes. This keeps it
        // independent of syntax colouring and correlation highlights, and makes
        // the "current match" always the right one when navigating.
        private final List<Object> hlTags = new ArrayList<>();
        private final Highlighter.HighlightPainter dimPainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(0x5a, 0x53, 0x14));
        private final Highlighter.HighlightPainter curPainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(0xc9, 0x99, 0x24));

        SearchPanel(JTextComponent tp) {
            this.target = tp;
            setVisible(false);
            setBackground(BG_SEARCH);
            setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER));
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

            JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            row1.setBackground(BG_SEARCH);
            styleField(findField);
            JButton prevBtn  = searchBtn("<< Prev", BTN_BLUE);
            JButton nextBtn  = searchBtn("Next >>",  BTN_BLUE);
            JButton closeBtn = searchBtn("Close",    FG_MUTED);
            closeBtn.addActionListener(e -> setVisible(false));
            countLabel.setFont(SMALL_FONT);
            countLabel.setForeground(new Color(0x7a, 0x9f, 0xcf));
            styleCheck(caseBox); styleCheck(wordBox); styleCheck(regexBox);
            row1.add(mkLbl("Find:")); row1.add(findField);
            row1.add(prevBtn); row1.add(nextBtn);
            row1.add(countLabel); row1.add(caseBox); row1.add(wordBox); row1.add(regexBox);
            row1.add(closeBtn);

            JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            row2.setBackground(BG_SEARCH);
            styleField(replaceField);
            JButton repOne = searchBtn("Replace",     new Color(0x7a, 0xb8, 0x7a));
            JButton repAll = searchBtn("Replace All", new Color(0x7a, 0xb8, 0x7a));
            row2.add(mkLbl("Replace:")); row2.add(replaceField);
            row2.add(repOne); row2.add(repAll);
            add(row1); add(row2);

            findField.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e)  { doSearch(); }
                public void removeUpdate(DocumentEvent e)  { doSearch(); }
                public void changedUpdate(DocumentEvent e) { doSearch(); }
            });
            findField.addKeyListener(new KeyAdapter() {
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER)  navigateNext();
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) setVisible(false);
                }
            });
            prevBtn.addActionListener(e  -> navigatePrev());
            nextBtn.addActionListener(e  -> navigateNext());
            repOne.addActionListener(e   -> replaceOne());
            repAll.addActionListener(e   -> replaceAll());
            caseBox.addActionListener(e  -> doSearch());
            wordBox.addActionListener(e  -> doSearch());
            regexBox.addActionListener(e -> doSearch());
        }

        void focusSearch() { findField.requestFocusInWindow(); findField.selectAll(); }
        void searchFor(String term) { findField.setText(term); doSearch(); }

        @Override public void setVisible(boolean v) {
            super.setVisible(v);
            if (v) {
                if (findField != null && !findField.getText().trim().isEmpty()) doSearch();
            } else {
                clearHighlights();
            }
        }

        private void doSearch() {
            matches.clear(); currentIdx = -1;
            String query = findField.getText();
            if (query == null || query.trim().isEmpty()) {
                clearHighlights(); countLabel.setText("  "); return;
            }
            // Match against the authoritative model text so offsets line up
            // exactly with document positions used by the Highlighter.
            String text = safeDocText(target);
            try {
                int flags = caseBox.isSelected() ? 0 : Pattern.CASE_INSENSITIVE;
                String pat = regexBox.isSelected() ? query : Pattern.quote(query);
                if (wordBox.isSelected() && !regexBox.isSelected()) pat = "\\b" + pat + "\\b";
                Matcher m = Pattern.compile(pat, flags).matcher(text);
                while (m.find()) {
                    if (m.end() > m.start()) matches.add(new int[]{m.start(), m.end()});
                }
            } catch (PatternSyntaxException ignored) {}
            if (!matches.isEmpty()) {
                currentIdx = 0;
                applyHighlights(); scrollTo(0);
                countLabel.setForeground(new Color(0x7a, 0x9f, 0xcf));
                countLabel.setText("1/" + matches.size());
            } else {
                clearHighlights();
                countLabel.setText("Not found");
                countLabel.setForeground(new Color(0xe0, 0x70, 0x70));
            }
        }
        private void navigateNext() {
            if (matches.isEmpty()) return;
            currentIdx = (currentIdx + 1) % matches.size();
            applyHighlights(); scrollTo(currentIdx);
            countLabel.setText((currentIdx+1) + "/" + matches.size());
        }
        private void navigatePrev() {
            if (matches.isEmpty()) return;
            currentIdx = (currentIdx - 1 + matches.size()) % matches.size();
            applyHighlights(); scrollTo(currentIdx);
            countLabel.setText((currentIdx+1) + "/" + matches.size());
        }
        private void replaceOne() {
            if (matches.isEmpty() || currentIdx < 0) return;
            int[] m = matches.get(currentIdx);
            try {
                target.getDocument().remove(m[0], m[1] - m[0]);
                target.getDocument().insertString(m[0], replaceField.getText(), null);
            } catch (BadLocationException ignored) {}
            doSearch();
        }
        private void replaceAll() {
            String query = findField.getText();
            if (query == null || query.trim().isEmpty()) return;
            try {
                int flags = caseBox.isSelected() ? 0 : Pattern.CASE_INSENSITIVE;
                String pat = regexBox.isSelected() ? query : Pattern.quote(query);
                String result = Pattern.compile(pat, flags).matcher(safeDocText(target))
                    .replaceAll(Matcher.quoteReplacement(replaceField.getText()));
                target.setText(result);
            } catch (Exception ignored) {}
            doSearch();
        }
        // Remove only OUR search highlights (tracked by tag), leaving the caret
        // selection and any correlation highlights untouched.
        private void clearHighlights() {
            Highlighter hl = target.getHighlighter();
            for (Object t : hlTags) hl.removeHighlight(t);
            hlTags.clear();
        }
        private void applyHighlights() {
            Highlighter hl = target.getHighlighter();
            clearHighlights();
            for (int i = 0; i < matches.size(); i++) {
                int[] m = matches.get(i);
                try {
                    hlTags.add(hl.addHighlight(m[0], m[1],
                        i == currentIdx ? curPainter : dimPainter));
                } catch (BadLocationException ignored) {}
            }
        }
        @SuppressWarnings("deprecation")
        private void scrollTo(int idx) {
            if (idx < 0 || idx >= matches.size()) return;
            int pos = matches.get(idx)[0];
            try {
                target.setCaretPosition(pos);
                target.scrollRectToVisible(target.modelToView(pos));
            } catch (Exception ignored) {}
        }
        private JLabel mkLbl(String text) {
            JLabel l = new JLabel(text);
            l.setFont(SMALL_FONT); l.setForeground(new Color(0xa0, 0xa0, 0xc0));
            return l;
        }
        private JButton searchBtn(String text, Color color) {
            JButton b = new JButton(text);
            b.setFont(SMALL_FONT); b.setForeground(color);
            b.setBackground(new Color(color.getRed(), color.getGreen(), color.getBlue(), 25));
            b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                    new Color(color.getRed(), color.getGreen(), color.getBlue(), 80)),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
            b.setFocusPainted(false);
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.setOpaque(true);
            return b;
        }
        private void styleField(JTextField tf) {
            tf.setBackground(new Color(0x10, 0x10, 0x22));
            tf.setForeground(new Color(0xd4, 0xd4, 0xe8));
            tf.setCaretColor(FG_DEFAULT);
            tf.setFont(SMALL_FONT);
            tf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        }
        private void styleCheck(JCheckBox cb) {
            cb.setBackground(BG_SEARCH);
            cb.setForeground(new Color(0xa0, 0xa0, 0xc0));
            cb.setFont(SMALL_FONT);
            cb.setFocusPainted(false);
        }
    }

    // =========================================================================
    // MATCH LIST RENDERER
    // =========================================================================
    class MatchListRenderer extends DefaultListCellRenderer {
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            JLabel l = (JLabel) super.getListCellRendererComponent(
                list, value, index, isSelected, cellHasFocus);
            l.setFont(SMALL_FONT);
            boolean isTarget = (currentResult != null && index == currentResult.ordinal - 1);
            l.setBackground(isSelected ? new Color(0x2a, 0x2a, 0x4c) :
                            isTarget   ? new Color(0x2a, 0x2a, 0x1a) : BG_CORR);
            l.setForeground(isTarget ? C_PARAM : FG_MUTED);
            l.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
            return l;
        }
    }

    // =========================================================================
    // DATA MODEL
    // =========================================================================
    static class CorrelationResult {
        String paramName;
        String lb;
        String rb;
        String dynamicValue;   // value AS SENT (may be double-encoded) - used for Action replacement
        String responseValue;  // value AS IT APPEARS IN THE RESPONSE - used for boundaries/ordinal/test
        boolean fromResponse;  // true if anchored inside a Response section
        String snapshotId;
        String sourceType;
        int    ordinal;
        List<String> allMatches;
        String generatedCode;
        String snapshotContext;
        int    sectionStart;
        boolean testPassed;
    }
}
