import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

/**
 * HAR Request Filter - NestWorks
 *
 * A dependency-free Java Swing desktop tool.
 *  - Load a captured .har file.
 *  - See the request/resource types and HTTP methods it contains, with counts.
 *  - Tick the ones you want to keep.
 *  - Export a new .har containing only the selected requests.
 *
 * Every original field (including Chrome's "_resourceType", pages, creator, etc.)
 * is preserved untouched - only log.entries is filtered.
 *
 * Also usable head-less:
 *   java -jar HarFilter.jar --list input.har
 *   java -jar HarFilter.jar --filter input.har output.har --types document,xhr,fetch --methods GET,POST
 */
public class HarFilter extends JFrame {

    /* ============================================================
     *  Minimal, complete JSON parser / writer.
     *  Objects -> LinkedHashMap (preserves key order)
     *  Arrays  -> ArrayList
     *  Strings -> String
     *  Numbers -> JsonNumber (keeps the exact literal, no precision loss)
     *  true/false -> Boolean, null -> null
     * ============================================================ */

    static final class JsonNumber {
        final String literal;
        JsonNumber(String s) { literal = s; }
        public String toString() { return literal; }
    }

    static final class JsonParser {
        private final String s;
        private int i;
        JsonParser(String s) { this.s = s; this.i = 0; }

        Object parse() {
            skipWs();
            Object v = parseValue();
            skipWs();
            if (i < s.length()) throw err("Trailing characters");
            return v;
        }

