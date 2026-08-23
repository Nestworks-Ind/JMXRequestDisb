import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.swing.table.*;
import javax.swing.tree.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;

/** Standalone JMeter correlation tool: JMX + JTL -> readable view -> correlate/edit/test -> export. */
public class JMXCorrelator extends JFrame {

    // ---------- state ----------
    private Document jmxDoc;
    private File jmxFile;
    private final List<Sample> samples = new ArrayList<>();   // parsed JTL
    private final CorrelationEngine engine = new CorrelationEngine();
    private Element currentSampler;
    private Element processedSampler;   // the sampler whose value was last processed (jump target after Correlate)
    private CorrelationEngine.Result result;

    // ---------- ui ----------
    private JTree tree;
    private final JTable paramTable = new JTable();
    private final JTextArea bodyArea = new JTextArea();
    private final JTable fileTable = new JTable();
    private final JLabel samplerHeader = new JLabel("  Select a sampler");
    private final JTextField fVar = new JTextField(), fLB = new JTextField(),
                             fRB = new JTextField(), fMatch = new JTextField(), fSource = new JTextField();
    private final JTextArea studioStatus = new JTextArea(2, 40);
    private final JTextArea jtlPreview = new JTextArea();
    private final JTextField pvSearch = new JTextField(14);
    private final JLabel matchLabel = new JLabel("0 / 0");
    private final JLabel status = new JLabel("Load a JMX and a JTL to begin.");

