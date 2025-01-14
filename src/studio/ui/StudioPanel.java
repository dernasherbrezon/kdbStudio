package studio.ui;

import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import static javax.swing.JSplitPane.VERTICAL_SPLIT;
import static studio.ui.EscapeDialog.DialogResult.ACCEPTED;
import static studio.ui.EscapeDialog.DialogResult.CANCELLED;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.table.TableModel;
import javax.swing.text.*;

import kx.c;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaEditorKit;
import studio.core.AuthenticationManager;
import studio.core.Credentials;
import studio.kdb.*;
import studio.ui.action.QPadImport;
import studio.ui.action.QueryResult;
import studio.ui.action.WorkspaceSaver;
import studio.ui.chart.Chart;
import studio.ui.dndtabbedpane.DragEvent;
import studio.ui.dndtabbedpane.DraggableTabbedPane;
import studio.ui.rstextarea.FindReplaceAction;
import studio.ui.rstextarea.RSTextAreaFactory;
import studio.utils.*;

public class StudioPanel extends JPanel implements Observer,WindowListener {

    private static final Logger log = LogManager.getLogger();
    private static final Action editorUndoAction;
    private static final Action editorRedoAction;
    private static final Action editorCutAction;
    private static final Action editorCopyAction;
    private static final Action editorPasteAction;
    private static final Action editorSelectAllAction;
    private static final Action editorFindAction;
    private static final Action editorReplaceAction;

    static {
        // Action name will be used for text in menu items. Kit's actions have internal names.
        // We will create new actions for menu/toolbar and use kit's actions as action itself.
        editorCopyAction = RSTextAreaFactory.getAction(RSTextAreaFactory.rstaCopyAsStyledTextAction);
        editorCutAction = RSTextAreaFactory.getAction(RSTextAreaFactory.rstaCutAsStyledTextAction);
        editorPasteAction = RSTextAreaFactory.getAction(RSyntaxTextAreaEditorKit.pasteAction);
        editorSelectAllAction = RSTextAreaFactory.getAction(RSyntaxTextAreaEditorKit.selectAllAction);
        editorUndoAction = RSTextAreaFactory.getAction(RSyntaxTextAreaEditorKit.rtaUndoAction);
        editorRedoAction = RSTextAreaFactory.getAction(RSyntaxTextAreaEditorKit.rtaRedoAction);
        editorFindAction = RSTextAreaFactory.getAction(FindReplaceAction.findAction);
        editorReplaceAction = RSTextAreaFactory.getAction(FindReplaceAction.replaceAction);
    }


    private static boolean loading = false;

    private JComboBox<String> comboServer;
    private JTextField txtServer;
    private String exportFilename;
    private String lastQuery = null;
    private JToolBar toolbar;
    private DraggableTabbedPane tabbedEditors;
    private EditorTab editor;
    private JSplitPane splitpane;
    private DraggableTabbedPane tabbedPane;
    private ServerList serverList;
    private UserAction arrangeAllAction;
    private UserAction closeFileAction;
    private UserAction closeTabAction;
    private UserAction cleanAction;
    private UserAction openFileAction;
    private UserAction openInExcel;
    private UserAction codeKxComAction;
    private UserAction serverListAction;
    private UserAction serverHistoryAction;
    private UserAction newWindowAction;
    private UserAction newTabAction;
    private UserAction saveFileAction;
    private UserAction saveAllFilesAction;
    private UserAction saveAsFileAction;
    private UserAction exportAction;
    private UserAction chartAction;
    private Action undoAction;
    private Action redoAction;
    private Action cutAction;
    private Action copyAction;
    private Action pasteAction;
    private Action selectAllAction;
    private Action findAction;
    private Action replaceAction;
    private UserAction stopAction;
    private UserAction executeAction;
    private UserAction executeCurrentLineAction;
    private UserAction refreshAction;
    private UserAction aboutAction;
    private UserAction exitAction;
    private UserAction settingsAction;
    private UserAction toggleDividerOrientationAction;
    private UserAction minMaxDividerAction;
    private UserAction importFromQPadAction;
    private UserAction editServerAction;
    private UserAction addServerAction;
    private UserAction removeServerAction;
    private UserAction toggleCommaFormatAction;
    private UserAction nextEditorTabAction;
    private UserAction prevEditorTabAction;
    private UserAction[] lineEndingActions;
    private UserAction wordWrapAction;
    private JFrame frame;

    private static JFileChooser fileChooser;

    private static List<StudioPanel> allPanels = new ArrayList<>();

    private final List<Server> serverHistory;

