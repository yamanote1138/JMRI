package jmri.jmrix.dccpp.swing.virtuallcd;

import java.awt.*;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

import javax.swing.*;

import jmri.jmrix.ConnectionStatus;
import jmri.jmrix.dccpp.*;
import jmri.util.JmriJFrame;

/**
 * Frame to image the DCC-EX command station's OLED display
 *   Also sends request to DCC-EX to send copies of all LCD messages to this instance of JMRI
 *
 * @author BobJacobsen  Copyright (C) 2023
 * @author MSteveTodd   Copyright (C) 2023
 */
public class VirtualLCDFrame extends JmriJFrame implements DCCppListener  {

    private final DCCppTrafficController _tc;
    private final DCCppSystemConnectionMemo _memo;
    private final PropertyChangeListener _listener;

    static final int TOTALLINES = 64;
    static final int DEFAULT_VISIBLE_ROWS = 8;   // SSD1306 128x64 default grid
    static final int CELL_COLS = 22;             // SSD1306 char-grid width (128px / 6px font)
    // Bounds reflect realistic DCC-EX hardware: 16x2/20x4 LCDs, 22x4/22x8 OLEDs.
    static final int MIN_ROWS = 2;
    static final int MAX_ROWS = 8;
    static final int MIN_COLS = 16;
    static final int MAX_COLS = 22;
    private static final Color OLED_FG = new Color(90, 180, 255); // OLED blue
    private static final int PANE_PADDING = 5;   // pixels around the LCD pane

    private static final class DisplayPreset {
        final String displayName;
        final int rows;
        final int cols;
        final boolean isDefault;
        DisplayPreset(String displayName, int rows, int cols, boolean isDefault) {
            this.displayName = displayName;
            this.rows = rows;
            this.cols = cols;
            this.isDefault = isDefault;
        }
        String displayName() { return displayName; }
        int rows() { return rows; }
        int cols() { return cols; }
        boolean isDefault() { return isDefault; }
    }

    private static final List<DisplayPreset> DISPLAY_PRESETS = List.of(
            new DisplayPreset("LCD 16x2",  2, 16, false),
            new DisplayPreset("LCD 20x4",  4, 20, false),
            new DisplayPreset("OLED 22x4", 4, 22, false),
            new DisplayPreset("OLED 22x8", 8, 22, true));

    private static final float BASE_FONT_SIZE = 16f;

    private static final class MagnifyPreset {
        final String label;
        final double ratio;
        final boolean isDefault;
        MagnifyPreset(String label, double ratio, boolean isDefault) {
            this.label = label;
            this.ratio = ratio;
            this.isDefault = isDefault;
        }
        String label() { return label; }
        double ratio() { return ratio; }
        boolean isDefault() { return isDefault; }
    }

    private static final List<MagnifyPreset> MAGNIFY_PRESETS = List.of(
            new MagnifyPreset("50%",  0.5, false),
            new MagnifyPreset("100%", 1.0, true),
            new MagnifyPreset("200%", 2.0, false),
            new MagnifyPreset("300%", 3.0, false));

    final ArrayList<JLabel> lines;
    final JPanel _pane = new JPanel();
    int _currentRows = DEFAULT_VISIBLE_ROWS;
    int _currentCols = CELL_COLS;
    private Font _baseFont;          // 1x BOLD font; magnification derives from this
    private Dimension _lockedSize;   // null while applyDimensions is repacking

    public VirtualLCDFrame(DCCppSystemConnectionMemo memo) {
        // The window is auto-sized to the chosen LCD dimensions on every open
        // and centered on screen — saved size/position from prior sessions
        // would override pack() and could land the title bar off-screen.
        super(false, false);
        _tc = memo.getDCCppTrafficController();
        _memo = memo;
        _tc.sendDCCppMessage(DCCppMessage.makeLCDRequestMsg(), null);
        lines = new ArrayList<>(TOTALLINES + 1);

        _listener = evt -> {
            if (ConnectionStatus.CONNECTION_UP.equals(
                    ConnectionStatus.instance().getConnectionState(memo))) {
                _tc.sendDCCppMessage(DCCppMessage.makeLCDRequestMsg(), null);
            }
        };
        ConnectionStatus.instance().addPropertyChangeListener(_memo, _listener);
    }

