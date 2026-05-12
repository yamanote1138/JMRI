package jmri.jmrix.dccpp.swing.virtuallcd;

import jmri.jmrix.dccpp.*;
import jmri.util.JUnitUtil;
import jmri.util.JmriJFrameTestBase;
import jmri.util.ThreadingUtil;
import jmri.util.junit.annotations.DisabledIfHeadless;

import org.junit.jupiter.api.*;

/**
 * Tests for {@link VirtualLCDFrame}.
 */
public class VirtualLCDFrameTest extends JmriJFrameTestBase {

    private DCCppSystemConnectionMemo memo = null;

    @Test
    public void testPadToCellWidth_shortPadsWithSpaces() {
        String padded = VirtualLCDFrame.padToCellWidth("PWR On", VirtualLCDFrame.CELL_COLS);
        Assertions.assertEquals(VirtualLCDFrame.CELL_COLS, padded.length());
        Assertions.assertEquals("PWR On" + " ".repeat(VirtualLCDFrame.CELL_COLS - 6), padded);
    }

    @Test
    public void testPadToCellWidth_exactLengthUnchanged() {
        String exact = "A".repeat(VirtualLCDFrame.CELL_COLS);
        Assertions.assertEquals(exact, VirtualLCDFrame.padToCellWidth(exact, VirtualLCDFrame.CELL_COLS));
    }

    @Test
    public void testPadToCellWidth_longTruncated() {
        String tooLong = "B".repeat(VirtualLCDFrame.CELL_COLS + 8);
        String padded = VirtualLCDFrame.padToCellWidth(tooLong, VirtualLCDFrame.CELL_COLS);
        Assertions.assertEquals(VirtualLCDFrame.CELL_COLS, padded.length());
        Assertions.assertEquals("B".repeat(VirtualLCDFrame.CELL_COLS), padded);
    }

    @Test
    public void testPadToCellWidth_emptyIsAllSpaces() {
        Assertions.assertEquals(" ".repeat(VirtualLCDFrame.CELL_COLS),
                VirtualLCDFrame.padToCellWidth("", VirtualLCDFrame.CELL_COLS));
    }

    @Test
    public void testPadToCellWidth_respectsWidthParameter() {
        Assertions.assertEquals("hi   ", VirtualLCDFrame.padToCellWidth("hi", 5));
        Assertions.assertEquals("hello", VirtualLCDFrame.padToCellWidth("hello world", 5));
    }

    @DisabledIfHeadless
    @Test
    public void testLCDReplyUpdatesPaddedLabelText() {
        VirtualLCDFrame f = (VirtualLCDFrame) frame;
        ThreadingUtil.runOnGUI(f::initComponents);

        f.message(DCCppReply.parseDCCppReply("@ 0 3 \"Free RAM= 136Kb\""));

        String text = f.lines.get(3).getText();
        Assertions.assertEquals(VirtualLCDFrame.CELL_COLS, text.length(),
                "row text should be padded to the current column count");
        Assertions.assertTrue(text.startsWith("Free RAM= 136Kb"),
                "row text should start with the inbound message contents");
    }

    @DisabledIfHeadless
    @Test
    public void testInitialPaneMatchesDefaults() {
        VirtualLCDFrame f = (VirtualLCDFrame) frame;
        ThreadingUtil.runOnGUI(f::initComponents);

        Assertions.assertEquals(VirtualLCDFrame.DEFAULT_VISIBLE_ROWS, f._currentRows);
        Assertions.assertEquals(VirtualLCDFrame.CELL_COLS, f._currentCols);
        Assertions.assertEquals(VirtualLCDFrame.DEFAULT_VISIBLE_ROWS, f._pane.getComponentCount());
    }

    @DisabledIfHeadless
    @Test
    public void testApplyDimensionsResizesPane() {
        VirtualLCDFrame f = (VirtualLCDFrame) frame;
        ThreadingUtil.runOnGUI(f::initComponents);

        ThreadingUtil.runOnGUI(() -> f.applyDimensions(2, 16));

        Assertions.assertEquals(2, f._currentRows);
        Assertions.assertEquals(16, f._currentCols);
        Assertions.assertEquals(2, f._pane.getComponentCount(),
                "pane should hold exactly the configured number of rows");
    }

    @DisabledIfHeadless
    @Test
    public void testApplyDimensionsRepadsExistingText() {
        VirtualLCDFrame f = (VirtualLCDFrame) frame;
        ThreadingUtil.runOnGUI(f::initComponents);

        f.message(DCCppReply.parseDCCppReply("@ 0 0 \"hello\""));
        ThreadingUtil.runOnGUI(() -> f.applyDimensions(2, 16));

        String text = f.lines.get(0).getText();
        Assertions.assertEquals(16, text.length(),
                "row 0 text should be re-padded to the new column count");
        Assertions.assertTrue(text.startsWith("hello"));
    }