    public static void main(String[] a) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new JMXCorrelator().setVisible(true));
    }

    public JMXCorrelator() {
        super("JMX Correlator");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 800);
        setLocationRelativeTo(null);
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        JPanel sb = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        sb.add(status, BorderLayout.WEST);
        add(sb, BorderLayout.SOUTH);
    }

    // ========================================================= toolbar
    private JComponent buildToolbar() {
        JToolBar bar = new JToolBar(); bar.setFloatable(false);
        JButton loadJmx = new JButton("Load JMX");
        JButton loadJtl = new JButton("Load JTL");
        JButton export  = new JButton("Export JMX");
        JButton autoAll = new JButton("Auto-correlate all");
        JButton sr      = new JButton("Search / Replace");
        loadJmx.addActionListener(e -> loadJmx());
        loadJtl.addActionListener(e -> loadJtl());
        export.addActionListener(e -> exportJmx());
        autoAll.addActionListener(e -> autoCorrelateAll());
        sr.addActionListener(e -> searchReplace());
        bar.add(loadJmx); bar.add(loadJtl); bar.addSeparator(); bar.add(autoAll); bar.add(sr); bar.addSeparator(); bar.add(export);
        return bar;
    }

    // ========================================================= body
    private JComponent buildBody() {
        tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("(no JMX loaded)")));
        tree.addTreeSelectionListener(e -> onSelect());
        JScrollPane treeSp = new JScrollPane(tree);
        treeSp.setPreferredSize(new Dimension(360, 0));

        // --- Parameters tab: Name/Value table, right-click a row to process ---
        paramTable.setModel(new DefaultTableModel(new Object[]{"Name","Value"}, 0){
            public boolean isCellEditable(int r, int c){ return false; }
        });
        JPopupMenu pmenu = new JPopupMenu();
        JMenuItem pProc = new JMenuItem("Process for correlation");
        pProc.addActionListener(e -> processParamRow());
        pmenu.add(pProc);
        paramTable.setComponentPopupMenu(pmenu);
        paramTable.addMouseListener(new MouseAdapter(){
            public void mousePressed(MouseEvent e){ int r = paramTable.rowAtPoint(e.getPoint()); if (r>=0) paramTable.setRowSelectionInterval(r,r); }
        });

        // --- Body Data tab: selectable text; highlight the value and right-click ---
        bodyArea.setLineWrap(true); bodyArea.setWrapStyleWord(false);
        bodyArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JPopupMenu bmenu = new JPopupMenu();
        JMenuItem bProc = new JMenuItem("Process selection for correlation");
        bProc.addActionListener(e -> processBody());
        bmenu.add(bProc);
        bodyArea.setComponentPopupMenu(bmenu);

        // --- Files Upload tab ---
        fileTable.setModel(new DefaultTableModel(new Object[]{"File Path","Parameter Name","MIME Type"}, 0){
            public boolean isCellEditable(int r, int c){ return false; }
        });

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Parameters",   new JScrollPane(paramTable));
        tabs.addTab("Body Data",    new JScrollPane(bodyArea));
        tabs.addTab("Files Upload", new JScrollPane(fileTable));

        JPanel detail = new JPanel(new BorderLayout());
        samplerHeader.setFont(samplerHeader.getFont().deriveFont(Font.BOLD));
        samplerHeader.setBorder(BorderFactory.createEmptyBorder(6,4,6,4));
        JButton ehBtn = new JButton("Add error handling (Response Assertion)...");
        ehBtn.addActionListener(e -> addErrorHandling());
        JPanel dtop = new JPanel(new BorderLayout()); dtop.add(samplerHeader, BorderLayout.WEST); dtop.add(ehBtn, BorderLayout.EAST);
        detail.add(dtop, BorderLayout.NORTH);
        detail.add(tabs, BorderLayout.CENTER);

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, detail, buildStudio());
        rightSplit.setResizeWeight(0.6);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeSp, rightSplit);
        main.setDividerLocation(360);
        return main;
    }

    // ========================================================= studio
    private JComponent buildStudio() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder("Correlation Studio"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3,4,3,4); g.anchor = GridBagConstraints.WEST; g.fill = GridBagConstraints.HORIZONTAL;
        setEditable(false);
        int y=0;
        addRow(p,g,y++,"Variable",  fVar);
        addRow(p,g,y++,"Source",    fSource); fSource.setEditable(false);
        addRow(p,g,y++,"Left Boundary",  fLB);
        addRow(p,g,y++,"Right Boundary", fRB);
        addRow(p,g,y++,"Match number",   fMatch);

        JButton edit = new JButton("Edit"), test = new JButton("Test"), corr = new JButton("Correlate");
        edit.addActionListener(e -> setEditable(true));
        test.addActionListener(e -> doTest());
        corr.addActionListener(e -> doCorrelate());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT)); btns.add(edit); btns.add(test); btns.add(corr);
        g.gridx=0; g.gridy=y; g.gridwidth=2; p.add(btns, g); y++;

        studioStatus.setEditable(false); studioStatus.setLineWrap(true); studioStatus.setWrapStyleWord(true);
        studioStatus.setBackground(p.getBackground());
        g.gridx=0; g.gridy=y; g.gridwidth=2; p.add(new JScrollPane(studioStatus), g); y++;

        // JTL confirmation: source response with search + match navigation (like Results Tree > Response Data)
        jtlPreview.setEditable(false); jtlPreview.setLineWrap(true);
        jtlPreview.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JPanel pvBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        pvBar.add(new JLabel("Search:"));
        pvBar.add(pvSearch);
        JButton findBtn = new JButton("Find"); findBtn.addActionListener(e -> searchPreview());
        pvSearch.addActionListener(e -> searchPreview());
        pvBar.add(findBtn);
        pvBar.add(new JLabel("   Match:"));
        JButton prevM = new JButton("\u25C0 Prev"); prevM.addActionListener(e -> navMatch(-1));
        JButton nextM = new JButton("Next \u25B6"); nextM.addActionListener(e -> navMatch(+1));
        pvBar.add(prevM); pvBar.add(matchLabel); pvBar.add(nextM);
        JPanel pvWrap = new JPanel(new BorderLayout());
        pvWrap.setBorder(BorderFactory.createTitledBorder("Source response (extracted value highlighted)"));
        pvWrap.add(pvBar, BorderLayout.NORTH);
        pvWrap.add(new JScrollPane(jtlPreview), BorderLayout.CENTER);
        pvWrap.setPreferredSize(new Dimension(420, 200));
        g.gridx=0; g.gridy=y; g.gridwidth=2; g.weighty=1; g.fill=GridBagConstraints.BOTH; p.add(pvWrap, g);
        return p;
    }

    /** Show the source response and highlight what the current LB/RB/match actually extracts. */
    private void showJtlPreview() {
        if (result == null || !result.ok) { jtlPreview.setText(""); matchLabel.setText(""); return; }
        String src = engine.responseFor(result.sourceLabel, samples);
        if (src == null) { jtlPreview.setText("(source response not found)"); matchLabel.setText(""); return; }
        int total = engine.matchCount(result, samples);
        if (result.matchNumber < 1) result.matchNumber = 1;
        if (total > 0 && result.matchNumber > total) result.matchNumber = total;
        matchLabel.setText(total > 0 ? (result.matchNumber + " / " + total) : "0 / 0");
        String extracted = engine.extract(src, result.lb, result.rb, result.matchNumber);
        jtlPreview.setText(src);
        jtlPreview.setCaretPosition(0);
        jtlPreview.getHighlighter().removeAllHighlights();
        if (extracted != null && !extracted.isEmpty()) {
            int from = 0, occ = 0;
            while (true) {
                int idx = src.indexOf(extracted, from);
                if (idx < 0) break;
                occ++;
                if (occ == result.matchNumber) {
                    try {
                        jtlPreview.getHighlighter().addHighlight(idx, idx + extracted.length(),
                            new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(0xFFEB3B)));
                        jtlPreview.setCaretPosition(Math.max(0, idx - 40));
                    } catch (Exception ignored) {}
                    break;
                }
                from = idx + 1;
            }
        }
    }
    /** Prev/Next through the occurrences of this LB/RB in the source response. */
    private void navMatch(int delta) {
        if (result == null || !result.ok) return;
        int total = engine.matchCount(result, samples);
        if (total <= 0) return;
        int m = result.matchNumber + delta;
        if (m < 1) m = total; if (m > total) m = 1;   // wrap
        result.matchNumber = m;
        fMatch.setText(String.valueOf(m));
        showJtlPreview();
        studio("Match " + m + " of " + total + " - extracts: " + trim(engine.extract(engine.responseFor(result.sourceLabel, samples), result.lb, result.rb, m), 60));
    }
    /** Find the search term in the source response and jump to the next occurrence. */
    private void searchPreview() {
        String term = pvSearch.getText();
        String text = jtlPreview.getText();
        if (term.isEmpty() || text.isEmpty()) return;
        int start = jtlPreview.getCaretPosition();
        int idx = text.indexOf(term, start);
        if (idx < 0) idx = text.indexOf(term);          // wrap to top
        if (idx < 0) { status.setText("Not found: " + term); return; }
        try {
            jtlPreview.getHighlighter().addHighlight(idx, idx + term.length(),
                new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(0x90, 0xEE, 0x90)));
            jtlPreview.setCaretPosition(idx + term.length());
            jtlPreview.select(idx, idx + term.length());
        } catch (Exception ignored) {}
    }
    private void addRow(JPanel p, GridBagConstraints g, int y, String label, JComponent c){
        g.gridx=0; g.gridy=y; g.weightx=0; g.gridwidth=1; p.add(new JLabel(label+":"), g);
        g.gridx=1; g.gridy=y; g.weightx=1; p.add(c, g);
    }
    private void setEditable(boolean b){ fVar.setEditable(b); fLB.setEditable(b); fRB.setEditable(b); fMatch.setEditable(b); }

    // ========================================================= load
    private void loadJmx() {
        File f = choose("Load JMX", "jmx"); if (f == null) return;
        try {
            String xml = sanitizeXml(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            relaxLimits(dbf);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            jmxDoc = dbf.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(xml)));
            jmxFile = f;
            buildTree();
            status.setText("Loaded JMX: " + f.getName());
        } catch (Exception ex) { error("Could not parse JMX: " + ex.getMessage()); }
    }

    /** Remove characters that are illegal in XML 1.0 (NUL and other control bytes),
     *  including numeric entities like &#x0; that recorded JMX files sometimes contain. */
    private String sanitizeXml(String s) {
        // 1) drop numeric entities that reference illegal control chars: &#0; .. &#8;, &#x0; .. &#x8;, &#xB;, &#xC;, &#xE.. etc.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#x?0*([0-9A-Fa-f]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int code;
            try { String g = m.group(); code = g.charAt(2) == 'x' || g.charAt(2) == 'X'
                    ? Integer.parseInt(m.group(1), 16) : Integer.parseInt(m.group(1)); }
            catch (Exception e) { code = -1; }
            m.appendReplacement(sb, isLegalXml(code) ? java.util.regex.Matcher.quoteReplacement(m.group()) : "");
        }
        m.appendTail(sb);
        s = sb.toString();
        // 2) drop raw illegal chars
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (isLegalXml(c)) out.append(c); }
        return out.toString();
    }
    private boolean isLegalXml(int c) {
        return c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD);
    }

    /** Lift the JDK XML security size limits (0 = no limit) so large JMX files parse. */
    private void relaxLimits(DocumentBuilderFactory dbf) {
        for (String p : new String[]{"jdk.xml.maxGeneralEntitySizeLimit","jdk.xml.totalEntitySizeLimit",
                "jdk.xml.entityExpansionLimit","jdk.xml.maxXMLNameLimit","jdk.xml.elementAttributeLimit",
                "http://www.oracle.com/xml/jaxp/properties/maxGeneralEntitySizeLimit",
                "http://www.oracle.com/xml/jaxp/properties/totalEntitySizeLimit"})
            try { dbf.setAttribute(p, "0"); } catch (Exception ignored) {}
    }
    private void loadJtl() {
        File f = choose("Load JTL", "jtl"); if (f == null) return;
        try {
            samples.clear();
            parseJtl(f, samples);
            long withBody = samples.stream().filter(s -> !s.response.isEmpty()).count();
            status.setText("Loaded JTL: " + samples.size() + " samples, " + withBody + " with response data" +
                (withBody==0 ? "  (enable Save Response Data!)" : ""));
        } catch (Exception ex) { error("Could not parse JTL: " + ex.getMessage()); }
    }

    // ========================================================= tree
    private void buildTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new NodeInfo(jmxDoc.getDocumentElement(), "Test Plan", "jmeterTestPlan"));
        Element topHt = firstChildElement(jmxDoc.getDocumentElement(), "hashTree");
        if (topHt != null) buildChildren(topHt, root);
        tree.setModel(new DefaultTreeModel(root));
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
    }
    private void buildChildren(Element hashTree, DefaultMutableTreeNode parent) {
        List<Element> kids = childElements(hashTree);
        int i = 0;
        while (i < kids.size()) {
            Element e = kids.get(i);
            if (e.getTagName().equals("hashTree")) { i++; continue; }
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(new NodeInfo(e));
            parent.add(node);
            if (i+1 < kids.size() && kids.get(i+1).getTagName().equals("hashTree")) { buildChildren(kids.get(i+1), node); i+=2; }
            else i++;
        }
    }
    private void onSelect() {
        DefaultMutableTreeNode n = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        DefaultTableModel pm = (DefaultTableModel) paramTable.getModel(); pm.setRowCount(0);
        DefaultTableModel fm = (DefaultTableModel) fileTable.getModel();  fm.setRowCount(0);
        bodyArea.setText("");
        currentSampler = null;
        if (n == null || !(n.getUserObject() instanceof NodeInfo)) { samplerHeader.setText("  Select a sampler"); return; }
        NodeInfo info = (NodeInfo) n.getUserObject();
        String tag = info.elem.getTagName();

        // HTTP Header Manager: show its headers in the Parameters tab (right-click to correlate)
        if (tag.equals("HeaderManager")) {
            currentSampler = info.elem;
            samplerHeader.setText("  " + info.name + "   [Header Manager]");
            for (String[] h : getHeaders(info.elem)) pm.addRow(new Object[]{ h[0], h[1] });
            return;
        }
        if (!tag.equals("HTTPSamplerProxy")) { samplerHeader.setText("  " + info + "  (" + tag + ")"); return; }
        currentSampler = info.elem;
        samplerHeader.setText("  " + info.name + "   [" + getStringProp(info.elem, "HTTPSampler.method") + "  " +
            getStringProp(info.elem, "HTTPSampler.domain") + getStringProp(info.elem, "HTTPSampler.path") + "]");
        for (String[] p : getParams(info.elem)) pm.addRow(new Object[]{ p[0], p[1] });
        bodyArea.setText(getBody(info.elem)); bodyArea.setCaretPosition(0);
        for (String[] fpr : getFiles(info.elem)) fm.addRow(new Object[]{ fpr[0], fpr[1], fpr[2] });
    }

    // Header Manager headers -> [name, value]
    private List<String[]> getHeaders(Element hm) {
        List<String[]> out = new ArrayList<>();
        Element coll = childByNameAttr(hm, "collectionProp", "HeaderManager.headers");
        if (coll != null) for (Element ep : childElements(coll)) {
            if (!ep.getTagName().equals("elementProp")) continue;
            out.add(new String[]{ val(getStringProp(ep, "Header.name")), val(getStringProp(ep, "Header.value")) });
        }
        return out;
    }

    // Named parameters (HTTPArgument with a name).
    private List<String[]> getParams(Element sampler) {
        List<String[]> out = new ArrayList<>();
        Element coll = argsColl(sampler);
        if (coll != null) for (Element ep : childElements(coll)) {
            if (!ep.getTagName().equals("elementProp")) continue;
            String nm = getStringProp(ep, "Argument.name");
            if (nm != null && !nm.isEmpty()) out.add(new String[]{ nm, val(getStringProp(ep, "Argument.value")) });
        }
        return out;
    }
    // Raw body (HTTPArgument with an empty name).
    private String getBody(Element sampler) {
        Element coll = argsColl(sampler); StringBuilder sb = new StringBuilder();
        if (coll != null) for (Element ep : childElements(coll)) {
            if (!ep.getTagName().equals("elementProp")) continue;
            String nm = getStringProp(ep, "Argument.name");
            if (nm == null || nm.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(val(getStringProp(ep, "Argument.value"))); }
        }
        return sb.toString();
    }
    // File uploads.
    private List<String[]> getFiles(Element sampler) {
        List<String[]> out = new ArrayList<>();
        Element filesEl = childByNameAttr(sampler, "elementProp", "HTTPsampler.Files");
        if (filesEl != null) { Element coll = firstChildElement(filesEl, "collectionProp");
            if (coll != null) for (Element ep : childElements(coll)) {
                if (!ep.getTagName().equals("elementProp")) continue;
                out.add(new String[]{ val(getStringProp(ep,"File.path")), val(getStringProp(ep,"File.paramname")), val(getStringProp(ep,"File.mimetype")) });
            }}
        return out;
    }
    private Element argsColl(Element sampler) {
        Element argsEl = childByNameAttr(sampler, "elementProp", "HTTPsampler.Arguments");
        return argsEl == null ? null : firstChildElement(argsEl, "collectionProp");
    }
    private static String val(String s){ return s == null ? "" : s; }

    // ========================================================= process / studio
    private void processParamRow() {
        int r = paramTable.getSelectedRow();
        if (r < 0) { studio("Select a parameter row first."); return; }
        doProcess(innerValue(String.valueOf(paramTable.getValueAt(r, 1))));
    }
    private void processBody() {
        String sel = bodyArea.getSelectedText();
        String value = (sel != null && !sel.trim().isEmpty()) ? sel.trim() : innerValue(bodyArea.getText());
        doProcess(value);
    }
    private void doProcess(String value) {
        if (samples.isEmpty()) { studio("Load a JTL (with response data) first."); return; }
        if (value == null || value.isEmpty()) { studio("Nothing to process - select a value."); return; }
        processedSampler = currentSampler;   // the request we are correlating from
        result = engine.correlate(value, samples);
        if (!result.ok) { studio(result.message + "  (tried value: " + trim(value, 60) + ")"); clearStudio(); return; }
        fVar.setText(result.var); fSource.setText(result.sourceLabel);
        fLB.setText(result.lb); fRB.setText(result.rb); fMatch.setText(String.valueOf(result.matchNumber));
        result.value = value;
        setEditable(false);
        studio("Correlating value: " + trim(value, 60) + "\n" + result.message + "  Test, then Correlate.");
        showJtlPreview();
    }

    /** Strip a JSON/form wrapper down to the actual value:
     *  {"token":"ABC"} -> ABC   |   name=ABC -> ABC   |   otherwise the trimmed text. */
    private String innerValue(String cell) {
        if (cell == null) return "";
        String s = cell.trim();
        Matcher j = Pattern.compile("\"[^\"]+\"\\s*:\\s*\"([^\"]*)\"").matcher(s);
        if (j.find()) return j.group(1);                       // JSON "key":"value"
        Matcher f = Pattern.compile("^[^=&]+=([^&]+)$").matcher(s);
        if (f.find()) return f.group(1);                       // form key=value
        return s;
    }
    private void doTest() {
        if (result == null || !result.ok) { studio("Process a value first."); return; }
        readStudioIntoResult();
        String src = null;
        for (Sample s : samples) if (s.label.equals(result.sourceLabel)) { src = s.response; break; }
        String got = src == null ? null : engine.extract(src, result.lb, result.rb, result.matchNumber);
        boolean pass = result.value.equals(got);
        studio(pass ? "TEST PASS - extracts: " + trim(got, 60)
                    : "TEST FAIL - got: " + (got == null ? "(nothing)" : trim(got, 60)) + "  (expected " + trim(result.value, 40) + ")");
        showJtlPreview();
    }
    private void doCorrelate() {
        if (result == null || !result.ok) { studio("Process a value first."); return; }
        readStudioIntoResult();
        // Test the (possibly edited) boundaries before inserting.
        if (!engine.verify(result, samples)) {
            CorrelationEngine.Result fresh = engine.correlate(result.value, samples);   // try all possible cases
            if (fresh.ok && engine.verify(fresh, samples)) {
                result.lb = fresh.lb; result.rb = fresh.rb; result.matchNumber = fresh.matchNumber; result.sourceLabel = fresh.sourceLabel;
                fLB.setText(result.lb); fRB.setText(result.rb); fMatch.setText(String.valueOf(result.matchNumber)); fSource.setText(result.sourceLabel);
                showJtlPreview();
            } else {
                JOptionPane.showMessageDialog(this,
                    "Correlation not found - the boundaries do not extract the value from the source response.\n" +
                    "Nothing was inserted. Adjust the boundaries and Test, or re-Process the value.",
                    "Correlation", JOptionPane.WARNING_MESSAGE);
                studio("Correlation not found - not inserted.");
                return;
            }
        }
        Element source = findSamplerByName(result.sourceLabel);
        if (source == null) { studio("Source sampler \""+result.sourceLabel+"\" not found in the JMX."); return; }
        addRegexExtractor(source, result.var, result.lb, result.rb, result.matchNumber);
        int n = replaceValueInDom(result.value, result.var);
        // Jump back to the request we correlated from (the one now using ${var}).
        Element jumpTo = processedSampler != null ? processedSampler : firstSamplerUsing("${" + result.var + "}");
        String jumpName = (jumpTo != null) ? jumpTo.getAttribute("testname") : null;
        buildTree();
        if (jumpName != null) selectSamplerNode(jumpName);
        studio("Correlated (tested OK): added Regular Expression Extractor \""+result.var+"\" under \""+result.sourceLabel+
               "\"; replaced value in "+n+" place(s) with ${"+result.var+"}.  Export JMX when done.");
        status.setText("Correlated " + result.var + " (source " + result.sourceLabel + ")");
    }
    // first sampler (document order) whose request now sends ${var}
    private Element firstSamplerUsing(String token) {
        for (Element s : samplersInOrder()) {
            NodeList props = s.getElementsByTagName("stringProp");
            for (int i = 0; i < props.getLength(); i++) {
                Element p = (Element) props.item(i); String nm = p.getAttribute("name");
                if ((nm.equals("Argument.value") || nm.equals("HTTPSampler.path") || nm.equals("Header.value"))
                    && p.getTextContent() != null && p.getTextContent().contains(token)) return s;
            }
        }
        return null;
    }
    // Select the tree node for a sampler by its testname (stable across rebuilds).
    private void selectSamplerNode(String testname) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        java.util.Enumeration<?> e = root.depthFirstEnumeration();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode nd = (DefaultMutableTreeNode) e.nextElement();
            if (nd.getUserObject() instanceof NodeInfo) {
                NodeInfo ni = (NodeInfo) nd.getUserObject();
                if (ni.elem.getTagName().equals("HTTPSamplerProxy") && testname.equals(ni.elem.getAttribute("testname"))) {
                    javax.swing.tree.TreePath tp = new javax.swing.tree.TreePath(nd.getPath());
                    tree.setSelectionPath(tp); tree.scrollPathToVisible(tp);
                    onSelect();
                    return;
                }
            }
        }
    }
    private void readStudioIntoResult() {
        result.var = fVar.getText().trim(); result.lb = fLB.getText(); result.rb = fRB.getText();
        try { result.matchNumber = Integer.parseInt(fMatch.getText().trim()); } catch (Exception ignored) { result.matchNumber = 1; }
    }

    // ========================================================= search / replace
    private void searchReplace() {
        if (jmxDoc == null) { error("Load a JMX first."); return; }
        JTextField find = new JTextField(30), repl = new JTextField(30);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4,4,4,4); g.anchor = GridBagConstraints.WEST; g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx=0; g.gridy=0; form.add(new JLabel("Find:"), g);      g.gridx=1; g.gridy=0; g.weightx=1; form.add(find, g);
        g.gridx=0; g.gridy=1; form.add(new JLabel("Replace with:"), g); g.gridx=1; g.gridy=1; form.add(repl, g);
        Object[] opts = {"Count", "Replace all", "Cancel"};
        int ch = JOptionPane.showOptionDialog(this, form, "Search / Replace (values, paths, headers)",
            JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[1]);
        String f = find.getText();
        if (ch == 2 || ch == JOptionPane.CLOSED_OPTION || f.isEmpty()) return;
        boolean replace = (ch == 1);
        int hits = searchReplaceDom(f, repl.getText(), replace);
        if (replace) { buildTree(); onSelect(); }
        JOptionPane.showMessageDialog(this,
            (replace ? "Replaced " : "Found ") + hits + " occurrence(s) of \"" + f + "\".",
            "Search / Replace", JOptionPane.INFORMATION_MESSAGE);
    }
    private int searchReplaceDom(String find, String with, boolean doReplace) {
        int hits = 0;
        // search value-bearing string props
        String[] targets = {"Argument.value","Argument.name","HTTPSampler.path","HTTPSampler.domain","Header.value","Header.name"};
        Set<String> t = new HashSet<>(Arrays.asList(targets));
        NodeList props = jmxDoc.getElementsByTagName("stringProp");
        for (int i = 0; i < props.getLength(); i++) {
            Element p = (Element) props.item(i);
            if (!t.contains(p.getAttribute("name"))) continue;
            String txt = p.getTextContent();
            if (txt == null || !txt.contains(find)) continue;
            int c = countOcc(txt, find); hits += c;
            if (doReplace) p.setTextContent(txt.replace(find, with));
        }
        return hits;
    }
    private int countOcc(String s, String sub) { int c=0, i=0; while ((i=s.indexOf(sub,i))>=0){ c++; i+=sub.length(); } return c; }

    // ========================================================= auto-correlate everything
    private void autoCorrelateAll() {
        if (jmxDoc == null || samples.isEmpty()) { error("Load both a JMX and a JTL first."); return; }
        int done = 0;
        for (Element sampler : samplersInOrder()) {
            // collect candidate values: each named param's value + the body's inner value
            List<String> candidates = new ArrayList<>();
            for (String[] p : getParams(sampler)) candidates.add(innerValue(p[1]));
            String body = getBody(sampler); if (!body.isEmpty()) candidates.add(innerValue(body));
            for (String value : candidates) {
                if (value == null) continue; value = value.trim();
                if (value.isEmpty() || value.length() < 6 || value.startsWith("${")) continue;
                CorrelationEngine.Result r = engine.correlate(value, samples);
                if (!r.ok) continue;
                Element source = findSamplerByName(r.sourceLabel);
                if (source == null) continue;
                addRegexExtractor(source, r.var, r.lb, r.rb, r.matchNumber);
                replaceValueInDom(value, r.var);
                done++;
            }
        }
        buildTree();
        JOptionPane.showMessageDialog(this, "Auto-correlated " + done + " value(s). Review and Export JMX.",
            "Auto-correlate", JOptionPane.INFORMATION_MESSAGE);
        status.setText("Auto-correlated " + done + " value(s).");
    }

    // ========================================================= error handling (Response Assertion)
    private void addErrorHandling() {
        if (currentSampler == null) { error("Select an HTTP sampler first."); return; }
        String text = JOptionPane.showInputDialog(this, "Text to verify is present in the response:", "Add Error Handling", JOptionPane.PLAIN_MESSAGE);
        if (text == null || text.trim().isEmpty()) return;
        Element ht = nextHashTree(currentSampler);
        if (ht == null) { ht = jmxDoc.createElement("hashTree"); currentSampler.getParentNode().insertBefore(ht, currentSampler.getNextSibling()); }
        Element ra = jmxDoc.createElement("ResponseAssertion");
        ra.setAttribute("guiclass","AssertionGui"); ra.setAttribute("testclass","ResponseAssertion");
        ra.setAttribute("testname","Verify: " + trim(text, 30)); ra.setAttribute("enabled","true");
        Element coll = jmxDoc.createElement("collectionProp"); coll.setAttribute("name","Asserion.test_strings");
        coll.appendChild(stringProp("0", text));
        ra.appendChild(coll);
        ra.appendChild(stringProp("Assertion.custom_message",""));
        ra.appendChild(stringProp("Assertion.test_field","Assertion.response_data"));
        Element bp = jmxDoc.createElement("boolProp"); bp.setAttribute("name","Assertion.assume_success"); bp.setTextContent("false"); ra.appendChild(bp);
        Element ip = jmxDoc.createElement("intProp"); ip.setAttribute("name","Assertion.test_type"); ip.setTextContent("16"); ra.appendChild(ip);
        ht.appendChild(ra); ht.appendChild(jmxDoc.createElement("hashTree"));
        buildTree();
        status.setText("Added Response Assertion (verify text) to " + currentSampler.getAttribute("testname"));
        JOptionPane.showMessageDialog(this,
            "Added a Response Assertion that checks the response contains the text.\n\n" +
            "Note: JMeter has no 'goto'. To branch (e.g. skip to logout when the text is\n" +
            "missing) wrap the later samplers in an If Controller using a variable/assertion\n" +
            "result - that restructuring is done in JMeter itself.",
            "Error Handling", JOptionPane.INFORMATION_MESSAGE);
    }

    // ========================================================= DOM edits
    private void addRegexExtractor(Element sampler, String var, String lb, String rb, int match) {
        Element ht = nextHashTree(sampler);
        if (ht == null) { ht = jmxDoc.createElement("hashTree"); sampler.getParentNode().insertBefore(ht, sampler.getNextSibling()); }
        Element re = jmxDoc.createElement("RegexExtractor");
        re.setAttribute("guiclass","RegexExtractorGui"); re.setAttribute("testclass","RegexExtractor");
        re.setAttribute("testname",var); re.setAttribute("enabled","true");
        re.appendChild(stringProp("RegexExtractor.useHeaders","false"));
        re.appendChild(stringProp("RegexExtractor.refname", var));
        re.appendChild(stringProp("RegexExtractor.regex", regexFor(lb, rb)));
        re.appendChild(stringProp("RegexExtractor.template","$1$"));
        re.appendChild(stringProp("RegexExtractor.default","NOTFOUND_" + var));
        re.appendChild(stringProp("RegexExtractor.match_number", String.valueOf(match)));
        Element bp = jmxDoc.createElement("boolProp"); bp.setAttribute("name","RegexExtractor.default_empty_value"); bp.setTextContent("false"); re.appendChild(bp);
        ht.appendChild(re); ht.appendChild(jmxDoc.createElement("hashTree"));
    }
    /** Build the JMeter regex from LB/RB: LB(.+?)RB with the boundaries regex-escaped. */
    private String regexFor(String lb, String rb) {
        return reEsc(lb) + "(.+?)" + reEsc(rb);
    }
    private String reEsc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) { if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) b.append('\\'); b.append(c); }
        return b.toString();
    }
    private int replaceValueInDom(String value, String var) {
        String token = "${" + var + "}"; int c = 0;
        NodeList props = jmxDoc.getElementsByTagName("stringProp");
        for (int i = 0; i < props.getLength(); i++) {
            Element p = (Element) props.item(i);
            String nm = p.getAttribute("name");
            if (nm.equals("Argument.value") || nm.equals("HTTPSampler.path") || nm.equals("Header.value")) {
                String t = p.getTextContent();
                if (t != null && t.contains(value)) { p.setTextContent(t.replace(value, token)); c++; }
            }
        }
        return c;
    }
    private Element stringProp(String name, String value){ Element e = jmxDoc.createElement("stringProp"); e.setAttribute("name", name); e.setTextContent(value); return e; }

    // ========================================================= export
    private void exportJmx() {
        if (jmxDoc == null) { error("Load a JMX first."); return; }
        JFileChooser fc = new JFileChooser();
        if (jmxFile != null) fc.setSelectedFile(new File(jmxFile.getParentFile(), stripExt(jmxFile.getName())+"_correlated.jmx"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.transform(new DOMSource(jmxDoc), new StreamResult(fc.getSelectedFile()));
            status.setText("Exported: " + fc.getSelectedFile().getName());
            JOptionPane.showMessageDialog(this, "Saved:\n" + fc.getSelectedFile().getAbsolutePath() +
                "\n\nOpen it in JMeter to review and run.", "Export", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) { error("Export failed: " + ex.getMessage()); }
    }

    // ========================================================= DOM helpers
    private Element findSamplerByName(String name) {
        NodeList ns = jmxDoc.getElementsByTagName("HTTPSamplerProxy");
        for (int i = 0; i < ns.getLength(); i++) { Element e = (Element) ns.item(i); if (name.equals(e.getAttribute("testname"))) return e; }
        return null;
    }
    private List<Element> samplersInOrder() {
        List<Element> out = new ArrayList<>(); NodeList ns = jmxDoc.getElementsByTagName("HTTPSamplerProxy");
        for (int i = 0; i < ns.getLength(); i++) out.add((Element) ns.item(i)); return out;
    }
    private Element nextHashTree(Element e) {
        Node n = e.getNextSibling();
        while (n != null && !(n instanceof Element && ((Element)n).getTagName().equals("hashTree"))) n = n.getNextSibling();
        return (Element) n;
    }
    private String getStringProp(Element parent, String name) {
        for (Element e : childElements(parent))
            if (e.getTagName().equals("stringProp") && name.equals(e.getAttribute("name"))) return e.getTextContent();
        return "";
    }
    private Element childByNameAttr(Element parent, String tag, String nameAttr) {
        for (Element e : childElements(parent)) if (e.getTagName().equals(tag) && nameAttr.equals(e.getAttribute("name"))) return e;
        return null;
    }
    private Element firstChildElement(Element parent, String tag) {
        for (Element e : childElements(parent)) if (e.getTagName().equals(tag)) return e;
        return null;
    }
    private List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>(); NodeList ns = parent.getChildNodes();
        for (int i = 0; i < ns.getLength(); i++) if (ns.item(i) instanceof Element) out.add((Element) ns.item(i));
        return out;
    }

    // ========================================================= misc ui
    private void studio(String s){ studioStatus.setText(s); }
    private void clearStudio(){ fVar.setText(""); fLB.setText(""); fRB.setText(""); fMatch.setText(""); fSource.setText(""); }
    private void error(String s){ JOptionPane.showMessageDialog(this, s, "JMX Correlator", JOptionPane.WARNING_MESSAGE); status.setText(s); }
    private File choose(String title, String ext){
        JFileChooser fc = new JFileChooser(); fc.setDialogTitle(title);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(ext.toUpperCase()+" files", ext));
        return fc.showOpenDialog(this)==JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
    }
    private static String trim(String s, int n){ return s.length()<=n ? s : s.substring(0,n)+"..."; }
    private static String stripExt(String s){ int d=s.lastIndexOf('.'); return d<0?s:s.substring(0,d); }

    // ========================================================= JTL parse
    private void parseJtl(File jtl, List<Sample> out) throws Exception {
        String xml = sanitizeXml(new String(java.nio.file.Files.readAllBytes(jtl.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        SAXParserFactory f = SAXParserFactory.newInstance(); f.setNamespaceAware(false);
        try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignored) {}
        javax.xml.parsers.SAXParser sax = f.newSAXParser();
        for (String p : new String[]{"jdk.xml.maxGeneralEntitySizeLimit","jdk.xml.totalEntitySizeLimit",
                "jdk.xml.entityExpansionLimit","jdk.xml.maxXMLNameLimit","jdk.xml.elementAttributeLimit"})
            try { sax.setProperty(p, "0"); } catch (Exception ignored) {}
        sax.parse(new org.xml.sax.InputSource(new StringReader(xml)), new DefaultHandler(){
            String label; StringBuilder resp; boolean in;
            public void startElement(String u,String ln,String qn,Attributes a){
                if (qn.equals("httpSample")||qn.equals("sample")){ label=a.getValue("lb"); resp=new StringBuilder(); in=false; }
                else if (qn.equals("responseData")) in=true;
            }
            public void characters(char[] ch,int st,int len){ if(in&&resp!=null) resp.append(ch,st,len); }
            public void endElement(String u,String ln,String qn){
                if (qn.equals("responseData")) in=false;
                else if (qn.equals("httpSample")||qn.equals("sample")){ if(label!=null) out.add(new Sample(label, resp==null?"":resp.toString())); label=null; resp=null; }
            }
        });
    }

    // ========================================================= model
    static final class Sample { final String label, response; Sample(String l,String r){label=l;response=r==null?"":r;} }
    static final class NodeInfo {
        final Element elem; final String name, type;
        NodeInfo(Element e){ this(e, e.getAttribute("testname"), e.getTagName()); }
        NodeInfo(Element e, String n, String t){ elem=e; name=(n==null||n.isEmpty())?t:n; type=t; }
        public String toString(){ return name + (type.equals("HTTPSamplerProxy")?"  [HTTP]":""); }
    }

    // ========================================================= engine
    static final class CorrelationEngine {
        static final class Result { String var, lb, rb, sourceLabel, value; int matchNumber=1; boolean ok; String message; }
        private int seq=0;
        Result correlate(String value, List<Sample> samples){
            Result r = new Result();
            if (value==null||value.isEmpty()){ r.message="No value."; return r; }
            String key = value.indexOf('%')>=16 ? value.substring(0,value.indexOf('%')) : value;
            for (Sample s : samples){
                int idx = s.response.indexOf(key); if (idx<0) continue;
                String actual = slice(s.response, idx);
                int vpos = s.response.indexOf(actual);
                // try candidate boundary sets; pick the first that actually extracts the value
                for (String[] c : candidates(actual, s.response, vpos)) {
                    String lb=c[0], rb=c[1];
                    if (lb==null||lb.isEmpty()) continue;
                    List<String> all = findAll(s.response, lb, rb); int ord=-1;
                    for (int i=0;i<all.size();i++) if (all.get(i).equals(actual)){ ord=i+1; break; }
                    if (ord>0 && actual.equals(extract(s.response, lb, rb, ord))) {
                        r.lb=lb; r.rb=rb; r.matchNumber=ord; r.sourceLabel=s.label; r.value=actual; r.ok=true;
                        r.var=String.format("C_%s_%02d", fieldName(lb), ++seq);
                        r.message="Found in \""+s.label+"\" (match "+ord+" of "+all.size()+").";
                        return r;
                    }
                }
            }
            r.message="Correlation not found (value not present in an earlier response, or boundaries could not be derived)."; return r;
        }
        boolean verify(Result r, List<Sample> samples){
            if (r==null||r.value==null) return false;
            for (Sample s : samples) if (s.label.equals(r.sourceLabel)) return r.value.equals(extract(s.response, r.lb, r.rb, r.matchNumber));
            return false;
        }
        int matchCount(Result r, List<Sample> samples){
            for (Sample s : samples) if (s.label.equals(r.sourceLabel)) return findAll(s.response, r.lb, r.rb).size();
            return 0;
        }
        String responseFor(String label, List<Sample> samples){ for (Sample s : samples) if (s.label.equals(label)) return s.response; return null; }
        // field name for the variable, LR-style: last identifier in the left boundary
        private String fieldName(String lb){
            Matcher m=Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(lb==null?"":lb);
            String last="param"; while(m.find()) last=m.group(); return last;
        }
        // candidate (LB,RB) pairs to try, best first
        private List<String[]> candidates(String actual, String resp, int pos){
            List<String[]> out=new ArrayList<>();
            String left=resp.substring(Math.max(0,pos-200),pos);
            String right=resp.substring(Math.min(resp.length(),pos+actual.length()));
            String plb=lb(left), prb=rb(right);
            out.add(new String[]{plb, prb});
            for (int m : new int[]{1,2,3}) if (right.length()>=m) out.add(new String[]{plb, right.substring(0,m)});
            for (int k : new int[]{12,25,40}) if (left.length()>=k) out.add(new String[]{left.substring(left.length()-k), prb});
            for (int k : new int[]{12,25}) for (int m : new int[]{1,2})
                if (left.length()>=k && right.length()>=m) out.add(new String[]{left.substring(left.length()-k), right.substring(0,m)});
            return out;
        }
        boolean test(String resp,String lb,String rb,int ord,String value){ return value.equals(extract(resp,lb,rb,ord)); }
        String extract(String t,String lb,String rb,int ord){
            if (lb==null||lb.isEmpty()) return null;
            try { Matcher m=Pattern.compile(Pattern.quote(lb)+"(.*?)"+(rb==null||rb.isEmpty()?"":Pattern.quote(rb)),Pattern.DOTALL).matcher(t);
                int n=0; while(m.find()) if(++n==ord) return m.group(1);
            } catch(Exception ignored){} return null;
        }
        private String slice(String r,int start){ int i=start,max=Math.min(r.length(),start+6000);
            while(i<max){ char c=r.charAt(i); if(c=='"'||c=='\''||c=='<'||c=='>'||c=='&'||c==' '||c=='\n'||c=='\r'||c=='\t') break; i++; } return r.substring(start,i); }
        private String[] boundaries(String v,String r,int pos){ if(pos<0) pos=r.indexOf(v);
            String left=r.substring(Math.max(0,pos-200),pos); String right=r.substring(Math.min(r.length(),pos+v.length()));
            return new String[]{ lb(left), rb(right) }; }
        private String lb(String left){
            Matcher m1=Pattern.compile("\"[^\"]{1,80}\"\\s*:\\s*\"\\s*$").matcher(left); if(m1.find()) return left.substring(m1.start());
            Matcher m1b=Pattern.compile("'[^']{1,80}'\\s*:\\s*'\\s*$").matcher(left); if(m1b.find()) return left.substring(m1b.start());
            // header / text style  "Name: "  or  Name:   (KEEP the trailing whitespace so the value starts cleanly)
            Matcher m2=Pattern.compile("[\"']?[A-Za-z_][A-Za-z0-9_.\\- ]*:[ \\t]+$").matcher(left); if(m2.find()) return left.substring(m2.start());
            Matcher m3=Pattern.compile("[A-Za-z_][A-Za-z0-9_:-]*=\"\\s*$").matcher(left); if(m3.find()) return left.substring(m3.start());
            Matcher m4=Pattern.compile("[A-Za-z_][A-Za-z0-9_.%-]*=[ \\t]*$").matcher(left); if(m4.find()) return left.substring(m4.start());
            // fallback: the actual trailing text, INCLUDING any whitespace before the value
            return left.length()>25 ? left.substring(left.length()-25) : left;
        }
        private String rb(String right){ if(right.isEmpty()) return "";
            char c=right.charAt(0); if(c=='"'||c=='\''||c=='&'||c=='<'||c==';') return String.valueOf(c);
            int i=0; while(i<right.length()&&i<8&&!Character.isLetterOrDigit(right.charAt(i))) i++; return i>0?right.substring(0,i):String.valueOf(c);
        }
        private List<String> findAll(String scope,String lb,String rb){ List<String> r=new ArrayList<>(); if(lb==null||lb.isEmpty()) return r;
            try { Matcher m=Pattern.compile(Pattern.quote(lb)+"(.*?)"+(rb==null||rb.isEmpty()?"":Pattern.quote(rb)),Pattern.DOTALL).matcher(scope);
                while(m.find()) r.add(m.group(1)); } catch(Exception ignored){} return r; }
    }
}