        private Object parseValue() {
            skipWs();
            char c = peek();
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': case 'f': return parseBool();
                case 'n': return parseNull();
                default:  return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            i++; // {
            skipWs();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                if (peek() != '"') throw err("Expected object key");
                String key = parseString();
                skipWs();
                if (peek() != ':') throw err("Expected ':'");
                i++;
                m.put(key, parseValue());
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; break; }
                throw err("Expected ',' or '}'");
            }
            return m;
        }

        private List<Object> parseArray() {
            ArrayList<Object> a = new ArrayList<Object>();
            i++; // [
            skipWs();
            if (peek() == ']') { i++; return a; }
            while (true) {
                a.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; break; }
                throw err("Expected ',' or ']'");
            }
            return a;
        }

        private String parseString() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                if (i >= s.length()) throw err("Unterminated string");
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw err("Bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object parseBool() {
            if (s.startsWith("true", i))  { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw err("Invalid literal");
        }

        private Object parseNull() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw err("Invalid literal");
        }

        private JsonNumber parseNumber() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && isNumChar(s.charAt(i))) i++;
            if (start == i) throw err("Invalid value");
            return new JsonNumber(s.substring(start, i));
        }

        private boolean isNumChar(char c) {
            return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
        }

        private char peek() {
            if (i >= s.length()) throw err("Unexpected end of input");
            return s.charAt(i);
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        private RuntimeException err(String m) {
            return new RuntimeException("JSON parse error: " + m + " (index " + i + ")");
        }
    }

    static String writeJson(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(v, sb, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object v, StringBuilder sb, int indent) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int n = 0, size = m.size();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                indent(sb, indent + 1);
                writeString(e.getKey(), sb);
                sb.append(": ");
                writeValue(e.getValue(), sb, indent + 1);
                if (++n < size) sb.append(',');
                sb.append('\n');
            }
            indent(sb, indent);
            sb.append('}');
        } else if (v instanceof List) {
            List<Object> a = (List<Object>) v;
            if (a.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int k = 0; k < a.size(); k++) {
                indent(sb, indent + 1);
                writeValue(a.get(k), sb, indent + 1);
                if (k < a.size() - 1) sb.append(',');
                sb.append('\n');
            }
            indent(sb, indent);
            sb.append(']');
        } else if (v instanceof String) {
            writeString((String) v, sb);
        } else if (v instanceof JsonNumber) {
            sb.append(((JsonNumber) v).literal);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else {
            writeString(String.valueOf(v), sb);
        }
    }

    private static void writeString(String str, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append("  ");
    }

    /* ============================================================
     *  HAR helpers
     * ============================================================ */

    /** One row of information we extract per HAR entry. */
    static final class EntryInfo {
        final Map<String, Object> raw;  // the original entry object (preserved on save)
        final String method;
        final String type;
        final String status;
        final String url;
        EntryInfo(Map<String, Object> raw, String method, String type, String status, String url) {
            this.raw = raw; this.method = method; this.type = type; this.status = status; this.url = url;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Object> getEntries(Object root) {
        if (!(root instanceof Map)) throw new RuntimeException("Not a HAR file: top level is not an object.");
        Object log = ((Map<String, Object>) root).get("log");
        if (!(log instanceof Map)) throw new RuntimeException("Not a HAR file: missing \"log\" object.");
        Object entries = ((Map<String, Object>) log).get("entries");
        if (!(entries instanceof List)) throw new RuntimeException("Not a HAR file: missing \"log.entries\" array.");
        return (List<Object>) entries;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> getLog(Object root) {
        return (Map<String, Object>) ((Map<String, Object>) root).get("log");
    }

    @SuppressWarnings("unchecked")
    static String classifyType(Map<String, Object> entry) {
        // 1) Chrome/Edge dev-tools annotate each entry with _resourceType - trust it.
        Object rt = entry.get("_resourceType");
        if (rt instanceof String && !((String) rt).trim().isEmpty()) {
            return ((String) rt).trim().toLowerCase();
        }
        // 2) Fall back to the response mime type / url extension.
        String mime = "";
        Object resp = entry.get("response");
        if (resp instanceof Map) {
            Object content = ((Map<String, Object>) resp).get("content");
            if (content instanceof Map) {
                Object mt = ((Map<String, Object>) content).get("mimeType");
                if (mt instanceof String) mime = ((String) mt).toLowerCase();
            }
        }
        int semi = mime.indexOf(';');
        if (semi >= 0) mime = mime.substring(0, semi).trim();

        String url = "";
        Object req = entry.get("request");
        if (req instanceof Map) {
            Object u = ((Map<String, Object>) req).get("url");
            if (u instanceof String) url = ((String) u).toLowerCase();
        }

        if (mime.contains("html")) return "document";
        if (mime.contains("css")) return "stylesheet";
        if (mime.contains("javascript") || mime.contains("ecmascript")) return "script";
        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("font/") || mime.contains("font")
                || url.matches(".*\\.(woff2?|ttf|otf|eot)(\\?.*)?$")) return "font";
        if (mime.startsWith("audio/") || mime.startsWith("video/")) return "media";
        if (mime.contains("json") || mime.contains("xml") || mime.contains("plain")) return "xhr";
        if (mime.isEmpty()) return "other";
        return "other";
    }

    @SuppressWarnings("unchecked")
    static String reqMethod(Map<String, Object> entry) {
        Object req = entry.get("request");
        if (req instanceof Map) {
            Object m = ((Map<String, Object>) req).get("method");
            if (m instanceof String) return ((String) m).toUpperCase();
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    static String reqUrl(Map<String, Object> entry) {
        Object req = entry.get("request");
        if (req instanceof Map) {
            Object u = ((Map<String, Object>) req).get("url");
            if (u instanceof String) return (String) u;
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    static String respStatus(Map<String, Object> entry) {
        Object resp = entry.get("response");
        if (resp instanceof Map) {
            Object st = ((Map<String, Object>) resp).get("status");
            if (st != null) return st.toString();
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    static List<EntryInfo> buildInfos(List<Object> entries) {
        List<EntryInfo> out = new ArrayList<EntryInfo>();
        for (Object o : entries) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> e = (Map<String, Object>) o;
            out.add(new EntryInfo(e, reqMethod(e), classifyType(e), respStatus(e), reqUrl(e)));
        }
        return out;
    }

    /* ============================================================
     *  Swing UI
     * ============================================================ */

    private Object root;                       // parsed HAR tree
    private List<EntryInfo> allInfos = new ArrayList<EntryInfo>();
    private File loadedFile;

    private final JLabel fileLabel = new JLabel("No file loaded.");
    private final JLabel countLabel = new JLabel(" ");
    private final CheckTableModel typeModel = new CheckTableModel("Type");
    private final CheckTableModel methodModel = new CheckTableModel("Method");
    private final PreviewModel previewModel = new PreviewModel();
    private final JButton saveButton = new JButton("Save Filtered HAR...");

    HarFilter() {
        super("HAR Request Filter  \u2013  NestWorks");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1040, 720);
        setLocationRelativeTo(null);
        buildUi();
    }

    private void buildUi() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(10, 10, 10, 10));
        setContentPane(content);

        // --- top: open file ---
        JButton openButton = new JButton("Open HAR...");
        openButton.addActionListener(this::onOpen);
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(openButton, BorderLayout.WEST);
        fileLabel.setBorder(new EmptyBorder(0, 8, 0, 0));
        top.add(fileLabel, BorderLayout.CENTER);
        content.add(top, BorderLayout.NORTH);

        // --- filters: two check-tables side by side ---
        JPanel filters = new JPanel(new GridLayout(1, 2, 10, 0));
        filters.add(buildCheckPanel("Request / Resource types", typeModel));
        filters.add(buildCheckPanel("HTTP methods", methodModel));

        // --- preview table ---
        JTable preview = new JTable(previewModel);
        preview.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        preview.getColumnModel().getColumn(0).setPreferredWidth(50);
        preview.getColumnModel().getColumn(1).setPreferredWidth(80);
        preview.getColumnModel().getColumn(2).setPreferredWidth(110);
        preview.getColumnModel().getColumn(3).setPreferredWidth(60);
        preview.getColumnModel().getColumn(4).setPreferredWidth(640);
        JScrollPane previewScroll = new JScrollPane(preview);
        previewScroll.setBorder(new TitledBorder("Preview \u2013 requests that will be kept"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, filters, previewScroll);
        split.setResizeWeight(0.42);
        split.setBorder(null);
        content.add(split, BorderLayout.CENTER);

        // --- bottom: count + save ---
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        countLabel.setBorder(new EmptyBorder(0, 4, 0, 0));
        bottom.add(countLabel, BorderLayout.CENTER);
        saveButton.addActionListener(this::onSave);
        saveButton.setEnabled(false);
        bottom.add(saveButton, BorderLayout.EAST);
        content.add(bottom, BorderLayout.SOUTH);

        // Recompute preview whenever a checkbox flips.
        typeModel.addTableModelListener(e -> refreshPreview());
        methodModel.addTableModelListener(e -> refreshPreview());
    }

    private JPanel buildCheckPanel(String title, CheckTableModel model) {
        JTable table = new JTable(model);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(2).setMaxWidth(90);
        // right-align counts
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(2).setCellRenderer(right);

        JButton all = new JButton("Select all");
        JButton none = new JButton("Clear all");
        all.addActionListener(e -> model.setAll(true));
        none.addActionListener(e -> model.setAll(false));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(all);
        buttons.add(none);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(new TitledBorder(title));
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void onOpen(ActionEvent ev) {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("HAR files (*.har, *.json)", "har", "json"));
        if (loadedFile != null) fc.setCurrentDirectory(loadedFile.getParentFile());
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = fc.getSelectedFile();
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            Object parsed = new JsonParser(new String(bytes, StandardCharsets.UTF_8)).parse();
            List<Object> entries = getEntries(parsed);

            this.root = parsed;
            this.loadedFile = file;
            this.allInfos = buildInfos(entries);
            populateFilters();
            fileLabel.setText(file.getName() + "   (" + allInfos.size() + " requests)");
            saveButton.setEnabled(true);
            refreshPreview();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not load this file:\n" + ex.getMessage(),
                    "Load error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void populateFilters() {
        // count per type and per method
        TreeMap<String, Integer> typeCounts = new TreeMap<String, Integer>();
        TreeMap<String, Integer> methodCounts = new TreeMap<String, Integer>();
        for (EntryInfo info : allInfos) {
            inc(typeCounts, info.type.isEmpty() ? "(unknown)" : info.type);
            inc(methodCounts, info.method.isEmpty() ? "(none)" : info.method);
        }
        typeModel.load(typeCounts);
        methodModel.load(methodCounts);
    }

    private static void inc(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        m.put(k, v == null ? 1 : v + 1);
    }

    private List<EntryInfo> currentSelection() {
        Set<String> types = typeModel.selectedKeys();
        Set<String> methods = methodModel.selectedKeys();
        List<EntryInfo> kept = new ArrayList<EntryInfo>();
        for (EntryInfo info : allInfos) {
            String t = info.type.isEmpty() ? "(unknown)" : info.type;
            String m = info.method.isEmpty() ? "(none)" : info.method;
            if (types.contains(t) && methods.contains(m)) kept.add(info);
        }
        return kept;
    }

    private void refreshPreview() {
        if (allInfos.isEmpty()) { countLabel.setText(" "); return; }
        List<EntryInfo> kept = currentSelection();
        previewModel.setRows(kept);
        countLabel.setText("Keeping " + kept.size() + " of " + allInfos.size() + " requests");
    }

    @SuppressWarnings("unchecked")
    private void onSave(ActionEvent ev) {
        if (root == null) return;
        List<EntryInfo> kept = currentSelection();
        if (kept.isEmpty()) {
            int ok = JOptionPane.showConfirmDialog(this,
                    "No request types are selected, so the exported HAR will have 0 entries.\nContinue?",
                    "Nothing selected", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) return;
        }

        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("HAR files (*.har)", "har"));
        String base = loadedFile.getName().replaceAll("\\.har$", "").replaceAll("\\.json$", "");
        fc.setSelectedFile(new File(loadedFile.getParentFile(), base + "_filtered.har"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File out = fc.getSelectedFile();
        if (!out.getName().toLowerCase().endsWith(".har")) out = new File(out.getParentFile(), out.getName() + ".har");

        try {
            // Build a fresh entries array from the ORIGINAL entry objects (all fields intact).
            List<Object> filtered = new ArrayList<Object>();
            for (EntryInfo info : kept) filtered.add(info.raw);

            Map<String, Object> log = getLog(root);
            Object originalEntries = log.get("entries");   // keep to restore afterwards
            log.put("entries", filtered);
            String json = writeJson(root);
            log.put("entries", originalEntries);           // restore so repeated saves still work

            Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));
            JOptionPane.showMessageDialog(this,
                    "Saved " + kept.size() + " requests to:\n" + out.getAbsolutePath(),
                    "Done", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not save file:\n" + ex.getMessage(),
                    "Save error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /* ---- table models ---- */

    /** Checkbox | Label | Count  table used for both types and methods. */
    static final class CheckTableModel extends AbstractTableModel {
        private final String labelHeader;
        private final List<Object[]> rows = new ArrayList<Object[]>(); // {Boolean, String key, Integer count}

        CheckTableModel(String labelHeader) { this.labelHeader = labelHeader; }

        void load(Map<String, Integer> counts) {
            rows.clear();
            for (Map.Entry<String, Integer> e : counts.entrySet())
                rows.add(new Object[]{Boolean.TRUE, e.getKey(), e.getValue()});
            fireTableDataChanged();
        }

        void setAll(boolean value) {
            for (Object[] r : rows) r[0] = value;
            fireTableDataChanged();
        }

        Set<String> selectedKeys() {
            Set<String> s = new HashSet<String>();
            for (Object[] r : rows) if (Boolean.TRUE.equals(r[0])) s.add((String) r[1]);
            return s;
        }

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return 3; }
        public String getColumnName(int c) { return c == 0 ? "\u2713" : c == 1 ? labelHeader : "Count"; }
        public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : c == 2 ? Integer.class : String.class; }
        public boolean isCellEditable(int r, int c) { return c == 0; }
        public Object getValueAt(int r, int c) { return rows.get(r)[c]; }
        public void setValueAt(Object v, int r, int c) {
            if (c == 0) { rows.get(r)[0] = v; fireTableCellUpdated(r, c); }
        }
    }

    /** Read-only preview of kept requests. */
    static final class PreviewModel extends AbstractTableModel {
        private final String[] cols = {"#", "Method", "Type", "Status", "URL"};
        private List<EntryInfo> rows = new ArrayList<EntryInfo>();

        void setRows(List<EntryInfo> r) { rows = r; fireTableDataChanged(); }
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }
        public Object getValueAt(int r, int c) {
            EntryInfo e = rows.get(r);
            switch (c) {
                case 0: return r + 1;
                case 1: return e.method;
                case 2: return e.type;
                case 3: return e.status;
                default: return e.url;
            }
        }
    }

    /* ============================================================
     *  Entry point (GUI, or head-less CLI when args are given)
     * ============================================================ */

    public static void main(String[] args) throws Exception {
        if (args.length > 0) { runCli(args); return; }
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new HarFilter().setVisible(true));
    }

    private static void runCli(String[] args) throws Exception {
        String cmd = args[0];
        if ("--list".equals(cmd) && args.length >= 2) {
            Object root = new JsonParser(read(args[1])).parse();
            List<EntryInfo> infos = buildInfos(getEntries(root));
            TreeMap<String, Integer> types = new TreeMap<String, Integer>();
            TreeMap<String, Integer> methods = new TreeMap<String, Integer>();
            for (EntryInfo i : infos) {
                inc(types, i.type.isEmpty() ? "(unknown)" : i.type);
                inc(methods, i.method.isEmpty() ? "(none)" : i.method);
            }
            System.out.println("Total requests: " + infos.size());
            System.out.println("\nResource types:");
            for (Map.Entry<String, Integer> e : types.entrySet())
                System.out.printf("  %-14s %d%n", e.getKey(), e.getValue());
            System.out.println("\nHTTP methods:");
            for (Map.Entry<String, Integer> e : methods.entrySet())
                System.out.printf("  %-14s %d%n", e.getKey(), e.getValue());
            return;
        }
        if ("--filter".equals(cmd) && args.length >= 3) {
            String in = args[1], out = args[2];
            Set<String> types = null, methods = null;
            for (int i = 3; i < args.length - 1; i++) {
                if ("--types".equals(args[i]))   types   = csv(args[i + 1]);
                if ("--methods".equals(args[i])) methods = csv(args[i + 1]);
            }
            Object root = new JsonParser(read(in)).parse();
            Map<String, Object> log = getLog(root);
            List<Object> entries = getEntries(root);
            List<Object> filtered = new ArrayList<Object>();
            for (Object o : entries) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) o;
                String t = classifyType(e); if (t.isEmpty()) t = "(unknown)";
                String m = reqMethod(e);    if (m.isEmpty()) m = "(none)";
                boolean keep = (types == null || types.contains(t)) && (methods == null || methods.contains(m));
                if (keep) filtered.add(e);
            }
            log.put("entries", filtered);
            Files.write(new File(out).toPath(), writeJson(root).getBytes(StandardCharsets.UTF_8));
            System.out.println("Wrote " + filtered.size() + " of " + entries.size() + " requests to " + out);
            return;
        }
        System.out.println("HAR Request Filter - NestWorks\n"
                + "Usage:\n"
                + "  (no args)                              launch the desktop app\n"
                + "  --list  input.har                      show request types & methods with counts\n"
                + "  --filter input.har output.har [--types a,b] [--methods GET,POST]\n"
                + "                                         write a HAR keeping only matching requests");
    }

    private static Set<String> csv(String s) {
        Set<String> set = new HashSet<String>();
        for (String p : s.split(",")) { p = p.trim().toLowerCase(); if (!p.isEmpty()) set.add(p); }
        // methods are compared upper-case in --list output but lower in classify; normalise both ways
        Set<String> both = new HashSet<String>(set);
        for (String p : set) both.add(p.toUpperCase());
        return both;
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