    public final static int menuShortcutKeyMask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    private final static Cursor textCursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR);
    private final static Cursor waitCursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR);

    private final static int MAX_SERVERS_TO_CLONE = 20;

    private static final Config CONFIG = Config.getInstance();
    
    public void refreshTitle() {
        if (editor.getTitle() == null) return;

        int count = tabbedEditors.getTabCount();
        for (int index=0; index<count; index++) {
            String oldTitle = tabbedEditors.getTitleAt(index);
            String title = getEditor(index).getTabTitle();
            if (oldTitle!= null && oldTitle.equals(title)) continue;
            tabbedEditors.setTitleAt(index, title);
        }

        if (! loading) {
            Server server = editor.getServer();
            String env = Config.getEnvironment();
            String frameTitle = editor.getTitle() + (editor.isModified() ? " (not saved) " : "") + (server != null ? " @" + server.toString() : "") + " Studio for kdb+ " + Lm.version + (env == null ? "" : " [" + env + "]");
            if (!frameTitle.equals(frame.getTitle())) {
                frame.setTitle(frameTitle);
            }
        }
    }

    //@TODO: Should we have a generic code which override or remove all our actions from the netbeans JEditorPane
    private void overrideDefaultKeymap(JComponent component, UserAction... actions) {
        for (UserAction action: actions) {
            component.getInputMap().put(action.getKeyStroke(), action.getText());
            component.getActionMap().put(action.getText(), action);
        }
    }

    private void removeFocusChangeKeysForWindows(JComponent component) {
        if (Util.MAC_OS_X) return;

        KeyStroke ctrlTab = KeyStroke.getKeyStroke("ctrl TAB");
        KeyStroke ctrlShiftTab = KeyStroke.getKeyStroke("ctrl shift TAB");

        // Remove ctrl-tab from normal focus traversal
        Set<AWTKeyStroke> forwardKeys = new HashSet<>(component.getFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS));
        forwardKeys.remove(ctrlTab);
        component.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, forwardKeys);

        // Remove ctrl-shift-tab from normal focus traversal
        Set<AWTKeyStroke> backwardKeys = new HashSet<>(component.getFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS));
        backwardKeys.remove(ctrlShiftTab);
        component.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, backwardKeys);
    }

    private void setActionsEnabled(boolean value, Action... actions) {
        for (Action action: actions) {
            if (action != null) {
                action.setEnabled(value);
            }
        }
    }

    public void refreshActionState() {
        RSyntaxTextArea textArea = editor.getTextArea();
        if (textArea == null || tabbedPane == null) {
            setActionsEnabled(false, undoAction, redoAction, stopAction, executeAction,
                    executeCurrentLineAction, refreshAction);
            return;
        }

        Server server = editor.getServer();
        editServerAction.setEnabled(server != null);
        removeServerAction.setEnabled(server != null);

        undoAction.setEnabled(textArea.canUndo());
        redoAction.setEnabled(textArea.canRedo());

        wordWrapAction.setSelected(CONFIG.getBoolean(Config.RSTA_WORD_WRAP));

        for (LineEnding lineEnding: LineEnding.values() ) {
            lineEndingActions[lineEnding.ordinal()].setSelected(editor.getLineEnding() == lineEnding);
        }

        boolean queryRunning = editor.getQueryExecutor().running();
        stopAction.setEnabled(queryRunning);
        executeAction.setEnabled(!queryRunning);
        executeCurrentLineAction.setEnabled(!queryRunning);
        refreshAction.setEnabled(lastQuery != null && !queryRunning);

        TabPanel tab = (TabPanel) tabbedPane.getSelectedComponent();
        if (tab == null) {
            setActionsEnabled(false, exportAction, chartAction, openInExcel, refreshAction);
        } else {
            exportAction.setEnabled(tab.isTable());
            chartAction.setEnabled(tab.getType() == TabPanel.ResultType.TABLE);
            openInExcel.setEnabled(tab.isTable());
            refreshAction.setEnabled(true);
            tab.refreshActionState(queryRunning);
        }
    }

    private String chooseFilename() {
        if (fileChooser == null) {
            fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

            FileFilter ff = new FileNameExtensionFilter("q script", "q");
            fileChooser.addChoosableFileFilter(ff);
            fileChooser.setFileFilter(ff);
        }

        String filename = editor.getFilename();
        if (filename != null) {
            fileChooser.setCurrentDirectory(new File(new File(filename).getPath()));
        }

        int option = fileChooser.showOpenDialog(this);
        if (option == JFileChooser.APPROVE_OPTION) {
            return fileChooser.getSelectedFile().getAbsolutePath();
        }
        return null;
    }

    private void exportAsExcel(final String filename) {
        new ExcelExporter().exportTableX(frame,getSelectedTable(),new File(filename),false);
    }

    private void exportAsDelimited(final TableModel model,final String filename,final char delimiter) {
        UIManager.put("ProgressMonitor.progressText","Studio for kdb+");
        final ProgressMonitor pm = new ProgressMonitor(frame,"Exporting data to " + filename,
                "0% complete",0,100);
        pm.setMillisToDecideToPopup(100);
        pm.setMillisToPopup(100);
        pm.setProgress(0);

        Runnable runner = () -> {
            if (filename != null) {
                String lineSeparator = System.getProperty("line.separator");;

                try (BufferedWriter fw = new BufferedWriter(new FileWriter(filename))) {
                    for(int col = 0; col < model.getColumnCount(); col++) {
                        if (col > 0)
                            fw.write(delimiter);
                        fw.write(model.getColumnName(col));
                    }
                    fw.write(lineSeparator);
                    int maxRow = model.getRowCount();
                    for(int r = 1; r <= maxRow; r++) {
                        for (int col = 0;col < model.getColumnCount();col++) {
                            if (col > 0) fw.write(delimiter);

                            K.KBase o = (K.KBase) model.getValueAt(r - 1,col);
                            if (!o.isNull())
                                fw.write(o.toString(KFormatContext.NO_TYPE));
                        }
                        fw.write(lineSeparator);
                        if (pm.isCanceled()) break;
                        int progress = (100 * r) / maxRow;
                        String note = "" + progress + "% complete";
                        SwingUtilities.invokeLater( () -> {
                            pm.setProgress(progress);
                            pm.setNote(note);
                        } );
                    }

                }
                catch (IOException e) {
                    log.error("Error in writing to file {}", filename, e);
                }
                finally {
                    pm.close();
                }
            }
        };

        Thread t = new Thread(runner,"Exporter");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void exportAsXml(final TableModel model,final String filename) {
        UIManager.put("ProgressMonitor.progressText","Studio for kdb+");
        final ProgressMonitor pm = new ProgressMonitor(frame,"Exporting data to " + filename,
                "0% complete",0,100);
        pm.setMillisToDecideToPopup(100);
        pm.setMillisToPopup(100);
        pm.setProgress(0);

        Runnable runner = () -> {
            if (filename != null) {
                String lineSeparator = System.getProperty("line.separator");;

                try (BufferedWriter fw = new BufferedWriter(new FileWriter(filename))) {
                    fw.write("<R>");
                    int maxRow = model.getRowCount();
                    fw.write(lineSeparator);

                    String[] columns = new String[model.getColumnCount()];
                    for (int col = 0; col < model.getColumnCount(); col++)
                        columns[col] = model.getColumnName(col);

                    for (int r = 1; r <= maxRow; r++) {
                        fw.write("<r>");
                        for (int col = 0; col < columns.length; col++) {
                            fw.write("<" + columns[col] + ">");

                            K.KBase o = (K.KBase) model.getValueAt(r - 1,col);
                            if (!o.isNull())
                                fw.write(o.toString(KFormatContext.NO_TYPE));

                            fw.write("</" + columns[col] + ">");
                        }
                        fw.write("</r>");
                        fw.write(lineSeparator);

                        if (pm.isCanceled()) break;
                        int progress = (100 * r) / maxRow;
                        String note = "" + progress + "% complete";
                        SwingUtilities.invokeLater(() -> {
                            pm.setProgress(progress);
                            pm.setNote(note);
                        });
                    }
                    fw.write("</R>");
                }
                catch (IOException e) {
                    log.error("Error in writing to file {}", filename, e);
                }
                finally {
                    pm.close();
                }
            }
        };

        Thread t = new Thread(runner, "Exporter");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void exportAsTxt(String filename) {
        exportAsDelimited(getSelectedTable().getModel(),filename,'\t');
    }

    private void exportAsCSV(String filename) {
        exportAsDelimited(getSelectedTable().getModel(),filename,',');
    }

    private void export() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.setDialogTitle("Export result set as");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

        FileFilter csvFilter = null;
        FileFilter txtFilter = null;
        FileFilter xmlFilter = null;
        FileFilter xlsFilter = null;

        if (getSelectedTable() != null) {
            csvFilter =
                    new FileFilter() {
                        public String getDescription() {
                            return "csv (Comma delimited)";
                        }

                        public boolean accept(File file) {
                            if (file.isDirectory() || file.getName().endsWith(".csv"))
                                return true;
                            else
                                return false;
                        }
                    };

            txtFilter =
                    new FileFilter() {
                        public String getDescription() {
                            return "txt (Tab delimited)";
                        }

                        public boolean accept(File file) {
                            if (file.isDirectory() || file.getName().endsWith(".txt"))
                                return true;
                            else
                                return false;
                        }
                    };

            xmlFilter =
                    new FileFilter() {
                        public String getDescription() {
                            return "xml";
                        }

                        public boolean accept(File file) {
                            if (file.isDirectory() || file.getName().endsWith(".xml"))
                                return true;
                            else
                                return false;
                        }
                    };


            xlsFilter =
                    new FileFilter() {
                        public String getDescription() {
                            return "xls (Microsoft Excel)";
                        }

                        public boolean accept(File file) {
                            if (file.isDirectory() || file.getName().endsWith(".xls"))
                                return true;
                            else
                                return false;
                        }
                    };

            chooser.addChoosableFileFilter(csvFilter);
            chooser.addChoosableFileFilter(txtFilter);
            chooser.addChoosableFileFilter(xmlFilter);
            chooser.addChoosableFileFilter(xlsFilter);
        }

        if (exportFilename != null) {
            File file = new File(exportFilename);
            File dir = new File(file.getPath());
            chooser.setCurrentDirectory(dir);
            chooser.ensureFileIsVisible(file);
            if (getSelectedTable() != null)
                if (exportFilename.endsWith(".xls"))
                    chooser.setFileFilter(xlsFilter);
                else if (exportFilename.endsWith(".csv"))
                    chooser.setFileFilter(csvFilter);
                else if (exportFilename.endsWith(".xml"))
                    chooser.setFileFilter(xmlFilter);
                else if (exportFilename.endsWith(".txt"))
                    chooser.setFileFilter(txtFilter);
        }

        int option = chooser.showSaveDialog(this);

        if (option == JFileChooser.APPROVE_OPTION) {
            File sf = chooser.getSelectedFile();
            File f = chooser.getCurrentDirectory();
            String dir = f.getAbsolutePath();

//            Cursor cursor= frame.getCursor();

            try {
                //              frame.setCursor(new java.awt.Cursor(java.awt.Cursor.WAIT_CURSOR));
                FileFilter ff = chooser.getFileFilter();

                exportFilename = dir + "/" + sf.getName();

                if (getSelectedTable() != null)
                    if (exportFilename.endsWith(".xls"))
                        exportAsExcel(exportFilename);
                    else if (exportFilename.endsWith(".csv"))
                        exportAsCSV(exportFilename);
                    else if (exportFilename.endsWith(".txt"))
                        exportAsTxt(exportFilename);
                    else if (exportFilename.endsWith(".xml"))
                        exportAsXml(getSelectedTable().getModel(),exportFilename);
                    /*                    else if (exportFilename.endsWith(".res")) {
                    exportAsBin(exportFilename);
                    }
                     */
                    else
                    if (ff == csvFilter)
                        exportAsCSV(exportFilename);
                    else if (ff == xlsFilter)
                        exportAsExcel(exportFilename);
                    else if (ff == txtFilter)
                        exportAsTxt(exportFilename);
                    else if (ff == xmlFilter)
                        exportAsXml(getSelectedTable().getModel(),exportFilename);
                        /*else if( ff == binFilter){
                        exportAsBin(exportFilename);
                        }
                         */
                    else
                        JOptionPane.showMessageDialog(frame,
                                "Warning",
                                "You did not specify what format to export the file as.\n Cancelling data export",
                                JOptionPane.WARNING_MESSAGE,
                                Util.WARNING_ICON);
            /*                else {
            exportAsBin(exportFilename);
            }
             */
            }
            catch (Exception e) {
                JOptionPane.showMessageDialog(frame,
                        "Error",
                        "An error occurred whilst writing the export file.\n Details are: " + e.getMessage(),
                        JOptionPane.ERROR_MESSAGE,
                        Util.ERROR_ICON);
            }
            finally {
                //            frame.setCursor(cursor);
            }
        }
    }

    public void newFile() {
        if (!checkAndSaveTab(editor)) return;

        editor.setFilename(null);
        editor.init(Content.getEmpty());
    }

    private void openFile() {
        String filename = chooseFilename();
        if (filename == null) return;
        addToMruFiles(filename);
        addTab(editor.getServer(), filename);
    }

    public void loadMRUFile(String filename) {
        if (!checkAndSaveTab(editor)) return;

        if (!loadFile(filename)) return;
        addToMruFiles(filename);
        refreshTitle();
        rebuildAll();
    }

    private void addToMruFiles(String filename) {
        if (filename == null)
            return;

        Vector v = new Vector();
        v.add(filename);
        String[] mru = CONFIG.getMRUFiles();
        for (int i = 0;i < mru.length;i++)
            if (!v.contains(mru[i]))
                v.add(mru[i]);
        CONFIG.saveMRUFiles((String[]) v.toArray(new String[0]));
        rebuildMenuBar();
    }

    public boolean loadFile(String filename) {
        Content content = Content.getEmpty();
        try {
            content = FileReaderWriter.read(filename);
            if (content.hasMixedLineEnding()) {
                JOptionPane.showMessageDialog(frame, "The file " + filename + " has mixed line endings. Mixed line endings are not supported.\n\n" +
                                "All line endings are set to " + content.getLineEnding() + " style.",
                        "Mixed Line Ending", JOptionPane.INFORMATION_MESSAGE);
            }
            return true;
        } catch (IOException e) {
            log.error("Failed to load file {}", filename, e);
            JOptionPane.showMessageDialog(frame, "Failed to load file "+filename + ".\n" + e.getMessage(),
                    "Error in file load", JOptionPane.ERROR_MESSAGE);
        } finally {
            editor.setFilename(filename);
            editor.init(content);
        }
        return false;
    }

    private boolean saveAsFile(EditorTab editor) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.setDialogTitle("Save script as");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

        FileFilter ff =
                new FileFilter() {
                    public String getDescription() {
                        return "q script";
                    }

                    public boolean accept(File file) {
                        if (file.isDirectory() || file.getName().endsWith(".q"))
                            return true;
                        else
                            return false;
                    }
                };

        chooser.addChoosableFileFilter(ff);

        chooser.setFileFilter(ff);

        String filename = editor.getFilename();
        if (filename != null) {
            File file = new File(filename);
            File dir = new File(file.getPath());
            chooser.setCurrentDirectory(dir);
            chooser.setSelectedFile(file);
        }

        int option = chooser.showSaveDialog(this);
        if (option != JFileChooser.APPROVE_OPTION) return false;
        File sf = chooser.getSelectedFile();
        filename = sf.getAbsolutePath();

        if (chooser.getFileFilter() == ff) {
            if (! sf.getName().endsWith(".q")) {
                filename = filename + ".q";
            }
        }

        if (new File(filename).exists()) {
            int choice = JOptionPane.showOptionDialog(frame,
                    filename + " already exists.\nOverwrite?",
                    "Overwrite?",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    Util.QUESTION_ICON,
                    null, // use standard button titles
                    null);      // no default selection

            if (choice != JOptionPane.YES_OPTION)
                return false;
        }

        editor.setFilename(filename);
        return saveFileOnDisk(editor);
    }

    private boolean saveEditor(EditorTab editor) {
        if (editor.getFilename() == null) {
            return saveAsFile(editor);
        } else {
            return saveFileOnDisk(editor);
        }
    }

    private void saveAll() {
        for (StudioPanel panel: allPanels) {
            int count = panel.tabbedEditors.getTabCount();
            for (int index=0; index<count; index++) {
                saveFileOnDisk(panel.getEditor(index));
            }
        }
    }

    // returns true if saved, false if error or cancelled
    private static boolean saveFileOnDisk(EditorTab editor) {
        String filename = editor.getFilename();
        if (filename == null) return false;

        try {
            FileReaderWriter.write(filename, editor.getTextArea().getText(), editor.getLineEnding());
            rebuildAll();
            editor.setModified(false);
            editor.getPanel().addToMruFiles(filename);
            return true;
        }
        catch (IOException e) {
            log.error("Error during saving file " + filename, e);
        }

        return false;
    }

    private void arrangeAll() {
        int noWins = allPanels.size();

        Iterator<StudioPanel> panelIterator = allPanels.iterator();

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();

        int noRows = Math.min(noWins, 3);
        int height = screenSize.height / noRows;

        for (int row = 0;row < noRows;row++) {
            int noCols = (noWins / 3);

            if ((row == 0) && ((noWins % 3) > 0))
                noCols++;
            else if ((row == 1) && ((noWins % 3) > 1))
                noCols++;

            int width = screenSize.width / noCols;

            for (int col = 0;col < noCols;col++) {
                StudioPanel panel = panelIterator.next();
                JFrame frame = panel.frame;

                frame.setSize(width,height);
                frame.setLocation(col * width,((noRows - 1) - row) * height);
                ensureDeiconified(frame);
            }
        }
    }

    private void setServer(Server server) {
        if (server == null) return;
        editor.setServer(server);

        if (!loading) {
            CONFIG.addServerToHistory(server);
            serverHistory.add(server);

            refreshTitle();
            rebuildAll();
        }
    }

    private void initActions() {
        cleanAction = UserAction.create("Clean", Util.NEW_DOCUMENT_ICON, "Clean editor script", KeyEvent.VK_N,
                null, e -> newFile());

        arrangeAllAction = UserAction.create(I18n.getString("ArrangeAll"), Util.BLANK_ICON, "Arrange all windows on screen",
                KeyEvent.VK_A, null, e -> arrangeAll());

        minMaxDividerAction = UserAction.create(I18n.getString("MaximizeEditorPane"), Util.BLANK_ICON, "Maximize editor pane",
                KeyEvent.VK_M, KeyStroke.getKeyStroke(KeyEvent.VK_M, menuShortcutKeyMask),
                e -> minMaxDivider());

        toggleDividerOrientationAction = UserAction.create(I18n.getString("ToggleDividerOrientation"), Util.BLANK_ICON,
                "Toggle the window divider's orientation", KeyEvent.VK_C, null, e -> toggleDividerOrientation());

        closeTabAction = UserAction.create("Close Tab", Util.BLANK_ICON, "Close current tab", KeyEvent.VK_W,
                KeyStroke.getKeyStroke(KeyEvent.VK_W, menuShortcutKeyMask), e -> closeTab());

        closeFileAction = UserAction.create("Close Window", Util.BLANK_ICON, "Close current window (close all tabs)",
                KeyEvent.VK_C, null, e -> closePanel());

        openFileAction = UserAction.create(I18n.getString("Open"), Util.FOLDER_ICON, "Open a script", KeyEvent.VK_O,
                KeyStroke.getKeyStroke(KeyEvent.VK_O, menuShortcutKeyMask), e -> openFile());

        newWindowAction = UserAction.create(I18n.getString("NewWindow"), Util.BLANK_ICON, "Open a new window",
                KeyEvent.VK_N, KeyStroke.getKeyStroke(KeyEvent.VK_N, menuShortcutKeyMask | InputEvent.SHIFT_MASK),
                e -> new StudioPanel().addTab(editor.getServer(), null) );

        newTabAction = UserAction.create("New Tab", Util.BLANK_ICON, "Open a new tab", KeyEvent.VK_T,
                KeyStroke.getKeyStroke(KeyEvent.VK_N, menuShortcutKeyMask),
                e -> addTab(editor.getServer(), null));

        serverListAction = UserAction.create(I18n.getString("ServerList"), Util.TEXT_TREE_ICON, "Show server list",
                KeyEvent.VK_L, KeyStroke.getKeyStroke(KeyEvent.VK_L, menuShortcutKeyMask | InputEvent.SHIFT_MASK),
                e -> showServerList(false));

        serverHistoryAction = UserAction.create("Server History", null, "Recent selected servers", KeyEvent.VK_R,
                KeyStroke.getKeyStroke(KeyEvent.VK_R, menuShortcutKeyMask | InputEvent.SHIFT_MASK),
                e -> showServerList(true));

        importFromQPadAction = UserAction.create("Import Servers from QPad...", null, "Import from Servers.cfg",
                KeyEvent.VK_I, null, e -> QPadImport.doImport(this));

        editServerAction = UserAction.create(I18n.getString("Edit"), Util.SERVER_INFORMATION_ICON, "Edit the server details",
                KeyEvent.VK_E, null, e -> {
                    Server s = new Server(editor.getServer());

                    EditServerForm f = new EditServerForm(frame, s);
                    f.alignAndShow();
                    if (f.getResult() == ACCEPTED) {
                        if (stopAction.isEnabled())
                            stopAction.actionPerformed(e);

                        ConnectionPool.getInstance().purge(editor.getServer());
                        CONFIG.removeServer(editor.getServer());

                        s = f.getServer();
                        CONFIG.addServer(s);
                        setServer(s);
                        rebuildAll();
                    }
                });


        addServerAction = UserAction.create(I18n.getString("Add"), Util.ADD_SERVER_ICON, "Configure a new server",
                KeyEvent.VK_A, null, e -> {
                    AddServerForm f = new AddServerForm(frame);
                    f.alignAndShow();
                    if (f.getResult() == ACCEPTED) {
                        Server s = f.getServer();
                        CONFIG.addServer(s);
                        ConnectionPool.getInstance().purge(s);   //?
                        setServer(s);
                        rebuildAll();
                    }
                });

        removeServerAction = UserAction.create(I18n.getString("Remove"), Util.DELETE_SERVER_ICON, "Remove this server",
                KeyEvent.VK_R, null, e -> {
                    int choice = JOptionPane.showOptionDialog(frame,
                            "Remove server " + editor.getServer().getFullName() + " from list?",
                            "Remove server?",
                            JOptionPane.YES_NO_CANCEL_OPTION,
                            JOptionPane.QUESTION_MESSAGE,
                            Util.QUESTION_ICON,
                            null, // use standard button titles
                            null);      // no default selection

                    if (choice == 0) {
                        CONFIG.removeServer(editor.getServer());

                        Server[] servers = CONFIG.getServers();

                        if (servers.length > 0)
                            setServer(servers[0]);

                        rebuildAll();
                    }
                });


        saveFileAction = UserAction.create(I18n.getString("Save"), Util.DISKS_ICON, "Save the script",
                KeyEvent.VK_S, KeyStroke.getKeyStroke(KeyEvent.VK_S, menuShortcutKeyMask),
                e -> saveEditor(editor));

        saveAllFilesAction = UserAction.create("Save All...", Util.BLANK_ICON, "Save all files",
                KeyEvent.VK_L, KeyStroke.getKeyStroke(KeyEvent.VK_S, menuShortcutKeyMask | InputEvent.SHIFT_MASK),
                e -> saveAll());

        saveAsFileAction = UserAction.create(I18n.getString("SaveAs"), Util.SAVE_AS_ICON, "Save script as",
                KeyEvent.VK_A, null, e -> saveAsFile(editor));

        exportAction = UserAction.create(I18n.getString("Export"), Util.EXPORT_ICON, "Export result set",
                KeyEvent.VK_E, null, e -> export());

        chartAction = UserAction.create(I18n.getString("Chart"), Util.CHART_ICON, "Chart current data set",
                KeyEvent.VK_E, null, e -> new Chart((KTableModel) getSelectedTable().getModel()));

        stopAction = UserAction.create(I18n.getString("Stop"), Util.STOP_ICON, "Stop the query",
                KeyEvent.VK_S, null, e -> editor.getQueryExecutor().cancel());

        openInExcel = UserAction.create(I18n.getString("OpenInExcel"), Util.EXCEL_ICON, "Open in Excel",
                KeyEvent.VK_O, null, e -> {
                    try {
                        File file = File.createTempFile("studioExport", ".xlsx");
                        new ExcelExporter().exportTableX(frame, getSelectedTable(), file, true);
                    } catch (IOException ex) {
                        log.error("Failed to create temporary file", ex);
                        JOptionPane.showMessageDialog(frame, "Failed to Open in Excel " + ex.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                    }
                });

        executeAction = UserAction.create(I18n.getString("Execute"), Util.TABLE_SQL_RUN_ICON, "Execute the full or highlighted text as a query",
                KeyEvent.VK_E, KeyStroke.getKeyStroke(KeyEvent.VK_E, menuShortcutKeyMask), e -> executeQuery());

        executeCurrentLineAction = UserAction.create(I18n.getString("ExecuteCurrentLine"), Util.RUN_ICON, "Execute the current line as a query",
                KeyEvent.VK_ENTER, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menuShortcutKeyMask), e -> executeQueryCurrentLine());

        refreshAction = UserAction.create(I18n.getString("Refresh"), Util.REFRESH_ICON, "Refresh the result set",
                KeyEvent.VK_R, KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuShortcutKeyMask | InputEvent.SHIFT_MASK), e -> refreshQuery());

        toggleCommaFormatAction = UserAction.create("Toggle Comma Format", Util. COMMA_ICON, "Add/remove thousands separator in selected result",
                KeyEvent.VK_F, KeyStroke.getKeyStroke(KeyEvent.VK_J, menuShortcutKeyMask),
                e -> {
                    TabPanel tab = (TabPanel) tabbedPane.getSelectedComponent();
                    if (tab != null) tab.toggleCommaFormatting();
                });

        aboutAction = UserAction.create(I18n.getString("About"), Util.ABOUT_ICON, "About Studio for kdb+",
                KeyEvent.VK_E, null, e -> about());

        exitAction = UserAction.create(I18n.getString("Exit"), Util.BLANK_ICON, "Close this window",
                KeyEvent.VK_X, e -> quit());

        settingsAction = UserAction.create("Settings", Util.BLANK_ICON, "Settings",
                KeyEvent.VK_S, null, e -> settings());

        codeKxComAction = UserAction.create("code.kx.com", Util.TEXT_ICON, "Open code.kx.com",
                KeyEvent.VK_C, null, e -> {
                    try {
                        BrowserLaunch.openURL("http://code.kx.com/q/");
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(null, "Error attempting to launch web browser:\n" + ex.getLocalizedMessage());
                    }
                });

        copyAction = UserAction.create(I18n.getString("Copy"), Util.COPY_ICON, "Copy the selected text to the clipboard",
                KeyEvent.VK_C, KeyStroke.getKeyStroke(KeyEvent.VK_C,menuShortcutKeyMask), editorCopyAction);

        cutAction = UserAction.create(I18n.getString("Cut"), Util.CUT_ICON, "Cut the selected text",
                KeyEvent.VK_T, KeyStroke.getKeyStroke(KeyEvent.VK_X,menuShortcutKeyMask), editorCutAction);

        pasteAction = UserAction.create(I18n.getString("Paste"), Util.PASTE_ICON, "Paste text from the clipboard",
                KeyEvent.VK_P, KeyStroke.getKeyStroke(KeyEvent.VK_V,menuShortcutKeyMask), editorPasteAction);

        findAction = UserAction.create(I18n.getString("Find"), Util.FIND_ICON, "Find text in the document",
                KeyEvent.VK_F, KeyStroke.getKeyStroke(KeyEvent.VK_F,menuShortcutKeyMask), editorFindAction);

        replaceAction = UserAction.create(I18n.getString("Replace"), Util.REPLACE_ICON, "Replace text in the document",
                KeyEvent.VK_R, KeyStroke.getKeyStroke(KeyEvent.VK_R,menuShortcutKeyMask), editorReplaceAction);

        selectAllAction = UserAction.create(I18n.getString("SelectAll"), "Select all text in the document",
                KeyEvent.VK_A, KeyStroke.getKeyStroke(KeyEvent.VK_A,menuShortcutKeyMask), editorSelectAllAction);

        undoAction = UserAction.create(I18n.getString("Undo"), Util.UNDO_ICON, "Undo the last change to the document",
                KeyEvent.VK_U, KeyStroke.getKeyStroke(KeyEvent.VK_Z,menuShortcutKeyMask), editorUndoAction);

        redoAction = UserAction.create(I18n.getString("Redo"), Util.REDO_ICON, "Redo the last change to the document",
                KeyEvent.VK_R, KeyStroke.getKeyStroke(KeyEvent.VK_Y,menuShortcutKeyMask), editorRedoAction);

        nextEditorTabAction = UserAction.create("Next tab", Util.BLANK_ICON,
                "Select next editor tab", KeyEvent.VK_N,
                    Util.MAC_OS_X ? KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, menuShortcutKeyMask | InputEvent.ALT_MASK ) :
                                    KeyStroke.getKeyStroke(KeyEvent.VK_TAB, menuShortcutKeyMask),
                e -> selectNextTab(true));

        prevEditorTabAction = UserAction.create("Previous tab", Util.BLANK_ICON,
                "Select previous editor tab", KeyEvent.VK_P,
                Util.MAC_OS_X ? KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, menuShortcutKeyMask | InputEvent.ALT_MASK ) :
                        KeyStroke.getKeyStroke(KeyEvent.VK_TAB, menuShortcutKeyMask | InputEvent.SHIFT_MASK),
                e -> selectNextTab(false));

        lineEndingActions = new UserAction[LineEnding.values().length];
        for(LineEnding lineEnding: LineEnding.values()) {
            lineEndingActions[lineEnding.ordinal()] = UserAction.create(lineEnding.getDescription(),
                e -> {
                    editor.setLineEnding(lineEnding);
                    refreshActionState();
                } );
        }

        wordWrapAction = UserAction.create("Word wrap", Util.BLANK_ICON, "Word wrap for all tabs",
                KeyEvent.VK_W, KeyStroke.getKeyStroke(KeyEvent.VK_W, menuShortcutKeyMask | InputEvent.SHIFT_MASK),
                e -> toggleWordWrap());
    }

    public void settings() {
        SettingsDialog dialog = new SettingsDialog(frame);
        dialog.alignAndShow();
        if (dialog.getResult() == CANCELLED) return;
        //@TODO Need rework - we are reading from Config inside SettingDialog; while saving happens outside
        String auth = dialog.getDefaultAuthenticationMechanism();
        CONFIG.setDefaultAuthMechanism(auth);
        CONFIG.setDefaultCredentials(auth, new Credentials(dialog.getUser(), dialog.getPassword()));
        CONFIG.setShowServerComboBox(dialog.isShowServerComboBox());
        CONFIG.setResultTabsCount(dialog.getResultTabsCount());
        CONFIG.setMaxCharsInResult(dialog.getMaxCharsInResult());
        CONFIG.setMaxCharsInTableCell(dialog.getMaxCharsInTableCell());
        CONFIG.setDouble(Config.CELL_RIGHT_PADDING, dialog.getCellRightPadding());
        CONFIG.setInt(Config.CELL_MAX_WIDTH, dialog.getCellMaxWidth());
        CONFIG.setExecAllOption(dialog.getExecAllOption());
        CONFIG.setBoolean(Config.SAVE_ON_EXIT, dialog.isSaveOnExit());
        CONFIG.setBoolean(Config.AUTO_SAVE, dialog.isAutoSave());
        CONFIG.setEnum(Config.DEFAULT_LINE_ENDING, dialog.getDefaultLineEnding());
        
        boolean changed = CONFIG.setBoolean(Config.RSTA_ANIMATE_BRACKET_MATCHING, dialog.isAnimateBracketMatching());
        changed |= CONFIG.setBoolean(Config.RSTA_HIGHLIGHT_CURRENT_LINE, dialog.isHighlightCurrentLine());
        changed |= CONFIG.setBoolean(Config.RSTA_WORD_WRAP, dialog.isWordWrap());

        if (changed) {
            refreshEditorsSettings();
        }

        String lfClass = dialog.getLookAndFeelClassName();
        if (!lfClass.equals(UIManager.getLookAndFeel().getClass().getName())) {
            CONFIG.setLookAndFeel(lfClass);
            JOptionPane.showMessageDialog(frame, "Look and Feel was changed. New L&F will take effect on the next start up.", "Look and Feel Setting Changed", JOptionPane.INFORMATION_MESSAGE);
        }

        rebuildToolbar();
    }

    private void toggleWordWrap() {
        boolean value = CONFIG.getBoolean(Config.RSTA_WORD_WRAP);
        CONFIG.setBoolean(Config.RSTA_WORD_WRAP, !value);
        refreshEditorsSettings();
        refreshActionState();
        rebuildAll();
    }

    private static void refreshEditorsSettings() {
        for (StudioPanel panel: allPanels) {
            int count = panel.tabbedEditors.getTabCount();
            for (int index=0; index<count; index++) {
                RSyntaxTextArea editor = panel.getEditor(index).getTextArea();
                editor.setHighlightCurrentLine(CONFIG.getBoolean(Config.RSTA_HIGHLIGHT_CURRENT_LINE));
                editor.setAnimateBracketMatching(CONFIG.getBoolean(Config.RSTA_ANIMATE_BRACKET_MATCHING));
                editor.setLineWrap(CONFIG.getBoolean(Config.RSTA_WORD_WRAP));
            }
        }
    }

    public static StudioPanel[] getPanels() {
        return allPanels.toArray(new StudioPanel[0]);
    }

    public void about() {
        HelpDialog help = new HelpDialog(frame);
        Util.centerChildOnParent(help,frame);
        // help.setTitle("About Studio for kdb+");
        help.pack();
        help.setVisible(true);
    }

    public boolean quit() {
        WorkspaceSaver.setEnabled(false);
        try {
            if (CONFIG.getBoolean(Config.SAVE_ON_EXIT)) {
                for (StudioPanel panel : allPanels.toArray(new StudioPanel[0])) {
                    panel.getFrame().toFront();
                    JTabbedPane tabbedEditors = panel.tabbedEditors;
                    int selectedTab = tabbedEditors.getSelectedIndex();
                    try {
                        int count = tabbedEditors.getTabCount();
                        for (int index = 0; index < count; index++) {
                            EditorTab editor = panel.getEditor(index);
                            if (editor.isModified()) {
                                tabbedEditors.setSelectedIndex(index);
                                if (!checkAndSaveTab(editor)) {
                                    return false;
                                }
                            }
                        }
                    } finally {
                        tabbedEditors.setSelectedIndex(selectedTab);
                    }
                }
            }
        } finally {
            frame.toFront();
            WorkspaceSaver.setEnabled(true);
        }
        WorkspaceSaver.save(getWorkspace());
        log.info("Shutting down");
        System.exit(0);
        return true;
    }

    private boolean closePanel() {
        // If this is the last window, we need to properly persist workspace
        if (allPanels.size() == 1) return quit();

        while (tabbedEditors.getTabCount() > 0) {
            if (! closeTab()) return false;
        }
        //closing the last tab would dispose the frame
        return true;
    }

    private boolean checkAndSaveTab(EditorTab editor) {
        if (! editor.isModified()) return true;

        int choice = JOptionPane.showOptionDialog(editor.getTextArea(),
                editor.getTitle() + " is changed. Save changes?","Save changes?",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, Util.QUESTION_ICON,
                null, // use standard button titles
                null);      // no default selection

        if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) return false;

        if (choice == JOptionPane.YES_OPTION) {
            return saveEditor(editor);
        }

        return true;
    }

    private void closeFrame() {
        frame.dispose();
        allPanels.remove(this);
        rebuildAll();
    }

    private void closeIfEmpty() {
        if (tabbedEditors.getTabCount()>0) return;
        closeFrame();
    }

    private boolean closeTab() {
        if (!checkAndSaveTab(editor)) return false;

        if (tabbedEditors.getTabCount() == 1 && allPanels.size() == 1) {
            WorkspaceSaver.save(getWorkspace());
            log.info("Closed the last tab. Shutting down");
            System.exit(0);
            return true;
        }

        tabbedEditors.remove(tabbedEditors.getSelectedIndex());
        closeIfEmpty();
        return true;
    }

    public static void rebuildAll() {
        for (StudioPanel panel: allPanels) {
            panel.rebuildMenuAndTooblar();
        }
    }

    private void rebuildMenuAndTooblar() {
        rebuildMenuBar();
        rebuildToolbar();
    }

    private void rebuildMenuBar() {
        if (loading) return;

        JMenuBar menubar = createMenuBar();
        frame.setJMenuBar(menubar);
        menubar.validate();
        menubar.repaint();
        frame.validate();
        frame.repaint();
    }

    private JMenuBar createMenuBar() {
        JMenuBar menubar = new JMenuBar();
        JMenu menu = new JMenu(I18n.getString("File"));
        menu.setMnemonic(KeyEvent.VK_F);
        menu.add(new JMenuItem(newWindowAction));
        menu.add(new JMenuItem(newTabAction));
        menu.add(new JMenuItem(openFileAction));
        menu.add(new JMenuItem(saveFileAction));
        menu.add(new JMenuItem(saveAsFileAction));
        menu.add(new JMenuItem(saveAllFilesAction));

        menu.add(new JMenuItem(closeTabAction));
        menu.add(new JMenuItem(closeFileAction));

        if (! Util.MAC_OS_X) {
            menu.add(new JMenuItem(settingsAction));
        }
        menu.addSeparator();
//        menu.add(new JMenuItem(importAction));
        menu.add(new JMenuItem(openInExcel));
        menu.addSeparator();
        menu.add(new JMenuItem(exportAction));
        menu.addSeparator();
        menu.add(new JMenuItem(chartAction));

        String[] mru = CONFIG.getMRUFiles();

        if (mru.length > 0) {
            menu.addSeparator();
            char[] mnems = "123456789".toCharArray();

            for (int i = 0;i < (mru.length > mnems.length ? mnems.length : mru.length);i++) {
                final String filename = mru[i];

                JMenuItem item = new JMenuItem("" + (i + 1) + " " + filename);
                item.setMnemonic(mnems[i]);
                item.setIcon(Util.BLANK_ICON);
                item.addActionListener(new ActionListener() {

                    public void actionPerformed(ActionEvent e) {
                        loadMRUFile(filename);
                    }
                });
                menu.add(item);
            }
        }

        if (! Util.MAC_OS_X) {
            menu.addSeparator();
            menu.add(new JMenuItem(exitAction));
        }
        menubar.add(menu);

        menu = new JMenu(I18n.getString("Edit"));
        menu.setMnemonic(KeyEvent.VK_E);
        menu.add(new JMenuItem(undoAction));
        menu.add(new JMenuItem(redoAction));
        menu.addSeparator();
        menu.add(new JMenuItem(cutAction));
        menu.add(new JMenuItem(copyAction));
        menu.add(new JMenuItem(pasteAction));
        menu.addSeparator();

        menu.add(new JCheckBoxMenuItem(wordWrapAction));

        JMenu lineEndingSubMenu = new JMenu("Line Ending");
        lineEndingSubMenu.setIcon(Util.BLANK_ICON);
        for (Action action: lineEndingActions) {
            lineEndingSubMenu.add(new JCheckBoxMenuItem(action));
        }
        menu.add(lineEndingSubMenu);

        menu.add(new JMenuItem(cleanAction));
        menu.add(new JMenuItem(selectAllAction));
        menu.addSeparator();
        menu.add(new JMenuItem(findAction));
        menu.add(new JMenuItem(replaceAction));
//        menu.addSeparator();
//        menu.add(new JMenuItem(editFontAction));
        menubar.add(menu);

        menu = new JMenu(I18n.getString("Server"));
        menu.setMnemonic(KeyEvent.VK_S);
        menu.add(new JMenuItem(addServerAction));
        menu.add(new JMenuItem(editServerAction));
        menu.add(new JMenuItem(removeServerAction));

        Server server = editor.getServer();
        Server[] servers = CONFIG.getServers();
        if (servers.length > 0) {
            JMenu subMenu = new JMenu(I18n.getString("Clone"));
            subMenu.setIcon(Util.DATA_COPY_ICON);

            int count = MAX_SERVERS_TO_CLONE;
            for (int i = 0;i < servers.length;i++) {
                final Server s = servers[i];
                if (!s.equals(server) && count <= 0) continue;
                count--;
                JMenuItem item = new JMenuItem(s.getFullName());
                item.addActionListener(new ActionListener() {

                    public void actionPerformed(ActionEvent e) {
                        Server clone = new Server(s);
                        clone.setName("Clone of " + clone.getName());

                        EditServerForm f = new EditServerForm(frame,clone);
                        f.alignAndShow();

                        if (f.getResult() == ACCEPTED) {
                            clone = f.getServer();
                            CONFIG.addServer(clone);
                            //ebuildToolbar();
                            setServer(clone);
                            ConnectionPool.getInstance().purge(clone); //?
                            rebuildAll();
                        }
                    }
                });

                subMenu.add(item);
            }

            menu.add(subMenu);
        }

        menu.addSeparator();
        menu.add(new JMenuItem(serverListAction));
        menu.add(new JMenuItem(serverHistoryAction));
        menu.add(new JMenuItem(importFromQPadAction));

        menubar.add(menu);

        menu = new JMenu(I18n.getString("Query"));
        menu.setMnemonic(KeyEvent.VK_Q);
        menu.add(new JMenuItem(executeCurrentLineAction));
        menu.add(new JMenuItem(executeAction));
        menu.add(new JMenuItem(stopAction));
        menu.add(new JMenuItem(refreshAction));
        menu.add(new JMenuItem(toggleCommaFormatAction));
        menubar.add(menu);

        menu = new JMenu(I18n.getString("Window"));
        menu.setMnemonic(KeyEvent.VK_W);

        menu.add(new JMenuItem(minMaxDividerAction));
        menu.add(new JMenuItem(toggleDividerOrientationAction));
        menu.add(new JMenuItem(arrangeAllAction));
        menu.add(new JMenuItem(nextEditorTabAction));
        menu.add(new JMenuItem(prevEditorTabAction));

        if (allPanels.size() > 0) {
            menu.addSeparator();

            int i = 0;
            for (StudioPanel panel: allPanels) {
                EditorTab editor = panel.editor;
                String t = "unknown";
                String filename = editor.getFilename();

                if (filename != null)
                    t = filename.replace('\\','/');

                if (editor.getServer() != null)
                    t = t + "[" + editor.getServer().getFullName() + "]";
                else
                    t = t + "[no server]";

                JMenuItem item = new JMenuItem("" + (i + 1) + " " + t);
                item.addActionListener(new ActionListener() {

                    public void actionPerformed(ActionEvent e) {
                        ensureDeiconified(panel.frame);
                    }
                });

                if (panel == this)
                    item.setIcon(Util.CHECK_ICON);
                else
                    item.setIcon(Util.BLANK_ICON);

                menu.add(item);
                i++;
            }
        }
        menubar.add(menu);
        menu = new JMenu(I18n.getString("Help"));
        menu.setMnemonic(KeyEvent.VK_H);
        menu.add(new JMenuItem(codeKxComAction));
        if (! Util.MAC_OS_X)
            menu.add(new JMenuItem(aboutAction));
        menubar.add(menu);

        return menubar;
    }

    private void ensureDeiconified(JFrame f) {
        int state = f.getExtendedState();
        state = state & ~Frame.ICONIFIED;
        f.setExtendedState(state);
        f.show();
    }

    private void selectConnectionString() {
        String connection = txtServer.getText().trim();
        if (connection.length() == 0) return;
        Server server = editor.getServer();
        if (server != null && server.getConnectionString().equals(connection)) return;

        try {
            setServer(CONFIG.getServerByConnectionString(connection));

            rebuildToolbar();
            toolbar.validate();
            toolbar.repaint();
        } catch (IllegalArgumentException e) {
            refreshConnection();
        }
    }

    private void showServerList(boolean selectHistory) {
        if (serverList == null) {
            serverList = new ServerList(frame);
        }
        serverList.updateServerTree(CONFIG.getServerTree(), editor.getServer());
        serverList.updateServerHistory(serverHistory);
        serverList.selectHistoryTab(selectHistory);
        serverList.setVisible(true);

        Rectangle bounds = serverList.getBounds();
        CONFIG.setBounds(Config.SERVER_LIST_BOUNDS, bounds);

        Server selectedServer = serverList.getSelectedServer();
        if (selectedServer == null || selectedServer.equals(editor.getServer())) return;

        setServer(selectedServer);
        rebuildToolbar();
    }


    private void selectServerName() {
        String selection = comboServer.getSelectedItem().toString();
        if(! CONFIG.getServerNames().contains(selection)) return;

        setServer(CONFIG.getServer(selection));
        rebuildToolbar();
        toolbar.validate();
        toolbar.repaint();
    }

    private void refreshConnection() {
        Server server = editor.getServer();
        if (server == null) {
            txtServer.setText("");
            txtServer.setToolTipText("Select connection details");
        } else {
            txtServer.setText(server.getConnectionString());
            txtServer.setToolTipText(server.getConnectionStringWithPwd());
        }
    }

    private void toolbarAddServerSelection() {
        Server server = editor.getServer();
        Collection<String> names = CONFIG.getServerNames();
        String name = server == null ? "" : server.getFullName();
        if (!names.contains(name)) {
            List<String> newNames = new ArrayList<>();
            newNames.add(name);
            newNames.addAll(names);
            names = newNames;
        }
        comboServer = new JComboBox<>(names.toArray(new String[0]));
        comboServer.setToolTipText("Select the server context");
        comboServer.setSelectedItem(name);
        comboServer.addActionListener(e->selectServerName());
        // Cut the width if it is too wide.
        comboServer.setMinimumSize(new Dimension(0, 0));
        comboServer.setVisible(CONFIG.isShowServerComboBox());

        txtServer = new JTextField(32);
        txtServer.addActionListener(e -> {
            selectConnectionString();
            editor.getTextArea().requestFocus();
        });
        txtServer.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                selectConnectionString();
            }
        });
        refreshConnection();

        toolbar.add(new JLabel(I18n.getString("Server")));
        toolbar.add(comboServer);
        toolbar.add(txtServer);
        toolbar.add(serverListAction);
        toolbar.addSeparator();
    }

    private void rebuildToolbar() {
        if (loading) return;

        if (toolbar != null) {
            toolbar.removeAll();
            toolbarAddServerSelection();

            toolbar.add(stopAction);
            toolbar.add(executeAction);
            toolbar.add(refreshAction);
            toolbar.addSeparator();

            toolbar.add(openFileAction);
            toolbar.add(saveFileAction);
            toolbar.add(saveAsFileAction);
            toolbar.addSeparator();
            toolbar.add(openInExcel);
            toolbar.addSeparator();
            toolbar.add(exportAction);
            toolbar.addSeparator();

            toolbar.add(chartAction);
            toolbar.addSeparator();

            toolbar.add(undoAction);
            toolbar.add(redoAction);
            toolbar.addSeparator();

            toolbar.add(cutAction);
            toolbar.add(copyAction);
            toolbar.add(pasteAction);

            toolbar.addSeparator();
            toolbar.add(findAction);

            toolbar.add(replaceAction);

            toolbar.addSeparator();
            toolbar.add(codeKxComAction);

            for (int j = 0;j < toolbar.getComponentCount();j++) {
                Component c = toolbar.getComponentAtIndex(j);

                if (c instanceof JButton) {
                    JButton btn = (JButton)c;
                    btn.setRequestFocusEnabled(false);
                    btn.setMnemonic(KeyEvent.VK_UNDEFINED);
                }
            }
            refreshActionState();
        }
    }

    private int dividerLastPosition; // updated from property change listener
    private void minMaxDivider(){
        //BasicSplitPaneDivider divider = ((BasicSplitPaneUI)splitpane.getUI()).getDivider();
        //((JButton)divider.getComponent(0)).doClick();
        //((JButton)divider.getComponent(1)).doClick();
        if(splitpane.getDividerLocation()>=splitpane.getMaximumDividerLocation()){
            // Minimize editor pane
            splitpane.getTopComponent().setMinimumSize(new Dimension());
            splitpane.getBottomComponent().setMinimumSize(null);
            splitpane.setDividerLocation(0.);
            splitpane.setResizeWeight(0.);
        }
        else if(splitpane.getDividerLocation()<=splitpane.getMinimumDividerLocation()){
            // Restore editor pane
            splitpane.getTopComponent().setMinimumSize(null);
            splitpane.getBottomComponent().setMinimumSize(null);
            splitpane.setResizeWeight(0.);
            // Could probably catch resize edge-cases etc in pce too
            if(dividerLastPosition>=splitpane.getMaximumDividerLocation()||dividerLastPosition<=splitpane.getMinimumDividerLocation())
                dividerLastPosition=splitpane.getMaximumDividerLocation()/2;
            splitpane.setDividerLocation(dividerLastPosition);
        }
        else{
            // Maximize editor pane
            splitpane.getBottomComponent().setMinimumSize(new Dimension());
            splitpane.getTopComponent().setMinimumSize(null);
            splitpane.setDividerLocation(splitpane.getOrientation()==VERTICAL_SPLIT?splitpane.getHeight()-splitpane.getDividerSize():splitpane.getWidth()-splitpane.getDividerSize());
            splitpane.setResizeWeight(1.);
        }
    }

    private void toggleDividerOrientation() {
        if (splitpane.getOrientation() == JSplitPane.VERTICAL_SPLIT) {
            splitpane.setOrientation(JSplitPane.HORIZONTAL_SPLIT);
            tabbedPane.setTabPlacement(JTabbedPane.LEFT);
        } else {
            splitpane.setOrientation(JSplitPane.VERTICAL_SPLIT);
            tabbedPane.setTabPlacement(JTabbedPane.TOP);
        }

        int count = tabbedPane.getTabCount();
        for (int index = 0; index<count; index++) {
            ((TabPanel)tabbedPane.getComponent(index)).updateToolbarLocation(tabbedPane);
        }

        splitpane.setDividerLocation(0.5);
    }

    private void selectNextTab(boolean forward) {
        int index = tabbedEditors.getSelectedIndex();
        int count = tabbedEditors.getTabCount();
        if (forward) {
            index++;
            if (index == count) index = 0;
        } else {
            index--;
            if (index == -1) index = count-1;
        }
        tabbedEditors.setSelectedIndex(index);
    }

    public EditorTab addTab(Server server, String filename) {
        editor = new EditorTab(this);
        JComponent editorPane = editor.getPane();
        JTextComponent textArea = editor.getTextArea();
        removeFocusChangeKeysForWindows(textArea);

        overrideDefaultKeymap(textArea, toggleCommaFormatAction, newTabAction, closeTabAction, nextEditorTabAction, prevEditorTabAction);
        editorPane.putClientProperty(EditorTab.class, editor);
        tabbedEditors.add(editorPane);
        tabbedEditors.setSelectedIndex(tabbedEditors.getTabCount()-1);
        setServer(server);

        if (filename != null) {
            loadFile(filename);
        } else {
            editor.setFilename(null);
            editor.init(Content.getEmpty());
        }
        textArea.getDocument().addDocumentListener(new MarkingDocumentListener(editor));
        textArea.requestFocus();
        refreshActionState();
        return editor;
    }

    private void refreshEditor() {
        if ( tabbedEditors.getSelectedIndex() == -1) return;
        editor = getEditor(tabbedEditors.getSelectedIndex());
        editor.setPanel(this);
        setServer(editor.getServer());
        lastQuery = null;
        refreshTitle();
        refreshActionState();
    }

    private void resultTabDragged(DragEvent event) {
        DraggableTabbedPane targetPane = event.getTargetPane();
        StudioPanel targetPanel = (StudioPanel) targetPane.getClientProperty(StudioPanel.class);
        ((TabPanel)targetPane.getComponentAt(event.getTargetIndex())).setPanel(targetPanel);
    }

    public StudioPanel() {
        registerForMacOSXEvents();
        initActions();
        serverHistory = new HistoricalList<>(CONFIG.getServerHistoryDepth(),
                CONFIG.getServerHistory());

        splitpane = new JSplitPane();
        frame = new JFrame();
        allPanels.add(this);

        tabbedEditors = new DraggableTabbedPane("Editor");
        removeFocusChangeKeysForWindows(tabbedEditors);
        ClosableTabbedPane.makeCloseable(tabbedEditors, index -> {
            tabbedEditors.setSelectedIndex(index);
            return closeTab();
        });
        tabbedEditors.addChangeListener(e -> refreshEditor() );
        tabbedEditors.addContainerListener(new ContainerListener() {
            @Override
            public void componentAdded(ContainerEvent e) {
                refreshEditor();
            }
            @Override
            public void componentRemoved(ContainerEvent e) {
                refreshEditor();
            }
        });
        tabbedEditors.addDragCompleteListener(success -> closeIfEmpty() );
        splitpane.setTopComponent(tabbedEditors);
        splitpane.setDividerLocation(0.5);

        toolbar = new JToolBar();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
        toolbar.setFloatable(false);


        tabbedPane = new DraggableTabbedPane("Result", JTabbedPane.TOP);
        ClosableTabbedPane.makeCloseable(tabbedPane, index -> {
            tabbedPane.removeTabAt(index);
            return true;
        });
        tabbedPane.addChangeListener(e->refreshActionState());
        tabbedPane.putClientProperty(StudioPanel.class, this);
        tabbedPane.addDragListener( evt -> resultTabDragged(evt));

        splitpane.setBottomComponent(tabbedPane);
        splitpane.setOneTouchExpandable(true);
        splitpane.setOrientation(JSplitPane.VERTICAL_SPLIT);
        try {
            Component divider = ((BasicSplitPaneUI) splitpane.getUI()).getDivider();

            divider.addMouseListener(new MouseAdapter() {

                public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2)
                        toggleDividerOrientation();
                }
            });
        }
        catch (ClassCastException e) {
        }
        splitpane.setContinuousLayout(true);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();