    @DisabledIfHeadless
    @Test
    public void testDefaultMenuItemsAreBold() {
        VirtualLCDFrame f = (VirtualLCDFrame) frame;
        ThreadingUtil.runOnGUI(f::initComponents);

        javax.swing.JMenuBar bar = f.getJMenuBar();
        Assertions.assertNotNull(bar, "Options menu bar should be installed");
        javax.swing.JMenu options = bar.getMenu(0);
        Assertions.assertEquals(2, options.getMenuComponentCount(),
                "Options menu should contain Screen size and Font size submenus");

        assertGroupHasBoldDefault((javax.swing.JMenu) options.getMenuComponent(0));
        assertGroupHasBoldDefault((javax.swing.JMenu) options.getMenuComponent(1));
    }

    private static void assertGroupHasBoldDefault(javax.swing.JMenu submenu) {
        boolean foundBoldDefault = false;
        for (int i = 0; i < submenu.getMenuComponentCount(); i++) {
            java.awt.Component c = submenu.getMenuComponent(i);
            if (c instanceof javax.swing.JMenuItem) {
                javax.swing.JMenuItem item = (javax.swing.JMenuItem) c;
                if (item.getText() != null && item.getText().contains("(default)")) {
                    Assertions.assertTrue(item.getFont().isBold(),
                            submenu.getText() + " default item should be bold: " + item.getText());
                    foundBoldDefault = true;
                }
            }
        }
        Assertions.assertTrue(foundBoldDefault,
                submenu.getText() + " should contain a (default) item");
    }

    @DisabledIfHeadless
    @Test
    public void testApplyMagnificationScalesFontSize() {
        VirtualLCDFrame f = (VirtualLCDFrame) frame;
        ThreadingUtil.runOnGUI(f::initComponents);

        float originalSize = f.lines.get(0).getFont().getSize2D();
        ThreadingUtil.runOnGUI(() -> f.applyMagnification(2.0));
        float doubled = f.lines.get(0).getFont().getSize2D();
        Assertions.assertEquals(originalSize * 2.0f, doubled, 0.01f,
                "2x magnification should double the font size");

        ThreadingUtil.runOnGUI(() -> f.applyMagnification(0.5));
        float halved = f.lines.get(0).getFont().getSize2D();
        Assertions.assertEquals(originalSize * 0.5f, halved, 0.01f,
                "0.5x magnification should halve the font size");
    }

    @DisabledIfHeadless
    @Test
    public void testApplyDimensionsClampsToBounds() {
        VirtualLCDFrame f = (VirtualLCDFrame) frame;
        ThreadingUtil.runOnGUI(f::initComponents);

        ThreadingUtil.runOnGUI(() -> f.applyDimensions(0, 999));
        Assertions.assertEquals(VirtualLCDFrame.MIN_ROWS, f._currentRows, "rows below MIN should clamp");
        Assertions.assertEquals(VirtualLCDFrame.MAX_COLS, f._currentCols, "cols above MAX should clamp");

        ThreadingUtil.runOnGUI(() -> f.applyDimensions(999, 0));
        Assertions.assertEquals(VirtualLCDFrame.MAX_ROWS, f._currentRows, "rows above MAX should clamp");
        Assertions.assertEquals(VirtualLCDFrame.MIN_COLS, f._currentCols, "cols below MIN should clamp");
    }

    @DisabledIfHeadless
    @Test
    public void testFrameIsNotResizable() {
        VirtualLCDFrame f = (VirtualLCDFrame) frame;
        ThreadingUtil.runOnGUI(f::initComponents);
        Assertions.assertFalse(f.isResizable(), "window should be fixed-size; user shouldn't be able to drag it");
    }

    @Test
    public void testFrameDoesNotSaveOrRestoreSize() {
        VirtualLCDFrame f = (VirtualLCDFrame) frame;
        Assertions.assertFalse(f.getSaveSize(),
                "size restoration must be off so saved sessions don't override pack()");
    }

    @Test
    public void testFrameDoesNotSaveOrRestorePosition() {
        VirtualLCDFrame f = (VirtualLCDFrame) frame;
        Assertions.assertFalse(f.getSavePosition(),
                "position restoration must be off so the window doesn't land in the top-left from stale prefs");
    }

    @DisabledIfHeadless
    @Test
    public void testDisplayNumberOtherThanZeroIgnored() {
        VirtualLCDFrame f = (VirtualLCDFrame) frame;
        ThreadingUtil.runOnGUI(f::initComponents);

        String before = f.lines.get(0).getText();
        f.message(DCCppReply.parseDCCppReply("@ 1 0 \"display one\""));

        Assertions.assertEquals(before, f.lines.get(0).getText(),
                "row 0 of display 0 should be untouched by a reply for display 1");
    }

    @BeforeEach
    @Override
    public void setUp() {
        JUnitUtil.setUp();
        DCCppInterfaceScaffold t = new DCCppInterfaceScaffold(new DCCppCommandStation());
        memo = new DCCppSystemConnectionMemo(t);
        frame = new VirtualLCDFrame(memo);
    }

    @AfterEach
    @Override
    public void tearDown() {
        Assertions.assertNotNull(memo);
        memo.getDCCppTrafficController().terminateThreads();
        memo.dispose();
        memo = null;
        super.tearDown();
    }

}