    @Override
    public void dispose() {
        ConnectionStatus.instance().removePropertyChangeListener(_memo, _listener);
        super.dispose();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void message(DCCppMessage msg) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void message(DCCppReply msg) {
        if (msg.isLCDTextReply()) { // <@ display# line# "message text">
            int displayNumber = msg.getLCDDisplayNumInt();
            if (displayNumber == 0) {  //TODO: add support for multiple LCD displays
                int lineNumber = msg.getLCDLineNumInt();
                if (lineNumber < TOTALLINES) {
                    lines.get(lineNumber).setText(padToCellWidth(msg.getLCDTextString(), _currentCols));
                } else {
                    log.warn("Received LCD message for line {}, but configured for TOTALLINES limit of {}",
                                lineNumber, TOTALLINES-1);
                }
                log.debug("Received LCD message for display# {}, only display 0 supported at this time.", displayNumber);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void notifyTimeout(DCCppMessage msg) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initComponents() {
        super.initComponents();
        getContentPane().setLayout(new BoxLayout(getContentPane(), BoxLayout.Y_AXIS));

        Font font = null;
        try (InputStream stream = new FileInputStream(new File("resources/fonts/5x8_lcd_hd44780u_a02.ttf"))) {
            font = Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont(BASE_FONT_SIZE).deriveFont(Font.BOLD);
        } catch (IOException | FontFormatException e) {
            log.error("failed to load LCD font: {}", e.getMessage());
        }
        Font useFont = (font != null) ? font : new JLabel().getFont();
        _baseFont = useFont;

        _pane.setLayout(new BoxLayout(_pane, BoxLayout.Y_AXIS));
        _pane.setOpaque(true);
        _pane.setBackground(Color.BLACK);
        _pane.setBorder(BorderFactory.createEmptyBorder(PANE_PADDING, PANE_PADDING, PANE_PADDING, PANE_PADDING));

        for (int i = 0; i < TOTALLINES; i++) {
            var label = new JLabel();
            label.setFont(useFont);
            label.setOpaque(true);
            label.setBackground(Color.BLACK);
            label.setForeground(OLED_FG);
            lines.add(label);
        }

        this.add(_pane);

        setJMenuBar(buildOptionsMenuBar());
        setResizable(false);

        // Wayland compositors often ignore setResizable(false); this listener
        // snaps the frame back to the packed size if the user drags an edge.
        // _lockedSize is nulled during applyDimensions so programmatic resize
        // (preset switches) can still grow or shrink the window.
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (_lockedSize != null && !getSize().equals(_lockedSize)) {
                    setSize(_lockedSize);
                }
            }
        });

        // set the title, include prefix in event of multiple connections
        setTitle(Bundle.getMessage("VirtualLCDFrameTitle") + " (" + _memo.getSystemPrefix() + ")");

        // Apply the default preset (OLED 22x8): sets cell sizes, fills the pane, packs.
        applyDimensions(DEFAULT_VISIBLE_ROWS, CELL_COLS);

        // Center on screen so the title bar isn't clipped against the top-left.
        setLocationRelativeTo(null);
    }

    static String padToCellWidth(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    void applyMagnification(double ratio) {
        if (_baseFont != null) {
            Font scaled = _baseFont.deriveFont((float)(BASE_FONT_SIZE * ratio));
            for (JLabel label : lines) {
                label.setFont(scaled);
            }
        }
        // Re-run dimensions to recompute cell sizes from the new font metrics and repack.
        applyDimensions(_currentRows, _currentCols);
    }

    void applyDimensions(int rows, int cols) {
        rows = Math.max(MIN_ROWS, Math.min(rows, MAX_ROWS));
        cols = Math.max(MIN_COLS, Math.min(cols, MAX_COLS));
        _currentRows = rows;
        _currentCols = cols;

        // Rebuild pane with the right number of rows.
        _pane.removeAll();
        for (int i = 0; i < rows; i++) {
            _pane.add(lines.get(i));
        }

        // Recompute cell footprint and re-pad existing text to the new width.
        FontMetrics fm = getFontMetrics(lines.get(0).getFont());
        Dimension cellSize = new Dimension(fm.stringWidth("W".repeat(cols)), fm.getHeight());
        for (JLabel label : lines) {
            label.setPreferredSize(cellSize);
            label.setMinimumSize(cellSize);
            label.setMaximumSize(cellSize);
            String text = label.getText();
            if (text != null && !text.isEmpty()) {
                label.setText(padToCellWidth(text.replaceAll("\\s+$", ""), cols));
            }
        }

        _pane.revalidate();
        // Disarm the snap-back so pack() can change the window size, then
        // re-arm it with the new packed dimensions as the target.
        _lockedSize = null;
        pack();
        _lockedSize = getSize();
    }

    private JMenuBar buildOptionsMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu options = new JMenu(Bundle.getMessage("VirtualLCDMenuOptions"));
        options.add(buildScreenSizeMenu());
        options.add(buildFontSizeMenu());
        bar.add(options);
        return bar;
    }

    private JMenu buildScreenSizeMenu() {
        JMenu menu = new JMenu(Bundle.getMessage("VirtualLCDMenuScreenSize"));
        List<JMenuItem> group = new ArrayList<>();
        for (DisplayPreset preset : DISPLAY_PRESETS) {
            JMenuItem item = makeGroupMenuItem(preset.displayName(), preset.isDefault(), group);
            item.addActionListener(e -> {
                applyDimensions(preset.rows(), preset.cols());
                selectMenuItem(item, group);
            });
            menu.add(item);
        }
        JMenuItem other = makeGroupMenuItem(Bundle.getMessage("VirtualLCDMenuOther"), false, group);
        other.addActionListener(e -> {
            if (showCustomDimensionsDialog()) {
                selectMenuItem(other, group);
            }
        });
        menu.add(other);
        return menu;
    }

    private JMenu buildFontSizeMenu() {
        JMenu menu = new JMenu(Bundle.getMessage("VirtualLCDMenuFontSize"));
        List<JMenuItem> group = new ArrayList<>();
        for (MagnifyPreset preset : MAGNIFY_PRESETS) {
            JMenuItem item = makeGroupMenuItem(preset.label(), preset.isDefault(), group);
            item.addActionListener(e -> {
                applyMagnification(preset.ratio());
                selectMenuItem(item, group);
            });
            menu.add(item);
        }
        return menu;
    }

    private static JMenuItem makeGroupMenuItem(String text, boolean isDefault, List<JMenuItem> group) {
        String label = isDefault ? text + " " + Bundle.getMessage("VirtualLCDMenuDefaultSuffix") : text;
        JMenuItem item = new JMenuItem(label);
        if (isDefault) {
            item.setFont(item.getFont().deriveFont(Font.BOLD));
        }
        group.add(item);
        return item;
    }

    private static void selectMenuItem(JMenuItem selected, List<JMenuItem> group) {
        for (JMenuItem m : group) {
            m.setFont(m.getFont().deriveFont(m == selected ? Font.BOLD : Font.PLAIN));
        }
    }

    private boolean showCustomDimensionsDialog() {
        JSpinner rowsSpinner = new JSpinner(new SpinnerNumberModel(_currentRows, MIN_ROWS, MAX_ROWS, 1));
        JSpinner colsSpinner = new JSpinner(new SpinnerNumberModel(_currentCols, MIN_COLS, MAX_COLS, 1));
        JPanel p = new JPanel(new GridLayout(2, 2, 5, 5));
        p.add(new JLabel(Bundle.getMessage("VirtualLCDDialogRows")));
        p.add(rowsSpinner);
        p.add(new JLabel(Bundle.getMessage("VirtualLCDDialogCols")));
        p.add(colsSpinner);
        int result = JOptionPane.showConfirmDialog(this, p,
                Bundle.getMessage("VirtualLCDDialogCustomTitle"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            applyDimensions((Integer) rowsSpinner.getValue(), (Integer) colsSpinner.getValue());
            return true;
        }
        return false;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VirtualLCDFrame.class);

}