//        rebuildMenuAndTooblar();

        frame.getContentPane().add(toolbar,BorderLayout.NORTH);
        frame.getContentPane().add(splitpane,BorderLayout.CENTER);
        // frame.setSize(frame.getContentPane().getPreferredSize());

        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(this);
        frame.setSize((int) (0.8 * screenSize.width),
                (int) (0.8 * screenSize.height));

        frame.setLocation(((int) Math.max(0,(screenSize.width - frame.getWidth()) / 2.0)),
                (int) (Math.max(0,(screenSize.height - frame.getHeight()) / 2.0)));

        frame.setIconImage(Util.LOGO_ICON.getImage());

        //     frame.pack();
        frame.setVisible(true);
        splitpane.setDividerLocation(0.5);

        splitpane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,new PropertyChangeListener(){
            public void propertyChange(PropertyChangeEvent pce){
                String s=splitpane.getDividerLocation()>=splitpane.getMaximumDividerLocation()?I18n.getString("MinimizeEditorPane"):splitpane.getDividerLocation()<=splitpane.getMinimumDividerLocation()?I18n.getString("RestoreEditorPane"):I18n.getString("MaximizeEditorPane");
                minMaxDividerAction.putValue(Action.SHORT_DESCRIPTION,s);
                minMaxDividerAction.putValue(Action.NAME,s);
                if(splitpane.getDividerLocation()<splitpane.getMaximumDividerLocation()&&splitpane.getDividerLocation()>splitpane.getMinimumDividerLocation())
                    dividerLastPosition=splitpane.getDividerLocation();
            }
        });
        dividerLastPosition=splitpane.getDividerLocation();
    }

    public void update(Observable obs,Object obj) {
    }
    private static boolean registeredForMaxOSXEvents = false;

    public void registerForMacOSXEvents() {
        if (registeredForMaxOSXEvents)
            return;

        if (Util.MAC_OS_X)
            try {
                // Generate and register the OSXAdapter, passing it a hash of all the methods we wish to
                // use as delegates for various com.apple.eawt.ApplicationListener methods
                OSXAdapter.setQuitHandler(this, StudioPanel.class.getDeclaredMethod("quit"));
                OSXAdapter.setAboutHandler(this, StudioPanel.class.getDeclaredMethod("about"));
                OSXAdapter.setPreferencesHandler(this, StudioPanel.class.getDeclaredMethod("settings"));
                registeredForMaxOSXEvents = true;
            }
            catch (Exception e) {
                log.error("Failed to set MacOS handlers", e);
            }
    }

    private static Server getServer(Workspace.Tab tab) {
        Server server = null;
        String serverFullname = tab.getServerFullName();
        if (serverFullname != null) {
            server = CONFIG.getServer(serverFullname);
        }
        if (server != null) return server;

        String connectionString = tab.getServerConnection();
        if (connectionString != null) {
            server = CONFIG.getServerByConnectionString(connectionString);
        }
        if (server == null) server = new Server();

        String auth = tab.getServerAuth();
        if (auth == null) return server;

        if (AuthenticationManager.getInstance().lookup(auth) != null) {
            server.setAuthenticationMechanism(auth);
        }
        return server;
    }

    public static void loadWorkspace(Workspace workspace) {
        loading = true;
        for (Workspace.Window window: workspace.getWindows()) {
            Workspace.Tab[] tabs = window.getTabs();
            if (tabs.length == 0) {
                log.info("Strange: a window has zero tabs. Skipping initialization");
                continue;
            }

            StudioPanel panel = new StudioPanel();
            for (Workspace.Tab tab: tabs) {
                EditorTab editor = panel.addTab(getServer(tab), tab.getFilename());
                editor.init(Content.newContent(tab.getContent(), tab.getLineEnding()));
                editor.setModified(tab.isModified());
                int caretPosition = tab.getCaret();
                if (caretPosition>=0 && caretPosition < editor.getTextArea().getDocument().getLength()) {
                    editor.getTextArea().setCaretPosition(caretPosition);
                }
                editor.getTextArea().discardAllEdits();
            }
            if (window.getSelectedTab() != -1) {
                panel.tabbedEditors.setSelectedIndex(window.getSelectedTab());
            }
        }

        if (workspace.getSelectedWindow() != -1) {
            allPanels.get(workspace.getSelectedWindow()).frame.toFront();
        }

        loading = false;
        for (StudioPanel panel: allPanels) {
            panel.refreshTitle();
        }
        rebuildAll();
    }

    public void refreshQuery() {
        executeK4Query(lastQuery);
    }

    public void executeQueryCurrentLine() {
        executeQuery(getCurrentLineEditorText(editor.getTextArea()));
    }

    public void executeQuery() {
        executeQuery(getEditorText(editor.getTextArea()));
    }

    private void executeQuery(String text) {
        if (text == null) {
            return;
        }
        text = text.trim();
        if (text.length() == 0) {
            log.info("Nothing to execute - got empty string");
            return;
        }

        executeK4Query(text);
        lastQuery = text;
    }

    public JFrame getFrame() {
        return frame;
    }

    public Server getServer() {
        return editor.getServer();
    }

    private String getEditorText(JTextComponent editor) {
        String text = editor.getSelectedText();
        if (text != null) return text;

        Config.ExecAllOption option = CONFIG.getExecAllOption();
        if (option == Config.ExecAllOption.Ignore) {
            log.info("Nothing is selected. Ignore execution of the whole script");
            return null;
        }
        if (option == Config.ExecAllOption.Execute) {
            return editor.getText();
        }
        //Ask
        int result = JOptionPane.showOptionDialog(frame, "Nothing is selected. Execute the whole script?",
                "Execute All?", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
                Util.QUESTION_ICON,null, JOptionPane.NO_OPTION);

        if (result == JOptionPane.YES_OPTION ) {
            return editor.getText();
        }

        return null;
    }

    private String getCurrentLineEditorText(JTextComponent editor) {
        String newLine = "\n";
        String text = null;

        try {
            int pos = editor.getCaretPosition();
            int max = editor.getDocument().getLength();


            if ((max > pos) && (!editor.getText(pos,1).equals("\n"))) {
                String toeol = editor.getText(pos,max - pos);
                int eol = toeol.indexOf('\n');

                if (eol > 0)
                    pos = pos + eol;
                else
                    pos = max;
            }

            text = editor.getText(0,pos);

            int lrPos = text.lastIndexOf(newLine);

            if (lrPos >= 0) {
                lrPos += newLine.length(); // found it so skip it
                text = text.substring(lrPos,pos).trim();
            }
        }
        catch (BadLocationException e) {
        }

        if (text != null) {
            text = text.trim();

            if (text.length() == 0)
                text = null;
        }

        return text;
    }

    private JTable getSelectedTable() {
        TabPanel tab = (TabPanel) tabbedPane.getSelectedComponent();
        if (tab == null) return null;
        return tab.getTable();
    }

    private void executeK4Query(String text) {
        editor.getTextArea().setCursor(waitCursor);
        editor.setStatus("Executing: " + text);
        editor.getQueryExecutor().execute(text);
        refreshActionState();
    }

    void executeK4Query(K.KBase query) {
        editor.getTextArea().setCursor(waitCursor);
        editor.setStatus("Executing: " + query.toString());
        editor.getQueryExecutor().execute(query);
        refreshActionState();
    }

    private EditorTab getEditor(int index) {
        return (EditorTab) ((JComponent) tabbedEditors.getComponentAt(index)).getClientProperty(EditorTab.class);
    }

    // if the query is cancelled execTime=-1, result and error are null's
    public static void queryExecutionComplete(EditorTab editor, QueryResult queryResult) {
        JTextComponent textArea = editor.getTextArea();
        textArea.setCursor(textCursor);

        Throwable error = queryResult.getError();
        if (queryResult.isComplete()) {
            long execTime = queryResult.getExecutionTime();
            editor.setStatus("Last execution time: " + (execTime > 0 ? "" + execTime : "<1") + " mS");
        } else {
            editor.setStatus("Last query was cancelled");
        }

        StudioPanel panel = editor.getPanel();
        if (error != null && ! (error instanceof c.K4Exception)) {
            String message = error.getMessage();
            if ((message == null) || (message.length() == 0))
                message = "No message with exception. Exception is " + error.toString();
            JOptionPane.showMessageDialog(textArea,
                    "\nAn unexpected error occurred whilst communicating with " +
                            editor.getServer().getConnectionString() +
                            "\n\nError detail is\n\n" + message + "\n\n",
                    "Studio for kdb+",
                    JOptionPane.ERROR_MESSAGE,
                    Util.ERROR_ICON);
        } else if (queryResult.isComplete()) {
            JTabbedPane tabbedPane = panel.tabbedPane;
            TabPanel tab = new TabPanel(panel, queryResult);
            if(tabbedPane.getTabCount()>= CONFIG.getResultTabsCount()) {
                tabbedPane.remove(0);
            }
            tab.addInto(tabbedPane);
            tab.setToolTipText(editor.getServer().getConnectionString());
        }
        panel.refreshActionState();
    }

    public static Workspace getWorkspace() {
        Workspace workspace = new Workspace();
        Window activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();

        for (StudioPanel panel : allPanels) {
            Workspace.Window window = workspace.addWindow(panel.getFrame() == activeWindow);

            int count = panel.tabbedEditors.getTabCount();
            for (int index = 0; index < count; index++) {
                EditorTab editor = panel.getEditor(index);
                Server server = editor.getServer();
                String filename = editor.getFilename();
                boolean modified = editor.isModified();
                if (modified && CONFIG.getBoolean(Config.AUTO_SAVE)) {
                    panel.saveFileOnDisk(editor);
                }
                JTextComponent textArea = editor.getTextArea();

                window.addTab(index == panel.tabbedEditors.getSelectedIndex())
                        .addFilename(filename)
                        .addServer(server)
                        .addContent(textArea.getText())
                        .setCaret(textArea.getCaretPosition())
                        .setModified(modified)
                        .setLineEnding(editor.getLineEnding());
            }

        }
        return workspace;
    }

    public void windowClosing(WindowEvent e) {
        closePanel();
    }


    public void windowClosed(WindowEvent e) {
    }


    public void windowOpened(WindowEvent e) {
    }
    // ctrl-alt spacebar to minimize window

    public void windowIconified(WindowEvent e) {
    }


    public void windowDeiconified(WindowEvent e) {
    }


    public void windowActivated(WindowEvent e) {
        this.invalidate();
        SwingUtilities.updateComponentTreeUI(this);
    }


    public void windowDeactivated(WindowEvent e) {
    }

    private class MarkingDocumentListener implements DocumentListener {
        private final EditorTab editor;
        public MarkingDocumentListener(EditorTab editor) {
            this.editor = editor;
        }
        private void update() {
            editor.setModified(true);
            refreshActionState();
        }
        public void changedUpdate(DocumentEvent evt) { update(); }
        public void insertUpdate(DocumentEvent evt) {
            update();
        }
        public void removeUpdate(DocumentEvent evt) {
            update();
        }
    }
}
